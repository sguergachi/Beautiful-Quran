"""The compositor: a recorded take, in a Pixel frame, over a blurred world.

Every output frame is a pure function of (spec, t), so the editor's exact
preview, a single-frame check and the parallel export all agree:

    background plate (blurred, graded)  -> follows the camera by `parallax`
    soft shadow of the phone            -> under the body
    phone = screen (the take) + frame   -> composed at the take's resolution,
                                           area-filtered down to the output
                                           size (no shimmer on text), then
                                           placed by the camera
    captions / end card                 -> screen space, not moved by camera

Camera moves are eased keyframes (spec.camera). When the phone moves fast
between frames, several sub-frames across the shutter are averaged: real
motion blur on zooms and whips, none when the camera is still.
"""
import json
import math
import os
import subprocess
import time
from concurrent.futures import ProcessPoolExecutor

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

import library
import phone as phone_mod
from spec import CACHE, OUT, REPO, size, take_dir

cv2.setNumThreads(2)


# ---------------------------------------------------------------- easing

def ease(name, u):
    u = min(1.0, max(0.0, u))
    if name == "linear":
        return u
    if name == "hold":
        return 0.0 if u < 1.0 else 1.0
    if name == "inOutSine":
        return 0.5 - 0.5 * math.cos(math.pi * u)
    if name == "outCubic":
        return 1 - (1 - u) ** 3
    if name == "inCubic":
        return u ** 3
    if name == "outExpo":
        return 1.0 if u >= 1 else 1 - 2 ** (-10 * u)
    return 4 * u ** 3 if u < 0.5 else 1 - (-2 * u + 2) ** 3 / 2      # inOutCubic


def camera_at(keys, t):
    """(zoom, x, y, rot) at video time t. Zoom is interpolated in log space."""
    def val(k):
        return (float(k.get("zoom", 1)), float(k.get("x", 0.5)), float(k.get("y", 0.5)), float(k.get("rot", 0)))
    if t <= keys[0]["t"]:
        return val(keys[0])
    for a, b in zip(keys, keys[1:]):
        if a["t"] <= t <= b["t"]:
            span = max(1e-6, b["t"] - a["t"])
            e = ease(b.get("ease", "inOutCubic"), (t - a["t"]) / span)
            za, xa, ya, ra = val(a)
            zb, xb, yb, rb = val(b)
            z = math.exp(math.log(za) + (math.log(zb) - math.log(za)) * e)
            return z, xa + (xb - xa) * e, ya + (yb - ya) * e, ra + (rb - ra) * e
    return val(keys[-1])


# ---------------------------------------------------------------- fonts

def font_path(name):
    fonts = os.path.join(REPO, "web", "public", "fonts")
    if name.endswith(".woff2"):
        out = os.path.join(CACHE, "fonts", name.replace(".woff2", ".ttf"))
        if not os.path.exists(out):
            from fontTools.ttLib import TTFont
            os.makedirs(os.path.dirname(out), exist_ok=True)
            f = TTFont(os.path.join(fonts, name))
            f.flavor = None
            f.save(out)
        return out
    return os.path.join(fonts, name)


