#!/usr/bin/env python3
"""Put a real app recording onto a green-screen phone in stock footage.

    composite_video.py PLATE.mp4 SCREEN.mp4 OUT.mp4 [--fps 30] [--start 0] [--dur N]
                       [--screen-start 0] [--width 1080] [--crop x,y,w,h] [--grade 1.0]

Per frame: key the screen (composite_screen.chroma_alpha), fit its corners
(composite_screen.fit_quad), then smooth the corner tracks over time so the
app does not shimmer on the glass, and composite with edge unmixing and
despill (composite_screen.composite). --crop takes a region of the plate
(e.g. a 9:16 window of landscape footage) before scaling to --width.
"""
import argparse
import subprocess

import cv2
import numpy as np

from composite_screen import chroma_alpha, composite, fit_quad, key_colour


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                          "stream=width,height", "-of", "csv=p=0", path], capture_output=True, text=True, check=True)
    w, h = map(int, out.stdout.strip().split(",")[:2])
    return w, h


def frames(path, fps, start, dur, vf, size):
    cmd = ["ffmpeg", "-v", "error", "-ss", str(start), "-i", path]
    if dur:
        cmd += ["-t", str(dur)]
    cmd += ["-vf", f"{vf}fps={fps}" if vf else f"fps={fps}", "-f", "rawvideo", "-pix_fmt", "bgr24", "-"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    w, h = size
    n = w * h * 3
    while True:
        buf = p.stdout.read(n)
        if len(buf) < n:
            break
        yield np.frombuffer(buf, np.uint8).reshape(h, w, 3)
    p.wait()


def smooth(track, radius=3):
    """Moving average of corner tracks; edges use the window they have."""
    out = np.empty_like(track)
    for i in range(len(track)):
        lo, hi = max(0, i - radius), min(len(track), i + radius + 1)
        out[i] = track[lo:hi].mean(0)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("plate"); ap.add_argument("screen"); ap.add_argument("out")
    ap.add_argument("--fps", type=float, default=30); ap.add_argument("--start", type=float, default=0)
    ap.add_argument("--dur", type=float, default=0); ap.add_argument("--screen-start", type=float, default=0)
    ap.add_argument("--width", type=int, default=1080); ap.add_argument("--crop", default="")
    ap.add_argument("--grade", type=float, default=1.0, help="screen brightness to sit in the plate's light")
    a = ap.parse_args()

    pw, ph = probe(a.plate)
    vf = ""
    if a.crop:
        x, y, cw, ch = map(int, a.crop.split(","))
        vf += f"crop={cw}:{ch}:{x}:{y},"
        pw, ph = cw, ch
    W = a.width
    H = round(ph * W / pw / 2) * 2
    vf += f"scale={W}:{H}:flags=lanczos,"
    plates = list(frames(a.plate, a.fps, a.start, a.dur, vf, (W, H)))
    sw, sh = probe(a.screen)
    screens = list(frames(a.screen, a.fps, a.screen_start, len(plates) / a.fps + 1, "", (sw, sh)))
    # pass 1: key + corners per frame
    key = key_colour(plates[len(plates) // 2])
    quads = np.array([fit_quad(chroma_alpha(p, key)) for p in plates], np.float32)
    quads = smooth(quads)
    # pass 2: composite
    enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{W}x{H}",
                            "-r", str(a.fps), "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", "16",
                            "-pix_fmt", "yuv420p", "-movflags", "+faststart", a.out], stdin=subprocess.PIPE)
    for i, p in enumerate(plates):
        s = screens[min(i, len(screens) - 1)]
        if a.grade != 1.0:
            s = np.clip(s.astype(np.float32) * a.grade, 0, 255).astype(np.uint8)
        enc.stdin.write(composite(p, s, chroma_alpha(p, key), quads[i], key, seed=i).tobytes())
    enc.stdin.close(); enc.wait()
    print(f"wrote {a.out}: {len(plates)} frames {W}x{H}")


if __name__ == "__main__":
    main()
