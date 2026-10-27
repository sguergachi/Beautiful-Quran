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
from paper_ink_outline import outline_distance

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'app/src/main/assets/paper-ink/ink.agsl').read_text()
PLAIN_SOURCE = SOURCE.replace('organicPooling(p, filmWet, unit, distance, coverageAA)', 'weight * filmWet')
FAINT_SOURCE = SOURCE.replace('min(weight * 0.6, 0.6 * scale)', 'min(weight * 0.12, 0.12 * scale)')
# Same material, sampled directly. At high resolution, this integrates the
# pixel area independently of the production eight-tap approximation.
POINT_SOURCE = SOURCE[:SOURCE.index('// Integrate the wet material')] + '''
uniform float2 samplePhase;
half4 main(float2 p) {
    float base = normals.eval(floor(p + samplePhase) + 0.5 - samplePhase).b;
    float unread = base * unreadAlpha;
    return mix(inkAt(p, base, aa), half4(dryInk * unread, unread), evaporation);
}
'''
QUAD_SOURCE = SOURCE[:SOURCE.index('// Integrate the wet material')] + '''
half4 main(float2 p) {
    float base = normals.eval(p).b;
    float o = aa * (0.25 / 0.9);
    half4 ink = (inkAt(p + float2(-o, -o), base, aa * 0.5) +
        inkAt(p + float2(o, -o), base, aa * 0.5) +
        inkAt(p + float2(-o, o), base, aa * 0.5) +
        inkAt(p + float2(o, o), base, aa * 0.5)) * 0.25;
    float unread = base * unreadAlpha;
    return mix(ink, half4(dryInk * unread, unread), evaporation);
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
    distance = outline_distance(alpha, scale, distance)
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
                       'plain': skia.RuntimeEffect.MakeForShader(PLAIN_SOURCE),
                       'faint': skia.RuntimeEffect.MakeForShader(FAINT_SOURCE),
                       'point': skia.RuntimeEffect.MakeForShader(POINT_SOURCE),
                       'quad': skia.RuntimeEffect.MakeForShader(QUAD_SOURCE)}
        cls.words = [word('ٱللَّهِ', geometry.ARABIC_FONT, 40, True, 4),
                     word('ٱلرَّحۡمَٰنِ', geometry.ARABIC_FONT, 40, True, 6),
                     word('Merciful', geometry.ENGLISH_FONT, 24, False, 8)]

    def render(self, word, effect='aa', resolution=1, pixel_phase=(0., 0.), **values):
        builder = skia.RuntimeShaderBuilder(self.effects[effect])
        for name, child in word['children'].items():
            builder.setChild(name, child)
        uniforms = dict(fieldScale=2., unit=word['unit'], progress=1., reflectionStrength=.5,
            lightSoftness=0., retained=1., wet=1., underProgress=1., underWet=0.,
            weight=.875 * word['unit'], aa=.9 / resolution,
            dryInk=[52/255, 50/255, 46/255], wetInk=[6/255, 7/255, 8/255], unreadAlpha=.2661,
            repeatColor=[194/255, 95/255, 59/255], repeatPass=0., repeatTint=0., spreadMethod=0.,
            letterDuration=word['duration'], lead=1., leadEntry=0., origin=[0., 0.], evaporation=0.)
        uniforms.update(values)
        for name, value in uniforms.items():
            builder.setUniform(name, value)
        if effect == 'point':
            builder.setUniform('samplePhase', list(pixel_phase))
        surface = skia.Surface(word['width'] * resolution, word['height'] * resolution)
        surface.getCanvas().scale(resolution, resolution)
        surface.getCanvas().translate(*pixel_phase)
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

    def test_replacing_printed_ink_does_not_stack_fractional_glyph_edges(self):
        for word in self.words:
            printed = self.render(word, progress=-1., wet=0., retained=0.)
            for progress in (-1., .3, 1.):
                replacement = self.render(word, progress=progress, wet=1., retained=0.)

                def compose(binary):
                    mask = np.zeros_like(printed)
                    mask[:, :, 3] = np.where(word['coverage'] > 0, 255, 0) if binary else word['coverage']
                    surface = skia.Surface(word['width'], word['height'])
                    canvas = surface.getCanvas()

                    def image(pixels):
                        return skia.Image.fromarray(pixels, colorType=skia.ColorType.kRGBA_8888_ColorType,
                            alphaType=skia.AlphaType.kPremul_AlphaType)

                    canvas.drawImage(image(printed), 0, 0)
                    canvas.drawImage(image(mask), 0, 0, skia.SamplingOptions(),
                        skia.Paint(BlendMode=skia.BlendMode.kDstOut))
                    canvas.drawImage(image(replacement), 0, 0)
                    return surface.makeImageSnapshot().toarray(colorType=skia.ColorType.kRGBA_8888_ColorType,
                        alphaType=skia.AlphaType.kPremul_AlphaType)

                with self.subTest(word=word['width'], progress=progress):
                    np.testing.assert_array_equal(compose(True), replacement)
                    if progress < 0:
                        self.assertGreater(np.abs(compose(False).astype(int) - replacement.astype(int)).max(), 5)

    def test_english_word_entry_does_not_pop_its_leading_letter(self):
        # The one-letter lead may not anticipate the next printed English word.
        # Its first active frame must therefore still equal that unread word.
        for text in ('in', 'Allah', 'and', 'His', 'Messenger'):
            specimen = word(text, geometry.ENGLISH_FONT, 48, False, len(text))
            unread = self.render(specimen, progress=-1., wet=0., retained=0.)
            entering = self.render(specimen, progress=0., wet=1., retained=0., leadEntry=1.)
            np.testing.assert_array_equal(entering, unread, err_msg=text)

            # Disabling the English entry rule reproduces the reported flash.
            old_entry = self.render(specimen, progress=0., wet=1., retained=0.)
            self.assertGreater(np.abs(old_entry.astype(int) - unread.astype(int)).max(), 10)
            next_frame = self.render(specimen, progress=.0001, wet=1., retained=0., leadEntry=1.)
            np.testing.assert_allclose(next_frame, unread, atol=1., err_msg=text)

    def test_english_entry_keeps_a_soft_continuous_fill_through_both_spread_methods(self):
        for text in ('in', 'Allah', 'Messenger'):
            specimen = word(text, geometry.ENGLISH_FONT, 48, False, len(text))
            for method in (0., 1.):
                options = dict(wet=0., retained=0., leadEntry=1., spreadMethod=method)
                previous = self.render(specimen, progress=-1., **options)[:, :, 3].astype(int)
                partial = False
                for progress in np.linspace(0., 1., 101):
                    actual = self.render(specimen, progress=float(progress), **options)[:, :, 3].astype(int)
                    self.assertTrue((actual >= previous).all())
                    partial |= bool(((actual > specimen['coverage'] * .27) &
                                     (actual < specimen['coverage'] * .95)).any())
                    previous = actual
                self.assertTrue(partial)
                np.testing.assert_array_equal(previous, specimen['coverage'])
                for phase in (.03, .5):
                    first = self.render(specimen, progress=phase, wet=.6, retained=0.,
                        leadEntry=1., spreadMethod=method)
                    repeated = self.render(specimen, progress=0., wet=1., retained=1.,
                        repeatPass=1., repeatTint=1., underProgress=phase, underWet=.6,
                        leadEntry=1., spreadMethod=method)
                    np.testing.assert_allclose(repeated, first, atol=1.)
                    evaporated = self.render(specimen, progress=phase, wet=.6, retained=0.,
                        leadEntry=1., spreadMethod=method, evaporation=1.)
                    unread = self.render(specimen, progress=-1., wet=0., retained=0., leadEntry=1.)
                    np.testing.assert_array_equal(evaporated, unread)

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

    def test_paper_tooth_is_confined_to_a_small_wet_outline_envelope(self):
        changed = 0
        for specimen in self.words:
            for pooling in (.28, 1.):
                weight = pooling * 3.5 * specimen['unit']
                bound = min(weight * .6, .6 * specimen['unit'])
                for method in (0., 1.):
                    for repeat in (0., 1.):
                        options = dict(reflectionStrength=0., spreadMethod=method,
                                       repeatPass=repeat, repeatTint=repeat)
                        actual = self.render(specimen, weight=weight, **options).astype(int)
                        plain = self.render(specimen, 'plain', weight=weight, **options).astype(int)
                        lower = self.render(specimen, 'plain', weight=weight-bound, **options)[:, :, 3].astype(int)
                        upper = self.render(specimen, 'plain', weight=weight+bound, **options)[:, :, 3].astype(int)
                        self.assertTrue((actual[:, :, 3] >= lower - 1).all())
                        self.assertTrue((actual[:, :, 3] <= upper + 1).all())
                        core = specimen['coverage'] == 255
                        np.testing.assert_array_equal(actual[core], plain[core])
                        changed += np.count_nonzero(actual[:, :, 3] != plain[:, :, 3])
        self.assertGreater(changed, 0, 'the wet outline must actually gain paper tooth')

    def test_stock_pooling_exceeds_the_superseded_faint_edge_control(self):
        # This measures paint contrast, not a universal perceptual threshold.
        for text, font, size, rtl, letters in (
                ('أُمِرُوٓاْ', geometry.ARABIC_FONT, 104, True, 5),
                ('Merciful', geometry.ENGLISH_FONT, 64, False, 8)):
            specimen = word(text, font, size, rtl, letters)
            options = dict(weight=.28 * 3.5 * specimen['unit'])
            plain = self.render(specimen, 'plain', **options)[:, :, 3].astype(float)
            fringe = (plain > 0) & (plain < 255)
            faint = self.render(specimen, 'faint', **options)[:, :, 3].astype(float)
            actual = self.render(specimen, **options)[:, :, 3].astype(float)
            old_rms = np.sqrt(((faint - plain)[fringe] ** 2).mean())
            new_rms = np.sqrt(((actual - plain)[fringe] ** 2).mean())
            self.assertGreater(new_rms, old_rms * 3, text)

    def test_paper_tooth_leaves_dry_zero_pooling_and_unread_words_exact(self):
        for specimen in self.words:
            for options in (dict(wet=0.), dict(weight=0.),
                            dict(progress=-1., retained=0.),
                            dict(progress=0., retained=0., leadEntry=1.)):
                np.testing.assert_array_equal(self.render(specimen, **options),
                                              self.render(specimen, 'plain', **options))

    def test_final_word_fills_every_letter_without_a_retained_base(self):
        closing = word('ٱلۡمُسۡرِفِينَ', geometry.ARABIC_FONT, 80, True, 8)
        for method in (0., 1.):
            previous = np.zeros_like(closing['coverage'], dtype=int)
            for progress in (0., .25, .5, .75, 1.):
                actual = self.render(closing, wet=0., retained=0., progress=progress,
                                     spreadMethod=method)[:, :, 3].astype(int)
                self.assertTrue((actual >= previous).all())
                previous = actual
            np.testing.assert_array_equal(previous, closing['coverage'])

    def test_repeat_evaporates_from_thin_edges_into_thicker_strokes(self):
        specimens = [word('ٱلرَّحۡمَٰنِ', geometry.ARABIC_FONT, 80, True, 6),
                     word('Merciful', geometry.ENGLISH_FONT, 48, False, 8)]
        for specimen in specimens:
            inside = specimen['coverage'] > 240
            depth = distance_transform_edt(specimen['coverage'] > 127)
            edges = inside & (depth <= 1.)
            cores = inside & (depth >= 2.)
            self.assertTrue(edges.any() and cores.any())
            for method in (0., 1.):
                options = dict(wet=0., retained=1., repeatPass=1., spreadMethod=method)
                dry = self.render(specimen, **options, repeatTint=0.).astype(float)
                held = self.render(specimen, **options, repeatTint=1.).astype(float)
                half = self.render(specimen, **options, repeatTint=.5).astype(float)
                remaining = (half[:, :, 0] - dry[:, :, 0]) / np.maximum(1., held[:, :, 0] - dry[:, :, 0])
                self.assertGreater(remaining[cores].mean(), remaining[edges].mean() + .15)

    def test_repeat_release_retains_full_underprint_and_reaches_exact_dry_ink(self):
        for specimen in self.words:
            for method in (0., 1.):
                dry = self.render(specimen, wet=0., repeatPass=0., retained=1., spreadMethod=method)
                for wet in (0., 1.):
                    options = dict(wet=wet, repeatPass=1., retained=1., spreadMethod=method)
                    previous = self.render(specimen, **options, repeatTint=1.)
                    for tint in np.linspace(.95, 0., 20):
                        actual = self.render(specimen, **options, repeatTint=float(tint))
                        self.assertTrue((actual[:, :, 3].astype(int) >= specimen['coverage'].astype(int)).all())
                        self.assertTrue((actual[:, :, :3] <= actual[:, :, 3:4]).all())
                        if wet == 0.:
                            self.assertTrue((actual[:, :, 0].astype(int) <= previous[:, :, 0].astype(int)).all())
                        previous = actual
                    np.testing.assert_allclose(previous, dry, atol=1.)

    def test_repeat_starts_on_the_existing_wet_coat_and_replaces_it_without_a_dry_flash(self):
        for specimen in self.words:
            for method in (0., 1.):
                for remaining in (1., .6, .2, 0.):
                    first = self.render(specimen, wet=remaining, spreadMethod=method)
                    repeat = self.render(specimen, progress=0., wet=1., repeatPass=1., repeatTint=1.,
                        underWet=remaining, spreadMethod=method)
                    np.testing.assert_allclose(repeat, first, atol=1.)
                orange = self.render(specimen, repeatPass=1., repeatTint=1., spreadMethod=method)
                covered = self.render(specimen, repeatPass=1., repeatTint=1., underWet=1., spreadMethod=method)
                np.testing.assert_allclose(covered, orange, atol=1.)
                for progress in (.5, .9):
                    first = self.render(specimen, progress=progress, wet=.6, retained=0., spreadMethod=method)
                    repeated = self.render(specimen, progress=0., repeatPass=1., repeatTint=1.,
                        underWet=.6, underProgress=progress, spreadMethod=method)
                    np.testing.assert_allclose(repeated, first, atol=1.)

    def test_wet_undercoat_remains_a_complete_premultiplied_layer_during_repeat_and_release(self):
        for specimen in self.words:
            for method in (0., 1.):
                for progress in (0., .25, .5, .75, 1.):
                    for tint in (1., .5, 0.):
                        actual = self.render(specimen, repeatPass=1., repeatTint=tint,
                            underWet=.6, progress=progress, spreadMethod=method)
                        self.assertTrue((actual[:, :, 3].astype(int) >= specimen['coverage'].astype(int)).all())
                        self.assertTrue((actual[:, :, :3] <= actual[:, :, 3:4]).all())
                released = self.render(specimen, repeatPass=1., repeatTint=0., underWet=.6, spreadMethod=method)
                first = self.render(specimen, wet=.6, spreadMethod=method)
                np.testing.assert_allclose(released, first, atol=1.)

    def test_alif_reflection_uses_the_pixel_area_at_fractional_reader_positions(self):
        from scipy.ndimage import label, find_objects
        errors = []
        for size in (48, 80, 112):
            pictured = word('أُمِرُوٓاْ', geometry.ARABIC_FONT, size, True, 5)
            components = find_objects(label(pictured['coverage'] > 127)[0])
            stem = max((s for s in components if s[0].stop - s[0].start > pictured['height'] * .25),
                       key=lambda s: s[1].stop)
            region = tuple(slice(max(0, s.start - 2), s.stop + 2) for s in stem)
            for phase in ((0., 0.), (.3, .4), (.65, .2)):
                for progress in (.25, .6, 1.):
                    options = dict(pixel_phase=phase, progress=progress, retained=0.)
                    reference = self.render(pictured, 'point', 8, **options).astype(float)
                    reference = reference.reshape(pictured['height'], 8, pictured['width'], 8, 4).mean((1, 3))
                    quad = self.render(pictured, 'quad', **options).astype(float)
                    actual = self.render(pictured, **options).astype(float)
                    errors.append([np.mean((pixels[region] - reference[region]) ** 2)
                                   for pixels in (quad, actual)])
        quad_error, filtered_error = np.mean(errors, axis=0)
        print(f'Alif reflection MSE: four-tap={quad_error:.4f}, stratified={filtered_error:.4f}')
        self.assertLess(filtered_error, quad_error * .7)

    def test_evaporation_dissolves_the_existing_deposit_to_exact_unread_ink(self):
        for word in self.words:
            unread = self.render(word, wet=0., progress=-1., retained=0.)
            for repeat in (0., 1.):
                for progress in (.35, 1.):
                    options = dict(progress=progress, repeatPass=repeat, repeatTint=repeat, retained=repeat)
                    deposited = self.render(word, **options).astype(float)
                    for evaporation in (.25, .5, .75, 1.):
                        actual = self.render(word, **options, evaporation=evaporation).astype(float)
                        expected = deposited * (1 - evaporation) + unread * evaporation
                        np.testing.assert_allclose(actual, expected, atol=1.)
                        self.assertTrue((actual[:, :, :3] <= actual[:, :, 3:4]).all())
                    np.testing.assert_array_equal(actual, unread)


if __name__ == '__main__':
    unittest.main()
