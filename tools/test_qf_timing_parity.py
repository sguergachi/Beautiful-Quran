#!/usr/bin/env python3
"""Regression checks for authenticated repeat-source parity; no network or DB writes."""

from copy import deepcopy
from contextlib import redirect_stderr, redirect_stdout
import hashlib
from io import StringIO
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from qf_timing_parity import QDC_REPEAT_RECITERS, compare_sources, main, snapshot_rows, source_hash


def snapshot(qid=7):
    return {
        "resource_group": "chapter_recitations", "resource_id": qid,
        "schema_version": 1, "sync_sequence": 1,
        "records": [{
            "record_type": "audio_segment", "audio_recitation_id": qid,
            "chapter_id": 2, "verse_number": 14, "verse_key": "2:14",
            "timestamp_from": 1000,
            "segments": [[1, 1000.0, 1100.0], [2, 1100, 1200], [1, 1200, 1300],
                         [2, 1300, 1400], [2, 1400, 1500], [3, 1500, 1600]],
        }],
    }


class QfTimingParityTest(unittest.TestCase):
    def test_chapter_clock_rebase_preserves_span_and_same_word_repeats(self):
        self.assertEqual(snapshot_rows(snapshot(), 7), {
            "2:14": [[1, 0, 100], [2, 100, 200], [1, 200, 300],
                     [2, 300, 400], [2, 400, 500], [3, 500, 600]],
        })

    def test_flattened_repeat_is_blocked_even_with_all_words_present(self):
        baseline = snapshot_rows(snapshot(), 7)
        candidate = {"2:14": [baseline["2:14"][i] for i in [0, 1, 5]]}
        result = compare_sources(baseline, candidate)
        self.assertFalse(result["matchesPinnedSource"])
        self.assertEqual(result["changes"]["topology"], ["2:14"])
        self.assertEqual(result["baselineRepeatOccurrences"], 3)
        self.assertEqual(result["candidateRepeatOccurrences"], 0)

    def test_shifted_clock_missing_and_added_rows_are_blocked(self):
        baseline = {"1:1": [[1, 0, 100]], "1:2": [[1, 0, 100]]}
        candidate = {"1:1": [[1, 5, 105]], "1:3": [[1, 0, 100]]}
        result = compare_sources(baseline, candidate)
        self.assertEqual(result["changedRows"], 3)
        self.assertEqual(result["changes"], {
            "timestamp": ["1:1"], "missing": ["1:2"], "added": ["1:3"], "topology": [],
        })

    def test_null_source_segments_are_missing_not_an_invented_monotonic_row(self):
        value = snapshot()
        value["records"][0]["segments"] = None
        self.assertEqual(snapshot_rows(value, 7), {})

    def test_unfinished_labels_and_extra_fields_match_the_legacy_loader(self):
        value = snapshot()
        value["records"][0]["segments"] = [[1], [2, 1000], [3, 1100, 1200, 99]]
        self.assertEqual(snapshot_rows(value, 7), {"2:14": [[3, 100, 200]]})
        value["records"][0]["segments"] = [[1], [2, 1000]]
        self.assertEqual(snapshot_rows(value, 7), {})
        self.assertEqual(compare_sources({"2:14": [[1, 0, 100]]}, {})["changes"]["missing"], ["2:14"])

    def test_duplicate_verse_cannot_silently_overwrite_a_repeat(self):
        value = snapshot()
        value["records"].append(deepcopy(value["records"][0]))
        value["records"][1]["segments"] = []
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            snapshot_rows(value, 7)

    def test_wrong_reciter_owner_schema_and_malformed_tuples_fail_closed(self):
        changes = [
            ("resource_id", 9), ("schema_version", 2),
            ("audio_recitation_id", 9), ("verse_number", 15),
            ("segments", ["invalid"]), ("segments", [[True, 1000, 1100]]),
            ("segments", [[1.5, 1000, 1100]]), ("segments", [[1, float("nan"), 1100]]),
        ]
        for key, value in changes:
            with self.subTest(key=key, value=value):
                body = snapshot()
                target = body if key in ["resource_id", "schema_version"] else body["records"][0]
                target[key] = value
                with self.assertRaises(ValueError):
                    snapshot_rows(body, 7)

    def test_hash_uses_numeric_verse_order(self):
        rows = {"2:10": [[1, 0, 100]], "2:2": [[1, 0, 100]]}
        expected = json.dumps({"2:2": rows["2:2"], "2:10": rows["2:10"]}, separators=(",", ":"))
        self.assertEqual(source_hash(rows), hashlib.sha256(expected.encode()).hexdigest())

    def test_cli_returns_failure_for_source_change_or_tampered_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            reference = json.dumps(snapshot_rows(snapshot(), 7), separators=(",", ":")).encode()
            pins = {qid: hashlib.sha256(reference).hexdigest() for qid in QDC_REPEAT_RECITERS.values()}
            for qid in pins:
                (directory / f"qdc_{qid}.json").write_bytes(reference)
                (directory / f"snapshot-{qid}.json").write_text(json.dumps(snapshot(qid)))
            args = ["--snapshots", str(directory), "--qdc-cache", str(directory)]
            with patch("qf_timing_parity.QDC_SOURCE_SHA256", pins), \
                    redirect_stdout(StringIO()), redirect_stderr(StringIO()):
                self.assertEqual(main(args), 0)
                changed = snapshot(7)
                changed["records"][0]["segments"][0][2] += 1
                (directory / "snapshot-7.json").write_text(json.dumps(changed))
                self.assertEqual(main(args), 1)
                (directory / "qdc_7.json").write_text("{}")
                self.assertEqual(main(args), 2)


if __name__ == "__main__":
    unittest.main()
