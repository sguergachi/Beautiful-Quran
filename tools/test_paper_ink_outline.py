"""Wet-outline regression against independent supersampled font geometry.

Includes the user's pictured 6:150 word 16 at reading sizes, at both default
and full pooling. This reference rasterizes the font again at 16x: integrating
more samples of the existing low-resolution SDF cannot pass for a contour fix.
"""
import unittest
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt, map_coordinates

from build_paper_ink_study import ARABIC_FONT
from paper_ink_outline import outline_distance


def binary_distance(alpha):
    ink = alpha > .5
    d = distance_transform_edt(ink) - distance_transform_edt(~ink)
    d -= np.sign(d) * .5
    edge = (alpha > 0) & (alpha < 1)
    d[edge] = alpha[edge] - .5
    return d


class PaperInkOutlineTest(unittest.TestCase):
    def test_pictured_follow_word_tracks_an_independent_high_resolution_outline(self):
        errors = []
        text = 'تَتَّبِعۡ'
        for size in (40, 64, 80, 104):
            font = ImageFont.truetype(str(ARABIC_FONT), size)
            box = font.getbbox(text, direction='rtl')
            w, h = box[2] - box[0] + 12, box[3] - box[1] + 12

            def raster(scale):
                image = Image.new('L', (w * scale, h * scale))
                ImageDraw.Draw(image).text(((6 - box[0]) * scale, (6 - box[1]) * scale), text,
                    font=ImageFont.truetype(str(ARABIC_FONT), size * scale), fill=255, direction='rtl')
                return np.array(image) / 255

            alpha = raster(2)
            old = binary_distance(alpha) / 2
            contour = outline_distance(alpha, 2, old)
            high = raster(16)
            reference_distance = binary_distance(high) / 16
            base = raster(1)
            yy, xx = np.indices((h, w))
            for weight in (.875 * size / 160, 3.5 * size / 160):
                expected = np.clip((reference_distance + weight) * 16 + .5, 0, 1)
                # The printed pixel's original coverage is fixed in all wet
                # samples, just as it is in the actual production material.
                expected = np.maximum(expected.reshape(h, 16, w, 16), base[:, None, :, None]).mean((1, 3))
                pair = []
                for distance in (old, contour):
                    taps = []
                    for dy in (-.25, .25):
                        for dx in (-.25, .25):
                            d = map_coordinates(distance, [(yy + .5 + dy) * 2 - .5,
                                (xx + .5 + dx) * 2 - .5], order=1, mode='nearest')
                            t = np.clip((d + weight) / .45 + .5, 0, 1)
                            taps.append(np.maximum(base, t * t * (3 - 2 * t)))
                    pair.append(np.mean((np.mean(taps, axis=0) - expected) ** 2))
                errors.append(pair)
        old, reconstructed = np.mean(errors, axis=0)
        print(f'Independent glyph outline MSE: binary={old:.6f}, contour={reconstructed:.6f}')
        self.assertLess(reconstructed, old * .9)


if __name__ == '__main__':
    unittest.main()
