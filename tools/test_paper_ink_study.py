"""Geometry regressions for the offline Paper material experiment."""
import re
import sqlite3
import unittest

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt, label

from build_paper_ink_study import (
    ARABIC_FONT, ENGLISH_FONT, ROOT, SS, filling_field, liquid_surface, swelling_channels,
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
    @staticmethod
    def core_line_end(arrival, piece):
        """When a piece's wet core line has reached every column it occupies."""
        return max(arrival[:, x][piece[:, x]].min() for x in range(piece.shape[1]) if piece[:, x].any())

    def test_front_sweeps_along_the_baseline_not_up_a_stroke(self):
        # A tall upright stroke beside a low horizontal one: the column, not the
        # path along the ink, sets arrival.
        ink = np.zeros((200, 300), dtype=bool)
        ink[10:190, 220:240] = True
        ink[160:190, 20:220] = True
        depth = distance_transform_edt(ink) / SS
        for flowing in (False, True):
            field = swelling_channels(ink, depth, [230, 120], True, flowing)
            stem = field[30:130, 222:238]
            self.assertLess(np.abs(stem.mean(axis=1) - stem.mean(axis=1)[50]).max(), 1e-6, flowing)
            self.assertLess(field[175, 230], field[175, 100])
            self.assertLess(field[175, 100], field[175, 30])

    def test_a_leaning_stem_is_fed_from_its_foot_not_diagonally(self):
        # An alif leaning left as it rises, standing on a baseline stroke.
        ink = np.zeros((220, 400), dtype=bool)
        for y in range(10, 190):
            x = 250 - (190 - y) // 6
            ink[y, x - 10:x + 10] = True
        ink[170:200, 20:300] = True
        depth = distance_transform_edt(ink) / SS
        for flowing in (False, True):
            plain = swelling_channels(ink, depth, [250, 120], True, flowing)
            footed = swelling_channels(ink, depth, [250, 120], True, flowing, baseline=185)
            # Rows clear of the base stroke's swell neighbourhood.
            top, low = (np.flatnonzero(ink[y]) for y in (30, 120))
            core = lambda field, y, xs: field[y, xs[len(xs) // 2]]
            self.assertGreater(core(plain, 30, top) - core(plain, 120, low), .02, flowing)
            # The residual is the stem's pixel staircase (about an eighth of the lean).
            self.assertLess(abs(core(footed, 30, top) - core(footed, 120, low)), .01, flowing)
            self.assertLess(footed[185, 260], footed[185, 100])

    def test_each_letter_gets_an_equal_time_slot(self):
        # Two narrow letters then one wide flourish, right to left: the narrow
        # pair takes well over its small share of width (smoothing softens the
        # exact two thirds so the speed never jumps).
        ink = np.zeros((40, 420), dtype=bool)
        ink[10:30, 10:410] = True
        depth = distance_transform_edt(ink) / SS
        row = swelling_channels(ink, depth, [390, 350, 170], True, True)[20]
        even = swelling_channels(ink, depth, [343, 210, 77], True, True)[20]
        self.assertGreater(row[340], 1.5 * even[340])
        # Monotone away from the end caps, where the rims swell last.
        self.assertTrue(np.all(np.diff(row[30:390]) <= 1e-9))

    def test_strokes_swell_from_their_core_to_both_edges(self):
        ink = np.zeros((60, 400), dtype=bool)
        ink[10:50, 10:390] = True
        depth = distance_transform_edt(ink) / SS
        lags = []
        for flowing in (False, True):
            field = swelling_channels(ink, depth, [300, 200, 100], False, flowing)
            core, top, bottom = (field[y, 100:300].mean() for y in (29, 10, 49))
            self.assertLess(core, top)
            self.assertAlmostEqual(top, bottom, places=6)
            lags.append(top - core)
        self.assertGreater(lags[0], lags[1])

    def test_detached_mark_arrives_with_the_body_beneath_it(self):
        ink = np.zeros((80, 320), dtype=bool)
        ink[40:65, 10:300] = True
        ink[10:20, 250:260] = True
        for flowing in (False, True):
            field = swelling_channels(ink, distance_transform_edt(ink) / SS, [230, 80], False, flowing)
            # Within the swell of the body's own column (under one letter slot).
            self.assertLess(abs(field[10:20, 250:260].mean() - field[40:65, 250:260].mean()), .4, flowing)

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

    def test_pictured_arabic_core_lines_arrive_in_reading_order(self):
        for text in ('ٱللَّهِ', 'ٱلرَّحِيمِ', 'ٱلۡحَمۡدُ'):
            for flowing in (False, True):
                with self.subTest(text=text, flowing=flowing):
                    ink, _, arrival, _ = material(text, True, flowing=flowing)
                    # Each column's wet core line; small steps where a column
                    # holds only a hairline still swelling from its neighbour.
                    cores = [arrival[:, x][ink[:, x]].min() for x in range(ink.shape[1]) if ink[:, x].any()]
                    self.assertTrue(all(np.diff(cores) < .05))
                    self.assertGreater(cores[0], cores[-1])

    def test_study_builds_are_reproducible(self):
        first = material('ٱلرَّحۡمَٰنِ', True, flowing=False)[2]
        np.testing.assert_array_equal(first, material('ٱلرَّحۡمَٰنِ', True, flowing=False)[2])

    def test_modes_keep_the_same_contour_and_window_with_different_fronts(self):
        ink, depth, first, duration = material('ٱلۡحَمۡدُ', True, flowing=False)
        other, other_depth, second, second_duration = material('ٱلۡحَمۡدُ', True, flowing=True)
        np.testing.assert_array_equal(ink, other)
        np.testing.assert_array_equal(depth, other_depth)
        self.assertEqual(duration, second_duration)
        # Same letter pacing; only the front's meniscus and fibre differ.
        self.assertGreater(np.mean(np.abs(first[ink] - second[ink])), .002)

    def test_english_detached_letters_continue_left_to_right(self):
        ink, _, arrival, _ = material('OO')
        pieces, count = label(ink)
        self.assertEqual(2, count)
        ordered = sorted(range(1, count + 1), key=lambda p: np.nonzero(pieces == p)[1].mean())
        left, right = (arrival[pieces == p] for p in ordered)
        self.assertLess(np.median(left), np.median(right))
        # The next letter starts once the previous one is mostly inked; only its
        # finest hairlines may still be swelling.
        self.assertGreater(right.min(), np.median(left))

    def test_arabic_detached_letters_continue_right_to_left(self):
        ink, _, arrival, _ = material('اا', arabic=True)
        pieces, count = label(ink)
        self.assertEqual(2, count)
        ordered = sorted(range(1, count + 1), key=lambda p: -np.nonzero(pieces == p)[1].mean())
        right, left = (arrival[pieces == p] for p in ordered)
        self.assertLess(np.median(right), np.median(left))
        self.assertGreater(left.min(), self.core_line_end(arrival, pieces == ordered[0]))

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

if __name__ == '__main__':
    unittest.main()
