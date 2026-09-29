#!/usr/bin/env python3
"""Find single-word re-says that qdc flattened, and prove them with two models.

qdc tiles an ayah gaplessly, so a word said twice with only one label hands
its second utterance to the following word, which then starts a whole
utterance early (Hani 21:46 يَٰوَيۡلَنَآ, #779). No row shape reveals this:
qdc shows an ordinary monotonic pair, quran-align cannot express a repeat,
and CTC often fuses the rewind into one token. What does reveal it is the
audio, so this tool asks the audio directly:

1. ``candidates`` — run the pipeline and list every first-pass pair ``p, p+1``
   where quran-align opens ``p+1`` at least ``--lead-ms`` after qdc does while
   still sounding ``p`` through the gap. That gap is where a second utterance
   of ``p`` could hide. Alone it proves nothing: a held madd looks the same
   (1,338 rows disagree by a second or more).
2. ``score`` — force both occurrence sequences, ``…p, p+1…`` and
   ``…p, p, p+1…``, through Arabic XLSR and MMS/uroman on the exact EveryAyah
   file. The Viterbi score depends only on the sequence, never on where the
   stored boundaries sit.
3. ``write`` — a re-say is accepted only when BOTH models prefer the repeated
   sequence by more than ``ACCEPT_MARGIN``. Its boundaries come from the two
   models' forced alignment, and it lands as a generated
   ``restore_flattened_resay`` typed correction pinned to its source pair.
4. ``verdicts`` — after rebuilding, bind each changed row to its scores in
   ``tools/timing_verdicts/flattened-resay-class.json``.

Calibration (2026-09-29, quran-v64): of 169 rows #748 proved to be a held madd
labelled as the next word — the look-alike this test must reject — none was
preferred as a repeat by both models (closest: -0.0070 / -0.0062). Of 16 known
flattened re-says (CTC restores plus the #779 ear patch), 14 were, every one
by more than 0.002 on both. A scan of 3,731 candidate rows found four the
models prefer; one, Abdul Basit 37:152, is an MP3 that opens with the previous
ayah, which the extra occurrence soaked up — hence ``MIN_WORD_PROBABILITY``.

Steps 2 and 3 need the qasr venv (torch, uroman) and the cached EveryAyah MP3s:

    python3 tools/find_flattened_resays.py candidates --out /tmp/resay-candidates.json
    ~/qasr/venv/bin/python tools/find_flattened_resays.py score \\
        /tmp/resay-candidates.json --out /tmp/resay-scores.jsonl --resume
    python3 tools/find_flattened_resays.py write /tmp/resay-scores.jsonl
    python3 tools/build_db.py --refresh-qdc-timings
    git show "$(git merge-base HEAD origin/master)":data/quran.db > /tmp/base.db
    python3 tools/find_flattened_resays.py verdicts /tmp/resay-scores.jsonl \\
        --baseline-db /tmp/base.db

The verdict baseline must be the merge-base database: that is what the delta
gate in tools/test_build_db.py compares against.
"""

from __future__ import annotations

import argparse
import collections
import json
import re
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

GENERATOR = "tools/find_flattened_resays.py"
LEDGER = TOOLS / "timing_verdicts" / "flattened-resay-class.json"
CORRECTIONS_DIR = TOOLS / "timing_corrections"
QDC_RECITERS = {1, 2, 3, 4, 5, 7}
# Candidate gap: a re-say needs room for a whole second utterance.
DEFAULT_LEAD_MS = 600
# Both models must prefer the repeated sequence by more than this (per frame).
ACCEPT_MARGIN = 0.001
# ...and hear each occurrence as the word. A preferred sequence alone is not
# enough: an MP3 that carries speech outside the transcript lets the extra
# occurrence soak it up (Abdul Basit 37:152 opens with the previous ayah's
# لَيَقُولُونَ; XLSR scores its two وَلَدَ at 0.37 and 0.21). Every known re-say
# holds each occurrence at >= 0.62 on XLSR and >= 0.53 on MMS.
MIN_WORD_PROBABILITY = 0.5
MODELS = (
    ("xlsr", "arabic-xlsr-ctc"),
    ("mms", "mms-uroman-ctc"),
)


