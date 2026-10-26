#!/usr/bin/env python3
"""Resumable, offline-artifact English mapping review; AI review is never gold."""
import argparse
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import copy
import inspect
import json
from pathlib import Path
import subprocess
import tempfile

import english_alignment_ledger as ledger
from english_alignment_evidence import QuranEvidence, structured, surface

RUBRIC = Path(__file__).with_name("english_chapter_review.md")


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def chapters(value):
    selected = set()
    for part in value.split(","):
        ends = list(map(int, part.split("-")))
        ledger.require(1 <= ends[0] <= ends[-1] <= 114 and len(ends) <= 2, "Invalid chapter range")
        selected.update(range(ends[0], ends[-1] + 1))
    return selected


def schema(properties):
    return {"type": "object", "properties": properties, "required": list(properties), "additionalProperties": False}


INTEGER = {"type": "integer"}
STRING = {"type": "string"}
DRAFT_SCHEMA = schema({"verse": STRING, "tokens": {"type": "array", "items": schema({
    "index": INTEGER, "contributors": {"type": "array", "items": INTEGER},
    "head": INTEGER, "ink_anchor": INTEGER, "meaning": STRING, "unit": STRING})}})
REVIEW_SCHEMA = schema({"verse": STRING, "approved": {"type": "boolean"},
                        "issues": {"type": "array", "items": STRING}})


def entry_keys(entry):
    """Tafsir names its covered ayahs; Quran/translation entries name one ayah."""
    return {entry["ayah"]} if "ayah" in entry else set(entry.get("ayahs", []))


def model_call(payload, output_schema, directory, model, timeout):
    """Fresh, read-only Codex invocation per pass; no API secret enters any artifact."""
    directory.mkdir(parents=True, exist_ok=True)
    save(directory / "input.json", payload)
    save(directory / "schema.json", output_schema)
    answer = directory / "answer.json"
    # A separate empty working directory prevents project instructions from
    # turning annotation into a coding or memory-management task.
    with tempfile.TemporaryDirectory(prefix="bq-annotation-") as working:
        command = ["codex", "exec", "--ignore-user-config", "--ephemeral", "--skip-git-repo-check",
                   "--sandbox", "read-only", "--model", model, "--cd", working,
                   "-c", 'model_reasoning_effort="xhigh"',
                   "--output-schema", str((directory / "schema.json").resolve()),
                   "--output-last-message", str(answer.resolve()), "-"]
        with (directory / "stdout.log").open("w") as stdout, (directory / "stderr.log").open("w") as stderr:
            subprocess.run(command, input=RUBRIC.read_text() + "\nINPUT JSON:\n" + json.dumps(payload, ensure_ascii=False),
                           text=True, stdout=stdout, stderr=stderr, timeout=timeout, check=True)
    return json.loads(answer.read_text())


def cached_model_call(payload, output_schema, directory, model, timeout):
    """Resume a completed stage after interruption; the job directory binds model and rubric."""
    if all((directory / name).exists() for name in ["input.json", "schema.json", "answer.json"]):
        try:
            if (json.loads((directory / "input.json").read_text()) == payload and
                    json.loads((directory / "schema.json").read_text()) == output_schema):
                return json.loads((directory / "answer.json").read_text())
        except json.JSONDecodeError:
            pass  # An interrupted partial write is not a completed model stage.
    return model_call(payload, output_schema, directory, model, timeout)


