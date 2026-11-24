"""Decide what the reciter actually said: spoken openings and re-recited phrases.

Forced alignment needs the exact word sequence. A recording may add speech the
canonical text lacks: an istiadha / basmala at the start or at a section break
inside a long chapter, or a phrase said twice. Such speech shows up as audio
between two aligned words that the aligned words do not explain (a run of
confident non-blank frames). For every such gap this scores competing
hypotheses against the same audio, by CTC likelihood, and keeps the route
that explains it best:

    nothing extra | an opening (istiadha, basmala, or both) | the previous N words again

A hypothesis is accepted only when it beats "nothing extra" by a clear margin,
so noise, coughs and breaths leave the route alone.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace

import numpy as np

from .text import OPENINGS, Word, letters, opening_words
from .windowed import AlignConfig, align_route, place_words

OPENING_CHOICES = [("istiadha",), ("basmala",), ("istiadha", "basmala")]


@dataclass(frozen=True)
class RouteConfig:
    min_runs: int = 3          # confident non-blank runs in a gap before it is investigated
    word_end_margin_s: float = 0.3   # a word's own tail is not "extra" speech
    word_start_margin_s: float = 0.1
    min_prob: float = 0.5      # a frame counts as speech when its best token is this likely
    max_back: int = 80         # farthest a repeated verse may reach back, in words
    max_phrase: int = 8        # farthest a repeat that does not start at a verse's first word may reach
    context_before: int = 4    # aligned words kept on each side of the gap when scoring
    context_after: int = 6
    margin_s: float = 0.15     # audio kept beyond the anchor words (no wildcards: they would hide the contested speech)
    min_gain: float = 12.0     # log-likelihood a hypothesis must add over "nothing extra"
    max_rounds: int = 4
    bad_score: float = -2.5    # a word this poorly matched is probably squeezed into the wrong audio
    bad_cluster: int = 3       # this many bad words in a row mark a derailment
    good_score: float = -1.5   # anchors on either side of a suspect region must score at least this
    merge_gap: int = 3         # suspects this close (in words) belong to one region
    max_points: int = 30       # insertion points tried per region
    inner_excess: int = 4      # a word holding this many more speech runs than tokens hides extra speech


@dataclass
class Change:
    after: int            # route index the words were inserted after (-1 = before the first word)
    kind: str             # "opening" or "repeat"
    detail: str
    gain: float
    seconds: float        # where in the audio


@dataclass
class RouteReport:
    changes: list[Change] = field(default_factory=list)
    unresolved: list[tuple[int, float, int]] = field(default_factory=list)  # (after, seconds, runs)


def speech_runs(emissions, blank, lo, hi, min_prob):
    """Distinct confident non-blank tokens in frames [lo, hi): a count of unexplained speech."""
    if hi <= lo:
        return 0
    block = emissions[lo:hi, :-1]
    best = block.argmax(axis=1)
    sure = np.exp(block.max(axis=1)) >= min_prob
    count, prev = 0, -1
    for tok, ok in zip(best, sure):
        if ok and tok != blank:
            if tok != prev:
                count += 1
            prev = tok
        else:
            prev = -1
    return count


def gap_runs(emissions, blank, placements, stride_s, cfg: RouteConfig):
    """Unexplained-speech count before the first word, between each pair, and after the last."""
    placed = [(i, p) for i, p in enumerate(placements) if p]
    end_m = int(cfg.word_end_margin_s / stride_s)
    start_m = int(cfg.word_start_margin_s / stride_s)
    out = {}
    first_i, first = placed[0]
    out[-1] = speech_runs(emissions, blank, 0, first.start - start_m, cfg.min_prob)
    for (i, a), (_, b) in zip(placed, placed[1:]):
        out[i] = speech_runs(emissions, blank, a.end + end_m, b.start - start_m, cfg.min_prob)
    last_i, last = placed[-1]
    out[last_i] = speech_runs(emissions, blank, last.end + end_m, len(emissions), cfg.min_prob)
    return out


def inner_excess(emissions, blank, placements, tokens, cfg: RouteConfig):
    """Words whose own span holds clearly more speech than their text: a phrase said inside them."""
    out = set()
    for i, p in enumerate(placements):
        if p and speech_runs(emissions, blank, p.start, p.end, cfg.min_prob) - len(tokens[i]) >= cfg.inner_excess:
            out.add(i)
    return out


def _regions(placements, runs, cfg: RouteConfig, extra=frozenset()):
    """Suspect regions as (first, last): word indices, where a gap after word g is index g."""
    n = len(placements)
    marks = {g for g, count in runs.items() if count >= cfg.min_runs} | set(extra)
    streak = []
    for i in range(n + 1):
        bad = i < n and placements[i] is not None and placements[i].score < cfg.bad_score
        if bad:
            streak.append(i)
            continue
        if len(streak) >= cfg.bad_cluster:
            marks.update(streak)
        streak = []
    regions = []
    for m in sorted(marks):
        if regions and m - regions[-1][1] <= cfg.merge_gap:
            regions[-1][1] = m
        else:
            regions.append([m, m])
    return [tuple(r) for r in regions]


def _good(placements, i, cfg: RouteConfig):
    return placements[i] is not None and placements[i].score >= cfg.good_score


def _hypotheses(route, after, cfg: RouteConfig, any_back: bool):
    """name -> words inserted after route[after]."""
    options = {}
    for kinds in OPENING_CHOICES:
        options["opening:" + "+".join(kinds)] = opening_words(kinds)
    for back in range(1, min(cfg.max_back, after + 1) + 1):
        words = route[after - back + 1:after + 1]
        if any(w.ayah == 0 for w in words):
            continue
        # Away from the end of the region only whole-verse repeats are plausible.
        if (any_back and back <= cfg.max_phrase) or words[0].pos == 1:
            again = [replace(w, inserted=True) for w in words]
            options[f"repeat:{back}"] = again
            # A restart often breaks off mid-word: the phrase again, then the start of the next word.
            following = route[after + 1] if after + 1 < len(route) else None
            if back <= cfg.max_phrase and following is not None and following.ayah and len(letters(following.text)) > 2:
                for keep in (1, 2, 3):
                    if keep < len(letters(following.text)):
                        options[f"repeat:{back}+{keep}"] = again + [
                            replace(following, text=letters(following.text)[:keep], kind="fragment", inserted=True)]
    return options


def _score(emissions, backend, placements, route, after, inserted, window, stride_s, cfg: RouteConfig):
    """Total CTC score of route[first:after+1] + inserted + route[after+1:last] over a fixed audio window."""
    first, last, lo, hi = window
    words = route[first:after + 1] + inserted + route[after + 1:last]
    tokens = [backend.tokens(w.text) for w in words]
    try:
        _, total = place_words(emissions, backend.blank, backend.star, tokens, lo, hi,
                               word_stars=False, lead_star=False, trail_star=False)
    except (ValueError, RuntimeError):
        return None
    return total


def _window(emissions, placements, region, cfg: RouteConfig, stride_s):
    """Words and frames to score a region against: good anchors on both sides."""
    n = len(placements)
    a, b = region
    first = max(0, a - cfg.context_before)
    while first > 0 and not _good(placements, first, cfg):
        first -= 1
    right = min(n, b + 1)
    while right < n and not _good(placements, right, cfg):
        right += 1
    last = min(n, right + cfg.context_after)
    margin = int(cfg.margin_s / stride_s)
    anchors = [i for i in range(first, last) if placements[i]]
    lo = 0 if first == 0 else max(0, placements[anchors[0]].start - margin)
    hi = len(emissions) if last >= n else min(len(emissions), placements[anchors[-1]].end + margin)
    return first, last, lo, hi


def resolve_route(emissions, backend, route: list[Word], stride_s, align_cfg=AlignConfig(),
                  cfg=RouteConfig(), log=lambda *_: None):
    """Align `route`, insert whatever extra speech the audio holds, and re-align.

    Returns (route, placements, RouteReport).
    """
    report = RouteReport()
    for round_no in range(cfg.max_rounds):
        tokens = [backend.tokens(w.text) for w in route]
        placements = align_route(emissions, backend.blank, backend.star, tokens, stride_s, align_cfg)
        runs = gap_runs(emissions, backend.blank, placements, stride_s, cfg)
        report.unresolved = []
        inserts = []
        extra = inner_excess(emissions, backend.blank, placements, tokens, cfg)
        for region in _regions(placements, runs, cfg, extra):
            a, b = region
            window = _window(emissions, placements, region, cfg, stride_s)
            anchor = placements[min(max(b, 0), len(route) - 1)]
            seconds = (anchor.start if anchor else 0) * stride_s
            # A repeat can be explained either way round (which of two identical passes the aligner
            # left unexplained is arbitrary), so any word inside the scored window may precede it.
            points = list(range(max(window[0], 0) if a > 0 else -1, min(window[1], len(route))))
            if len(points) > cfg.max_points:
                points = sorted(points, key=lambda j: abs(j - (a + b) / 2))[:cfg.max_points]
            best = (cfg.min_gain, None, None, None)
            for after in points:
                base = _score(emissions, backend, placements, route, after, [], window, stride_s, cfg)
                if base is None:
                    continue
                for name, words in _hypotheses(route, after, cfg, any_back=after >= b).items():
                    score = _score(emissions, backend, placements, route, after, words, window, stride_s, cfg)
                    if score is not None and score - base > best[0]:
                        best = (score - base, name, words, after)
            gain, name, words, after = best
            if name:
                inserts.append((after, words))
                kind, _, detail = name.partition(":")
                report.changes.append(Change(after, kind, detail, round(gain, 1), round(seconds, 1)))
                log(f"  round {round_no}: +{name} after word {after} (region {a}..{b}, {seconds:.1f}s, gain {gain:.0f})")
            else:
                report.unresolved.append((a, round(seconds, 1), max(runs.get(g, 0) for g in range(a, b + 1))))
        if not inserts:
            break
        for after, words in sorted(inserts, key=lambda x: -x[0]):
            route = route[:after + 1] + list(words) + route[after + 1:]
    tokens = [backend.tokens(w.text) for w in route]
    placements = align_route(emissions, backend.blank, backend.star, tokens, stride_s, align_cfg)
    return route, placements, report


def inserted_blocks(route):
    """Maximal runs of words the audio added: [(first, last)] route indices, inclusive."""
    blocks, start = [], None
    for i, word in enumerate(route + [None]):
        if word is not None and word.inserted:
            start = i if start is None else start
        elif start is not None:
            blocks.append((start, i - 1))
            start = None
    return blocks


def block_gain(emissions, backend, route, placements, block, stride_s, cfg: RouteConfig = RouteConfig()):
    """How much better `backend` explains the audio with `block` than without it (log-likelihood).

    Scored over the block and a few words either side, in the audio the primary alignment gave them.
    """
    first, last = block
    lo_word = max(0, first - cfg.context_before)
    hi_word = min(len(route), last + 1 + cfg.context_after)
    placed = [p for p in placements[lo_word:hi_word] if p]
    if not placed:
        return None
    margin = int(cfg.margin_s / stride_s)
    scale = stride_s / backend.stride_s
    lo = max(0, int((placed[0].start - margin) * scale))
    hi = min(len(emissions), int((placed[-1].end + margin) * scale))
    with_block = route[lo_word:hi_word]
    without = route[lo_word:first] + route[last + 1:hi_word]
    scores = []
    for words in (with_block, without):
        try:
            _, total = place_words(emissions, backend.blank, backend.star,
                                   [backend.tokens(w.text) for w in words], lo, hi,
                                   word_stars=False, lead_star=False, trail_star=False)
        except (ValueError, RuntimeError):
            return None
        scores.append(total)
    return scores[0] - scores[1]
