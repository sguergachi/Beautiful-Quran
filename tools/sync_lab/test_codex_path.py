#!/usr/bin/env python3
"""
Test Codex-proposed path vs baselines on gold hard cases + clock ablations.

Methods (structure):
  - mono
  - decode_v1 (legacy unique-span insert)
  - grammar (canonical candidate scoring vs free decode)
  - grammar+qdc_prior (QDC as candidate only)
  - qdc alone

Methods (clock, structure fixed to gold positions):
  - global FA
  - by_runs proportional
  - grammar reclock (global FA via reclock_anchored)

Metrics:
  Structure: exact seq, BT bag P/R, FP on no-BT cases, repeat-positive exact
  Clock: |Δstart| vs shipped gold (proxy), pad recovery residual
"""
from __future__ import annotations

import json
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np

LAB = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB))

from aligners import load_mono_16k, pad_silence  # noqa: E402
from decode_structure import align_decode_structure  # noqa: E402
from grammar_structure import (  # noqa: E402
    mono_positions,
    reclock_anchored,
    select_structure,
    timed_free_decode,
)
from reclock import reclock_by_runs, reclock_positions  # noqa: E402
from structure_engine import force_align_positions  # noqa: E402


def bt_counter(positions):
    c = Counter()
    hw = 0
    for p in positions:
        if p <= hw:
            c[p] += 1
        hw = max(hw, p)
    return c


def metrics(gold, hyp):
    gbt, hbt = bt_counter(gold), bt_counter(hyp)
    tp = sum((gbt & hbt).values())
    fp = sum((hbt - gbt).values())
    fn = sum((gbt - hbt).values())
    prec = tp / (tp + fp) if tp + fp else 1.0 if not gbt else 0.0
    rec = tp / (tp + fn) if tp + fn else 1.0 if not hbt else 0.0
    return {
        "exact": list(hyp) == list(gold),
        "bt_match": gbt == hbt,
        "prec": prec,
        "rec": rec,
        "has_gold_bt": bool(gbt),
        "fp_on_clean": bool(hbt) and not gbt,
        "gbt": dict(gbt),
        "hbt": dict(hbt),
    }


