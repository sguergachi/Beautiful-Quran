"""Automated quality metrics for word-timing segments (no human ear)."""
from __future__ import annotations

import math
from dataclasses import dataclass, asdict
from typing import Sequence

import numpy as np


@dataclass
class TimingQuality:
    method: str
    n_ayahs: int
    n_words: int
    word_count_match_rate: float
    mono_ok_rate: float
    med_abs_start_err: float | None  # vs baseline (reference, not gold)
    p90_abs_start_err: float | None
    mean_abs_start_err: float | None
    med_gap_ms: float
    mean_gap_ms: float
    last_word_tail_ratio: float
    # non-circular acoustic quality
    mean_boundary_rise_db: float | None  # higher = clearer onsets at starts
    frac_positive_rise: float | None
    # CTC path confidence when available
    mean_path_score: float | None
    # pad-shift recovery error (ms residual after subtracting pad)
    pad_recovery_med_ms: float | None
    # composite (higher better)
    score: float

    def as_dict(self) -> dict:
        return asdict(self)


def _gaps(segs: Sequence[Sequence[int | float]]) -> list[float]:
    gaps = []
    for i in range(len(segs) - 1):
        g = float(segs[i + 1][1]) - float(segs[i][2])
        if g > 0:
            gaps.append(g)
    return gaps


def _is_monotonic(segs: Sequence[Sequence[int | float]]) -> bool:
    if not segs:
        return False
    prev_end = -1.0
    for s in segs:
        start, end = float(s[1]), float(s[2])
        if end < start:
            return False
        if start + 2 < prev_end:  # allow 1–2 ms rounding / karaoke abut
            return False
        prev_end = max(prev_end, end)
    return True


def compare_to_ref(hyp, ref) -> tuple[list[float], list[float]]:
    ref_by_pos = {}
    for pos, start, end in ref:
        p = int(pos)
        if p not in ref_by_pos:
            ref_by_pos[p] = (float(start), float(end))
    start_errs, end_errs = [], []
    seen = set()
    for pos, start, end in hyp:
        p = int(pos)
        if p in seen or p not in ref_by_pos:
            continue
        seen.add(p)
        rs, re = ref_by_pos[p]
        start_errs.append(abs(float(start) - rs))
        end_errs.append(abs(float(end) - re))
    return start_errs, end_errs