def text_layer(W, H, text, font, size_px, y, color, shadow=0.35, max_w=0.86):
    """RGBA float32 (premultiplied) layer holding one centred caption."""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    f = ImageFont.truetype(font_path(font), size_px)
    d = ImageDraw.Draw(img)
    # wrap to max_w of the frame
    words, lines, cur = text.split(), [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if d.textlength(trial, font=f) > W * max_w and cur:
            lines.append(cur); cur = w
        else:
            cur = trial
    lines.append(cur)
    lh = int(size_px * 1.18)
    top = int(y - lh * len(lines) / 2)
    rgb = tuple(int(color.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ds = ImageDraw.Draw(sh)
    for i, line in enumerate(lines):
        x = (W - d.textlength(line, font=f)) / 2
        ds.text((x, top + i * lh + size_px * 0.04), line, font=f, fill=(0, 0, 0, int(255 * shadow)))
        d.text((x, top + i * lh), line, font=f, fill=rgb + (255,))
    s = np.asarray(sh, np.float32) / 255
    s = cv2.GaussianBlur(s, (0, 0), size_px * 0.18)
    t_ = np.asarray(img, np.float32) / 255
    a = t_[..., 3:4] + s[..., 3:4] * (1 - t_[..., 3:4])
    rgb_p = t_[..., :3] * t_[..., 3:4]
    return np.dstack([rgb_p, a])


# ---------------------------------------------------------------- renderer

class Renderer:
    def __init__(self, s, scale=1.0):
        self.s = s
        W, H = size(s)
        self.W, self.H = int(round(W * scale / 2) * 2), int(round(H * scale / 2) * 2)
        self.scale = scale
        self.fps = int(s["fps"])
        self.td = take_dir(s)
        meta_p = os.path.join(self.td, "meta.json")
        if not os.path.exists(meta_p):
            raise FileNotFoundError(f"no take for this spec yet ({self.td}); run: studio.py capture {s['name']}")
        self.meta = json.load(open(meta_p))
        self.clip = os.path.join(self.td, "clip.mp4")
        self.sw, self.sh = self.meta["width"], self.meta["height"]
        fp, mp, g = phone_mod.ensure(os.path.join(CACHE, "phone"), self.sw, self.sh, s["phone"]["color"])
        self.g = g
        fr = np.asarray(Image.open(fp), np.float32) / 255
        m = np.asarray(Image.open(mp), np.float32) / 255
        fa = fr[..., 3:4]
        self.Fp = fr[..., :3] * fa                                  # frame, premultiplied
        self.A = (m[..., None] * (1 - fa))                          # where the screen shows
        self.Pa = (self.A + fa)[..., 0]                             # phone alpha
        y0, x0 = int(round(g["sy0"])), int(round(g["sx0"]))
        self.scr = (slice(y0, y0 + self.sh), slice(x0, x0 + self.sw))
        # soft shadow from the body silhouette, kept at 1/4 resolution
        q = 4
        pad = int(g["H"] * 0.12)
        sil = np.pad(self.Pa, pad)
        small = cv2.resize(sil, (sil.shape[1] // q, sil.shape[0] // q), interpolation=cv2.INTER_AREA)
        self.shadow = cv2.GaussianBlur(small, (0, 0), g["H"] * 0.035 / q)
        self.shadow_q, self.shadow_pad = q, pad
        # base placement: phone body height = phone.height of the frame
        ph = s["phone"]
        self.s0 = ph["height"] * self.H / g["bh"]
        self.P0 = np.array([self.W / 2 + ph["x"] * self.W, self.H / 2 + ph["y"] * self.H])
        self.C0 = np.array([g["W"] / 2, g["H"] / 2])
        # background plate
        bg = dict(s["background"])
        self.plate = library.plate(bg, self.W, self.H)
        pw, ph_, self.pk = library.plate_geometry(bg, self.W, self.H)
        self.pw, self.ph = pw, ph_
        self.plate_len = library.probe(self.plate)[2]
        yy, xx = np.mgrid[0:self.H, 0:self.W].astype(np.float32)
        r = np.hypot((xx - self.W / 2) / (self.W / 2), (yy - self.H / 2) / (self.H / 2)) / math.sqrt(2)
        self.vig = (1 - bg["vignette"] * np.clip(r, 0, 1) ** 2)[..., None].astype(np.float32)
        self._alpha_cache = {}
        self.overlays = self._overlays()

    # -- geometry ---------------------------------------------------------

    def focus_canvas(self, fx, fy):
        g = self.g
        return np.array([g["sx0"] + fx * g["sw"], g["sy0"] + fy * g["sh"]])

    def phone_matrix(self, cam):
        """2x3 affine: phone canvas px -> output px."""
        z, fx, fy, rot = cam
        F = self.P0 + self.s0 * (self.focus_canvas(fx, fy) - self.C0)
        c, s_ = math.cos(math.radians(rot)), math.sin(math.radians(rot))
        R = np.array([[c, -s_], [s_, c]])
        L = R * (z * self.s0)                                   # canvas -> output linear part
        # out = R z (P0 + s0 (p - C0) - F) + P0
        t = R @ (z * (self.P0 - self.s0 * self.C0 - F)) + self.P0
        return np.hstack([L, t[:, None]])

    def bg_matrix(self, cam):
        z, fx, fy, rot = cam
        p = float(self.s["background"]["parallax"])
        zb = 1 + (z - 1) * p
        F = self.P0 + self.s0 * (self.focus_canvas(fx, fy) - self.C0)
        shift = (F - self.P0) * p
        c, s_ = math.cos(math.radians(rot * p)), math.sin(math.radians(rot * p))
        R = np.array([[c, -s_], [s_, c]])
        O = np.array([self.W / 2, self.H / 2])
        Bc = np.array([self.pw / 2, self.ph / 2])
        L = R * (zb / self.pk)
        t = R @ (zb * (-Bc / self.pk - shift)) + O
        return np.hstack([L, t[:, None]])

    def screen_corners(self, cam):
        M = self.phone_matrix(cam)
        g = self.g
        pts = np.array([[g["sx0"], g["sy0"], 1], [g["sx0"] + g["sw"], g["sy0"], 1],
                        [g["sx0"], g["sy0"] + g["sh"], 1], [g["sx0"] + g["sw"], g["sy0"] + g["sh"], 1]])
        return pts @ M.T

    # -- layers -----------------------------------------------------------

    def _overlays(self):
        s, W, H = self.s, self.W, self.H
        out = []
        k = self.H / 1080 if s["aspect"] != "16:9" else 1.0
        for c in s["captions"]:
            pos = c.get("pos", "top")
            sz = int(c.get("size", 46) * min(W, H) / 1080)
            if pos == "top":
                y = H * float(c.get("y", 0.06 if s["aspect"] == "1:1" else 0.09))
            else:
                y = H * float(c.get("y", 0.94 if s["aspect"] == "1:1" else 0.91))
            lay = text_layer(W, H, c["text"], c.get("font", "cormorant_garamond_italic.ttf"), sz, y,
                             c.get("color", "#F6F0E4"))
            out.append({"t0": float(c["t0"]), "t1": float(c["t1"]), "fade": float(c.get("fade", 0.45)), "layer": lay})
        e = s.get("end_card")
        if e:
            out.append({"t0": float(e["t"]), "t1": 1e9, "fade": float(e.get("fade", 0.8)), "layer": self._end_card(e)})
        _ = k
        return out

    def _end_card(self, e):
        W, H = self.W, self.H
        from spec import THEMES
        th = THEMES[self.s["app"]["theme"]]
        bgc = e.get("background", th["bg"])
        ink = e.get("color", "#1C1B18" if self.s["app"]["theme"] == "Paper" else "#F3ECDF")
        base = Image.new("RGBA", (W, H), bgc)
        u = min(W, H) / 1080
        icon = Image.open(os.path.join(REPO, "docs", "play_store_icon.png")).convert("RGBA")
        isz = int(220 * u)
        icon = icon.resize((isz, isz), Image.LANCZOS)
        mask = Image.new("L", (isz, isz), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, isz - 1, isz - 1], radius=int(isz * 0.23), fill=255)
        base.paste(icon, ((W - isz) // 2, int(H * 0.5 - isz * 0.95)), mask)
        d = ImageDraw.Draw(base)
        f1 = ImageFont.truetype(font_path("junicode_variable.woff2"), int(84 * u))
        f2 = ImageFont.truetype(font_path("cormorant_garamond_italic.ttf"), int(40 * u))
        title, sub = e.get("title", "Beautiful Quran"), e.get("subtitle", "Free · Android & Web")
        d.text(((W - d.textlength(title, font=f1)) / 2, H * 0.5 + 10 * u), title, font=f1, fill=ink)
        rgb = tuple(int(ink.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))
        d.text(((W - d.textlength(sub, font=f2)) / 2, H * 0.5 + 120 * u), sub, font=f2, fill=rgb + (200,))
        a = np.asarray(base, np.float32) / 255
        return np.dstack([a[..., :3] * a[..., 3:4], a[..., 3:4]])

    def _phone_layers(self, screen):
        """Premultiplied phone RGB at canvas resolution for this screen frame."""
        P = self.Fp.copy()
        P[self.scr] += screen * self.A[self.scr]
        return P

    def _warp_phone(self, P, M, acc_rgb, acc_a):
        """Area-downscale P (and its alpha) to the target scale, then place it."""
        sc = math.hypot(M[0, 0], M[1, 0])
        Hc, Wc = P.shape[:2]
        if sc < 0.98:
            tw, th = max(8, int(round(Wc * sc))), max(8, int(round(Hc * sc)))
            Ps = cv2.resize(P, (tw, th), interpolation=cv2.INTER_AREA)
            a = self._alpha_cache.get((tw, th))
            if a is None:
                a = cv2.resize(self.Pa, (tw, th), interpolation=cv2.INTER_AREA)
                if len(self._alpha_cache) > 64:
                    self._alpha_cache.clear()
                self._alpha_cache[(tw, th)] = a
            S = np.array([[Wc / tw, 0, 0], [0, Hc / th, 0], [0, 0, 1]])
            Mi = np.vstack([M, [0, 0, 1]]) @ S
            Mi = Mi[:2]
            # the resize grid is pixel-centre aligned: shift by half a pixel
            Mi[:, 2] += (M[:, :2] @ np.array([0.5 * (Wc / tw - 1), 0.5 * (Hc / th - 1)]))
            src, src_a, Mw = Ps, a, Mi
        else:
            src, src_a, Mw = P, self.Pa, M
        flags = cv2.INTER_LINEAR
        rgb = cv2.warpAffine(src, Mw, (self.W, self.H), flags=flags, borderMode=cv2.BORDER_CONSTANT)
        al = cv2.warpAffine(src_a, Mw, (self.W, self.H), flags=flags, borderMode=cv2.BORDER_CONSTANT)
        return rgb, al

    def frame(self, t, screen_u8, plate_u8):
        """Compose the output frame at video time t (uint8 RGB)."""
        s = self.s
        screen = screen_u8.astype(np.float32) / 255
        plate = plate_u8.astype(np.float32) / 255
        P = self._phone_layers(screen)
        dt = 1.0 / self.fps
        shutter = float(s.get("motion_blur", 1.0)) * 0.5 * dt * 2     # 1.0 = a 360-degree shutter
        cams = [camera_at(s["camera"], t)]
        if shutter > 0:
            a = self.screen_corners(camera_at(s["camera"], t - shutter / 2))
            b = self.screen_corners(camera_at(s["camera"], t + shutter / 2))
            move = float(np.abs(a - b).max())
            n = int(min(24, max(1, math.ceil(move / 1.5))))
            if n > 1:
                cams = [camera_at(s["camera"], t + shutter * (i / (n - 1) - 0.5)) for i in range(n)]
        acc = np.zeros((self.H, self.W, 3), np.float32)
        sh_strength = float(s["phone"]["shadow"])
        for cam in cams:
            bg = cv2.warpAffine(plate, self.bg_matrix(cam), (self.W, self.H), flags=cv2.INTER_LINEAR,
                                borderMode=cv2.BORDER_REFLECT)
            bg *= self.vig
            M = self.phone_matrix(cam)
            if sh_strength > 0:
                q, pad = self.shadow_q, self.shadow_pad
                Ms = np.vstack([M, [0, 0, 1]]) @ np.array([[q, 0, -pad], [0, q, -pad], [0, 0, 1]])
                Ms = Ms[:2].copy()
                Ms[1, 2] += self.H * 0.022 * cam[0]                  # light from above: shadow falls lower
                shd = cv2.warpAffine(self.shadow, Ms, (self.W, self.H), flags=cv2.INTER_LINEAR,
                                     borderMode=cv2.BORDER_CONSTANT)
                bg *= (1 - sh_strength * shd)[..., None]
            rgb, al = self._warp_phone(P, M, None, None)
            acc += rgb + bg * (1 - al)[..., None]
        out = acc / len(cams)
        for o in self.overlays:
            if o["t0"] - 1e-6 <= t <= o["t1"] + o["fade"]:
                f = o["fade"]
                k = min(1.0, (t - o["t0"]) / f if f > 0 else 1.0)
                if o["t1"] < 1e8:
                    k = min(k, (o["t1"] + f - t) / f if f > 0 else float(t <= o["t1"]))
                k = max(0.0, min(1.0, k))
                k = k * k * (3 - 2 * k)
                if k > 0:
                    lay = o["layer"]
                    out = out * (1 - k * lay[..., 3:4]) + k * lay[..., :3]
        # half-LSB dither: the blurred gradients would band in 8 bit
        noise = (np.random.default_rng(int(t * 1000)).random((self.H, self.W, 1), np.float32) - 0.5) / 255
        return np.clip((out + noise) * 255 + 0.5, 0, 255).astype(np.uint8)


# ---------------------------------------------------------------- decoding

class Frames:
    """Sequential rgb24 frames from a video via ffmpeg, starting at `start` s."""

    def __init__(self, path, w, h, fps, start=0.0, loop=False, loop_len=None):
        self.w, self.h = w, h
        if loop and loop_len:
            start = start % loop_len
        cmd = ["ffmpeg", "-v", "error"]
        if loop:
            cmd += ["-stream_loop", "-1"]
        cmd += ["-ss", f"{start:.4f}", "-i", path, "-vf", f"fps={fps},scale={w}:{h}", "-f", "rawvideo",
                "-pix_fmt", "rgb24", "-"]
        self.p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=w * h * 3 * 4)
        self.last = np.zeros((h, w, 3), np.uint8)

    def read(self):
        buf = self.p.stdout.read(self.w * self.h * 3)
        if len(buf) == self.w * self.h * 3:
            self.last = np.frombuffer(buf, np.uint8).reshape(self.h, self.w, 3)
        return self.last        # past the end: hold the last frame

    def close(self):
        self.p.stdout.close()
        self.p.kill()
        self.p.wait()


def grab(path, w, h, t):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{max(0.0, t):.4f}", "-i", path, "-frames:v", "1",
                          "-vf", f"scale={w}:{h}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                         capture_output=True, check=True).stdout
    if len(raw) < w * h * 3:
        return np.zeros((h, w, 3), np.uint8)
    return np.frombuffer(raw[:w * h * 3], np.uint8).reshape(h, w, 3)


def still(s, t, scale=1.0):
    """One exact output frame (RGB uint8) at video time t."""
    r = Renderer(s, scale)
    start = float(s["app"]["start"])
    scr = grab(r.clip, r.sw, r.sh, start + t)
    pl = grab(r.plate, r.pw, r.ph, (t + float(s["background"].get("offset", 0))) % max(0.01, r.plate_len))
    return r.frame(t, scr, pl)


# ---------------------------------------------------------------- export

def _render_chunk(args):
    s, f0, f1, path, scale = args
    r = Renderer(s, scale)
    fps = r.fps
    start = float(s["app"]["start"])
    off = float(s["background"].get("offset", 0))
    scr = Frames(r.clip, r.sw, r.sh, fps, start + f0 / fps)
    pl = Frames(r.plate, r.pw, r.ph, fps, off + f0 / fps, loop=True, loop_len=r.plate_len)
    enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s",
                            f"{r.W}x{r.H}", "-r", str(fps), "-i", "-", "-c:v", "libx264", "-preset", "medium",
                            "-crf", "14", "-pix_fmt", "yuv420p", "-g", str(fps), path], stdin=subprocess.PIPE)
    for f in range(f0, f1):
        img = r.frame(f / fps, scr.read(), pl.read())
        enc.stdin.write(img.tobytes())
    enc.stdin.close(); enc.wait()
    scr.close(); pl.close()
    return path


def export(s, out=None, workers=None, scale=1.0, log=print, progress=None):
    """Render the whole spec to an H.264/AAC mp4. Returns the output path."""
    r = Renderer(s, scale)          # validates the take and builds the plate once, up front
    fps, dur = r.fps, float(s["duration"])
    n = int(round(dur * fps))
    workers = workers or max(1, min(8, (os.cpu_count() or 4) - 2))
    os.makedirs(OUT, exist_ok=True)
    out = out or os.path.join(OUT, f"{s['name']}.mp4")
    work = os.path.join(CACHE, "render", s["name"])
    os.makedirs(work, exist_ok=True)
    bounds = [round(n * i / workers) for i in range(workers + 1)]
    chunks = [(s, bounds[i], bounds[i + 1], os.path.join(work, f"part{i:02d}.mp4"), scale)
              for i in range(workers) if bounds[i + 1] > bounds[i]]
    t0 = time.time()
    log(f"rendering {s['name']}: {n} frames {r.W}x{r.H}@{fps} in {len(chunks)} parts")
    with ProcessPoolExecutor(len(chunks)) as ex:
        for i, _ in enumerate(ex.map(_render_chunk, chunks)):
            if progress:
                progress((i + 1) / len(chunks))
    lst = os.path.join(work, "parts.txt")
    with open(lst, "w") as f:
        for c in chunks:
            f.write(f"file '{c[3]}'\n")
    a = s["audio"]
    start = float(s["app"]["start"])
    wav = os.path.join(r.td, "audio.wav")
    fo = float(a["fade_out"])
    af = [f"atrim=start={start:.4f}:duration={dur:.4f}", "asetpts=PTS-STARTPTS", f"volume={a['gain_db']}dB"]
    if float(a["fade_in"]) > 0:
        af.append(f"afade=t=in:st=0:d={a['fade_in']}")
    if fo > 0:
        af.append(f"afade=t=out:st={max(0.0, dur - fo):.3f}:d={fo}")
    af.append(f"apad=whole_dur={dur:.4f}")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-i", wav,
                    "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-af", ",".join(af), "-c:a", "aac",
                    "-b:a", "256k", "-ar", "48000", "-t", f"{dur:.4f}", "-movflags", "+faststart", out], check=True)
    log(f"wrote {out} in {time.time() - t0:.0f}s")
    return out
