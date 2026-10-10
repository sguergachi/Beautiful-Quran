"""Offline regressions for the authenticated timing pipeline's release guards."""

import contextlib
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import build_db as build
from qf_timing_parity import source_hash
from timing_delta import build_delta, read_timing_rows


class AuthenticatedTimingBuildTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.directory = Path(self.scratch.name)

    def database(self, name, segments, onset=0):
        path = self.directory / name
        with contextlib.closing(sqlite3.connect(path)) as db:
            db.executescript("""
                CREATE TABLE reciters (id INTEGER, slug TEXT);
                CREATE TABLE timings (reciter_id INTEGER, surah_id INTEGER,
                    ayah_number INTEGER, segments TEXT, audio_onset_ms INTEGER);
                INSERT INTO reciters VALUES (1, 'Alafasy_128kbps');
            """)
            if segments is not None:
                db.execute("INSERT INTO timings VALUES (1, 1, 1, ?, ?)",
                           (json.dumps(segments), onset))
            db.commit()
        return path

    def ledger(self, before, after, verdict="hold"):
        delta = build_delta(read_timing_rows(before), read_timing_rows(after))["changes"][0]
        topology = "topology" in delta["kinds"]
        entry = {
            "verdict": verdict, "kinds": delta["kinds"],
            "baselinePayloadHash": delta["old"]["payloadHash"] if delta["old"] else None,
            "candidatePayloadHash": delta["new"]["payloadHash"] if delta["new"] else None,
            "reason": "The independent witnesses rejected this candidate",
            "evidence": {
                "kind": "dual_model_topology" if topology or delta["old"] is None else "dual_model_boundary",
                "summary": "Both independent model witnesses improve",
                "artifact": "unit fixture", "audioSha256": "a" * 64,
                "waveformVeto": False,
                "waveform": {"decodedDurationMs": 5000, "durationToleranceMs": 50,
                             "boundsWithinTolerance": True, "voicedEndToleranceMs": 250,
                             "voicedTailCutRisks": []},
                "models": [{"name": name,
                            "metric": "viterbiLogProbabilityPerFrame" if topology else "meanAbsStartResidualMs",
                            "baselineScore": -100, "candidateScore": -50,
                            "baselineResidualMs": 100, "candidateResidualMs": 50,
                            "newOccurrenceMinLabelProbability": 0.8}
                           for name in ("arabic-xlsr-ctc", "mms-uroman-ctc")],
            },
        }
        path = self.directory / "verdicts.json"
        path.write_text(json.dumps({
            "schemaVersion": 1, "sourceProfile": "authenticated",
            "baselineDbSha256": build.QF_TIMING_BASELINE_SHA256,
            "sourceHashes": {str(key): value for key, value in build.QF_SOURCE_SHA256.items()},
            "verdicts": {delta["key"]: entry},
        }))
        return path

    def test_rejects_snapshot_drift_without_changing_legacy_lock(self):
        snapshot = {
            "resource_group": "chapter_recitations", "resource_id": 7, "schema_version": 1,
            "records": [{"record_type": "audio_segment", "verse_key": "1:1",
                         "audio_recitation_id": 7, "chapter_id": 1, "verse_number": 1,
                         "timestamp_from": 1000, "segments": [[1, 1000, 2000], [1, 2000, 3000]]}],
        }
        path = self.directory / "snapshot-7.json"
        path.write_text(json.dumps(snapshot))
        expected = source_hash({"1:1": [[1, 0, 1000], [1, 1000, 2000]]})
        legacy = dict(build.QDC_SOURCE_SHA256)
        with patch.object(build, "QF_SOURCE_SHA256", {7: expected}):
            self.assertEqual(build.load_qf_timings(self.directory, 7)[(1, 1)],
                             [[1, 0, 1000], [1, 1000, 2000]])
            snapshot["records"][0]["segments"].pop()
            path.write_text(json.dumps(snapshot))
            with self.assertRaises(SystemExit):
                build.load_qf_timings(self.directory, 7)
        self.assertEqual(build.QDC_SOURCE_SHA256, legacy)

    def test_retirement_is_profile_scoped_and_bound_to_exact_input(self):
        row = [[1, 0, 1000], [1, 1000, 2000], [2, 2000, 3000]]
        digest = hashlib.sha256(json.dumps(row, separators=(",", ":")).encode()).hexdigest()
        edit = {"authenticatedRetirement": {"inputSegmentsSha256": digest, "evidence": "audio verdict"}}
        self.assertFalse(build.retire_authenticated_edit(edit, row, "legacy"))
        self.assertTrue(build.retire_authenticated_edit(edit, row, "authenticated"))
        with self.assertRaises(ValueError):
            build.retire_authenticated_edit(edit, [[1, 0, 2000], [2, 2000, 3000]], "authenticated")
        with self.assertRaises(ValueError):
            build.retire_authenticated_edit(edit, row, "unknown")

    def test_authentication_cannot_overwrite_release_asset_or_baseline(self):
        for output in (build.OUT, self.directory / "baseline.db"):
            with self.subTest(output=output), patch.object(build, "fetch") as fetch:
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    build.main(["--qf-snapshots", str(self.directory), "--output", str(output),
                                "--timing-baseline", str(self.directory / "baseline.db")])
                fetch.assert_not_called()

    def test_legacy_and_flattened_audits_cannot_overwrite_a_release_asset(self):
        for mode in ("--refresh-qdc-timings", "--quran-align-only"):
            for output in ([], ["--output", str(build.OUT)], ["--output", str(build.ROOT)]):
                with self.subTest(mode=mode, output=output), patch.object(build, "fetch") as fetch:
                    with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                        build.main([mode, *output])
                    fetch.assert_not_called()

    def test_rejected_repeat_flattening_restores_topology_and_onset(self):
        original = [[1, 0, 1000], [2, 1000, 2000], [1, 2000, 3000], [2, 3000, 4000]]
        flattened = [[1, 0, 2000], [2, 2000, 4000]]
        before = self.database("before.db", original, 90)
        after = self.database("after.db", flattened, 0)
        with patch.object(build, "QF_VERDICTS_FILE", self.ledger(before, after)):
            rows, onsets = build.apply_authenticated_verdicts([(1, 1, 1, flattened)], {}, before)
        self.assertEqual(json.loads(rows[0][3]), original)
        self.assertEqual(onsets, {(1, 1, 1): 90})

    def test_rejected_addition_remains_withheld(self):
        new = [[1, 0, 1000]]
        before = self.database("before.db", None)
        after = self.database("after.db", new)
        with patch.object(build, "QF_VERDICTS_FILE", self.ledger(before, after)):
            self.assertEqual(build.apply_authenticated_verdicts([(1, 1, 1, new)], {}, before), ([], {}))

    def test_unknown_or_stale_delta_fails_closed(self):
        old, new = [[1, 0, 1000]], [[1, 20, 1000]]
        before = self.database("before.db", old)
        after = self.database("after.db", new)
        ledger = self.ledger(before, after)
        with patch.object(build, "QF_VERDICTS_FILE", ledger), self.assertRaises(SystemExit):
            build.apply_authenticated_verdicts([(1, 1, 1, [[1, 30, 1000]])], {}, before)
        ledger.write_text('{"verdicts": {}}')
        with patch.object(build, "QF_VERDICTS_FILE", ledger), self.assertRaises(SystemExit):
            build.apply_authenticated_verdicts([(1, 1, 1, new)], {}, before)

    def test_acceptance_requires_nonregression_on_both_independent_models(self):
        old, new = [[1, 0, 1000]], [[1, 20, 1000]]
        before = self.database("before.db", old)
        after = self.database("after.db", new)
        ledger = self.ledger(before, after, "accept")
        with patch.object(build, "QF_VERDICTS_FILE", ledger):
            rows, _ = build.apply_authenticated_verdicts([(1, 1, 1, new)], {}, before)
            self.assertEqual(json.loads(rows[0][3]), new)
            payload = json.loads(ledger.read_text())
            entry = next(iter(payload["verdicts"].values()))
            entry["evidence"]["models"][1]["candidateScore"] = -101
            ledger.write_text(json.dumps(payload))
            with self.assertRaises(SystemExit):
                build.apply_authenticated_verdicts([(1, 1, 1, new)], {}, before)

    def test_acceptance_requires_strong_topology_and_new_occurrence_witnesses(self):
        old = [[1, 0, 500], [2, 500, 1000]]
        new = [[1, 0, 400], [1, 500, 700], [2, 750, 1000]]
        before, after = self.database("before.db", old), self.database("after.db", new)
        ledger = self.ledger(before, after, "accept")
        payload = json.loads(ledger.read_text())
        model = next(iter(payload["verdicts"].values()))["evidence"]["models"][1]
        original = dict(model)
        failures = (
            {"candidateScore": -99.9995},
            {"newOccurrenceMinLabelProbability": 0.49},
            {"newOccurrenceMinLabelProbability": None},
            {"metric": "meanAbsStartResidualMs"},
        )
        for failure in failures:
            with self.subTest(failure=failure), patch.object(build, "QF_VERDICTS_FILE", ledger):
                model.clear()
                model.update(original | failure)
                ledger.write_text(json.dumps(payload))
                with self.assertRaises(SystemExit):
                    build.apply_authenticated_verdicts([(1, 1, 1, new)], {}, before)

    def test_end_only_and_unmeasured_waveform_changes_cannot_be_accepted(self):
        old = [[1, 0, 1000]]
        before = self.database("before.db", old)
        for new in ([[1, 0, 900]], [[1, 20, 1000]]):
            after = self.database(f"after-{new[0][1]}.db", new)
            ledger = self.ledger(before, after, "accept")
            payload = json.loads(ledger.read_text())
            if new[0][1] != 0:
                entry = next(iter(payload["verdicts"].values()))
                entry["evidence"]["waveform"]["decodedDurationMs"] = 800
                ledger.write_text(json.dumps(payload))
            with self.subTest(new=new), patch.object(build, "QF_VERDICTS_FILE", ledger):
                with self.assertRaises(SystemExit):
                    build.apply_authenticated_verdicts([(1, 1, 1, new)], {}, before)

    def test_verdict_ledger_cannot_move_to_another_source_profile(self):
        old, new = [[1, 0, 1000]], [[1, 20, 1000]]
        before, after = self.database("before.db", old), self.database("after.db", new)
        ledger = self.ledger(before, after, "accept")
        original = json.loads(ledger.read_text())
        for field, value in (("sourceProfile", "legacy"), ("sourceHashes", {}),
                             ("baselineDbSha256", "b" * 64)):
            ledger.write_text(json.dumps(original | {field: value}))
            with self.subTest(field=field), patch.object(build, "QF_VERDICTS_FILE", ledger):
                with self.assertRaises(SystemExit):
                    build.apply_authenticated_verdicts([(1, 1, 1, new)], {}, before)


if __name__ == "__main__":
    unittest.main()
