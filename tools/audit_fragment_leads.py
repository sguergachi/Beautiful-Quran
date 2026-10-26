#!/usr/bin/env python3
"""Inventory merged fragment leads and score their complete pipeline outputs.

All inputs/outputs are private scratch. Inventory covers every authenticated
source row beside the complete delivered corpus; holds/corrections never
exclude a pair. Score uses the final pre-verdict candidate, not a hand-edited
verse. A boundary-only change has the same transcript, so forced starts are
independent of its proposed timestamps.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import sqlite3

import build_db as build
from qf_timing_parity import snapshot_rows, source_hash
from timing_delta import make_payload, payload_hash


def payload(db, key):
    row = db.execute("SELECT segments,audio_onset_ms FROM timings WHERE "
                     "reciter_id=? AND surah_id=? AND ayah_number=?", key).fetchone()
    return None if row is None else {"segments": json.loads(row[0]), "audioOnsetMs": row[1]}


def inventory(args):
    db = sqlite3.connect(args.db)
    words = {}
    for s, a, p, text in db.execute("SELECT surah_id,ayah_number,position,arabic FROM words"):
        words.setdefault((s, a), {})[p] = text
    counts = {key: len(value) for key, value in words.items()}
    slugs = dict(db.execute("SELECT id,slug FROM reciters"))
    durations = build.load_audio_durations()
    pairs, hashes, row_counts = [], {}, {}
    for rid, qid in build.QDC_REPEAT_RECITERS.items():
        snapshot = json.loads((args.snapshots / f"snapshot-{qid}.json").read_text())
        rows = snapshot_rows(snapshot, qid)
        digest = source_hash(rows)
        if digest != build.QF_SOURCE_SHA256[qid]:
            raise ValueError("unreviewed authenticated source")
        hashes[str(qid)] = digest
        row_counts[str(rid)] = len(rows)
        references = build.alignment_reference(args.source_cache / "quran-align-data.zip",
                                               rid, slugs[rid], counts, words)
        for verse, source in rows.items():
            s, a = map(int, verse.split(":"))
            key = (rid, s, a)
            cleaned = build.adjust_qdc_segments(source, counts[s, a],
                                                collections.defaultdict(int), words=words[s, a])
            reference = references.get(key)
            clocked, offset = build.rebase_qdc_clock(cleaned, reference, durations.get(key))
            leads = build.fragment_same_position_leads(source, clocked, reference, offset)
            for i in range(1, len(source) - 1):
                previous, lead, real = source[i - 1:i + 2]
                if (lead[0] != real[0] or not build.is_split_fragment(
                        lead[2] - lead[1], real[2] - real[1])):
                    continue
                match = (lead[0], lead[1] + (offset or 0), real[1] + (offset or 0))
                reason = "retain: not an exact single merged occurrence"
                starts = {p: start for p, start, _ in reference or []}
                ends = {p: end for p, _, end in reference or []}
                if offset is None:
                    reason = "retain: no corroborated file clock"
                elif previous[0] != lead[0] - 1:
                    reason = "retain: preceding label is not the previous canonical word"
                elif not (0 <= real[1] - lead[2] <= build.QDC_SPLIT_MERGE_GAP_MS and
                          0 <= lead[1] - previous[2] <= build.QDC_SPLIT_MERGE_GAP_MS):
                    reason = "retain: source gaps are not a tiled fragment merge"
                elif lead[0] not in starts or lead[0] - 1 not in ends:
                    reason = "retain: missing independent word/previous-word witness"
                elif starts[lead[0]] - (lead[1] + offset) < build.PEER_ONSET_CORROBORATION_MS:
                    reason = "retain: reference corroborates the earlier onset"
                elif abs(starts[lead[0]] - (real[1] + offset)) > build.MAX_CLOCK_DISAGREEMENT_MS:
                    reason = "retain: reference does not corroborate the second onset"
                elif ends[lead[0] - 1] < lead[1] + offset:
                    reason = "retain: reference does not assign the lead to the previous word"
                pairs.append({"key": list(key), "position": lead[0],
                              "sourceWindow": [previous, lead, real],
                              "clockOffsetMs": offset, "reference": reference,
                              "detected": match in leads,
                              "reason": "witnessed merged lead" if match in leads else reason,
                              "baseline": payload(db, key),
                              "cleaned": clocked,
                              "proposed": build.hand_lead_to_previous_word(clocked, leads)})
    result = {"schema": 1, "dbSha256": hashlib.sha256(args.db.read_bytes()).hexdigest(),
              "sourceHashes": hashes, "sourceRows": row_counts,
              "deliveredRows": db.execute("SELECT COUNT(*) FROM timings").fetchone()[0],
              "deliveredRowsByReciter": dict(db.execute(
                  "SELECT reciter_id,COUNT(*) FROM timings GROUP BY reciter_id")),
              "pairs": pairs}
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("fragment pairs", len(pairs), "detected", sum(v["detected"] for v in pairs))


def score(args):
    import audit_forced_alignment as af
    import torch
    torch.set_num_threads(4)
    source = json.loads(args.inventory.read_text())
    if source["dbSha256"] != hashlib.sha256(args.db.read_bytes()).hexdigest():
        raise ValueError("stale delivered corpus")
    keys = {tuple(p["key"]) for p in source["pairs"] if p["detected"]}
    # Genuine re-says and the older substantial-lead class are controls too.
    keys.update({(7, 4, 4), (1, 3, 21), (1, 19, 48), (7, 66, 6)})
    name = af.MODEL_NAME if args.model == "xlsr" else af.MMS_MODEL_NAME
    processor, net, device = af.load_model(name, "auto")
    romanizer = None
    if args.model == "mms":
        import uroman
        romanizer = uroman.Uroman()
    baseline, candidate = sqlite3.connect(args.db), sqlite3.connect(args.candidate_db)
    slugs = dict(baseline.execute("SELECT id,slug FROM reciters"))
    done = {}
    if args.out.exists():
        for line in args.out.read_text().splitlines():
            value = json.loads(line)
            done[tuple(value["key"])] = value
    with args.out.open("a") as out:
        for key in sorted(keys):
            before, after = payload(baseline, key), payload(candidate, key)
            if key in done:
                if (done[key]["baseline"] != before or done[key]["candidate"] != after or
                        done[key]["modelRevision"] != net.config._commit_hash):
                    raise ValueError("stale acoustic evidence")
                if "error" not in done[key]:
                    audio = af.audio_path(Path.home() / "qasr", slugs[key[0]], key[1], key[2])
                    if af.sha256(audio) != done[key]["evidence"]["audioSha256"]:
                        raise ValueError("stale acoustic recording")
                    continue
            row = {"key": list(key), "baseline": before, "candidate": after,
                   "model": name, "modelRevision": net.config._commit_hash}
            try:
                evidence = af.force_one(db=baseline, processor=processor, model=net,
                    device=device, qasr_root=Path.home() / "qasr", reciter_id=key[0],
                    slug=slugs[key[0]], surah=key[1], ayah=key[2],
                    min_label_probability=.15, max_residual_ms=250,
                    romanize=args.model == "mms", model_name=name, romanizer=romanizer)
                row["evidence"] = evidence
                if (before is None or after is None or
                        [s[0] for s in before["segments"]] != [s[0] for s in after["segments"]]):
                    row["review"] = "topology differs; boundary metric cannot approve"
                else:
                    forced = [w["forcedStartMs"] for w in evidence["words"]]
                    row["baselineScore"] = sum(abs(s[1] - f) for s, f in zip(
                        before["segments"], forced)) / len(forced)
                    row["candidateScore"] = sum(abs(s[1] - f) for s, f in zip(
                        after["segments"], forced)) / len(forced)
            except Exception as error:
                row["error"] = str(error)
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            out.flush()
            print(key, row.get("baselineScore"), row.get("candidateScore"), row.get("error", ""), flush=True)
            done[key] = row
    if any("error" in value for value in done.values()) or set(done) != keys:
        raise SystemExit("incomplete acoustic coverage; do not publish")


def review(args):
    """Require complete dual-model coverage; emit bounded public dispositions."""
    source = json.loads(args.inventory.read_text())
    reports = {}
    for tag in ("xlsr", "mms"):
        reports[tag] = {tuple(row["key"]): row for row in map(
            json.loads, getattr(args, tag).read_text().splitlines())}
    keys = {tuple(p["key"]) for p in source["pairs"] if p["detected"]}
    controls = {(7, 4, 4), (1, 3, 21), (1, 19, 48), (7, 66, 6)}
    if any(set(rows) != keys | controls or any("error" in r for r in rows.values())
           for rows in reports.values()):
        raise ValueError("incomplete dual-model coverage")
    for key in controls:
        if any(rows[key]["baseline"] != rows[key]["candidate"] for rows in reports.values()):
            raise ValueError("genuine-repeat control changed")
    slugs = {rid: slug for rid, slug, _, _ in build.RECITERS}
    verdicts, dispositions = {}, []
    for pair in source["pairs"]:
        key, position = tuple(pair["key"]), pair["position"]
        item = {"key": list(key), "position": position, "detected": pair["detected"],
                "disposition": "retain", "reason": pair["reason"]}
        if not pair["detected"]:
            dispositions.append(item)
            continue
        rows = [reports[tag][key] for tag in ("xlsr", "mms")]
        before, after = rows[0]["baseline"], rows[0]["candidate"]
        if before != pair["baseline"]:
            raise ValueError("acoustic baseline does not match inventoried delivery")
        if any(r["baseline"] != before or r["candidate"] != after for r in rows):
            raise ValueError("model hypotheses disagree")
        expected = [seg[:] for seg in before["segments"]]
        index = next(i for i, seg in enumerate(expected) if seg[0] == position)
        target = pair["sourceWindow"][2][1] + pair["clockOffsetMs"]
        expected[index - 1][2] = target
        expected[index][1] = target
        if after != {"segments": expected, "audioOnsetMs": before["audioOnsetMs"]}:
            raise ValueError("pipeline changed more than the witnessed handoff")
        models, risks, onset_errors = [], [], []
        for tag, row in zip(("arabic-xlsr-ctc", "mms-uroman-ctc"), rows):
            proof = row["evidence"]
            words = proof["words"]
            forced = next(w for w in words if w["position"] == position)
            error = target - forced["forcedStartMs"]
            onset_errors.append(abs(error))
            for old, new, word in zip(before["segments"], after["segments"], words):
                if new[2] < old[2] and word["forcedEndMs"] - new[2] > 250:
                    risks.append([tag, word["position"]])
            models.append({"name": tag, "model": row["model"],
                "modelRevision": row["modelRevision"], "metric": "meanAbsStartResidualMs",
                "baselineResidualMs": row["baselineScore"], "candidateResidualMs": row["candidateScore"],
                "baselineScore": -row["baselineScore"], "candidateScore": -row["candidateScore"],
                "changedWord": {"position": position, "forcedStartMs": forced["forcedStartMs"],
                    "candidateStartMs": target, "candidateResidualMs": abs(error),
                    "meanLabelProbability": forced["meanLabelProbability"]}})
        durations = {r["evidence"]["audioDurationMs"] for r in rows}
        audio = {r["evidence"]["audioSha256"] for r in rows}
        if len(durations) != 1 or len(audio) != 1:
            raise ValueError("different acoustic recording")
        duration = durations.pop()
        bounds = all(0 <= s < e <= duration + 50 for _, s, e in expected)
        accepted = (not risks and bounds and max(onset_errors) <= 250 and
                    all(r["candidateScore"] <= r["baselineScore"] for r in rows))
        reason = ("both models improve; restored source onset within 250ms of both witnesses"
                  if accepted else "retain baseline: source onset remains over 250ms from a model or waveform/metric veto")
        entry = {"verdict": "accept" if accepted else "hold", "kinds": ["timestamp"],
                 "baselinePayloadHash": payload_hash(make_payload(before["segments"], before["audioOnsetMs"])),
                 "candidatePayloadHash": payload_hash(make_payload(after["segments"], after["audioOnsetMs"])),
                 "reason": reason, "evidence": {"kind": "dual_model_boundary", "models": models,
                    "audioSha256": audio.pop(), "artifact": "tools/timing_evidence/fragment-lead-review.json",
                    "summary": "Exact two-boundary handoff: preceding held word regains its tail; every other timing and occurrence is unchanged.",
                    "waveformVeto": bool(risks or not bounds), "waveform": {
                        "method": "decoded audio bounds, identical occurrence sequence, unchanged or extended voiced ends",
                        "decodedDurationMs": duration, "boundsWithinTolerance": bounds,
                        "durationToleranceMs": 50, "voicedEndToleranceMs": 250,
                        "voicedTailCutRisks": risks}}}
        verdicts[f"{slugs[key[0]]}:{key[1]}:{key[2]}"] = entry
        item.update(disposition=entry["verdict"], reason=reason)
        dispositions.append(item)
    result = {"schemaVersion": 1, "deliveredRows": source["deliveredRows"],
              "deliveredRowsByReciter": source["deliveredRowsByReciter"],
              "sourceHashes": source["sourceHashes"], "sourceRows": source["sourceRows"],
              "criteria": {"candidateOnsetMaxAbsResidualMs": 250,
                           "boundaryMetric": "meanAbsStartResidualMs", "bothModelsMustNotRegress": True},
              "summary": dict(collections.Counter(d["disposition"] for d in dispositions)),
              "negativeControls": [{"key": list(k), "unchanged": True,
                  "models": [{"model": reports[tag][k]["model"],
                              "modelRevision": reports[tag][k]["modelRevision"],
                              "baselineResidualMs": reports[tag][k]["baselineScore"],
                              "candidateResidualMs": reports[tag][k]["candidateScore"],
                              "audioSha256": reports[tag][k]["evidence"]["audioSha256"]}
                             for tag in ("xlsr", "mms")]} for k in sorted(controls)],
              "modelCoverage": {tag: len(rows) for tag, rows in reports.items()},
              "dispositions": dispositions, "verdicts": verdicts}
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(result["summary"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inv = sub.add_parser("inventory")
    inv.add_argument("--snapshots", type=Path, required=True)
    inv.add_argument("--source-cache", type=Path, required=True)
    scoring = sub.add_parser("score")
    scoring.add_argument("--inventory", type=Path, required=True)
    scoring.add_argument("--candidate-db", type=Path, required=True)
    scoring.add_argument("--model", choices=("xlsr", "mms"), required=True)
    reviewing = sub.add_parser("review")
    reviewing.add_argument("--inventory", type=Path, required=True)
    reviewing.add_argument("--xlsr", type=Path, required=True)
    reviewing.add_argument("--mms", type=Path, required=True)
    reviewing.add_argument("--out", type=Path, required=True)
    for cmd in (inv, scoring):
        cmd.add_argument("--db", type=Path, required=True)
        cmd.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    for path in ([args.out] if args.command == "review" else [args.db, args.out]):
        if path.resolve().is_relative_to(build.ROOT):
            parser.error("raw timing inputs and acoustic outputs must stay outside the checkout")
    {"inventory": inventory, "score": score, "review": review}[args.command](args)


if __name__ == "__main__":
    main()
