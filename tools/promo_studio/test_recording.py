"""Recording sequence contracts without a device or network."""
import copy
import unittest
from unittest.mock import Mock, patch

import audio
import capture
import spec
import timeline
import server
from recording import Sequence


class SequenceTests(unittest.TestCase):
    def test_plain_take_identity_and_legacy_migration(self):
        plain = spec.resolve({})
        self.assertEqual(spec.take_key(plain), 'alafasy-001-001-paper-scroll-both-default')
        self.assertEqual(spec.take_key(spec.resolve({'app': {'intro': {'steps': []}}})), spec.take_key(plain))
        legacy = spec.resolve({'app': {'intro': {'cover': 2, 'home': 3, 'header': True, 'settings': 2, 'roots': 1, 'repeat': True}}})
        steps = spec.opening_steps(legacy)
        self.assertEqual([s['kind'] for s in steps], ['cover', 'home', 'open', 'header', 'settings', 'roots', 'repeat'])
        explicit = copy.deepcopy(legacy)
        explicit['app']['intro']['steps'] = steps
        self.assertEqual(spec.take_key(explicit), spec.take_key(legacy))
        explicit['app']['seconds'] = 90
        self.assertEqual(spec.take_key(explicit), spec.take_key(legacy))
        explicit['app']['intro']['steps'].reverse()
        self.assertNotEqual(spec.take_key(explicit), spec.take_key(legacy))
        self.assertEqual(spec.opening_steps(legacy)[0]['seconds'], 2)

    def test_invalid_sequences_fail_before_capture(self):
        for intro in [None, {'steps': [{}]}, {'steps': [{'kind': 'home', 'seconds': -1}]},
                      {'steps': [{'kind': 'home', 'seconds': float('nan')}]},
                      {'steps': [{'kind': 'open', 'seconds': 1, 'via': 'bad'}]},
                      {'steps': [{'kind': 'roots', 'seconds': 1, 'word': 0}]},
                      {'steps': [{'kind': 'home', 'seconds': 1}] * 25}, {'play': 'yes'}]:
            with self.subTest(intro=intro), self.assertRaises(ValueError):
                spec.resolve({'app': {'intro': intro}})
        for app in [{'layout': 'Mushaf'}, {'view': 'English'}]:
            with self.assertRaises(ValueError):
                spec.resolve({'app': {**app, 'intro': {'steps': [{'kind': 'roots', 'seconds': 1}]}}})
        with self.assertRaises(ValueError):
            spec.resolve({'app': {'surah': 1, 'repeat': 'range', 'repeat_to': 8}})

    def test_step_order_holds_and_scene_only_finish(self):
        s = spec.resolve({'app': {'intro': {'steps': [{'kind': 'home', 'seconds': 2}, {'kind': 'open', 'via': 'search', 'seconds': 3}, {'kind': 'wait', 'seconds': 4}], 'dwell': 5, 'play': False}}})
        q = Sequence(s, driver=object())
        calls, marks, sleeps = [], [], []
        q.home = lambda: calls.append('home')
        q.chapter = lambda via: calls.append(via)
        q.reader = lambda: self.fail('scene-only must keep the last screen')
        with patch('recording.time.sleep', sleeps.append):
            q.run(marks.append, lambda *args: None)
        self.assertEqual(calls, ['home', 'search'])
        self.assertEqual(sleeps, [2, 3, 4, 5])
        self.assertEqual(marks, ['intro', 'step:0:home', 'step:1:open', 'step:2:wait'])
        self.assertEqual(timeline.build(s), {'verses': [], 'repeats': [], 'words': []})
        for app in [{'intro': {'play': False}}, {'repeat': 'ayah'}]:
            with self.assertRaises(ValueError): timeline.plan_range(spec.resolve({'app': app}), 1, 2)

    def test_target_word_does_not_fall_into_another_verse(self):
        nodes = [('سُورَةُ البقرة', '', [0, 0, 40, 40]), ('الٓمٓ', '', [20, 80, 60, 120]), ('﴿\u2060١\u2060﴾', '', [0, 120, 40, 150])]
        with patch.object(capture.at, 'nodes', return_value=nodes), patch.object(capture, 'adb'), patch.object(capture.time, 'sleep'):
            self.assertEqual(capture.word_xy(1), (40, 100))
            self.assertIsNone(capture.word_xy(14))

    def test_ceremony_launches_on_camera_and_opens_automatically(self):
        driver = Mock()
        driver.wait_for.return_value = True
        q = Sequence(spec.resolve({}), driver=driver)
        q.cover()
        command = driver.adb.call_args.args
        self.assertIn('0x10008000', command)
        self.assertIn('android.intent.action.MAIN', command)
        driver.wait_for.assert_called_once_with('Open settings', tries=20)
        driver.at.tap.assert_not_called()
        self.assertEqual(q.screen, 'home')

    def test_home_recognizes_a_nonempty_search_then_clears_it(self):
        driver = Mock()
        driver.at.find.side_effect = lambda label, exact: (10, 20) if label in ('Open settings', 'Clear search') else None
        q = Sequence(spec.resolve({}), driver=driver)
        q.home()
        driver.at.tap.assert_called_once_with((10, 20))
        driver.back.assert_called_once()
        self.assertEqual(q.screen, 'home')

    def test_ordinary_take_never_preplays_and_resumes_halfway_into_opening(self):
        driver = Mock()
        driver.wait_for.return_value = True
        q = Sequence(spec.resolve({}), driver=driver)
        q.prepare()
        driver.at.open_reader.assert_called_once_with(1, 1, play=False)
        driver.start_playback.assert_not_called()
        driver.playing.assert_not_called()

    def test_repeat_playlist_uses_reviewed_tail_and_skips_preface(self):
        with patch.object(audio, 'fetch', side_effect=lambda slug, s, a: f'{s}:{a}'), patch.object(audio, 'duration', return_value=5), patch.object(audio, 'ayah_count', return_value=7):
            items, total = audio.playlist('alafasy', 2, 1, 16, 'range', 2, 3)
            self.assertEqual([(l, t) for l, _, t in items], [('2:1', 0), ('2:2', 5), ('2:1', 8), ('2:2', 13)])
            self.assertEqual(total, 16)
            items, total = audio.playlist('alafasy', 2, 1, 7, 'ayah', end_at=3)
            self.assertEqual([t for _, _, t in items], [0, 3, 6])
            items, total = audio.playlist('alafasy', 2, 1, 7)
            self.assertEqual([l for l, _, _ in items], ['basmalah', '2:1'])

    def test_repeated_timeline_uses_measured_opening(self):
        s = spec.resolve({'app': {'surah': 2, 'repeat': 'ayah', 'seconds': 7}, 'edit': [{'in': 0, 'out': 17}]})
        with patch.object(timeline, 'rows', return_value={(2, 1): [[1, 0, 3000]]}), patch.object(audio, 'fetch', return_value='wav'), patch.object(audio, 'duration', return_value=5), patch.object(audio, 'ayah_count', return_value=286):
            tl = timeline.build(s, lead=10)
            self.assertEqual([x['t0'] for x in tl['verses']], [10, 13, 16])
            self.assertEqual(tl['words'][1]['t0'], 13)

    def test_long_opening_does_not_download_extra_recitation(self):
        s = spec.resolve({'app': {'seconds': 5}})
        with patch.object(server, 'take_meta', return_value={'length': 100, 'lead': 90}), patch.object(timeline, 'build', return_value={'verses': [], 'repeats': []}) as build:
            server.editor_timeline(s)
            self.assertEqual(build.call_args.args[0]['app']['seconds'], 10)
            self.assertEqual(build.call_args.args[1], 90)


if __name__ == '__main__': unittest.main()
