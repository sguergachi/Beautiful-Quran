"""CTC forced alignment of a fixed token sequence against frame log-probabilities."""

from __future__ import annotations

import numpy as np

NEG = -1e30


def _numpy_align(log_probs: np.ndarray, targets: np.ndarray, blank: int):
    frames = log_probs.shape[0]
    length = len(targets)
    states = 2 * length + 1
    ext = np.full(states, blank, dtype=np.int64)
    ext[1::2] = targets
    skip_ok = np.zeros(states, dtype=bool)
    if states > 3:
        skip_ok[3::2] = ext[3::2] != ext[1:-2:2]
    prev = np.full(states, NEG, dtype=np.float64)
    prev[0] = log_probs[0, blank]
    if states > 1:
        prev[1] = log_probs[0, ext[1]]
    back = np.zeros((frames, states), dtype=np.int8)
    for t in range(1, frames):
        one = np.concatenate(([NEG], prev[:-1]))
        two = np.concatenate(([NEG, NEG], prev[:-2]))
        two = np.where(skip_ok, two, NEG)
        choices = np.stack([prev, one, two])
        pick = choices.argmax(axis=0)
        back[t] = pick
        prev = choices[pick, np.arange(states)] + log_probs[t, ext]
    state = states - 1 if states == 1 or prev[states - 1] >= prev[states - 2] else states - 2
    labels = np.empty(frames, dtype=np.int64)
    for t in range(frames - 1, -1, -1):
        labels[t] = ext[state]
        state -= int(back[t, state])
    scores = log_probs[np.arange(frames), labels]
    return labels, scores.astype(np.float32)


def forced_align(log_probs: np.ndarray, targets, blank: int):
    """Most likely CTC path for `targets`: (label per frame, log-prob per frame).

    Needs at least len(targets) + (adjacent equal targets) frames.
    """
    log_probs = np.ascontiguousarray(log_probs, dtype=np.float32)
    targets = np.asarray(targets, dtype=np.int64)
    repeats = int(np.sum(targets[1:] == targets[:-1])) if len(targets) > 1 else 0
    if len(targets) == 0 or log_probs.shape[0] < len(targets) + repeats:
        raise ValueError(f"{log_probs.shape[0]} frames cannot hold {len(targets)} tokens")
    try:
        from ctc_forced_aligner.ctc_forced_aligner import forced_align as native
    except ImportError:
        return _numpy_align(log_probs, targets, blank)
    labels, scores = native(log_probs[None], targets[None], blank)
    return np.asarray(labels).reshape(-1), np.asarray(scores).reshape(-1)


def segments(labels: np.ndarray, scores: np.ndarray, blank: int):
    """Non-blank runs of a path in order: [(label, first_frame, last_frame_exclusive, mean_score)]."""
    out = []
    t, n = 0, len(labels)
    while t < n:
        if labels[t] == blank:
            t += 1
            continue
        start = t
        while t < n and labels[t] == labels[start]:
            t += 1
        out.append((int(labels[start]), start, t, float(scores[start:t].mean())))
    return out
