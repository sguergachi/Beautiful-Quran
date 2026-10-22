"""Host capture contracts: media clock continuity, identity and physical geometry."""
import tempfile
import unittest

from capture_host import audio_position
from capture import centered_cutout
import phone
import spec


class CaptureHostTests(unittest.TestCase):
    def test_pixel_cutout_stays_centered_when_the_framebuffer_changes(self):
        self.assertEqual(centered_cutout('m 581.5,86.5 a 42,42 0 0 0 -84,0 42,42 0 0 0 84,0 z @left'),
                         'm 42,86.5 a 42,42 0 0 0 -84,0 42,42 0 0 0 84,0 z')
        self.assertEqual(centered_cutout('m 484.5,0 h 110 v 141.5 h -110 z @left', True),
                         'm -55,0 h 110 v 141.5 h -110 z')
        with self.assertRaises(ValueError): centered_cutout('unrecognized @left')

    def test_audio_clock_absorbs_jitter_but_preserves_pauses_and_reconnects(self):
        origin = 1_000_000
        self.assertEqual(audio_position(origin + 100_100, origin, 4800, 480), (4800, 0, 0))
        self.assertEqual(audio_position(origin + 200_000, origin, 4800, 480), (9600, 0, 4800))
        self.assertEqual(audio_position(origin, origin, 4800, 480), (4800, 480, 0))

    def test_resolution_never_reuses_or_invalidates_a_legacy_take(self):
        plain = spec.resolve({})
        self.assertEqual(spec.take_key(plain), 'alafasy-001-001-paper-scroll-both-default')
        sharp = spec.resolve({'app': {'capture': '2k'}})
        self.assertEqual(spec.take_key(sharp), spec.take_key(plain) + '-2k')
        with self.assertRaises(ValueError): spec.resolve({'app': {'capture': 'upscale'}})

    def test_native_screen_radius_and_camera_are_not_overlaid_twice(self):
        display = {'camera_baked': True, 'corner_radius': 33}
        g, frame, _ = phone.render(360, 800, 'obsidian', display)
        self.assertEqual(g['screen_r'], 33)
        self.assertAlmostEqual(g['body_r'] - g['screen_r'], (g['bw'] - g['sw']) / 2)
        self.assertEqual(g['sx0'], round(g['sx0']))
        self.assertEqual(g['sy0'], round(g['sy0']))
        x, y = round(g['cx']), round(g['sy0'] + phone.HOLE_TOP_MM * g['k'])
        self.assertEqual(frame[y, x, 3], 0)
        legacy = phone.render(360, 800, 'obsidian')[1]
        self.assertEqual(legacy[y, x, 3], 255)
        with tempfile.TemporaryDirectory() as root:
            a = phone.ensure(root, 360, 800, 'obsidian', display)
            b = phone.ensure(root, 360, 800, 'obsidian')
            self.assertNotEqual(a[:2], b[:2])
