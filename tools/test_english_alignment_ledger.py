#!/usr/bin/env python3
"""Reject stale/incomplete approvals and reproduce the bundled mapping artifact."""
import copy
import base64
import json
import subprocess
import tempfile
import unittest
import english_alignment_ledger as ledger


class LedgerTest(unittest.TestCase):
    def setUp(self):
        self.row = {"verse": "1:1", "text": "alpha and beta", "arabic": ["a", "b"], "tokens": []}
        for start, end, text in [(0, 5, "alpha"), (6, 9, "and"), (10, 14, "beta")]:
            self.row["tokens"].append({"start": start, "end": end, "text": text, "head": 2, "ink_anchor": 1,
                "contributors": [1, 2], "meaning": "synthetic test only", "unit": "expansion", "status": "reviewed",
                "review": {"method": "human_review", "reviewer": "TEST ONLY", "evidence": ["synthetic fixture"]}})
        fingerprint = ledger.digest("\0".join([ledger.FORMAT, self.row["text"], *self.row["arabic"]]).encode())
        self.row["fingerprint"] = fingerprint
        self.binding = {"fingerprint": fingerprint, "arabic_slots": 2, "printed_tokens": 3,
            "tokens_sha256": ledger.digest(ledger.encoded([[t["start"], t["end"], t["text"]] for t in self.row["tokens"]]))}

    def test_compiler_keeps_heads_and_ink_separate(self):
        self.assertEqual(f"1:1\t{self.row['fingerprint']}\t1,1,1\t2,2,2", ledger.compile_verse(self.row, self.binding))
        for t in self.row["tokens"]:
            t["head"] = 1
        self.assertTrue(ledger.compile_verse(self.row, self.binding).endswith("\t="))

    def test_exported_reordered_draft_keeps_its_source_not_the_earlier_prefix(self):
        with tempfile.TemporaryDirectory(prefix="english-draft-order-") as temporary:
            root = ledger.Path(temporary)
            b64 = lambda s: base64.b64encode(s.encode()).decode()
            (root / "input.tsv").write_text("\t".join(["1:1", b64("Allah helped us"),
                b64("نَصَرَنَا\0ٱللَّهُ"), b64("helped us\0Allah"), b64("نَصَرَ\0ٱللَّه")]) + "\n")
            sources = [ledger.SOURCE / p for p in ("domain/EnglishWordAlignment.kt", "domain/EnglishTypography.kt",
                "domain/ArabicNormalize.kt", "domain/ReviewedEnglishAlignment.kt", "data/model/Models.kt")]
            subprocess.run(["kotlinc", *map(str, sources), str(ledger.REPO / "tools/ExportEnglishAlignment.kt"),
                "-include-runtime", "-d", str(root / "probe.jar")], check=True)
            result = subprocess.run(["java", "-jar", str(root / "probe.jar"), str(root / "input.tsv")],
                check=True, capture_output=True, text=True)
            tokens = result.stdout.strip().split("\t")[3].split(';')
            self.assertEqual([('2', '2'), ('1', '1'), ('1', '1')], [tuple(t.split(',')[3:]) for t in tokens])

    def test_unreviewed_silver_and_model_labels_never_ship(self):
        for status in ledger.STATUSES - ledger.APPROVED:
            row = copy.deepcopy(self.row)
            row["tokens"][1]["status"] = status
            self.assertIsNone(ledger.compile_verse(row, self.binding), status)

    def test_approval_requires_valid_decisions_and_provenance(self):
        for field, value in [("head", 0), ("ink_anchor", 3), ("head", True), ("contributors", None),
                ("contributors", [2]), ("contributors", [1, 1]), ("meaning", ""), ("unit", ""),
                ("review", None), ("review", {"method": "model_review", "reviewer": "LLM", "evidence": ["agreement"]})]:
            with self.subTest(field=field, value=value):
                row = copy.deepcopy(self.row)
                row["tokens"][0][field] = value
                with self.assertRaises(ValueError):
                    ledger.compile_verse(row, self.binding)
        self.row["tokens"][0]["status"] = "blind_gold"
        with self.assertRaises(ValueError):
            ledger.compile_verse(self.row, self.binding)

    def test_null_linked_addition_does_not_invent_a_contributor(self):
        self.row["tokens"][0]["unit"] = "left"
        self.row["tokens"][2]["unit"] = "right"
        self.row["tokens"][1]["contributors"] = []
        self.row["tokens"][1]["unit"] = "translator addition"
        self.assertIsNotNone(ledger.compile_verse(self.row, self.binding))

    def test_ai_review_is_explicit_and_requires_source_crosswalk_and_model_provenance(self):
        for token in self.row["tokens"]:
            token.update(status="model_reviewed", review={"method": "model_review", "reviewer": "TEST AI", "evidence": ["synthetic evidence"]})
        self.assertIsNone(ledger.compile_verse(self.row, self.binding))
        with self.assertRaisesRegex(ValueError, "crosswalk"):
            ledger.compile_verse(self.row, self.binding, ai_reviewed=True)
        self.row["qac_trust"] = "surface_verified"
        self.assertIsNotNone(ledger.compile_verse(self.row, self.binding, ai_reviewed=True))
        self.row["tokens"][1]["status"] = "consensus_silver"
        self.assertIsNone(ledger.compile_verse(self.row, self.binding, ai_reviewed=True))
        self.row["tokens"][1]["status"] = "model_reviewed"
        self.row["tokens"][0]["review"]["method"] = "human_review"
        with self.assertRaisesRegex(ValueError, "provenance"):
            ledger.compile_verse(self.row, self.binding, ai_reviewed=True)

    def test_ai_pilot_reproduces_with_full_population_and_keeps_problem_meanings(self):
        data = ledger.REPO / "data"
        manifest = json.loads((data / "english_alignment_manifest.json").read_text())
        source = data / "english_ai_alignment_ledger.jsonl"
        artifact, coverage = ledger.compile_ledger(source, manifest, allow_partial=True, ai_reviewed=True)
        self.assertEqual((data / "english-ai-alignments.tsv").read_text(), artifact)
        self.assertEqual(json.loads((data / "english_ai_alignment_coverage.json").read_text()), coverage)
        self.assertEqual((7, 325, 6229), (coverage["ai_reviewed_verses"], coverage["approved_tokens"], coverage["fallback_verses"]))
        self.assertFalse(coverage["semantic_accuracy_established"])
        _, human = ledger.compile_ledger(source, manifest, allow_partial=True)
        self.assertEqual(0, human["approved_verses"])
        rows = {r["verse"]: r for r in map(json.loads, source.read_text().splitlines())}
        for key, text, owners in [("24:30", "vision", [5]), ("24:31", "vision", [5]),
                ("24:31", "brothers", [35, 38]), ("24:37", "performance", [10]),
                ("24:37", "prayer", [11]), ("23:83", "before", [7]), ("9:25", "victory", [2])]:
            self.assertEqual(owners, [t["head"] for t in rows[key]["tokens"] if t["text"] == text])
        against = [t for t in rows["9:4"]["tokens"] if t["text"] == "against"]
        self.assertEqual(12, against[0]["head"])

    def test_units_cannot_mix_sources_or_skip_intervening_tokens(self):
        self.row["tokens"][1].update(head=1, contributors=[1])
        with self.assertRaisesRegex(ValueError, "mixes"):
            ledger.compile_verse(self.row, self.binding)
        self.row["tokens"][1].update(head=2, contributors=[1, 2], unit="other")
        with self.assertRaisesRegex(ValueError, "contiguous"):
            ledger.compile_verse(self.row, self.binding)

    def test_fixed_contract_matches_runtime_fingerprint(self):
        fixture = ledger.REPO / "app/src/test/resources/english-ledger-contract.tsv"
        text, arabic, fingerprint, _ = fixture.read_text().strip().split("\t")
        self.assertEqual(fingerprint, ledger.digest("\0".join([ledger.FORMAT, text, *arabic.split('|')]).encode()))

    def test_text_surfaces_offsets_and_population_are_bound(self):
        for mutate in [lambda r: r.update(text=r["text"] + "!"),
                lambda r: r["arabic"].reverse(), lambda r: r["tokens"].pop(),
                lambda r: r["tokens"][0].update(start=1)]:
            row = copy.deepcopy(self.row)
            mutate(row)
            with self.assertRaises(ValueError):
                ledger.compile_verse(row, self.binding)
        manifest = json.loads((ledger.REPO / "data/english_alignment_manifest.json").read_text())
        manifest["verses"].pop("1:1")
        with self.assertRaisesRegex(ValueError, "subset"):
            ledger.validate_manifest(manifest)

    def test_bundled_artifact_reproduces_and_complete_mode_rejects_fallback(self):
        data = ledger.REPO / "data"
        manifest = json.loads((data / "english_alignment_manifest.json").read_text())
        artifact, coverage = ledger.compile_ledger(data / "english_alignment_ledger.jsonl", manifest, allow_partial=True)
        self.assertEqual((data / "english-alignments.tsv").read_text(), artifact)
        self.assertEqual(json.loads((data / "english_alignment_coverage.json").read_text()), coverage)
        self.assertEqual(6236, coverage["verses"])
        self.assertFalse(coverage["semantic_accuracy_established"])
        with self.assertRaisesRegex(ValueError, "fallback"):
            ledger.compile_ledger(data / "english_alignment_ledger.jsonl", manifest)
        manifest["components"].pop("data/quran.db")
        with self.assertRaisesRegex(ValueError, "Incomplete component"):
            ledger.validate_manifest(manifest)

    def test_unrelated_component_edits_preserve_review_and_text_edits_do_not(self):
        manifest = json.loads((ledger.REPO / "data/english_alignment_manifest.json").read_text())
        manifest["components"]["data/quran.db"] = "older timing-build provenance"
        manifest["components"]["docs/ENGLISH_ALIGNMENT_LEDGER.md"] = "earlier documentation"
        ledger.validate_manifest(manifest)
        manifest["content_sha256"] = "changed input"
        with self.assertRaisesRegex(ValueError, "surfaces changed"):
            ledger.validate_manifest(manifest)


if __name__ == "__main__":
    unittest.main()
