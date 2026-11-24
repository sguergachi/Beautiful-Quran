#!/usr/bin/env python3
"""Offline tests for tools/reciter_align (synthetic emissions: no models, no audio files)."""

import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from reciter_align import artifact  # noqa: E402
from reciter_align.acoustics import Energy, SAMPLE_RATE  # noqa: E402
from reciter_align.route import RouteConfig, resolve_route  # noqa: E402
from reciter_align.text import ISTIADHA, Word, letters, opening_words, surah_words  # noqa: E402
from reciter_align.viterbi import forced_align, segments  # noqa: E402
from reciter_align.windowed import AlignConfig, align_route  # noqa: E402

STRIDE = 0.02
V = 44  # blank + one id per Arabic letter


class FakeBackend:
    """Arabic letters as tokens; emissions built by `speak`."""

    name = "fake"
    stride_s = STRIDE
    blank = 0
    star = V
    word_delimiter = None

    def tokens(self, word):
        return [ord(c) - 0x620 for c in letters(word)]


def speak(backend, script, tail_s=3.0):
    """script: [(word_text, silence_before_s)] -> (emissions, [first-token frame per word])."""
    frames, starts, t = [], [], 0.0
    plan = []
    for text, gap in script:
        t += gap
        start = round(t / STRIDE)
        starts.append(start)
        toks = backend.tokens(text)
        for k, tok in enumerate(toks):
            plan.append((start + 2 * k, tok))
        t += (2 * len(toks) + 3) * STRIDE
    total = round((t + tail_s) / STRIDE)
    em = np.full((total, V + 1), np.log(0.03 / (V - 1)), dtype=np.float32)
    em[:, backend.blank] = np.log(0.97)
    for f, tok in plan:
        em[f, :] = np.log(0.01 / (V - 1))
        em[f, tok] = np.log(0.95)
        em[f, backend.blank] = np.log(0.04)
    em[:, V] = 0.0  # star
    return em, starts


def words_of(route):
    return [(w.ayah, w.pos) for w in route]


def test_letters_fold_marks_and_hamza():
    assert letters("ٱلرَّحۡمَٰنِ") == "الرحمن"
    assert letters("أُوْلَـٰٓئِكَ") == "اوليك"
    assert letters("۝١") == ""


def test_forced_align_places_tokens_on_their_frames():
    em = np.full((12, 4), np.log(0.02), dtype=np.float32)
    em[:, 0] = np.log(0.94)
    for frame, tok in [(2, 1), (5, 2), (8, 2)]:
        em[frame, :] = np.log(0.02)
        em[frame, tok] = np.log(0.94)
    labels, scores = forced_align(em, [1, 2, 2], blank=0)
    runs = segments(labels, scores, 0)
    assert [(r[0], r[1]) for r in runs] == [(1, 2), (2, 5), (2, 8)], runs


def test_windowed_alignment_survives_many_window_hops():
    backend = FakeBackend()
    words = surah_words(2)[:300]
    script = [(w.text, 0.5 if i % 7 == 0 else 0.0) for i, w in enumerate(words)]
    em, starts = speak(backend, script)
    placed = align_route(em, backend.blank, backend.star, [backend.tokens(w.text) for w in words],
                         STRIDE, AlignConfig(window_s=20.0))
    got = [p.start for p in placed]
    assert got == starts, [(i, a, b) for i, (a, b) in enumerate(zip(got, starts)) if a != b][:5]


def test_opening_and_repeat_are_recovered_from_the_audio_alone():
    backend = FakeBackend()
    verse = surah_words(1)
    ayah2 = [w for w in verse if w.ayah == 2]
    spoken = (
        [Word(0, i, t, "istiadha") for i, t in enumerate(ISTIADHA, 1)]
        + verse[:4 + len(ayah2)]            # basmala (ayah 1) and ayah 2 ...
        + ayah2                             # ... said again in full
        + [w for w in verse if w.ayah >= 3]
    )
    script = [(w.text, 0.6 if i in (0, len(ISTIADHA)) else 0.0) for i, w in enumerate(spoken)]
    em, starts = speak(backend, script)
    route, placed, report = resolve_route(em, backend, verse, STRIDE, AlignConfig(window_s=30.0),
                                          RouteConfig(min_gain=8.0))
    assert words_of(route) == words_of(spoken)
    assert [p.start for p in placed] == starts
    assert sorted(c.kind for c in report.changes) == ["opening", "repeat"]


def test_clean_recitation_is_left_alone():
    backend = FakeBackend()
    verse = surah_words(112)
    em, starts = speak(backend, [(w.text, 0.2) for w in verse])
    route, placed, report = resolve_route(em, backend, verse, STRIDE)
    assert words_of(route) == words_of(verse) and not report.changes
    assert [p.start for p in placed] == starts


def test_energy_edges_bracket_a_burst():
    rng = np.random.default_rng(1)
    audio = (rng.standard_normal(SAMPLE_RATE * 4) * 0.001).astype(np.float32)
    audio[SAMPLE_RATE * 1:SAMPLE_RATE * 2 + 8000] += (rng.standard_normal(SAMPLE_RATE + 8000) * 0.2).astype(np.float32)
    energy = Energy(audio)
    level = energy.level(1.1, 1.9)
    assert abs(energy.onset(1.1, 0.0, level) - 1.0) < 0.05
    assert abs(energy.offset(1.9, 4.0, level) - 2.5) < 0.05


def test_artifact_round_trip():
    with tempfile.TemporaryDirectory() as tmp:
        work, audio = Path(tmp) / "w", Path(tmp) / "a.mp3"
        audio.write_bytes(b"abc")
        for s in (1, 2):
            artifact.save_surah(work, s, {"durationMs": s, "words": [[1, 1, 0, 100, -0.1]], "runs": [], "openings": [], "review": {}})
        out = Path(tmp) / "x.json.gz"
        sources = {s: {"bytes": 3, "sha256": artifact.sha256_file(audio)} for s in (1, 2)}
        artifact.pack(work, out, "slug", {"primary": "m"}, sources, surahs=[1, 2])
        loaded = artifact.load(out)
        assert loaded["reciter"] == "slug" and sorted(loaded["surahs"]) == ["1", "2"]
        assert loaded["sources"]["1"]["bytes"] == 3


def main():
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for test in tests:
        test()
        print(f"ok  {test.__name__}")
    print(f"all {len(tests)} reciter_align tests pass")


if __name__ == "__main__":
    main()
