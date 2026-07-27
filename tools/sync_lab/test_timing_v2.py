#!/usr/bin/env python3
"""Pure tests for fail-closed V2 acoustic-keyframe rebasing."""
from aligners import _map_chars_to_ids
from generate_timing_v2 import keyframed_segments, reconcile_acoustic_boundaries
from quran_phoneme_aligner import phoneme_keyframes, spatial_progress, token_error_rate
from qua_timing import (
    ClockMatch,
    accepted_clock,
    acoustic_keyframes,
    match_audio_clock,
    occurrence_letters,
    source_groups,
)
from timing_v2_metrics import summarize_clock_conventions, summarize_errors
from validate_timing_v2 import (
    historical_overlap,
    is_repeat_row,
    objective_row_flags,
    summarize_payload,
)


def test_rebases_keyframes_inside_final_spans():
    got = keyframed_segments(
        [[1, 100, 500]],
        [[[120, 0.25], [220, 0.5], [500, 1.0]]],
    )
    assert got[0]["keyframes"] == [
        {"offsetMs": 20, "progress": 0.25},
        {"offsetMs": 120, "progress": 0.5},
        {"offsetMs": 400, "progress": 1.0},
    ]


def test_preserves_acoustic_plateaus():
    got = keyframed_segments(
        [[1, 100, 700]],
        [[[100, 0.0], [200, 0.5], [600, 0.5], [700, 1.0]]],
    )
    assert got[0]["keyframes"] == [
        {"offsetMs": 100, "progress": 0.5},
        {"offsetMs": 500, "progress": 0.5},
        {"offsetMs": 600, "progress": 1.0},
    ]


def test_rejects_incomplete_or_clamped_rows():
    assert keyframed_segments([[1, 0, 100]], []) == []
    assert keyframed_segments([[1, 0, 100]], [[]]) == []
    assert keyframed_segments([[1, 100, 500]], [[[100, 0.5], [500, 1.0]]]) == []
    assert keyframed_segments([[1, 100, 500]], [[[120, 0.5], [501, 1.0]]]) == []
    assert keyframed_segments(
        [[1, 100, 500]],
        [[[120, 0.25], [120, 0.5], [500, 1.0]]],
    ) == []


def test_refinements_cannot_cross_acoustic_evidence():
    base = [[1, 100, 500], [2, 500, 900]]
    keyframes = [
        [[180, 0.5], [480, 1.0]],
        [[520, 0.5], [880, 1.0]],
    ]
    assert reconcile_acoustic_boundaries(
        base,
        [[1, 120, 530], [2, 530, 860]],
        keyframes,
    ) == [[1, 120, 500], [2, 500, 900]]
    assert reconcile_acoustic_boundaries(
        base,
        [[1, 120, 510], [2, 510, 900]],
        keyframes,
    ) == [[1, 120, 510], [2, 510, 900]]
    assert reconcile_acoustic_boundaries(
        base,
        [[1, 181, 500], [2, 500, 900]],
        keyframes,
    ) == base
    assert reconcile_acoustic_boundaries(
        [[1, 480, 500], [2, 500, 510]],
        [[1, 480, 500], [2, 500, 510]],
        keyframes,
    ) == []


def test_accuracy_summary_uses_absolute_nearest_rank_errors():
    assert summarize_errors([-10, 20, 100]) == {
        "count": 3,
        "medianMs": 20,
        "p90Ms": 100,
        "within100Pct": 100.0,
    }
    conventions = summarize_clock_conventions([[110, 120, 130], [-10, 0, 10]])
    assert conventions["signedMedianMs"] == 60.0
    assert conventions["perRowSignedMedianMs"] == 60.0
    assert conventions["rowCentered"]["medianMs"] == 10.0


def test_v2_target_mapping_never_drops_unknown_letters():
    vocab = {"ا": 1, "ب": 2}
    assert _map_chars_to_ids("ابت", vocab) == [1, 2]
    assert _map_chars_to_ids("ابت", vocab, strict=True) is None
    assert _map_chars_to_ids("اب", vocab, strict=True) == [1, 2]


