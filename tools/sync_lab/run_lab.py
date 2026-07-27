#!/usr/bin/env python3
"""Sync fidelity lab — automated word-timing bake-off.

Primary non-circular metrics:
  - pad-shift recovery residual (ms) — lower better
  - CTC path log-prob when available — higher better
  - structural health (count match, mono, last-word tail)
  - |Δ| vs shipped baseline reported as distance, not truth

Usage:
  source /tmp/alignlab-venv/bin/activate
  python tools/sync_lab/run_lab.py --pad-test --limit 20
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import traceback
from pathlib import Path

import numpy as np

LAB = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB))

from aligners import (  # noqa: E402
    boundary_energy_rise,
    ctc_force_align_words,
    energy_uniform_align,
    ensemble_median,
    equal_split,
    load_mono_16k,
    pad_silence,
    refine_starts_to_onsets,
    snap_lead_in,
    trim_trailing_silence,
)
from metrics import aggregate, score_segments  # noqa: E402

ARABIC_CTC = "jonatasgrosman/wav2vec2-large-xlsr-53-arabic"
QURAN_PHON = "TBOGamer22/wav2vec2-quran-phonetics"
PAD_MS = 200.0


def audio_path(slug: str, surah: int, ayah: int) -> Path:
    return LAB / "audio" / slug / f"{surah:03d}{ayah:03d}.mp3"


def first_pass(segs):
    out, seen = [], set()
    for pos, s, e in segs:
        if pos not in seen:
            out.append([pos, s, e])
            seen.add(pos)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reciters", nargs="+", default=["Alafasy_128kbps", "Husary_64kbps"])
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--pad-test", action="store_true")
    ap.add_argument("--try-quran-model", action="store_true")
    ap.add_argument("--out", default="lab_run.json")
    args = ap.parse_args()

    eval_set = json.loads((LAB / "eval_set.json").read_text())
    ayahs = eval_set["ayahs"]
    if args.limit:
        ayahs = ayahs[: args.limit]

    methods = [
        "baseline",
        "equal_split",
        "energy_uniform",
        "ctc_arabic",
        "ctc_arabic_lead",
        "ctc_arabic_lead_trim",
        "ctc_arabic_lead_onset40_trim",
        "baseline_lead_trim",
        "ensemble_ctc_base",
    ]
    if args.try_quran_model:
        methods += ["ctc_quran", "ctc_quran_lead_onset40_trim"]

    rows: dict[str, list] = {m: [] for m in methods}
    per_ayah = []
    ctc_cache: dict = {}

    def get_ctc(path, words, model_id=ARABIC_CTC, wave=None, srate=None):
        # Don't cache padded waves
        if wave is not None:
            return ctc_force_align_words(
                path, words, model_id=model_id, return_score=True,
                waveform=wave, sr=srate,
            )
        k = (str(path), model_id)
        if k not in ctc_cache:
            ctc_cache[k] = ctc_force_align_words(
                path, words, model_id=model_id, return_score=True
            )
        return ctc_cache[k]

    t0 = time.time()
    for slug in args.reciters:
        for a in ayahs:
            path = audio_path(slug, a["surah"], a["ayah"])
            if not path.exists():
                print(f"missing {path}")
                continue
            words = [w["arabic"] for w in a["words"]]
            bl = a["baseline"].get(slug)
            if not bl:
                continue
            baseline_fp = first_pass(bl)
            expected = a["n_words"]
            y, sr = load_mono_16k(path)
            dur = 1000.0 * len(y) / sr

            try:
                ctc_segs, ctc_score = get_ctc(path, words)
            except Exception as e:
                print(f"CTC fail {path}: {e}")
                traceback.print_exc()
                ctc_segs, ctc_score = None, None

            quran_segs = quran_score = None
            if args.try_quran_model:
                try:
                    quran_segs, quran_score = get_ctc(path, words, QURAN_PHON)
                except Exception as e:
                    print(f"quran model fail: {e}")

            # pad recovery for raw CTC (and baseline proxy via re-run only CTC)
            pad_ctc = None
            if args.pad_test and ctc_segs is not None:
                try:
                    y_pad = pad_silence(y, sr, PAD_MS)
                    segs_pad, _ = get_ctc(path, words, wave=y_pad, srate=sr)
                    if segs_pad and len(segs_pad) == len(ctc_segs):
                        pad_ctc = [
                            abs((float(b[1]) - float(a[1])) - PAD_MS)
                            for a, b in zip(ctc_segs, segs_pad)
                        ]
                except Exception as e:
                    print(f"pad fail {path}: {e}")

            pad_quran = None
            if args.pad_test and quran_segs is not None:
                try:
                    y_pad = pad_silence(y, sr, PAD_MS)
                    segs_pad, _ = get_ctc(path, words, QURAN_PHON, wave=y_pad, srate=sr)
                    if segs_pad and len(segs_pad) == len(quran_segs):
                        pad_quran = [
                            abs((float(b[1]) - float(a[1])) - PAD_MS)
                            for a, b in zip(quran_segs, segs_pad)
                        ]
                except Exception as e:
                    print(f"pad quran fail: {e}")

            ayah_rec = {"surah": a["surah"], "ayah": a["ayah"], "reciter": slug, "methods": {}}

            for m in methods:
                path_score = None
                segs = None
                pad_errs = None

                if m == "baseline":
                    segs = baseline_fp
                elif m == "equal_split":
                    segs = equal_split(path, words)
                elif m == "energy_uniform":
                    segs = energy_uniform_align(path, words)
                elif m == "ctc_arabic":
                    segs = ctc_segs
                    path_score = ctc_score
                    pad_errs = pad_ctc
                elif m == "ctc_arabic_lead":
                    segs = snap_lead_in(ctc_segs, path, y=y, sr=sr) if ctc_segs else None
                    path_score = ctc_score
                    pad_errs = pad_ctc
                elif m == "ctc_arabic_lead_trim":
                    if ctc_segs:
                        segs = trim_trailing_silence(
                            snap_lead_in(ctc_segs, path, y=y, sr=sr), path
                        )
                    path_score = ctc_score
                    pad_errs = pad_ctc
                elif m == "ctc_arabic_lead_onset40_trim":
                    if ctc_segs:
                        s = snap_lead_in(ctc_segs, path, y=y, sr=sr)
                        s = refine_starts_to_onsets(s, path, window_ms=40, y=y, sr=sr)
                        segs = trim_trailing_silence(s, path)
                    path_score = ctc_score
                    pad_errs = pad_ctc
                elif m == "baseline_lead_trim":
                    segs = trim_trailing_silence(
                        snap_lead_in(baseline_fp, path, y=y, sr=sr), path
                    )
                elif m == "ensemble_ctc_base":
                    if ctc_segs:
                        refined = trim_trailing_silence(
                            snap_lead_in(ctc_segs, path, y=y, sr=sr), path
                        )
                        segs = ensemble_median([refined, baseline_fp])
                    path_score = ctc_score
                elif m == "ctc_quran":
                    segs = quran_segs
                    path_score = quran_score
                    pad_errs = pad_quran
                elif m == "ctc_quran_lead_onset40_trim":
                    if quran_segs:
                        s = snap_lead_in(quran_segs, path, y=y, sr=sr)
                        s = refine_starts_to_onsets(s, path, window_ms=40, y=y, sr=sr)
                        segs = trim_trailing_silence(s, path)
                    path_score = quran_score
                    pad_errs = pad_quran
                else:
                    continue

                if segs is None:
                    continue

                rises = boundary_energy_rise(y, sr, [float(s[1]) for s in segs])
                row = score_segments(
                    segs,
                    expected_words=expected,
                    audio_dur_ms=dur,
                    ref=baseline_fp,
                    y=y,
                    sr=sr,
                    method=m,
                    path_score=path_score,
                    boundary_rises=rises,
                    pad_recovery_errs=pad_errs,
                )
                rows[m].append(row)
                ayah_rec["methods"][m] = {
                    "segs": segs,
                    "med_start_err_vs_base": (
                        float(np.median(row["start_errs"])) if row["start_errs"] else None
                    ),
                    "path_score": path_score,
                    "pad_med": float(np.median(pad_errs)) if pad_errs else None,
                    "last_ratio": row["last_ratio"],
                }
            per_ayah.append(ayah_rec)
            print(f"done {slug} {a['surah']}:{a['ayah']} n={expected}", flush=True)

    summary = []
    for m, r in rows.items():
        if r:
            summary.append(aggregate(m, r).as_dict())
    summary.sort(key=lambda x: -x["score"])

    out = {
        "elapsed_s": round(time.time() - t0, 2),
        "n_ayah_reciter": len(per_ayah),
        "reciters": args.reciters,
        "pad_ms": PAD_MS if args.pad_test else None,
        "ranking": summary,
        "per_ayah": per_ayah,
    }
    out_path = LAB / "results" / args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2))

    print("\n=== RANKING (higher better) ===")
    print(
        f"{'method':32} {'score':>6} {'cnt%':>5} {'mono%':>5} "
        f"{'padεmed':>8} {'path':>7} {'lastR':>5} {'|Δbase|':>8}"
    )
    for s in summary:
        def p(x):
            return f"{100 * x:5.1f}" if x is not None else "  n/a"

        def f(x, w=7):
            return f"{x:{w}.2f}" if x is not None else " " * (w - 3) + "n/a"

        print(
            f"{s['method']:32} {s['score']:6.1f} {p(s['word_count_match_rate'])} "
            f"{p(s['mono_ok_rate'])} {f(s['pad_recovery_med_ms'],8)} "
            f"{f(s['mean_path_score'],7)} {f(s['last_word_tail_ratio'],5)} "
            f"{f(s['med_abs_start_err'],8)}"
        )
    print(f"\nWrote {out_path} ({out['elapsed_s']}s)")


if __name__ == "__main__":
    main()
