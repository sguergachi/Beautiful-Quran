#!/usr/bin/env python3
"""Strict clock transfer for Qur'anic Universal Audio letter timings."""
from __future__ import annotations

import unicodedata
from dataclasses import dataclass

import numpy as np
from scipy.signal import correlate

QUA_RELEASE = "v2.3.0"
QUA_REVISION = "9b83ea5824d1f4de3921562f9d7282e279f05860"
ALAFASY_RELEASE_SHA256 = "8a05209a022ad4410ce39f74f374ec09fb5bae6a019b1e2b054fda8342bf0df7"
QPC_HAFS_SHA256 = "9b2f91a19769275d0da57464002beacd8cec396b02b520aa14d17e3b135012a7"
LETTER_VOCAB_SHA256 = "4043204e2b646c5de3a55165a7054488acd2c7df4bfd8eca7fc178db85be660a"
MIN_CORRELATION = 0.70
MIN_PEAK_MARGIN = 0.25
MAX_EXPECTED_CLOCK_DELTA_MS = 500


@dataclass(frozen=True)
class ClockMatch:
    source_zero_ms: float
    correlation: float
    peak_margin: float


def match_audio_clock(
    source_window: np.ndarray,
    everyayah_audio: np.ndarray,
    sample_rate: int,
    window_start_ms: float,
) -> ClockMatch | None:
    """Locate a decoded EveryAyah clip inside a decoded source-audio window."""
    if sample_rate <= 0 or len(everyayah_audio) < sample_rate:
        return None
    if len(source_window) < len(everyayah_audio):
        return None

    target = everyayah_audio.astype(np.float64)
    target -= target.mean()
    target_norm = np.linalg.norm(target)
    if target_norm == 0:
        return None

    source = source_window.astype(np.float64)
    dots = correlate(source, target, mode="valid", method="fft")
    size = len(target)
    total = np.concatenate(([0.0], np.cumsum(source)))
    squares = np.concatenate(([0.0], np.cumsum(source * source)))
    sums = total[size:] - total[:-size]
    variance = squares[size:] - squares[:-size] - sums * sums / size
    scores = dots / (np.sqrt(np.maximum(variance, 1e-18)) * target_norm)
    peak = int(np.argmax(scores))
    correlation = float(scores[peak])

    outside = np.ones(len(scores), dtype=bool)
    radius = round(0.05 * sample_rate)
    outside[max(0, peak - radius) : peak + radius + 1] = False
    runner_up = float(scores[outside].max()) if outside.any() else -1.0
    return ClockMatch(
        source_zero_ms=window_start_ms + peak * 1000.0 / sample_rate,
        correlation=correlation,
        peak_margin=correlation - runner_up,
    )


def accepted_clock(
    match: ClockMatch | None,
    expected_source_zero_ms: float | None = None,
) -> bool:
    return bool(
        match
        and match.correlation >= MIN_CORRELATION
        and match.peak_margin >= MIN_PEAK_MARGIN
        and (
            expected_source_zero_ms is None
            or abs(match.source_zero_ms - expected_source_zero_ms)
            <= MAX_EXPECTED_CLOCK_DELTA_MS
        )
    )


def _units(tokens: list[str]) -> list[tuple[list[int], str]]:
    """Group source tokens by rendered base-letter slot."""
    units: list[tuple[list[int], str]] = []
    index = 0
    while index < len(tokens):
        char = tokens[index]
        if char == "\u0654" and index + 1 < len(tokens) and tokens[index + 1] in "اوي":
            carrier = tokens[index + 1]
            units.append(([index, index + 1], {"ا": "أ", "و": "ؤ", "ي": "ئ"}[carrier]))
            index += 2
        elif unicodedata.category(char).startswith("L"):
            units.append(([index], char))
            index += 1
        elif units:
            units[-1][0].append(index)
            index += 1
        else:
            return []
    return units


def source_groups(
    source_word: str,
    rendered_word: str,
    letter_vocab: set[str],
) -> list[list[int]]:
    """Map QUA's acoustic tokens onto the app's rendered base-letter slots."""
    source = [char for char in source_word if char in letter_vocab]
    rendered = [char for char in rendered_word if char in letter_vocab]
    source_units = _units(source)
    rendered_units = _units(rendered)

    def comparable(base: str) -> str:
        return "ي" if base in "يىئ" else base

    if [comparable(unit[1]) for unit in source_units] != [
        comparable(unit[1]) for unit in rendered_units
    ]:
        return []
    return [indices for indices, _ in source_units]


