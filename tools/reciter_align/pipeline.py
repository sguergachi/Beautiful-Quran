"""One surah recording -> its alignment record."""

from __future__ import annotations

from dataclasses import replace

import numpy as np

from . import crosscheck
from .acoustics import Energy
from .backends import decode_audio
from .route import RouteConfig, block_gain, inserted_blocks, resolve_route
from .text import DEFAULT_DB, starts_merged, surah_words
from .windowed import AlignConfig, align_route

WEAK_SCORE = -2.5  # a word matched this poorly is listed for review
BIG_DISAGREEMENT_S = 0.5  # models further apart than this go to a third model
SHORT_MS = 40      # a word squeezed into less than this is listed for review


def _ms(frames, stride_s):
    return int(round(frames * stride_s * 1000))


def align_surah(surah, audio_path, primary, check=None, third=None, db_path=DEFAULT_DB,
                align_cfg=AlignConfig(), route_cfg=RouteConfig(), log=lambda *_: None,
                emissions=None, check_emissions=None):
    audio = decode_audio(audio_path)
    stride = primary.stride_s
    if emissions is None:
        emissions = primary.emissions(audio)
    # What was said (openings, repeats) is decided on bare letters; only then are the words
    # placed in their spoken idgham forms (text.spoken). The forms move where a merged word
    # starts, and fed to the route search or the support test below they also tip close
    # calls between explanations of the same audio (a repeated verse read as a basmala, a
    # repeat across two verses that no verse slice can hold).
    route, placements, report = resolve_route(
        emissions, primary, surah_words(surah, db_path, merged=False), stride, align_cfg, route_cfg, log
    )

    # An insertion both models do not support (each must explain the audio better with it than
    # without) was an artefact of the search: take it out and align again.
    if check is not None and check_emissions is None:
        check_emissions = check.emissions(audio)
    for _ in range(3):
        unsupported = []
        for block in inserted_blocks(route):
            gain = block_gain(emissions, primary, route, placements, block, stride, route_cfg)
            check_gain = None if check is None else block_gain(
                check_emissions, check, route, placements, block, stride, route_cfg)
            if gain is None or gain <= 0 or (check_gain is not None and check_gain <= 0):
                unsupported.append(block)
        if not unsupported:
            break
        drop = {i for first, last in unsupported for i in range(first, last + 1)}
        route = [w for i, w in enumerate(route) if i not in drop]
        placements = align_route(emissions, primary.blank, primary.star,
                                 [primary.tokens(w.text) for w in route], stride, align_cfg)

    forms = {(w.ayah, w.pos): w.text for w in surah_words(surah, db_path)}
    route = [replace(w, text=forms[(w.ayah, w.pos)]) if w.ayah and w.kind == "verse" else w for w in route]
    placements = align_route(emissions, primary.blank, primary.star,
                             [primary.tokens(w.text) for w in route], stride, align_cfg)

    # Words with no tokens (rare) take the position of their predecessor.
    spans, previous = [], 0
    for p in placements:
        if p:
            spans.append((_ms(p.start, stride), _ms(p.end, stride), p.score))
            previous = _ms(p.end, stride)
        else:
            spans.append((previous, previous, 0.0))

    energy = Energy(audio)
    edges = {}
    for first, last in crosscheck.verse_runs(route):
        start_s, end_s = spans[first][0] / 1000, spans[last][1] / 1000
        level = energy.level(start_s, end_s)
        before = spans[first - 1][1] / 1000 if first else 0.0
        after = spans[last + 1][0] / 1000 if last + 1 < len(route) else len(audio) / 16000
        edges[first] = (energy.onset(start_s, before, level), energy.offset(end_s, after, level))

    rows, openings, runs = [], [], []
    row_of = {}  # route index -> index into `rows` (spoken openings have none)
    for first, last in crosscheck.verse_runs(route):
        word = route[first]
        start_ms, end_ms = spans[first][0], spans[last][1]
        onset, offset = edges[first]
        if word.ayah == 0:
            openings.append({"kind": word.kind, "startMs": start_ms, "endMs": end_ms,
                             "onsetMs": round(onset * 1000), "offsetMs": round(offset * 1000),
                             "before": len(rows)})
            continue
        first_row = len(rows)
        for i in range(first, last + 1):
            if route[i].kind == "fragment":
                continue  # a broken-off word: it shapes the alignment, but is not a word of the verse
            row_of[i] = len(rows)
            rows.append([route[i].ayah, route[i].pos, spans[i][0], spans[i][1], round(spans[i][2], 2)])
        runs.append({"ayah": word.ayah, "first": first_row, "last": len(rows) - 1,
                     "onsetMs": round(onset * 1000), "offsetMs": round(offset * 1000)})

    record = {
        "durationMs": round(len(audio) / 16),
        "words": rows,
        "runs": runs,
        "openings": openings,
        "review": {
            "inserted": [],
            "unexplainedSpeech": [{"word": a, "atS": s, "runs": n} for a, s, n in report.unresolved],
            "weakWords": [row_of[i] for i, (s, e, sc) in enumerate(spans)
                          if i in row_of and (sc < WEAK_SCORE or e - s < SHORT_MS)],
        },
    }
    if check is not None and check_emissions is None:
        check_emissions = check.emissions(audio)
    for first, last in inserted_blocks(route):
        word = route[first]
        gain = block_gain(emissions, primary, route, placements, (first, last), stride, route_cfg)
        check_gain = None if check is None else block_gain(
            check_emissions, check, route, placements, (first, last), stride, route_cfg)
        record["review"]["inserted"].append({
            "kind": "opening" if word.ayah == 0 else "repeat",
            "what": word.kind if word.ayah == 0 else f"{word.ayah}:{word.pos}-{route[last].ayah}:{route[last].pos}",
            "words": last - first + 1,
            "atS": round(spans[first][0] / 1000, 1),
            "gain": None if gain is None else round(gain, 1),
            "checkGain": None if check_gain is None else round(check_gain, 1),
        })
    adjusted = []
    if check is not None:
        diffs, flagged = crosscheck.compare(check, check_emissions, route, placements, stride, edges)
        record["check"] = {"model": check.name, **crosscheck.summary(diffs),
                           "discordant": [{"word": row_of[i], "diffMs": None if d is None else round(d * 1000)}
                                          for i, d in flagged if i in row_of]}
        big = {i: d for i, d in flagged if d is not None and abs(d) > BIG_DISAGREEMENT_S and i in row_of}
        if big and third is not None:
            runs_of = {}
            for first, last in crosscheck.verse_runs(route):
                for i in big:
                    if first <= i <= last:
                        runs_of[i] = first
            third_emissions = third.emissions(audio)
            third_starts, _ = crosscheck.run_starts(third, third_emissions, route, placements, stride, edges,
                                                    only=set(runs_of.values()))
            for i, d in sorted(big.items()):
                if i not in third_starts or i == runs_of[i]:
                    continue  # a run's first word is its verse's edge: left to review
                if starts_merged(route[i].text):
                    # All three models hear an idgham word start where its ghunna ends; the
                    # primary, given the doubled letter, is the one that reaches its onset.
                    continue
                primary_s = spans[i][0] / 1000
                verdict = crosscheck.arbitrate(primary_s, primary_s + d, third_starts[i])
                if verdict and verdict[0] == "check":
                    lo = spans[i - 1][0] / 1000 if i else 0.0
                    hi = spans[i + 1][0] / 1000 if i + 1 < len(spans) else len(audio) / 16000
                    if lo < verdict[1] < hi:
                        adjusted.append({"word": row_of[i], "fromMs": spans[i][0], "toMs": round(verdict[1] * 1000),
                                         "by": [check.name, third.name]})
                        for row, k in ((rows[row_of[i]], i),):
                            row[2] = round(verdict[1] * 1000)
                            row[3] = max(row[3], row[2] + 20)
            record["check"]["adjusted"] = adjusted
            record["check"]["thirdModel"] = third.name
    return record
