#!/usr/bin/env python3
"""Gate tests: invalid mappings, disagreement, resume, staleness and chapter denominators."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

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

    def test_evidence_filters_only_verse_markers_and_revalidates_cached_surface_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary); client = self.evidence_client()
            package = runner.fetch_evidence(client, self.row, directory, "ar-jalalayn")
            self.assertEqual(2, len(package["morphology"]["words"]))
            self.assertEqual(1, len(package["omitted_verse_markers"]))
            self.assertEqual(package, runner.fetch_evidence(client, self.row, directory, "ar-jalalayn"))
            self.assertEqual(4, client.calls)
            package["morphology"]["words"].reverse()
            runner.save(directory / "1-1.json", package)
            with self.assertRaisesRegex(ValueError, "positions"):
                runner.fetch_evidence(client, self.row, directory, "ar-jalalayn")

    def test_equal_word_counts_do_not_verify_a_wrong_arabic_surface(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "surface"):
                runner.fetch_evidence(self.evidence_client(wrong_surface=True), self.row, Path(temporary), "ar-jalalayn")

    def test_completed_stage_resumes_but_changed_or_interrupted_input_does_not(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name, value in [("input.json", {"stage": "draft"}), ("schema.json", runner.DRAFT_SCHEMA),
                                ("answer.json", self.draft)]: runner.save(directory / name, value)
            with patch.object(runner, "model_call", return_value={"rerun": True}) as model:
                self.assertEqual(self.draft, runner.cached_model_call({"stage": "draft"}, runner.DRAFT_SCHEMA, directory, "test", 5))
                model.assert_not_called()
                self.assertEqual({"rerun": True}, runner.cached_model_call({"stage": "review"}, runner.DRAFT_SCHEMA, directory, "test", 5))
                (directory / "answer.json").write_text('{"interrupted":')
                self.assertEqual({"rerun": True}, runner.cached_model_call({"stage": "draft"}, runner.DRAFT_SCHEMA, directory, "test", 5))


if __name__ == "__main__":
    unittest.main()