def _load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _key(k):
    return tuple(int(x) for x in k)


def settled_keys():
    """Rows another verdict already owns; a generated edit must not compete."""
    owned = set()
    for directory in ("timing_repairs", "timing_corrections"):
        for path in (TOOLS / directory).glob("*.json"):
            if path.name.endswith(".flagged.json"):
                continue
            for edit in _load(path).get("edits") or []:
                if edit.get("generator") == GENERATOR:
                    continue
                owned.add((edit["reciterId"], edit["surahId"], edit["ayah"]))
    holds = TOOLS / "timing_holds" / "audit-held-rows.json"
    if holds.is_file():
        for row in _load(holds).get("rows") or []:
            owned.add((row["reciterId"], row["surahId"], row["ayah"]))
    return owned


def pipeline_rows():
    """Run the refresh pipeline and capture the rows typed corrections see."""
    import build_db

    captured = {}
    apply_corrections = build_db.apply_timing_corrections
    apply_repairs = build_db.apply_timing_repairs

    def corrections(rows, *args, **kwargs):
        captured["rows"] = {
            (rid, sid, ay): json.loads(segs) if isinstance(segs, str) else segs
            for rid, sid, ay, segs in rows
        }
        # Judge every row as if nothing had been generated yet, so a rerun
        # re-derives the same entries instead of seeing its own output.
        return apply_corrections(rows, *args, corrections_dir=hand_only, **kwargs)

    def repairs(rows, *args, **kwargs):
        if "only_keys" not in kwargs:
            captured["references"] = kwargs.get("references") or {}
        return apply_repairs(rows, *args, **kwargs)

    with tempfile.TemporaryDirectory() as tmp:
        hand_only = Path(tmp) / "corrections"
        hand_only.mkdir()
        for path in CORRECTIONS_DIR.glob("*.json"):
            payload = _load(path)
            payload["edits"] = [
                e for e in payload.get("edits") or [] if e.get("generator") != GENERATOR
            ]
            (hand_only / path.name).write_text(json.dumps(payload), encoding="utf-8")
        build_db.apply_timing_corrections = corrections
        build_db.apply_timing_repairs = repairs
        build_db.OUT = Path(tmp) / "quran.db"
        argv = sys.argv
        sys.argv = ["build_db.py", "--refresh-qdc-timings"]
        try:
            build_db.main()
        finally:
            sys.argv = argv
        shipped = {
            (rid, sid, ay): json.loads(segs)
            for rid, sid, ay, segs in sqlite3.connect(build_db.OUT).execute(
                "SELECT reciter_id,surah_id,ayah_number,segments FROM timings"
            )
        }
    return captured["rows"], captured["references"], shipped


def find_candidates(rows, references, shipped, owned, lead_ms):
    """One candidate pair per row: the widest gap quran-align opens."""
    found = []
    for key, segs in sorted(rows.items()):
        if key[0] not in QDC_RECITERS or key in owned or key not in references:
            continue
        onset, sounding = {}, {}
        for pos, start, end in references[key]:
            onset.setdefault(pos, start)
            sounding[pos] = end
        counts = collections.Counter(pos for pos, _, _ in segs)
        best = None
        for (p, s0, e0), (q, s1, e1) in zip(segs, segs[1:]):
            if q != p + 1 or counts[p] != 1 or e0 != s1:
                continue
            witness = onset.get(q)
            if witness is None or sounding.get(p) is None:
                continue
            lead = witness - s1
            if lead < lead_ms or sounding[p] < witness - 60 or not witness < e1:
                continue
            # The corrected pair must be the one that ships, or the verdict
            # would describe a row the finalizer rewrote.
            final = shipped.get(key) or []
            if [p, s0, e0] not in final or [q, s1, e1] not in final:
                continue
            if best is None or lead > best["leadMs"]:
                best = {
                    "key": list(key),
                    "position": p,
                    "sourcePair": [[p, s0, e0], [q, s1, e1]],
                    "leadMs": lead,
                }
        if best:
            found.append(best)
    return found


