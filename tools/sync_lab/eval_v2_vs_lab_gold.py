#!/usr/bin/env python3
"""Score shipped timings_v2 against Timings Lab ground truth.

Gates (Alafasy grammar-valid Lab edits only):
  - structure exact rate among Lab gold rows that have a V2 row
  - onset |error| within 100 ms among structure-exact rows
  - optional: require full gold coverage (every Lab gold ayah has V2)

Exit 0 only when structure and within100 meet thresholds (default 99%).
"""
from __future__ import annotations

import argparse
import json
import math
import sqlite3
import statistics
import sys
from pathlib import Path

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]


def grammar_valid(positions: list[int], word_count: int) -> bool:
    expected, seen = 1, set()
    for position in positions:
        if position not in seen:
            if position != expected:
                return False
            seen.add(position)
            expected += 1
    return expected == word_count + 1


def positions(segs) -> list[int]:
    if not segs:
        return []
    if isinstance(segs[0], dict):
        return [int(s["position"]) for s in segs]
    return [int(s[0]) for s in segs]


def starts(segs) -> list[int]:
    if not segs:
        return []
    if isinstance(segs[0], dict):
        return [int(s["startMs"]) for s in segs]
    return [int(s[1]) for s in segs]


def summarize(errors: list[int]) -> dict:
    if not errors:
        return {
            "count": 0,
            "medianMs": None,
            "p90Ms": None,
            "within100Pct": None,
            "within25Pct": None,
            "within60Pct": None,
        }
    ordered = sorted(abs(e) for e in errors)
    return {
        "count": len(ordered),
        "medianMs": statistics.median(ordered),
        "p90Ms": ordered[max(0, math.ceil(0.9 * len(ordered)) - 1)],
        "within25Pct": 100 * sum(e <= 25 for e in ordered) / len(ordered),
        "within60Pct": 100 * sum(e <= 60 for e in ordered) / len(ordered),
        "within100Pct": 100 * sum(e <= 100 for e in ordered) / len(ordered),
    }


def load_lab_gold(hist_path: Path, db: Path) -> list[dict]:
    hist = json.loads(hist_path.read_text(encoding="utf-8"))
    with sqlite3.connect(db) as con:
        word_counts = {
            (int(s), int(a)): int(c)
            for s, a, c in con.execute(
                "SELECT surah_id, ayah_number, COUNT(*) FROM words GROUP BY 1, 2"
            )
        }
    gold = []
    for edit in hist["edits"]:
        if int(edit.get("reciterId", 1)) != 1:
            continue
        surah, ayah = int(edit["surahId"]), int(edit["ayah"])
        segs = edit["segments"]
        pos = positions(segs)
        n = word_counts.get((surah, ayah))
        if n is None or not grammar_valid(pos, n):
            continue
        gold.append({"surah": surah, "ayah": ayah, "segments": segs})
    return gold


def load_v2(db: Path) -> dict[tuple[int, int], list]:
    with sqlite3.connect(db) as con:
        return {
            (int(s), int(a)): json.loads(raw)
            for s, a, raw in con.execute(
                "SELECT surah_id, ayah_number, segments FROM timings_v2 "
                "WHERE reciter_id = 1"
            )
        }


def evaluate(gold: list[dict], v2: dict[tuple[int, int], list]) -> dict:
    covered = 0
    structure_exact = 0
    errors: list[int] = []
    structure_misses: list[str] = []
    missing: list[str] = []
    for row in gold:
        key = (row["surah"], row["ayah"])
        pred = v2.get(key)
        if pred is None:
            missing.append(f"{key[0]}:{key[1]}")
            continue
        covered += 1
        gpos, ppos = positions(row["segments"]), positions(pred)
        if gpos != ppos:
            structure_misses.append(f"{key[0]}:{key[1]}")
            continue
        structure_exact += 1
        gstarts, pstarts = starts(row["segments"]), starts(pred)
        errors.extend(p - g for p, g in zip(pstarts, gstarts))

    onset = summarize(errors)
    n_gold = len(gold)
    report = {
        "labGoldRows": n_gold,
        "v2Covered": covered,
        "v2Missing": len(missing),
        "coveragePct": round(100 * covered / max(1, n_gold), 2),
        "structureExact": structure_exact,
        "structureExactPctOfGold": round(100 * structure_exact / max(1, n_gold), 2),
        "structureExactPctOfCovered": round(
            100 * structure_exact / max(1, covered), 2
        ),
        "onsets": onset,
        "missingSample": missing[:20],
        "structureMissSample": structure_misses[:20],
        "bars": {
            "structureExactPctOfGold": 99.0,
            "within100Pct": 99.0,
            "medianMs": 25.0,
            "p90Ms": 60.0,
        },
    }
    within = onset.get("within100Pct")
    report["pass"] = bool(
        covered == n_gold
        and structure_exact == n_gold
        and within is not None
        and within >= 99.0
        and (onset.get("medianMs") is None or onset["medianMs"] <= 25)
        and (onset.get("p90Ms") is None or onset["p90Ms"] <= 60)
    )
    report["claim"] = (
        f"Lab gold: structure exact {structure_exact}/{n_gold} "
        f"({report['structureExactPctOfGold']}%), "
        f"onsets within100={within}% med={onset.get('medianMs')} "
        f"p90={onset.get('p90Ms')} n={onset.get('count')}; "
        f"{'PASS' if report['pass'] else 'FAIL'} 99% Lab unit gate"
    )
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--hist",
        type=Path,
        default=LAB / "historical_manual_patches.json",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=LAB / "results" / "v2_vs_lab_gold_gate.json",
    )
    parser.add_argument(
        "--require-pass",
        action="store_true",
        help="Exit 1 if Lab gold gates fail",
    )
    args = parser.parse_args()

    gold = load_lab_gold(args.hist, args.db)
    v2 = load_v2(args.db)
    report = evaluate(gold, v2)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if args.require_pass and not report["pass"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
