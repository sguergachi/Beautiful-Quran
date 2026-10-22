"""Render the study's real shader to check first/repeat wet-weight parity.

Optional offline render gate: requires skia-python and numpy. No Android or
production dependency. Run with `python3 tools/test_paper_ink_shader.py`.
"""
import colorsys
import json
from pathlib import Path
import unittest

import numpy as np
import skia
from PIL import Image

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/paper-ink-study'
DRY_REPEAT = np.array([194, 95, 59]) * .7 + np.array([.86, .82, .77]) * 255 * .3
WET_REPEAT = np.array([194, 95, 59])


def mix_feather(method):
    """The shader's feather share of letterDuration for each spread method."""
    return .1 + .02 * method


class PaperInkShaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.effect = skia.RuntimeEffect.MakeForShader((ASSETS / 'ink.agsl').read_text())
        cls.words = []
        for manifest, prefix in [('words.json', 'word'), ('english.json', 'word-en')]:
            for spec in json.loads((ASSETS / manifest).read_text()):
                if spec['ayah'] != 2:
                    continue
                children = {}
                for name, suffix in [('field', 'field'), ('normals', 'normal')]:
                    image = skia.Image.open(str(ASSETS / f'{prefix}-2-{spec["position"]}-{suffix}.png'))
                    children[name] = image.makeRawShader(
                        samplingOptions=skia.SamplingOptions(skia.FilterMode.kLinear))
                cls.words.append((spec, children))

    def render(self, word, repeat, wet, weight, method, tint=1, progress=1, retained=1, lead=0):
        spec, children = word
        builder = skia.RuntimeShaderBuilder(self.effect)
        for name, child in children.items():
            builder.setChild(name, child)
        uniforms = dict(progress=float(progress), reflectionStrength=0., lightSoftness=0., retained=float(retained),
                        wet=float(wet), weight=float(weight), aa=.9, repeatColor=[194/255, 95/255, 59/255],
                        repeatPass=float(repeat), repeatTint=float(tint), spreadMethod=float(method),
                        letterDuration=spec['letterDuration'], origin=[0., 0.], lead=float(lead))
        for name, value in uniforms.items():
            builder.setUniform(name, value)
        surface = skia.Surface(spec['width'], spec['height'])
        surface.getCanvas().drawPaint(skia.Paint(Shader=builder.makeShader()))
        return surface.makeImageSnapshot().toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)

    @staticmethod
    def coverage(pixels, ink_blue):
        return np.clip((232 - pixels[:, :, 2].astype(float)) / (232 - ink_blue), 0, 1)

    def test_both_wet_passes_gain_the_same_weight_in_both_scripts_and_methods(self):
        for word in self.words:
            for method in (0, 1):
                for weight in (.875, 3.5):
                    with self.subTest(word=word[0], method=method, weight=weight):
                        first = self.coverage(self.render(word, 0, 1, weight, method), 8) > .5
                        repeat = self.coverage(self.render(word, 1, 1, weight, method), WET_REPEAT[2]) > .5
                        dry = self.coverage(self.render(word, 1, 0, weight, method), DRY_REPEAT[2]) > .5
                        self.assertGreater((first & repeat).sum() / (first | repeat).sum(), .99)
                        self.assertGreater(repeat.sum(), dry.sum() * 1.05)

    def test_orange_held_at_full_strength_still_thins_as_the_wet_ink_dries(self):
        for word in self.words:
            for method in (0, 1):
                with self.subTest(word=word[0], method=method):
                    areas = [self.coverage(self.render(word, 1, wet, .875, method), WET_REPEAT[2]).sum()
                             for wet in (1, .5, .25, 0)]
                    self.assertTrue(all(a > b for a, b in zip(areas, areas[1:])), areas)
                    dry_orange = self.coverage(self.render(word, 1, 0, .875, method), DRY_REPEAT[2])
                    dry_black = self.coverage(self.render(word, 0, 0, .875, method), 46)
                    np.testing.assert_allclose(dry_orange, dry_black, atol=.006)
                    np.testing.assert_array_equal(self.render(word, 1, 0, .875, method),
                                                  self.render(word, 1, 0, 0, method))

    def test_repeat_dries_paler_and_less_saturated_before_releasing_to_charcoal(self):
        for word in self.words:
            regular = self.render(word, 0, 0, 0, 0)
            core = self.coverage(regular, 46) > .999
            wet = np.median(self.render(word, 1, 1, .875, 0)[core, :3], axis=0)
            dry = np.median(self.render(word, 1, 0, .875, 0)[core, :3], axis=0)
            np.testing.assert_allclose(wet, WET_REPEAT, atol=1)
            np.testing.assert_allclose(dry, DRY_REPEAT, atol=1)
            wet_hue, wet_light, wet_sat = colorsys.rgb_to_hls(*(wet / 255))
            dry_hue, dry_light, dry_sat = colorsys.rgb_to_hls(*(dry / 255))
            self.assertGreater(dry_light, wet_light)
            self.assertLess(dry_sat, wet_sat)
            self.assertLess(abs(dry_hue - wet_hue), .02)
            np.testing.assert_array_equal(self.render(word, 1, 0, .875, 0, tint=0), regular)

    def test_repeat_gains_weight_during_letter_filling_before_the_word_finishes(self):
        for word in self.words:
            for method in (0, 1):
                dry = self.coverage(self.render(word, 0, 0, .875, method), 46)
                outside = dry < .001
                growth = []
                for progress in (.25, .5, .75):
                    with self.subTest(word=word[0], method=method, progress=progress):
                        first = self.coverage(self.render(word, 0, 1, .875, method, progress=progress), 8)
                        repeat = self.coverage(self.render(word, 1, 1, .875, method, progress=progress), WET_REPEAT[2])
                        # Outside the regular contour, the retained repeat base
                        # contributes nothing: this is the added wet weight.
                        np.testing.assert_allclose(first[outside], repeat[outside], atol=.012)
                        growth.append(repeat[outside].sum())
                # Interior pools need not reach the outline at the first sample.
                self.assertGreater(max(growth), 1)

    def test_lead_fade_feathers_about_one_letter_ahead_of_the_wet_edge(self):
        for word in self.words:
            spec = word[0]
            prefix = 'word-en' if 'text' in spec else 'word'
            full_field = np.asarray(Image.open(ASSETS / f'{prefix}-2-{spec["position"]}-field.png')).astype(float)
            field = full_field[2::4, 2::4][:spec['height'], :spec['width']]
            # A pixel is untouched only when all eight subpixel taps lie beyond
            # the lead. Sample the same continuous 4x field as the shader;
            # using just the centre misclassifies pixels on steep arrival edges.
            taps = []
            for dx, dy in [(-7, -1), (-5, 5), (-3, -7), (-1, 3),
                           (1, -3), (3, 7), (5, -5), (7, 1)]:
                x = np.arange(spec['width']) * 4 + 1.5 + dx / 4
                y = np.arange(spec['height']) * 4 + 1.5 + dy / 4
                ix, iy = np.floor(x).astype(int), np.floor(y).astype(int)
                fx, fy = (x - ix)[None, :, None], (y - iy)[:, None, None]
                sample = 0
                for oy in (0, 1):
                    for ox in (0, 1):
                        sample = sample + full_field[np.clip(iy + oy, 0, full_field.shape[0] - 1)[:, None],
                            np.clip(ix + ox, 0, full_field.shape[1] - 1)[None, :]] * \
                            (fx if ox else 1 - fx) * (fy if oy else 1 - fy)
                taps.append(sample)
            taps = np.array(taps)
            d = spec['letterDuration']
            slot = (1 - .24 * d) / max(1, 2.8 / d - 1.8)
            for method in (0, 1):
                arrival = field[:, :, method] / 255
                feather = max(.006, d * mix_feather(method))
                with self.subTest(word=spec, method=method):
                    plain, led = (self.render(word, 0, 1, .875, method, progress=.4, retained=0, lead=lead)
                                  [:, :, 2].astype(int) for lead in (0, 1))
                    ink = field[:, :, 2] > 140
                    near = ink & (arrival > .4 + feather + .01) & (arrival < .4 + slot * .6)
                    far = ink & (taps[:, :, :, method].min(axis=0) / 255 > .4 + slot + feather + .02)
                    if near.any():
                        self.assertLess(led[near].mean(), plain[near].mean() - 10)
                    np.testing.assert_array_equal(led[far], plain[far])
                    # The repeat keeps its clean dry charcoal until the orange arrives.
                    np.testing.assert_array_equal(self.render(word, 1, 1, .875, method, progress=.4, lead=1),
                                                  self.render(word, 1, 1, .875, method, progress=.4))


if __name__ == '__main__':
    unittest.main()
