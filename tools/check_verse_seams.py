#!/usr/bin/env python3
"""Check a captured playback of range-sourced verses against the surah recording.

`VerseClipSeamCaptureTest` (app/src/androidTest) plays verses back to back
through the real player, decoder and seam processing on a device and writes the
PCM it was handed. Pull its folder and point this at it:

    adb shell am instrument -w -e class com.beautifulquran.playback.VerseClipSeamCaptureTest \\
        com.beautifulquran.test/androidx.test.runner.AndroidJUnitRunner
    adb pull /data/data/com.beautifulquran/files/seam-capture/ /tmp/
    tools/check_verse_seams.py /tmp/seam-capture/109 --source DIR_OF_NNN.mp3

Each verse must be exactly its stretch of the recording (a sample or two of
decoder rounding allowed), and verses must follow each other with no sample
inserted or dropped: a seam inside connected recitation is then inaudible.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

INDEX = Path(__file__).resolve().parent / "timing_sources" / "tawfeek-verse-index.json"
TOLERANCE = 8  # 16-bit units; two decoders round a few samples differently


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("capture", type=Path, help="capture path without extension (.pcm and .txt)")
    parser.add_argument("--source", type=Path, required=True, help="dir of NNN.mp3 surah files")
    parser.add_argument("--index", type=Path, default=INDEX)
    args = parser.parse_args()

    meta = dict(line.split(" ", 1) for line in args.capture.with_suffix(".txt").read_text().splitlines() if " " in line)
    surah = int(meta["surah"])
    channels = 2 if "channelCount=2" in meta["format"] else 1
    cap = np.frombuffer(args.capture.with_suffix(".pcm").read_bytes(), dtype=np.int16).reshape(-1, channels).astype(np.int32)
    src = np.frombuffer(subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(args.source / f"{surah:03d}.mp3"), "-ac", str(channels), "-f", "s16le", "-"],
        capture_output=True, check=True).stdout, dtype=np.int16).reshape(-1, channels).astype(np.int32)
    clips = json.loads(args.index.read_text())["clips"]
    lo, hi = map(int, meta["ayahs"].split())
    pos, problems = 0, 0
    for ayah in range(lo, hi + 1):
        clip = clips[f"{surah}:{ayah}"]
        origin, duration = clip["originSamples"], clip["durationSamples"]
        heard = cap[pos:pos + duration]
        if len(heard) < duration:
            print(f"{surah}:{ayah}: capture ends {duration - len(heard)} samples early")
            return 1
        diff = np.abs(heard - src[origin:origin + duration]).max(axis=1)
        bad = np.nonzero(diff > TOLERANCE)[0]
        status = "exact" if not len(bad) else f"{len(bad)} samples differ, first at {bad[0]} (max {diff.max()})"
        print(f"{surah}:{ayah}: {duration} samples, {status}")
        problems += bool(len(bad))
        pos += duration
    extra = cap[pos:]
    if len(extra) and np.abs(extra).max() > TOLERANCE:
        print(f"{len(extra)} samples of sound after the last verse")
        problems += 1
    print("seamless" if not problems else f"{problems} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
