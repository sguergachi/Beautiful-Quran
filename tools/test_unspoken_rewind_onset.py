"""Exact-source and topology guards for audio-vetted pre-rewind removals."""

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from build_db import apply_timing_corrections, discard_unspoken_rewind_onset


class UnspokenRewindOnsetTest(unittest.TestCase):
    def setUp(self):
        self.case = json.loads((Path(__file__).parent / "timing_patch_cases" /
                                "audio-vetted-rewind-onset-alafasy-5-2.json").read_text())
        self.segs = self.case["input_segments"]
        self.window = self.case["source_window"]

    def test_removes_only_the_verified_occurrence_without_mutating_input(self):
        original = json.dumps(self.segs)
        self.assertEqual(discard_unspoken_rewind_onset(self.segs, self.window, True),
                         self.case["expected_segments"])
        self.assertEqual(json.dumps(self.segs), original)

    def test_requires_an_audio_verdict(self):
        with self.assertRaisesRegex(ValueError, "audio verdict"):
            discard_unspoken_rewind_onset(self.segs, self.window)

    def test_rejects_any_source_window_drift(self):
        for row in range(3):
            for column in range(3):
                changed = [list(seg) for seg in self.window]
                changed[row][column] += 1
                with self.subTest(row=row, column=column), self.assertRaises(ValueError):
                    discard_unspoken_rewind_onset(self.segs, changed, True)

    def test_requires_a_later_real_occurrence(self):
        with self.assertRaisesRegex(ValueError, "recited later"):
            discard_unspoken_rewind_onset(self.segs[:-1], self.window, True)

    def test_rejects_a_word_that_is_already_a_repeat(self):
        with self.assertRaisesRegex(ValueError, "new word"):
            discard_unspoken_rewind_onset([[15, 0, 100], *self.segs], self.window, True)

    def test_requires_the_preceding_word_and_a_rewind(self):
        for row, position in ((1, 12), (3, 16)):
            changed = [list(seg) for seg in self.segs]
            changed[row][0] = position
            with self.subTest(row=row), self.assertRaisesRegex(ValueError, "new word"):
                discard_unspoken_rewind_onset(changed, changed[1:4], True)

    def test_rejects_ambiguous_or_incomplete_windows(self):
        for segs, window in ((self.segs * 2, self.window), (self.segs, self.window[:2])):
            with self.subTest(window=window), self.assertRaises(ValueError):
                discard_unspoken_rewind_onset(segs, window, True)

    def test_dispatches_on_both_profiles_and_preserves_unrelated_rows(self):
        correction = next(edit for edit in json.loads((Path(__file__).parent /
                          "timing_corrections/Alafasy_128kbps.json").read_text())["edits"]
                          if edit["op"] == "discard_unspoken_rewind_onset")
        untouched = [[13, 16565, 17820], [13, 17820, 19000], [14, 19000, 19650]]
        rows = [(1, 5, 2, json.dumps(self.segs)), (1, 47, 4, json.dumps(untouched))]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "correction.json").write_text(json.dumps({"edits": [correction]}))
            for profile in ("legacy", "authenticated"):
                with self.subTest(profile=profile), contextlib.redirect_stdout(io.StringIO()):
                    out = apply_timing_corrections(rows, corrections_dir=path, source_profile=profile)
                self.assertEqual(json.loads(out[0][3]), self.case["expected_segments"])
                self.assertEqual(json.loads(out[1][3]), untouched)


if __name__ == "__main__":
    unittest.main()