def main():
    cases = json.loads((LAB / "gold_structure_cases.json").read_text())
    t0 = time.time()
    structure_rows = []
    clock_rows = []

    for c in cases:
        path = LAB / "audio" / c["slug"] / f"{c['surah']:03d}{c['ayah']:03d}.mp3"
        if not path.exists():
            print("missing", path)
            continue
        y, sr = load_mono_16k(path)
        words = c["words"]
        gold = c["positions"]
        gold_segs = c["segs"]
        label = f"{c['slug']} {c['surah']}:{c['ayah']}"
        print(f"\n=== {label} — {c['note']} ===", flush=True)

        # --- structure methods ---
        methods = {}

        # mono
        methods["mono"] = mono_positions(len(words))

        # decode_v1
        hyp_v1, _, warns_v1 = align_decode_structure(y, sr, words)
        methods["decode_v1"] = hyp_v1

        # grammar
        win_g, dec, scored = select_structure(y, sr, words, priors=None)
        methods["grammar"] = win_g.positions

        # grammar + qdc prior
        win_qp, dec2, scored2 = select_structure(
            y, sr, words, priors=[("qdc", gold)]
        )
        methods["grammar+qdc_prior"] = win_qp.positions

        # qdc alone (gold structure as if we always took prior)
        methods["qdc"] = gold

        case_struct = {"case": label, "note": c["note"], "methods": {}}
        for name, hyp in methods.items():
            m = metrics(gold, hyp)
            case_struct["methods"][name] = {
                **m,
                "hyp": hyp,
            }
            tag = (
                "OK"
                if m["exact"]
                else ("BT" if m["bt_match"] else ("FP!" if m["fp_on_clean"] else "MISS"))
            )
            print(
                f"  S {name:18} {tag:4} P={m['prec']:.0%} R={m['rec']:.0%} "
                f"hyp_bt={m['hbt']}"
            )

        # evidence dump for grammar winner
        case_struct["grammar_winner"] = {
            "source": win_g.source,
            "score": win_g.score,
            "sim": win_g.decode_sim,
            "positions": win_g.positions,
        }
        case_struct["grammar_top3"] = [
            {"source": s.source, "score": s.score, "sim": s.decode_sim, "pos": s.positions}
            for s in scored[:3]
        ]
        case_struct["decode_len"] = len(dec.text)
        case_struct["decode_preview"] = dec.text[:120]
        structure_rows.append(case_struct)

        # --- clock methods (fixed gold structure) ---
        pad_ms = 200.0
        for cname, fn in [
            ("global", lambda: reclock_positions(y, sr, gold, words)),
            ("by_runs", lambda: reclock_by_runs(y, sr, gold, words)),
            ("anchored", lambda: reclock_anchored(y, sr, gold, words, dec)),
        ]:
            segs = fn()
            if len(segs) != len(gold_segs):
                print(f"  C {cname}: len mismatch {len(segs)} vs {len(gold_segs)}")
                continue
            start_err = [abs(h[1] - g[1]) for h, g in zip(segs, gold_segs)]
            # pad recovery on global-style only for global/anchored
            pad_err = None
            if cname in ("global", "anchored"):
                ypad = pad_silence(y, sr, pad_ms)
                segs_p = reclock_positions(ypad, sr, gold, words)
                if len(segs_p) == len(segs):
                    pad_err = [
                        abs((float(b[1]) - float(a[1])) - pad_ms)
                        for a, b in zip(segs, segs_p)
                    ]
            row = {
                "case": label,
                "method": cname,
                "med_start": float(np.median(start_err)),
                "p90_start": float(np.percentile(start_err, 90)),
                "within_50": float(np.mean([e <= 50 for e in start_err])),
                "within_100": float(np.mean([e <= 100 for e in start_err])),
                "pad_med": float(np.median(pad_err)) if pad_err else None,
                "n": len(start_err),
            }
            clock_rows.append(row)
            print(
                f"  C {cname:18} med|Δ|={row['med_start']:6.1f} "
                f"≤100={row['within_100']:.0%} padε={row['pad_med']}"
            )

    # --- aggregates ---
    method_names = ["mono", "decode_v1", "grammar", "grammar+qdc_prior", "qdc"]
    summary = {"structure": {}, "clock": {}, "elapsed_s": round(time.time() - t0, 2)}

    print("\n======== STRUCTURE AGGREGATE ========")
    for name in method_names:
        rows = [r["methods"][name] for r in structure_rows]
        n = len(rows)
        n_exact = sum(1 for r in rows if r["exact"])
        n_bt = sum(1 for r in rows if r["bt_match"])
        rep = [r for r in rows if r["has_gold_bt"]]
        clean = [r for r in rows if not r["has_gold_bt"]]
        n_rep_exact = sum(1 for r in rep if r["exact"])
        n_fp = sum(1 for r in clean if r["fp_on_clean"])
        mean_p = float(np.mean([r["prec"] for r in rows]))
        mean_r = float(np.mean([r["rec"] for r in rows]))
        mean_r_rep = float(np.mean([r["rec"] for r in rep])) if rep else 0.0
        summary["structure"][name] = {
            "exact": n_exact,
            "n": n,
            "bt_match": n_bt,
            "repeat_positive_exact": f"{n_rep_exact}/{len(rep)}",
            "false_positives_on_clean": n_fp,
            "mean_prec": mean_p,
            "mean_rec": mean_r,
            "mean_rec_on_repeat_positive": mean_r_rep,
        }
        print(
            f"{name:18} exact {n_exact}/{n}  bt {n_bt}/{n}  "
            f"rep+ exact {n_rep_exact}/{len(rep)}  cleanFP={n_fp}  "
            f"P={mean_p:.0%} R={mean_r:.0%} R_rep+={mean_r_rep:.0%}"
        )

    print("\n======== CLOCK AGGREGATE (vs shipped times; proxy) ========")
    for cname in ("global", "by_runs", "anchored"):
        rows = [r for r in clock_rows if r["method"] == cname]
        if not rows:
            continue
        total_n = sum(r["n"] for r in rows)

        def wavg(k):
            return sum(r[k] * r["n"] for r in rows) / total_n

        pads = [r["pad_med"] for r in rows if r["pad_med"] is not None]
        summary["clock"][cname] = {
            "weighted_med_start": wavg("med_start"),
            "weighted_within_100": wavg("within_100"),
            "pad_med_mean": float(np.mean(pads)) if pads else None,
            "n_words": total_n,
        }
        print(
            f"{cname:18} med|Δ|≈{wavg('med_start'):.1f}  "
            f"≤100≈{wavg('within_100'):.0%}  "
            f"padε≈{np.mean(pads) if pads else float('nan'):.1f}"
        )

    out = {
        "summary": summary,
        "structure_cases": structure_rows,
        "clock_rows": clock_rows,
    }
    out_path = LAB / "results" / "codex_path_eval.json"
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2))
    print(f"\nwrote {out_path} in {summary['elapsed_s']}s")


if __name__ == "__main__":
    main()
