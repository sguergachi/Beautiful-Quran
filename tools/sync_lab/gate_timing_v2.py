#!/usr/bin/env python3
"""Calibrate Timing V2 acceptance for a defendable high-accuracy subset.

Pipeline (build-time only):
  1. Energy-snap word starts within ±window (kills systematic early bias).
  2. Drop rows with a dead-zone onset (proven error).
  3. Optional dual-witness: mono CTC max |Δstart| ≤ threshold → keep.
     Rows that disagree abstain (coverage loss, not silent wrong ink).

This does **not** mint ear-gold 99%. It produces the *shape* of the accuracy
vs coverage tradeoff and a shippable high-agreement subset. The headline 99%
still requires `independent_labels/frozen-v1.json` (see docs/V2_99_PROTOCOL.md).
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

from aligners import load_mono_16k, refine_starts_to_onsets  # noqa: E402
from generate_timing_v2 import align_ayah, audio_file, keyframed_segments, load_words  # noqa: E402
from validate_timing_v2 import dead_zone_onset, frame_rms, is_repeat_row  # noqa: E402

GENERATOR = "sync_lab/gate_timing_v2.py@1"


def absolute_keyframes(segment: dict) -> list[list[float]]:
    start = int(segment["startMs"])
    return [
        [start + int(point["offsetMs"]), float(point["progress"])]
        for point in segment["keyframes"]
    ]


def _dead_zone_start(wave: np.ndarray, rate: int, start_ms: int) -> bool:
    """True if start sits in a real dead zone (no energy rise in the next ~60ms)."""
    frame = max(1, int(rate * 0.02))
    idx = min(len(wave) - 1, max(0, int(start_ms * rate / 1000)))
    ahead = wave[idx : idx + int(0.2 * rate)]
    local = wave[idx : idx + 3 * frame]
    if ahead.size < frame or local.size < frame:
        return False
    peak = float(np.sqrt(np.mean(ahead * ahead) + 1e-12))
    now = float(np.sqrt(np.mean(local * local) + 1e-12))
    return peak > 1e-6 and now < peak * 0.1


def energy_snap_row(
    row: dict,
    audio: Path,
    window_ms: float = 80.0,
) -> dict | None:
    """Snap starts to energy onsets; rebase keyframes; abstain only on dead-zone.

    If snapping would invalidate acoustic keyframes, keep the original spans
    (fail-open on snap, fail-closed on proven silence onsets).
    """
    wave, rate = load_mono_16k(audio)
    segs = [
        [int(s["position"]), int(s["startMs"]), int(s["endMs"])]
        for s in row["segments"]
    ]
    abs_kf = [absolute_keyframes(s) for s in row["segments"]]
    # Do not pull a start past the first keyframe (would make offset ≤ 0).
    refined = refine_starts_to_onsets(segs, audio, window_ms=window_ms, y=wave, sr=rate)
    for i, points in enumerate(abs_kf):
        if not points:
            continue
        first_abs = int(round(points[0][0]))
        # first keyframe offset must be ≥ 1 under keyframed_segments
        refined[i][1] = min(int(refined[i][1]), first_abs - 1)
        if i > 0:
            refined[i][1] = max(refined[i][1], refined[i - 1][1] + 1)
    for i in range(len(refined) - 1):
        refined[i][2] = max(refined[i][1] + 1, refined[i + 1][1])
    refined[-1][2] = max(refined[-1][1] + 1, segs[-1][2])

    rebuilt = keyframed_segments(refined, abs_kf)
    snapped = True
    if not rebuilt or len(rebuilt) != len(row["segments"]):
        rebuilt = row["segments"]
        snapped = False

    for segment in rebuilt:
        if _dead_zone_start(wave, rate, int(segment["startMs"])):
            return None
    out = dict(row)
    out["segments"] = rebuilt
    out["energySnapped"] = snapped
    return out


def ctc_witness_max_abs(row: dict, db: Path, audio_dir: Path) -> tuple[float | None, list[int]]:
    """Return max |V2-CTC| start error for mono rows; None if unusable."""
    if is_repeat_row(row):
        return None, []
    words = load_words(db, int(row["surah"]), int(row["ayah"]))
    if len(words) != len(row["segments"]):
        return None, []
    audio = audio_file(audio_dir, "Alafasy_128kbps", int(row["surah"]), int(row["ayah"]))
    try:
        segs, score = align_ayah(audio, words)
    except Exception:
        return None, []
    if not segs or len(segs) != len(words):
        return None, []
    errs = [
        int(v["startMs"]) - int(c["startMs"])
        for v, c in zip(row["segments"], segs)
    ]
    return float(max(abs(e) for e in errs)), errs


def summarize_gate(kept: list[dict], dropped: dict[str, int], universe: int) -> dict:
    words = sum(len(r["segments"]) for r in kept)
    return {
        "keptRows": len(kept),
        "keptWordOccurrences": words,
        "coveragePctOfAlafasy": round(100 * len(kept) / max(1, universe), 2),
        "dropped": dropped,
        "note": (
            "Accuracy and coverage are separate. Dual-witness agreement is not "
            "ear gold; freeze independent_labels before claiming 99%."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--in",
        dest="inp",
        type=Path,
        default=ROOT / "tools/timing_v2/alafasy_qua.json",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=ROOT / "tools/timing_v2/alafasy_qua_gated.json",
    )
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=LAB / "audio" / "Alafasy_128kbps",
    )
    parser.add_argument(
        "--energy-window-ms",
        type=float,
        default=80.0,
        help="Snap starts to energy onsets within ±window (post-pause gold shows ~40ms early bias).",
    )
    parser.add_argument(
        "--ctc-max-abs-ms",
        type=float,
        default=100.0,
        help="Mono dual-witness gate; set <0 to skip CTC (energy+silence only).",
    )
    parser.add_argument(
        "--skip-repeats-without-ctc-gate",
        action="store_true",
        help="When CTC gate is on, drop repeats (no mono witness) instead of keeping them.",
    )
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument(
        "--report",
        type=Path,
        default=LAB / "results" / "v2_gate_report.json",
    )
    args = parser.parse_args()

    payload = json.loads(args.inp.read_text(encoding="utf-8"))
    rows = payload["rows"]
    if args.limit:
        rows = rows[: args.limit]

    kept: list[dict] = []
    dropped = {
        "energy_snap_failed": 0,
        "dead_zone_or_invalid": 0,
        "ctc_disagree": 0,
        "ctc_unavailable": 0,
        "repeat_skipped": 0,
    }
    witness_errs: list[int] = []

    for index, row in enumerate(rows):
        audio = args.audio_dir / f"{row['surah']:03d}{row['ayah']:03d}.mp3"
        if not audio.exists():
            dropped["ctc_unavailable"] += 1
            continue
        snapped = energy_snap_row(row, audio, window_ms=args.energy_window_ms)
        if snapped is None:
            dropped["dead_zone_or_invalid"] += 1
            continue

        if args.ctc_max_abs_ms >= 0:
            if is_repeat_row(snapped):
                if args.skip_repeats_without_ctc_gate:
                    dropped["repeat_skipped"] += 1
                    continue
                # keep repeats only through energy/silence path
                kept.append(snapped)
                continue
            max_abs, errs = ctc_witness_max_abs(snapped, args.db, args.audio_dir)
            if max_abs is None:
                dropped["ctc_unavailable"] += 1
                continue
            if max_abs > args.ctc_max_abs_ms:
                dropped["ctc_disagree"] += 1
                continue
            witness_errs.extend(errs)
            snapped = dict(snapped)
            snapped["ctcWitnessMaxAbsMs"] = max_abs
            # Keep original QUA clock correlation as gateScore (build_db threshold).
        kept.append(snapped)
        if (index + 1) % 25 == 0:
            print(
                f"progress {index + 1}/{len(rows)} kept={len(kept)} dropped={sum(dropped.values())}",
                flush=True,
            )

    out_payload = {
        "schema": 2,
        "reciterId": payload["reciterId"],
        "reciter": payload["reciter"],
        "generator": payload.get("generator", "sync_lab/generate_qua_timing_v2.py@1"),
        "source": payload.get("source"),
        "sourceRevision": payload.get("sourceRevision"),
        "sourceAssetSha256": payload.get("sourceAssetSha256"),
        "minimumGateScore": payload.get("minimumGateScore", 0.7),
        "minimumPeakMargin": payload.get("minimumPeakMargin", 0.25),
        "postGate": {
            "tool": GENERATOR,
            "energyWindowMs": args.energy_window_ms,
            "ctcMaxAbsMs": args.ctc_max_abs_ms,
            "skipRepeatsWithoutCtc": args.skip_repeats_without_ctc_gate,
        },
        "rows": kept,
    }
    # load_timing_v2 requires known generator pins — keep original generator string
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(out_payload, ensure_ascii=False, indent=2) + "\n")

    report = summarize_gate(kept, dropped, universe=6236)
    if witness_errs:
        abs_e = sorted(abs(e) for e in witness_errs)
        report["dualWitnessOnsets"] = {
            "count": len(abs_e),
            "medianMs": statistics.median(abs_e),
            "p90Ms": abs_e[max(0, int(0.9 * len(abs_e)) - 1)],
            "within100Pct": 100 * sum(e <= 100 for e in abs_e) / len(abs_e),
            "within60Pct": 100 * sum(e <= 60 for e in abs_e) / len(abs_e),
            "signedMedianMs": statistics.median(witness_errs),
        }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Wrote {args.out} ({len(kept)} rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
