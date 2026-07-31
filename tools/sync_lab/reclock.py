"""
Re-clock: given a known position sequence (structure), produce start/end ms.

This is the clean production path for TIMING quality:
  structure  ←  qdc / Lab / (future structure engine)
  clock      ←  this module (CTC force-align on the fixed sequence)

No repeat-vs-split rules. No dephantom. Structure is an input.
"""
from __future__ import annotations

from typing import Sequence

import numpy as np

from structure_engine import force_align_positions, pause_chunks


def reclock_positions(
    y: np.ndarray,
    sr: int,
    positions: Sequence[int],
    words: Sequence[str],
    *,
    device: str | None = None,
) -> list[list[int]]:
    """
    Align the exact position sequence (backtracks allowed) to audio.

    Strategy: one global FA on the full sequence. If that fails length check,
    fall back to equal split by letter weight.
    """
    pos = [int(p) for p in positions]
    score, segs = force_align_positions(y, sr, pos, words, device=device)
    if segs is not None and len(segs) == len(pos):
        # karaoke hold
        for i in range(len(segs) - 1):
            segs[i][2] = max(segs[i][1] + 1, segs[i + 1][1])
        return segs

    # Fallback: duration proportional to letter count
    from aligners import letters_only

    dur = int(len(y) * 1000 / sr)
    weights = np.array(
        [max(1, len(letters_only(words[p - 1]))) for p in pos], dtype=np.float64
    )
    weights /= weights.sum()
    segs = []
    t = 0.0
    for i, p in enumerate(pos):
        nxt = t + weights[i] * dur
        segs.append([p, int(t), int(nxt) if i + 1 < len(pos) else dur])
        t = nxt
    return segs


def reclock_by_runs(
    y: np.ndarray,
    sr: int,
    positions: Sequence[int],
    words: Sequence[str],
    *,
    device: str | None = None,
) -> list[list[int]]:
    """
    Split the position sequence into monotonic runs (break at backtracks),
    force-align each run on a proportional audio slice, stitch.

    Often more stable than one giant FA when the ayah has re-says.
    """
    pos = [int(p) for p in positions]
    if not pos:
        return []

    # runs: lists of positions
    runs: list[list[int]] = [[pos[0]]]
    hw = pos[0]
    for p in pos[1:]:
        if p <= hw:
            runs.append([p])
            hw = p
        else:
            runs[-1].append(p)
            hw = max(hw, p)

    # audio split by letter-weight of each run
    from aligners import letters_only

    run_w = []
    for run in runs:
        run_w.append(
            sum(max(1, len(letters_only(words[p - 1]))) for p in run)
        )
    total_w = sum(run_w) or 1
    dur = len(y)
    segs: list[list[int]] = []
    sample_t = 0
    for run, w in zip(runs, run_w):
        span = int(round(dur * w / total_w))
        a0 = sample_t
        a1 = min(len(y), sample_t + max(span, int(0.05 * sr)))
        if run is runs[-1]:
            a1 = len(y)
        y_c = y[a0:a1]
        sc, local = force_align_positions(y_c, sr, run, words, device=device)
        base_ms = int(a0 * 1000 / sr)
        if local is not None:
            for p, s, e in local:
                segs.append([p, base_ms + s, base_ms + e])
        else:
            # equal inside run
            rd = int((a1 - a0) * 1000 / sr)
            step = max(1, rd // len(run))
            for i, p in enumerate(run):
                segs.append(
                    [
                        p,
                        base_ms + i * step,
                        base_ms + ((i + 1) * step if i + 1 < len(run) else rd),
                    ]
                )
        sample_t = a1

    for i in range(len(segs) - 1):
        segs[i][2] = max(segs[i][1] + 1, segs[i + 1][1])
    return segs
