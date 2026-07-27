#!/usr/bin/env python3
"""Measure meaningful leading silence and total length of everyayah MP3s.

Only the opening HTTP byte range is fetched. ffmpeg decodes that prefix and
reports the first sustained non-silent sample; results at or above 250 ms are
written to tools/audio_onsets/ for build_db.py to apply. The same request
reports each file's byte length, which becomes the recording's playable
duration — the hard ceiling build_db.py holds every timing row inside.

`--durations-only` refreshes just those ceilings from HEAD requests, so the
guard can be re-measured without decoding every recording again.
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

INITIAL_RANGE_BYTES = 96 * 1024
RETRY_RANGE_BYTES = 256 * 1024
ANALYSIS_SECONDS = 8
NOISE_DB = -40
SUSTAINED_MS = 80
MIN_OFFSET_MS = 250
SILENCE_END = re.compile(r"silence_end: ([0-9.]+)")
OUT_TIME_US = re.compile(r"out_time_us=(\d+)")
SLUG_BITRATE = re.compile(r"_(\d+)kbps$")
CONTENT_RANGE_TOTAL = re.compile(r"/(\d+)$")
USER_AGENT = "beautiful-quran-onset-scan/1.0"


class IncompletePrefix(RuntimeError):
    """The decoded prefix ended before the opening silence did."""


def audio_url(slug, surah, ayah):
    return f"https://everyayah.com/data/{slug}/{surah:03d}{ayah:03d}.mp3"


def bitrate_bps(slug):
    """everyayah publishes one constant bitrate per reciter, named in the slug."""
    match = SLUG_BITRATE.search(slug)
    if not match:
        raise SystemExit(f"cannot read a bitrate from {slug}")
    return int(match.group(1)) * 1000


def duration_ms(total_bytes, slug):
    """Playable length of a constant-bitrate file, from its byte length.

    ID3 tags round this ~130 ms long, which only ever widens the ceiling the
    build guard checks against, never narrows it below the real audio.
    """
    return total_bytes * 8000 // bitrate_bps(slug)


def fetch_prefix(url, range_bytes):
    """Return the opening bytes plus the file's full byte length."""
    request = urllib.request.Request(
        url,
        headers={"Range": f"bytes=0-{range_bytes - 1}", "User-Agent": USER_AGENT},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        prefix = response.read(range_bytes)
        total = CONTENT_RANGE_TOTAL.search(response.headers.get("Content-Range", ""))
    return prefix, int(total.group(1)) if total else len(prefix)


def parse_onset(log, progress):
    """Read a real silence transition, rejecting ffmpeg's EOF flush."""
    if not re.search(r"silence_start: 0(?:\.0+)?(?:\s|$)", log):
        return 0
    match = SILENCE_END.search(log)
    decoded = OUT_TIME_US.findall(progress)
    if not match or not decoded:
        raise IncompletePrefix("leading silence exceeds decoded prefix")
    onset = round(float(match.group(1)) * 1000)
    decoded_ms = max(map(int, decoded)) / 1000
    if decoded_ms - onset < SUSTAINED_MS:
        raise IncompletePrefix("leading silence reaches decoded prefix end")
    return onset


def analyze_prefix(audio):
    result = subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-nostats", "-f", "mp3", "-i", "pipe:0",
            "-af", f"silencedetect=noise={NOISE_DB}dB:d={SUSTAINED_MS / 1000}",
            "-t", str(ANALYSIS_SECONDS), "-f", "null", "-", "-progress", "pipe:1",
        ],
        input=audio,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    log = result.stderr.decode(errors="replace")
    if result.returncode:
        raise RuntimeError(log.strip().splitlines()[-1] if log.strip() else "ffmpeg failed")
    return parse_onset(log, result.stdout.decode(errors="replace"))


def detect_onset(slug, verse):
    """Return this verse's (onset ms, total byte length)."""
    url = audio_url(slug, *verse)
    for range_bytes in (INITIAL_RANGE_BYTES, RETRY_RANGE_BYTES):
        prefix, total_bytes = fetch_prefix(url, range_bytes)
        try:
            return analyze_prefix(prefix), total_bytes
        except IncompletePrefix:
            continue
    raise RuntimeError("leading silence exceeds extended decoded prefix")


