"""Regression/negative controls for short words following a stolen held tail."""
import collections
import copy
import unittest

from build_db import (clean_qdc_artifacts, fragment_same_position_leads,
                      hand_lead_to_previous_word)


class FragmentLeadTest(unittest.TestCase):
    source = [[7, 6545, 6985], [8, 6985, 8885], [8, 8885, 9245], [9, 9245, 10185]]
    reference = [[7, 6580, 8890], [8, 8900, 10080], [9, 10090, 11700]]

    def cleaned(self, source=None, offset=25):
        rows = clean_qdc_artifacts(source or self.source, collections.defaultdict(int))
        return [[p, s + offset, e + offset] for p, s, e in rows]

    def test_held_allah_keeps_its_tail_until_the_next_phrase(self):
        cleaned = self.cleaned()
        leads = fragment_same_position_leads(self.source, cleaned, self.reference, 25)
        self.assertEqual([(8, 7010, 8910)], leads)
        self.assertEqual([[7, 6570, 8910], [8, 8910, 9270], [9, 9270, 10210]],
                         hand_lead_to_previous_word(cleaned, leads))
        self.assertEqual([8, 6985, 8885], self.source[1])

    def test_missing_clock_or_reference_cannot_move_a_boundary(self):
        for reference, offset in [(None, 25), (self.reference, None), ([], 25)]:
            self.assertEqual([], fragment_same_position_leads(
                self.source, self.cleaned(), reference, offset))

    def test_mid_word_split_keeps_its_first_onset(self):
        reference = [[7, 6580, 7000], [8, 7010, 9260], [9, 9270, 10210]]
        self.assertEqual([], fragment_same_position_leads(
            self.source, self.cleaned(), reference, 25))

    def test_genuine_re_says_and_paused_pairs_are_not_fragment_leads(self):
        peers = [[1, 0, 1000], [2, 1000, 2700], [2, 2700, 3820], [3, 3820, 5000]]
        paused = [[1, 0, 1000], [2, 1000, 2900], [2, 3300, 3660], [3, 3660, 5000]]
        reference = [[1, 0, 3000], [2, 3300, 3660], [3, 3660, 5000]]
        for source in (peers, paused):
            self.assertEqual([], fragment_same_position_leads(
                source, self.cleaned(source, 0), reference, 0))

    def test_another_occurrence_or_intervening_owner_blocks_the_edit(self):
        repeated = self.cleaned() + [[8, 12000, 13000]]
        changed = copy.deepcopy(self.source)
        changed[0][0] = 6
        self.assertEqual([], fragment_same_position_leads(
            self.source, repeated, self.reference, 25))
        self.assertEqual([], fragment_same_position_leads(
            changed, self.cleaned(changed), self.reference, 25))

    def test_reference_must_corroborate_the_second_onset_and_preceding_owner(self):
        for reference in ([[7, 6580, 6990], [8, 8900, 10080]],
                          [[7, 6580, 8890], [8, 9600, 10080]]):
            self.assertEqual([], fragment_same_position_leads(
                self.source, self.cleaned(), reference, 25))


if __name__ == "__main__":
    unittest.main()
