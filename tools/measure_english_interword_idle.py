#!/usr/bin/env python3
"""Measure visible interword idle from fixed glyph-body cores in a recording.

Requires ffmpeg, ffprobe and numpy. Mask JSON is an ordered list of first/last core
pairs, each with word, part, x and y arrays; coordinates are relative to --crop.
Use identical masks/layout and explicit unread/settled windows for comparisons.
This is a pixel diagnostic, not a readability or acoustic accuracy score.
Resampling duplicates variable-rate source frames; --fps is not a claim about
independent visual samples or the recording's actual temporal resolution.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import numpy as np


def interval(value):
    start, end = map(float, value.split(":"))
    if not 0 <= start < end:
        raise argparse.ArgumentTypeError("window must be start:end with 0 <= start < end")
    return start, end


def first_crossing(values, threshold, after, fps):
    for at in range(after, len(values) - 4):
        if np.count_nonzero(values[at:at + 4] >= threshold) >= 3:
            return at / fps
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("masks", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--crop", required=True, help="ffmpeg width:height:x:y")
    parser.add_argument("--fps", type=int, default=60)
    parser.add_argument("--unread-window", type=interval, default=(0, 2))
    parser.add_argument("--settled-window", type=interval, default=(25, 29))
    args = parser.parse_args()
    width, height, left, top = map(int, args.crop.split(":"))
    if min(width, height, args.fps) <= 0 or min(left, top) < 0:
        parser.error("crop and fps must describe a positive image")
    masks = json.loads(args.masks.read_text())
    if len(masks) < 4 or len(masks) % 2:
        parser.error("masks must contain at least two first/last pairs")
    points = []
    for index, mask in enumerate(masks):
        if mask["part"] != ("first" if index % 2 == 0 else "last"):
            parser.error("mask order must be first,last for each word")
        x, y = np.array(mask["x"], dtype=int), np.array(mask["y"], dtype=int)
        if not len(x) or x.shape != y.shape or x.min() < 0 or y.min() < 0 or x.max() >= width or y.max() >= height:
            parser.error("mask points must be inside the crop")
        points.append((x, y))
    process = subprocess.Popen([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(args.video),
        "-vf", f"fps={args.fps},crop={args.crop}", "-pix_fmt", "gray", "-f", "rawvideo", "pipe:1",
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    values = []
    try:
        while True:
            frame = process.stdout.read(width * height)
            if not frame:
                break
            if len(frame) != width * height:
                raise ValueError("partial decoded frame")
            pixels = np.frombuffer(frame, np.uint8).reshape(height, width)
            values.append([float(np.median(pixels[y, x])) for x, y in points])
        if process.wait():
            raise RuntimeError(process.stderr.read().decode(errors="replace"))
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
    values = np.array(values)
    references = []
    for start, end in (args.unread_window, args.settled_window):
        first, last = int(start * args.fps), int(end * args.fps)
        if not 0 <= first < last <= len(values):
            parser.error("reference window is outside the decoded recording")
        references.append(np.median(values[first:last], axis=0))
    unread, settled = references
    if np.any(unread - settled <= 1):
        parser.error("every core needs a darker settled reference than its unread reference")
    ink = (unread - values) / (unread - settled)
    transitions = []
    after = int(args.unread_window[1] * args.fps)
    for index in range(len(masks) // 2 - 1):
        previous, following = index * 2 + 1, (index + 1) * 2
        for finish in (.7, .8, .9):
            for start in (.1, .15, .2):
                first = first_crossing(ink[:, previous], finish, after, args.fps)
                last = first_crossing(ink[:, following], start, after, args.fps)
                transitions.append(dict(
                    transition=index, previous=masks[previous]["word"], following=masks[following]["word"],
                    finish_threshold=finish, start_threshold=start, finish_s=first, start_s=last,
                    idle_ms=None if first is None or last is None else (last - first) * 1000,
                ))
    source = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
        "-show_entries", "stream=nb_read_frames,avg_frame_rate:format=duration", "-of", "json", str(args.video),
    ]))
    result = dict(
        frames=len(values), resampled_fps=args.fps, crop=args.crop,
        source_video=source,
        sampling_note="Output-grid differences are not guaranteed source-frame or perceptual resolution.",
        unread_window=args.unread_window, settled_window=args.settled_window,
        video_sha256=hashlib.sha256(args.video.read_bytes()).hexdigest(),
        masks_sha256=hashlib.sha256(args.masks.read_bytes()).hexdigest(),
        transitions=transitions,
    )
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"{len(values)} frames; {len(transitions)} threshold comparisons; {args.output}")


if __name__ == "__main__":
    main()