def fetch_evidence(client, row, directory, tafsir):
    """Cache exact replies; validate verse keys, editions, pages and surface positions."""
    path = directory / (row["verse"].replace(":", "-") + ".json")
    if path.exists():
        package = json.loads(path.read_text())
        ledger.require(package["fingerprint"] == row["fingerprint"] and package["tafsir_edition"] == tafsir,
                       "Stale evidence binding; use a new work directory")
        validate_evidence(row, package)
        return package
    package = {"verse": row["verse"], "fingerprint": row["fingerprint"], "tafsir_edition": tafsir,
               "grounding_rules": client.grounding,
               "tafsir_selection": "Concise lexical and phrase parsing; catalogue description retained.", "sources": {}}
    for name, edition in [("fetch_quran", "ar-uthmani-hafs"),
                          ("fetch_translation", "en-sahih-international"), ("fetch_tafsir", tafsir)]:
        pages = []; entries = []; continuation = None
        while True:
            arguments = {"continuation": continuation} if continuation else {"ayahs": row["verse"], "editions": edition}
            raw = client.call(name, arguments)
            pages.append(raw)
            save(directory / "raw" / row["verse"].replace(":", "-") / (name + ".json"), pages)
            result = structured(raw)
            ledger.require(result.get("ayahs") == [row["verse"]], f"Wrong requested verse from {name}")
            ledger.require(set(result["results"]) == {edition}, f"Unexpected edition from {name}")
            for item in result["results"][edition]:
                ledger.require(row["verse"] in entry_keys(item), f"Wrong verse from {name}")
                entries.append(item)
            pagination = result.get("pagination", {})
            if not pagination.get("has_more"):
                break
            next_page = pagination.get("continuation")
            ledger.require(next_page and next_page != continuation, "Invalid evidence continuation")
            continuation = next_page
        ledger.require(len(entries) == 1, f"Missing/duplicate canonical verse in {name}")
        package["sources"][name] = {"edition": edition, "entry": entries[0], "reply_sha256": ledger.digest(ledger.encoded(pages))}
        save(directory / "raw" / row["verse"].replace(":", "-") / (name + ".json"), pages)
    morphology = client.call("fetch_word_morphology", {"ayah_key": row["verse"]})
    data = structured(morphology)
    ledger.require(data["ayah_key"] == row["verse"], "Wrong morphology verse")
    package["morphology"] = dict(data, words=[w for w in data["words"] if surface(w["text_uthmani"])])
    package["omitted_verse_markers"] = [w for w in data["words"] if not surface(w["text_uthmani"])]
    package["morphology_reply_sha256"] = ledger.digest(ledger.encoded(morphology))
    validate_evidence(row, package)
    save(directory / "raw" / row["verse"].replace(":", "-") / "fetch_word_morphology.json", morphology)
    save(path, package)
    return package


def validate_evidence(row, package):
    ledger.require(package["verse"] == row["verse"] and package["morphology"]["ayah_key"] == row["verse"],
                   "Cached evidence verse differs")
    for source in package["sources"].values():
        ledger.require(row["verse"] in entry_keys(source["entry"]), "Cached canonical verse differs")
    words = package["morphology"]["words"]
    ledger.require([w["position"] for w in words] == list(range(1, len(row["arabic"]) + 1)), "Morphology positions differ")
    ledger.require([surface(w["text_uthmani"]) for w in words] == [surface(w) for w in row["arabic"]],
                   "Morphology/reader surface crosswalk differs")
    fetched = package["sources"]["fetch_quran"]["entry"]["text"]
    ledger.require([surface(w) for w in fetched.split() if surface(w)] == [surface(w) for w in row["arabic"]],
                   "Fetched Arabic/reader surfaces differ")


def candidate(row, answer, binding, evidence, model):
    ledger.require(answer.get("verse") == row["verse"], "Draft verse mismatch")
    decisions = answer["tokens"]
    ledger.require([d["index"] for d in decisions] == list(range(len(row["tokens"]))), "Missing/duplicate/reordered draft tokens")
    result = copy.deepcopy(row)
    result["qac_trust"] = "surface_verified"
    evidence_id = ledger.digest(ledger.encoded(evidence))
    citations = [s["entry"]["citation_url"] for s in evidence.get("sources", {}).values() if s["entry"].get("citation_url")]
    for token, decision in zip(result["tokens"], decisions):
        token.update({k: decision[k] for k in ["contributors", "head", "ink_anchor", "meaning", "unit"]})
        token.update(status="model_reviewed", review={"method": "model_review", "reviewer": model,
            "evidence": [f"quran.ai package sha256:{evidence_id}", f"draft sha256:{ledger.digest(ledger.encoded(answer))}", *citations]})
    ledger.require(ledger.compile_verse(result, binding, ai_reviewed=True) is not None, "Incomplete candidate")
    return result


def job_signature(row, binding, evidence, model):
    return ledger.digest(ledger.encoded([row, binding, evidence, model, RUBRIC.read_text(),
        [inspect.getsource(f) for f in [candidate, review_verse, validate_evidence, model_call]],
        Path(ledger.__file__).read_text(), Path(__file__).with_name("english_alignment_evidence.py").read_text()]))


