"""The compositor: a recorded take, in a Pixel frame, over a blurred world.

Every output frame is a pure function of (spec, t), so the editor's exact
preview, a single-frame check and the parallel export all agree:

    background plate   blurred and graded; follows the camera by `parallax`;
                       bg_blur / bg_brightness are applied per frame, so
                       they can be keyframed
    phone shadow       under the body
    phone              screen (the take, through the edit) + drawn frame,
                       composed at the take's resolution and area-filtered
                       down to the output size (no shimmer on text), placed
                       by phone_x / phone_y / phone_scale / phone_tilt
    camera             zoom / pan_x / pan_y / rotate over the whole scene

Properties come from anim.state_at (static value or keyframes). When the
phone moves fast between frames, sub-frames across the shutter are averaged:
real motion blur on zooms and whips, none when the camera is still.
"""
import json
import math
import os
import subprocess
import time
from concurrent.futures import ProcessPoolExecutor

import cv2
import numpy as np
from PIL import Image

import library
import phone as phone_mod
from anim import source_time, state_at
from spec import CACHE, OUT, size, take_dir

cv2.setNumThreads(2)


def plate_settings(s):
    """The background look baked into the plate: the least blur the video
    ever uses (more is added per frame), no brightness (added per frame)."""
    blurs = [float(s["props"]["bg_blur"])] + [float(k["v"]) for k in s["tracks"].get("bg_blur", [])]
    return dict(s["background"], blur=max(2.0, min(blurs)), brightness=0.0)


