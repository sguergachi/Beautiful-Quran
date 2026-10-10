"""Regression checks for private runtime export and reproducible occurrence hashes."""
import json
import sqlite3
import unittest
from unittest.mock import patch

import sync_qf_timings as sync


class TimingExportTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript("""
            CREATE TABLE reciters(id INTEGER,slug TEXT);
            INSERT INTO reciters VALUES(1,'Alafasy_128kbps');
            CREATE TABLE words(surah_id INTEGER,ayah_number INTEGER);
            INSERT INTO words VALUES(1,1),(1,1),(1,2);
            CREATE TABLE timings(reciter_id INTEGER,surah_id INTEGER,ayah_number INTEGER,segments TEXT,audio_onset_ms INTEGER);
        """)
        self.snapshot = {"resource_group": "chapter_recitations", "resource_id": 7, "schema_version": 1,
            "sync_sequence": 10, "records": [{"record_type": "audio_segment", "verse_key": "1:1",
                "audio_recitation_id": 7, "chapter_id": 1, "verse_number": 1,
                "timestamp_from": 100, "segments": [[1, 100, 200], [2, 200, 300], [1, 300, 400], [2, 400, 500]]}]}
        self.source = sync.source_hash(sync.snapshot_rows(self.snapshot, 7))
        self.state = {"sourceKey": "private-source", "syncedAtMs": 20, "deleted": False}

    def row(self, segments, onset=0):
        self.db.execute("INSERT INTO timings VALUES(1,1,1,?,?)", (json.dumps(segments), onset))

    def export(self):
        with patch.dict(sync.build.QF_SOURCE_SHA256, {7: self.source}):
            return sync.runtime_pack(self.db, 1, self.snapshot, self.state, "a" * 64)

    def test_export_preserves_every_repeat_occurrence_and_explicit_withholding(self):
        self.row([[1, 0, 100], [2, 100, 200], [1, 200, 300], [2, 300, 400]])
        metadata, payload = self.export()
        self.assertEqual([s[0] for s in payload["rows"][0][2]], [1, 2, 1, 2])
        self.assertEqual(payload["withheldVerseKeys"], ["1:2"])
        self.assertEqual(metadata["clock"], "everyayah-ms")
        self.assertEqual(metadata["sourceSha256"], self.source)
        self.assertNotIn("sourceKey", metadata)

    def test_revision_changes_with_onset_end_or_repeat_topology_but_not_sync_time(self):
        self.row([[1, 0, 100], [2, 100, 200]])
        before, _ = self.export()
        self.state["syncedAtMs"] = 30
        after, _ = self.export()
        self.assertEqual(before["revision"], after["revision"])
        self.db.execute("UPDATE timings SET segments=?", (json.dumps([[1, 0, 90], [2, 100, 200]]),))
        changed, _ = self.export()
        self.assertNotEqual(before["revision"], changed["revision"])

    def test_overlapping_clock_missing_positions_or_invalid_onset_cannot_publish(self):
        for segments, onset in (([[1, 0, 110], [2, 100, 200]], 0), ([[1, 0, 100]], 0), ([[1, 0, 100], [2, 100, 200]], 1)):
            with self.subTest(segments=segments, onset=onset):
                self.db.execute("DELETE FROM timings")
                self.row(segments, onset)
                with self.assertRaises(ValueError):
                    self.export()

    def test_unpinned_or_withdrawn_source_cannot_publish(self):
        self.row([[1, 0, 100], [2, 100, 200]])
        self.snapshot["records"][0]["segments"].pop()
        with self.assertRaises(ValueError):
            self.export()
        self.snapshot["records"][0]["segments"].append([2, 400, 500])
        self.state["deleted"] = True
        with self.assertRaises(ValueError):
            self.export()


if __name__ == "__main__":
    unittest.main()
