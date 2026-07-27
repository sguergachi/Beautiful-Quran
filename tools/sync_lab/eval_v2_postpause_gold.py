#!/usr/bin/env python3
"""Post-pause objective onset gold — no human labels required.

After ≥pause_ms of near-silence, the next energy rise is a measurable word
onset (~10–20 ms). Compare V2 starts on those positions only. This is an
easy-subset lower bound, not the full 99% product bar.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

import numpy as np

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
sys.path.insert(0, str(LAB))

from aligners import load_mono_16k  # noqa: E402
from metrics import energy_onsets  # noqa: E402
from timing_v2_metrics import summarize_errors  # noqa: E402


def speech_mask(y: np.ndarray, sr: int, thr_ratio: float = 0.08) -> np.ndarray:
    frame = max(1, int(sr * 0.02))
    hop = max(1, int(sr * 0.01))
    n = 1 + max(0, (len(y) - frame) // hop)
    rms = np.array(
        [np.sqrt(np.mean(y[i * hop : i * hop + frame] ** 2) + 1e-12) for i in range(n)],
        dtype=np.float64,
    )
    thr = float(rms.max()) * thr_ratio if rms.size else 1.0
    return rms > thr, hop, sr


def post_pause_gold_onsets(
    y: np.ndarray,
    sr: int,
    pause_ms: float = 250.0,
) -> list[float]:
    """Energy onsets that follow a clear silence run."""
    speech, hop, _ = speech_mask(y, sr)
    if speech.size == 0:
        return []
    need = max(1, int(pause_ms / (hop * 1000 / sr)))
    onsets = energy_onsets(y, sr)
    gold = []
    for t in onsets:
        idx = int(t / (hop * 1000 / sr))
        if idx < need:
            continue
        if not speech[idx - need : idx].any() and (
            idx < len(speech) and speech[idx : min(len(speech), idx + 3)].any()
        ):
            gold.append(float(t))
    return gold


def match_nearest(pred: list[int], gold: list[float], max_ms: float = 200.0) -> list[int]:
    """Signed pred-gold errors for each gold onset matched to nearest pred."""
    if not pred or not gold:
        return []
    errors = []
    for g in gold:
        best = min(pred, key=lambda p: abs(p - g))
        if abs(best - g) <= max_ms:
            errors.append(int(round(best - g)))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--payload",
        type=Path,
        default=ROOT / "tools/timing_v2/alafasy_qua.json",
    )
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=LAB / "audio" / "Alafasy_128kbps",
    )
    parser.add_argument("--pause-ms", type=float, default=250.0)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument(
        "--out",
        type=Path,
        default=LAB / "results" / "v2_postpause_gold.json",
    )
    args = parser.parse_args()

    payload = json.loads(args.payload.read_text(encoding="utf-8"))
    rows = payload["rows"]
    if args.limit:
        rows = rows[: args.limit]

    all_errors: list[int] = []
    matched_rows = 0
    gold_count = 0
    for row in rows:
        path = args.audio_dir / f"{row['surah']:03d}{row['ayah']:03d}.mp3"
        if not path.exists():
            continue
        y, sr = load_mono_16k(path)
        gold = post_pause_gold_onsets(y, sr, pause_ms=args.pause_ms)
        gold_count += len(gold)
        pred = [int(s["startMs"]) for s in row["segments"]]
        errs = match_nearest(pred, gold)
        if errs:
            matched_rows += 1
            all_errors.extend(errs)

    summary = {
        "rowsScanned": len(rows),
        "rowsWithMatches": matched_rows,
        "goldOnsetsFound": gold_count,
        "matchedOnsets": len(all_errors),
        "onsets": summarize_errors(all_errors),
        "signedMedianMs": statistics.median(all_errors) if all_errors else None,
        "pauseMs": args.pause_ms,
        "note": (
            "Easy-subset objective gold after long silence. Not full-product 99%. "
            "Use to CI-gate regressions and calibrate early bias."
        ),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