def occurrence_letters(
    word_rows: list[list[int]],
    letter_rows: list[list],
    canonical_words: dict[int, str],
    letter_vocab: set[str],
) -> list[list[list]] | None:
    """Split QUA letters into word occurrences, including adjacent repeats."""
    occurrences: dict[int, int] = {}
    for position, *_ in word_rows:
        occurrences[position] = occurrences.get(position, 0) + 1

    chunks: dict[int, list[list[list]]] = {}
    for position, count in occurrences.items():
        target = [char for char in canonical_words.get(position, "") if char in letter_vocab]
        actual = [row for row in letter_rows if int(row[0]) == position]
        if not target or len(actual) != len(target) * count:
            return None
        chunks[position] = [
            actual[index : index + len(target)]
            for index in range(0, len(actual), len(target))
        ]
        if any([row[1] for row in chunk] != target for chunk in chunks[position]):
            return None

    used: dict[int, int] = {}
    result = []
    for position, *_ in word_rows:
        index = used.get(position, 0)
        result.append(chunks[position][index])
        used[position] = index + 1
    return result


def acoustic_keyframes(
    word_start_ms: int,
    word_end_ms: int,
    letters: list[list],
    groups: list[list[int]],
) -> list[dict]:
    """Convert measured letter intervals into a monotone spatial wash curve.

    Tolerates QUA letters that slightly overhang the word span (clamped) and
    zero-width clamped slices so short words still emit a valid curve.
    """
    if word_end_ms <= word_start_ms or not letters or not groups:
        return []
    progress = {}
    for slot, token_indices in enumerate(groups):
        for offset, token_index in enumerate(token_indices, 1):
            progress[token_index] = (slot + offset / len(token_indices)) / len(groups)
    if sorted(progress) != list(range(len(letters))):
        return []

    intervals = []
    for token_index, (_, _, start, end) in enumerate(letters):
        if int(end) <= word_start_ms or int(start) >= word_end_ms:
            continue
        lo = max(word_start_ms, int(start))
        hi = min(word_end_ms, int(end))
        if hi < lo:
            continue
        item = [lo, hi, progress[token_index]]
        if intervals and item[:2] == intervals[-1][:2]:
            intervals[-1][2] = item[2]
        else:
            intervals.append(item)

    if not intervals:
        return []

    points: list[list[float]] = []

    def append(at_ms: int, value: float) -> None:
        offset = at_ms - word_start_ms
        if offset < 0:
            return
        if points and offset == points[-1][0]:
            points[-1][1] = max(points[-1][1], value)
        elif not points or offset > points[-1][0]:
            points.append([offset, value])

    last_time = word_start_ms
    last_progress = 0.0
    for start, end, end_progress in intervals:
        if end < start or start < last_time:
            # Overhang / reorder — snap progress at last_time then continue.
            if end < last_time:
                last_progress = max(last_progress, end_progress)
                continue
            start = last_time
        if end == start:
            # Instantaneous (clamped) letter: record progress, no span.
            append(end, end_progress)
            last_progress = end_progress
            continue
        if start > last_time:
            append(start, last_progress)
        append(end, end_progress)
        last_time = max(last_time, end)
        last_progress = end_progress

    append(word_end_ms, 1.0)
    while points and points[0][0] == 0 and points[0][1] == 0:
        points.pop(0)
    if not points:
        return []
    if points[0][0] <= 0:
        # Must not start with a hard pop at t=0 — nudge first positive offset.
        if len(points) == 1:
            points = [[max(1, (word_end_ms - word_start_ms) // 2), 1.0]]
        else:
            points[0][0] = max(1, points[0][0])
    if points[-1][1] < 1.0:
        append(word_end_ms, 1.0)
    if not points or points[-1][1] != 1.0:
        return []
    return [
        {"offsetMs": int(offset), "progress": round(float(value), 6)}
        for offset, value in points
    ]


def fallback_word_keyframe(word_start_ms: int, word_end_ms: int) -> list[dict]:
    """Single end-anchor when letter mapping fails — structure still ships."""
    if word_end_ms <= word_start_ms:
        return []
    mid = max(1, (word_end_ms - word_start_ms) // 2)
    return [{"offsetMs": mid, "progress": 1.0}]
