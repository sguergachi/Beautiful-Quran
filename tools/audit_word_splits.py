#!/usr/bin/env python3
"""Inventory and acoustically audit EVERY adjacent same-word pair in a corpus.

Use a private SQLite combining current runtime resources with bundled timings.
No correction/hold ownership excludes a candidate. Paused pairs are scored as
negative controls but cannot be automatically merged. Both hypotheses share
one actual neural inference per recording/model; raw JSONL survives interruption.

    python3 tools/audit_word_splits.py inventory --db /private/live.db --out inventory.json
    ~/qasr/venv/bin/python tools/audit_word_splits.py score --db /private/live.db \\
        --inventory inventory.json --model xlsr --out xlsr.jsonl --resume
    # Repeat with --model mms, then:
    python3 tools/audit_word_splits.py review --db /private/live.db \\
        --inventory inventory.json --xlsr xlsr.jsonl --mms mms.jsonl --out review.json
"""
from __future__ import annotations

import argparse
import collections
import copy
from contextlib import closing
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import sys
import urllib.request

from timing_delta import make_payload, payload_hash
from word_split_evidence import frame_ownership, merge_pair, ownership_problem

MODELS = {"xlsr": "jonatasgrosman/wav2vec2-large-xlsr-53-arabic",
          "mms": "MahmoudAshraf/mms-300m-1130-forced-aligner"}


def read_reports(path, recover=False):
    """Tolerate only an interrupted final write, never a corrupt complete row."""
    lines = path.read_text().splitlines(keepends=True)
    rows = {}
    for index, line in enumerate(lines):
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            if index != len(lines) - 1 or line.endswith("\n"):
                raise ValueError(f"corrupt JSONL row in {path}")
            print(f"Incomplete final JSONL row in {path}; coverage remains incomplete", file=sys.stderr)
            if recover:
                path.write_text("".join(lines[:index]))
            break
        rows[row["id"]] = row
    return rows


