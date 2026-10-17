"""Reviewed split merges preserve repairs, clocks and genuine peer repeats."""

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import build_db as build


class VerifiedWordSplitTest(unittest.TestCase):
    def setUp(self):
        self.case = json.loads((Path(__file__).parent / "timing_patch_cases" /
                               "verified-word-split-alafasy-47-4.json").read_text())
        self.segs = self.case["input_segments"]
        self.pair = self.case["source_pair"]

    def test_one_full_span_and_no_input_mutation(self):
        original = json.dumps(self.segs)
        self.assertEqual(build.merge_verified_word_split(self.segs, self.pair, True),
                         self.case["expected_segments"])
        self.assertEqual(json.dumps(self.segs), original)

    def test_requires_an_audio_verdict(self):
        with self.assertRaisesRegex(ValueError, "audio verdict"):
            build.merge_verified_word_split(self.segs, self.pair)

    def test_rejects_source_drift_or_ambiguity(self):
        for row in range(2):
            for column in range(3):
                changed = [list(seg) for seg in self.pair]
                changed[row][column] += 1
                with self.subTest(row=row, column=column), self.assertRaises(ValueError):
                    build.merge_verified_word_split(self.segs, changed, True)
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            build.merge_verified_word_split(self.segs * 2, self.pair, True)

    def test_rejects_different_positions_gaps_and_incomplete_pairs(self):
        for pair in ([[13, 0, 100], [14, 100, 200]],
                     [[13, 0, 100], [13, 110, 200]], self.pair[:1]):
            with self.subTest(pair=pair), self.assertRaises(ValueError):
                build.merge_verified_word_split(pair, pair, True)

    def test_merge_runs_after_peer_protection_without_moving_neighbours(self):
        edits = json.loads((Path(__file__).parent /
                            "timing_corrections/generated-word-splits.json").read_text())["edits"]
        correction = next(e for e in edits if e["reciterId"] == 1 and e["surahId"] == 47 and e["ayah"] == 4)
        source = [[12, 14510, 16460], [13, 16460, 17820], [13, 17820, 19000],
                  [14, 19000, 19650], [15, 19650, 21360]]
        repair = {"reciterId": 1, "surahId": 47, "ayah": 4, "kind": "drop",
                  "segments": self.case["expected_segments"]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            corrections, repairs = path / "corrections", path / "repairs"
            corrections.mkdir()
            repairs.mkdir()
            (corrections / "entry.json").write_text(json.dumps({"edits": [correction]}))
            (repairs / "entry.json").write_text(json.dumps({"edits": [repair]}))
            for profile in ("legacy", "authenticated"):
                with self.subTest(profile=profile), contextlib.redirect_stdout(io.StringIO()):
                    rows = build.apply_timing_corrections([(1, 47, 4, json.dumps(source))],
                             corrections_dir=corrections, source_profile=profile)
                    self.assertEqual(json.loads(rows[0][3]), source)
                    with patch.object(build, "REPAIRS_DIR", repairs):
                        rows = build.apply_timing_repairs(rows, {(47, 4): 38}, source_profile=profile)
                    self.assertEqual(json.loads(rows[0][3]), self.segs)
                    rows = build.apply_timing_corrections(rows, corrections_dir=corrections,
                             source_profile=profile, phase="after_repairs")
                    self.assertEqual(json.loads(rows[0][3]), self.case["expected_segments"])

    def test_unknown_phases_fail_instead_of_silently_skipping_corrections(self):
        with self.assertRaises(ValueError):
            build.apply_timing_corrections([], phase="after_repair")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "entry.json").write_text(json.dumps({"edits": [{"phase": "typo"}]}))
            with self.assertRaises(SystemExit):
                build.apply_timing_corrections([], corrections_dir=path)


if __name__ == "__main__":
    unittest.main()
