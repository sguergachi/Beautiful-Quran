"""Align a long route of words against hours of audio, one overlapping window at a time.

A whole-surah CTC trellis is too big for the long chapters, and an
over-long text squeezed into a short window ruins its tail. So each window is
given only as much text as certainly fits (at a deliberately slow assumed
speaking rate), wrapped in "star" wildcards that absorb whatever audio the text
does not explain. The words nearest the window's end are the least reliable
(the next window re-aligns them with more context), so only the earlier ones
are committed.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .viterbi import forced_align, segments


@dataclass(frozen=True)
class AlignConfig:
    window_s: float = 240.0       # audio per window
    min_tokens_per_s: float = 2.5  # slowest speech the text budget allows for
    fill: float = 0.5             # text is sized to occupy this share of the window at that rate
    tail_words: int = 3           # uncommitted words at a window's end
    min_commit: int = 4           # every window must be able to commit at least this many words
    lead_s: float = 0.4           # next window starts this long before its first uncommitted word
    word_stars: bool = False      # a wildcard before every word: lets words slide to look-alike sounds far away, so off by default


@dataclass(frozen=True)
class Placement:
    """Where one word's tokens fall, in frames of the emission matrix (end exclusive)."""

    start: int
    end: int
    score: float  # mean log-prob of its token frames (0 = certain)


def place_words(emissions, blank, star, token_lists, t0, t1, word_stars=True, lead_star=True, trail_star=True):
    """Align `token_lists` to emissions[t0:t1]. Returns (placements, total path score)."""
    target, owner = [], []
    if lead_star:
        target.append(star)
        owner.append(None)
    for i, tokens in enumerate(token_lists):
        if not tokens:
            continue
        if word_stars and i and target and target[-1] != star:
            target.append(star)
            owner.append(None)
        for tok in tokens:
            target.append(tok)
            owner.append(i)
    if trail_star and target and target[-1] != star:
        target.append(star)
        owner.append(None)
    labels, scores = forced_align(emissions[t0:t1], target, blank)
    runs = segments(labels, scores, blank)
    if len(runs) != len(target):
        raise RuntimeError(f"path has {len(runs)} runs for {len(target)} tokens")
    spans = {}
    for (label, a, b, score), who in zip(runs, owner):
        if who is None:
            continue
        spans.setdefault(who, []).append((a, b, score))
    placed = []
    for i in range(len(token_lists)):
        runs_i = spans.get(i)
        if not runs_i:
            placed.append(None)
            continue
        placed.append(Placement(
            t0 + runs_i[0][0], t0 + runs_i[-1][1], float(np.mean([r[2] for r in runs_i]))
        ))
    return placed, float(scores.sum())


def align_route(emissions, blank, star, token_lists, stride_s, config=AlignConfig()):
    """Placements for every word of a route (None for a word with no tokens)."""
    n, frames = len(token_lists), len(emissions)
    window = int(config.window_s / stride_s)
    lead = int(config.lead_s / stride_s)
    per_frame = stride_s * config.min_tokens_per_s * config.fill
    result: list[Placement | None] = [None] * n
    w, t0 = 0, 0
    while w < n:
        t1 = min(frames, t0 + window)
        budget = max(1, int((t1 - t0) * per_frame))
        k, used = w, 0
        floor = w + config.tail_words + config.min_commit  # always enough text to commit something
        while k < n and (k < floor or used + len(token_lists[k]) <= budget):
            used += len(token_lists[k])
            k += 1
        while True:
            try:
                placed, _ = place_words(
                    emissions, blank, star, token_lists[w:k], t0, t1, config.word_stars
                )
                break
            except ValueError:
                if k - w <= 1:
                    raise
                k = w + (k - w + 1) // 2  # too little audio for the text: halve the text
        final = k >= n
        keep = (k - w) if final else max(1, (k - w) - config.tail_words)
        for i in range(keep):
            result[w + i] = placed[i]
        if final:
            break
        # Resume just after the last committed word. The uncommitted tail is where text runs out
        # and look-alike sounds further on can tempt a word away, so its position is not trusted.
        anchor = next((p for p in reversed(placed[:keep]) if p), None)
        w += keep
        t0 = max(t0 + 1, (anchor.end if anchor else t0) - lead)
    return result
