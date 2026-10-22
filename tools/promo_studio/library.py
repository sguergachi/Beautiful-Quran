"""Background footage: download, then bake a small blurred plate per look.

A plate is the background already blurred and colour-graded, stored small
(the blur leaves nothing for extra pixels to show) and made seamless by
playing the footage forward then backward. The compositor loops it and
scales it up to the frame. Plates are cached by every setting that changes
their pixels, so trying a look twice costs nothing.

Background ids: a key of backgrounds.json, `file:/abs/path` (image or
video) or `color:#RRGGBB`.
"""
import json
import os
import re
import threading
import subprocess
import urllib.request

from spec import CACHE, HERE, digest

LIB = os.path.join(HERE, "backgrounds.json")
SRC = os.path.join(CACHE, "bg-src")
PLATES = os.path.join(CACHE, "plates")
IMAGE_EXT = (".png", ".jpg", ".jpeg", ".webp")


def catalog():
    with open(LIB) as f:
        return {k: v for k, v in json.load(f).items() if not k.startswith("_")}


def source(bg_id):
    """Local path of a background's footage, downloading Pexels clips on first use."""
    if bg_id.startswith("file:"):
        return bg_id[5:]
    if bg_id.startswith("color:"):
        return None
    entry = catalog()[bg_id]
    os.makedirs(SRC, exist_ok=True)
    path = os.path.join(SRC, f"{entry['pexels']}.mp4")
    if not os.path.exists(path):
        req = urllib.request.Request(f"https://www.pexels.com/download/video/{entry['pexels']}/",
                                     headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req) as r, open(path + ".part", "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        os.replace(path + ".part", path)
    return path


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                          "stream=width,height:format=duration", "-of", "json", path],
                         capture_output=True, text=True, check=True).stdout
    j = json.loads(out)
    st = j["streams"][0]
    return st["width"], st["height"], float(j.get("format", {}).get("duration", 0) or 0)


def plate_geometry(bg, out_w, out_h):
    """Plate size: blur sigma ~8 plate pixels, 30% wider than the frame for parallax."""
    k = min(1.0, 8.0 / max(1.0, bg["blur"]))
    pw = max(64, int(round(out_w * 1.3 * k / 2)) * 2)
    ph = max(64, int(round(out_h * 1.3 * k / 2)) * 2)
    return pw, ph, k


_PLATE_LOCK = threading.Lock()


def plate(bg, out_w, out_h, fps=30):
    """Build a plate once; HTTP preview requests may arrive concurrently."""
    with _PLATE_LOCK:
        return _plate(bg, out_w, out_h, fps)


def _plate(bg, out_w, out_h, fps=30):
    """Path to a seamless, blurred, graded plate for this background look."""
    pw, ph, k = plate_geometry(bg, out_w, out_h)
    identity = None
    if bg["id"].startswith("file:"):
        stat = os.stat(bg["id"][5:])
        identity = [stat.st_mtime_ns, stat.st_size]
    key = digest({"id": bg["id"], "blur": bg["blur"], "brightness": bg["brightness"],
                  "saturation": bg["saturation"], "speed": bg["speed"], "w": pw, "h": ph, "fps": fps, "v": 2, **({"file": identity} if identity else {})})
    os.makedirs(PLATES, exist_ok=True)
    out = os.path.join(PLATES, f"{re.sub(r'[^A-Za-z0-9._-]', '_', bg['id'])[:80]}-{key}.mp4")
    if os.path.exists(out):
        return out
    sigma = bg["blur"] * k
    pad = int(sigma * 3) + 2                    # blur past the edges, then crop them off
    grade = f"eq=brightness={bg['brightness']:.3f}:saturation={bg['saturation']:.3f}"
    blur = f"gblur=sigma={sigma:.2f}:steps=3"
    cover = (f"scale={pw + 2 * pad}:{ph + 2 * pad}:force_original_aspect_ratio=increase:flags=area,"
             f"crop={pw + 2 * pad}:{ph + 2 * pad}")
    if bg["id"].startswith("color:"):
        color = bg["id"][6:]
        cmd = ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"color=c={color}:s={pw}x{ph}:r={fps}:d=1",
               "-c:v", "libx264", "-crf", "12", "-pix_fmt", "yuv420p", out + ".part.mp4"]
    else:
        src = source(bg["id"])
        if src.lower().endswith(IMAGE_EXT):
            cmd = ["ffmpeg", "-v", "error", "-y", "-loop", "1", "-i", src, "-t", "1", "-r", str(fps),
                   "-vf", f"{cover},{blur},crop={pw}:{ph}:{pad}:{pad},{grade},format=yuv420p",
                   "-c:v", "libx264", "-crf", "12", out + ".part.mp4"]
        else:
            start = float(catalog().get(bg["id"], {}).get("start", 0))
            _, _, dur = probe(src)
            span = max(1.0, min(dur - start, 30.0))   # one pass of at most 30 s, then back
            speed = max(0.05, float(bg["speed"]))
            fc = (f"[0:v]trim=start={start}:duration={span},setpts=(PTS-STARTPTS)/{speed},fps={fps},"
                  f"{cover},{blur},crop={pw}:{ph}:{pad}:{pad},{grade},split[f][r];"
                  f"[r]reverse,trim=start_frame=1[b];[f][b]concat=n=2:v=1:a=0,format=yuv420p[v]")
            cmd = ["ffmpeg", "-v", "error", "-y", "-i", src, "-filter_complex", fc, "-map", "[v]",
                   "-c:v", "libx264", "-crf", "12", "-g", str(fps), out + ".part.mp4"]
            subprocess.run(cmd, check=True)
            os.replace(out + ".part.mp4", out)
            return out
    subprocess.run(cmd, check=True)
    os.replace(out + ".part.mp4", out)
    return out
