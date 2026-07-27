#!/usr/bin/env python3
"""Evaluate pause-first multi-hypothesis structure vs gold (shipped DB).

Gold = post-repair quran.db structure for known hard cases.
Success = same backtrack multiset + same position sequence (or close).
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np

LAB = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB))

from aligners import load_mono_16k  # noqa: E402
from structure_engine import (  # noqa: E402
    align_structure,
    structure_signature,
)


def bt_positions(positions):
    """Multiset of word positions that are backtracks (order-independent)."""
    from collections import Counter
    hw = 0
    c = Counter()
    for p in positions:
        if p <= hw:
            c[p] += 1
        hw = max(hw, p)
    return c


def same_pos_pairs(positions):
    return sum(1 for i in range(len(positions) - 1) if positions[i] == positions[i + 1])


def main():
    cases = json.loads((LAB / "gold_structure_cases.json").read_text())
    results = []
    t0 = time.time()

    for c in cases:
        slug, surah, ayah = c["slug"], c["surah"], c["ayah"]
        path = LAB / "audio" / slug / f"{surah:03d}{ayah:03d}.mp3"
        if not path.exists():
            print(f"MISSING {path}")
            continue
        y, sr = load_mono_16k(path)
        words = c["words"]
        gold_pos = c["positions"]

        print(f"\n=== {slug} {surah}:{ayah} — {c['note']} ===", flush=True)
        print(f"  gold pos: {gold_pos}")
        t1 = time.time()
        res = align_structure(y, sr, words, min_pause_ms=300.0)
        elapsed = time.time() - t1
        hyp = res.positions
        print(f"  hyp  pos: {hyp}")
        print(f"  chunks: {len(res.chunks)}  choices: {[(ch.kind, ch.template, f'{ch.score:.2f}') for ch in res.choices]}")
        if res.warnings:
            print(f"  warn: {res.warnings}")

        gold_bt = bt_positions(gold_pos)
        hyp_bt = bt_positions(hyp)
        exact = structure_signature(hyp) == structure_signature(gold_pos)
        # Structure OK if same backtrack multiset AND first-pass covers all words
        bt_match = gold_bt == hyp_bt
        gold_fp = set(gold_pos)  # positions that appear
        hyp_fp = set(hyp)
        coverage = len(hyp_fp & set(range(1, c["n_words"] + 1))) / c["n_words"]

        # Backtrack precision/recall on position multiset
        # treat Counter as bags
        tp = sum((gold_bt & hyp_bt).values())
        fp = sum((hyp_bt - gold_bt).values())
        fn = sum((gold_bt - hyp_bt).values())
        prec = tp / (tp + fp) if (tp + fp) else 1.0 if not gold_bt else 0.0
        rec = tp / (tp + fn) if (tp + fn) else 1.0 if not hyp_bt else 0.0

        row = {
            "case": f"{slug} {surah}:{ayah}",
            "note": c["note"],
            "exact_seq": exact,
            "bt_match": bt_match,
            "bt_precision": prec,
            "bt_recall": rec,
            "coverage": coverage,
            "n_chunks": len(res.chunks),
            "gold_bt": dict(gold_bt),
            "hyp_bt": dict(hyp_bt),
            "gold_pos": gold_pos,
            "hyp_pos": hyp,
            "elapsed_s": round(elapsed, 2),
            "warnings": res.warnings,
            "segments": res.segments,
        }
        results.append(row)
        status = "OK" if exact else ("BT_OK" if bt_match and coverage >= 0.99 else "FAIL")
        print(
            f"  → {status} exact={exact} bt_match={bt_match} "
            f"P={prec:.0%} R={rec:.0%} cov={coverage:.0%} ({elapsed:.1f}s)"
        )

    n = len(results)
    n_exact = sum(1 for r in results if r["exact_seq"])
    n_bt = sum(1 for r in results if r["bt_match"])
    mean_p = float(np.mean([r["bt_precision"] for r in results])) if results else 0
    mean_r = float(np.mean([r["bt_recall"] for r in results])) if results else 0
    mean_c = float(np.mean([r["coverage"] for r in results])) if results else 0

    summary = {
        "n": n,
        "exact_seq": n_exact,
        "bt_match": n_bt,
        "mean_bt_precision": mean_p,
        "mean_bt_recall": mean_r,
        "mean_coverage": mean_c,
        "elapsed_s": round(time.time() - t0, 2),
        "cases": results,
    }
    out = LAB / "results" / "structure_eval.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=2))

    print("\n======== SUMMARY ========")
    print(f"exact sequence: {n_exact}/{n}")
    print(f"backtrack bag match: {n_bt}/{n}")
    print(f"mean BT precision={mean_p:.1%} recall={mean_r:.1%} coverage={mean_c:.1%}")
    print(f"wrote {out} ({summary['elapsed_s']}s)")
    # exit code for CI-ish use
    sys.exit(0 if n_bt == n and mean_c >= 0.99 else 1)


if __name__ == "__main__":
    main()
