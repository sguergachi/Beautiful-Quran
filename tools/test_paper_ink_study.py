"""Geometry regressions for the offline Paper material experiment."""
import re
import sqlite3
import unittest

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt, label

from build_paper_ink_study import (
    ARABIC_FONT, ENGLISH_FONT, ROOT, SS, filling_field, wicking_basins,
)


def material(text, arabic=False):
    path, size, direction = (ARABIC_FONT, 160, 'rtl') if arabic else (ENGLISH_FONT, 80, 'ltr')
    font = ImageFont.truetype(str(path), size)
    box = font.getbbox(text, direction=direction)
    image = Image.new('L', ((box[2] - box[0] + 16) * SS, (box[3] - box[1] + 16) * SS))
    ImageDraw.Draw(image).text(((8 - box[0]) * SS, (8 - box[1]) * SS), text,
                              font=ImageFont.truetype(str(path), size * SS), fill=255, direction=direction)
    ink = np.array(image) > 127
    depth = (distance_transform_edt(ink) - distance_transform_edt(~ink)) / SS
    arrival, duration = filling_field(text, ink, depth, box, font.getmetrics()[0], path, size)
    return ink, depth, arrival, duration


class PaperInkStudyTest(unittest.TestCase):
    def test_single_letter_fills_its_interior_before_both_sides_of_its_contour(self):
        ink, depth, arrival, _ = material('O')
        x = np.indices(ink.shape)[1]
        for half in (x < ink.shape[1] / 2, x >= ink.shape[1] / 2):
            core = ink & half & (depth > depth.max() * .7)
            edge = ink & half & (depth < .5)
            self.assertLess(np.median(arrival[core]), np.median(arrival[edge]) - .12)
        # The left contour can remain empty after the right interior fills:
        # this cannot be produced by a directional cut through the letter.
        left_edge = ink & (x < ink.shape[1] / 2) & (depth < .5)
        right_core = ink & (x >= ink.shape[1] / 2) & (depth > depth.max() * .7)
        self.assertLess(np.median(arrival[right_core]), np.median(arrival[left_edge]) - .12)

    def test_english_letters_start_left_to_right_with_overlapping_blooms(self):
        ink, _, arrival, _ = material('OO')
        pieces, count = label(ink)
        self.assertEqual(2, count)
        ordered = sorted(range(1, count + 1), key=lambda p: np.nonzero(pieces == p)[1].mean())
        left, right = (arrival[pieces == p] for p in ordered)
        self.assertLess(np.median(left), np.median(right))
        self.assertLess(right.min(), left.max())

    def test_arabic_letters_start_right_to_left_with_overlapping_blooms(self):
        ink, _, arrival, _ = material('اا', arabic=True)
        pieces, count = label(ink)
        self.assertEqual(2, count)
        ordered = sorted(range(1, count + 1), key=lambda p: -np.nonzero(pieces == p)[1].mean())
        right, left = (arrival[pieces == p] for p in ordered)
        self.assertLess(np.median(right), np.median(left))
        self.assertLess(left.min(), right.max())

    def test_whole_word_ligatures_keep_separate_letter_blooms(self):
        for text in ('بِسۡمِ', 'لِلَّهِ'):
            with self.subTest(text=text):
                _, _, _, duration = material(text, arabic=True)
                self.assertAlmostEqual(2.8 / (3 + 1.8), duration)

    def test_all_study_letters_receive_ink_and_finish_in_their_word_window(self):
        with sqlite3.connect(ROOT / 'data/quran.db') as db:
            arabic = [r[0] for r in db.execute('select arabic from words where surah_id=1 and ayah_number<=3')]
            english = [word for r in db.execute('select translation_en from ayahs where surah_id=1 and ayah_number<=3')
                       for word in re.findall(r'\S+', r[0])]
        for text, is_arabic in [(v, True) for v in arabic] + [(v, False) for v in english]:
            with self.subTest(text=text):
                ink, _, arrival, duration = material(text, is_arabic)
                self.assertGreaterEqual(arrival[ink].min() - duration * .1, -1 / 255)
                self.assertLessEqual(arrival[ink].max() + duration * .1, 1 + 1 / 255)

    def test_single_pixel_mark_has_finite_ink_and_its_letter_owner(self):
        owners, travel = wicking_basins(np.ones((1, 1), dtype=bool), np.ones((1, 1)), [(7, 0)])
        np.testing.assert_array_equal(owners, [7])
        np.testing.assert_array_equal(travel, [0])

    def test_capillary_paths_cannot_cross_a_gap_in_the_paper(self):
        ink = np.ones((15, 15), dtype=bool)
        ink[:12, 6:9] = False
        depth = distance_transform_edt(ink)
        _, travel = wicking_basins(ink, depth, [(1, 1)])
        field = np.zeros(ink.shape)
        field[ink] = travel
        self.assertGreater(field[1, 10], field[12, 10])


if __name__ == '__main__':
    unittest.main()
