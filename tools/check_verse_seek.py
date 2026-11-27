#!/usr/bin/env python3
"""Check that a seek inside a range-sourced verse keeps the player's clock honest.

`VerseClipSeamCaptureTest.captureSeekInsideAyatAlKursi` plays one verse, seeks
back inside it, and marks the presentation time the sink was handed after each
flush (`pts <us> <byte>`). Word times count from the verse's heard start, so
the sample heard when the clock reads T must be the recording's
`originSamples + T`. This finds where in the recording each stretch after a
flush really is and prints the error in samples (a frame is 1,152):

    adb pull /data/data/com.beautifulquran/files/seam-capture/ /tmp/
    tools/check_verse_seek.py /tmp/seam-capture/2-255-seek --source DIR_OF_NNN.mp3
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

INDEX = Path(__file__).resolve().parent / "timing_sources" / "tawfeek-verse-index.json"
RATE = 44_100
WINDOW = 4_096
SEARCH = 6_000
TOLERANCE = 8
SETTLE = 4 * 1152


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("capture", type=Path, help="capture path without extension (.pcm and .txt)")
    parser.add_argument("--source", type=Path, required=True, help="dir of NNN.mp3 surah files")
    parser.add_argument("--index", type=Path, default=INDEX)
    args = parser.parse_args()

    lines = args.capture.with_suffix(".txt").read_text().splitlines()
    meta = dict(line.split(" ", 1) for line in lines if " " in line)
    surah, ayah = int(meta["surah"]), int(meta["ayahs"].split()[0])
    channels = 2 if "channelCount=2" in meta["format"] else 1
    cap = np.frombuffer(args.capture.with_suffix(".pcm").read_bytes(), dtype=np.int16).reshape(-1, channels).astype(np.int32)
    src = np.frombuffer(subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(args.source / f"{surah:03d}.mp3"), "-ac", str(channels), "-f", "s16le", "-"],
        capture_output=True, check=True).stdout, dtype=np.int16).reshape(-1, channels).astype(np.int32)
    origin = json.loads(args.index.read_text())["clips"][f"{surah}:{ayah}"]["originSamples"]

    marks = [tuple(map(int, line.split()[1:])) for line in lines if line.startswith("pts ")]
    # The renderer offsets every timestamp; the first one is the verse's start, position 0.
    base = marks[0][0]
    problems = 0
    for pts_us, byte in marks:
        pts_us -= base
        # A decoder restarted mid-stream has no bit reservoir or overlap for its
        # first frames: compare a stretch after them.
        at = byte // (2 * channels) + SETTLE
        heard = cap[at:at + WINDOW]
        expected = origin + round(pts_us * RATE / 1_000_000) + SETTLE
        best, best_diff = None, None
        for x in range(max(0, expected - SEARCH), expected + SEARCH):
            diff = np.abs(heard - src[x:x + WINDOW]).max()
            if best_diff is None or diff < best_diff:
                best, best_diff = x, diff
                if diff <= TOLERANCE:
                    break
        found = best_diff is not None and best_diff <= TOLERANCE * 4
        error = best - expected if found else None
        print(f"pts {pts_us / 1000:.1f} ms, capture sample {at - SETTLE}: "
              + (f"recording sample {best}, error {error:+d} samples ({error * 1000 / RATE:+.1f} ms), max diff {best_diff}"
                 if found else f"not found within ±{SEARCH} samples (best max diff {best_diff})"))
        problems += not found or abs(error) > 2
    print("clock honest" if not problems else f"{problems} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
