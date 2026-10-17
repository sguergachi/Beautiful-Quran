#!/usr/bin/env python3
"""Small deterministic tests for audit_forced_alignment's CTC core.

Run with either ``python3 tools/test_audit_forced_alignment.py`` or the qasr
virtualenv.  They do not load an acoustic model or touch quran.db.
"""

import math
import sqlite3
import json
import tempfile
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import numpy as np
import torch

from audit_forced_alignment import (
    Occurrence,
    blank_id_for,
    decode_waveform,
    forced_ctc_viterbi,
    force_one,
    normalize_for_mms,
    normalize_for_model,
    occurrence_frame_evidence,
    target_labels,
    main as audit_main,
)


def log_row(*probabilities):
    return torch.tensor(probabilities, dtype=torch.float32).log()


def test_normalization():
    assert normalize_for_model("بِسْمِ") == "بسم"
    assert normalize_for_model("ٱلرَّحْمَٰنِ") == "الرحمن"
    assert normalize_for_model("يَسْـَٔلُونَ") == "يسءلون"


def test_target_keeps_repeated_occurrences():
    vocabulary = {"ب": 1, "س": 2, "م": 3, "|": 4}
    occurrences = [
        Occurrence(1, "بسم", "بسم", 0, 100),
        Occurrence(1, "بسم", "بسم", 200, 300),
    ]
    labels, owners, transcript = target_labels(occurrences, vocabulary, "|")
    assert transcript == "بسم|بسم"
    assert labels == [1, 2, 3, 4, 1, 2, 3]
    assert owners == [0, 0, 0, None, 1, 1, 1]


def test_target_concatenates_when_delimiter_is_none():
    vocabulary = {"y": 1, "a": 2}
    occurrences = [
        Occurrence(1, "يا", "ya", 0, 100),
        Occurrence(2, "يا", "ya", 200, 300),
    ]
    labels, owners, transcript = target_labels(occurrences, vocabulary, None)
    assert transcript == "yaya"
    assert labels == [1, 2, 1, 2]
    assert owners == [0, 0, 1, 1]


def test_blank_id_prefers_named_blank_over_pad():
    tokenizer = SimpleNamespace(
        blank_token_id=None,
        blank_token="<blank>",
        pad_token_id=1,
        get_vocab=lambda: {"<blank>": 0, "<pad>": 1, "a": 4},
    )
    assert blank_id_for(tokenizer) == 0


def test_blank_id_falls_back_to_pad():
    tokenizer = SimpleNamespace(
        blank_token_id=None,
        blank_token=None,
        pad_token_id=1,
        get_vocab=lambda: {"<pad>": 1, "ا": 2},
    )
    assert blank_id_for(tokenizer) == 1


def test_mms_folds_maddah_before_romanizing():
    seen = {}

    class FakeRomanizer:
        def romanize_string(self, text, lcode=None):
            seen["text"] = text
            seen["lcode"] = lcode
            return "malaayikaa"

    assert normalize_for_mms("مَلَـٰٓئِكَةٌ", FakeRomanizer()) == "malaayikaa"
    assert seen["text"] == "ملائكة"
    assert seen["lcode"] == "ara"


def test_viterbi_requires_blank_between_duplicate_labels():
    # blank=0, target is a-a.  The only feasible high-probability path has a
    # blank between its two a labels, proving a duplicated target is preserved.
    probabilities = torch.stack((
        log_row(0.05, 0.94, 0.01),
        log_row(0.97, 0.02, 0.01),
        log_row(0.05, 0.94, 0.01),
        log_row(0.98, 0.01, 0.01),
    ))
    path, score = forced_ctc_viterbi(probabilities, [1, 1], blank_id=0)
    assert math.isfinite(score)
    # Expanded states [blank, a, blank, a, blank].
    assert path == [1, 2, 3, 4]


def test_occurrence_evidence_does_not_merge_repeated_words():
    probabilities = torch.stack((
        log_row(0.05, 0.94, 0.01),
        log_row(0.97, 0.02, 0.01),
        log_row(0.05, 0.94, 0.01),
        log_row(0.98, 0.01, 0.01),
    ))
    path, _ = forced_ctc_viterbi(probabilities, [1, 1], blank_id=0)
    evidence = occurrence_frame_evidence(
        probabilities, path, [1, 1], [0, 1], occurrence_count=2
    )
    assert evidence[0]["firstFrame"] == 0
    assert evidence[0]["lastFrameExclusive"] == 1
    assert evidence[1]["firstFrame"] == 2
    assert evidence[1]["lastFrameExclusive"] == 3


def test_ffmpeg_decoder_uses_complete_float_pcm():
    pcm = np.array([0.25, -0.5], dtype="<f4").tobytes()
    with patch(
        "audit_forced_alignment.subprocess.run",
        return_value=SimpleNamespace(returncode=0, stdout=pcm, stderr=b""),
    ) as run:
        waveform = decode_waveform(Path("sample.mp3"), 16_000)
    assert waveform.tolist() == [0.25, -0.5]
    assert run.call_args.args[0][-1] == "pipe:1"