class Renderer:
    def __init__(self, s, scale=1.0):
        self.s = s
        W, H = size(s)
        self.W, self.H = int(round(W * scale / 2) * 2), int(round(H * scale / 2) * 2)
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
        self.base_s = s["phone"]["height"] * self.H / g["bh"]     # canvas px -> output px at scale 1
        self.C0 = np.array([g["W"] / 2, g["H"] / 2])
        self.O = np.array([self.W / 2, self.H / 2])
        # background plate
        self.bg = plate_settings(s)
        self.plate = library.plate(self.bg, self.W, self.H)
        self.pw, self.ph, self.pk = library.plate_geometry(self.bg, self.W, self.H)
        self.plate_len = library.probe(self.plate)[2]
        yy, xx = np.mgrid[0:self.H, 0:self.W].astype(np.float32)
        r = np.hypot((xx - self.W / 2) / (self.W / 2), (yy - self.H / 2) / (self.H / 2)) / math.sqrt(2)
        self.vig = (1 - s["background"]["vignette"] * np.clip(r, 0, 1) ** 2)[..., None].astype(np.float32)
        self._alpha_cache = {}

    # -- geometry (editor.js phoneMatrix / bgMatrix mirror these) ---------

    def phone_matrix(self, st):
        """2x3 affine: phone canvas px -> output px."""
        z = st["zoom"]
        sc = self.base_s * st["phone_scale"]
        P0 = np.array([self.W / 2 + st["phone_x"] * self.W, self.H / 2 + st["phone_y"] * self.H])
        C = np.array([st["pan_x"] * self.W, st["pan_y"] * self.H])
        rc, rt = math.radians(st["rotate"]), math.radians(st["phone_tilt"])
        Rc = np.array([[math.cos(rc), -math.sin(rc)], [math.sin(rc), math.cos(rc)]])
        Rt = np.array([[math.cos(rt), -math.sin(rt)], [math.sin(rt), math.cos(rt)]])
        # world w = P0 + sc Rt (p - C0); out = z Rc (w - C) + O
        L = z * sc * (Rc @ Rt)
        t = z * (Rc @ (P0 - C)) - L @ self.C0 + self.O
        return np.hstack([L, t[:, None]])

    def bg_matrix(self, st):
        p = float(self.s["background"]["parallax"])
        zb = 1 + (st["zoom"] - 1) * p
        shift = (np.array([st["pan_x"] * self.W, st["pan_y"] * self.H]) - self.O) * p
        r = math.radians(st["rotate"] * p)
        R = np.array([[math.cos(r), -math.sin(r)], [math.sin(r), math.cos(r)]])
        Bc = np.array([self.pw / 2, self.ph / 2])
        L = R * (zb / self.pk)
        t = R @ (zb * (-Bc / self.pk - shift)) + self.O
        return np.hstack([L, t[:, None]])

    def screen_corners(self, st):
        M = self.phone_matrix(st)
        g = self.g
        pts = np.array([[g["sx0"], g["sy0"], 1], [g["sx0"] + g["sw"], g["sy0"], 1],
                        [g["sx0"], g["sy0"] + g["sh"], 1], [g["sx0"] + g["sw"], g["sy0"] + g["sh"], 1]])
        return pts @ M.T

    # -- layers -----------------------------------------------------------

    def _phone_layers(self, screen):
        """Premultiplied phone RGB at canvas resolution for this screen frame."""
        P = self.Fp.copy()
        P[self.scr] += screen * self.A[self.scr]
        return P

    def _warp_phone(self, P, M):
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
            Mi = (np.vstack([M, [0, 0, 1]]) @ S)[:2]
            # the resize grid is pixel-centre aligned: shift by half a pixel
            Mi[:, 2] += (M[:, :2] @ np.array([0.5 * (Wc / tw - 1), 0.5 * (Hc / th - 1)]))
            src, src_a, Mw = Ps, a, Mi
        else:
            src, src_a, Mw = P, self.Pa, M
        rgb = cv2.warpAffine(src, Mw, (self.W, self.H), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        al = cv2.warpAffine(src_a, Mw, (self.W, self.H), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        return rgb, al

    def _background(self, plate, st):
        extra = math.sqrt(max(0.0, st["bg_blur"] ** 2 - self.bg["blur"] ** 2)) * self.pk
        if extra > 0.3:
            plate = cv2.GaussianBlur(plate, (0, 0), extra, borderType=cv2.BORDER_REFLECT)
        bg = cv2.warpAffine(plate, self.bg_matrix(st), (self.W, self.H), flags=cv2.INTER_LINEAR,
                            borderMode=cv2.BORDER_REFLECT)
        b = st["bg_brightness"]
        if b > 0:                      # towards white / towards black: the editor's canvas does the same
            bg = bg + min(1.0, b) * (1 - bg)
        elif b < 0:
            bg = bg * (1 + max(-1.0, b))
        return bg * self.vig

    def frame(self, t, screen_u8, plate_u8):
        """Compose the output frame at video time t (uint8 RGB)."""
        s = self.s
        screen = screen_u8.astype(np.float32) / 255
        plate = plate_u8.astype(np.float32) / 255
        P = self._phone_layers(screen)
        shutter = float(s.get("motion_blur", 1.0)) / self.fps       # 1.0 = open for the whole frame
        states = [state_at(s, t)]
        if shutter > 0:
            a = self.screen_corners(state_at(s, t - shutter / 2))
            b = self.screen_corners(state_at(s, t + shutter / 2))
            n = int(min(24, max(1, math.ceil(float(np.abs(a - b).max()) / 1.5))))
            if n > 1:
                states = [state_at(s, t + shutter * (i / (n - 1) - 0.5)) for i in range(n)]
        acc = np.zeros((self.H, self.W, 3), np.float32)
        sh_strength = float(s["phone"]["shadow"])
        for st in states:
            bg = self._background(plate, st)
            M = self.phone_matrix(st)
            if sh_strength > 0:
                q, pad = self.shadow_q, self.shadow_pad
                Ms = (np.vstack([M, [0, 0, 1]]) @ np.array([[q, 0, -pad], [0, q, -pad], [0, 0, 1]]))[:2].copy()
                Ms[1, 2] += self.H * 0.022 * st["zoom"] * st["phone_scale"]   # light from above
                shd = cv2.warpAffine(self.shadow, Ms, (self.W, self.H), flags=cv2.INTER_LINEAR,
                                     borderMode=cv2.BORDER_CONSTANT)
                bg *= (1 - sh_strength * shd)[..., None]
            rgb, al = self._warp_phone(P, M)
            acc += rgb + bg * (1 - al)[..., None]
        out = acc / len(states)
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


class EditFrames:
    """The take's frames in video order: reads sequentially inside an edit
    piece and re-seeks at each cut."""

    def __init__(self, s, path, w, h, fps):
        self.s, self.path, self.w, self.h, self.fps = s, path, w, h, fps
        self.cur, self.piece = None, None

    def read(self, t):
        src, piece = source_time(self.s, t)
        if piece is not self.piece:
            if self.cur:
                self.cur.close()
            self.cur, self.piece = Frames(self.path, self.w, self.h, self.fps, src), piece
        return self.cur.read()

    def close(self):
        if self.cur:
            self.cur.close()


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
    scr = grab(r.clip, r.sw, r.sh, source_time(s, t)[0])
    pl = grab(r.plate, r.pw, r.ph, (t + float(s["background"].get("offset", 0))) % max(0.01, r.plate_len))
    return r.frame(t, scr, pl)


# ---------------------------------------------------------------- export

def _render_chunk(args):
    s, f0, f1, path, scale = args
    r = Renderer(s, scale)
    fps = r.fps
    off = float(s["background"].get("offset", 0))
    scr = EditFrames(s, r.clip, r.sw, r.sh, fps)
    pl = Frames(r.plate, r.pw, r.ph, fps, off + f0 / fps, loop=True, loop_len=r.plate_len)
    enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s",
                            f"{r.W}x{r.H}", "-r", str(fps), "-i", "-", "-c:v", "libx264", "-preset", "medium",
                            "-crf", "14", "-pix_fmt", "yuv420p", "-g", str(fps), path], stdin=subprocess.PIPE)
    for f in range(f0, f1):
        t = f / fps
        enc.stdin.write(r.frame(t, scr.read(t), pl.read()).tobytes())
    enc.stdin.close(); enc.wait()
    scr.close(); pl.close()
    return path


