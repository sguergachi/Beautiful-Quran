#!/usr/bin/env python3
"""Small deterministic tests for audit_forced_alignment's CTC core.

Run with either ``python3 tools/test_audit_forced_alignment.py`` or the qasr
virtualenv.  They do not load an acoustic model or touch quran.db.
"""

import math
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np
import torch

from audit_forced_alignment import (
    Occurrence,
    decode_waveform,
    forced_ctc_viterbi,
    normalize_for_model,
    occurrence_frame_evidence,
    target_labels,
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


def main():
    test_normalization()
    test_target_keeps_repeated_occurrences()
    test_viterbi_requires_blank_between_duplicate_labels()
    test_occurrence_evidence_does_not_merge_repeated_words()
    test_ffmpeg_decoder_uses_complete_float_pcm()
    print("all forced-alignment audit tests pass")


if __name__ == "__main__":
    main()