def test_phonemes_subdivide_only_their_rendered_letter_slot():
    assert spatial_progress([[0, 1], [], [2, 3, 4]]) == {
        0: 1 / 6,
        1: 1 / 3,
        2: 5 / 9,
        3: 7 / 9,
        4: 1.0,
    }
    assert phoneme_keyframes(
        [(1, 1), (2, 2), (5, 5)],
        (3,),
        ((0.25, 0.5, 1.0),),
        20.0,
    ) == [[[20, 0.0], [40, 0.25], [60, 0.5], [100, 0.5], [120, 1.0]]]
    assert token_error_rate([1, 2, 3], [1, 2, 3]) == 0.0
    assert token_error_rate([1, 2, 3], [1, 3]) == 1 / 3


def test_qua_clock_transfer_requires_a_unique_same_take_match():
    rng = __import__("numpy").random.default_rng(7)
    clip = rng.normal(size=8_000).astype("float32")
    source = __import__("numpy").concatenate(
        [rng.normal(size=2_000), clip, rng.normal(size=3_000)]
    ).astype("float32")
    match = match_audio_clock(source, clip, 8_000, 5_000)
    assert match is not None
    assert round(match.source_zero_ms) == 5_250
    assert match.correlation > 0.99
    assert accepted_clock(match)
    assert not accepted_clock(ClockMatch(0, 0.69, 1.0))
    assert not accepted_clock(ClockMatch(0, 1.0, 0.249))
    assert accepted_clock(ClockMatch(5_200, 1.0, 1.0), 5_000)
    assert not accepted_clock(ClockMatch(5_501, 1.0, 1.0), 5_000)


def test_qua_letters_preserve_repeats_and_rendered_slots():
    vocab = set("بسمٱلهرحٰنئاأ")
    rows = [[1, 100, 400], [1, 500, 800], [2, 800, 1_200]]
    letters = [
        [1, "ب", 100, 200], [1, "س", 200, 300], [1, "م", 300, 400],
        [1, "ب", 500, 600], [1, "س", 600, 700], [1, "م", 700, 800],
        [2, "ٱ", 800, 1_000], [2, "ل", 800, 1_000],
        [2, "ل", 800, 1_000], [2, "ه", 1_000, 1_200],
    ]
    chunks = occurrence_letters(rows, letters, {1: "بسم", 2: "ٱلله"}, vocab)
    assert chunks is not None
    assert ["".join(row[1] for row in chunk) for chunk in chunks] == [
        "بسم", "بسم", "ٱلله",
    ]
    assert source_groups("رَحْمَٰن", "رَحۡمَٰن", vocab) == [[0], [1], [2, 3], [4]]
    assert source_groups("لٔا", "لأ", vocab) == [[0], [1, 2]]


def test_qua_keyframes_animate_shared_and_silent_letters_without_fake_time():
    points = acoustic_keyframes(
        100,
        500,
        [
            [1, "ٱ", 100, 300],
            [1, "ل", 100, 300],
            [1, "ل", 100, 300],
            [1, "ه", 300, 500],
        ],
        [[0], [1], [2], [3]],
    )
    assert points == [
        {"offsetMs": 200, "progress": 0.75},
        {"offsetMs": 400, "progress": 1.0},
    ]
    assert acoustic_keyframes(
        100,
        500,
        [[1, "ن", 100, 500], [1, "ا", 500, 600]],
        [[0], [1]],
    ) == [{"offsetMs": 400, "progress": 1.0}]
    assert acoustic_keyframes(100, 500, [[1, "ا", 500, 600]], [[0]]) == []


def test_objective_flags_catch_past_duration_and_empty_spans():
    row = {
        "segments": [
            {
                "position": 1,
                "startMs": 0,
                "endMs": 500,
                "keyframes": [{"offsetMs": 250, "progress": 1.0}],
            },
            {
                "position": 2,
                "startMs": 500,
                "endMs": 1200,
                "keyframes": [{"offsetMs": 700, "progress": 1.0}],
            },
        ]
    }
    assert "past_duration" in objective_row_flags(row, duration_ms=1000)
    empty = {
        "segments": [
            {
                "position": 1,
                "startMs": 100,
                "endMs": 100,
                "keyframes": [{"offsetMs": 1, "progress": 1.0}],
            }
        ]
    }
    assert "empty_span" in objective_row_flags(empty, duration_ms=5000)


