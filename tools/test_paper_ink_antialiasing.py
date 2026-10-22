"""Direct Skia checks of the production wet-ink shader's pixel coverage.

Requires skia-python, numpy and the study generator's Python dependencies.
Run: python3 tools/test_paper_ink_antialiasing.py
"""
from pathlib import Path
import unittest

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import skia
from scipy.ndimage import distance_transform_edt

import build_paper_ink_study as geometry

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'app/src/main/assets/paper-ink/ink.agsl').read_text()
# Same material, sampled directly. At high resolution, this integrates the
# pixel area independently of the production four-tap approximation.
POINT_SOURCE = SOURCE[:SOURCE.index('// Integrate the wet material')] + '''
half4 main(float2 p) {
    return inkAt(p, normals.eval(floor(p) + 0.5).b, aa);
}
'''


def word(text, font, size, rtl, letters):
    scale = 2
    geometry.SS = scale
    ref = ImageFont.truetype(str(font), size)
    box = ref.getbbox(text, direction='rtl' if rtl else 'ltr')
    w, h = box[2] - box[0] + 12, box[3] - box[1] + 12
    image = Image.new('L', (w * scale, h * scale))
    ImageDraw.Draw(image).text(((6 - box[0]) * scale, (6 - box[1]) * scale), text,
        font=ImageFont.truetype(str(font), size * scale), fill=255, direction='rtl' if rtl else 'ltr')
    alpha = np.array(image) / 255
    ink = alpha > .5
    distance = (distance_transform_edt(ink) - distance_transform_edt(~ink)) / scale
    distance -= np.sign(distance) / (2 * scale)
    edge = (alpha > 0) & (alpha < 1)
    distance[edge] = (alpha[edge] - .5) / scale
    xs = np.nonzero(ink)[1]
    centers = np.linspace(xs.max() if rtl else xs.min(), xs.min() if rtl else xs.max(), letters + 2)[1:-1]
    baseline = (6 - box[1] + ref.getmetrics()[0]) * scale
    duration = 2.8 / (letters + 1.8)
    arrivals = [geometry.swelling_channels(ink, distance, centers, rtl, flowing, size / 160, baseline)
                for flowing in (False, True)]
    field = np.full((*alpha.shape, 4), 255, dtype='uint8')
    for i, arrival in enumerate(arrivals):
        field[:, :, i] = np.rint((.12 * duration + (1 - .24 * duration) * arrival) * 255)
    field[:, :, 2] = np.clip(np.rint(128 + distance * 16), 0, 255)
    normals = geometry.liquid_surface(distance, size)
    normals = np.rint(normals.reshape(h, scale, w, scale, 4).mean(axis=(1, 3))).astype('uint8')
    # Stand-in for the captured original glyph, independent of wet AA.
    coverage = np.rint(alpha.reshape(h, scale, w, scale).mean(axis=(1, 3)) * 255).astype('uint8')
    normals[:, :, 2] = coverage
    children = {}
    for name, data in [('field', field), ('normals', normals)]:
        children[name] = skia.Image.fromarray(data, colorType=skia.ColorType.kRGBA_8888_ColorType).makeRawShader(
            samplingOptions=skia.SamplingOptions(skia.FilterMode.kLinear))
    return dict(width=w, height=h, unit=size / 160, duration=duration, children=children, coverage=coverage)


class PaperInkAntialiasingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.effects = {'aa': skia.RuntimeEffect.MakeForShader(SOURCE),
                       'point': skia.RuntimeEffect.MakeForShader(POINT_SOURCE)}
        cls.words = [word('ٱللَّهِ', geometry.ARABIC_FONT, 40, True, 4),
                     word('ٱلرَّحۡمَٰنِ', geometry.ARABIC_FONT, 40, True, 6),
                     word('Merciful', geometry.ENGLISH_FONT, 24, False, 8)]

    def render(self, word, effect='aa', resolution=1, **values):
        builder = skia.RuntimeShaderBuilder(self.effects[effect])
        for name, child in word['children'].items():
            builder.setChild(name, child)
        uniforms = dict(fieldScale=2., unit=word['unit'], progress=1., reflectionStrength=.5,
            lightSoftness=0., retained=1., wet=1., weight=.875 * word['unit'], aa=.9 / resolution,
            dryInk=[52/255, 50/255, 46/255], wetInk=[6/255, 7/255, 8/255], unreadAlpha=.2661,
            repeatColor=[194/255, 95/255, 59/255], repeatPass=0., repeatTint=0., spreadMethod=0.,
            letterDuration=word['duration'], lead=1., origin=[0., 0.])
        uniforms.update(values)
        for name, value in uniforms.items():
            builder.setUniform(name, value)
        surface = skia.Surface(word['width'] * resolution, word['height'] * resolution)
        surface.getCanvas().scale(resolution, resolution)
        surface.getCanvas().drawPaint(skia.Paint(Shader=builder.makeShader()))
        return surface.makeImageSnapshot().toarray(colorType=skia.ColorType.kRGBA_8888_ColorType,
            alphaType=skia.AlphaType.kPremul_AlphaType)

    def test_dry_glyphs_remain_pixel_exact_including_held_repeat_and_unread(self):
        for word in self.words:
            for repeat, tint, retained, progress in [(0, 0, 1, 1), (1, 1, 1, 1), (0, 0, 0, 0)]:
                options = dict(wet=0., repeatPass=float(repeat), repeatTint=float(tint),
                               retained=float(retained), progress=float(progress))
                actual = self.render(word, **options)
                np.testing.assert_array_equal(actual, self.render(word, 'point', **options))
                if retained:
                    np.testing.assert_array_equal(actual[:, :, 3], word['coverage'])

    def test_wet_sampling_tracks_pixel_area_more_closely_than_one_point(self):
        errors = []
        for word in self.words:
            for progress in (.35, .7, 1.):
                for repeat in (0., 1.):
                    for pooling in (.875, 3.5):
                        for method in (0., 1.):
                            options = dict(progress=progress, repeatPass=repeat, repeatTint=repeat,
                                           weight=pooling * word['unit'], spreadMethod=method)
                            reference = self.render(word, 'point', 8, **options).astype(float)
                            reference = reference.reshape(word['height'], 8, word['width'], 8, 4).mean(axis=(1, 3))
                            point = self.render(word, 'point', **options).astype(float)
                            filtered = self.render(word, **options).astype(float)
                            errors.append([np.mean((point - reference) ** 2), np.mean((filtered - reference) ** 2)])
                            self.assertTrue((filtered[:, :, :3] <= filtered[:, :, 3:4]).all())
        point_error, filtered_error = np.mean(errors, axis=0)
        print(f'Wet pixel-area MSE: point={point_error:.4f}, antialiased={filtered_error:.4f}')
        self.assertLess(filtered_error, point_error * .85)


if __name__ == '__main__':
    unittest.main()