def test_hypotheses_share_inference_but_keep_distinct_ctc_paths():
    class Processor:
        feature_extractor = SimpleNamespace(sampling_rate=16_000)
        tokenizer = SimpleNamespace(pad_token_id=0, word_delimiter_token="|",
                                    get_vocab=lambda: {"<pad>": 0, "ا": 1, "|": 2})

        def __call__(self, waveform, **kwargs):
            return SimpleNamespace(input_values=torch.zeros((1, len(waveform))))

    logits = torch.stack([log_row(.05, .9, .05), log_row(.9, .05, .05),
                          log_row(.05, .05, .9), log_row(.9, .05, .05),
                          log_row(.05, .9, .05), log_row(.9, .05, .05)])
    model = MagicMock(return_value=SimpleNamespace(logits=logits.unsqueeze(0)))
    db = sqlite3.connect(":memory:")
    db.executescript("""
        CREATE TABLE words(surah_id, ayah_number, position, arabic);
        CREATE TABLE timings(reciter_id, surah_id, ayah_number, segments);
        INSERT INTO words VALUES(1,1,1,'ا');
        INSERT INTO timings VALUES(1,1,1,'[[1,0,30],[1,30,60]]');
    """)
    cache = {}
    kwargs = dict(db=db, processor=Processor(), model=model, device=torch.device("cpu"),
                  qasr_root=Path("unused"), reciter_id=1, slug="fixture", surah=1, ayah=1,
                  min_label_probability=.15, max_residual_ms=250, include_alignment=True)
    with patch("audit_forced_alignment.audio_path", return_value=Path("fixture.mp3")), \
         patch("audit_forced_alignment.sha256", return_value="a" * 64), \
         patch("audit_forced_alignment.decode_waveform", return_value=np.zeros(960)) as decode:
        repeated = force_one(**kwargs, emission_cache=cache)
        db.execute("UPDATE timings SET segments='[[1,0,60]]'")
        single = force_one(**kwargs, emission_cache=cache)
        assert model.call_count == decode.call_count == 1
        assert repeated["occurrenceCount"] == 2 and single["occurrenceCount"] == 1
        assert repeated["viterbiLogProbabilityPerFrame"] != single["viterbiLogProbabilityPerFrame"]
        assert single["words"][0]["labelFrames"]
        uncached = force_one(**kwargs)
        assert uncached == single
        assert model.call_count == 2
    db.close()


def test_resume_preserves_selected_errors_without_retrying_recorded_rows():
    def row(ayah, error=False):
        return {
            "schema": 1,
            "kind": "ctc_fixed_sequence_audio_audit_error" if error else "ctc_fixed_sequence_audio_audit",
            "reciterId": 1, "surahId": 1, "ayah": ayah,
            **({"error": "FileNotFoundError: missing audio"} if error else {"reviewCount": 0}),
        }

    cases = [
        ("all-success", [row(1), row(2)], [], 0, 0, 0),
        ("recorded-error", [row(1, True), row(2)], [], 1, 0, 1),
        ("mixed-new-success", [row(1, True)], [], 1, 1, 1),
        ("unselected-error", [row(1), row(2, True)], ["--surah", "1", "--ayah", "1"], 0, 0, 0),
        ("unselected-error-new-success", [row(2, True)], ["--surah", "1", "--ayah", "1"], 0, 1, 0),
        ("error-outside-shard", [row(1, True), row(2)], ["--shard", "2/2"], 0, 0, 0),
    ]
    for name, reports, selection, expected_code, expected_new, expected_recorded_errors in cases:
        with tempfile.TemporaryDirectory(prefix=f"forced-audit-{name}-") as temporary:
            root = Path(temporary)
            database, report = root / "timings.db", root / "audit.jsonl"
            with sqlite3.connect(database) as db:
                db.executescript("""
                    CREATE TABLE reciters(id, slug, has_timings);
                    CREATE TABLE timings(reciter_id, surah_id, ayah_number);
                    INSERT INTO reciters VALUES(1, 'fixture', 1);
                    INSERT INTO timings VALUES(1, 1, 1), (1, 1, 2);
                """)
            original = "".join(json.dumps(item) + "\n" for item in reports)
            report.write_text(original)
            output = StringIO()
            with patch("audit_forced_alignment.load_model", return_value=(None, None, None)) as load, \
                 patch("audit_forced_alignment.force_one", side_effect=lambda **kwargs: row(kwargs["ayah"])) as force, \
                 redirect_stdout(output):
                code = audit_main([
                    "--db", str(database), "--reciter-id", "1", "--output", str(report), "--resume",
                    *selection,
                ])
            assert code == expected_code, (name, output.getvalue())
            assert force.call_count == expected_new, name
            assert load.call_count == bool(expected_new), name
            assert f"recorded_errors={expected_recorded_errors}" in output.getvalue(), name
            assert report.read_text().startswith(original), name
            assert len(report.read_text().splitlines()) == len(reports) + expected_new, name


def main():
    test_normalization()
    test_target_keeps_repeated_occurrences()
    test_target_concatenates_when_delimiter_is_none()
    test_blank_id_prefers_named_blank_over_pad()
    test_blank_id_falls_back_to_pad()
    test_mms_folds_maddah_before_romanizing()
    test_viterbi_requires_blank_between_duplicate_labels()
    test_occurrence_evidence_does_not_merge_repeated_words()
    test_ffmpeg_decoder_uses_complete_float_pcm()
    test_hypotheses_share_inference_but_keep_distinct_ctc_paths()
    test_resume_preserves_selected_errors_without_retrying_recorded_rows()
    print("all forced-alignment audit tests pass")


if __name__ == "__main__":
    main()
