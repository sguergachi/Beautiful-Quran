#!/usr/bin/env python3
"""Compare V2 word onsets with historical ear-edited regression evidence.

This corpus is not independent gold and cannot prove 99%. It is the cheapest
honest check that V2 beats V1 on previously reported timing defects before
collecting a frozen, independently labeled test set.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from pathlib import Path

from generate_timing_v2 import align_ayah, audio_file, load_words
from grammar_structure import is_grammar_valid
from timing_v2_metrics import summarize_clock_conventions, summarize_errors

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]


def shipped_segments(db: Path, reciter: int, surah: int, ayah: int) -> list:
    with sqlite3.connect(db) as con:
        row = con.execute(
            "SELECT segments FROM timings "
            "WHERE reciter_id=? AND surah_id=? AND ayah_number=?",
            (reciter, surah, ayah),
        ).fetchone()
    return json.loads(row[0]) if row else []


def positions(segments: list) -> list[int]:
    return [
        int(segment["position"] if isinstance(segment, dict) else segment[0])
        for segment in segments
    ]


def starts(segments: list) -> list[int]:
    return [
        int(segment["startMs"] if isinstance(segment, dict) else segment[1])
        for segment in segments
    ]


def summarize(rows: list[dict], field: str) -> dict:
    accepted = [row for row in rows if row[field]]
    exact = [
        row for row in accepted
        if positions(row[field]) == positions(row["gold"])
    ]
    error_rows = [
        [
            predicted - gold
            for predicted, gold in zip(starts(row[field]), starts(row["gold"]))
        ]
        for row in exact
    ]
    errors = [error for row in error_rows for error in row]
    total_gold_onsets = sum(len(row["gold"]) for row in rows)
    matched_within_100 = sum(abs(error) <= 100 for error in errors)
    return {
        "rows": len(rows),
        "coveragePct": 100 * len(accepted) / max(1, len(rows)),
        "structureExactPct": 100 * len(exact) / max(1, len(rows)),
        "acceptedStructureExactPct": 100 * len(exact) / max(1, len(accepted)),
        "clockEligibleRows": len(exact),
        "onsets": summarize_errors(errors),
        "clockConventionDiagnostics": summarize_clock_conventions(error_rows),
        "endToEndOnsetsWithin100Pct": (
            100 * matched_within_100 / max(1, total_gold_onsets)
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--labels",
        type=Path,
        default=LAB / "historical_manual_patches.json",
    )
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument("--audio-dir", type=Path, default=LAB / "audio")
    parser.add_argument("--split", choices=("train", "validation", "test"))
    parser.add_argument("--min-path-score", type=float, default=-1.0)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    payload = json.loads(args.labels.read_text())
    independent = payload.get("independent") is True
    edits = payload["edits"]
    if args.split:
        edits = [edit for edit in edits if edit.get("split") == args.split]
    valid = [
        edit for edit in edits
        if is_grammar_valid(
            positions(edit["segments"]),
            len(load_words(args.db, edit["surahId"], edit["ayah"])),
        )
    ]
    if args.limit > 0:
        valid = valid[:args.limit]

    rows = []
    for index, edit in enumerate(valid, 1):
        reciter = int(edit["reciterId"])
        surah = int(edit["surahId"])
        ayah = int(edit["ayah"])
        slug = edit["reciterSlug"]
        words = load_words(args.db, surah, ayah)
        audio = audio_file(args.audio_dir / slug, slug, surah, ayah)
        if independent:
            actual_hash = hashlib.sha256(audio.read_bytes()).hexdigest()
            if actual_hash != edit.get("audioSha256"):
                raise ValueError(f"{slug} {surah}:{ayah}: label audio hash mismatch")
        v2, score = align_ayah(audio, words)
        if score < args.min_path_score:
            v2 = []
        row = {
            "reciterId": reciter,
            "surah": surah,
            "ayah": ayah,
            "pathScore": score,
            "gold": edit["segments"],
            "v1": shipped_segments(args.db, reciter, surah, ayah),
            "v2": v2,
        }
        rows.append(row)
        print(f"[{index}/{len(valid)}] {slug} {surah}:{ayah} score={score:.3f}")

    report = {
        "labelProvenance": "independent" if independent else "regression-only",
        "minPathScore": args.min_path_score,
        "warning": None if independent else (
            "Historical edits are regression evidence, not independent gold."
        ),
        "grammarValidRows": len(rows),
        "v1": summarize(rows, "v1"),
        "v2": summarize(rows, "v2"),
    }
    print(json.dumps(report, indent=2))
    if args.out:
        args.out.write_text(
            json.dumps({**report, "cases": rows}, ensure_ascii=False, indent=2)
            + "\n"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