def measure_length(slug, verse):
    """Return this verse's total byte length without decoding any audio."""
    request = urllib.request.Request(
        audio_url(slug, *verse), method="HEAD", headers={"User-Agent": USER_AGENT}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return int(response.headers["Content-Length"])


def scan(slug, work, workers, measure):
    """Run one measurement across the verse list, reporting progress."""
    measured = {}
    failures = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {pool.submit(measure, slug, verse): verse for verse in work}
        for completed, future in enumerate(as_completed(futures), 1):
            verse = futures[future]
            try:
                measured[verse] = future.result()
            except Exception as e:
                failures.append((verse, str(e)))
            if completed % 100 == 0 or completed == len(work):
                print(f"\r{completed}/{len(work)} scanned", end="", flush=True)
    print()
    if failures:
        for verse, error in failures[:20]:
            print(f"  {verse[0]}:{verse[1]}: {error}", file=sys.stderr)
        raise SystemExit(f"{len(failures)} audio file(s) could not be measured")
    return measured


def verse_key(verse):
    return f"{verse[0]}:{verse[1]}"


def sorted_by_verse(values):
    return dict(
        sorted(values.items(), key=lambda item: tuple(map(int, item[0].split(":"))))
    )


def verses(selected):
    if selected:
        try:
            surah, ayah = (int(part) for part in selected.split(":"))
            return [(surah, ayah)]
        except ValueError as e:
            raise SystemExit("--verse must be SURAH:AYAH") from e
    with sqlite3.connect(OUT) as db:
        return list(db.execute("SELECT surah_id,ayah_number FROM ayahs ORDER BY 1,2"))


def refresh_durations(slug, work, workers, output):
    """Re-measure only the duration ceilings, leaving recorded onsets alone."""
    if not output.exists():
        raise SystemExit(f"{output} is missing — run a full scan first")
    payload = json.loads(output.read_text())
    lengths = scan(slug, work, workers, measure_length)
    payload["schema"] = 2
    payload["detector"]["bitrateBps"] = bitrate_bps(slug)
    payload["durations"] = sorted_by_verse(
        {verse_key(verse): duration_ms(total, slug) for verse, total in lengths.items()}
    )
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    print(f"wrote {len(payload['durations'])} durations to {output}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reciter", required=True, choices=[r[1] for r in RECITERS])
    parser.add_argument("--verse", help="scan one SURAH:AYAH instead of the full Quran")
    parser.add_argument(
        "--durations-only",
        action="store_true",
        help="refresh recording lengths only, without decoding any audio",
    )
    parser.add_argument("--workers", type=int, default=12)
    args = parser.parse_args()
    if not OUT.exists():
        raise SystemExit(f"{OUT} is missing")

    work = verses(args.verse)
    output = AUDIO_ONSETS_DIR / f"{args.reciter}.json"
    if args.durations_only:
        if args.verse:
            total = measure_length(args.reciter, work[0])
            print(f"{verse_key(work[0])} duration: {duration_ms(total, args.reciter)} ms")
            return
        refresh_durations(args.reciter, work, args.workers, output)
        return

    if not shutil.which("ffmpeg"):
        raise SystemExit("ffmpeg is required")
    measured = scan(args.reciter, work, args.workers, detect_onset)
    onsets = {
        verse_key(verse): onset
        for verse, (onset, _) in measured.items()
        if onset >= MIN_OFFSET_MS
    }
    if args.verse:
        print(f"{verse_key(work[0])} onset: {onsets.get(verse_key(work[0]), 0)} ms")
        return

    AUDIO_ONSETS_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": 2,
        "reciterId": next(r[0] for r in RECITERS if r[1] == args.reciter),
        "reciterSlug": args.reciter,
        "detector": {
            "noiseDb": NOISE_DB,
            "sustainedMs": SUSTAINED_MS,
            "minimumOffsetMs": MIN_OFFSET_MS,
            "analysisMs": ANALYSIS_SECONDS * 1000,
            "initialRangeBytes": INITIAL_RANGE_BYTES,
            "retryRangeBytes": RETRY_RANGE_BYTES,
            "bitrateBps": bitrate_bps(args.reciter),
        },
        "scannedAyahs": len(work),
        "offsets": sorted_by_verse(onsets),
        "durations": sorted_by_verse(
            {
                verse_key(verse): duration_ms(total, args.reciter)
                for verse, (_, total) in measured.items()
            }
        ),
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    print(f"wrote {len(onsets)} offsets and {len(work)} durations to {output}")


if __name__ == "__main__":
    sys.exit(main())
