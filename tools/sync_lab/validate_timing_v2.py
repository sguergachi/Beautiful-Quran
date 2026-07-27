#!/usr/bin/env python3
"""Objective Timing V2 quality gates — accuracy and coverage stay separate.

These checks do not prove 99%. They falsify silent desync, over-long spans,
and unvalidated repeat structure without needing human labels.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
from pathlib import Path

import numpy as np

from timing_v2_metrics import summarize_errors

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
ALAFASY = 6236  # everyayah ayah count for Alafasy_128kbps


def load_payload(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def audio_duration_ms(path: Path) -> int:
    """Decode duration via ffmpeg null mux (works for mp3 without mutagen)."""
    out = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ],
        text=True,
    ).strip()
    return int(round(float(out) * 1000))


def decode_mono(path: Path, sample_rate: int = 8_000) -> np.ndarray:
    raw = subprocess.check_output(
        [
            "ffmpeg",
            "-v",
            "error",
            "-i",
            str(path),
            "-ac",
            "1",
            "-ar",
            str(sample_rate),
            "-f",
            "f32le",
            "pipe:1",
        ]
    )
    return np.frombuffer(raw, dtype=np.float32)


def frame_rms(wave: np.ndarray, sample_rate: int, frame_ms: int = 20) -> np.ndarray:
    size = max(1, round(sample_rate * frame_ms / 1000))
    n = len(wave) // size
    if n == 0:
        return np.array([], dtype=np.float64)
    clipped = wave[: n * size].reshape(n, size).astype(np.float64)
    return np.sqrt(np.mean(clipped * clipped, axis=1) + 1e-18)


def silence_mask(rms: np.ndarray, floor_db: float = -45.0) -> np.ndarray:
    db = 20 * np.log10(np.maximum(rms, 1e-12))
    return db < floor_db


def dead_zone_onset(rms: np.ndarray, start_ms: int, frame_ms: int = 20) -> bool:
    """True only if the labeled onset sits in silence and no energy rises soon after.

    Soft early taps one frame before speech must not count as failures.
    """
    if len(rms) == 0:
        return False
    index = min(len(rms) - 1, max(0, start_ms // frame_ms))
    # local floor relative to the next 200 ms of audio
    ahead = rms[index : min(len(rms), index + 10)]
    if len(ahead) < 3:
        return False
    peak = float(ahead.max())
    if peak <= 1e-6:
        return True
    # dead zone: current frame and next ~60 ms stay >20 dB below the local peak
    local = rms[index : min(len(rms), index + 4)]
    return bool(np.all(local < peak * 0.1))


def interior_pause_run(
    rms: np.ndarray,
    start_ms: int,
    end_ms: int,
    frame_ms: int = 20,
    pause_ms: int = 350,
) -> bool:
    """Flag only long interior silences that are not near either span edge."""
    if end_ms - start_ms < pause_ms + 200:
        return False
    a = max(0, (start_ms + 80) // frame_ms)
    b = min(len(rms), max(a + 1, (end_ms - 80) // frame_ms))
    silent = silence_mask(rms[a:b])
    run = 0
    need = max(1, pause_ms // frame_ms)
    for bit in silent:
        run = run + 1 if bit else 0
        if run >= need:
            return True
    return False


def objective_row_flags(
    row: dict,
    duration_ms: int | None,
    wave: np.ndarray | None = None,
    sample_rate: int = 8_000,
    pause_ms: int = 350,
) -> list[str]:
    """Return objective failure tags for one accepted V2 row."""
    flags: list[str] = []
    segments = row["segments"]
    starts = [int(s["startMs"]) for s in segments]
    ends = [int(s["endMs"]) for s in segments]

    if any(end <= start for start, end in zip(starts, ends)):
        flags.append("empty_span")
    if any(left >= right for left, right in zip(starts, starts[1:])):
        flags.append("non_monotonic")
    if duration_ms is not None:
        if ends and ends[-1] > duration_ms + 25:
            flags.append("past_duration")
        if starts and starts[0] < 0:
            flags.append("negative_start")

    if wave is not None and len(wave) > sample_rate // 10:
        rms = frame_rms(wave, sample_rate)
        for start in starts:
            if dead_zone_onset(rms, start):
                flags.append("onset_in_silence")
                break
        for start, end in zip(starts, ends):
            if interior_pause_run(rms, start, end, pause_ms=pause_ms):
                flags.append("span_crosses_pause")
                break

    # keyframe plateau / progress sanity already enforced at load; re-check ends
    for segment in segments:
        last = segment["keyframes"][-1]
        if last["progress"] != 1.0:
            flags.append("incomplete_keyframes")
            break
        if int(last["offsetMs"]) > int(segment["endMs"]) - int(segment["startMs"]):
            flags.append("keyframe_past_span")
            break
    return sorted(set(flags))


def is_repeat_row(row: dict) -> bool:
    positions = [int(s["position"]) for s in row["segments"]]
    return len(positions) != len(set(positions))


def repeat_episode_correlations(
    wave: np.ndarray,
    row: dict,
    sample_rate: int = 8_000,
) -> list[float]:
    """Max-lag self-xcorr of each re-say against the first emission.

    Tajweed re-says rarely match at zero lag; report peak correlation only as a
    diagnostic. Do not use this alone as an accept/reject gate.
    """
    from scipy.signal import correlate

    by_pos: dict[int, list[tuple[int, int]]] = {}
    for segment in row["segments"]:
        by_pos.setdefault(int(segment["position"]), []).append(
            (int(segment["startMs"]), int(segment["endMs"]))
        )
    scores: list[float] = []
    for spans in by_pos.values():
        if len(spans) < 2:
            continue
        first_start, first_end = spans[0]
        a0 = round(first_start * sample_rate / 1000)
        a1 = round(first_end * sample_rate / 1000)
        if a1 <= a0 + sample_rate // 20:
            continue
        ref = wave[a0:a1].astype(np.float64)
        ref -= ref.mean()
        ref_norm = np.linalg.norm(ref)
        if ref_norm == 0:
            continue
        ref = ref / ref_norm
        for start, end in spans[1:]:
            b0 = round(start * sample_rate / 1000)
            b1 = round(end * sample_rate / 1000)
            if b1 <= b0 + sample_rate // 20:
                continue
            cand = wave[b0:b1].astype(np.float64)
            cand -= cand.mean()
            cand_norm = np.linalg.norm(cand)
            if cand_norm == 0:
                continue
            cand = cand / cand_norm
            scores.append(float(correlate(ref, cand, mode="full").max()))
    return scores


def multi_window_clock_deltas(
    source: np.ndarray,
    clip: np.ndarray,
    sample_rate: int,
    source_zero_ms: float,
) -> list[float]:
    """Cross-correlate head/mid/tail of the clip; return zero-point deltas in ms."""
    from qua_timing import match_audio_clock

    n = len(clip)
    if n < sample_rate * 2:
        return []
    thirds = [
        (0, n // 3),
        (n // 3, 2 * n // 3),
        (2 * n // 3, n),
    ]
    deltas = []
    for start, end in thirds:
        piece = clip[start:end]
        if len(piece) < sample_rate // 2:
            continue
        # search around the expected location of this piece in source
        expected = source_zero_ms + start * 1000.0 / sample_rate
        window_start = max(0.0, expected / 1000.0 - 1.5)
        a = round(window_start * sample_rate)
        b = min(len(source), a + len(piece) + round(3.0 * sample_rate))
        if b - a < len(piece):
            continue
        match = match_audio_clock(source[a:b], piece, sample_rate, window_start * 1000.0)
        if match is None:
            continue
        piece_zero = match.source_zero_ms - start * 1000.0 / sample_rate
        deltas.append(piece_zero - source_zero_ms)
    return deltas


def summarize_payload(
    payload: dict,
    audio_dir: Path | None = None,
    source_audio_dir: Path | None = None,
    max_audio_rows: int | None = None,
    check_repeats: bool = True,
    check_clock_windows: bool = False,
) -> dict:
    rows = payload["rows"]
    total_universe = ALAFASY if payload.get("reciter") == "Alafasy_128kbps" else None
    flag_counts: dict[str, int] = {}
    flagged_rows = 0
    repeat_rows = 0
    repeat_scores: list[float] = []
    clock_deltas: list[float] = []
    checked_audio = 0

    for index, row in enumerate(rows):
        is_rep = is_repeat_row(row)
        if is_rep:
            repeat_rows += 1
        duration = None
        wave = None
        path = None
        if audio_dir is not None and (
            max_audio_rows is None or checked_audio < max_audio_rows
        ):
            path = audio_dir / f"{row['surah']:03d}{row['ayah']:03d}.mp3"
            if path.exists():
                duration = audio_duration_ms(path)
                if check_repeats and is_rep or check_clock_windows or True:
                    wave = decode_mono(path)
                checked_audio += 1
        flags = objective_row_flags(row, duration, wave)
        if flags:
            flagged_rows += 1
            for flag in flags:
                flag_counts[flag] = flag_counts.get(flag, 0) + 1
        if wave is not None and is_rep and check_repeats:
            repeat_scores.extend(repeat_episode_correlations(wave, row))
        if (
            check_clock_windows
            and wave is not None
            and source_audio_dir is not None
            and "sourceZeroMs" in row
        ):
            source_path = source_audio_dir / f"{row['surah']:03d}.mp3"
            if source_path.exists():
                source = decode_mono(source_path)
                clock_deltas.extend(
                    multi_window_clock_deltas(
                        source,
                        wave,
                        8_000,
                        float(row["sourceZeroMs"]),
                    )
                )

    word_occs = sum(len(row["segments"]) for row in rows)
    keyframes = sum(
        len(segment["keyframes"])
        for row in rows
        for segment in row["segments"]
    )
    summary = {
        "acceptedRows": len(rows),
        "wordOccurrences": word_occs,
        "keyframes": keyframes,
        "repeatRows": repeat_rows,
        "coveragePctOfAlafasy": (
            round(100 * len(rows) / total_universe, 2) if total_universe else None
        ),
        "audioRowsChecked": checked_audio,
        "flaggedRows": flagged_rows,
        "flaggedPctOfAccepted": round(100 * flagged_rows / max(1, len(rows)), 2),
        "flagCounts": dict(sorted(flag_counts.items())),
        "repeatSelfCorrDiagnostic": {
            "count": len(repeat_scores),
            "median": float(np.median(repeat_scores)) if repeat_scores else None,
            "p10": float(np.percentile(repeat_scores, 10)) if repeat_scores else None,
            "below0_25": sum(s < 0.25 for s in repeat_scores),
            "note": "max-lag waveform xcorr; tajweed re-says are often low — not a hard gate",
        },
        "multiWindowClockDeltaMs": summarize_errors(
            [int(round(d)) for d in clock_deltas]
        )
        if clock_deltas
        else {"count": 0},
        "note": (
            "coverage and accuracy are separate; objective flags are not ear gold; "
            "99% requires frozen independent labels"
        ),
    }
    return summary


def historical_overlap(
    payload: dict,
    historical_path: Path,
) -> dict:
    """Report structure agreement on grammar-valid historical patches (evidence)."""
    hist = json.loads(historical_path.read_text(encoding="utf-8"))
    # support both list-of-edits and {edits:[...]} shapes
    edits = hist.get("edits", hist) if isinstance(hist, dict) else hist
    v2 = {(r["surah"], r["ayah"]): r for r in payload["rows"]}
    compared = 0
    structure_exact = 0
    onset_errors: list[int] = []
    for edit in edits:
        reciter = int(edit.get("reciterId", edit.get("reciter_id", 1)))
        if reciter != int(payload.get("reciterId", 1)):
            continue
        surah = int(edit.get("surahId", edit.get("surah")))
        ayah = int(edit.get("ayah", edit.get("ayah_number")))
        row = v2.get((surah, ayah))
        if row is None:
            continue
        gold = edit["segments"]
        gold_pos = [int(s[0] if isinstance(s, list) else s["position"]) for s in gold]
        pred_pos = [int(s["position"]) for s in row["segments"]]
        compared += 1
        if gold_pos != pred_pos:
            continue
        structure_exact += 1
        gold_starts = [int(s[1] if isinstance(s, list) else s["startMs"]) for s in gold]
        pred_starts = [int(s["startMs"]) for s in row["segments"]]
        onset_errors.extend(p - g for p, g in zip(pred_starts, gold_starts))
    return {
        "overlapRows": compared,
        "structureExact": structure_exact,
        "structureExactPct": round(100 * structure_exact / max(1, compared), 2),
        "onsets": summarize_errors(onset_errors),
        "note": "historical patches are regression evidence, not independent gold",
    }


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
    parser.add_argument(
        "--source-audio-dir",
        type=Path,
        default=LAB / "audio" / "qua_alafasy",
    )
    parser.add_argument("--max-audio-rows", type=int, default=200)
    parser.add_argument("--check-clock-windows", action="store_true")
    parser.add_argument(
        "--historical",
        type=Path,
        default=LAB / "historical_manual_patches.json",
    )
    parser.add_argument("--out", type=Path, default=LAB / "results" / "v2_validate.json")
    args = parser.parse_args()

    payload = load_payload(args.payload)
    summary = summarize_payload(
        payload,
        audio_dir=args.audio_dir if args.audio_dir.exists() else None,
        source_audio_dir=(
            args.source_audio_dir if args.source_audio_dir.exists() else None
        ),
        max_audio_rows=args.max_audio_rows,
        check_clock_windows=args.check_clock_windows,
    )
    if args.historical.exists():
        summary["historicalOverlap"] = historical_overlap(payload, args.historical)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
