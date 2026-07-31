#!/usr/bin/env python3
"""Unit tests for tools/build_dictionary_db.py match helpers."""

from __future__ import annotations

import unittest

from build_dictionary_db import (
    clean_gloss,
    entry_match_keys,
    entry_stripped_keys,
    resolve_lemmas,
    strip_tashkeel,
)


class StripTashkeelTest(unittest.TestCase):
    def test_dagger_alef_meets_plain_alef(self) -> None:
        self.assertEqual(strip_tashkeel("كِتَٰب"), strip_tashkeel("كِتَاب"))
        self.assertEqual(strip_tashkeel("كِتَٰب"), "كتاب")

    def test_dagger_on_maqsurah_does_not_add_alef(self) -> None:
        self.assertEqual(strip_tashkeel("بُشْرَىٰ"), strip_tashkeel("بشرى"))
        self.assertEqual(strip_tashkeel("بُشْرَىٰ"), "بشرى")

    def test_wasla_normalizes_to_alef(self) -> None:
        self.assertEqual(strip_tashkeel("ٱللَّه"), "الله")


class GlossFilterTest(unittest.TestCase):
    def test_keeps_real_glosses(self) -> None:
        self.assertEqual(clean_gloss("to say"), "to say")
        self.assertEqual(clean_gloss("mercy, compassion"), "mercy, compassion")

    def test_drops_cross_refs(self) -> None:
        self.assertIsNone(clean_gloss("verbal noun of كَتَبَ (kataba)"))
        self.assertIsNone(clean_gloss("instance noun of رَحِمَ (raḥima)"))
        self.assertIsNone(clean_gloss("plural of كَاتِب"))


class ResolveLemmaTest(unittest.TestCase):
    def test_exact_then_unique_stripped(self) -> None:
        exact = {"قَالَ": "قَالَ", "كِتَٰب": "كِتَٰب"}
        stripped = {strip_tashkeel("كِتَٰب"): ["كِتَٰب"]}
        self.assertEqual(
            resolve_lemmas(["قَالَ"], ["قَالَ"], exact, stripped),
            ["قَالَ"],
        )
        self.assertEqual(
            resolve_lemmas(["كتاب", "كِتَاب"], ["كتاب", "كِتَاب"], exact, stripped),
            ["كِتَٰب"],
        )

    def test_ambiguous_stripped_attaches_to_every_candidate(self) -> None:
        exact = {"بُشِّرَ": "بُشِّرَ", "بَشَر": "بَشَر", "بُشْر": "بُشْر"}
        stripped = {"بشر": ["بُشْر", "بَشَر", "بُشِّرَ"]}
        self.assertEqual(
            resolve_lemmas(["مُبَشِّر", "بشر"], ["بَشَّرَ", "بشر"], exact, stripped),
            ["بُشْر", "بَشَر", "بُشِّرَ"],
        )


class EntryKeysTest(unittest.TestCase):
    def test_prefers_canonical_arabic_forms(self) -> None:
        keys = entry_match_keys(
            {
                "word": "كتاب",
                "forms": [
                    {"form": "كِتَاب", "tags": ["canonical"]},
                    {"form": "kitāb", "tags": ["romanization"]},
                    {"form": "no-table-tags", "tags": ["table-tags"]},
                    # Person-tagged conjugations must not participate in matching.
                    {"form": "كَتَبْتُمَا", "tags": ["second-person", "dual", "past"]},
                ],
            }
        )
        self.assertIn("كِتَاب", keys)
        self.assertNotIn("kitāb", keys)
        self.assertNotIn("no-table-tags", keys)
        self.assertNotIn("كَتَبْتُمَا", keys)
        stripped = entry_stripped_keys(
            {
                "word": "بشر",
                "forms": [
                    {"form": "بَشَّرَ", "tags": ["canonical", "form-ii"]},
                    {"form": "مُبَشِّر", "tags": ["participle"]},
                ],
            }
        )
        self.assertEqual(stripped, ["بَشَّرَ", "بشر"])


if __name__ == "__main__":
    unittest.main()
