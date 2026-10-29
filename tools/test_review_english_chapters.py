#!/usr/bin/env python3
"""Gate tests: invalid mappings, disagreement, resume, staleness and chapter denominators."""
import copy
import json
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from threading import Event
from types import SimpleNamespace

import english_alignment_ledger as ledger
import review_english_chapters as runner
from english_alignment_evidence import surface


class ChapterReviewTest(unittest.TestCase):
    def setUp(self):
        self.row = {"verse": "1:1", "text": "Mercy prevails", "arabic": ["رَحْمَة", "تَغْلِب"], "tokens": [
            {"start": 0, "end": 5, "text": "Mercy"}, {"start": 6, "end": 14, "text": "prevails"}]}
        self.row["fingerprint"] = ledger.digest("\0".join([ledger.FORMAT, self.row["text"], *self.row["arabic"]]).encode())
        self.binding = {"fingerprint": self.row["fingerprint"], "arabic_slots": 2, "printed_tokens": 2,
            "tokens_sha256": ledger.digest(ledger.encoded([[t["start"], t["end"], t["text"]] for t in self.row["tokens"]]))}
        self.draft = {"verse": "1:1", "tokens": [{"index": i, "contributors": [i + 1], "head": i + 1,
            "ink_anchor": i + 1, "meaning": "synthetic only", "unit": str(i)} for i in range(2)]}
        self.review = {"verse": "1:1", "approved": True, "issues": []}
        self.calls = []

    def call(self, payload, schema, destination):
        self.calls.append(payload["stage"])
        return copy.deepcopy(self.draft if payload["stage"] == "draft" else self.review)

    def run_review(self, directory, attempts=2):
        return runner.review_verse(self.row, self.binding, {"synthetic": True}, directory,
                                   "synthetic-test-only", attempts, self.call)

    def test_complete_review_passes_and_resume_makes_no_model_calls(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            state = self.run_review(directory)
            self.assertEqual("model_reviewed", state["status"])
            self.assertEqual(["draft", "review"], self.calls)
            self.assertFalse(any(t["status"] in ledger.APPROVED for t in state["row"]["tokens"]))
            self.assertEqual(state, self.run_review(directory))
            self.assertEqual(2, len(self.calls))

    def test_review_disagreement_is_held_after_bounded_attempts(self):
        self.review.update(approved=False, issues=["Wrong contributor for token 0"])
        with tempfile.TemporaryDirectory() as temporary:
            state = self.run_review(Path(temporary))
            self.assertEqual("held", state["status"])
            self.assertEqual(2, state["attempts"])
            self.assertNotIn("row", state)
            self.assertEqual(4, len(self.calls))

    def test_invalid_source_or_missing_token_never_reaches_critique(self):
        for mutation in [lambda: self.draft["tokens"].pop(),
                         lambda: self.draft["tokens"][0].update(head=9),
                         lambda: self.draft["tokens"][0].update(contributors=[2])]:
            self.setUp(); mutation()
            with tempfile.TemporaryDirectory() as temporary:
                state = self.run_review(Path(temporary), attempts=1)
                self.assertEqual("held", state["status"])
                self.assertEqual(["draft"], self.calls)

    def test_approval_with_issues_or_nonboolean_approval_cannot_ship(self):
        for approved, issues in [(True, ["Unresolved token"]), ("true", []), (False, [])]:
            self.review.update(approved=approved, issues=issues)
            with tempfile.TemporaryDirectory() as temporary:
                self.assertEqual("held", self.run_review(Path(temporary), attempts=1)["status"])

    def test_changed_policy_or_evidence_invalidates_resume(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            first = self.run_review(directory)
            with patch.object(runner, "RUBRIC") as rubric:
                rubric.read_text.return_value = "changed policy"
                second = self.run_review(directory)
            self.assertNotEqual(first["input_sha256"], second["input_sha256"])
            self.assertEqual(4, len(self.calls))

    def test_chapter_report_counts_missing_and_held_verses_and_separates_playback(self):
        rows = {"1:1": self.row, "1:2": {}, "2:1": {}}
        states = {"1:1": {"status": "model_reviewed"}}
        chapter = runner.summarize(rows, states, {1}, "passed")["chapters"]["1"]
        self.assertEqual((2, 1, 1, False), tuple(chapter[k] for k in ["verses", "model_reviewed", "held", "complete"]))
        states["1:2"] = {"status": "model_reviewed"}
        self.assertFalse(runner.summarize(rows, states, {1}, "failed")["chapters"]["1"]["complete"])

    def test_ranges_and_arabic_comparison_do_not_accept_wrong_surfaces(self):
        self.assertEqual({1, 2, 3, 24}, runner.chapters("1-3,24"))
        for value in ["0", "115", "3-1", "1-2-3"]:
            with self.assertRaises(ValueError): runner.chapters(value)
        self.assertEqual(surface("ٱلْأَرْضِ"), surface("الأرض"))
        self.assertNotEqual(surface("الأرض"), surface("السماء"))

    def test_canonical_entry_contracts_include_tafsir_and_reject_other_verses(self):
        self.assertEqual({"64:1"}, runner.entry_keys({"ayah": "64:1", "text": "fixture"}))
        self.assertEqual({"64:1"}, runner.entry_keys({"ayahs": ["64:1"], "range": "64:1"}))
        self.assertNotIn("64:1", runner.entry_keys({"ayahs": ["64:2"]}))
        self.assertFalse(runner.entry_keys({"range": "64:1"}))

    def evidence_client(self, wrong_surface=False):
        row = self.row
        class Client:
            grounding = {"fixture": "synthetic only"}
            calls = 0
            def call(self, name, args):
                self.calls += 1
                if name == "fetch_word_morphology":
                    words = [{"position": i + 1, "text_uthmani": text} for i, text in enumerate(row["arabic"])]
                    if wrong_surface: words[0]["text_uthmani"] = row["arabic"][1]
                    return {"structuredContent": {"ayah_key": row["verse"], "words": [*words, {"position": 3, "text_uthmani": "١"}]}}
                edition = args["editions"]
                entry = {"text": " ".join(row["arabic"]) if name == "fetch_quran" else "synthetic only",
                         "citation_url": None}
                entry.update({"ayahs": [row["verse"]]} if name == "fetch_tafsir" else {"ayah": row["verse"]})
                return {"structuredContent": {"ayahs": [row["verse"]], "results": {edition: [entry]}, "pagination": {"has_more": False}}}
        return Client()

    def test_evidence_is_retrieved_online_and_only_hashes_and_citations_are_saved(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary); client = self.evidence_client()
            package = runner.fetch_evidence(client, self.row, directory, "ar-jalalayn")
            self.assertEqual(2, len(package["morphology"]["words"]))
            self.assertEqual(1, len(package["omitted_verse_markers"]))
            self.assertEqual(package, runner.fetch_evidence(client, self.row, directory, "ar-jalalayn"))
            self.assertEqual(8, client.calls)
            receipt = json.loads((directory / '1-1.json').read_text())
            self.assertEqual(runner.evidence_receipt(package), receipt)
            self.assertNotIn('synthetic only', (directory / '1-1.json').read_text())
            self.assertFalse((directory / 'raw').exists())
            self.assertEqual(runner.job_signature(self.row, self.binding, package, 'test'),
                             runner.job_signature(self.row, self.binding, receipt, 'test'))
            package["morphology"]["words"].reverse()
            with self.assertRaisesRegex(ValueError, "positions"):
                runner.validate_evidence(self.row, package)

    def test_equal_word_counts_do_not_verify_a_wrong_arabic_surface(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "surface"):
                runner.fetch_evidence(self.evidence_client(wrong_surface=True), self.row, Path(temporary), "ar-jalalayn")

    def test_completed_review_evidence_is_rebound_without_a_paid_refetch(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            runner.fetch_evidence(self.evidence_client(), self.row, work / "evidence", "ar-jalalayn")
            receipt = json.loads((work / "evidence" / "1-1.json").read_text())
            self.assertEqual(receipt, runner.stored_evidence(work, self.row))
            self.assertIsNone(runner.stored_evidence(work, dict(self.row, fingerprint="0" * 64)))
            (work / "evidence" / "1-1.json").write_text('{"interrupted":')
            self.assertIsNone(runner.stored_evidence(work, self.row))

    def test_completed_stage_resumes_but_changed_or_interrupted_input_does_not(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            receipt = dict(runner.request_receipt({'stage': 'draft'}, runner.DRAFT_SCHEMA, 'test'), completed=True,
                           answer_sha256=ledger.digest(ledger.encoded(self.draft)))
            for name, value in [("request.json", receipt), ("schema.json", runner.DRAFT_SCHEMA),
                                ("answer.json", self.draft)]: runner.save(directory / name, value)
            with patch.object(runner, "model_call", return_value={"rerun": True}) as model:
                self.assertEqual(self.draft, runner.cached_model_call({"stage": "draft"}, runner.DRAFT_SCHEMA, directory, "test", 5))
                model.assert_not_called()
                self.assertEqual({"rerun": True}, runner.cached_model_call({"stage": "review"}, runner.DRAFT_SCHEMA, directory, "test", 5))
                (directory / "answer.json").write_text('{"interrupted":')
                self.assertEqual({"rerun": True}, runner.cached_model_call({"stage": "draft"}, runner.DRAFT_SCHEMA, directory, "test", 5))

    def test_prior_ai_seed_is_reused_but_not_counted_as_a_fresh_critique(self):
        mapped = runner.candidate(self.row, self.draft, self.binding, {"synthetic": True}, "TEST AI")
        states = {}
        runner.import_seed({"1:1": self.row}, {"verses": {"1:1": self.binding}}, states, {"1:1": mapped}, {1})
        chapter = runner.summarize({"1:1": self.row}, states, {1}, "passed")["chapters"]["1"]
        self.assertEqual((1, 0), (chapter["reused_prior_ai_verses"], chapter["freshly_critiqued_verses"]))
        self.assertTrue(chapter["complete"])
        self.assertEqual("model_reviewed", states["1:1"]["row"]["tokens"][0]["status"])
        stale = copy.deepcopy(mapped); stale["text"] += "!"
        with self.assertRaises(ValueError):
            runner.import_seed({"1:1": self.row}, {"verses": {"1:1": self.binding}}, {}, {"1:1": stale}, {1})

    def test_cancelled_run_does_not_launch_or_retry_a_model(self):
        stop = Event(); stop.set()
        with tempfile.TemporaryDirectory() as temporary, patch.object(runner, "model_call") as model:
            with self.assertRaises(KeyboardInterrupt):
                runner.cached_model_call({}, runner.DRAFT_SCHEMA, Path(temporary), "test", 5, stop)
            model.assert_not_called()

    def test_changed_validator_reuses_only_exact_prompt_schema_model_and_reasoning(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old = root / "old-validator" / "1" / "draft"
            new = root / "new-validator" / "1" / "draft"
            payload = {"stage": "draft"}
            receipt = dict(runner.request_receipt(payload, runner.DRAFT_SCHEMA, 'test'), completed=True,
                           answer_sha256=ledger.digest(ledger.encoded(self.draft)))
            for name, value in [("request.json", receipt), ("schema.json", runner.DRAFT_SCHEMA), ("answer.json", self.draft)]:
                runner.save(old / name, value)
            with patch.object(runner, "model_call", return_value={"rerun": True}) as model:
                self.assertEqual(self.draft, runner.cached_model_call(payload, runner.DRAFT_SCHEMA, new, "test", 5))
                model.assert_not_called()
                self.assertTrue((new / "reuse.json").exists())
                different = root / "different-model" / "1" / "draft"
                self.assertEqual({"rerun": True}, runner.cached_model_call(payload, runner.DRAFT_SCHEMA, different, "other", 5))

    def test_unit_separation_changes_only_invalid_group_identifiers(self):
        original = copy.deepcopy(self.draft)
        for token in original["tokens"]: token["unit"] = "shared"
        split = runner.separate_units(original)
        self.assertNotEqual(split["tokens"][0]["unit"], split["tokens"][1]["unit"])
        for a, b in zip(original["tokens"], split["tokens"]):
            self.assertEqual({k:v for k,v in a.items() if k != "unit"}, {k:v for k,v in b.items() if k != "unit"})
        self.assertEqual(self.draft, runner.separate_units(self.draft))
        repeated = copy.deepcopy(self.draft)
        repeated["tokens"].append(dict(repeated["tokens"][0], index=2))
        separated = runner.separate_units(repeated)
        self.assertNotEqual(separated["tokens"][0]["unit"], separated["tokens"][2]["unit"])

    def test_auth_failure_stops_without_spending_the_second_attempt(self):
        calls = []
        def unavailable(*args):
            calls.append(True)
            raise runner.ModelUnavailableError("ERROR: not authenticated")
        with tempfile.TemporaryDirectory() as temporary:
            state = runner.review_verse(self.row, self.binding, {"synthetic": True}, Path(temporary), "test", 2, unavailable)
        self.assertEqual(1, len(calls))
        self.assertEqual("held", state["status"])
        self.assertTrue(state["provider_unavailable"])
        self.assertNotIn("row", state)

    def test_quota_wait_retries_the_same_stage_without_spending_a_semantic_attempt(self):
        clock = [1000.0]
        def sleep(seconds): clock[0] += seconds
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); stop = Event()
            gate = runner.UsageWait(root / 'usage-wait.json', stop, clock=lambda: clock[0], sleeper=sleep)
            def call(payload, schema, directory):
                return runner.cached_model_call(payload, schema, directory, 'test', 5, stop, gate)
            with patch.object(runner, 'model_call', side_effect=[runner.UsageLimitError('retry in 2 seconds'), self.draft, self.review]) as model:
                state = runner.review_verse(self.row, self.binding, {'synthetic': True}, root / 'jobs', 'test', 2, call)
            self.assertEqual(('model_reviewed', 1, 3), (state['status'], state['attempts'], model.call_count))
            self.assertEqual(1062, clock[0])

    def test_live_prompt_and_cli_transcripts_are_not_saved_to_the_work_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def execute(command, **kwargs):
                self.assertIn('private source sentinel', kwargs['input'])
                Path(command[command.index('--output-last-message') + 1]).write_text(json.dumps(self.draft))
                return SimpleNamespace(stdout='private source sentinel', stderr='user\nprivate source sentinel\n')
            with patch.object(runner.subprocess, 'run', side_effect=execute):
                self.assertEqual(self.draft, runner.model_call({'source': 'private source sentinel'}, runner.DRAFT_SCHEMA, root, 'test', 5))
            for p in root.rglob('*'):
                if p.is_file(): self.assertNotIn('private source sentinel', p.read_text())
            self.assertTrue(json.loads((root / 'request.json').read_text())['completed'])

    def test_failed_cli_calls_classify_quota_and_auth_without_retaining_the_prompt(self):
        for message, kind in [('ERROR: usage limit; try again at 6:35 PM', runner.UsageLimitError),
                              ('ERROR: not authenticated', runner.ModelUnavailableError)]:
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                failure = subprocess.CalledProcessError(1, ['codex'], output='private source sentinel',
                            stderr='user\nprivate source sentinel\n' + message)
                with patch.object(runner.subprocess, 'run', side_effect=failure):
                    with self.assertRaises(kind):
                        runner.model_call({'source': 'private source sentinel'}, runner.DRAFT_SCHEMA, root, 'test', 5)
                self.assertFalse(json.loads((root / 'request.json').read_text())['completed'])
                for p in root.rglob('*'):
                    if p.is_file(): self.assertNotIn('private source sentinel', p.read_text())

    def test_legacy_cleanup_preserves_decisions_and_replaces_source_copies_with_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            package = runner.fetch_evidence(self.evidence_client(), self.row, work / 'evidence', 'ar-jalalayn')
            runner.save(work / 'evidence/1-1.json', package)
            runner.save(work / 'evidence/raw/1-1/reply.json', {'source': 'private source sentinel'})
            runner.save(work / 'jobs/1-1/a/1/draft/input.json', {'source': 'private source sentinel'})
            transcript = work / 'jobs/1-1/a/1/draft/stderr.log'
            transcript.write_text('private source sentinel')
            runner.save(transcript.with_name('answer.json'), self.draft)
            runner.save(work / 'state.json', {'preserved': True})
            (work / 'evidence/2-1.json.tmp').write_text('interrupted private source sentinel')
            transcript.with_name('input.json.tmp').write_text('interrupted private source sentinel')
            self.assertEqual(6, runner.purge_source_payloads(work))
            self.assertEqual({'preserved': True}, json.loads((work / 'state.json').read_text()))
            self.assertEqual(self.draft, json.loads(transcript.with_name('answer.json').read_text()))
            for p in work.rglob('*'):
                if p.is_file(): self.assertNotIn('private source sentinel', p.read_text())


if __name__ == "__main__":
    unittest.main()
