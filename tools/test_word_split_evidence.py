"""Conservative acoustic ownership and full-corpus enumeration regressions."""
import copy
import json
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import hashlib
import io
import contextlib
import gzip

from audit_word_splits import (MODELS, capture, generated_corrections, hypothesis, inventory,
                               issue_inventory, read_reports, read_rows, rewind_inventory, validate_report)
import build_db as build
from word_split_evidence import frame_ownership, merge_index, merge_pair, ownership_problem


class WordSplitEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads((Path(__file__).parent / "timing_evidence" /
                                   "word-split-calibration.json").read_text())
        self.before = self.fixture["beforeSegments"]
        self.after = self.fixture["afterSegments"]
        self.witness = copy.deepcopy(self.fixture["models"][0])

    def test_actual_xlsr_confident_label_is_retained_acoustic_speech(self):
        self.assertGreater(self.witness["removedOccurrenceMaxLabelProbability"], .5)
        self.assertIsNone(ownership_problem(self.before, self.after, self.witness))
        self.assertAlmostEqual(self.witness["sameWordSplit"]["unexplainedMaxLabelProbability"],
                               .33929967880249023)

    def test_actual_mms_independent_frame_still_fails_the_exception(self):
        mms = self.fixture["models"][1]
        self.assertLess(mms["removedOccurrenceMaxLabelProbability"], .5)
        self.assertIn("confident emitting frame", ownership_problem(self.before, self.after, mms))

    def test_confident_unexplained_speech_cannot_be_hidden_by_a_declared_zero(self):
        proof = self.witness["sameWordSplit"]
        novel_frame = proof["unexplainedFrames"][0][0]
        for word in proof["baselineWords"]:
            for sample in word["labelFrames"]:
                if sample[0] == novel_frame:
                    sample[2] = .99
        proof["unexplainedMaxLabelProbability"] = 0
        self.assertIsNotNone(ownership_problem(self.before, self.after, self.witness))

    def test_additional_boundary_changes_and_stale_source_fail_closed(self):
        for index, field in [(0, 1), (12, 2), (13, 1)]:
            changed = copy.deepcopy(self.after)
            changed[index][field] += 1
            self.assertIsNotNone(ownership_problem(self.before, changed, self.witness))

    def test_missing_duplicate_overlapping_and_invalid_frames_fail_closed(self):
        for invalid in ["missing", "duplicate", "overlap", "nan", "negative"]:
            witness = copy.deepcopy(self.witness)
            pair = witness["sameWordSplit"]["baselineWords"]
            frames = pair[0]["labelFrames"]
            if invalid == "missing":
                pair[0].pop("labelFrames")
            elif invalid == "duplicate":
                frames.append(frames[0])
            elif invalid == "overlap":
                pair[1]["labelFrames"][0][0] = frames[0][0]
            elif invalid == "nan":
                frames[0][2] = float("nan")
            else:
                frames[0][0] = -1
            with self.subTest(invalid=invalid):
                self.assertIsNotNone(ownership_problem(self.before, self.after, witness))

    def test_confidence_and_shared_emission_probabilities_must_match_raw_frames(self):
        witness = copy.deepcopy(self.witness)
        word = witness["sameWordSplit"]["baselineWords"][0]
        word["meanLabelProbability"] = .75
        self.assertIn("confidence does not match",ownership_problem(self.before,self.after,witness))
        witness = copy.deepcopy(self.witness)
        old = {(f,t) for w in witness["sameWordSplit"]["baselineWords"] for f,t,p in w["labelFrames"]}
        word = next(w for w in witness["sameWordSplit"]["candidateWords"]
                    if any((f,t) in old for f,t,p in w["labelFrames"]))
        sample = next(v for v in word["labelFrames"] if tuple(v[:2]) in old)
        sample[2] *= .9
        word["meanLabelProbability"] = sum(v[2] for v in word["labelFrames"]) / len(word["labelFrames"])
        self.assertIn("inconsistent neural emissions",ownership_problem(self.before,self.after,witness))

    def test_candidate_confidence_and_onset_are_not_optional(self):
        for field, value in [("meanLabelProbability", .49), ("meanLabelProbability", float("nan")),
                             ("startResidualMs", 251), ("startResidualMs", float("inf"))]:
            witness = copy.deepcopy(self.witness)
            witness["sameWordSplit"]["candidateWords"][1][field] = value
            with self.subTest(field=field, value=value):
                self.assertIsNotNone(ownership_problem(self.before, self.after, witness))

    def test_paused_repeats_cannot_use_the_exception(self):
        before = copy.deepcopy(self.before)
        before[13][1] += 300
        self.assertIsNone(merge_index(before, hypothesis(before, 12)))
        with self.assertRaises(ValueError):
            merge_pair(before, 12)

    def test_inventory_keeps_all_reciters_chains_pauses_and_owned_rows(self):
        rows = {(1, 1, 1): ([[1, 0, 100], [1, 100, 200], [1, 500, 700]], 0),
                (9, 2, 3): ([[1, 0, 100], [1, 100, 200]], 50),
                (19, 3, 4): ([[1, 0, 100], [2, 100, 200]], 0)}
        found = inventory(rows)
        self.assertEqual(found, json.loads(json.dumps(found)))
        self.assertEqual(found["rowCount"], 3)
        self.assertEqual([c["id"] for c in found["candidates"]], ["1:1:1:0", "1:1:1:1", "9:2:3:0"])
        rows[1, 1, 1][0][0][1] += 1
        self.assertNotEqual(found["candidates"][0]["baselinePayloadHash"],
                            inventory(rows)["candidates"][0]["baselinePayloadHash"])

    def test_incomplete_reviews_cannot_generate_a_one_row_shortcut(self):
        rows = {(1, 47, 4): (self.before, 0)}
        item = inventory(rows)["candidates"][0]
        reviewed = {"complete": False, "rowCount": 1, "candidateCount": 1,
                    "corpusSha256": inventory(rows)["corpusSha256"],
                    "decisions": [{**item, "decision": "repair", "reason": "actual dual-model calibration",
                        "models": self.fixture["models"], "audioSha256": "b" * 64,
                        "audioDurationMs": 49821, "sourceReplay": {
                        "pairPresent": True, "outputMatches": True}}]}
        with self.assertRaisesRegex(ValueError, "complete corpus"):
            generated_corrections(reviewed, rows)
        reviewed["complete"] = True
        edit = generated_corrections(reviewed, rows)["edits"][0]
        self.assertEqual(edit["sourcePair"], item["sourcePair"])
        self.assertEqual(edit["generator"], "tools/audit_word_splits.py")
        reviewed["decisions"][0]["sourceReplay"]["outputMatches"] = False
        with self.assertRaisesRegex(ValueError, "exact source replay"):
            generated_corrections(reviewed, rows)
        rows[1, 47, 4][0][0][1] += 1
        with self.assertRaisesRegex(ValueError, "coverage changed"):
            generated_corrections(reviewed, rows)

    def test_review_rejects_swapped_models_frames_and_unrelated_boundaries(self):
        item = inventory({(1,47,4): (self.before,0)})["candidates"][0]
        row = {**item, "modelTag": "xlsr", "modelRevision": "a" * 40}
        for label, segments in [("baseline", self.before), ("candidate", self.after)]:
            row[label] = {"model": MODELS["xlsr"], "reciterId": 1, "surahId": 47, "ayah": 4,
                "occurrenceCount": len(segments), "viterbiLogProbabilityPerFrame": -.5,
                "words": [{"position": p, "storedStartMs": s, "storedEndMs": e,
                           "meanLabelProbability": .8, "forcedStartMs":s, "startResidualMs":0} for p,s,e in segments],
                "audioSha256": "b" * 64, "audioDurationMs": 49821, "ctcFrames": 2490, "ctcFrameMs": 20.0}
        validate_report(row, item, self.before, self.after, "xlsr")
        for invalid in ["model", "frames", "boundary", "provenance", "nan"]:
            changed = copy.deepcopy(row)
            if invalid == "model":
                changed["candidate"]["model"] = MODELS["mms"]
            elif invalid == "frames":
                changed["candidate"]["ctcFrames"] += 1
            elif invalid == "boundary":
                changed["baseline"]["words"][0]["storedStartMs"] += 1
            elif invalid == "provenance":
                changed["modelRevision"] = "unknown"
            else:
                changed["candidate"]["words"][0]["startResidualMs"] = float("nan")
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                validate_report(changed, item, self.before, self.after, "xlsr")

    def test_capture_includes_runtime_voices_and_preserves_independent_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            base, output = path / "base.db", path / "live.db"
            with sqlite3.connect(base) as db:
                self.addCleanup(db.close)
                db.executescript("""
                    CREATE TABLE reciters(id,slug,has_timings);
                    INSERT INTO reciters VALUES(1,'test',1),(9,'independent',1);
                    CREATE TABLE words(surah_id,ayah_number,position);
                    INSERT INTO words VALUES(1,1,1);
                    CREATE TABLE timings(reciter_id,surah_id,ayah_number,segments,audio_onset_ms);
                    INSERT INTO timings VALUES(9,1,1,'[[1,0,500]]',0);
                """)
                with self.assertRaisesRegex(ValueError, "missing delivered reciters"):
                    read_rows(db)
            body = {"rows": [[1,1,[[1,20,300],[1,300,500]],0]], "withheldVerseKeys": []}
            resource = {**body, "schemaVersion": 1, "reciterId": 1, "qfReciterId": 7,
                        "audioSlug": "test", "clock": "everyayah-ms",
                        "revision": hashlib.sha256(json.dumps(body, separators=(",", ":")).encode()).hexdigest()}
            original = base.read_bytes()
            with patch("build_db.QDC_REPEAT_RECITERS", {1:7}), patch(
                    "audit_word_splits.urllib.request.urlopen",
                    return_value=io.BytesIO(json.dumps(resource).encode())):
                capture(SimpleNamespace(db=base, out=output))
            with sqlite3.connect(output) as db:
                self.addCleanup(db.close)
                rows = read_rows(db)
            self.assertEqual(rows[9,1,1], ([[1,0,500]],0))
            self.assertEqual(rows[1,1,1], ([[1,20,300],[1,300,500]],0))
            self.assertEqual(base.read_bytes(), original)
            self.assertTrue(output.with_suffix(".manifest.json").exists())
            with self.assertRaisesRegex(ValueError, "never the release asset"):
                capture(SimpleNamespace(db=base, out=base))

    def test_resume_recovers_only_a_partial_last_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.jsonl"
            good = '{"id":"known"}\n'
            path.write_text(good + '{"id":"partial')
            self.assertEqual(read_reports(path, recover=True), {"known": {"id": "known"}})
            self.assertEqual(path.read_text(), good)
            path.write_text(good + 'not-json\n')
            with self.assertRaisesRegex(ValueError, "corrupt JSONL"):
                read_reports(path, recover=True)

    def test_all_generated_repairs_preserve_surrounding_windows_and_pass_the_gate(self):
        root = Path(__file__).parent
        cases = json.loads((root / "timing_evidence/word-split-pipeline-replays.json").read_text())["cases"]
        review = json.loads((root / "timing_evidence/word-split-corpus-review.json").read_text())
        self.assertTrue(review["complete"])
        self.assertEqual(len(cases), review["summary"]["repair"])
        self.assertEqual({c["id"] for c in cases},
                         {d["id"] for d in review["decisions"] if d["decision"] == "repair"})
        self.assertEqual({c["key"][0] for c in cases}, set(build.QDC_REPEAT_RECITERS))
        rows = [(*c["key"], json.dumps(c["inputSegments"])) for c in cases]
        lead = json.loads((root / "timing_patch_cases/false-lead-alafasy-28-9.json").read_text())
        rows.append((1, 28, 9, json.dumps(lead["input_segments"])))
        with contextlib.redirect_stdout(io.StringIO()):
            corrected = build.apply_timing_corrections(rows, phase="after_repairs")
        actual = {tuple(row[:3]): json.loads(row[3]) for row in corrected}
        self.assertEqual(actual[1, 28, 9], lead["expected_segments"])
        ledger = json.loads((root / "timing_evidence/word-split-corpus-candidates.verdict.json").read_text())["verdicts"]
        from timing_delta import make_payload
        for case in cases:
            key = tuple(case["key"])
            with self.subTest(id=case["id"]):
                self.assertEqual(actual[key], case["expectedSegments"])
                # Whole-row payload hashes bind the private replay. This
                # exercises the local geometry and raw proof without bundling
                # complete QF timing rows into the public repository.
                before = make_payload(case["auditBaselineWindow"],0)
                change = {"old":before,"new":make_payload(case["auditCandidateWindow"],0),
                          "kinds":["topology"],"verdict":ledger[case["verdictKey"]]}
                self.assertIsNone(build.authenticated_evidence_problem(change))

    def test_actual_inference_excerpts_cover_the_whole_inventory_on_both_models(self):
        directory = Path(__file__).parent / "timing_evidence"
        review = json.loads((directory / "word-split-corpus-review.json").read_text())
        provenance = json.loads((directory / "word-split-corpus-provenance.json").read_text())
        ids = {d["id"] for d in review["decisions"]}
        for tag in ["xlsr", "mms"]:
            data = (directory / f"word-splits-{tag}.excerpts.jsonl.gz").read_bytes()
            expected = provenance["archives"][tag]
            self.assertEqual(hashlib.sha256(data).hexdigest(), expected["archiveSha256"])
            raw = gzip.decompress(data)
            self.assertEqual(hashlib.sha256(raw).hexdigest(), expected["rawSha256"])
            self.assertEqual(review["reportSha256"][f"{tag}.jsonl"],expected["fullRawSha256"])
            reports = [json.loads(line) for line in raw.splitlines()]
            self.assertEqual({r["id"] for r in reports}, ids)
            self.assertEqual(len(reports), review["candidateCount"])
            self.assertTrue(all("error" not in r and r["modelTag"] == tag for r in reports))
            self.assertTrue(all(len(r["baseline"]["words"]) <= 4 and r.get("excerptScope") for r in reports))
        for key in [[1,24,25],[7,4,4],[7,21,46],[7,21,63]]:
            control = next(d for d in review["decisions"] if d["key"] == key)
            self.assertEqual(control["decision"], "retain")
            self.assertTrue(all(m["candidateScore"] < m["baselineScore"] for m in control["models"]))

    def test_issue_search_includes_timing_patches_with_unrelated_titles(self):
        rows = {(1,47,4): (self.before,0)}
        body = '```json\n' + json.dumps({"edits": [{"reciterId":1,"surahId":47,"ayah":4,
                                                    "segments":self.after}]}) + '\n```'
        issues = [{"number":1,"title":"Fix repetition bug","state":"CLOSED","body":body},
                  {"number":2,"title":"Playback loop","state":"OPEN","body":"No timing edit"}]
        result = issue_inventory(issues, rows)
        self.assertEqual(result["issueCount"],2)
        match = result["issues"][0]["timingEdits"][0]
        self.assertEqual(match["candidateIds"],["1:47:4:12"])
        self.assertEqual(match["occurrenceCounts"][0]["patch"],1)

    def test_pre_rewind_search_remains_an_explicitly_unscored_distinct_class(self):
        rows = {(1,1,1): ([[1,0,100],[2,100,200],[1,200,300],[2,300,400]],0),
                (7,1,1): ([[1,0,100],[3,100,200],[1,200,300],[3,300,400]],0)}
        result = rewind_inventory(rows)
        self.assertFalse(result["complete"])
        self.assertEqual([d["decision"] for d in result["candidates"]], ["uncertain","retain"])
        self.assertEqual(result["corpusSha256"],inventory(rows)["corpusSha256"])


if __name__ == "__main__":
    unittest.main()
