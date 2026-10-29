#!/usr/bin/env python3
"""Gate tests: which verses a review pass is allowed to skip."""
import unittest

import english_review_selection as selection


def verse(key, words, heads):
    return {"verse": key, "arabic": [f"w{i}" for i in range(1, words + 1)],
            "tokens": [{"text": f"t{i}", "head": head} for i, head in enumerate(heads, 1)]}


class ReviewSelectionTest(unittest.TestCase):
    def test_an_arabic_word_no_token_names_is_the_reported_defect(self):
        # "and he saw" recites three words; the baseline names only the first and third.
        row = verse("6:76", 3, [1, 3, 3])
        self.assertEqual([2], selection.uncovered_words(row))
        self.assertTrue(selection.needs_review(row))

    def test_full_coverage_is_not_flagged_even_when_reordered(self):
        row = verse("1:2", 4, [1, 2, 4, 3])
        self.assertEqual([], selection.uncovered_words(row))
        self.assertFalse(selection.needs_review(row))

    def test_audit_draw_is_stable_and_proportional(self):
        keys = [f"{1 + i % 114}:{1 + i // 114}" for i in range(4000)]
        first = {key for key in keys if selection.audited(key, 0.1)}
        self.assertEqual(first, {key for key in keys if selection.audited(key, 0.1)})
        self.assertAlmostEqual(0.1, len(first) / len(keys), delta=0.02)
        self.assertEqual({key for key in keys if selection.audited(key, 0.0)}, set())
        self.assertEqual({key for key in keys if selection.audited(key, 1.0)}, set(keys))
        self.assertNotEqual({key for key in keys if selection.audited(key, 0.1, "other-seed")}, first)

    def test_queue_is_corpus_order_and_bounded_to_selected_chapters(self):
        rows = {key: verse(key, 2, [1, 2] if i % 2 else [1, 1]) for i, key in
                enumerate(["1:1", "1:2", "2:1", "2:2", "3:1"])}
        risky = selection.review_queue(rows, {1, 2}, "risky", 0.0)
        self.assertEqual(["1:1", "2:1"], risky)
        self.assertEqual(["1:1", "1:2", "2:1"], selection.review_queue(rows, {1, 2}, "risky", 0.0, {"1:2", "3:1"}))
        self.assertEqual(list(rows), selection.review_queue(rows, {1, 2, 3}, "all", 0.0))
        sampled = selection.review_queue(rows, {1}, "risky", 1.0)
        self.assertEqual(["1:1", "1:2"], sampled)


if __name__ == "__main__":
    unittest.main()
