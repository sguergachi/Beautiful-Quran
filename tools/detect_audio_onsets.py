#!/usr/bin/env python3
"""Measure meaningful leading silence and total length of everyayah MP3s.

Only the opening HTTP byte range is fetched. ffmpeg decodes that prefix and
reports the first sustained non-silent sample; results at or above 250 ms are
written to tools/audio_onsets/ for build_db.py to apply. The same bytes carry
the file's own MPEG header, which gives its playable duration — the hard
ceiling build_db.py holds every timing row inside.

`--durations-only` refreshes just those ceilings from an 8 KiB range request,
so the guard can be re-measured without decoding every recording again.
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
CONTENT_RANGE_TOTAL = re.compile(r"/(\d+)$")
USER_AGENT = "beautiful-quran-onset-scan/1.0"
# Enough of the file to hold an ID3v2 tag and the first audio frame's Xing tag.
DURATION_RANGE_BYTES = 8 * 1024
FRAME_HEADER_BYTES = 4
SAMPLE_RATES = {3: (44100, 48000, 32000), 2: (22050, 24000, 16000), 0: (11025, 12000, 8000)}
BITRATES_MPEG1 = (0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
BITRATES_MPEG2 = (0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)


class IncompletePrefix(RuntimeError):
    """The decoded prefix ended before the opening silence did."""


def audio_url(slug, surah, ayah):
    return f"https://everyayah.com/data/{slug}/{surah:03d}{ayah:03d}.mp3"


def id3_size(data):
    """Length of a leading ID3v2 tag, whose size field is seven bits per byte."""
    if data[:3] != b"ID3":
        return 0
    size = 0
    for byte in data[6:10]:
        size = (size << 7) | (byte & 0x7F)
    return 10 + size


def parse_frame_header(data, i):
    """Read one MPEG Layer III frame header, or None if this is not one."""
    if data[i] != 0xFF or (data[i + 1] & 0xE0) != 0xE0:
        return None
    version = (data[i + 1] >> 3) & 3  # 3=MPEG1, 2=MPEG2, 0=MPEG2.5
    if version == 1 or (data[i + 1] >> 1) & 3 != 1:  # layer III only
        return None
    bitrate_index = (data[i + 2] >> 4) & 15
    rate_index = (data[i + 2] >> 2) & 3
    if bitrate_index in (0, 15) or rate_index == 3:
        return None
    bitrates = BITRATES_MPEG1 if version == 3 else BITRATES_MPEG2
    return (
        version,
        bitrates[bitrate_index] * 1000,
        SAMPLE_RATES[version][rate_index],
        ((data[i + 3] >> 6) & 3) == 3,  # mono
        (data[i + 2] >> 1) & 1,  # padding bit
    )


def frame_size(frame):
    """Return the byte length of one Layer III frame from its header."""
    version, bitrate, sample_rate, _, padding = frame
    # Layer III uses 1,152 samples/frame in MPEG1 and 576 in MPEG2/2.5.
    coefficient = 144 if version == 3 else 72
    return coefficient * bitrate // sample_rate + padding


def has_following_frame(data, start, frame):
    """Reject header-shaped ID3/payload bytes before trusting a candidate."""
    next_start = start + frame_size(frame)
    if next_start + FRAME_HEADER_BYTES > len(data):
        return False
    following = parse_frame_header(data, next_start)
    return following is not None and following[0] == frame[0] and following[2] == frame[2]


def duration_ms(prefix, total_bytes):
    """Playable length of an everyayah MP3, read from its own header.

    The directory name's bitrate is not reliable — some files are encoded well
    away from it — so the length comes from the file itself: an exact frame
    count when the encoder wrote a Xing/Info tag (which everyayah's do), else
    the constant-bitrate length of the first frame. Both round a tenth of a
    second long, which only ever widens the ceiling the build gates on.
    """
    start = id3_size(prefix)
    for i in range(start, len(prefix) - 4):
        frame = parse_frame_header(prefix, i)
        if frame is None or not has_following_frame(prefix, i, frame):
            continue
        version, bitrate, sample_rate, mono, _ = frame
        samples = 1152 if version == 3 else 576
        side_info = (17 if mono else 32) if version == 3 else (9 if mono else 17)
        tag = i + FRAME_HEADER_BYTES + side_info
        if prefix[tag:tag + 4] in (b"Xing", b"Info"):
            flags = int.from_bytes(prefix[tag + 4:tag + 8], "big")
            frames = int.from_bytes(prefix[tag + 8:tag + 12], "big")
            if flags & 1 and frames:
                return round(frames * samples * 1000 / sample_rate)
        return round((total_bytes - i) * 8000 / bitrate)
    raise RuntimeError("no MPEG audio frame in the opening bytes")


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
    """Return this verse's (onset ms, playable duration ms)."""
    url = audio_url(slug, *verse)
    for range_bytes in (INITIAL_RANGE_BYTES, RETRY_RANGE_BYTES):
        prefix, total_bytes = fetch_prefix(url, range_bytes)
        try:
            return analyze_prefix(prefix), duration_ms(prefix, total_bytes)
        except IncompletePrefix:
            continue
    raise RuntimeError("leading silence exceeds extended decoded prefix")


def measure_duration(slug, verse):
    """Return this verse's playable duration without decoding any audio."""
    prefix, total_bytes = fetch_prefix(
        audio_url(slug, *verse), DURATION_RANGE_BYTES
    )
    return duration_ms(prefix, total_bytes)


def measure_local_duration(audio_root, slug, verse):
    """Read the same MPEG-header duration from an exact cached EveryAyah MP3."""
    path = audio_root / f"{slug}_{verse[0]:03d}{verse[1]:03d}.mp3"
    if not path.is_file():
        raise FileNotFoundError(path)
    with path.open("rb") as audio:
        prefix = audio.read(DURATION_RANGE_BYTES)
    return duration_ms(prefix, path.stat().st_size)


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


def detector_metadata():
    """The settings this scan was produced with, recorded beside its results."""
    return {
        "noiseDb": NOISE_DB,
        "sustainedMs": SUSTAINED_MS,
        "minimumOffsetMs": MIN_OFFSET_MS,
        "analysisMs": ANALYSIS_SECONDS * 1000,
        "initialRangeBytes": INITIAL_RANGE_BYTES,
        "retryRangeBytes": RETRY_RANGE_BYTES,
    }


def refresh_durations(slug, work, workers, output, measure=measure_duration):
    """Re-measure only the duration ceilings, leaving recorded onsets alone."""
    if not output.exists():
        raise SystemExit(f"{output} is missing — run a full scan first")
    payload = json.loads(output.read_text())
    measured = scan(slug, work, workers, measure)
    payload["schema"] = 2
    payload["detector"] = detector_metadata()
    payload["durations"] = sorted_by_verse(
        {verse_key(verse): ms for verse, ms in measured.items()}
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
    parser.add_argument(
        "--audio-root",
        type=Path,
        help="exact cached EveryAyah MP3 directory for an offline duration refresh",
    )
    parser.add_argument("--workers", type=int, default=12)
    args = parser.parse_args()
    if not OUT.exists():
        raise SystemExit(f"{OUT} is missing")
    if args.audio_root and not args.durations_only:
        raise SystemExit("--audio-root requires --durations-only")
    if args.audio_root and not args.audio_root.is_dir():
        raise SystemExit(f"--audio-root is not a directory: {args.audio_root}")

    work = verses(args.verse)
    output = AUDIO_ONSETS_DIR / f"{args.reciter}.json"
    if args.audio_root:
        def measure(slug, verse):
            return measure_local_duration(args.audio_root, slug, verse)
    else:
        measure = measure_duration
    if args.durations_only:
        if args.verse:
            print(
                f"{verse_key(work[0])} duration: "
                f"{measure(args.reciter, work[0])} ms"
            )
            return
        refresh_durations(args.reciter, work, args.workers, output, measure)
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
        "detector": detector_metadata(),
        "scannedAyahs": len(work),
        "offsets": sorted_by_verse(onsets),
        "durations": sorted_by_verse(
            {verse_key(verse): ms for verse, (_, ms) in measured.items()}
        ),
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    print(f"wrote {len(onsets)} offsets and {len(work)} durations to {output}")


if __name__ == "__main__":
    sys.exit(main())