def hypothesis(segs, source_pair):
    """The same row with ``p`` said twice; boundaries are irrelevant to Viterbi."""
    (p, s0, e0), (q, s1, e1) = source_pair
    i = segs.index([p, s0, e0])
    mid = (s1 + e1) // 2
    return [*segs[:i], [p, s0, e0], [p, s1, mid], [q, mid, e1], *segs[i + 2 :]]


def cmd_candidates(args):
    rows, references, shipped = pipeline_rows()
    found = find_candidates(rows, references, shipped, settled_keys(), args.lead_ms)
    for candidate in found:
        candidate["base"] = shipped[tuple(candidate["key"])]
    Path(args.out).write_text(json.dumps(found, ensure_ascii=False) + "\n", encoding="utf-8")
    by_reciter = collections.Counter(c["key"][0] for c in found)
    print(f"{len(found)} candidate row(s): {dict(sorted(by_reciter.items()))}")


def cmd_score(args):
    import audit_forced_alignment as af

    candidates = _load(args.candidates)
    out = Path(args.out)
    done = set()
    if args.resume and out.is_file():
        done = {tuple(json.loads(line)["key"]) for line in out.read_text().splitlines() if line}
    todo = [c for c in candidates if tuple(c["key"]) not in done]
    with tempfile.TemporaryDirectory() as tmp:
        dbs = {}
        for tag in ("base", "hyp"):
            path = Path(tmp) / f"{tag}.db"
            shutil.copy(ROOT / "data" / "quran.db", path)
            db = sqlite3.connect(path)
            for c in todo:
                segs = c["base"] if tag == "base" else hypothesis(c["base"], c["sourcePair"])
                db.execute(
                    "UPDATE timings SET segments=? WHERE reciter_id=? AND surah_id=? AND ayah_number=?",
                    (json.dumps(segs, separators=(",", ":")), *c["key"]),
                )
            db.commit()
            dbs[tag] = db
        slugs = dict(dbs["base"].execute("SELECT id, slug FROM reciters"))
        results = {tuple(c["key"]): {"key": c["key"]} for c in todo}
        for tag, model_name, romanize in (
            ("xlsr", af.MODEL_NAME, False),
            ("mms", af.MMS_MODEL_NAME, True),
        ):
            processor, model, device = af.load_model(model_name, "auto")
            romanizer = None
            if romanize:
                import uroman as ur

                romanizer = ur.Uroman()
            for n, c in enumerate(todo, start=1):
                key = tuple(c["key"])
                for which, db in dbs.items():
                    try:
                        evidence = af.force_one(
                            db=db, processor=processor, model=model, device=device,
                            qasr_root=af.DEFAULT_QASR, reciter_id=key[0], slug=slugs[key[0]],
                            surah=key[1], ayah=key[2], min_label_probability=0.15,
                            max_residual_ms=250, romanize=romanize,
                            model_name=model_name, romanizer=romanizer,
                        )
                    except Exception as error:  # evidence must record, not hide, a gap
                        results[key][f"{tag}Error"] = f"{type(error).__name__}: {error}"
                        continue
                    results[key][f"{tag}_{which}"] = evidence["viterbiLogProbabilityPerFrame"]
                    results[key]["audioSha256"] = evidence["audioSha256"]
                    if which == "hyp":
                        results[key][f"{tag}Words"] = [
                            [
                                w["position"], w["forcedStartMs"], w["forcedEndMs"],
                                round(w["meanLabelProbability"], 4),
                            ]
                            for w in evidence["words"]
                        ]
                if n % 250 == 0:
                    print(f"  {tag} {n}/{len(todo)}", flush=True)
        with out.open("a", encoding="utf-8") as report:
            for c in todo:
                row = results[tuple(c["key"])]
                row.update({"position": c["position"], "sourcePair": c["sourcePair"], "leadMs": c["leadMs"]})
                report.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"scored {len(todo)} candidate row(s)")