CUT_FADE = 0.008     # seconds faded at each side of a cut: no click, no audible dip


def audio_graph(s):
    """filter_complex cutting the take's sound like the picture -> [cut]."""
    parts, n = [], len(s["edit"])
    for i, p in enumerate(s["edit"]):
        a, b = float(p["in"]), float(p["out"])
        f = f"[0:a]atrim=start={a:.4f}:end={b:.4f},asetpts=PTS-STARTPTS"
        if i > 0:
            f += f",afade=t=in:st=0:d={CUT_FADE}"
        if i < n - 1:
            f += f",afade=t=out:st={max(0.0, b - a - CUT_FADE):.4f}:d={CUT_FADE}"
        parts.append(f + f"[p{i}]")
    parts.append("".join(f"[p{i}]" for i in range(n)) + f"concat=n={n}:v=0:a=1[cut]")
    return ";".join(parts)


def audio_filter(s):
    """The take's own sound for this video: cut by the edit, loudness-
    normalised (two-pass EBU R128, docs/KEY_ART.md: -16 LUFS, peaks under
    -1.5 dBTP), then the spec's gain and fades. Returns (wav, filter_complex)."""
    a, dur = s["audio"], float(s["duration"])
    wav = os.path.join(take_dir(s), "audio.wav")
    graph = audio_graph(s)
    chain = []
    target = a.get("loudness", -16.0)
    if target is not None:
        probe = subprocess.run(["ffmpeg", "-hide_banner", "-i", wav, "-filter_complex",
                                f"{graph};[cut]loudnorm=I={target}:TP=-1.5:LRA=11:print_format=json",
                                "-f", "null", "-"], capture_output=True, text=True).stderr
        m = json.loads(probe[probe.rindex("{"):probe.rindex("}") + 1])
        chain.append(f"loudnorm=I={target}:TP=-1.5:LRA=11:measured_I={m['input_i']}:measured_TP={m['input_tp']}:"
                     f"measured_LRA={m['input_lra']}:measured_thresh={m['input_thresh']}:"
                     f"offset={m['target_offset']}:linear=true")
    chain.append(f"volume={a['gain_db']}dB")
    if float(a["fade_in"]) > 0:
        chain.append(f"afade=t=in:st=0:d={a['fade_in']}")
    if float(a["fade_out"]) > 0:
        chain.append(f"afade=t=out:st={max(0.0, dur - float(a['fade_out'])):.3f}:d={a['fade_out']}")
    chain.append(f"aresample=48000,apad=whole_dur={dur:.4f}")
    return wav, f"{graph};[cut]{','.join(chain)}[aout]"


def mux(s, video_input, out):
    """Lay the take's sound under a rendered picture (video stream copied)."""
    wav, fc = audio_filter(s)
    tmp = out + ".mux.mp4"
    n_in = video_input.count("-i")
    fc = fc.replace("[0:a]", f"[{n_in}:a]")
    subprocess.run(["ffmpeg", "-v", "error", "-y", *video_input, "-i", wav, "-filter_complex", fc,
                    "-map", "0:v", "-map", "[aout]", "-c:v", "copy", "-c:a", "aac", "-b:a", "256k", "-ar", "48000",
                    "-t", f"{float(s['duration']):.4f}", "-movflags", "+faststart", tmp], check=True)
    os.replace(tmp, out)
    return out


def export(s, out=None, workers=None, scale=1.0, log=print, progress=None):
    """Render the whole spec to an H.264/AAC mp4. Returns the output path."""
    r = Renderer(s, scale)          # validates the take and builds the plate once, up front
    if s["duration"] > 0 and max(float(p["out"]) for p in s["edit"]) > r.meta["length"] + 0.05:
        log(f"warning: the edit reads past the take's end ({r.meta['length']:.1f}s): the last frame holds")
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
    mux(s, ["-f", "concat", "-safe", "0", "-i", lst], out)
    log(f"wrote {out} in {time.time() - t0:.0f}s")
    return out
