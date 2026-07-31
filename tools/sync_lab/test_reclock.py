#!/usr/bin/env python3
"""
Verify: with GOLD structure fixed, re-clock alone hits high timing accuracy.

This is the production-shaped pipeline:
  structure = shipped (post-repair) positions
  clock     = CTC re-align of that sequence

Compares start_ms to gold segments (same structure).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

LAB = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB))

from aligners import load_mono_16k  # noqa: E402
from reclock import reclock_by_runs, reclock_positions  # noqa: E402


def main():
    cases = json.loads((LAB / "gold_structure_cases.json").read_text())
    rows = []
    for c in cases:
        path = LAB / "audio" / c["slug"] / f"{c['surah']:03d}{c['ayah']:03d}.mp3"
        if not path.exists():
            continue
        y, sr = load_mono_16k(path)
        gold_pos = c["positions"]
        gold_segs = c["segs"]
        # map gold first occurrence times for each sequence index
        for name, fn in [
            ("global", reclock_positions),
            ("by_runs", reclock_by_runs),
        ]:
            hyp = fn(y, sr, gold_pos, c["words"])
            if len(hyp) != len(gold_segs):
                print(f"{c['slug']} {c['surah']}:{c['ayah']} {name}: len mismatch {len(hyp)} vs {len(gold_segs)}")
                continue
            start_err = [abs(h[1] - g[1]) for h, g in zip(hyp, gold_segs)]
            end_err = [abs(h[2] - g[2]) for h, g in zip(hyp, gold_segs)]
            row = {
                "case": f"{c['slug']} {c['surah']}:{c['ayah']}",
                "method": name,
                "med_start": float(np.median(start_err)),
                "p90_start": float(np.percentile(start_err, 90)),
                "mean_start": float(np.mean(start_err)),
                "med_end": float(np.median(end_err)),
                "within_50": float(np.mean([e <= 50 for e in start_err])),
                "within_100": float(np.mean([e <= 100 for e in start_err])),
                "within_150": float(np.mean([e <= 150 for e in start_err])),
                "n": len(start_err),
            }
            rows.append(row)
            print(
                f"{row['case']:40} {name:8} med={row['med_start']:6.1f} "
                f"p90={row['p90_start']:6.1f} ≤50ms={row['within_50']:.0%} "
                f"≤100ms={row['within_100']:.0%} ≤150ms={row['within_150']:.0%}"
            )

    # aggregate by method
    print("\n=== AGGREGATE ===")
    for method in ("global", "by_runs"):
        mrows = [r for r in rows if r["method"] == method]
        if not mrows:
            continue
        # micro-average over words
        # re-load not stored per-word; use mean of medians weighted by n
        total_n = sum(r["n"] for r in mrows)
        # approximate: average of case metrics weighted by n
        def wavg(key):
            return sum(r[key] * r["n"] for r in mrows) / total_n

        print(
            f"{method:8} weighted med_start≈{wavg('med_start'):.1f}  "
            f"≤50={wavg('within_50'):.1%} ≤100={wavg('within_100'):.1%} "
            f"≤150={wavg('within_150'):.1%}  (n_words={total_n})"
        )

    out = LAB / "results" / "reclock_eval.json"
    out.write_text(json.dumps({"rows": rows}, indent=2))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
