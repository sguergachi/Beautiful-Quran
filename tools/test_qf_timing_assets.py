import unittest

from qf_timing_assets import RUNTIME_DELIVERY, corpus_digest, verify_asset_transition


class TimingAssetTest(unittest.TestCase):
    def setUp(self):
        self.old_qf = {("Alafasy_128kbps", 1, 1): {"payloadHash": "a" * 64}}
        self.independent = {("Yasser_Ad-Dussary_128kbps", 1, 1): {"payloadHash": "b" * 64}}
        self.manifest = {"schemaVersion": 1, "runtimeReciters": [1, 2, 3, 4, 5, 7], "delivery": RUNTIME_DELIVERY,
            "retainedTimingRows": 1, "retainedTimingsSha256": corpus_digest(self.independent),
            "removedTimingRows": 1, "removedTimingsSha256": corpus_digest(self.old_qf)}

    def test_exact_removed_corpus_is_the_only_delivery_exception(self):
        self.assertEqual(verify_asset_transition(self.old_qf | self.independent, self.independent, self.manifest), self.independent)
        self.assertEqual(verify_asset_transition(self.independent, self.independent, self.manifest), self.independent)

    def test_wrong_removed_baseline_or_reintroduced_qf_rows_fail(self):
        for before, after in (({}, self.old_qf | self.independent),
            ({("Alafasy_128kbps", 1, 1): {"payloadHash": "c" * 64}}, self.independent)):
            with self.assertRaises(ValueError):
                verify_asset_transition(before, after, self.manifest)

    def test_independent_changes_remain_visible_to_the_existing_delta_gate(self):
        changed = {("Yasser_Ad-Dussary_128kbps", 1, 1): {"payloadHash": "c" * 64}}
        with self.assertRaises(ValueError):
            verify_asset_transition(self.old_qf | self.independent, changed, self.manifest)
        self.manifest["retainedTimingsSha256"] = corpus_digest(changed)
        self.assertEqual(verify_asset_transition(self.old_qf | self.independent, changed, self.manifest), self.independent)
        # Updating delivery metadata cannot hide the independent before/after.
        self.assertNotEqual(self.independent, changed)


if __name__ == "__main__":
    unittest.main()
