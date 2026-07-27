#!/usr/bin/env python3
"""Detect audible wasl connections from audio + orthographic nūn rules.

Text rules name *candidates* (same as V1 Tajweed). Audio decides whether the
reciter actually joined the words: continuous energy across a short boundary
with no silence dip.
"""
from __future__ import annotations

import math
import re
import unicodedata
from dataclasses import dataclass

import numpy as np

# Idghām letters (يرملون), iqlāb (ب), ikhfāʾ set (15) — opening base letter.
IDGHAM = set("يرملون")
IQLAB = set("ب")
IKHFA = set("تثجدذزسشصضطظفقك")
WASL_TARGETS = IDGHAM | IQLAB | IKHFA

# Arabic marks that signal nūn sākinah / tanwīn at word end.
TANWEEN = set("ًٌٍ")
SUKUN_MARKS = set("ْۡ")


def _letters(text: str) -> str:
    return "".join(
        ch for ch in text
        if unicodedata.category(ch).startswith("L") and ch not in "ـ"
    )


def ends_with_noon_sakin_or_tanween(arabic: str) -> bool:
    """True when the written word can donate a wasl nūn (orthography only)."""
    if not arabic:
        return False
    if any(ch in TANWEEN for ch in arabic):
        return True
    # Nūn with sukūn, or bare nūn as last letter (common in Uthmani with sukūn).
    stripped = arabic
    for mark in "ًٌٍَُِّْٰٓٔۡ۟ۖۗۘۙۚۛۜ":
        stripped = stripped.replace(mark, "")
    if stripped.endswith("ن"):
        # Explicit sukūn or bare final nūn both candidate for wasl rules.
        return True
    # Look for ن + sukūn near the end
    return bool(re.search(r"ن[ْۡ]?$", arabic))


def opening_base(arabic: str) -> str | None:
    letters = _letters(arabic)
    return letters[0] if letters else None


def text_wasl_candidate(prev_arabic: str, next_arabic: str) -> bool:
    """Orthographic wasl-nūn candidate (same-ayah rules only)."""
    if not ends_with_noon_sakin_or_tanween(prev_arabic):
        return False
    base = opening_base(next_arabic)
    return base is not None and base in WASL_TARGETS


def frame_rms(y: np.ndarray, sr: int, frame_ms: float = 20.0) -> tuple[np.ndarray, int]:
    frame = max(1, int(sr * frame_ms / 1000))
    hop = max(1, int(sr * frame_ms / 2000))
    n = 1 + max(0, (len(y) - frame) // hop)
    if n <= 0:
        return np.zeros(0, dtype=np.float64), hop
    rms = np.empty(n, dtype=np.float64)
    for i in range(n):
        sl = y[i * hop : i * hop + frame]
        rms[i] = math.sqrt(float(np.mean(sl * sl)) + 1e-12)
    return rms, hop


@dataclass(frozen=True)
class WaslLink:
    """Audible wasl into the word at [next_index] (0-based occurrence index)."""
    next_index: int
    wasl_from_prev_ms: int
    gap_ms: int
    continuity: float  # 0..1, fraction of bridge frames above silence floor


def detect_wasl_links(
    y: np.ndarray,
    sr: int,
    segments: list[dict],
    arabic_words: list[str],
    *,
    max_gap_ms: int = 90,
    silence_ratio: float = 0.08,
    min_continuity: float = 0.55,
    max_wasl_ms: int = 480,
) -> list[WaslLink]:
    """Return acoustic wasl links for consecutive occurrences.

    [arabic_words] is indexed by 1-based position (list length = word count).
    [segments] are occurrence-ordered dicts with position/startMs/endMs.
    """
    if len(segments) < 2 or sr <= 0 or y.size == 0:
        return []
    rms, hop = frame_rms(y, sr)
    if rms.size == 0:
        return []
    peak = float(rms.max())
    thr = peak * silence_ratio
    links: list[WaslLink] = []

    for i in range(len(segments) - 1):
        left, right = segments[i], segments[i + 1]
        prev_pos = int(left["position"])
        next_pos = int(right["position"])
        if prev_pos < 1 or next_pos > len(arabic_words):
            continue
        prev_ar = arabic_words[prev_pos - 1]
        next_ar = arabic_words[next_pos - 1]
        if not text_wasl_candidate(prev_ar, next_ar):
            continue
        left_end = int(left["endMs"])
        right_start = int(right["startMs"])
        gap = right_start - left_end
        if gap > max_gap_ms:
            continue
        # Bridge window: last part of donor through first part of receiver.
        bridge0 = max(0, left_end - min(120, max(40, (left_end - int(left["startMs"])) // 3)))
        bridge1 = right_start + min(80, max(20, (int(right["endMs"]) - right_start) // 4))
        a = max(0, int(bridge0 * sr / 1000) // hop)
        b = min(len(rms), max(a + 1, int(bridge1 * sr / 1000) // hop))
        window = rms[a:b]
        if window.size == 0:
            continue
        continuity = float(np.mean(window >= thr))
        if continuity < min_continuity:
            continue
        # Duration of continuous speech on the donor's tail (wasl bloom budget).
        donor_tail0 = max(int(left["startMs"]), left_end - max_wasl_ms)
        t0 = max(0, int(donor_tail0 * sr / 1000) // hop)
        t1 = min(len(rms), max(t0 + 1, int(left_end * sr / 1000) // hop))
        tail = rms[t0:t1]
        voiced = int(np.sum(tail >= thr))
        wasl_ms = int(round(voiced * hop * 1000 / sr))
        wasl_ms = max(40, min(max_wasl_ms, wasl_ms))
        links.append(
            WaslLink(
                next_index=i + 1,
                wasl_from_prev_ms=wasl_ms,
                gap_ms=gap,
                continuity=continuity,
            )
        )
    return links


def apply_wasl_links(segments: list[dict], links: list[WaslLink]) -> list[dict]:
    """Copy segments with waslFromPrevMs set on receiving occurrences."""
    out = [dict(s) for s in segments]
    for link in links:
        if 0 <= link.next_index < len(out):
            out[link.next_index]["waslFromPrevMs"] = int(link.wasl_from_prev_ms)
    for seg in out:
        seg.setdefault("waslFromPrevMs", 0)
    return out
