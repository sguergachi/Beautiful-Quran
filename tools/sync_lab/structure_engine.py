"""
Time-anchored pause multi-hypothesis structure aligner + long-word doubles.

Rules (intentionally few — replace the timing_repairs rule pile):
  1. Monotonic CTC FA gives a full 1..N clock (always complete).
  2. Pauses split the timeline; each chunk only claims mono words whose
     midpoint falls inside it (time-anchored — no over-claim).
  3. For chunks after the first, score a small template set:
       pure forward | single-word double | short span double | backtrack
     Accept non-forward only with path-score margin.
  4. Abnormally long mono words: try [w,w] on that slice (seamless singles).

Build-time only.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

import numpy as np
import torch

MODEL_ID = "jonatasgrosman/wav2vec2-large-xlsr-53-arabic"


@dataclass
class Chunk:
    start_ms: int
    end_ms: int
    start_sample: int
    end_sample: int

    @property
    def dur_ms(self) -> int:
        return max(0, self.end_ms - self.start_ms)


@dataclass
class SpanChoice:
    kind: str
    template: tuple[int, ...]
    score: float
    path_score: float


@dataclass
class StructureResult:
    positions: list[int]
    segments: list[list[int]]
    chunks: list[Chunk] = field(default_factory=list)
    choices: list[SpanChoice] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    mode: str = ""


def pause_chunks(
    y: np.ndarray,
    sr: int,
    *,
    min_pause_ms: float = 200.0,
    min_speech_ms: float = 80.0,
    thr_ratio: float = 0.07,
    frame_ms: float = 20.0,
    hop_ms: float = 10.0,
) -> list[Chunk]:
    if y.size == 0:
        return []
    frame = max(1, int(sr * frame_ms / 1000))
    hop = max(1, int(sr * hop_ms / 1000))
    n = 1 + max(0, (len(y) - frame) // hop)
    rms = np.empty(n, dtype=np.float64)
    for i in range(n):
        sl = y[i * hop : i * hop + frame]
        rms[i] = float(np.sqrt(np.mean(sl * sl) + 1e-12))
    if n >= 5:
        rms = np.convolve(rms, np.ones(5) / 5.0, mode="same")
    thr = float(np.max(rms) + 1e-12) * thr_ratio
    speech = rms > thr
    min_speech_f = max(1, int(min_speech_ms / hop_ms))
    min_pause_f = max(1, int(min_pause_ms / hop_ms))
    chunks: list[Chunk] = []
    i = 0
    while i < n:
        while i < n and not speech[i]:
            i += 1
        if i >= n:
            break
        s = i
        while i < n and speech[i]:
            i += 1
        while i < n:
            j = i
            while j < n and not speech[j]:
                j += 1
            if (j - i) < min_pause_f and j < n and speech[j]:
                i = j
                while i < n and speech[i]:
                    i += 1
            else:
                break
        e = i
        if e - s < min_speech_f:
            continue
        ss, es = s * hop, min(len(y), e * hop + frame)
        chunks.append(Chunk(int(ss * 1000 / sr), int(es * 1000 / sr), ss, es))
    if not chunks:
        chunks.append(Chunk(0, int(len(y) * 1000 / sr), 0, len(y)))
    return chunks


@torch.inference_mode()
def force_align_slice(
    y_chunk: np.ndarray,
    sr: int,
    words: Sequence[str],
    *,
    device: str | None = None,
) -> tuple[float, list[list[int]] | None]:
    from aligners import (
        get_ctc_model,
        _map_chars_to_ids,
        _ctc_token_spans,
        _merge_char_spans_to_words,
        letters_only,
    )

    if y_chunk.size < int(0.04 * sr) or not words:
        return float("-inf"), None
    cm = get_ctc_model(MODEL_ID, device=device)
    mapped = []
    for w in words:
        nw = letters_only(w).replace(" ", "") or "ا"
        ids = _map_chars_to_ids(nw, cm.vocab)
        mapped.append(ids if ids else (_map_chars_to_ids("ا", cm.vocab) or [1]))
    flat = [i for m in mapped for i in m]
    if not flat:
        return float("-inf"), None
    inputs = cm.processor(y_chunk, sampling_rate=sr, return_tensors="pt", padding=True)
    logits = cm.model(inputs.input_values.to(cm.device)).logits[0]
    log_probs = torch.log_softmax(logits.float(), dim=-1)
    T = log_probs.size(0)
    if T < max(1, len(flat) // 2):
        return float("-inf"), None
    spans, path_score = _ctc_token_spans(log_probs, flat, cm.blank_id)
    if len(spans) != len(flat):
        return float("-inf"), None
    ms_per = (1000.0 * len(y_chunk) / sr) / max(T, 1)
    segs = _merge_char_spans_to_words(spans, [len(m) for m in mapped], ms_per)
    dur = int(1000 * len(y_chunk) / sr)
    for s in segs:
        s[1] = max(0, min(int(s[1]), dur))
        s[2] = max(s[1] + 1, min(int(s[2]), dur))
    if segs:
        segs[-1][2] = max(segs[-1][2], segs[-1][1] + 1)
    return float(path_score), segs


def force_align_positions(
    y_chunk: np.ndarray,
    sr: int,
    pos_seq: Sequence[int],
    words: Sequence[str],
    *,
    device: str | None = None,
) -> tuple[float, list[list[int]] | None]:
    span_words = [words[p - 1] for p in pos_seq]
    score, segs = force_align_slice(y_chunk, sr, span_words, device=device)
    if segs is None or len(segs) != len(pos_seq):
        return score, None
    return score, [[pos, s, e] for (p, s, e), pos in zip(segs, pos_seq)]


def mono_align(
    y: np.ndarray, sr: int, words: Sequence[str], device: str | None = None
) -> tuple[list[list[int]], float]:
    score, segs = force_align_slice(y, sr, words, device=device)
    if segs is None or len(segs) != len(words):
        dur = int(len(y) * 1000 / sr)
        n = max(1, len(words))
        step = max(1, dur // n)
        segs = [
            [i + 1, i * step, (i + 1) * step if i + 1 < n else dur]
            for i in range(len(words))
        ]
        return segs, float("-inf")
    return [[i + 1, s[1], s[2]] for i, s in enumerate(segs)], score


def expand_long_words(
    y: np.ndarray,
    sr: int,
    mono: list[list[int]],
    words: Sequence[str],
    *,
    min_ms: float = 900.0,
    ratio: float = 1.5,
    margin: float = 0.08,
    device: str | None = None,
) -> list[list[int]]:
    durs = [s[2] - s[1] for s in mono]
    if not durs:
        return [list(s) for s in mono]
    med = float(np.median(durs))
    thr = max(min_ms, ratio * med)
    out: list[list[int]] = []
    for pos, s, e in mono:
        if e - s < thr:
            out.append([pos, s, e])
            continue
        a0, a1 = int(s * sr / 1000), int(e * sr / 1000)
        y_c = y[a0:a1]
        sc1, _ = force_align_positions(y_c, sr, [pos], words, device=device)
        sc2, loc2 = force_align_positions(y_c, sr, [pos, pos], words, device=device)
        if (
            loc2 is not None
            and sc2 > sc1 + margin
            and sc2 > -1.5
            and (loc2[0][2] - loc2[0][1]) > 100
            and (loc2[1][2] - loc2[1][1]) > 100
        ):
            out.append([pos, s + loc2[0][1], s + loc2[0][2]])
            out.append([pos, s + loc2[1][1], s + loc2[1][2]])
        else:
            out.append([pos, s, e])
    return out


def _templates(
    high_water: int,
    n_words: int,
    chunk_dur_ms: float,
    mono_in_chunk: list[int],
) -> list[tuple[str, tuple[int, ...]]]:
    """Small, time-budgeted template set for one post-first chunk."""
    out: list[tuple[str, tuple[int, ...]]] = []
    max_n = max(1, min(10, int(chunk_dur_ms / 180) + 2))

    def add(kind: str, seq: list[int]) -> None:
        if not seq or any(p < 1 or p > n_words for p in seq):
            return
        if len(seq) > max_n + 3:
            return
        out.append((kind, tuple(seq)))

    # Expected forward from mono labels in this chunk (if any past high_water)
    mono_fwd = [p for p in mono_in_chunk if p > high_water]
    if mono_fwd:
        add("forward_mono", mono_fwd)

    if high_water < n_words:
        start = high_water + 1
        rem = n_words - high_water
        for L in range(1, min(max_n, rem) + 1):
            add("forward", list(range(start, start + L)))
        # single-word double then continue
        for L in range(1, min(max_n - 1, rem) + 1):
            seq = [start, start] + list(range(start + 1, start + L))
            add("double", seq[: L + 1])
        # span double (2–3) then continue
        for k in (2, 3):
            if k > rem:
                break
            rep = list(range(start, start + k))
            for t in range(0, min(4, rem - k) + 1):
                add("span_double", rep + rep + list(range(start + k, start + k + t)))

    if high_water >= 1:
        for j in range(max(1, high_water - 4), high_water + 1):
            for end in range(j, high_water + 1):
                if end - j > 3:
                    continue
                rep = list(range(j, end + 1))
                add("backtrack", rep)
                if high_water < n_words:
                    for t in range(1, min(5, n_words - high_water) + 1):
                        add(
                            "back_fwd",
                            rep + list(range(high_water + 1, high_water + 1 + t)),
                        )

    # dedup keep order
    seen = set()
    uniq = []
    for k, s in out:
        if s not in seen:
            seen.add(s)
            uniq.append((k, s))
    return uniq[:36]


def _hold(segs: list[list[int]]) -> list[list[int]]:
    for i in range(len(segs) - 1):
        segs[i][2] = max(segs[i][1] + 1, segs[i + 1][1])
        if segs[i][2] <= segs[i][1]:
            segs[i][2] = segs[i][1] + 1
    return segs


def align_structure(
    y: np.ndarray,
    sr: int,
    words: Sequence[str],
    *,
    min_pause_ms: float = 200.0,
    margin: float = 0.10,
    device: str | None = None,
) -> StructureResult:
    warnings: list[str] = []
    n = len(words)
    if n == 0:
        return StructureResult([], [], mode="empty")

    mono, mono_sc = mono_align(y, sr, words, device=device)
    expanded = expand_long_words(y, sr, mono, words, device=device)
    chunks = pause_chunks(y, sr, min_pause_ms=min_pause_ms)

    # --- single chunk path ---
    if len(chunks) <= 1:
        segs = _hold([list(s) for s in expanded])
        return StructureResult(
            positions=[s[0] for s in segs],
            segments=segs,
            chunks=chunks,
            choices=[SpanChoice("mono_long", tuple(s[0] for s in segs), mono_sc, mono_sc)],
            warnings=warnings,
            mode="mono_long",
        )

    # --- multi-chunk: time-anchor each chunk to mono midpoints ---
    positions: list[int] = []
    segments: list[list[int]] = []
    choices: list[SpanChoice] = []
    high_water = 0

    for ci, ch in enumerate(chunks):
        y_c = y[ch.start_sample : ch.end_sample]
        mono_in = [
            s[0]
            for s in mono
            if ch.start_ms <= (s[1] + s[2]) / 2.0 < ch.end_ms
        ]

        if ci == 0:
            # Emit mono words whose midpoint is in first chunk only
            first_segs = [list(s) for s in mono if s[0] in set(mono_in) or (
                s[1] < ch.end_ms and s[0] <= (max(mono_in) if mono_in else 0)
            )]
            # cleaner: all mono with start < next chunk start
            next_start = chunks[1].start_ms
            first_segs = [list(s) for s in mono if s[1] < next_start - 20]
            if not first_segs:
                first_segs = [list(s) for s in mono[: max(1, len(mono_in) or 1)]]
            # re-clock on chunk
            pos_seq = [s[0] for s in first_segs]
            sc, loc = force_align_positions(y_c, sr, pos_seq, words, device=device)
            if loc is not None:
                for p, s, e in loc:
                    positions.append(p)
                    segments.append([p, ch.start_ms + s, ch.start_ms + e])
            else:
                for s in first_segs:
                    positions.append(s[0])
                    segments.append(
                        [s[0], max(ch.start_ms, s[1]), min(ch.end_ms, s[2])]
                    )
            high_water = max(positions) if positions else 0
            choices.append(
                SpanChoice("forward", tuple(positions), sc if loc else mono_sc, sc if loc else mono_sc)
            )
            continue

        # Subsequent chunk: multi-hypothesis
        tpls = _templates(high_water, n, ch.dur_ms, mono_in)
        scored: list[tuple[float, float, str, tuple[int, ...], list | None]] = []
        for kind, seq in tpls:
            path, loc = force_align_positions(y_c, sr, seq, words, device=device)
            if path == float("-inf") or loc is None:
                continue
            L = len(seq)
            per = ch.dur_ms / max(L, 1)
            fit = 0.0
            if per < 100:
                fit -= (100 - per) / 50.0
            elif per > 1000:
                fit -= (per - 1000) / 400.0
            cov = (loc[-1][2] - loc[0][1]) / max(ch.dur_ms, 1)
            if cov < 0.55:
                fit -= 0.35
            rep_pen = 0.0 if kind.startswith("forward") else -0.05
            # prefer matching mono count when forward
            if kind.startswith("forward") and mono_in:
                fit -= 0.03 * abs(L - len([p for p in mono_in if p > high_water]))
            total = path + 0.2 * fit + rep_pen
            scored.append((total, path, kind, seq, loc))

        if not scored:
            warnings.append(f"chunk{ci}: fallback mono_in")
            for s in mono:
                if s[0] in mono_in and s[0] > high_water:
                    positions.append(s[0])
                    segments.append([s[0], max(ch.start_ms, s[1]), min(ch.end_ms, s[2])])
                    high_water = max(high_water, s[0])
            continue

        scored.sort(key=lambda x: -x[0])
        best = scored[0]
        if not best[2].startswith("forward"):
            forwards = [s for s in scored if s[2].startswith("forward")]
            if forwards and best[0] < forwards[0][0] + margin:
                best = forwards[0]
            if not best[2].startswith("forward") and best[1] < -1.6:
                if forwards:
                    best = forwards[0]

        total, path, kind, seq, loc = best
        choices.append(SpanChoice(kind, seq, total, path))
        for p, s, e in loc:
            positions.append(p)
            segments.append([p, ch.start_ms + s, ch.start_ms + e])
        high_water = max(high_water, max(seq))

    if high_water < n:
        warnings.append(f"tail {high_water+1}..{n}")
        for s in mono:
            if s[0] > high_water:
                positions.append(s[0])
                segments.append(list(s))

    # If pause path found no backtracks but long-word expand did, merge expand
    def n_bt(pos):
        hw = 0
        c = 0
        for p in pos:
            if p <= hw:
                c += 1
            hw = max(hw, p)
        return c

    if n_bt(positions) == 0 and n_bt([s[0] for s in expanded]) > 0:
        warnings.append("use long-word doubles")
        positions = [s[0] for s in expanded]
        segments = [list(s) for s in expanded]

    segments = _hold(segments)
    return StructureResult(
        positions=positions,
        segments=segments,
        chunks=chunks,
        choices=choices,
        warnings=warnings,
        mode="time_anchored",
    )


def structure_signature(positions: Sequence[int]) -> tuple[int, ...]:
    return tuple(int(p) for p in positions)