def _resay_words(row, tag):
    """The forced ``p, p, p+1`` triple, or None when the model did not place it."""
    p = row["position"]
    words = row[f"{tag}Words"]
    idx = [
        i for i in range(len(words) - 2)
        if words[i][0] == p == words[i + 1][0] and words[i + 2][0] == p + 1
    ]
    return words[idx[0] : idx[0] + 3] if len(idx) == 1 else None


def forced_resay(row):
    """Re-say and next-word onsets: the pause midpoints both models agree on."""
    splits, onsets = [], []
    for tag, _ in MODELS:
        triple = _resay_words(row, tag)
        if triple is None:
            return None
        first, second, following = triple
        splits.append((first[2] + second[1]) / 2)
        onsets.append((second[2] + following[1]) / 2)
    return round(sum(splits) / len(splits)), round(sum(onsets) / len(onsets))


def accepted(row):
    if any(f"{tag}Error" in row or f"{tag}_hyp" not in row for tag, _ in MODELS):
        return False
    if not all(row[f"{tag}_hyp"] - row[f"{tag}_base"] > ACCEPT_MARGIN for tag, _ in MODELS):
        return False
    for tag, _ in MODELS:
        triple = _resay_words(row, tag)
        if triple is None or min(triple[0][3], triple[1][3]) < MIN_WORD_PROBABILITY:
            return False
    return True


def cmd_write(args):
    rows = [json.loads(line) for line in Path(args.scores).read_text().splitlines() if line]
    slugs = {}
    for path in CORRECTIONS_DIR.glob("*.json"):
        payload = _load(path)
        for edit in payload.get("edits") or []:
            slugs[edit["reciterId"]] = payload["reciter"]
    import build_db

    slugs.update({rid: slug for rid, slug, _name, _style in build_db.RECITERS})
    edits = collections.defaultdict(list)
    rejected_bounds = 0
    for row in rows:
        if not accepted(row):
            continue
        onsets = forced_resay(row)
        (p, s0, _e0), (_q, _s1, e1) = row["sourcePair"]
        if onsets is None or not s0 < onsets[0] < onsets[1] < e1:
            rejected_bounds += 1
            continue
        rid, sid, ay = row["key"]
        edits[rid].append({
            "reciterId": rid,
            "surahId": sid,
            "ayah": ay,
            "op": "restore_flattened_resay",
            "position": p,
            "resayStartMs": onsets[0],
            "nextOnsetMs": onsets[1],
            "sourcePair": row["sourcePair"],
            "requiresAudioVerdict": True,
            "generator": GENERATOR,
            "evidence": ["dual-model forced alignment", "quran-align"],
            "refs": ["tools/timing_verdicts/flattened-resay-class.json"],
        })
    for rid, slug in sorted(slugs.items()):
        path = CORRECTIONS_DIR / f"{slug}.json"
        payload = _load(path) if path.is_file() else {"schema": 1, "reciter": slug, "edits": []}
        kept = [e for e in payload["edits"] if e.get("generator") != GENERATOR]
        if len(kept) == len(payload["edits"]) and not edits.get(rid):
            continue  # nothing generated here before or now: leave the file alone
        payload["edits"] = kept + sorted(edits.get(rid, []), key=lambda e: (e["surahId"], e["ayah"]))
        path.write_text(_dump_corrections(payload), encoding="utf-8")
    total = sum(len(v) for v in edits.values())
    print(f"{total} generated re-say correction(s); {rejected_bounds} accepted row(s) had no usable boundary")


