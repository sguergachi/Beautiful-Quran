#!/usr/bin/env python3
"""Conform a slow-speed Android take to real time for the edit.

    conform_take.py TAKE.mp4 REF.png OUT.mp4 --speed 0.25 [--pre 0.6] [--dur 30] [--fps 60]

Finds the playback onset (find_onset.py), cuts from `pre` media seconds
before it, speeds the take back to 1x, resamples to --fps, and fills the
slowed speed label out (delogo); REF.png is only used to find the onset. Every
conformed clip of the same recitation then shares one timeline: clip t=0 is
`pre` seconds before the first recited file starts.
"""
import argparse
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("take"); ap.add_argument("ref"); ap.add_argument("out")
    ap.add_argument("--speed", type=float, required=True)
    ap.add_argument("--pre", type=float, default=0.6)
    ap.add_argument("--dur", type=float, default=0)
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--onset", type=float, default=None)
    ap.add_argument("--at", default=None, help="play/pause centre as w,h fractions (find_onset --at)")
    ap.add_argument("--label", default="528,1474,96,56",
                    help="speed-label box x,y,w,h on the 720x1600 layout (Mushaf: 488,1393,96,56)")
    a = ap.parse_args()
    onset = a.onset
    if onset is None:
        extra = ["--at", a.at] if a.at else []
        onset = float(subprocess.run(["python3", os.path.join(HERE, "find_onset.py"), a.take, a.ref, *extra],
                                     capture_output=True, text=True, check=True).stdout.strip())
    w, h = map(int, subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                    "stream=width,height", "-of", "csv=p=0", a.take],
                                   capture_output=True, text=True, check=True).stdout.strip().split(","))
    k = w / 720                                   # patch box measured on the 720x1600 layout
    x, y, bw, bh = (round(int(v) * k) for v in a.label.split(","))
    start = onset - a.pre / a.speed
    # not enough footage before the onset: hold the first frame instead of
    # clamping, which would slide the whole clip off the shared timeline
    hold = max(0.0, -start) * a.speed
    start = max(0.0, start)
    # The slowed speed label ("0.25×") stays lit; fill it from its surroundings
    # (delogo). At 1x the label recedes to near-invisible, so nothing is lost.
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.3f}", "-i", a.take,
           "-vf", f"setpts=(PTS-STARTPTS)*{a.speed},fps={a.fps},delogo=x={x}:y={y}:w={bw}:h={bh},"
           f"tpad=start_duration={hold:.3f}:start_mode=clone,format=yuv420p"]
    if a.dur:
        cmd += ["-t", str(a.dur)]
    cmd += ["-c:v", "libx264", "-preset", "slow", "-crf", "12", "-g", str(a.fps // 2), "-an", a.out]
    subprocess.run(cmd, check=True)
    print(f"{a.out}: onset {onset:.3f}s, starts {a.pre}s before the recitation")


if __name__ == "__main__":
    main()
