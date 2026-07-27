#!/usr/bin/env python3
"""Automated acoustic confidence for Timing V2 — no human labels.

A row is *machine-trusted* when its word starts are consistent with independent
waveform energy onsets. That is the non-circular automated bar:

  post-pause energy rises and local energy onsets are not the aligner itself.

Use these scores to abstain rather than ship silent desync.
"""
from __future__ import annotations

import math
from typing import Sequence

import numpy as np

from metrics import energy_onsets


def frame_rms(y: np.ndarray, sr: int, frame_ms: float = 20.0) -> tuple[np.ndarray, int]:
    frame = max(1, int(sr * frame_ms / 1000))
    hop = max(1, int(sr * frame_ms / 2000))  # 50% hop
    n = 1 + max(0, (len(y) - frame) // hop)
    if n <= 0:
        return np.array([], dtype=np.float64), hop
    rms = np.empty(n, dtype=np.float64)
    for i in range(n):
        sl = y[i * hop : i * hop + frame]
        rms[i] = math.sqrt(float(np.mean(sl * sl)) + 1e-12)
    return rms, hop


def dead_zone(y: np.ndarray, sr: int, start_ms: int, quiet_ratio: float = 0.1) -> bool:
    """True if start sits in silence with no energy rise within ~60ms."""
    frame = max(1, int(sr * 0.02))
    idx = int(start_ms * sr / 1000)
    if idx < 0 or idx >= len(y):
        return True
    ahead = y[idx : idx + int(0.2 * sr)]
    local = y[idx : idx + 3 * frame]
    if ahead.size < frame or local.size < frame:
        return False
    peak = float(np.sqrt(np.mean(ahead * ahead) + 1e-12))
    now = float(np.sqrt(np.mean(local * local) + 1e-12))
    # No speech in the next 200ms either → still a dead placement.
    if peak <= 1e-4:
        return True
    return now < peak * quiet_ratio


def nearest_onset_errors(
    starts_ms: Sequence[int],
    y: np.ndarray,
    sr: int,
    window_ms: float = 80.0,
) -> list[float | None]:
    """Distance from each start to nearest energy onset within window (None if none)."""
    onsets = energy_onsets(y, sr)
    if onsets.size == 0:
        return [None] * len(starts_ms)
    out: list[float | None] = []
    for start in starts_ms:
        cand = onsets[(onsets >= start - window_ms) & (onsets <= start + window_ms)]
        if cand.size == 0:
            out.append(None)
        else:
            out.append(float(cand[np.argmin(np.abs(cand - start))] - start))
    return out


def row_confidence(
    starts_ms: Sequence[int],
    y: np.ndarray,
    sr: int,
    *,
    onset_window_ms: float = 80.0,
    match_ms: float = 40.0,
) -> dict:
    """Compute automated confidence stats for one mono row."""
    if not starts_ms:
        return {
            "ok": False,
            "deadZones": 0,
            "onsetMatchFrac": 0.0,
            "medianAbsOnsetErrMs": None,
            "maxAbsOnsetErrMs": None,
            "matched": 0,
            "n": 0,
        }
    dead = sum(1 for s in starts_ms if dead_zone(y, sr, int(s)))
    errs = nearest_onset_errors(starts_ms, y, sr, window_ms=onset_window_ms)
    matched = [abs(e) for e in errs if e is not None]
    within = [e for e in matched if e <= match_ms]
    return {
        "ok": dead == 0 and len(starts_ms) > 0,
        "deadZones": dead,
        "onsetMatchFrac": (len(within) / len(starts_ms)) if starts_ms else 0.0,
        "onsetHitFrac": (len(matched) / len(starts_ms)) if starts_ms else 0.0,
        "medianAbsOnsetErrMs": float(np.median(matched)) if matched else None,
        "maxAbsOnsetErrMs": float(max(matched)) if matched else None,
        "matched": len(matched),
        "n": len(starts_ms),
    }


def accept_row(
    conf: dict,
    *,
    min_onset_match_frac: float = 0.85,
    allow_unmatched: bool = False,
) -> bool:
    """Gate: no dead-zone starts; enough starts near independent energy onsets."""
    if conf.get("deadZones", 1) > 0:
        return False
    if conf.get("n", 0) <= 0:
        return False
    if conf["onsetMatchFrac"] < min_onset_match_frac:
        return False
    if not allow_unmatched and conf["onsetHitFrac"] < min_onset_match_frac:
        return False
    return True
