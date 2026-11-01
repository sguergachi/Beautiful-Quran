"""Fail closed for unretained phones, changed boundaries and real repeat speech."""
import copy
import unittest

from orphan_reassignment_evidence import orphan_reassignment_problem


class OrphanOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.before = [[p, i * 100, (i + 1) * 100]
                       for i, p in enumerate([10, 7, 1, 8, 9, 10, 11, 11, 12])]
        self.after = [s[:] for i, s in enumerate(self.before) if i not in (2, 6)]
        self.after[1][2] = 300
        self.after[4][2] = 700

        def word(segment, frames, residual=0):
            return dict(zip(('position', 'storedStartMs', 'storedEndMs'), segment),
                        labelFrames=frames, startResidualMs=residual,
                        meanLabelProbability=sum(f[2] for f in frames) / len(frames))

        self.model = {'removedOccurrenceMaxLabelProbability': .5, 'orphanReassignment': {
            'transcriptPolicy': 'connected_noon_v1', 'groups': [
                {'index': 2, 'baselineWord': word(self.before[2], [[20, 1, .99], [21, 2, .01]]),
                 'candidateWords': [word(self.after[1], [[19, 3, .99], [20, 1, .99]]),
                                    word(self.after[2], [[30, 8, .9]])]},
                {'index': 6, 'baselineWord': word(self.before[6], [[60, 11, .2]]),
                 'candidateWords': [word(self.after[4], [[50, 10, .9]]),
                                    word(self.after[5], [[70, 11, .9]])]},
            ]}}

    def problem(self):
        return orphan_reassignment_problem(self.before, self.after, self.model)

    def test_confident_phone_remains_on_the_adjacent_word(self):
        self.assertIsNone(self.problem())

    def test_missing_evidence_is_held(self):
        self.model.pop('orphanReassignment')
        self.assertIsNotNone(self.problem())

    def test_confident_unexplained_phone_is_held(self):
        group = self.model['orphanReassignment']['groups'][0]
        group['baselineWord']['labelFrames'][1][2] = .6
        group['baselineWord']['meanLabelProbability'] = .795
        self.model['removedOccurrenceMaxLabelProbability'] = .795
        self.assertIsNotNone(self.problem())

    def test_different_emissions_are_held(self):
        word = self.model['orphanReassignment']['groups'][0]['candidateWords'][0]
        word['labelFrames'][1][2] = .98
        word['meanLabelProbability'] = .985
        self.assertIsNotNone(self.problem())

    def test_connected_repeat_is_held(self):
        self.before[2][0] = 6
        self.model['orphanReassignment']['groups'][0]['baselineWord']['position'] = 6
        self.assertIsNotNone(self.problem())

    def test_first_pass_word_is_held(self):
        self.before[0][0] = 6
        self.assertIsNotNone(self.problem())

    def test_unrelated_start_or_end_cannot_move(self):
        for coordinate in (1, 2):
            after = copy.deepcopy(self.after)
            after[0][coordinate] += 1
            self.assertIsNotNone(orphan_reassignment_problem(self.before, after, self.model))

    def test_invalid_probabilities_and_stale_confidence_are_held(self):
        original = copy.deepcopy(self.model)
        for probability in (-.1, float('nan'), float('inf')):
            self.model = copy.deepcopy(original)
            self.model['orphanReassignment']['groups'][0]['baselineWord']['labelFrames'][0][2] = probability
            self.assertIsNotNone(self.problem())
        self.model = original
        self.model['removedOccurrenceMaxLabelProbability'] = .1
        self.assertIsNotNone(self.problem())

    def test_late_or_weak_retained_owner_cannot_explain_the_phone(self):
        self.model['orphanReassignment']['groups'][0]['candidateWords'][0]['startResidualMs'] = 251
        self.assertIsNotNone(self.problem())

    def test_publication_gate_checks_the_exact_proof_and_keeps_other_gates(self):
        import build_db
        models = [dict(copy.deepcopy(self.model), name=name,
                       metric='viterbiLogProbabilityPerFrame', baselineScore=-.1, candidateScore=-.01)
                  for name in ('arabic-xlsr-ctc', 'mms-uroman-ctc')]
        evidence = {'models': models, 'waveform': {'decodedDurationMs': 900,
                    'boundsWithinTolerance': True, 'durationToleranceMs': 50,
                    'voicedEndToleranceMs': 250, 'voicedTailCutRisks': []}}
        change = {'old': {'segments': self.before}, 'new': {'segments': self.after},
                  'kinds': ['topology'], 'verdict': {'evidence': evidence}}
        self.assertIsNone(build_db.authenticated_evidence_problem(change))
        models[0]['candidateScore'] = -.2
        self.assertIsNotNone(build_db.authenticated_evidence_problem(change))


if __name__ == '__main__':
    unittest.main()