def test_repeat_detection_and_payload_coverage_separate_from_accuracy():
    mono = {
        "segments": [
            {"position": 1, "startMs": 0, "endMs": 100,
             "keyframes": [{"offsetMs": 50, "progress": 1.0}]},
            {"position": 2, "startMs": 100, "endMs": 200,
             "keyframes": [{"offsetMs": 50, "progress": 1.0}]},
        ]
    }
    rep = {
        "segments": [
            {"position": 1, "startMs": 0, "endMs": 100,
             "keyframes": [{"offsetMs": 50, "progress": 1.0}]},
            {"position": 1, "startMs": 100, "endMs": 200,
             "keyframes": [{"offsetMs": 50, "progress": 1.0}]},
            {"position": 2, "startMs": 200, "endMs": 300,
             "keyframes": [{"offsetMs": 50, "progress": 1.0}]},
        ]
    }
    assert not is_repeat_row(mono)
    assert is_repeat_row(rep)
    payload = {
        "reciter": "Alafasy_128kbps",
        "reciterId": 1,
        "rows": [
            {"surah": 1, "ayah": 1, "segments": mono["segments"]},
            {"surah": 1, "ayah": 2, "segments": rep["segments"]},
        ],
    }
    summary = summarize_payload(payload)
    assert summary["acceptedRows"] == 2
    assert summary["repeatRows"] == 1
    assert summary["coveragePctOfAlafasy"] == round(100 * 2 / 6236, 2)
    assert "note" in summary


def test_wilson_and_protocol_claim_shape():
    from eval_v2_against_labels import wilson_lower_bound

    # 99% of 1200 with 8 misses → LCB still around 98%+
    assert wilson_lower_bound(1192, 1200) > 0.98
    assert wilson_lower_bound(990, 1000) > 0.98
    assert wilson_lower_bound(50, 100) < 0.6


def test_historical_overlap_reports_structure_not_gold():
    payload = {
        "reciterId": 1,
        "rows": [
            {
                "surah": 2,
                "ayah": 14,
                "segments": [
                    {"position": 1, "startMs": 10, "endMs": 100,
                     "keyframes": [{"offsetMs": 50, "progress": 1.0}]},
                    {"position": 2, "startMs": 100, "endMs": 200,
                     "keyframes": [{"offsetMs": 50, "progress": 1.0}]},
                ],
            }
        ],
    }
    hist = {
        "edits": [
            {
                "reciterId": 1,
                "surahId": 2,
                "ayah": 14,
                "segments": [[1, 0, 90], [2, 90, 180]],
            },
            {
                "reciterId": 1,
                "surahId": 2,
                "ayah": 15,
                "segments": [[1, 0, 50]],
            },
        ]
    }
    path = __import__("pathlib").Path("/tmp/v2-hist-overlap-test.json")
    path.write_text(__import__("json").dumps(hist))
    got = historical_overlap(payload, path)
    assert got["overlapRows"] == 1
    assert got["structureExact"] == 1
    assert got["onsets"]["count"] == 2


if __name__ == "__main__":
    test_rebases_keyframes_inside_final_spans()
    test_preserves_acoustic_plateaus()
    test_rejects_incomplete_or_clamped_rows()
    test_refinements_cannot_cross_acoustic_evidence()
    test_accuracy_summary_uses_absolute_nearest_rank_errors()
    test_v2_target_mapping_never_drops_unknown_letters()
    test_phonemes_subdivide_only_their_rendered_letter_slot()
    test_qua_clock_transfer_requires_a_unique_same_take_match()
    test_qua_letters_preserve_repeats_and_rendered_slots()
    test_qua_keyframes_animate_shared_and_silent_letters_without_fake_time()
    test_objective_flags_catch_past_duration_and_empty_spans()
    test_repeat_detection_and_payload_coverage_separate_from_accuracy()
    test_wilson_and_protocol_claim_shape()
    test_historical_overlap_reports_structure_not_gold()
    print("timing V2 keyframe tests pass")

