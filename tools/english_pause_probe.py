#!/usr/bin/env python3
"""Reproduce the measurements behind English pacing's heard pauses (docs/ENGLISH_PACING.md).

Mirrors `EnglishPauses` (app/src/main/java/com/beautifulquran/ui/reader/EnglishPauses.kt):
per-hop loudness in dB, median-filtered, relative to the verse's 95th percentile;
deep quiet (-20 dB, >=180 ms, 80 ms bridge) anywhere, shallow quiet (-12 dB,
>=150 ms) only around a word the Mushaf marks for a stop.

    # Detector recall against Hani ar-Rifai's measured quiet table.
    python3 tools/english_pause_probe.py hani --verses 25

    # One verse: deep pauses, and the quiet at marked vs unmarked word boundaries.
    python3 tools/english_pause_probe.py verse --reciter Alafasy_128kbps --qdc 7 --surah 3 --ayah 7

Needs ffmpeg, numpy and network access (everyayah.com audio, quran.com qdc
timings). Downloads go to a fresh cache directory; nothing in the repo changes.
"""
import argparse
import json
import random
import sqlite3
import subprocess
import tempfile
import urllib.request
from pathlib import Path

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

ROOT = Path(__file__).resolve().parent.parent
QUIET_DB, MIN_MS = -20.0, 180
MARKED_DB, MARKED_MIN_MS = -12.0, 150
MEDIAN_MS, BRIDGE_MS, HOP_MS = 70, 80, 10
MARKED_BEFORE_MS, MARKED_AFTER_MS = 600, 800
STOP_MARKS = "ۖۗۘۚۛۜ"  # never U+06D9, which forbids a stop


def open_url(url: str):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "BeautifulQuran/1.0"}))


def fetch(url: str, dest: Path) -> Path:
    if not dest.exists():
        with open_url(url) as response:
            dest.write_bytes(response.read())
    return dest


def loudness(mp3: Path, sr: int = 16000) -> np.ndarray:
    """Median-filtered dB per HOP_MS hop, relative to the verse's 95th percentile."""
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", str(mp3), "-ac", "1", "-ar", str(sr), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    x = np.frombuffer(raw, np.float32)
    hop = sr * HOP_MS // 1000
    n = len(x) // hop
    db = 10 * np.log10((x[: n * hop].reshape(n, hop) ** 2).mean(1) + 1e-12)
    half = max(1, MEDIAN_MS // HOP_MS // 2)
    smooth = np.median(sliding_window_view(np.pad(db, half, mode="edge"), 2 * half + 1), axis=1)
    return smooth - np.percentile(db, 95)


def runs(db: np.ndarray, threshold: float, start: int = 0, stop: int | None = None) -> list[tuple[int, int]]:
    stop = len(db) if stop is None else stop
    out: list[list[int]] = []
    h = start
    while h < stop:
        if db[h] < threshold:
            s = h
            while h < stop and db[h] < threshold:
                h += 1
            a, b = s * HOP_MS, h * HOP_MS
            if out and a - out[-1][1] <= BRIDGE_MS:
                out[-1][1] = b
            else:
                out.append([a, b])
        else:
            h += 1
    return [tuple(r) for r in out]


def deep_pauses(db: np.ndarray) -> list[tuple[int, int]]:
    return [r for r in runs(db, QUIET_DB) if r[1] - r[0] >= MIN_MS]


def quiet_near(db: np.ndarray, boundary_ms: int, threshold: float) -> int:
    """Longest run below [threshold] around a word boundary, in ms."""
    a = max(0, (boundary_ms - MARKED_BEFORE_MS) // HOP_MS)
    b = min(len(db), (boundary_ms + MARKED_AFTER_MS) // HOP_MS)
    found = runs(db, threshold, a, b)
    return max((r[1] - r[0] for r in found), default=0)


def hani(args: argparse.Namespace, cache: Path) -> None:
    table = ROOT / "app/src/main/assets/silence/7.txt"
    truth: dict[tuple[int, int], list[tuple[int, int]]] = {}
    for line in table.read_text().splitlines():
        if line.startswith("#") or not line.strip():
            continue
        s, a, st, en = map(int, line.split())
        if st > 0:
            truth.setdefault((s, a), []).append((st, en))
    keys = random.Random(args.seed).sample(sorted(truth), args.verses)
    hit = total = 0
    for s, a in keys:
        mp3 = fetch(f"https://everyayah.com/data/Hani_Rifai_192kbps/{s:03d}{a:03d}.mp3", cache / f"hani_{s:03d}{a:03d}.mp3")
        found = deep_pauses(loudness(mp3))
        for st, en in truth[(s, a)]:
            total += 1
            match = [p for p in found if min(en, p[1]) - max(st, p[0]) > 0.5 * (en - st)]
            hit += bool(match)
            print(f"{s}:{a} table {st}-{en}  detected {match[0] if match else 'MISSED'}")
    print(f"recall {hit}/{total}")


def verse(args: argparse.Namespace, cache: Path) -> None:
    mp3 = fetch(f"https://everyayah.com/data/{args.reciter}/{args.surah:03d}{args.ayah:03d}.mp3",
                cache / f"{args.reciter}_{args.surah:03d}{args.ayah:03d}.mp3")
    db = loudness(mp3)
    print("deep pauses (ms):", deep_pauses(db))
    url = f"https://api.qurancdn.com/api/qdc/audio/reciters/{args.qdc}/audio_files?chapter={args.surah}&segments=true"
    with open_url(url) as response:
        files = json.load(response)["audio_files"][0]["verse_timings"]
    timing = next(v for v in files if v["verse_key"] == f"{args.surah}:{args.ayah}")
    base = timing["timestamp_from"]
    starts = {s[0]: s[1] - base for s in timing["segments"] if len(s) == 3}
    words = sqlite3.connect(ROOT / "data/quran.db").execute(
        "select position, arabic from words where surah_id=? and ayah_number=? order by position",
        (args.surah, args.ayah)).fetchall()
    for (position, arabic), _ in zip(words, words[1:]):
        boundary = starts.get(position + 1)
        if boundary is None:
            continue
        marked = any(c in STOP_MARKS for c in arabic)
        shallow = quiet_near(db, boundary, MARKED_DB)
        if marked or shallow >= MARKED_MIN_MS:
            kind = "marked  " if marked else "unmarked"
            print(f"{kind} word {position:>3} -> next at {boundary:>6} ms: below {MARKED_DB:.0f} dB for {shallow} ms")
    print("unmarked boundaries over", MARKED_MIN_MS, "ms are listed above; marked ones always are.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    h = sub.add_parser("hani", help="detector recall against assets/silence/7.txt")
    h.add_argument("--verses", type=int, default=25)
    h.add_argument("--seed", type=int, default=1)
    v = sub.add_parser("verse", help="one verse's pauses and marked-stop quiet")
    v.add_argument("--reciter", default="Alafasy_128kbps", help="everyayah folder")
    v.add_argument("--qdc", type=int, default=7, help="quran.com qdc reciter id (Alafasy = 7)")
    v.add_argument("--surah", type=int, required=True)
    v.add_argument("--ayah", type=int, required=True)
    args = parser.parse_args()
    cache = Path(tempfile.mkdtemp(prefix="bq-english-pauses-"))
    (hani if args.command == "hani" else verse)(args, cache)


if __name__ == "__main__":
    main()
