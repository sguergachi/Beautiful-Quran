"""Audit the primary alignment with a second, independent acoustic model.

Each run of one verse's words (as the route spoke them) is re-aligned by the
check model alone, strictly and inside a small window around where the
primary model put it, and the word starts are compared. Two models trained on
different data agreeing on a start is evidence the start is right; a large
gap is listed for review and never silently averaged away.
"""

from __future__ import annotations

import numpy as np

from .windowed import place_words


def _unit(word):
    return word.kind if word.ayah == 0 else "verse"  # a broken-off fragment still belongs to its verse


def verse_runs(route):
    """Maximal runs of consecutive route words belonging to one ayah: [(first, last)] inclusive."""
    runs, start = [], 0
    for i in range(1, len(route) + 1):
        if i == len(route) or route[i].ayah != route[start].ayah or _unit(route[i]) != _unit(route[start]):
            runs.append((start, i - 1))
            start = i
    return runs


def run_starts(check, check_emissions, route, placements, stride_s, edges, margin_s=0.1, only=None):
    """Word starts (seconds) the check model gives, for every run (or just the runs in `only`).

    `edges[first_word]` is the run's acoustic (onset_s, offset_s). The check model sees only
    that sound plus a hair of margin, so a neighbouring verse's tail cannot lend it a word.
    Returns ({route_index: start_s}, [first words of runs it could not align]).
    """
    delim = check.word_delimiter
    stride_check = check.stride_s
    starts, failed = {}, []
    for first, last in verse_runs(route):
        if only is not None and first not in only:
            continue
        idx = [i for i in range(first, last + 1) if placements[i]]
        if not idx:
            continue
        onset, offset = edges[first]
        lo = max(0, int((onset - margin_s) / stride_check))
        hi = min(len(check_emissions), int((offset + margin_s) / stride_check) + 1)
        tokens = []
        for k, i in enumerate(idx):
            toks = check.tokens(route[i].text)
            if delim is not None and k < len(idx) - 1 and toks:
                toks = toks + [delim]
            tokens.append(toks)
        try:
            placed, _ = place_words(check_emissions, check.blank, check.star, tokens, lo, hi,
                                    word_stars=False, lead_star=False, trail_star=False)
        except (ValueError, RuntimeError):
            failed.append(first)
            continue
        for i, cp in zip(idx, placed):
            if cp is not None:
                starts[i] = cp.start * stride_check
    return starts, failed


def compare(check, check_emissions, route, placements, stride_s, edges, tolerance_s=0.2):
    """Per-word start differences (check - primary) in seconds, plus the words beyond tolerance."""
    starts, failed = run_starts(check, check_emissions, route, placements, stride_s, edges)
    diffs, flagged = [], [(first, None) for first in failed]
    for i, t in starts.items():
        d = t - placements[i].start * stride_s
        diffs.append(d)
        if abs(d) > tolerance_s:
            flagged.append((i, round(d, 3)))
    return np.array(diffs), flagged


def arbitrate(primary_s, check_s, third_s, agree_s=0.2):
    """Which start two of three models support: ("primary"|"check", seconds) or None if all three differ."""
    if abs(third_s - primary_s) <= agree_s:
        return "primary", primary_s
    if abs(third_s - check_s) <= agree_s:
        return "check", (check_s + third_s) / 2
    return None


def summary(diffs):
    if not len(diffs):
        return {}
    a = np.abs(diffs)
    return {
        "words": int(len(a)),
        "medianMs": round(float(np.median(a)) * 1000),
        "p90Ms": round(float(np.percentile(a, 90)) * 1000),
        "p99Ms": round(float(np.percentile(a, 99)) * 1000),
        "over200Ms": int((a > 0.2).sum()),
        "over500Ms": int((a > 0.5).sum()),
        "meanSignedMs": round(float(diffs.mean()) * 1000),
    }
