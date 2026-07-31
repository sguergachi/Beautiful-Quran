#!/usr/bin/env python3
"""Generate V2 from pinned QUA letters only when the decoded audio matches."""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import sqlite3
import subprocess
import urllib.request
import zipfile
from pathlib import Path

import numpy as np

from generate_timing_v2 import audio_file
from qua_timing import (
    ALAFASY_RELEASE_SHA256,
    LETTER_VOCAB_SHA256,
    MIN_CORRELATION,
    MIN_PEAK_MARGIN,
    QPC_HAFS_SHA256,
    QUA_RELEASE,
    QUA_REVISION,
    accepted_clock,
    acoustic_keyframes,
    match_audio_clock,
    occurrence_letters,
    source_groups,
)

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
GENERATOR = "sync_lab/generate_qua_timing_v2.py@1"
BASE_URL = (
    "https://github.com/Wider-Community/quranic-universal-audio/"
    f"releases/download/{QUA_RELEASE}"
)


def checked_download(url: str, path: Path, sha256: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        urllib.request.urlretrieve(url, path)
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != sha256:
        raise ValueError(f"{path.name}: expected sha256 {sha256}, got {actual}")
    return path


def decode(
    path: Path,
    sample_rate: int,
    start_seconds: float | None = None,
    duration_seconds: float | None = None,
) -> np.ndarray:
    command = ["ffmpeg", "-v", "error"]
    if start_seconds is not None:
        command += ["-ss", f"{start_seconds:.3f}"]
    command += ["-i", str(path)]
    if duration_seconds is not None:
        command += ["-t", f"{duration_seconds:.3f}"]
    command += ["-ac", "1", "-ar", str(sample_rate), "-f", "f32le", "pipe:1"]
    return np.frombuffer(subprocess.check_output(command), dtype=np.float32)


def complete_sequence(positions: list[int], word_count: int) -> bool:
    expected = 1
    seen = set()
    for position in positions:
        if position not in seen:
            if position != expected:
                return False
            seen.add(position)
            expected += 1
    return expected == word_count + 1 and all(
        1 <= position <= word_count for position in positions
    )


def load_words(db: Path, surah: int, ayah: int) -> list[str]:
    with sqlite3.connect(db) as connection:
        return [
            row[0]
            for row in connection.execute(
                "SELECT arabic FROM words WHERE surah_id=? AND ayah_number=? "
                "ORDER BY position",
                (surah, ayah),
            )
        ]


def build_segments(
    record: list,
    rendered_words: list[str],
    canonical_words: dict[int, str],
    letter_vocab: set[str],
    source_zero_ms: float,
    audio_duration_ms: int,
) -> list[dict]:
    word_rows, letter_rows = record[1], record[2]
    positions = [int(row[0]) for row in word_rows]
    if not complete_sequence(positions, len(rendered_words)):
        return []
    chunks = occurrence_letters(
        word_rows,
        letter_rows,
        canonical_words,
        letter_vocab,
    )
    if chunks is None:
        return []

    segments = []
    for (position, source_start, source_end), letters in zip(word_rows, chunks):
        groups = source_groups(
            canonical_words[position],
            rendered_words[position - 1],
            letter_vocab,
        )
        keyframes = acoustic_keyframes(source_start, source_end, letters, groups)
        start = round(source_start - source_zero_ms)
        end = round(source_end - source_zero_ms)
        if not keyframes or start < 0 or end <= start or end > audio_duration_ms:
            return []
        segments.append({
            "position": position,
            "startMs": start,
            "endMs": end,
            "keyframes": keyframes,
        })
    if any(
        left["startMs"] >= right["startMs"]
        for left, right in zip(segments, segments[1:])
    ):
        return []
    return segments


def main() -> int:
    parser = argparse.ArgumentParser()
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--surah", type=int)
    selection.add_argument("--all", action="store_true")
    parser.add_argument("--ayah-from", type=int, default=1)
    parser.add_argument("--ayah-to", type=int)
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=LAB / "audio" / "Alafasy_128kbps",
    )
    parser.add_argument("--source-audio-dir", type=Path, default=LAB / "audio" / "qua_alafasy")
    parser.add_argument("--cache-dir", type=Path, default=ROOT / "tools/.cache/qua")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    release_zip = checked_download(
        f"{BASE_URL}/mishary_rashid_al_afasy_mp3quran.zip",
        args.cache_dir / f"alafasy-{QUA_RELEASE}.zip",
        ALAFASY_RELEASE_SHA256,
    )
    qpc_path = checked_download(
        f"{BASE_URL}/qpc_hafs.json",
        args.cache_dir / f"qpc-hafs-{QUA_RELEASE}.json",
        QPC_HAFS_SHA256,
    )
    vocab_path = checked_download(
        f"{BASE_URL}/letter_vocab_hafs_qpc.csv",
        args.cache_dir / f"letter-vocab-{QUA_RELEASE}.csv",
        LETTER_VOCAB_SHA256,
    )
    qpc = json.loads(qpc_path.read_text(encoding="utf-8"))
    with vocab_path.open(encoding="utf-8") as source:
        letter_vocab = {row["char"] for row in csv.DictReader(source)}
    with zipfile.ZipFile(release_zip) as archive:
        catalog = json.loads(archive.read("catalog.json"))
        compressed = archive.read("letter_timestamps.json.gz")
        timing_data = json.loads(gzip.decompress(compressed))

    if args.all:
        selected = sorted(
            (tuple(map(int, key.split(":"))) for key in timing_data if key != "_meta")
        )
    else:
        last = args.ayah_to or max(
            int(key.split(":")[1])
            for key in timing_data
            if key.startswith(f"{args.surah}:")
        )
        selected = [
            (args.surah, ayah)
            for ayah in range(args.ayah_from, last + 1)
        ]

    rows = []
    sample_rate = 8_000
    source_hashes = {}
    for surah, ayah in selected:
        key = f"{surah}:{ayah}"
        record = timing_data.get(key)
        if record is None:
            print(f"{key} abstained: missing QUA row")
            continue
        source_audio = args.source_audio_dir / f"{surah:03d}.mp3"
        if not source_audio.exists():
            source_audio.parent.mkdir(parents=True, exist_ok=True)
            urllib.request.urlretrieve(
                catalog["audio"]["chapter_urls"][str(surah)],
                source_audio,
            )
        if surah not in source_hashes:
            source_hashes[surah] = hashlib.sha256(source_audio.read_bytes()).hexdigest()
        source_sha256 = source_hashes[surah]

        rendered_words = load_words(args.db, surah, ayah)
        canonical_words = {
            position: qpc.get(f"{key}:{position}", {}).get("text", "")
            for position in range(1, len(rendered_words) + 1)
        }
        audio = audio_file(args.audio_dir, "Alafasy_128kbps", surah, ayah)
        local = decode(audio, sample_rate)
        duration = len(local) / sample_rate
        source_start = max(0.0, record[0][0] / 1000.0 - 1.0)
        source_window = decode(
            source_audio,
            sample_rate,
            start_seconds=source_start,
            duration_seconds=duration + 2.0,
        )
        match = match_audio_clock(
            source_window,
            local,
            sample_rate,
            source_start * 1000.0,
        )
        segments = (
            build_segments(
                record,
                rendered_words,
                canonical_words,
                letter_vocab,
                match.source_zero_ms,
                round(duration * 1000),
            )
            if accepted_clock(match, expected_source_zero_ms=record[0][0])
            else []
        )
        print(
            f"{key} corr={match.correlation if match else 0:.3f} "
            f"margin={match.peak_margin if match else 0:.3f} "
            f"{'accepted' if segments else 'abstained'}"
        )
        if segments:
            rows.append({
                "surah": surah,
                "ayah": ayah,
                "gateScore": match.correlation,
                "clockCorrelation": match.correlation,
                "clockPeakMargin": match.peak_margin,
                "sourceZeroMs": round(match.source_zero_ms, 3),
                "audioSha256": hashlib.sha256(audio.read_bytes()).hexdigest(),
                "sourceAudioSha256": source_sha256,
                "segments": segments,
            })

    payload = {
        "schema": 2,
        "reciterId": 1,
        "reciter": "Alafasy_128kbps",
        "generator": GENERATOR,
        "source": f"Wider-Community/quranic-universal-audio@{QUA_RELEASE}",
        "sourceRevision": QUA_REVISION,
        "sourceAssetSha256": ALAFASY_RELEASE_SHA256,
        "minimumGateScore": MIN_CORRELATION,
        "minimumPeakMargin": MIN_PEAK_MARGIN,
        "rows": rows,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    print(f"Wrote {args.out} ({len(rows)} accepted rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