def capture(args):
    """Snapshot all public reviewed voices beside the bundled independent rows."""
    from build_db import QDC_REPEAT_RECITERS

    if args.out.exists() or args.out.resolve() == args.db.resolve():
        raise ValueError("capture requires a new private output, never the release asset")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    inputs = args.out.parent / "runtime-inputs"
    inputs.mkdir(exist_ok=True)
    manifest = {"bundledDbSha256": hashlib.sha256(args.db.read_bytes()).hexdigest(),
                "sourceProfile": "delivered runtime views, not private authenticated snapshots", "resources": {}}
    with closing(sqlite3.connect(f"file:{args.db.resolve()}?mode=ro", uri=True)) as source, \
            closing(sqlite3.connect(":memory:")) as db:
        source.backup(db)
        canonical = {(s, a): count for s, a, count in db.execute(
            "SELECT surah_id,ayah_number,COUNT(*) FROM words GROUP BY surah_id,ayah_number")}
        slugs = dict(db.execute("SELECT id,slug FROM reciters"))
        for rid, qf_id in QDC_REPEAT_RECITERS.items():
            url = f"https://beautiful-quran.sguergachi.workers.dev/api/timings/{rid}"
            request = urllib.request.Request(url, headers={"Origin": "https://sguergachi.github.io",
                "User-Agent": "Beautiful-Quran-acoustic-audit/1"})
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
            resource = json.loads(raw)
            body = {key: resource[key] for key in ["rows", "withheldVerseKeys"]}
            digest = hashlib.sha256(json.dumps(body, separators=(",", ":")).encode()).hexdigest()
            if (resource["schemaVersion"] != 1 or resource["revision"] != digest or resource["reciterId"] != rid or
                    resource["qfReciterId"] != qf_id or resource["audioSlug"] != slugs[rid] or
                    resource["clock"] != "everyayah-ms"):
                raise ValueError("runtime resource identity/revision mismatch")
            seen = set()
            for s, a, segments, onset in resource["rows"]:
                count = canonical[s, a]
                if ((s, a) in seen or not segments or type(onset) is not int or
                        not 0 <= onset <= segments[0][1] or
                        {v[0] for v in segments} != set(range(1, count + 1)) or
                        any(len(v) != 3 or any(type(n) is not int for n in v) or
                            not 1 <= v[0] <= count or not 0 <= v[1] < v[2] for v in segments) or
                        any(left[2] > right[1] for left, right in zip(segments, segments[1:]))):
                    raise ValueError("invalid runtime timing row")
                seen.add((s, a))
            withheld = {tuple(map(int, key.split(":"))) for key in resource["withheldVerseKeys"]}
            if seen & withheld or seen | withheld != set(canonical):
                raise ValueError("incomplete runtime timing coverage")
            db.execute("DELETE FROM timings WHERE reciter_id=?", (rid,))
            db.executemany("INSERT INTO timings(reciter_id,surah_id,ayah_number,segments,audio_onset_ms) "
                           "VALUES(?,?,?,?,?)", [(rid, s, a, json.dumps(segs), onset)
                                                  for s, a, segs, onset in resource["rows"]])
            (inputs / f"timings-{rid}.json").write_bytes(raw)
            manifest["resources"][str(rid)] = {k:v for k,v in resource.items()
                                               if k not in ["rows", "withheldVerseKeys"]}
            manifest["resources"][str(rid)]["responseSha256"] = hashlib.sha256(raw).hexdigest()
        db.commit()
        with closing(sqlite3.connect(args.out)) as output:
            db.backup(output)
    args.out.with_suffix(".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def read_rows(db):
    rows = {(rid, s, a): (json.loads(raw), onset) for rid, s, a, raw, onset in db.execute(
        "SELECT reciter_id,surah_id,ayah_number,segments,audio_onset_ms FROM timings")}
    declared = {rid for rid, in db.execute("SELECT id FROM reciters WHERE has_timings=1")}
    missing = declared - {key[0] for key in rows}
    if missing:
        raise ValueError(f"missing delivered reciters {sorted(missing)}; capture runtime views first")
    return rows


def inventory(rows):
    """Enumerate every pair, including chains, pauses, existing repairs and holds."""
    found = []
    digest = hashlib.sha256()
    for key, (segments, onset) in sorted(rows.items()):
        digest.update(json.dumps([*key, segments, onset], separators=(",", ":")).encode() + b"\n")
        for index, (first, second) in enumerate(zip(segments, segments[1:])):
            if first[0] == second[0]:
                found.append({"id": ":".join(map(str, (*key, index))), "key": list(key),
                              "index": index, "sourcePair": [first, second],
                              "baselinePayloadHash": payload_hash(make_payload(segments, onset))})
    return {"schemaVersion": 1, "predicate": "every adjacent equal-position pair; no exclusions",
            "corpusSha256": digest.hexdigest(),
            "rowCounts": {str(k): v for k, v in collections.Counter(key[0] for key in rows).items()},
            "rowCount": len(rows), "candidates": found}


def rewind_inventory(rows):
    """Keep the distinct pre-rewind class visible without reusing split verdicts.

    A spoken high-water word before a genuine rewind is ordinary recitation.
    These are review candidates, never structural evidence for deletion.
    """
    result = inventory(rows)
    result["predicate"] = "new high-water position immediately before a rewind, recited again later"
    result["complete"] = False
    result["scope"] = "structural inventory only; this split audit does not score pre-rewind removals"
    result["candidates"] = []
    for key, (segments, onset) in sorted(rows.items()):
        for index in range(1, len(segments) - 1):
            previous, word, returning = segments[index - 1:index + 2]
            if (word[0] <= max(s[0] for s in segments[:index]) or returning[0] >= word[0] or
                    not any(s[0] == word[0] for s in segments[index + 2:])):
                continue
            applicable = word[0] == previous[0] + 1
            result["candidates"].append({"id": ":".join(map(str, (*key, index))), "key": list(key),
                "index": index, "sourceWindow": [previous, word, returning],
                "baselinePayloadHash": payload_hash(make_payload(segments, onset)),
                "operationApplicable": applicable, "decision": "uncertain" if applicable else "retain",
                "reason": "requires separate dual-model removal inference" if applicable else
                          "predecessor is not the immediately previous canonical word"})
    return result


def issue_inventory(issues, rows):
    """Cross-reference every issue, including patches hidden under other titles."""
    candidates = inventory(rows)["candidates"]
    by_key = collections.defaultdict(list)
    for item in candidates:
        by_key[tuple(item["key"])].append(item)
    result = []
    for issue in issues:
        edits, errors = [], []
        for block in re.findall(r"```(?:json)?\s*\n(.*?)```", issue.get("body", ""), re.S):
            try:
                payload = json.loads(block)
            except json.JSONDecodeError:
                if '"segments"' in block:
                    errors.append("invalid JSON timing block")
                continue
            for edit in payload.get("edits", []) if isinstance(payload, dict) else []:
                if not all(k in edit for k in ["reciterId", "surahId", "ayah", "segments"]):
                    errors.append("incomplete timing edit")
                    continue
                key = tuple(edit[k] for k in ["reciterId", "surahId", "ayah"])
                live = rows.get(key)
                edits.append({"key": list(key), "candidateIds": [c["id"] for c in by_key[key]],
                    "liveEqualsPatch": live[0] == edit["segments"] if live else None,
                    "occurrenceCounts": [{"id": c["id"], "position": c["sourcePair"][0][0],
                        "live": sum(s[0] == c["sourcePair"][0][0] for s in live[0]),
                        "patch": sum(s[0] == c["sourcePair"][0][0] for s in edit["segments"])}
                        for c in by_key[key]]})
        result.append({"issue": issue["number"], "title": issue["title"], "state": issue["state"],
            "url": f"https://github.com/sguergachi/Beautiful-Quran/issues/{issue['number']}",
            "timingEdits": edits, "parseErrors": errors,
            "classification": "timing patch" if edits else
                              "timing report without parsed edit" if errors or "timing" in issue["title"].lower()
                              else "other report; no timing edit"})
    return {"issueCount": len(issues), "issues": result}


def hypothesis(segments, index):
    """Also score paused controls, while preserving their pause in the evidence."""
    first, second = segments[index:index + 2]
    return [*segments[:index], [first[0], first[1], second[2]], *segments[index + 2:]]


def model_witness(row):
    """Derive confidence and ownership from actual reports, never label ordinals alone."""
    before, after = row["baseline"], row["candidate"]
    index = row["index"]
    return {"name": "arabic-xlsr-ctc" if row["modelTag"] == "xlsr" else "mms-uroman-ctc",
            "model": before["model"], "modelRevision": row["modelRevision"],
            "metric": "viterbiLogProbabilityPerFrame",
            "baselineScore": before["viterbiLogProbabilityPerFrame"],
            "candidateScore": after["viterbiLogProbabilityPerFrame"],
            "removedOccurrenceMaxLabelProbability": before["words"][index + 1]["meanLabelProbability"],
            "sameWordSplit": frame_ownership(before["words"], after["words"], index)}


def validate_report(row, item, before, after, tag):
    """Reject a stale, swapped-model or unrelated transcript before adjudication."""
    if any(row.get(k) != item[k] for k in ["id", "key", "index", "sourcePair", "baselinePayloadHash"]):
        raise ValueError("inference identity does not match the candidate")
    revision = row.get("modelRevision", "")
    if row.get("modelTag") != tag or len(revision) != 40 or any(c not in "0123456789abcdef" for c in revision):
        raise ValueError("missing or swapped model provenance")
    for report, segments in [(row["baseline"], before), (row["candidate"], after)]:
        if (report["model"] != MODELS[tag] or
                [report[k] for k in ["reciterId", "surahId", "ayah"]] != item["key"] or
                report["occurrenceCount"] != len(segments) or
                [[w["position"], w["storedStartMs"], w["storedEndMs"]] for w in report["words"]] != segments or
                not math.isfinite(report["viterbiLogProbabilityPerFrame"])):
            raise ValueError("inference report does not describe the actual hypothesis")
        for word in report["words"]:
            if (any(not isinstance(word[k], (int, float)) or isinstance(word[k], bool) or
                    not math.isfinite(word[k]) for k in ["meanLabelProbability", "forcedStartMs", "startResidualMs"]) or
                    not 0 <= word["meanLabelProbability"] <= 1 or
                    word["startResidualMs"] != word["forcedStartMs"] - word["storedStartMs"]):
                raise ValueError("invalid acoustic confidence or start residual")
    for field in ["audioSha256", "audioDurationMs", "ctcFrames", "ctcFrameMs"]:
        if row["baseline"][field] != row["candidate"][field]:
            raise ValueError("hypotheses used different acoustic frames")


def score(args):
    import audit_forced_alignment as af
    import torch

    torch.set_num_threads(4)
    name = af.MODEL_NAME if args.model == "xlsr" else af.MMS_MODEL_NAME
    processor, net, device = af.load_model(name, args.device)
    romanizer = None
    if args.model == "mms":
        import uroman
        romanizer = uroman.Uroman()
    revision = net.config._commit_hash
    source = json.loads(args.inventory.read_text())
    done = {}
    if args.out.exists():
        if not args.resume:
            raise ValueError("output exists; use --resume")
        done = read_reports(args.out, recover=True)
    with closing(sqlite3.connect(f"file:{args.db.resolve()}?mode=ro", uri=True)) as original:
        db = sqlite3.connect(":memory:")
        original.backup(db)
    rows = read_rows(db)
    if inventory(rows) != source:
        raise ValueError("inventory does not match the complete input corpus")
    slugs = dict(db.execute("SELECT id,slug FROM reciters"))
    cache, previous_key, baseline = {}, None, None
    failures = 0
    with args.out.open("a") as out:
        for count, item in enumerate(source["candidates"], 1):
            prior = done.get(item["id"])
            if prior and "error" not in prior:
                if prior["baselinePayloadHash"] != item["baselinePayloadHash"] or prior["modelRevision"] != revision:
                    raise ValueError("stale resumed evidence")
                rid, surah, ayah = item["key"]
                audio = af.audio_path(args.qasr_root, slugs[rid], surah, ayah)
                if af.sha256(audio) != prior["baseline"]["audioSha256"]:
                    raise ValueError("resumed recording hash changed")
                continue
            key, index = tuple(item["key"]), item["index"]
            segments, onset = rows[key]
            result = {**item, "modelTag": args.model, "modelRevision": revision}
            try:
                def force(current):
                    db.execute("UPDATE timings SET segments=? WHERE reciter_id=? AND surah_id=? AND ayah_number=?",
                               (json.dumps(current), *key))
                    kwargs = dict(db=db, processor=processor, model=net, device=device,
                                  qasr_root=args.qasr_root, reciter_id=key[0], slug=slugs[key[0]],
                                  surah=key[1], ayah=key[2], min_label_probability=.15,
                                  max_residual_ms=250, romanize=args.model == "mms", model_name=name,
                                  romanizer=romanizer, emission_cache=cache, include_alignment=True)
                    try:
                        return af.force_one(**kwargs)
                    except torch.OutOfMemoryError:
                        # Keep a complete audit on the shared GPU. Only this
                        # recording falls back; the original model stays put.
                        torch.cuda.empty_cache()
                        cpu = net.to("cpu")
                        kwargs.update(model=cpu, device=torch.device("cpu"))
                        try:
                            return af.force_one(**kwargs)
                        finally:
                            net.to(device)
                if key != previous_key:
                    baseline = force(segments)
                    previous_key = key
                merged = hypothesis(segments, index)
                candidate = force(merged)
                result["candidatePayloadHash"] = payload_hash(make_payload(merged, onset))
                # Full words/residuals survive, with exact frame proofs around
                # the change. Other frame arrays add size but no local evidence.
                for label, report in [("baseline", baseline), ("candidate", candidate)]:
                    keep = range(max(0, index - 1), index + (3 if label == "baseline" else 2))
                    result[label] = {**report, "words": [
                        word if i in keep else {k: v for k, v in word.items() if k != "labelFrames"}
                        for i, word in enumerate(report["words"])]}
                result["witness"] = model_witness(result)
            except Exception as error:
                result["error"] = f"{type(error).__name__}: {error}"
                previous_key = None
                failures += 1
            out.write(json.dumps(result, ensure_ascii=False) + "\n")
            out.flush()
            if count % 10 == 0 or "error" in result:
                print(f"{args.model} {count}/{len(source['candidates'])} {item['id']} "
                      f"{result.get('error', 'ok')}", flush=True)
    db.close()
    if failures:
        raise SystemExit(f"{failures} inference error(s); audit incomplete, use --resume after resolving them")


def review(args):
    """Keep every missing, failed or disagreeing candidate visible in the result."""
    source = json.loads(args.inventory.read_text())
    trace = json.loads(args.trace.read_text())
    if trace["corpusSha256"] != source["corpusSha256"]:
        raise ValueError("source replay belongs to a different corpus")
    reports = [read_reports(path) for path in [args.xlsr, args.mms]]
    with closing(sqlite3.connect(f"file:{args.db.resolve()}?mode=ro", uri=True)) as db:
        rows = read_rows(db)
    if inventory(rows) != source:
        raise ValueError("stale corpus inventory")
    decisions = []
    for item in source["candidates"]:
        before, onset = rows[tuple(item["key"])]
        after = hypothesis(before, item["index"])
        witnesses = [report.get(item["id"]) for report in reports]
        decision = {**item, "decision": "uncertain"}
        if any(row is None or "error" in row for row in witnesses):
            decision["reason"] = "missing inference or inference error"
        elif any(row["baselinePayloadHash"] != item["baselinePayloadHash"] or
                 row["candidatePayloadHash"] != payload_hash(make_payload(after, onset)) for row in witnesses):
            raise ValueError("stale candidate evidence")
        elif len({row["baseline"]["audioSha256"] for row in witnesses}) != 1:
            raise ValueError("the models used different recordings")
        else:
            for row, tag in zip(witnesses, ["xlsr", "mms"]):
                validate_report(row, item, before, after, tag)
            models = [model_witness(row) for row in witnesses]
            gains = [m["candidateScore"] - m["baselineScore"] for m in models]
            decision.update(models=models, audioSha256=witnesses[0]["baseline"]["audioSha256"])
            decision["audioDurationMs"] = witnesses[0]["candidate"]["audioDurationMs"]
            if item["sourcePair"][0][2] != item["sourcePair"][1][1]:
                decision.update(decision="retain", reason="source pair has a pause; never auto-merge")
            elif any(gain <= .001 for gain in gains):
                decision.update(decision="retain" if all(gain <= 0 for gain in gains) else "uncertain",
                                reason="both models must prefer the merge by more than 0.001/frame")
            elif any(row["candidate"]["words"][item["index"]]["meanLabelProbability"] < .5
                     for row in witnesses):
                decision["reason"] = "the retained word lacks confident support on both models"
            elif any(m["removedOccurrenceMaxLabelProbability"] >= .5 and
                     ownership_problem(before, after, m) is not None for m in models):
                decision["reason"] = "confident independent speech or insufficient ownership evidence"
            elif any(abs(row["candidate"]["words"][item["index"]]["startResidualMs"]) > 250
                     for row in witnesses):
                decision["reason"] = "merge needs a separate onset repair"
            elif any(not 0 <= start < end <= row["candidate"]["audioDurationMs"] + 50
                     for row in witnesses for _, start, end in after):
                decision["reason"] = "decoded waveform bounds need a separate repair"
            else:
                decision.update(decision="repair", reason="dual-model sequence preference and retained acoustic ownership")
        decisions.append(decision)
        stage = trace["rows"].get(":".join(map(str, item["key"])))
        replayed = stage.get("beforeGeneratedMerges", []) if stage else []
        present = any(replayed[i:i + 2] == item["sourcePair"] for i in range(len(replayed) - 1))
        output = stage.get("candidateOutputs", {}).get(item["id"]) if stage else None
        exact = output == {"segments": after, "audioOnsetMs": onset}
        decision["sourceReplay"] = {"pairPresent": present,
                                    "outputMatches": exact,
                                    "profile": stage.get("sourceProfile") if stage else None}
        if decision["decision"] == "repair" and (not exact or stage.get("reviewedHolds")):
            decision.update(decision="uncertain", acousticDecision="repair",
                            reason="source replay or reviewed hold needs reconciliation")
    repair_counts = collections.Counter(tuple(d["key"]) for d in decisions if d["decision"] == "repair")
    for decision in decisions:
        if decision["decision"] == "repair" and repair_counts[tuple(decision["key"])] > 1:
            decision.update(decision="uncertain", reason="multiple merges in this row need joint inference")
    summary = dict(collections.Counter(d["decision"] for d in decisions))
    complete = (all(item["id"] in report and "error" not in report[item["id"]]
                    for item in source["candidates"] for report in reports)
                and all(":".join(map(str, c["key"])) in trace["rows"] for c in source["candidates"]))
    result = {**{k:v for k,v in source.items() if k != "candidates"}, "candidateCount": len(decisions),
              "complete": complete, "summary": summary,
              "sourceTraceSha256": hashlib.sha256(args.trace.read_bytes()).hexdigest(),
              "reportSha256": {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                                                       for path in [args.xlsr, args.mms]},
              "decisions": decisions}
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    if args.compact_out:
        indexed = compact_review(result)
        decisions = indexed.pop("decisions")
        header = json.dumps(indexed, ensure_ascii=False, indent=2).rstrip()[:-1].rstrip()
        entries = ",\n".join("    " + json.dumps(d, ensure_ascii=False, separators=(",", ":")) for d in decisions)
        args.compact_out.write_text(header + ',\n  "decisions": [\n' + entries + '\n  ]\n}\n')
    print(json.dumps(summary), flush=True)
    return 0 if complete else 2


def compact_review(reviewed):
    """Keep every disposition and the raw proofs needed by accepted exceptions.

    Full per-model JSONL remains the authority for every frame assignment;
    this index retains scores, forced boundaries and corpus/source coverage.
    """
    result = copy.deepcopy(reviewed)
    result["reportSha256"] = {Path(k).name: v for k,v in result["reportSha256"].items()}
    for decision in result["decisions"]:
        for model in decision.get("models", []):
            proof = model["sameWordSplit"]
            index = decision["index"]
            candidate = proof["candidateWords"][index - max(0, index - 1)]
            model["retainedWord"] = {k:v for k,v in candidate.items() if k != "labelFrames"}
            model["baselineOccurrences"] = [{k:v for k,v in word.items() if k != "labelFrames"}
                                             for word in proof["baselineWords"]]
            model["ownershipSummary"] = {"unexplainedFrameCount": len(proof["unexplainedFrames"]),
                "unexplainedMaxLabelProbability": proof["unexplainedMaxLabelProbability"]}
            if decision["decision"] != "repair" or model["removedOccurrenceMaxLabelProbability"] < .5:
                del model["sameWordSplit"]
    return result


def generated_corrections(reviewed, rows):
    """Generate the entire calibrated class; an incomplete scan cannot write edits."""
    if reviewed.get("complete") is not True or reviewed["candidateCount"] != len(reviewed["decisions"]):
        raise ValueError("a complete corpus review is required before generating corrections")
    current = inventory(rows)
    if reviewed.get("corpusSha256") != current["corpusSha256"] or reviewed["rowCount"] != len(rows):
        raise ValueError("review corpus coverage changed")
    if ({d["id"] for d in reviewed["decisions"]} != {c["id"] for c in current["candidates"]}
            or len(reviewed["decisions"]) != len(current["candidates"])):
        raise ValueError("a complete corpus review is required before generating corrections")
    expected = {c["id"]: c for c in current["candidates"]}
    edits = []
    for decision in reviewed["decisions"]:
        if any(decision.get(k) != v for k, v in expected[decision["id"]].items()):
            raise ValueError("review candidate identity changed")
        segments, onset = rows[tuple(decision["key"])]
        if payload_hash(make_payload(segments, onset)) != decision["baselinePayloadHash"]:
            raise ValueError("review source row changed")
        if decision["decision"] != "repair":
            continue
        if (decision.get("sourceReplay", {}).get("pairPresent") is not True or
                decision["sourceReplay"].get("outputMatches") is not True):
            raise ValueError("accepted correction lacks an exact source replay")
        from build_db import authenticated_evidence_problem
        after = merge_pair(segments, decision["index"])
        entry = verdict_entry(decision, segments, onset)
        change = {"old": make_payload(segments, onset), "new": make_payload(after, onset),
                  "kinds": ["topology"], "verdict": entry}
        if problem := authenticated_evidence_problem(change):
            raise ValueError(f"accepted correction fails the existing acoustic gate: {problem}")
        rid, surah, ayah = decision["key"]
        edits.append({"reciterId": rid, "surahId": surah, "ayah": ayah,
                      "op": "merge_verified_word_split", "phase": "after_repairs",
                      "sourcePair": decision["sourcePair"], "requiresAudioVerdict": True,
                      "generator": "tools/audit_word_splits.py",
                      "evidence": ["Arabic XLSR", "MMS/uroman", "acoustic frame ownership"],
                      "refs": ["tools/timing_evidence/word-split-corpus-review.json"]})
    return {"schema": 1, "generator": "tools/audit_word_splits.py", "edits": edits}


def verdict_entry(decision, before, onset):
    """Bind a generated merge to its exact delivered payload and acoustic proof."""
    after = hypothesis(before, decision["index"])
    return {"verdict": "accept", "kinds": ["topology"],
        "baselinePayloadHash": payload_hash(make_payload(before, onset)),
        "candidatePayloadHash": payload_hash(make_payload(after, onset)),
        "reason": decision["reason"],
        "evidence": {"kind": "dual_model_topology", "audioSha256": decision["audioSha256"],
            "artifact": "tools/timing_evidence/word-split-corpus-review.json",
            "summary": "Both sequences were forced against identical neural emissions per model; "
                       "the full merged span and every other timing boundary are retained.",
            "waveformVeto": False,
            "waveform": {"method": "ffmpeg_decoded_bounds_and_exact_union_of_unchanged_spans",
                "decodedDurationMs": decision["audioDurationMs"], "durationToleranceMs": 50,
                "boundsWithinTolerance": all(0 <= s < e <= decision["audioDurationMs"] + 50 for _,s,e in after),
                "voicedEndToleranceMs": 250, "voicedTailCutRisks": [],
                "scope": "An exact flush merge removes no audio interval and shortens no segment end."},
            "models": decision["models"]}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ["capture", "inventory", "issues", "trace", "score", "review", "write"]:
        cmd = sub.add_parser(name)
        cmd.add_argument("--db", type=Path, required=True)
        cmd.add_argument("--out", type=Path, required=True)
        if name not in ["capture", "inventory", "issues", "write"]:
            cmd.add_argument("--inventory", type=Path, required=True)
        if name == "issues":
            cmd.add_argument("--issues-json", type=Path, required=True)
        if name == "inventory":
            cmd.add_argument("--rewind-out", type=Path)
        if name == "write":
            cmd.add_argument("--review", type=Path, required=True)
            cmd.add_argument("--verdict-out", type=Path)
        if name == "trace":
            cmd.add_argument("--qdc-cache", type=Path, required=True)
        if name == "score":
            cmd.add_argument("--model", choices=["xlsr", "mms"], required=True)
            cmd.add_argument("--qasr-root", type=Path, default=Path.home()/"qasr")
            cmd.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
            cmd.add_argument("--resume", action="store_true")
        if name == "review":
            cmd.add_argument("--compact-out", type=Path)
            cmd.add_argument("--trace", type=Path, required=True)
            cmd.add_argument("--xlsr", type=Path, required=True)
            cmd.add_argument("--mms", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "capture":
        capture(args)
    elif args.command == "inventory":
        with closing(sqlite3.connect(f"file:{args.db.resolve()}?mode=ro", uri=True)) as db:
            result = inventory(read_rows(db))
        args.out.write_text(json.dumps(result, indent=2) + "\n")
        print(result["rowCount"], "rows;", len(result["candidates"]), "pairs")
        if args.rewind_out:
            with closing(sqlite3.connect(f"file:{args.db.resolve()}?mode=ro", uri=True)) as db:
                args.rewind_out.write_text(json.dumps(rewind_inventory(read_rows(db)), separators=(",", ":")) + "\n")
    elif args.command == "issues":
        with closing(sqlite3.connect(f"file:{args.db.resolve()}?mode=ro", uri=True)) as db:
            result = issue_inventory(json.loads(args.issues_json.read_text()), read_rows(db))
        result["inputSha256"] = hashlib.sha256(args.issues_json.read_bytes()).hexdigest()
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    elif args.command == "score":
        score(args)
    elif args.command == "trace":
        from timing_source_trace import trace_candidates
        source = json.loads(args.inventory.read_text())
        with closing(sqlite3.connect(f"file:{args.db.resolve()}?mode=ro", uri=True)) as db:
            if inventory(read_rows(db)) != source:
                raise ValueError("stale source replay inventory")
            traces = trace_candidates(db, source["candidates"], args.qdc_cache)
        args.out.write_text(json.dumps({"corpusSha256": source["corpusSha256"], "rows": traces},
                                       ensure_ascii=False) + "\n")
    elif args.command == "review":
        raise SystemExit(review(args))
    else:
        with closing(sqlite3.connect(f"file:{args.db.resolve()}?mode=ro", uri=True)) as db:
            rows = read_rows(db)
            reviewed = json.loads(args.review.read_text())
            result = generated_corrections(reviewed, rows)
            if args.verdict_out:
                slugs = dict(db.execute("SELECT id,slug FROM reciters"))
                verdicts = {f"{slugs[d['key'][0]]}:{d['key'][1]}:{d['key'][2]}":
                    verdict_entry(d, *rows[tuple(d["key"])])
                    for d in reviewed["decisions"] if d["decision"] == "repair"}
                args.verdict_out.write_text(json.dumps({"schemaVersion": 1,
                    "sourceProfile": "delivered-corpus acoustic candidate; authenticated promotion pending",
                    "corpusSha256": reviewed["corpusSha256"], "verdicts": verdicts},
                    ensure_ascii=False, indent=2) + "\n")
        from find_flattened_resays import _dump_corrections
        args.out.write_text(_dump_corrections(result))
        print(len(result["edits"]), "generated corrections")


if __name__ == "__main__":
    main()
