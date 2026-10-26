#!/usr/bin/env python3
"""The semantic acceptance gate must count grammar and reject incomplete references."""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


class SemanticGateTest(unittest.TestCase):
    def test_no_literal_overlap_still_uses_the_reviewed_semantic_gate(self):
        with tempfile.TemporaryDirectory(prefix="english-no-literal-test-") as temporary:
            root = Path(temporary)
            corpus = root / "corpus.tsv"
            corpus.write_text("24:4\tever after. Then [again]\tunrelated|absent\n")
            reference = root / "reviewed.tsv"
            for name, addition, expected_code in (
                ("complete", "2", 0), ("missing-addition", "", 1),
            ):
                with self.subTest(reference=name):
                    reference.write_text(f"24:4\t0\t4\tever\t1\n24:4\t5\t10\tafter\t1\n24:4\t12\t16\tThen\t2\n24:4\t18\t23\tagain\t{addition}\n")
                    output = root / name
                    result = subprocess.run([
                        "python3", str(Path(__file__).with_name("audit_english_alignment.py")), str(corpus),
                        "--output-dir", str(output), "--semantic-reference", str(reference),
                        "--require-semantic-accuracy", "99.9",
                    ], capture_output=True, text=True)
                    self.assertEqual(expected_code, result.returncode, result.stdout + result.stderr)
                    self.assertIn("no literal correspondences", result.stdout)
                    self.assertNotIn("Traceback", result.stderr)
                    report = json.loads((output / "semantic-certification.json").read_text())
                    self.assertEqual(4, report["population_printed_tokens"])
                    self.assertEqual(name == "complete", report["established"])
                    self.assertEqual(name == "missing-addition", report["unreviewed"] == 1)
                    if expected_code:
                        self.assertIn("semantic accuracy not established", result.stderr)

    def test_all_printed_words_count_and_missing_or_wrong_labels_fail(self):
        with tempfile.TemporaryDirectory(prefix="english-reference-test-") as temporary:
            root = Path(temporary)
            corpus = root / "corpus.tsv"
            corpus.write_text("24:4\tever after. Then [again]\tever|Then\n")
            reference = root / "reviewed.tsv"
            for name, after, addition, expected_code in (
                ("missing", "", "2", 1), ("missing-addition", "1", "", 1),
                ("wrong", "2", "2", 1), ("complete", "1", "2", 1),
            ):
                reference.write_text(f"24:4\t0\t4\tever\t1\n24:4\t5\t10\tafter\t{after}\n24:4\t12\t16\tThen\t2\n24:4\t18\t23\tagain\t{addition}\n")
                output = root / name
                result = subprocess.run([
                    "python3", str(Path(__file__).with_name("audit_english_alignment.py")), str(corpus),
                    "--output-dir", str(output), "--semantic-reference", str(reference),
                    "--require-semantic-accuracy", "99.9",
                ], capture_output=True, text=True)
                self.assertEqual(expected_code, result.returncode, result.stdout + result.stderr)
                report = json.loads((output / "semantic-certification.json").read_text())
                self.assertEqual(4, report["population_printed_tokens"])
                self.assertTrue(report["includes_grammar"])
                self.assertTrue(report["includes_additions"])
                self.assertFalse(report["established"], "A subset or model reference cannot certify the book")
                self.assertEqual(name == "complete", report["reference_coverage_target_met"])
                self.assertEqual(name.startswith("missing"), report["unreviewed"] == 1)
                self.assertEqual(name == "wrong", report["disagreements"] == 1)


if __name__ == "__main__":
    unittest.main()
