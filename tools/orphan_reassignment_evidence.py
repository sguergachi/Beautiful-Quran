"""Exact emitting-frame proof for a disconnected label returned to adjacent speech."""
import difflib
import math


def _frames(word):
    frames = word['labelFrames']
    if not frames or len({f for f, _, _ in frames}) != len(frames):
        raise ValueError('missing or duplicated emitting frames')
    if any(type(f) is not int or f < 0 or type(t) is not int or t < 0 or
           type(p) not in (int, float) or not math.isfinite(p) or not 0 <= p <= 1
           for f, t, p in frames):
        raise ValueError('invalid emitting frame')
    if not math.isclose(word['meanLabelProbability'], sum(p for _, _, p in frames) / len(frames),
                        rel_tol=1e-6, abs_tol=1e-9):
        raise ValueError('confidence differs from raw frames')
    return {(f, t): p for f, t, p in frames}


def orphan_reassignment_problem(before, after, model):
    """Allow only surgical deletions, with confident orphan phones retained nearby.

    The ordinary 0.5 gate still owns low-confidence removals. A confident
    removal must be an isolated interior label in a disconnected repeat run,
    between consecutive retained owners. Every confident emitting frame must
    survive on those adjacent words, on identical model/audio frames. No word
    start, unrelated end, real connected repeat or first-pass word may change.
    """
    try:
        proof = model['orphanReassignment']
        if proof['transcriptPolicy'] != 'connected_noon_v1':
            return 'unknown acoustic spelling'
        removed = []
        for tag, start, end, _, _ in difflib.SequenceMatcher(
                a=[tuple(s[:2]) for s in before], b=[tuple(s[:2]) for s in after], autojunk=False).get_opcodes():
            if tag != 'equal':
                if tag != 'delete':
                    return 'not a surgical deletion'
                removed.extend(range(start, end))
        if not removed or 0 in removed or len(before) - 1 in removed:
            return 'missing adjacent retained speech'
        retained = [i for i in range(len(before)) if i not in removed]
        indices = {old: new for new, old in enumerate(retained)}
        expected = []
        for i, segment in enumerate(before):
            if i in removed:
                expected[-1][2] = segment[2]
            else:
                expected.append(list(segment))
        if expected != after or [g['index'] for g in proof['groups']] != removed:
            return 'unrelated boundaries or stale removal proof'
        confidences = []
        for group in proof['groups']:
            i = group['index']
            word, neighbours = group['baselineWord'], group['candidateWords']
            expected_neighbours = [after[indices[j]] for j in (i - 1, i + 1)]
            if [[w['position'], w['storedStartMs'], w['storedEndMs']]
                    for w in [word, *neighbours]] != [before[i], *expected_neighbours]:
                return 'stale adjacent speech windows'
            old = _frames(word)
            new = {}
            seen = set()
            for neighbour in neighbours:
                frames = _frames(neighbour)
                emitting = {f for f, _ in frames}
                if seen & emitting:
                    return 'overlapping candidate emitting frames'
                seen.update(emitting)
                if neighbour['meanLabelProbability'] >= .5 and abs(neighbour['startResidualMs']) <= 250:
                    new.update(frames)
            confidence = word['meanLabelProbability']
            confidences.append(confidence)
            if confidence < .5:
                continue
            high_water = max(s[0] for s in before[:i])
            if not (before[i - 1][0] + 1 == before[i + 1][0] <= high_water and
                    before[i][0] <= high_water):
                return 'not an interior repeat orphan'
            left, right = i, i + 1
            while left > 0 and before[left - 1][0] <= max(
                    (s[0] for s in before[:left - 1]), default=-1):
                left -= 1
            while right < len(before) and before[right][0] <= high_water:
                right += 1
            if any(abs(before[j][0] - before[i][0]) <= 2
                   for j in range(left, right) if j != i):
                return 'connected repeat speech is protected'
            if any(old[key] != new[key] for key in old.keys() & new.keys()):
                return 'inconsistent neural emissions'
            if any(p >= .5 and key not in new for key, p in old.items()):
                return 'confident orphan speech is not retained'
        if not math.isclose(max(confidences), model['removedOccurrenceMaxLabelProbability']):
            return 'stale removal confidence'
    except (KeyError, TypeError, ValueError, IndexError):
        return 'invalid adjacent speech proof'
    return None