def _dump_corrections(payload):
    """Pretty JSON that keeps pairs and short lists on one line, like hand entries."""
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    number = r"\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\s*"
    text = re.sub(
        r'"sourcePair": \[\s*\[' + number + r'\],\s*\[' + number + r'\]\s*\]',
        r'"sourcePair": [[\1, \2, \3], [\4, \5, \6]]',
        text,
    )
    text = re.sub(
        r'"evidence": \[\s*((?:"[^"]*",?\s*)+)\]',
        lambda m: '"evidence": [' + ", ".join(re.findall(r'"[^"]*"', m.group(1))) + "]",
        text,
    )
    return text + "\n"


def cmd_verdicts(args):
    from timing_delta import build_delta, read_timing_rows

    scores = {
        tuple(row["key"]): row
        for row in (json.loads(line) for line in Path(args.scores).read_text().splitlines() if line)
    }
    generated = set()
    for path in CORRECTIONS_DIR.glob("*.json"):
        for edit in _load(path).get("edits") or []:
            if edit.get("generator") == GENERATOR:
                generated.add((edit["reciterId"], edit["surahId"], edit["ayah"]))
    slug_to_id = {slug: rid for rid, slug in sqlite3.connect(ROOT / "data" / "quran.db").execute("SELECT id, slug FROM reciters")}
    report = build_delta(read_timing_rows(Path(args.baseline_db)), read_timing_rows(ROOT / "data" / "quran.db"), {})
    ledger = _load(LEDGER)
    verdicts = {k: v for k, v in ledger["verdicts"].items() if v["evidence"].get("generator") != GENERATOR}
    for change in report["changes"]:
        rid = slug_to_id[change["reciter"]]
        key = (rid, *(int(x) for x in change["key"].split(":")[1:]))
        if key not in generated:
            continue
        row = scores[key]
        verdicts[change["key"]] = {
            "verdict": "accept",
            "kinds": change["kinds"],
            "baselinePayloadHash": change["old"]["payloadHash"],
            "candidatePayloadHash": change["new"]["payloadHash"],
            "evidence": {
                "kind": "dual_model_topology",
                "generator": GENERATOR,
                "artifact": "tools/timing_verdicts/flattened-resay-class.json",
                "summary": (
                    "Flattened re-say: qdc labelled the second utterance of a word as the next "
                    "word, which quran-align opens a full utterance later. Arabic XLSR and "
                    "MMS/uroman forced alignment both score the repeated occurrence sequence "
                    f"better than the flat one by more than {ACCEPT_MARGIN} per frame; the "
                    "boundaries are the pause midpoints both models agree on."
                ),
                "audioSha256": row["audioSha256"],
                "waveformVeto": False,
                "models": [
                    {
                        "name": name,
                        "metric": "viterbiLogProbabilityPerFrame",
                        "baselineScore": round(row[f"{tag}_base"], 6),
                        "candidateScore": round(row[f"{tag}_hyp"], 6),
                    }
                    for tag, name in MODELS
                ],
            },
        }
    ledger["verdicts"] = dict(sorted(verdicts.items()))
    LEDGER.write_text(json.dumps(ledger, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(verdicts)} verdict(s) in {LEDGER.name}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("candidates")
    p.add_argument("--out", required=True)
    p.add_argument("--lead-ms", type=int, default=DEFAULT_LEAD_MS)
    p = sub.add_parser("score")
    p.add_argument("candidates")
    p.add_argument("--out", required=True)
    p.add_argument("--resume", action="store_true")
    p = sub.add_parser("write")
    p.add_argument("scores")
    p = sub.add_parser("verdicts")
    p.add_argument("scores")
    p.add_argument("--baseline-db", required=True)
    args = parser.parse_args(argv)
    {"candidates": cmd_candidates, "score": cmd_score, "write": cmd_write, "verdicts": cmd_verdicts}[args.command](args)


if __name__ == "__main__":
    main()
