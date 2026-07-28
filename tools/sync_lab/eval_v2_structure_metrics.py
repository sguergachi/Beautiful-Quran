#!/usr/bin/env python3
"""V2 structure metrics vs V1 (product-facing, not Lab-subset).

Reports (Alafasy reciter_id=1):
  - backtrack *ayah* recall: |V2∩V1 backtracks| / |V1 backtracks|
  - structure-exact rate among V1-backtrack ayahs
  - V2-only / V1-only backtrack counts
  - false backtracks on Fatiha (must be 0)

Default floors are *no-regression* locks on the full-QUA Dir1 land
(docs/V2_STRUCTURE.md). The long-term product target is ≥99% recall; do not
claim 99% until this report says so.

Usage:
  python3 tools/sync_lab/eval_v2_structure_metrics.py
  python3 tools/sync_lab/eval_v2_structure_metrics.py --require-pass
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]

# No-regression floors after full-QUA import (measured ~77% recall / 665 V2 bt).
MIN_BACKTRACK_RECALL = 0.75
MIN_V2_BACKTRACK_AYAHS = 600


def positions_from_raw(raw) -> list[int]:
    segs = json.loads(raw) if isinstance(raw, str) else raw
    if not segs:
        return []
    if isinstance(segs[0], dict):
        return [int(s["position"]) for s in segs]
    return [int(s[0]) for s in segs]


def has_backtrack(pos: list[int]) -> bool:
    high = -1
    for p in pos:
        if p <= high:
            return True
        high = max(high, p)
    return False


def load_tables(db: Path) -> tuple[dict[tuple[int, int], list[int]], dict[tuple[int, int], list[int]]]:
    v1: dict[tuple[int, int], list[int]] = {}
    v2: dict[tuple[int, int], list[int]] = {}
    with sqlite3.connect(db) as con:
        for surah, ayah, raw in con.execute(
            "SELECT surah_id, ayah_number, segments FROM timings WHERE reciter_id=1"
        ):
            v1[(int(surah), int(ayah))] = positions_from_raw(raw)
        for surah, ayah, raw in con.execute(
            "SELECT surah_id, ayah_number, segments FROM timings_v2 WHERE reciter_id=1"
        ):
            v2[(int(surah), int(ayah))] = positions_from_raw(raw)
    return v1, v2


def evaluate(db: Path) -> dict:
    v1, v2 = load_tables(db)
    keys = sorted(set(v1) | set(v2))

    v1_bt = {k for k in keys if has_backtrack(v1.get(k, []))}
    v2_bt = {k for k in keys if has_backtrack(v2.get(k, []))}
    both = v1_bt & v2_bt
    exact = {
        k for k in v1_bt
        if k in v2 and v1[k] == v2[k]
    }

    fatiha_false = []
    for ayah in range(1, 8):
        pos = v2.get((1, ayah), [])
        if has_backtrack(pos):
            fatiha_false.append(f"1:{ayah}->{pos}")

    recall = (len(both) / len(v1_bt)) if v1_bt else 1.0
    exact_rate = (len(exact) / len(v1_bt)) if v1_bt else 1.0

    report = {
        "v1BacktrackAyahs": len(v1_bt),
        "v2BacktrackAyahs": len(v2_bt),
        "bothBacktrackAyahs": len(both),
        "v1OnlyBacktrackAyahs": len(v1_bt - v2_bt),
        "v2OnlyBacktrackAyahs": len(v2_bt - v1_bt),
        "backtrackRecall": recall,
        "structureExactAmongV1Bt": exact_rate,
        "structureExactCount": len(exact),
        "fatihaFalseBacktracks": fatiha_false,
        "floors": {
            "minBacktrackRecall": MIN_BACKTRACK_RECALL,
            "minV2BacktrackAyahs": MIN_V2_BACKTRACK_AYAHS,
            "fatihaMustBeMono": True,
        },
    }
    report["pass"] = (
        recall + 1e-12 >= MIN_BACKTRACK_RECALL
        and len(v2_bt) >= MIN_V2_BACKTRACK_AYAHS
        and not fatiha_false
    )
    report["claim"] = (
        f"V2 backtrack recall {recall:.1%} "
        f"({len(both)}/{len(v1_bt)} V1-bt ayahs); "
        f"exact {exact_rate:.1%} among V1-bt; "
        f"V2 bt ayahs={len(v2_bt)}; "
        f"Fatiha false-bt={len(fatiha_false)}"
    )
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=ROOT / "data" / "quran.db")
    parser.add_argument(
        "--require-pass",
        action="store_true",
        help="Exit 1 when no-regression floors fail",
    )
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if not args.db.exists():
        print(f"missing {args.db}", file=sys.stderr)
        return 2
    report = evaluate(args.db)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(report["claim"])
        print(
            f"  v1_only={report['v1OnlyBacktrackAyahs']} "
            f"v2_only={report['v2OnlyBacktrackAyahs']}"
        )
        if report["fatihaFalseBacktracks"]:
            print("  Fatiha false backtracks:", report["fatihaFalseBacktracks"])
        print("  pass" if report["pass"] else "  FAIL floors")
    if args.require_pass and not report["pass"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
