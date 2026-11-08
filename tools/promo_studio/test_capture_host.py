"""Host capture contracts: media clock continuity, identity and physical geometry."""
import tempfile
import unittest

from capture_host import audio_position
from capture import centered_cutout, record, take_audio_problems
from unittest.mock import patch
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
        # a packet stamped 35-45 ms late or early is jitter, not a gap: no silence inserted, no sound trimmed
        self.assertEqual(audio_position(origin + 100_000 + 40_000, origin, 4800, 480), (4800, 0, 0))
        self.assertEqual(audio_position(origin + 100_000 - 45_000, origin, 4800, 480), (4800, 0, 0))
        self.assertEqual(audio_position(origin + 400_000, origin, 4800, 480), (4800, 0, 0), "up to half a second is still jitter")
        # a real discontinuity (a reconnect, a stalled emulator) is preserved so later sound is not shifted
        self.assertEqual(audio_position(origin + 900_000, origin, 4800, 480), (43200, 0, 38400))
        self.assertEqual(audio_position(origin, origin, 40000, 480), (40000, 480, 0))

    def test_a_take_with_dropouts_is_recorded_again_and_a_clean_one_is_kept(self):
        import capture
        s = spec.resolve({})
        calls = []
        def fake_once(sp, log=print, force=False):
            calls.append(force); return '/take'
        with patch.object(capture, '_record_once', fake_once), patch.object(capture, 'wait_for_quiet', lambda log=print: .1), \
                patch.object(capture, 'take_audio_problems', side_effect=[[[1.0, 35.0, True]], [[2.0, 35.0, True]], []]), \
                patch.object(capture, 'take_dir', return_value='/nonexistent'):
            self.assertEqual(record(s, log=lambda m: None, force=True), '/take')
        self.assertEqual(calls, [True, True, True], 'recorded until one came out clean, each time forced')
        calls.clear()
        with patch.object(capture, '_record_once', fake_once), patch.object(capture, 'wait_for_quiet', lambda log=print: .1), \
                patch.object(capture, 'take_audio_problems', return_value=[[1.0, 35.0, True]]), \
                patch.object(capture, 'take_dir', return_value='/nonexistent'):
            record(s, log=lambda m: None, force=True)
        self.assertEqual(len(calls), capture.TAKE_ATTEMPTS, 'gives up after TAKE_ATTEMPTS and keeps the healed take')

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
