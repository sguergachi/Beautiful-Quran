#!/usr/bin/env python3
"""When did playback start in an Android take?

    find_onset.py TAKE.mp4 REF.png [--after 1.0] [--at 0.5,0.9387]

android_take.py starts the screen recording paused and then opens the verse
with ?play=true. The transport's play glyph turns into pause when Media3
reports playing; REF.png is a frame captured during playback, so its pause
glyph is the template. Prints the take time (s) of the first matching frame.
"""
import subprocess
import sys

import numpy as np
from PIL import Image


def main():
    take, ref = sys.argv[1], sys.argv[2]
    after = float(sys.argv[sys.argv.index("--after") + 1]) if "--after" in sys.argv else 1.0
    r = np.asarray(Image.open(ref).convert("L"), np.float32)
    h, w = r.shape
    # play/pause centre as a fraction of the frame (Scroll default; Mushaf ~0.5,0.888)
    fx, fy = (map(float, sys.argv[sys.argv.index("--at") + 1].split(",")) if "--at" in sys.argv else (0.5, 0.9387))
    cx, cy, half = int(w * fx), int(h * fy), int(w * 0.05)
    box = (cx - half, cy - half, cx + half, cy + half)
    tpl = r[box[1]:box[3], box[0]:box[2]]
    vw, vh = map(int, subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                      "stream=width,height", "-of", "csv=p=0", take],
                                     capture_output=True, text=True).stdout.strip().split(","))
    sx, sy = vw / w, vh / h
    crop = f"crop={int((box[2]-box[0])*sx)}:{int((box[3]-box[1])*sy)}:{int(box[0]*sx)}:{int(box[1]*sy)},scale={box[2]-box[0]}:{box[3]-box[1]}"
    ts = [float(x.strip(",")) for x in subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", take],
        capture_output=True, text=True).stdout.split()]
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", take, "-vf", crop, "-fps_mode", "passthrough",
                          "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True).stdout
    n = tpl.size
    fr = np.frombuffer(raw, np.uint8).reshape(-1, *tpl.shape).astype(np.float32)
    d = np.array([np.abs(f - tpl).mean() for f in fr])
    k = len(fr)
    # a play glyph first (far from the template), then the switch to pause
    thr = 6.0
    seen_play = False
    for i in range(k):
        if ts[i] < after:
            continue
        if d[i] > thr * 2:
            seen_play = True
        elif seen_play and d[i] < thr:
            print(f"{ts[i]:.3f}")
            return
    raise SystemExit(f"no play-to-pause switch after {after}s (min diff {d.min():.1f})")


if __name__ == "__main__":
    main()
