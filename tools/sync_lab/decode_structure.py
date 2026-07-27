"""
Structure from free CTC decode + unique span matching.

Unlike forced-align path scores (which *prefer* collapsing re-says),
greedy decode often still *emits* the repeated phrase twice. We detect
spans of the canonical word list that appear ≥2× in the decode string
and that are not mere duplicates of the written ayah text.

Rules (intentionally tiny):
  - Prefer multi-word spans (L≥2) or long single words (≥4 letters).
  - Span must occur once in the written ayah (unique wording) OR
    occurrences in decode are clearly sequential with gap.
  - Short function words alone never count.

Then: base mono 1..N, insert one extra copy of the best span after its
first completion.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Sequence

import numpy as np
import torch

from aligners import get_ctc_model, letters_only, load_mono_16k
from structure_engine import mono_align, force_align_positions, MODEL_ID


def _norm(s: str) -> str:
    return re.sub(r"[^ء-ي]", "", letters_only(s))


@torch.inference_mode()
def greedy_letters(y: np.ndarray, sr: int) -> str:
    cm = get_ctc_model(MODEL_ID)
    inv = {v: k for k, v in cm.vocab.items()}
    blank = cm.blank_id
    inputs = cm.processor(y, sampling_rate=sr, return_tensors="pt", padding=True)
    logits = cm.model(inputs.input_values.to(cm.device)).logits[0]
    ids = logits.argmax(-1).tolist()
    chars = []
    prev = None
    for i in ids:
        if i == blank:
            prev = None
            continue
        if i != prev:
            ch = inv.get(i, "")
            if ch and ch not in ("|", " ", "[PAD]", "[UNK]"):
                chars.append(ch)
            prev = i
    return _norm("".join(chars))


@dataclass
class ResayHit:
    start_pos: int  # 1-based
    end_pos: int
    score: float
    n_decode: int


def find_resays(words: Sequence[str], decode: str) -> list[ResayHit]:
    norms = [_norm(w) for w in words]
    n = len(words)
    full = "".join(norms)
    hits: list[ResayHit] = []

    for L in range(1, 5):
        for i in range(n - L + 1):
            span = "".join(norms[i : i + L])
            if len(span) < (3 if L > 1 else 4):
                continue
            # count in written text
            text_cnt = full.count(span)
            # count in decode (allow fuzzy: prefix of span if L==1 and long)
            dec_cnt = decode.count(span)
            if dec_cnt < 2 and L == 1 and len(span) >= 5:
                # truncated second copy: span[:-1]
                dec_cnt = max(dec_cnt, decode.count(span[:-1]))
            if dec_cnt < 2:
                continue
            # If text already has the span twice (e.g. ويقتلون), require
            # decode count > text count
            if text_cnt >= 2 and dec_cnt <= text_cnt:
                continue
            # function-word singles
            if L == 1 and len(span) <= 3:
                continue
            # Strongly prefer multi-word spans (shifted-span gold cases).
            score = L * 4.0 + len(span) * 0.15 + (dec_cnt - 1)
            if text_cnt == 1:
                score += 2.0  # unique in ayah
            hits.append(ResayHit(i + 1, i + L, score, dec_cnt))

    hits.sort(key=lambda h: -h.score)
    return hits


def build_positions_with_resay(
    n_words: int, hit: ResayHit | None
) -> list[int]:
    """Mono 1..N with one inserted re-say of hit after first pass through end_pos."""
    if hit is None:
        return list(range(1, n_words + 1))
    pos = []
    for p in range(1, n_words + 1):
        pos.append(p)
        if p == hit.end_pos:
            # insert re-say of start..end
            pos.extend(range(hit.start_pos, hit.end_pos + 1))
    return pos


def align_decode_structure(
    y: np.ndarray,
    sr: int,
    words: Sequence[str],
    *,
    device: str | None = None,
) -> tuple[list[int], list[list[int]], list[str]]:
    warnings: list[str] = []
    decode = greedy_letters(y, sr)
    hits = find_resays(words, decode)
    hit = hits[0] if hits else None
    if hit:
        warnings.append(
            f"resay {hit.start_pos}-{hit.end_pos} score={hit.score:.1f} dec×{hit.n_decode}"
        )
    positions = build_positions_with_resay(len(words), hit)

    # Clock: force-align full position sequence
    sc, segs = force_align_positions(y, sr, positions, words, device=device)
    if segs is None:
        mono, _ = mono_align(y, sr, words, device=device)
        # expand mono times roughly onto positions
        segs = []
        mono_by = {s[0]: s for s in mono}
        for p in positions:
            if p in mono_by:
                segs.append(list(mono_by[p]))
            else:
                segs.append([p, segs[-1][2] if segs else 0, (segs[-1][2] if segs else 0) + 200])
        warnings.append("FA failed; mono clock fallback")
    else:
        for i in range(len(segs) - 1):
            segs[i][2] = max(segs[i][1] + 1, segs[i + 1][1])
    return positions, segs, warnings
