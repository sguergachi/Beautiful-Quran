#!/usr/bin/env python3
"""Independent mono CTC onset witness against accepted QUA V2 rows.

Runs pinned Arabic CTC forced alignment on EveryAyah audio and compares word
onsets to the QUA-transferred V2 spans. This is the first non-circular accuracy
number for the same-take lane. It is not ear gold and cannot alone claim 99%.
"""
from __future__ import annotations

import argparse
import json
import random
import sqlite3
from pathlib import Path

from generate_timing_v2 import align_ayah, audio_file, load_words
from timing_v2_metrics import summarize_clock_conventions, summarize_errors

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--payload",
        type=Path,
        default=ROOT / "tools/timing_v2/alafasy_qua.json",
    )
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=LAB / "audio" / "Alafasy_128kbps",
    )
    parser.add_argument("--sample", type=int, default=80)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument(
        "--out",
        type=Path,
        default=LAB / "results" / "v2_ctc_witness.json",
    )
    parser.add_argument("--mono-only", action="store_true", default=True)
    args = parser.parse_args()

    payload = json.loads(args.payload.read_text(encoding="utf-8"))
    rows = payload["rows"]
    if args.mono_only:
        mono = []
        for row in rows:
            positions = [s["position"] for s in row["segments"]]
            if len(positions) == len(set(positions)):
                mono.append(row)
        rows = mono
    rng = random.Random(args.seed)
    sample = rows if len(rows) <= args.sample else rng.sample(rows, args.sample)

    comparisons = []
    error_rows = []
    for row in sample:
        surah, ayah = int(row["surah"]), int(row["ayah"])
        words = load_words(args.db, surah, ayah)
        if len(words) != len(row["segments"]):
            comparisons.append({
                "surah": surah,
                "ayah": ayah,
                "status": "structure_mismatch_vs_db_words",
            })
            continue
        audio = audio_file(args.audio_dir, "Alafasy_128kbps", surah, ayah)
        try:
            ctc_segments, score = align_ayah(audio, words)
        except Exception as exc:  # pragma: no cover - model/env issues
            comparisons.append({
                "surah": surah,
                "ayah": ayah,
                "status": f"align_error:{type(exc).__name__}",
            })
            continue
        if not ctc_segments or len(ctc_segments) != len(words):
            comparisons.append({
                "surah": surah,
                "ayah": ayah,
                "status": "ctc_abstained",
                "ctcScore": score,
            })
            continue
        v2_starts = [int(s["startMs"]) for s in row["segments"]]
        ctc_starts = [int(s["startMs"]) for s in ctc_segments]
        errors = [v - c for v, c in zip(v2_starts, ctc_starts)]
        error_rows.append(errors)
        comparisons.append({
            "surah": surah,
            "ayah": ayah,
            "status": "ok",
            "ctcScore": score,
            "errorsMs": errors,
            "absMedianMs": sorted(abs(e) for e in errors)[len(errors) // 2],
        })

    flat = [e for row in error_rows for e in row]
    summary = {
        "sampleSize": len(sample),
        "compared": len(error_rows),
        "monoOnly": bool(args.mono_only),
        "onsets": summarize_errors(flat),
        "clockConventionDiagnostics": summarize_clock_conventions(error_rows),
        "rows": comparisons,
        "note": (
            "CTC is an independent acoustic witness on EveryAyah audio, not ear "
            "gold. Report accuracy and coverage separately from QUA acceptance."
        ),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({k: v for k, v in summary.items() if k != "rows"}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
