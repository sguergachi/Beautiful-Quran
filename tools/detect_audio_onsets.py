#!/usr/bin/env python3
"""Measure meaningful leading silence in everyayah MP3s.

Only the opening HTTP byte range is fetched. ffmpeg decodes that prefix and
reports the first sustained non-silent sample; results at or above 250 ms are
written to tools/audio_onsets/ for build_db.py to apply.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import urllib.request

from build_db import AUDIO_ONSETS_DIR, OUT, RECITERS

RANGE_BYTES = 96 * 1024
NOISE_DB = -40
SUSTAINED_MS = 80
MIN_OFFSET_MS = 250
SILENCE_END = re.compile(r"silence_end: ([0-9.]+)")


def audio_url(slug, surah, ayah):
    return f"https://everyayah.com/data/{slug}/{surah:03d}{ayah:03d}.mp3"


def fetch_prefix(url):
    request = urllib.request.Request(
        url,
        headers={
            "Range": f"bytes=0-{RANGE_BYTES - 1}",
            "User-Agent": "beautiful-quran-onset-scan/1.0",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read(RANGE_BYTES)


def detect_onset(slug, verse):
    surah, ayah = verse
    audio = fetch_prefix(audio_url(slug, surah, ayah))
    result = subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-nostats", "-f", "mp3", "-i", "pipe:0",
            "-af", f"silencedetect=noise={NOISE_DB}dB:d={SUSTAINED_MS / 1000}",
            "-t", "5", "-f", "null", "-",
        ],
        input=audio,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        check=False,
    )
    log = result.stderr.decode(errors="replace")
    if result.returncode:
        raise RuntimeError(log.strip().splitlines()[-1] if log.strip() else "ffmpeg failed")
    if not re.search(r"silence_start: 0(?:\.0+)?(?:\s|$)", log):
        return verse, 0
    match = SILENCE_END.search(log)
    if not match:
        raise RuntimeError("leading silence exceeds decoded prefix")
    return verse, round(float(match.group(1)) * 1000)


def verses(selected):
    if selected:
        try:
            surah, ayah = (int(part) for part in selected.split(":"))
            return [(surah, ayah)]
        except ValueError as e:
            raise SystemExit("--verse must be SURAH:AYAH") from e
    with sqlite3.connect(OUT) as db:
        return list(db.execute("SELECT surah_id,ayah_number FROM ayahs ORDER BY 1,2"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reciter", required=True, choices=[r[1] for r in RECITERS])
    parser.add_argument("--verse", help="scan one SURAH:AYAH instead of the full Quran")
    parser.add_argument("--workers", type=int, default=12)
    args = parser.parse_args()
    if not OUT.exists():
        raise SystemExit(f"{OUT} is missing")
    if not shutil.which("ffmpeg"):
        raise SystemExit("ffmpeg is required")

    reciter_id = next(r[0] for r in RECITERS if r[1] == args.reciter)
    work = verses(args.verse)
    offsets = {}
    failures = []
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = {pool.submit(detect_onset, args.reciter, verse): verse for verse in work}
        for completed, future in enumerate(as_completed(futures), 1):
            verse = futures[future]
            try:
                (_, onset) = future.result()
                if onset >= MIN_OFFSET_MS:
                    offsets[f"{verse[0]}:{verse[1]}"] = onset
            except Exception as e:
                failures.append((verse, str(e)))
            if completed % 100 == 0 or completed == len(work):
                print(f"\r{completed}/{len(work)} scanned, {len(offsets)} offsets", end="", flush=True)
    print()
    if failures:
        for verse, error in failures[:20]:
            print(f"  {verse[0]}:{verse[1]}: {error}", file=sys.stderr)
        raise SystemExit(f"{len(failures)} audio file(s) could not be measured")
    if args.verse:
        key = f"{work[0][0]}:{work[0][1]}"
        print(f"{key} onset: {offsets.get(key, 0)} ms")
        return

    AUDIO_ONSETS_DIR.mkdir(parents=True, exist_ok=True)
    output = AUDIO_ONSETS_DIR / f"{args.reciter}.json"
    payload = {
        "schema": 1,
        "reciterId": reciter_id,
        "reciterSlug": args.reciter,
        "detector": {
            "noiseDb": NOISE_DB,
            "sustainedMs": SUSTAINED_MS,
            "minimumOffsetMs": MIN_OFFSET_MS,
            "rangeBytes": RANGE_BYTES,
        },
        "scannedAyahs": len(work),
        "offsets": dict(sorted(offsets.items(), key=lambda item: tuple(map(int, item[0].split(":"))))),
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    print(f"wrote {len(offsets)} offsets to {output}")


if __name__ == "__main__":
    sys.exit(main())
