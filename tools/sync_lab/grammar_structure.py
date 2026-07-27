"""
Canonical repeat-grammar structure decoder (Codex Experiment 2).

One CTC forward pass → free decode WITH character start times.
Candidates for position sequence:
  - mono 1..N
  - mono + one or more contiguous re-say episodes from unique span evidence
  - optional external prior (e.g. QDC / gold) as a *candidate*, never unconditional

Score = character edit similarity of free decode vs concat(normalized words along
the candidate sequence). Accept non-mono only with score margin.

This deliberately does NOT use forced-align path score for structure.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Sequence

import numpy as np
import torch

from aligners import get_ctc_model, letters_only
from structure_engine import MODEL_ID, force_align_positions


def norm_letters(s: str) -> str:
    return re.sub(r"[^ء-ي]", "", letters_only(s))


@dataclass
class TimedChar:
    ch: str
    start_ms: int
    end_ms: int


@dataclass
class DecodeResult:
    text: str
    chars: list[TimedChar]
    path_mean: float  # mean logprob of greedy path (diagnostic)


@dataclass
class StructureCandidate:
    positions: list[int]
    source: str
    score: float
    decode_sim: float
    episodes: list[tuple[int, int]] = field(default_factory=list)


@torch.inference_mode()
def timed_free_decode(
    y: np.ndarray,
    sr: int,
    *,
    device: str | None = None,
) -> DecodeResult:
    """Greedy CTC decode with per-character frame intervals (ms)."""
    cm = get_ctc_model(MODEL_ID, device=device)
    inv = {v: k for k, v in cm.vocab.items()}
    blank = cm.blank_id
    inputs = cm.processor(y, sampling_rate=sr, return_tensors="pt", padding=True)
    logits = cm.model(inputs.input_values.to(cm.device)).logits[0]
    log_probs = torch.log_softmax(logits.float(), dim=-1)
    ids = logits.argmax(-1).tolist()
    T = len(ids)
    ms_per = (1000.0 * len(y) / sr) / max(T, 1)

    # collapse repeats; track frame range per emitted char
    chars: list[TimedChar] = []
    t = 0
    path_scores = []
    while t < T:
        i = ids[t]
        path_scores.append(float(log_probs[t, i]))
        if i == blank:
            t += 1
            continue
        # extend run of same non-blank
        t0 = t
        while t < T and ids[t] == i:
            path_scores.append(float(log_probs[t, i]))
            t += 1
        ch = inv.get(i, "")
        if not ch or ch in ("|", " ", "[PAD]", "[UNK]"):
            continue
        letters = norm_letters(ch)
        if not letters:
            # keep single unknown-ish chars if arabic letter already filtered
            continue
        start_ms = int(t0 * ms_per)
        end_ms = int(t * ms_per)
        for c in letters:
            chars.append(TimedChar(c, start_ms, max(end_ms, start_ms + 1)))

    text = "".join(c.ch for c in chars)
    mean_lp = float(np.mean(path_scores)) if path_scores else float("-inf")
    return DecodeResult(text=text, chars=chars, path_mean=mean_lp)


def edit_similarity(a: str, b: str) -> float:
    """Normalized similarity in [0,1]: 1 - levenshtein/max(len)."""
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    # classic DP
    n, m = len(a), len(b)
    if n * m > 2_000_000:
        # too large — fall back to ratio of common prefix/suffix + length
        return max(0.0, 1.0 - abs(n - m) / max(n, m))
    prev = list(range(m + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            ins = cur[j - 1] + 1
            dele = prev[j] + 1
            sub = prev[j - 1] + (0 if ca == cb else 1)
            cur.append(min(ins, dele, sub))
        prev = cur
    dist = prev[m]
    return 1.0 - dist / max(n, m)


def sequence_string(positions: Sequence[int], words: Sequence[str]) -> str:
    return "".join(norm_letters(words[p - 1]) for p in positions)


def mono_positions(n: int) -> list[int]:
    return list(range(1, n + 1))


def insert_episode(
    base: Sequence[int], after_pos: int, start: int, end: int
) -> list[int] | None:
    """
    Insert re-say of [start..end] immediately after the first time `after_pos`
    appears in base (typically after_pos == end for first-pass completion).
    """
    if start < 1 or end < start:
        return None
    out: list[int] = []
    inserted = False
    for p in base:
        out.append(p)
        if not inserted and p == after_pos:
            out.extend(range(start, end + 1))
            inserted = True
    return out if inserted else None


def find_span_evidence(
    words: Sequence[str],
    decode: str,
    *,
    max_L: int = 12,
) -> list[tuple[int, int, float, int]]:
    """
    Return (start_pos, end_pos, evidence_score, decode_count) for spans that
    appear more times in decode than in the written ayah (or unique + 2× decode).
    """
    norms = [norm_letters(w) for w in words]
    n = len(words)
    full = "".join(norms)
    hits: list[tuple[int, int, float, int]] = []

    for L in range(1, min(max_L, n) + 1):
        for i in range(n - L + 1):
            span = "".join(norms[i : i + L])
            if len(span) < (3 if L > 1 else 4):
                continue
            if L == 1 and len(span) <= 3:
                continue
            text_cnt = full.count(span)
            dec_cnt = decode.count(span)
            if dec_cnt < 2 and L == 1 and len(span) >= 5:
                dec_cnt = max(dec_cnt, decode.count(span[:-1]))
            if dec_cnt < 2:
                # fuzzy: allow edit-near match for long spans — sample windows
                if L >= 2 and len(span) >= 6:
                    # sliding windows of len(span) in decode
                    wlen = len(span)
                    near = 0
                    for k in range(0, max(0, len(decode) - wlen + 1), max(1, wlen // 3)):
                        window = decode[k : k + wlen]
                        if edit_similarity(span, window) >= 0.82:
                            near += 1
                    dec_cnt = max(dec_cnt, near)
            if dec_cnt < 2:
                continue
            if text_cnt >= 2 and dec_cnt <= text_cnt:
                continue
            # evidence: extra decode mass + prefer longer unique spans
            extra = dec_cnt - max(text_cnt, 1)
            score = L * 3.0 + len(span) * 0.12 + extra * 1.5
            if text_cnt == 1:
                score += 2.0
            hits.append((i + 1, i + L, score, dec_cnt))

    hits.sort(key=lambda h: -h[2])
    return hits


def build_candidates(
    words: Sequence[str],
    decode: str,
    *,
    priors: list[tuple[str, list[int]]] | None = None,
    max_episodes: int = 3,
    top_spans: int = 12,
) -> list[list[int]]:
    """Generate grammar-valid position sequences to score."""
    n = len(words)
    mono = mono_positions(n)
    cands: list[list[int]] = [mono]
    seen = {tuple(mono)}

    def add(seq: list[int] | None) -> None:
        if not seq:
            return
        key = tuple(seq)
        if key in seen:
            return
        # grammar check: first-pass covers 1..N in order
        if not is_grammar_valid(seq, n):
            return
        seen.add(key)
        cands.append(seq)

    spans = find_span_evidence(words, decode)[:top_spans]
    # single episodes
    for start, end, _sc, _cnt in spans:
        add(insert_episode(mono, end, start, end))

    # multi-episode: combinations of top few non-overlapping in first-pass sense
    top = spans[:6]
    for i in range(len(top)):
        s1 = insert_episode(mono, top[i][1], top[i][0], top[i][1])
        if not s1:
            continue
        for j in range(i + 1, len(top)):
            # insert second episode into already-augmented seq
            # after first occurrence of end2 in s1
            s2 = insert_episode(s1, top[j][1], top[j][0], top[j][1])
            add(s2)
            if max_episodes >= 3:
                for k in range(j + 1, min(len(top), j + 3)):
                    s3 = insert_episode(s2, top[k][1], top[k][0], top[k][1]) if s2 else None
                    add(s3)

    if priors:
        for _name, seq in priors:
            add(list(seq))

    return cands


def is_grammar_valid(positions: Sequence[int], n_words: int) -> bool:
    """
    Base path covers 1..N in order exactly once as first occurrences;
    extras are only re-emissions of already-covered positions.
    """
    if not positions:
        return False
    seen_first: set[int] = set()
    expect_next = 1
    for p in positions:
        if p < 1 or p > n_words:
            return False
        if p not in seen_first:
            if p != expect_next:
                return False
            seen_first.add(p)
            expect_next = p + 1
        # else: re-say of already covered — ok if p <= max first so far
        elif p > max(seen_first):
            return False
    return expect_next == n_words + 1 and len(seen_first) == n_words


def score_candidate(
    positions: Sequence[int],
    words: Sequence[str],
    decode: str,
    *,
    source: str,
    repeat_prior: float = 0.0,
) -> StructureCandidate:
    """Score = decode edit similarity. Optional tiny length prior (default off).

    Selection margin vs mono is applied in select_structure — that is the
    false-positive gate, not a per-extra-word penalty (which rejected real
    multi-word re-says when decode was noisy).
    """
    target = sequence_string(positions, words)
    sim = edit_similarity(decode, target)
    n_extra = len(positions) - len(words)
    score = sim - repeat_prior * max(0, n_extra)
    episodes = []
    hw = 0
    ep_start = None
    for p in positions:
        if p <= hw:
            if ep_start is None:
                ep_start = p
        else:
            if ep_start is not None:
                episodes.append((ep_start, hw))
                ep_start = None
            hw = max(hw, p)
    if ep_start is not None:
        episodes.append((ep_start, hw))
    return StructureCandidate(
        positions=list(positions),
        source=source,
        score=score,
        decode_sim=sim,
        episodes=episodes,
    )


def select_structure(
    y: np.ndarray,
    sr: int,
    words: Sequence[str],
    *,
    priors: list[tuple[str, list[int]]] | None = None,
    margin: float = 0.003,
    device: str | None = None,
) -> tuple[StructureCandidate, DecodeResult, list[StructureCandidate]]:
    dec = timed_free_decode(y, sr, device=device)
    winner, scored = select_structure_from_decode(
        dec, words, priors=priors, margin=margin
    )
    return winner, dec, scored


def select_structure_from_decode(
    decode: DecodeResult,
    words: Sequence[str],
    *,
    priors: list[tuple[str, list[int]]] | None = None,
    margin: float = 0.003,
) -> tuple[StructureCandidate, list[StructureCandidate]]:
    """Score structure candidates without repeating the expensive CTC pass."""
    raw_cands = build_candidates(words, decode.text, priors=priors)
    mono_seq = mono_positions(len(words))
    scored: list[StructureCandidate] = []
    for seq in raw_cands:
        if list(seq) == mono_seq:
            src = "mono"
        else:
            src = "grammar"
            if priors:
                for name, pseq in priors:
                    if list(seq) == list(pseq):
                        src = f"prior:{name}"
                        break
        scored.append(score_candidate(seq, words, decode.text, source=src))

    scored.sort(key=lambda c: -c.score)
    mono = next((c for c in scored if c.source == "mono"), None)
    if mono is None:
        mono = score_candidate(mono_seq, words, decode.text, source="mono")
        scored.append(mono)
        scored.sort(key=lambda c: -c.score)
    best = scored[0]
    # Non-mono must beat mono by margin (abstention → mono)
    if best.source != "mono" and best.score < mono.score + margin:
        best = mono
    return best, scored


def reclock_anchored(
    y: np.ndarray,
    sr: int,
    positions: Sequence[int],
    words: Sequence[str],
    decode: DecodeResult,
    *,
    device: str | None = None,
) -> list[list[int]]:
    """
    Clock fixed positions; if we can find decode-char ranges for episode
    boundaries, force-align within coarse global windows.
    Default: one global FA of the full sequence (no proportional run slice).
    """
    sc, segs = force_align_positions(y, sr, positions, words, device=device)
    if segs is not None and len(segs) == len(positions):
        for i in range(len(segs) - 1):
            segs[i][2] = max(segs[i][1] + 1, segs[i + 1][1])
        # reject padded-looking zero-duration
        if any(s[2] <= s[1] for s in segs):
            return segs
        return segs
    # fail closed: equal split only as last resort with warning encoded as segs
    dur = int(len(y) * 1000 / sr)
    n = len(positions)
    step = max(1, dur // max(n, 1))
    return [
        [p, i * step, (i + 1) * step if i + 1 < n else dur]
        for i, p in enumerate(positions)
    ]