def review_verse(row, binding, evidence, directory, model, attempts, call):
    """A compiler-valid draft and a fresh critique must both pass; failures remain explicit."""
    signature = job_signature(row, binding, evidence, model)
    state_path = directory / (signature + ".json")
    if state_path.exists():
        return json.loads(state_path.read_text())
    state = {"verse": row["verse"], "model": model, "input_sha256": signature, "status": "held", "issues": [], "attempts": 0}
    feedback = []
    for attempt in range(attempts):
        state["attempts"] += 1
        run = directory / signature / str(attempt + 1)
        try:
            draft = call({"stage": "draft", "verse": row, "evidence": evidence, "feedback": feedback}, DRAFT_SCHEMA, run / "draft")
            mapped = candidate(row, draft, binding, evidence, model)
            # The critique receives token decisions, not the drafting transcript.
            review = call({"stage": "review", "verse": row, "decisions": draft, "evidence": evidence}, REVIEW_SCHEMA, run / "review")
            ledger.require(review.get("verse") == row["verse"] and type(review.get("approved")) is bool and
                isinstance(review.get("issues"), list) and all(isinstance(i, str) and i.strip() for i in review["issues"]),
                "Malformed review verdict")
            if review["approved"] and not review["issues"]:
                for token in mapped["tokens"]:
                    token["review"]["evidence"].append(f"fresh critique sha256:{ledger.digest(ledger.encoded(review))}")
                state.update(status="model_reviewed", row=mapped, issues=[])
                break
            feedback = review["issues"] or ["Critique withheld approval"]
        except (ValueError, KeyError, TypeError, subprocess.SubprocessError, OSError) as error:
            feedback = [str(error)]
        state["issues"] = feedback
        save(state_path, state)
    save(state_path, state)
    return state


