"""Geometry regressions for the offline Paper material experiment."""
import re
import sqlite3
import unittest

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt, label

from build_paper_ink_study import (
    ARABIC_FONT, ENGLISH_FONT, ROOT, SS, filling_field, liquid_surface, single_origin_wave, wicking_basins,
)


def material(text, arabic=False, flowing=True):
    path, size, direction = (ARABIC_FONT, 160, 'rtl') if arabic else (ENGLISH_FONT, 80, 'ltr')
    font = ImageFont.truetype(str(path), size)
    box = font.getbbox(text, direction=direction)
    image = Image.new('L', ((box[2] - box[0] + 16) * SS, (box[3] - box[1] + 16) * SS))
    ImageDraw.Draw(image).text(((8 - box[0]) * SS, (8 - box[1]) * SS), text,
                              font=ImageFont.truetype(str(path), size * SS), fill=255, direction=direction)
    ink = np.array(image) > 127
    depth = (distance_transform_edt(ink) - distance_transform_edt(~ink)) / SS
    arrival, duration = filling_field(text, ink, depth, box, font.getmetrics()[0], path, size, flowing=flowing)
    return ink, depth, arrival, duration


class PaperInkStudyTest(unittest.TestCase):
    def test_one_origin_advances_through_a_connected_stroke_without_new_islands(self):
        ink = np.zeros((60, 420), dtype=bool)
        ink[10:50, 10:410] = True
        depth = distance_transform_edt(ink) / SS
        for flowing in (False, True):
            field = single_origin_wave(ink, depth, np.zeros(ink.shape, dtype=int), [0], False, flowing)
            self.assertEqual(1, np.count_nonzero(ink & (field == 0)))
            for phase in np.linspace(.01, .99, 99):
                _, count = label(ink & (field <= phase), np.ones((3, 3)))
                self.assertEqual(1, count)
            self.assertLess(np.median(field[ink & (np.indices(ink.shape)[1] < 100)]),
                            np.median(field[ink & (np.indices(ink.shape)[1] > 300)]))

    def test_detached_mark_inherits_wave_arrival_instead_of_starting_ahead(self):
        ink = np.zeros((80, 320), dtype=bool)
        ink[40:65, 10:300] = True
        ink[10:20, 250:260] = True
        field = single_origin_wave(ink, distance_transform_edt(ink) / SS,
                                   np.zeros(ink.shape, dtype=int), [0], False)
        self.assertGreater(field[10:20, 250:260].min(), field[40:65, 245:265].min())
        self.assertEqual(1, np.count_nonzero(ink & (field == 0)))

    def test_liquid_surface_does_not_flatten_when_the_font_raster_is_half_size(self):
        fields = []
        for size in (160, 80):
            unit = size / 160
            yy, xx = np.indices((int(160 * unit), int(160 * unit)))
            distance = (50 * unit - np.hypot(xx - 80 * unit, yy - 80 * unit)) / SS
            surface = liquid_surface(distance, size)
            fields.append(surface[::2, ::2] if size == 160 else surface)
        # Compare the film's RGB data at the same physical points. The
        # one-level allowance is for finite differences and quantization.
        self.assertLessEqual(np.max(np.abs(fields[0].astype(int) - fields[1].astype(int))), 1)

    def test_both_modes_have_one_advancing_front_on_the_pictured_arabic_and_a_loop(self):
        for text, arabic in [('O', False), ('ٱلرَّحِيمِ', True), ('ٱلۡحَمۡدُ', True)]:
            for flowing in (False, True):
                ink, _, arrival, _ = material(text, arabic, flowing=flowing)
                components, _ = label(ink, np.ones((3, 3)))
                for phase in np.linspace(.02, .98, 49):
                    for part in np.unique(components[ink]):
                        _, count = label((components == part) & (arrival < phase), np.ones((3, 3)))
                        self.assertLessEqual(count, 1, (text, flowing, phase, part))

    def test_modes_keep_the_same_contour_and_window_with_different_flow_rates(self):
        ink, depth, first, duration = material('ٱلۡحَمۡدُ', True, flowing=False)
        other, other_depth, second, second_duration = material('ٱلۡحَمۡدُ', True, flowing=True)
        np.testing.assert_array_equal(ink, other)
        np.testing.assert_array_equal(depth, other_depth)
        self.assertEqual(duration, second_duration)
        self.assertGreater(np.mean(np.abs(first[ink] - second[ink])), .005)

    def test_english_detached_letters_continue_left_to_right(self):
        ink, _, arrival, _ = material('OO')
        pieces, count = label(ink)
        self.assertEqual(2, count)
        ordered = sorted(range(1, count + 1), key=lambda p: np.nonzero(pieces == p)[1].mean())
        left, right = (arrival[pieces == p] for p in ordered)
        self.assertLess(np.median(left), np.median(right))
        self.assertGreater(right.min(), left.max())

    def test_arabic_detached_letters_continue_right_to_left(self):
        ink, _, arrival, _ = material('اا', arabic=True)
        pieces, count = label(ink)
        self.assertEqual(2, count)
        ordered = sorted(range(1, count + 1), key=lambda p: -np.nonzero(pieces == p)[1].mean())
        right, left = (arrival[pieces == p] for p in ordered)
        self.assertLess(np.median(right), np.median(left))
        self.assertGreater(left.min(), right.max())

    def test_whole_word_ligatures_keep_their_letter_pacing_window(self):
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
                self.assertGreaterEqual(arrival[ink].min() - duration * .12, -1 / 255)
                self.assertLessEqual(arrival[ink].max() + duration * .12, 1 + 1 / 255)

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
