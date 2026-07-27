#!/usr/bin/env python3
"""Evaluate decode-string structure vs gold hard cases."""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np

LAB = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB))

from aligners import load_mono_16k  # noqa: E402
from decode_structure import align_decode_structure  # noqa: E402


def bt_counter(positions):
    c = Counter()
    hw = 0
    for p in positions:
        if p <= hw:
            c[p] += 1
        hw = max(hw, p)
    return c


def main():
    cases = json.loads((LAB / "gold_structure_cases.json").read_text())
    results = []
    for c in cases:
        path = LAB / "audio" / c["slug"] / f"{c['surah']:03d}{c['ayah']:03d}.mp3"
        if not path.exists():
            continue
        y, sr = load_mono_16k(path)
        hyp, segs, warns = align_decode_structure(y, sr, c["words"])
        gold = c["positions"]
        gbt, hbt = bt_counter(gold), bt_counter(hyp)
        tp = sum((gbt & hbt).values())
        fp = sum((hbt - gbt).values())
        fn = sum((gbt - hbt).values())
        prec = tp / (tp + fp) if tp + fp else 1.0 if not gbt else 0.0
        rec = tp / (tp + fn) if tp + fn else 1.0 if not hbt else 0.0
        exact = hyp == gold
        bt_match = gbt == hbt
        print(f"\n{c['slug']} {c['surah']}:{c['ayah']} — {c['note']}")
        print(f"  gold {gold}")
        print(f"  hyp  {hyp}")
        print(f"  warn {warns}")
        status = "OK" if exact else ("BT_OK" if bt_match else "FAIL")
        print(f"  → {status} P={prec:.0%} R={rec:.0%}")
        results.append(
            {
                "case": f"{c['slug']} {c['surah']}:{c['ayah']}",
                "exact": exact,
                "bt_match": bt_match,
                "prec": prec,
                "rec": rec,
                "gold_bt": dict(gbt),
                "hyp_bt": dict(hbt),
                "hyp": hyp,
                "warns": warns,
            }
        )

    n = len(results)
    print("\n======== DECODE STRUCTURE SUMMARY ========")
    print(f"exact {sum(r['exact'] for r in results)}/{n}")
    print(f"bt_match {sum(r['bt_match'] for r in results)}/{n}")
    print(f"mean P={np.mean([r['prec'] for r in results]):.0%} R={np.mean([r['rec'] for r in results]):.0%}")
    out = LAB / "results" / "decode_structure_eval.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print("wrote", out)
    # success gate: perfect on false-positive cases + high recall
    false_pos_cases = [r for r in results if not r["gold_bt"]]
    clean_fp = all(r["bt_match"] for r in false_pos_cases)
    sys.exit(0 if clean_fp and np.mean([r["rec"] for r in results]) >= 0.5 else 1)


if __name__ == "__main__":
    main()
