#!/usr/bin/env python3
"""Full QUA Alafasy → timings_v2 rows (structure + letters, verse-relative reclock).

Unlike generate_qua_timing_v2.py@1 (same-take xcorr only, ~205 rows), this
imports **every** QUA ayah that builds clean segments:

- Structure from QUA word order (phrase re-says preserved, e.g. 6:10).
- Letter keyframes from QUA letter tier.
- Clock: verse-relative to the EveryAyah ayah file (optional scale-to-fit).

Priority merge still puts Lab gold above this lane. Mono CTC must not invent
structure — see docs/V2_STRUCTURE.md.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

from generate_qua_timing_v2 import complete_sequence, load_words
from generate_timing_v2 import audio_file
from qua_timing import (
    ALAFASY_RELEASE_SHA256,
    LETTER_VOCAB_SHA256,
    QPC_HAFS_SHA256,
    QUA_RELEASE,
    QUA_REVISION,
    acoustic_keyframes,
    fallback_word_keyframe,
    occurrence_letters,
    source_groups,
)

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
GENERATOR = "sync_lab/generate_qua_full_v2.py@1"
# Soft identity gate for load_timing_v2 (structure-first lane).
MINIMUM_GATE_SCORE = 0.0


def media_duration_ms(path: Path) -> int:
    out = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ],
        text=True,
    )
    return max(1, int(round(float(out.strip()) * 1000)))


def build_segments_verse_relative(
    record: list,
    rendered_words: list[str],
    canonical_words: dict[int, str],
    letter_vocab: set[str],
    audio_duration_ms: int,
) -> list[dict] | None:
    """Map QUA surah-absolute times → ayah-relative EveryAyah clock."""
    verse_start = int(record[0][0])
    word_rows, letter_rows = record[1], record[2]
    positions = [int(row[0]) for row in word_rows]
    if not complete_sequence(positions, len(rendered_words)):
        return None
    chunks = occurrence_letters(
        word_rows,
        letter_rows,
        canonical_words,
        letter_vocab,
    )
    if chunks is None:
        return None

    # Scale if QUA span overruns the local EveryAyah duration slightly.
    raw_end = max(int(row[2]) for row in word_rows) - verse_start
    scale = 1.0
    if raw_end > audio_duration_ms and raw_end > 0:
        scale = max(0.5, (audio_duration_ms - 40) / raw_end)

    segments: list[dict] = []
    for (position, source_start, source_end), letters in zip(word_rows, chunks):
        groups = source_groups(
            canonical_words[int(position)],
            rendered_words[int(position) - 1],
            letter_vocab,
        )
        start = int(round((int(source_start) - verse_start) * scale))
        end = int(round((int(source_end) - verse_start) * scale))
        start = max(0, start)
        end = max(start + 1, end)
        if end > audio_duration_ms:
            end = audio_duration_ms
        if end <= start:
            return None

        # Letter walls in the same ayah-relative clock as start/end.
        rel_letters = [
            [
                int(row[0]),
                row[1],
                int(round((int(row[2]) - verse_start) * scale)),
                int(round((int(row[3]) - verse_start) * scale)),
            ]
            for row in letters
        ]

        keyframes: list[dict] = []
        if groups:
            keyframes = acoustic_keyframes(start, end, rel_letters, groups)
        if not keyframes:
            keyframes = fallback_word_keyframe(start, end)
        if not keyframes:
            return None
        segments.append({
            "position": int(position),
            "startMs": start,
            "endMs": end,
            "keyframes": keyframes,
        })

    if any(
        left["startMs"] >= right["startMs"]
        for left, right in zip(segments, segments[1:])
    ):
        return None
    if segments[-1]["endMs"] > audio_duration_ms + 1:
        return None
    return segments


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="All QUA ayahs")
    parser.add_argument("--surah", type=int)
    parser.add_argument("--ayah-from", type=int, default=1)
    parser.add_argument("--ayah-to", type=int)
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=LAB / "audio" / "Alafasy_128kbps",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=ROOT / "tools/.cache/qua",
    )
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0, help="Debug: max rows")
    args = parser.parse_args()

    release_zip = args.cache_dir / f"alafasy-{QUA_RELEASE}.zip"
    qpc_path = args.cache_dir / f"qpc-hafs-{QUA_RELEASE}.json"
    vocab_path = args.cache_dir / f"letter-vocab-{QUA_RELEASE}.csv"
    for path, digest in (
        (release_zip, ALAFASY_RELEASE_SHA256),
        (qpc_path, QPC_HAFS_SHA256),
        (vocab_path, LETTER_VOCAB_SHA256),
    ):
        if not path.exists():
            raise SystemExit(f"missing {path}; run generate_qua_timing_v2 once to fetch")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != digest:
            raise SystemExit(f"{path.name}: sha256 mismatch")

    qpc = json.loads(qpc_path.read_text(encoding="utf-8"))
    with vocab_path.open(encoding="utf-8") as source:
        letter_vocab = {row["char"] for row in csv.DictReader(source)}
    with zipfile.ZipFile(release_zip) as archive:
        timing_data = json.loads(gzip.decompress(archive.read("letter_timestamps.json.gz")))

    if args.all:
        selected = sorted(
            (tuple(map(int, key.split(":"))) for key in timing_data if key != "_meta")
        )
    else:
        if not args.surah:
            raise SystemExit("pass --all or --surah")
        last = args.ayah_to or max(
            int(key.split(":")[1])
            for key in timing_data
            if key.startswith(f"{args.surah}:")
        )
        selected = [
            (args.surah, ayah) for ayah in range(args.ayah_from, last + 1)
        ]

    rows = []
    n_fail = 0
    for surah, ayah in selected:
        if args.limit and len(rows) >= args.limit:
            break
        key = f"{surah}:{ayah}"
        record = timing_data.get(key)
        if record is None:
            n_fail += 1
            continue
        rendered = load_words(args.db, surah, ayah)
        if not rendered:
            n_fail += 1
            continue
        canonical = {
            position: qpc.get(f"{key}:{position}", {}).get("text", "")
            for position in range(1, len(rendered) + 1)
        }
        try:
            audio = audio_file(args.audio_dir, "Alafasy_128kbps", surah, ayah)
            duration_ms = media_duration_ms(audio)
            audio_sha = hashlib.sha256(audio.read_bytes()).hexdigest()
        except Exception:
            n_fail += 1
            continue
        segments = build_segments_verse_relative(
            record,
            rendered,
            canonical,
            letter_vocab,
            duration_ms,
        )
        if not segments:
            n_fail += 1
            continue
        rows.append({
            "surah": surah,
            "ayah": ayah,
            "gateScore": 1.0,
            "reclock": "verse_relative",
            "audioSha256": audio_sha,
            "segments": segments,
        })
        if len(rows) % 500 == 0:
            print(f"... {len(rows)} accepted / {n_fail} failed")

    # Structure spot-check
    pos_610 = next(
        (r["segments"] for r in rows if r["surah"] == 6 and r["ayah"] == 10),
        None,
    )
    if pos_610 is not None:
        got = [s["position"] for s in pos_610]
        want = [1, 2, 3, 4, 5, 6, 7, 8, 6, 7, 8, 9, 10, 11, 12, 13]
        print("6:10 structure", "OK" if got == want else f"FAIL got={got}")
    else:
        print("6:10 structure MISSING from output")

    payload = {
        "schema": 2,
        "reciterId": 1,
        "reciter": "Alafasy_128kbps",
        "generator": GENERATOR,
        "source": f"Wider-Community/quranic-universal-audio@{QUA_RELEASE}",
        "sourceRevision": QUA_REVISION,
        "sourceAssetSha256": ALAFASY_RELEASE_SHA256,
        "minimumGateScore": MINIMUM_GATE_SCORE,
        "rows": rows,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {args.out} accepted={len(rows)} failed={n_fail}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