def summarize(rows, states, selected, playback):
    report = {"semantic_accuracy_established": False, "approval_policy": "ai_reviewed", "chapters": {}}
    for chapter in sorted(selected):
        keys = [key for key in rows if int(key.split(":")[0]) == chapter]
        approved = [key for key in keys if states.get(key, {}).get("status") == "model_reviewed"]
        report["chapters"][str(chapter)] = {"verses": len(keys), "model_reviewed": len(approved),
            "held": len(keys) - len(approved), "playback": playback,
            "complete": len(approved) == len(keys) and playback == "passed",
            "issues": {key: states.get(key, {}).get("issues", ["Not processed"]) for key in keys if key not in approved}}
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["run", "report"])
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--chapters", default="1-114")
    parser.add_argument("--corpus", type=Path)
    parser.add_argument("--timings-cache", type=Path)
    parser.add_argument("--model")
    parser.add_argument("--attempts", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--tafsir", default="ar-jalalayn")
    parser.add_argument("--seed-ledger", type=Path, default=ledger.REPO / "data/english_ai_alignment_ledger.jsonl",
                        help="Retain previously AI-reviewed verses outside the selected chapters, bound and playback-tested again")
    parser.add_argument("--retry-held", action="store_true")
    parser.add_argument("--max-verses", type=int, help="Bound a batch; every unprocessed verse remains in the chapter denominator")
    parser.add_argument("--workers", type=int, default=2, help="1–4 concurrent verse reviews; canonical fetches remain serial")
    args = parser.parse_args()
    work = args.work_dir.resolve()
    if args.command == "report":
        print((work / "report.json").read_text())
        return
    ledger.require(args.model and args.corpus and args.timings_cache, "run needs --model, --corpus and --timings-cache")
    ledger.require(1 <= args.attempts <= 3 and 1 <= args.timeout <= 1800, "Invalid retry/timeout budget")
    ledger.require(args.max_verses is None or args.max_verses > 0, "--max-verses must be positive")
    ledger.require(1 <= args.workers <= 4, "--workers must be 1–4")
    selected = chapters(args.chapters)
    work.mkdir(parents=True, exist_ok=True)
    # A process lock prevents two runners from overwriting the same resumable queue.
    import fcntl
    with (work / ".lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        export = work / "export"
        if not (export / "manifest.json").exists():
            ledger.export_corpus(export, args.corpus)
        manifest = json.loads((export / "manifest.json").read_text())
        ledger.validate_manifest(manifest)
        rows = {r["verse"]: r for r in map(json.loads, (export / "ledger.jsonl").read_text().splitlines())}
        # Runtime TSVs can predate the current typesetter (e.g. hyphen vs en
        # dash). Audit the export's actual printed edition, with the same cards.
        corpus = {}
        for line in args.corpus.read_text().splitlines():
            fields = line.split("\t")
            ledger.require(fields[0] not in corpus and len(fields) >= 5, "Invalid/duplicate runtime corpus row")
            corpus[fields[0]] = fields
        ledger.require(set(corpus) == set(rows), "Runtime corpus must cover the complete printed population")
        for key, row in rows.items():
            ledger.require(corpus[key][4].split('|') == row["arabic"], f"Runtime Arabic surfaces differ: {key}")
            corpus[key][1] = row["text"]
        printed_corpus = work / "printed-corpus.tsv"
        printed_corpus.write_text("".join("\t".join(corpus[key]) + "\n" for key in rows))
        client = QuranEvidence()
        save(work / "grounding.json", client.grounding)
        catalogue = structured(client.call("list_editions", {"edition_type": ["translation", "tafsir"]}))
        save(work / "editions.json", catalogue)
        ledger.require(args.tafsir in {e["edition_id"] for e in catalogue["editions"]}, "Unknown tafsir edition")
        state_file = work / "state.json"
        states = json.loads(state_file.read_text()) if state_file.exists() else {}
        processed = 0
        pending_jobs = {}
        def checkpoint(key, state):
            states[key] = state
            save(state_file, states)
            save(work / "report.json", summarize(rows, states, selected, "pending"))
            print(f"{key}: {state['status']}", flush=True)

        def collect():
            completed, _ = wait(pending_jobs, return_when=FIRST_COMPLETED)
            for future in completed:
                key = pending_jobs.pop(future)
                checkpoint(key, future.result())

        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            for key, row in rows.items():
                if int(key.split(":")[0]) not in selected:
                    continue
                if args.max_verses is not None and processed >= args.max_verses:
                    break
                try:
                    evidence = fetch_evidence(client, row, work / "evidence", args.tafsir)
                    directory = work / "jobs" / key.replace(":", "-")
                    if args.retry_held and states.get(key, {}).get("status") == "held":
                        old = directory / (states[key].get("input_sha256", "") + ".json")
                        if old.exists():
                            import time
                            suffix = f".held-{time.time_ns()}"
                            old.rename(old.with_suffix(suffix + ".json"))
                            runs = old.with_suffix("")
                            if runs.exists():
                                runs.rename(runs.with_name(runs.name + suffix))
                    call = lambda payload, output, dest: cached_model_call(payload, output, dest, args.model, args.timeout)
                    cached = (directory / (job_signature(row, manifest["verses"][key], evidence, args.model) + ".json")).exists()
                    future = pool.submit(review_verse, row, manifest["verses"][key], evidence, directory, args.model, args.attempts, call)
                    pending_jobs[future] = key
                    processed += not cached
                except (ValueError, OSError, KeyError) as error:
                    checkpoint(key, {"verse": key, "status": "held", "issues": [str(error)]})
                    processed += 1
                if len(pending_jobs) >= args.workers:
                    collect()
            while pending_jobs:
                collect()
        # Only currently bound model-reviewed rows enter the candidate artifact.
        accepted = []
        for key, state in states.items():
            if state.get("status") != "model_reviewed" or key not in rows:
                continue
            evidence = json.loads((work / "evidence" / (key.replace(":", "-") + ".json")).read_text())
            if state["input_sha256"] == job_signature(rows[key], manifest["verses"][key], evidence, state["model"]):
                accepted.append(state["row"])
            else:
                state.update(status="held", issues=["Review inputs or policy changed; rerun this chapter"])
        save(state_file, states)
        reused = 0
        for line in args.seed_ledger.read_text().splitlines():
            row = json.loads(line)
            ledger.require(all(t["status"] == "model_reviewed" for t in row["tokens"]), "Seed must retain the AI-review policy")
            if int(row["verse"].split(":")[0]) in selected or row["verse"] in {r["verse"] for r in accepted}:
                continue
            if ledger.compile_verse(row, manifest["verses"][row["verse"]], ai_reviewed=True):
                accepted.append(row)
                reused += 1
        pending = work / "candidate-ledger.jsonl"
        pending.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in accepted))
        artifact, coverage = ledger.compile_ledger(pending, manifest, allow_partial=True, ai_reviewed=True)
        candidate_path = work / "candidate-alignments.tsv"
        candidate_path.write_text(artifact)
        audit_dir = work / "playback"
        command = ["python3", str(Path(__file__).with_name("audit_english_phrase_pacing.py")), str(printed_corpus),
                   str(args.timings_cache), "--output-dir", str(audit_dir), "--alignments", str(candidate_path)]
        command.append("--include-bundled")
        with (work / "playback.log").open("w") as output:
            audit = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
        report = summarize(rows, states, selected, "passed" if audit.returncode == 0 else "failed")
        report["reused_prior_ai_verses"] = reused
        save(work / "report.json", report)
        if audit.returncode:
            raise ValueError(f"Playback audit failed; candidate withheld. See {work / 'playback.log'}")
        (work / "english-ai-alignments.tsv").write_text(artifact)
        save(work / "coverage.json", coverage)
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
