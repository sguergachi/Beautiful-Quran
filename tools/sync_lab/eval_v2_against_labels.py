#!/usr/bin/env python3
"""Score a Timing V2 payload against frozen independent labels.

Only `labelStatus=done` rows in the requested split are scored. Prints the
exact sentence shape required by docs/V2_99_PROTOCOL.md.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path

from timing_v2_metrics import summarize_errors

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]


def wilson_lower_bound(successes: int, n: int, z: float = 1.96) -> float:
    if n <= 0:
        return 0.0
    p = successes / n
    denom = 1 + z * z / n
    centre = p + z * z / (2 * n)
    margin = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return (centre - margin) / denom


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--payload",
        type=Path,
        default=ROOT / "tools/timing_v2/alafasy_qua.json",
    )
    parser.add_argument(
        "--labels",
        type=Path,
        default=LAB / "independent_labels" / "frozen_sample_v1.json",
    )
    parser.add_argument("--split", default="test", choices=("test", "validation", "all"))
    parser.add_argument(
        "--out",
        type=Path,
        default=LAB / "results" / "v2_vs_independent.json",
    )
    args = parser.parse_args()

    payload = json.loads(args.payload.read_text(encoding="utf-8"))
    labels = json.loads(args.labels.read_text(encoding="utf-8"))
    v2 = {(r["surah"], r["ayah"]): r for r in payload["rows"]}

    onset_errors: list[int] = []
    structure_exact = 0
    structure_compared = 0
    accepted = 0
    labeled = 0
    pending = 0

    for edit in labels["edits"]:
        if args.split != "all" and edit.get("split") != args.split:
            continue
        if edit.get("labelStatus") != "done" or not edit.get("segments"):
            pending += 1
            continue
        labeled += 1
        key = (int(edit["surahId"]), int(edit["ayah"]))
        row = v2.get(key)
        if row is None:
            continue
        accepted += 1
        gold = edit["segments"]
        gold_pos = [int(s[0]) for s in gold]
        pred_pos = [int(s["position"]) for s in row["segments"]]
        structure_compared += 1
        if gold_pos != pred_pos:
            continue
        structure_exact += 1
        gold_starts = [int(s[1]) for s in gold]
        pred_starts = [int(s["startMs"]) for s in row["segments"]]
        if len(gold_starts) != len(pred_starts):
            continue
        onset_errors.extend(p - g for p, g in zip(pred_starts, gold_starts))

    abs_e = [abs(e) for e in onset_errors]
    within100 = sum(e <= 100 for e in abs_e)
    summary = {
        "split": args.split,
        "labeledRowsInSplit": labeled,
        "pendingRowsInSplit": pending,
        "v2AcceptedAmongLabeled": accepted,
        "coverageAmongLabeledPct": round(100 * accepted / max(1, labeled), 2),
        "structureExact": structure_exact,
        "structureCompared": structure_compared,
        "structureExactPct": round(
            100 * structure_exact / max(1, structure_compared), 2
        ),
        "onsets": summarize_errors(onset_errors),
        "within100WilsonLcb95": round(
            100 * wilson_lower_bound(within100, len(abs_e)), 2
        )
        if abs_e
        else None,
        "signedMedianMs": statistics.median(onset_errors) if onset_errors else None,
        "claimSentence": (
            f"{(100 * within100 / len(abs_e)) if abs_e else 0:.2f}% of V2-timed "
            f"onsets within 100ms "
            f"(med={(statistics.median(abs_e) if abs_e else None)}, "
            f"p90={(sorted(abs_e)[max(0, int(0.9*len(abs_e))-1)] if abs_e else None)}) "
            f"on split={args.split}; "
            f"V2 accepted {accepted}/{max(1, labeled)} labeled rows "
            f"({100 * accepted / max(1, labeled):.1f}% of labeled). "
            f"{'NOT a claim — labels pending.' if pending and not abs_e else ''}"
        ),
        "note": "99% claim allowed only when test labels are done and bars in V2_99_PROTOCOL.md hold.",
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
