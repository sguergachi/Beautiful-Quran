"""Acoustic ownership checks for joining two labels of one held word.

CTC label confidence is conditional on the supplied transcript. A confident
label cannot prove a second utterance when another alignment assigns those
same emitting frames to speech the candidate retains. No timing or text
heuristic alone authorizes removing a repeat.
"""
from __future__ import annotations

import math


def merge_pair(segments, index):
    """Join one flush same-position pair, preserving every other boundary."""
    first, second = segments[index:index + 2]
    if first[0] != second[0] or first[2] != second[1]:
        raise ValueError("a held-word merge requires a contiguous same-word pair")
    return [*segments[:index], [first[0], first[1], second[2]], *segments[index + 2:]]


def merge_index(before, after):
    """Return the uniquely changed pair; reject any additional boundary edit."""
    matches = [i for i in range(len(before) - 1)
               if before[i][0] == before[i + 1][0] and before[i][2] == before[i + 1][1]
               and merge_pair(before, i) == after]
    return matches[0] if len(matches) == 1 else None


def frame_ownership(before_words, after_words, index):
    """Record independent emitting frames instead of equating label ordinals.

    Compare identical frames and token IDs from the SAME model/audio. Only the
    merged word and its immediate neighbours may explain the source pair.
    A single confident unexplained frame is sufficient to keep the hold.
    """
    pair = before_words[index:index + 2]
    neighbours = after_words[max(0, index - 1):index + 2]
    if len(pair) != 2 or not all(w.get("labelFrames") for w in [*pair, *neighbours]):
        raise ValueError("missing emitting-frame evidence")
    explained = {frame: token for word in neighbours for frame, token, _ in word["labelFrames"]}
    novel = [[frame, token, probability] for word in pair
             for frame, token, probability in word["labelFrames"]
             if explained.get(frame) != token]
    return {"baselineWords": pair, "candidateWords": neighbours,
            "unexplainedFrames": novel,
            "unexplainedMaxLabelProbability": max((v[2] for v in novel), default=0.0)}


def ownership_problem(before, after, model):
    """Validate a frame-backed exception to the ordinal removal-confidence gate.

    The ordinary gate stays active for every other topology. This exception
    requires an exact flush merge, confident candidate word, supported onset,
    matching source/candidate boundaries, and no confident unexplained frame.
    """
    index = merge_index(before, after)
    proof = model.get("sameWordSplit")
    if index is None or not isinstance(proof, dict):
        return "missing exact held-word merge evidence"
    pair, neighbours = proof.get("baselineWords", []), proof.get("candidateWords", [])
    expected_neighbours = after[max(0, index - 1):index + 2]
    try:
        for words, segments in [(pair, before[index:index + 2]), (neighbours, expected_neighbours)]:
            if [[w["position"], w["storedStartMs"], w["storedEndMs"]] for w in words] != segments:
                return "held-word frame proof does not match the changed row"
            for word in words:
                frames = word["labelFrames"]
                if not frames or len({v[0] for v in frames}) != len(frames):
                    return "missing or duplicated held-word emitting frames"
                if any(not isinstance(f, int) or isinstance(f, bool) or f < 0 or
                       not isinstance(t, int) or isinstance(t, bool) or t < 0 or
                       not isinstance(p, (int, float)) or isinstance(p, bool) or
                       not math.isfinite(p) or not 0 <= p <= 1 for f, t, p in frames):
                    return "invalid held-word emitting frames"
                mean = word["meanLabelProbability"]
                if (not isinstance(mean, (int, float)) or isinstance(mean, bool) or not math.isfinite(mean) or
                        not math.isclose(mean, sum(v[2] for v in frames) / len(frames), rel_tol=1e-6, abs_tol=1e-9)):
                    return "held-word confidence does not match its raw frames"
            all_frames = [f for word in words for f, _, _ in word["labelFrames"]]
            if len(set(all_frames)) != len(all_frames):
                return "overlapping held-word emitting frames"
        old_emissions = {(f,t): p for word in pair for f,t,p in word["labelFrames"]}
        new_emissions = {(f,t): p for word in neighbours for f,t,p in word["labelFrames"]}
        if any(old_emissions[key] != new_emissions[key] for key in old_emissions.keys() & new_emissions.keys()):
            return "held-word hypotheses used inconsistent neural emissions"
        candidate = neighbours[index - max(0, index - 1)]
        if any(not isinstance(candidate[k], (int, float)) or isinstance(candidate[k], bool) or
               not math.isfinite(candidate[k]) for k in ["meanLabelProbability", "startResidualMs"]):
            return "invalid held-word candidate confidence or onset"
        if not 0 <= candidate["meanLabelProbability"] <= 1:
            return "invalid held-word candidate confidence or onset"
        if candidate["meanLabelProbability"] < 0.5 or abs(candidate["startResidualMs"]) > 250:
            return "held-word candidate confidence or onset is insufficient"
        # Recompute from the raw proof. A declared zero cannot bypass the gate.
        old = [{}] * index + pair
        new = [{}] * max(0, index - 1) + neighbours
        computed = frame_ownership(old, new, index)
        if computed["unexplainedFrames"] != proof["unexplainedFrames"]:
            return "stale held-word ownership summary"
        if computed["unexplainedMaxLabelProbability"] >= 0.5:
            return "a confident emitting frame is not retained by the merge"
    except (KeyError, TypeError, ValueError, IndexError):
        return "invalid held-word ownership proof"
    return None
