"""A stale repair cannot undo cleaning or bypass an exact false-lead witness."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import build_db as build


class RepeatChainRepairsTest(unittest.TestCase):
    def test_stale_unsplit_keeps_peer_repeat_and_next_word_onset(self):
        case = json.loads((Path(__file__).parent / 'timing_patch_cases' /
                           'reintroduced-orphan-alafasy-28-9.json').read_text())
        source = case['input_segments']
        repair = case['repair_segments']
        # The old unsplit also removes the peer duplicate; protection must run
        # before rejecting the disconnected label, so that duplicate survives.
        repair = [s for s in repair if s != [16, 27120, 28090]]
        repair = [[16, 27120, 28740] if s[0] == 16 else s for s in repair]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'stale.json').write_text(json.dumps({'edits': [{
                'reciterId': 1, 'surahId': 28, 'ayah': 9, 'kind': 'unsplit',
                'segments': repair,
            }]}))
            with patch.object(build, 'REPAIRS_DIR', path):
                rows = build.apply_timing_repairs(
                    [(1, 28, 9, json.dumps(source))], {(28, 9): 18})
        self.assertEqual(case['expected_segments'], json.loads(rows[0][3]))

    def test_false_lead_fails_closed_when_its_witness_moves(self):
        source = [[15, 26550, 27120], [16, 27120, 28090],
                  [16, 28090, 28740], [17, 28740, 29180]]
        pair = source[1:3]
        self.assertEqual([[15, 26550, 28090], pair[1], source[3]],
                         build.discard_false_same_position_lead(source, 16, True, pair))
        with self.assertRaisesRegex(ValueError, 'source pair changed'):
            build.discard_false_same_position_lead(source, 16, True,
                                                  [[16, 27120, 28080], pair[1]])
        with self.assertRaisesRegex(ValueError, 'requires an audio verdict'):
            build.discard_false_same_position_lead(source, 16, False, pair)


if __name__ == '__main__':
    unittest.main()