def energy_onsets(y, sr, frame_ms=10.0, hop_ms=5.0, min_gap_ms=40.0, prominence=0.12):
    if y.size == 0:
        return np.array([], dtype=np.float64)
    frame = max(1, int(sr * frame_ms / 1000))
    hop = max(1, int(sr * hop_ms / 1000))
    n = 1 + max(0, (len(y) - frame) // hop)
    rms = np.empty(n, dtype=np.float64)
    for i in range(n):
        sl = y[i * hop : i * hop + frame]
        rms[i] = math.sqrt(float(np.mean(sl * sl)) + 1e-12)
    if n >= 5:
        rms = np.convolve(rms, np.ones(5) / 5, mode="same")
    peak = float(np.max(rms)) + 1e-12
    thr = peak * prominence
    d = np.diff(rms, prepend=rms[0])
    onsets, last = [], -1e9
    for i in range(1, n):
        t_ms = (i * hop) * 1000.0 / sr
        if d[i] > 0 and rms[i] > thr and rms[i - 1] <= thr * 1.05:
            if t_ms - last >= min_gap_ms:
                onsets.append(t_ms)
                last = t_ms
        elif d[i] > thr * 0.35 and rms[i] > thr * 1.2:
            if t_ms - last >= min_gap_ms:
                onsets.append(t_ms)
                last = t_ms
    return np.array(onsets, dtype=np.float64)


def score_segments(
    segs,
    expected_words: int,
    audio_dur_ms: float | None = None,
    ref=None,
    y=None,
    sr=None,
    method: str = "unknown",
    path_score: float | None = None,
    boundary_rises: list[float] | None = None,
    pad_recovery_errs: list[float] | None = None,
) -> dict:
    first_pass = []
    seen = set()
    for pos, start, end in segs:
        p = int(pos)
        if p not in seen:
            first_pass.append([p, start, end])
            seen.add(p)
    count_match = len(first_pass) == expected_words or len(segs) == expected_words
    mono = _is_monotonic(segs)
    gaps = _gaps(segs)
    durs = [float(s[2]) - float(s[1]) for s in segs]
    last_ratio = 0.0
    if durs:
        total = audio_dur_ms if audio_dur_ms and audio_dur_ms > 0 else sum(durs)
        last_ratio = durs[-1] / max(total, 1.0)

    start_errs = end_errs = None
    if ref is not None and segs:
        start_errs, end_errs = compare_to_ref(segs, ref)

    if boundary_rises is None and y is not None and sr is not None and segs:
        from aligners import boundary_energy_rise
        boundary_rises = boundary_energy_rise(y, sr, [float(s[1]) for s in segs])

    return {
        "method": method,
        "count_match": count_match,
        "mono": mono,
        "n_segs": len(segs),
        "expected": expected_words,
        "med_gap": float(np.median(gaps)) if gaps else 0.0,
        "mean_gap": float(np.mean(gaps)) if gaps else 0.0,
        "last_ratio": last_ratio,
        "start_errs": start_errs,
        "end_errs": end_errs,
        "path_score": path_score,
        "boundary_rises": boundary_rises,
        "pad_recovery_errs": pad_recovery_errs,
    }


def aggregate(method: str, rows: list[dict]) -> TimingQuality:
    n = len(rows)
    if n == 0:
        return TimingQuality(method, 0, 0, 0, 0, None, None, None, 0, 0, 0,
                             None, None, None, None, 0.0)

    def flat(key):
        out = []
        for r in rows:
            v = r.get(key)
            if v is None:
                continue
            if isinstance(v, list):
                out.extend(v)
            else:
                out.append(v)
        return out

    start_errs = flat("start_errs")
    rises = flat("boundary_rises")
    pads = flat("pad_recovery_errs")
    paths = [r["path_score"] for r in rows if r.get("path_score") is not None]
    gaps = [r["mean_gap"] for r in rows]
    last_ratios = [r["last_ratio"] for r in rows]
    n_words = sum(r["n_segs"] for r in rows)
    count_rate = float(np.mean([1.0 if r["count_match"] else 0.0 for r in rows]))
    mono_rate = float(np.mean([1.0 if r["mono"] else 0.0 for r in rows]))

    mean_rise = float(np.mean(rises)) if rises else None
    frac_pos = float(np.mean([1.0 if r > 0 else 0.0 for r in rises])) if rises else None
    mean_path = float(np.mean(paths)) if paths else None
    pad_med = float(np.median(pads)) if pads else None

    # Composite: NO onset-hit term (circular for onset-refined methods).
    # Uses: count, mono, boundary energy rise, low last-word inflation,
    # low pad-recovery residual, path score when present.
    score = 0.0
    score += 20.0 * count_rate
    score += 15.0 * mono_rate
    if frac_pos is not None:
        score += 25.0 * frac_pos
    if mean_rise is not None:
        # ~0–12 dB typical; clamp
        score += min(15.0, max(0.0, mean_rise))
    mean_last = float(np.mean(last_ratios)) if last_ratios else 0.0
    score += max(0.0, 10.0 - max(0.0, mean_last - 0.35) * 30.0)
    med_gap = float(np.median(gaps)) if gaps else 0.0
    # small gaps ok; huge artificial gaps bad; zero gaps (hold model) fine
    if med_gap > 200:
        score -= min(10.0, (med_gap - 200) / 50.0)
    if pad_med is not None:
        # perfect recovery → 0 residual; >100ms residual hurts
        score += max(0.0, 15.0 - pad_med / 10.0)
    if mean_path is not None and math.isfinite(mean_path):
        # path scores are log-probs ~ -0.5 to -5; less negative better
        score += max(0.0, min(10.0, 5.0 + mean_path))

    return TimingQuality(
        method=method,
        n_ayahs=n,
        n_words=n_words,
        word_count_match_rate=count_rate,
        mono_ok_rate=mono_rate,
        med_abs_start_err=float(np.median(start_errs)) if start_errs else None,
        p90_abs_start_err=float(np.percentile(start_errs, 90)) if start_errs else None,
        mean_abs_start_err=float(np.mean(start_errs)) if start_errs else None,
        med_gap_ms=med_gap,
        mean_gap_ms=float(np.mean(gaps)) if gaps else 0.0,
        last_word_tail_ratio=mean_last,
        mean_boundary_rise_db=mean_rise,
        frac_positive_rise=frac_pos,
        mean_path_score=mean_path,
        pad_recovery_med_ms=pad_med,
        score=score,
    )
