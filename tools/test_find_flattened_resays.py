#!/usr/bin/env python3
"""Offline tests for tools/find_flattened_resays.py (no models, no audio)."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import find_flattened_resays as f  # noqa: E402
from build_db import restore_flattened_resay  # noqa: E402

# Hani 21:46 as qdc delivers it: one يَٰوَيۡلَنَآ, إِنَّا opening at 9800.
ROW = [[7, 6030, 7710], [8, 7710, 9800], [9, 9800, 12340], [10, 12340, 13680]]
REFERENCE = [[7, 6020, 7750], [8, 7760, 11340], [9, 11350, 12370], [10, 12380, 13670]]
KEY = (7, 21, 46)


def candidates(row=ROW, reference=REFERENCE, shipped=None, owned=(), lead=600):
    return f.find_candidates(
        {KEY: row}, {KEY: reference}, {KEY: shipped or row}, set(owned), lead
    )


def test_candidate_is_the_gap_quran_align_opens():
    [c] = candidates()
    assert c["position"] == 8
    assert c["sourcePair"] == [[8, 7710, 9800], [9, 9800, 12340]]
    assert c["leadMs"] == 1550


def test_no_candidate_when_the_sources_agree():
    agreeing = [[7, 6020, 7750], [8, 7760, 9800], [9, 9810, 12370], [10, 12380, 13670]]
    assert candidates(reference=agreeing) == []


def test_no_candidate_for_a_word_already_said_twice():
    twice = [[7, 6030, 7710], [8, 7710, 8700], [8, 8700, 9800], [9, 9800, 12340]]
    assert candidates(row=twice) == []


def test_no_candidate_for_a_row_another_verdict_owns():
    assert candidates(owned={KEY}) == []


def test_no_candidate_when_the_pair_does_not_ship():
    assert candidates(shipped=[[7, 6030, 7710], [8, 7710, 9900], [9, 9900, 12340]]) == []


def test_no_candidate_when_the_shipped_pair_is_split_apart():
    # Both segments still ship, but something now sits between them.
    shipped = [[7, 6030, 7710], [8, 7710, 9800], [8, 9800, 9800], [9, 9800, 12340]]
    assert candidates(shipped=shipped) == []


def test_hand_written_corrections_survive_a_rewrite():
    for path in sorted(f.CORRECTIONS_DIR.glob("*.json")):
        text = path.read_text(encoding="utf-8")
        assert f._dump_corrections(__import__("json").loads(text)) == text, path.name


def test_hypothesis_only_adds_the_repeated_occurrence():
    hyp = f.hypothesis(ROW, [[8, 7710, 9800], [9, 9800, 12340]])
    assert [s[0] for s in hyp] == [7, 8, 8, 9, 10]


# Forced words from the 21:46 audit: [position, start, end, mean label probability].
XLSR_WORDS = [[7, 6069, 7632, 0.9], [8, 7852, 10095, 0.982], [8, 10276, 11157, 0.808], [9, 11397, 12118, 0.7]]
MMS_WORDS = [[7, 6069, 7632, 0.9], [8, 7852, 8994, 0.914], [8, 9915, 11117, 0.865], [9, 11518, 12219, 0.7]]


def test_both_models_must_prefer_the_repeat():
    good = {
        "position": 8, "xlsrWords": XLSR_WORDS, "mmsWords": MMS_WORDS,
        "xlsr_base": -0.445, "xlsr_hyp": -0.432, "mms_base": -0.068, "mms_hyp": -0.049,
    }
    assert f.accepted(good)
    # Hani 89:16 in calibration: XLSR +0.068, MMS -0.0018 — one model is not enough.
    split = dict(good, mms_hyp=good["mms_base"] - 0.0018)
    assert not f.accepted(split)
    marginal = dict(good, mms_hyp=good["mms_base"] + f.ACCEPT_MARGIN / 2)
    assert not f.accepted(marginal)
    assert not f.accepted(dict(good, mmsError="RuntimeError: no audio"))


def test_each_occurrence_must_sound_like_the_word():
    # Abdul Basit 37:152: the MP3 opens with the previous ayah, and the extra
    # وَلَدَ soaked it up. Both models prefer the sequence; XLSR hears neither
    # occurrence as the word.
    row = {
        "position": 1,
        "xlsr_base": -0.60, "xlsr_hyp": -0.55, "mms_base": -0.10, "mms_hyp": -0.09,
        "xlsrWords": [[1, 681, 2964, 0.371], [1, 3144, 3505, 0.208], [2, 3645, 4186, 0.8]],
        "mmsWords": [[1, 1522, 2964, 0.53], [1, 3144, 3485, 0.484], [2, 3545, 4186, 0.8]],
    }
    assert not f.accepted(row)


def test_boundaries_are_the_pause_midpoints_both_models_place():
    row = {"position": 8, "xlsrWords": XLSR_WORDS, "mmsWords": MMS_WORDS}
    resay, onset = f.forced_resay(row)
    assert resay == round(((10095 + 10276) / 2 + (8994 + 9915) / 2) / 2)
    assert onset == round(((11157 + 11397) / 2 + (11117 + 11518) / 2) / 2)
    # Voice returns at ~9.84 s and إِنَّا opens at ~11.36 s on the waveform.
    assert abs(resay - 9840) < 100 and abs(onset - 11360) < 100
    applied = restore_flattened_resay(
        ROW, 8, resay, onset, [[8, 7710, 9800], [9, 9800, 12340]], True
    )
    assert [s[0] for s in applied] == [7, 8, 8, 9, 10]


def test_no_boundaries_when_a_model_does_not_place_two_occurrences():
    row = {
        "position": 8,
        "xlsrWords": [[7, 6069, 7632, 0.9], [8, 7852, 11157, 0.9], [9, 11397, 12118, 0.7]],
        "mmsWords": MMS_WORDS,
    }
    assert f.forced_resay(row) is None


def main():
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for test in tests:
        test()
    print(f"all {len(tests)} flattened re-say tests pass")


if __name__ == "__main__":
    main()
