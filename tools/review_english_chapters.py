#!/usr/bin/env python3
"""Resumable, offline-artifact English mapping review; AI review is never gold."""
import argparse
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import copy
import inspect
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
from threading import Event

import english_alignment_ledger as ledger
import english_review_selection as selection
from english_alignment_evidence import QuranEvidence, structured, surface
from english_review_wait import ModelUnavailableError, UsageLimitError, UsageWait

RUBRIC = Path(__file__).with_name("english_chapter_review.md")
REASONING = "xhigh"


def write_text(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(text)
    temporary.replace(path)


def save(path, value):
    write_text(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


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


def request_receipt(payload, output_schema, model):
    """Keep exact-request provenance without retaining source text or a prompt transcript."""
    return {"format": "english-review-request-v1", "input_sha256": ledger.digest(ledger.encoded(payload)),
            "schema_sha256": ledger.digest(ledger.encoded(output_schema)), "model": model,
            "reasoning_effort": REASONING, "rubric_sha256": ledger.digest(RUBRIC.read_bytes())}


def model_call(payload, output_schema, directory, model, timeout):
    """Source text and CLI transcripts remain in memory, including failed requests."""
    directory.mkdir(parents=True, exist_ok=True)
    receipt = request_receipt(payload, output_schema, model)
    save(directory / "request.json", dict(receipt, completed=False))
    save(directory / "schema.json", output_schema)
    answer = directory / "answer.json"
    answer.unlink(missing_ok=True)
    # A separate empty working directory prevents project instructions from
    # turning annotation into a coding or memory-management task.
    with tempfile.TemporaryDirectory(prefix="bq-annotation-") as working:
        command = ["codex", "exec", "--ignore-user-config", "--ephemeral", "--skip-git-repo-check",
                   "--sandbox", "read-only", "--model", model, "--cd", working,
                   "-c", f'model_reasoning_effort="{REASONING}"',
                   "--output-schema", str((directory / "schema.json").resolve()),
                   "--output-last-message", str(answer.resolve()), "-"]
        process = None
        try:
            process = subprocess.run(command, input=RUBRIC.read_text() + "\nINPUT JSON:\n" + json.dumps(payload, ensure_ascii=False),
                           text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=True)
        except subprocess.CalledProcessError as error:
            process = error
            errors = [line for line in (error.stderr or '').split("\n") if line.startswith("ERROR:")]
            for message in errors:
                if any(marker in message.lower() for marker in ["usage limit", "quota", "rate limit", "too many requests"]):
                    raise UsageLimitError(message) from None
                if any(marker in message.lower() for marker in ["not authenticated", "invalid api key", "authentication", "unauthorized"]):
                    raise ModelUnavailableError(message) from None
            raise
        finally:
            if process is not None:
                save(directory / "process.json", {"stdout_sha256": ledger.digest((process.stdout or '').encode()),
                     "stderr_sha256": ledger.digest((process.stderr or '').encode())})
    result = json.loads(answer.read_text())
    save(directory / "request.json", dict(receipt, completed=True, answer_sha256=ledger.digest(ledger.encoded(result))))
    return result


def cached_model_call(payload, output_schema, directory, model, timeout, cancelled=None, usage_wait=None):
    """Resume a completed stage after interruption; the job directory binds model and rubric."""
    if cancelled is not None and cancelled.is_set():
        raise KeyboardInterrupt
    expected = request_receipt(payload, output_schema, model)
    def completed(stage):
        receipt = json.loads((stage / "request.json").read_text())
        answer = json.loads((stage / "answer.json").read_text())
        return answer if (receipt == dict(expected, completed=True,
                        answer_sha256=ledger.digest(ledger.encoded(answer)))) else None
    if (directory / "request.json").exists():
        try:
            answer = completed(directory)
            if answer is not None:
                return answer
        except (OSError, ValueError):
            pass  # An interrupted partial write is not a completed model stage.
    # A validator change can invalidate a job without changing what the model
    # was asked. Reuse that exact completed request, then run the new gates.
    previous_stages = directory.parents[2].glob(f"*/*/{directory.name}") if len(directory.parents) > 2 and directory.name in {"draft", "review"} else []
    for previous in previous_stages:
        if previous == directory:
            continue
        try:
            answer = completed(previous)
            if answer is None:
                continue
            directory.mkdir(parents=True, exist_ok=True)
            for name in ["request.json", "schema.json", "answer.json"]:
                shutil.copyfile(previous / name, directory / name)
            save(directory / "reuse.json", {"from": str(previous), "answer_sha256": ledger.digest(ledger.encoded(answer))})
            return answer
        except (OSError, ValueError):
            continue
    while True:
        if usage_wait is not None:
            usage_wait.wait()
        try:
            answer = model_call(payload, output_schema, directory, model, timeout)
            if usage_wait is not None:
                usage_wait.succeeded()
            return answer
        except UsageLimitError as error:
            if usage_wait is None:
                raise
            usage_wait.defer(str(error))
        finally:
            if cancelled is not None and cancelled.is_set():
                raise KeyboardInterrupt


def evidence_receipt(package):
    """An attribution/hash record is not an offline copy of the retrieved content."""
    return {"format": "english-evidence-receipt-v1", "verse": package["verse"],
            "fingerprint": package["fingerprint"], "tafsir_edition": package["tafsir_edition"],
            "package_sha256": ledger.digest(ledger.encoded(package)),
            "sources": {name: {"edition": source["edition"], "citation_url": source["entry"].get("citation_url"),
                        "reply_sha256": source["reply_sha256"]} for name, source in package["sources"].items()},
            "morphology_reply_sha256": package["morphology_reply_sha256"]}


def purge_source_payloads(work):
    """Remove legacy MCP reply/prompt copies, retaining decisions and hash receipts."""
    import fcntl
    with (work / ".lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        count = 0
        for path in (work / "evidence").glob("*.json"):
            value = json.loads(path.read_text())
            if value.get("format") != "english-evidence-receipt-v1":
                save(path, evidence_receipt(value)); count += 1
        raw = work / "evidence" / "raw"
        if raw.exists():
            count += sum(p.is_file() for p in raw.rglob('*'))
            shutil.rmtree(raw)
        for path in (work / "jobs").rglob('input.json'):
            # Preserve the hash even for an incomplete legacy request. It is not
            # reusable without a completed request receipt from this implementation.
            save(path.with_name('legacy-input-receipt.json'), {"input_sha256": ledger.digest(ledger.encoded(json.loads(path.read_text())))})
            path.unlink(); count += 1
        for pattern in ['stdout.log', 'stderr.log']:
            for path in (work / "jobs").rglob(pattern):
                save(path.with_name(path.name + '.receipt.json'), {"sha256": ledger.digest(path.read_bytes())})
                path.unlink(); count += 1
        orphans = list((work / 'evidence').glob('*.json.tmp')) + list((work / 'jobs').rglob('input.json.tmp'))
        if orphans:
            save(work / 'source-orphan-receipts.json', [{"path": str(p.relative_to(work)),
                 "sha256": ledger.digest(p.read_bytes())} for p in orphans])
            for path in orphans:
                path.unlink(); count += 1
        if count or not (work / 'source-purge.json').exists():
            save(work / 'source-purge.json', {"removed_or_replaced_files": count,
                 "retained": "AI decisions, source attribution and hashes; no MCP source replies or prompt transcripts"})
        return count


def stored_evidence(work, row):
    """A completed review's own evidence receipt re-binds it without a paid refetch."""
    path = work / "evidence" / (row["verse"].replace(":", "-") + ".json")
    try:
        receipt = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    bound = receipt.get("format") == "english-evidence-receipt-v1" and receipt.get("verse") == row["verse"] \
        and receipt.get("fingerprint") == row["fingerprint"]
    return receipt if bound else None


def fetch_evidence(client, row, directory, tafsir):
    """Retrieve and validate online; persist only citations and hashes, never source text."""
    path = directory / (row["verse"].replace(":", "-") + ".json")
    grounding = copy.deepcopy(client.grounding)
    for block in grounding.get('content', []):
        if block.get('type') == 'text':
            block['text'] = re.split(r'\n---\nGROUNDING_NONCE:', block['text'])[0]
    package = {"verse": row["verse"], "fingerprint": row["fingerprint"], "tafsir_edition": tafsir,
               "grounding_rules": grounding,
               "tafsir_selection": "Concise lexical and phrase parsing; catalogue description retained.", "sources": {}}
    for name, edition in [("fetch_quran", "ar-uthmani-hafs"),
                          ("fetch_translation", "en-sahih-international"), ("fetch_tafsir", tafsir)]:
        pages = []; entries = []; continuation = None
        while True:
            arguments = {"continuation": continuation} if continuation else {"ayahs": row["verse"], "editions": edition}
            raw = client.call(name, arguments)
            pages.append(raw)
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
    morphology = client.call("fetch_word_morphology", {"ayah_key": row["verse"]})
    data = structured(morphology)
    ledger.require(data["ayah_key"] == row["verse"], "Wrong morphology verse")
    package["morphology"] = dict(data, words=[w for w in data["words"] if surface(w["text_uthmani"])])
    package["omitted_verse_markers"] = [w for w in data["words"] if not surface(w["text_uthmani"])]
    package["morphology_reply_sha256"] = ledger.digest(ledger.encoded(morphology))
    validate_evidence(row, package)
    save(path, evidence_receipt(package))
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


def separate_units(answer):
    """Split invalid unit IDs into contiguous contributor portions; never edit a meaning or owner."""
    result = copy.deepcopy(answer)
    units = {}
    for index, token in enumerate(result["tokens"]):
        units.setdefault(token["unit"], []).append(index)
    occupied = set(units)
    for unit, indices in units.items():
        contributors = [tuple(sorted(result["tokens"][i]["contributors"])) for i in indices]
        if indices[-1] - indices[0] + 1 == len(indices) and len(set(contributors)) == 1:
            continue
        last = None
        for index, sources in zip(indices, contributors):
            if last != (index - 1, sources):
                label = f"portion-{index}"
                while label in occupied:
                    label += "-"
                occupied.add(label)
            result["tokens"][index]["unit"] = label
            last = (index, sources)
    return result


def job_signature(row, binding, evidence, model):
    evidence_hash = evidence['package_sha256'] if evidence.get('format') == 'english-evidence-receipt-v1' else ledger.digest(ledger.encoded(evidence))
    return ledger.digest(ledger.encoded([row, binding, evidence_hash, model, RUBRIC.read_text(),
                                         REVIEW_POLICY]))


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
            raw_draft = call({"stage": "draft", "verse": row, "evidence": evidence, "feedback": feedback}, DRAFT_SCHEMA, run / "draft")
            draft = separate_units(raw_draft)
            if draft != raw_draft:
                save(run / "separated-units.json", draft)
            mapped = candidate(row, draft, binding, evidence, model)
            if draft != raw_draft:
                mapped["unit_separation"] = {"raw_draft_sha256": ledger.digest(ledger.encoded(raw_draft)),
                    "policy": "Split contiguous contributor portions; all meaning/owner decisions unchanged"}
            # The critique receives token decisions, not the drafting transcript.
            review = call({"stage": "review", "verse": row, "decisions": draft, "evidence": evidence}, REVIEW_SCHEMA, run / "review")
            ledger.require(review.get("verse") == row["verse"] and type(review.get("approved")) is bool and
                isinstance(review.get("issues"), list) and all(isinstance(i, str) and i.strip() for i in review["issues"]),
                "Malformed review verdict")
            if review["approved"] and not review["issues"]:
                for token in mapped["tokens"]:
                    token["review"]["evidence"].append(f"fresh critique sha256:{ledger.digest(ledger.encoded(review))}")
                mapped["automation"] = {"policy_sha256": REVIEW_POLICY,
                    "rubric_sha256": ledger.digest(RUBRIC.read_text().encode()),
                    "model": model, "reasoning_effort": REASONING, "independent_gold": False,
                    "raw_draft_sha256": ledger.digest(ledger.encoded(raw_draft)),
                    "critique_sha256": ledger.digest(ledger.encoded(review))}
                state.update(status="model_reviewed", row=mapped, issues=[])
                break
            feedback = review["issues"] or ["Critique withheld approval"]
        except ModelUnavailableError as error:
            state.update(provider_unavailable=True, issues=[str(error)])
            save(state_path, state)
            return state
        except (ValueError, KeyError, TypeError, subprocess.SubprocessError, OSError) as error:
            feedback = [str(error)]
        state["issues"] = feedback
        save(state_path, state)
    save(state_path, state)
    return state


# Capture executed policy once. Looking up live source line numbers during a
# long batch can accidentally bind a loaded function to a later editor change.
REVIEW_POLICY = ledger.digest(ledger.encoded([
    [inspect.getsource(f) for f in [candidate, separate_units, review_verse, validate_evidence, model_call]],
    DRAFT_SCHEMA, REVIEW_SCHEMA, REASONING, Path(ledger.__file__).read_text(),
    Path(__file__).with_name("english_alignment_evidence.py").read_text(),
]))


def summarize(rows, states, selected, playback, queued=None):
    report = {"semantic_accuracy_established": False, "approval_policy": "ai_reviewed", "chapters": {}}
    queued = set(queued if queued is not None else rows)
    for chapter in sorted(selected):
        keys = [key for key in rows if int(key.split(":")[0]) == chapter]
        reviewable = [key for key in keys if key in queued]
        approved = [key for key in reviewable if states.get(key, {}).get("status") == "model_reviewed"]
        decisions = [(base, mapped) for key in approved for base, mapped in
                     zip(rows[key].get("tokens", []), states[key].get("row", {}).get("tokens", []))]
        report["chapters"][str(chapter)] = {"verses": len(keys), "queued": len(reviewable),
            "model_reviewed": len(approved), "left_at_baseline": len(keys) - len(reviewable),
            "reused_prior_ai_verses": sum(states[key].get("origin") == "prior_ai_review" for key in approved),
            "freshly_critiqued_verses": sum(states[key].get("origin") != "prior_ai_review" for key in approved),
            "printed_tokens": sum(len(rows[key].get("tokens", [])) for key in keys),
            "reviewed_tokens": len(decisions),
            "changed_tap_heads": sum(a.get("head") != b["head"] for a, b in decisions),
            "changed_ink_anchors": sum(a.get("ink_anchor") != b["ink_anchor"] for a, b in decisions),
            "null_linked_tokens": sum(not b["contributors"] for _, b in decisions),
            "held": len(reviewable) - len(approved), "playback": playback,
            "complete": len(approved) == len(reviewable) and playback == "passed",
            "issues": {key: states.get(key, {}).get("issues", ["Not processed"]) for key in reviewable if key not in approved}}
    return report


def import_seed(rows, manifest, states, seed_rows, selected):
    """Reuse prior AI decisions explicitly; never claim they received this runner's fresh critique."""
    for key, row in seed_rows.items():
        if key in states or int(key.split(":")[0]) not in selected:
            continue
        ledger.require(key in rows and all(t["status"] == "model_reviewed" for t in row["tokens"]), "Invalid AI seed")
        ledger.require(ledger.compile_verse(row, manifest["verses"][key], ai_reviewed=True), "Incomplete AI seed")
        states[key] = {"verse": key, "status": "model_reviewed", "origin": "prior_ai_review",
                       "seed_sha256": ledger.digest(ledger.encoded(row)), "row": row, "issues": []}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["run", "report", "purge-source-cache"])
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--chapters", default="1-114")
    parser.add_argument("--corpus", type=Path)
    parser.add_argument("--timings-cache", type=Path)
    parser.add_argument("--model")
    parser.add_argument("--attempts", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--tafsir", default="ar-jalalayn")
    parser.add_argument("--seed-ledger", type=Path, default=ledger.REPO / "data/english_ai_alignment_ledger.jsonl",
                        help="Retain previously AI-reviewed verses, bound and playback-tested again")
    parser.add_argument("--refresh-reviewed", action="store_true", help="Re-annotate selected prior AI verses instead of reusing them")
    parser.add_argument("--retry-held", action="store_true")
    parser.add_argument("--max-verses", type=int, default=25,
                        help="New/retried verse budget (default 25); cached reviews do not consume it")
    parser.add_argument("--selection", choices=["risky", "all"], default="risky",
                        help="risky: only verses whose baseline leaves an Arabic word unowned, plus the audit sample")
    parser.add_argument("--audit-rate", type=float, default=0.1,
                        help="Share of baseline-covered verses still reviewed to bound their error rate")
    parser.add_argument("--workers", type=int, default=2, help="1–4 concurrent verse reviews; canonical fetches remain serial")
    args = parser.parse_args()
    work = args.work_dir.resolve()
    if args.command == "report":
        report = json.loads((work / "report.json").read_text())
        if (work / "usage-wait.json").exists():
            report['usage_wait'] = json.loads((work / "usage-wait.json").read_text())
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    if args.command == "purge-source-cache":
        print(f"Removed/replaced {purge_source_payloads(work)} legacy source-copy files.")
        return
    ledger.require(args.model and args.corpus and args.timings_cache, "run needs --model, --corpus and --timings-cache")
    ledger.require(1 <= args.attempts <= 3 and 1 <= args.timeout <= 1800, "Invalid retry/timeout budget")
    ledger.require(args.max_verses is None or args.max_verses > 0, "--max-verses must be positive")
    ledger.require(0 <= args.audit_rate <= 1, "--audit-rate must be a share of verses")
    ledger.require(1 <= args.workers <= 4, "--workers must be 1–4")
    cancelled = Event()
    def interrupt(signum, frame):
        cancelled.set()
        # Direct children are this run's model/validation processes, never
        # another worktree's runner. Do not leave paid requests orphaned.
        subprocess.run(["pkill", "-TERM", "-P", str(os.getpid())], check=False)
        raise KeyboardInterrupt
    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    selected = chapters(args.chapters)
    work.mkdir(parents=True, exist_ok=True)
    # A process lock prevents two runners from overwriting the same resumable queue.
    import fcntl
    with (work / ".lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        ledger.require(not (work / 'evidence/raw').exists() and
                       not next((work / 'jobs').rglob('input.json'), None) and
                       not next((work / 'jobs').rglob('input.json.tmp'), None) and
                       not next((work / 'evidence').glob('*.json.tmp'), None) and
                       not next((work / 'jobs').rglob('stderr.log'), None) and
                       not next((work / 'jobs').rglob('stdout.log'), None) and
                       all(json.loads(p.read_text()).get('format') == 'english-evidence-receipt-v1'
                           for p in (work / 'evidence').glob('*.json')),
                       'Legacy source copies found; preserve decisions and run purge-source-cache before resuming')
        usage_wait = UsageWait(work / "usage-wait.json", cancelled)
        usage_wait.wait()
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
        client = QuranEvidence(cancelled=cancelled)
        save(work / "grounding.json", client.grounding)
        catalogue = structured(client.call("list_editions", {"edition_type": ["translation", "tafsir"]}))
        save(work / "editions.json", catalogue)
        ledger.require(args.tafsir in {e["edition_id"] for e in catalogue["editions"]}, "Unknown tafsir edition")
        state_file = work / "state.json"
        states = json.loads(state_file.read_text()) if state_file.exists() else {}
        seeds = [json.loads(line) for line in args.seed_ledger.read_text().splitlines()]
        seed_rows = {r["verse"]: r for r in seeds}
        ledger.require(len(seed_rows) == len(seeds), "Duplicate AI seed verse")
        if not args.refresh_reviewed:
            import_seed(rows, manifest, states, seed_rows, selected)
        queue = selection.review_queue(rows, selected, args.selection, args.audit_rate,
                                       {key for key, state in states.items() if state.get("status") == "held"})
        flagged = sum(selection.needs_review(rows[key]) for key in queue)
        save(work / "selection.json", {"policy": args.selection, "audit_rate": args.audit_rate,
             "selection_sha256": ledger.digest(Path(selection.__file__).read_bytes()),
             "queued": len(queue), "flagged_uncovered": flagged, "audit_sampled": len(queue) - flagged,
             "left_at_baseline": sum(int(key.split(":")[0]) in selected for key in rows) - len(queue)})
        processed = 0
        pending_jobs = {}
        def checkpoint(key, state):
            states[key] = state
            save(state_file, states)
            save(work / "report.json", summarize(rows, states, selected, "pending", queue))
            print(f"{key}: {state['status']}", flush=True)
            if state.get("provider_unavailable"):
                cancelled.set()
                subprocess.run(["pkill", "-TERM", "-P", str(os.getpid())], check=False)
                raise SystemExit("AI provider unavailable; completed work is saved. Resume with --retry-held after access returns.")

        def collect():
            completed, _ = wait(pending_jobs, return_when=FIRST_COMPLETED)
            for future in completed:
                key = pending_jobs.pop(future)
                checkpoint(key, future.result())

        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            for key in queue:
                row = rows[key]
                if args.max_verses is not None and processed >= args.max_verses:
                    break
                if states.get(key, {}).get("origin") == "prior_ai_review" and not args.refresh_reviewed:
                    checkpoint(key, states[key])
                    continue
                try:
                    usage_wait.wait()
                    old_state = states.get(key, {})
                    evidence = (stored_evidence(work, row) if old_state.get("status") == "model_reviewed" else None) \
                        or fetch_evidence(client, row, work / "evidence", args.tafsir)
                    directory = work / "jobs" / key.replace(":", "-")
                    if old_state.get("provider_unavailable") or (args.retry_held and old_state.get("status") == "held"):
                        old = directory / (states[key].get("input_sha256", "") + ".json")
                        if old.exists():
                            import time
                            suffix = f".held-{time.time_ns()}"
                            old.rename(old.with_suffix(suffix + ".json"))
                            runs = old.with_suffix("")
                            if runs.exists():
                                runs.rename(runs.with_name(runs.name + suffix))
                    call = lambda payload, output, dest: cached_model_call(payload, output, dest, args.model, args.timeout, cancelled, usage_wait)
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
            if state.get("origin") == "prior_ai_review":
                ledger.require(state["seed_sha256"] == ledger.digest(ledger.encoded(seed_rows[key])), "Prior AI decision changed")
                accepted.append(state["row"])
                continue
            evidence = json.loads((work / "evidence" / (key.replace(":", "-") + ".json")).read_text())
            if state["input_sha256"] == job_signature(rows[key], manifest["verses"][key], evidence, state["model"]):
                accepted.append(state["row"])
            else:
                state.update(status="held", issues=["Review inputs or policy changed; rerun this chapter"])
        save(state_file, states)
        reused = sum(s.get("origin") == "prior_ai_review" for s in states.values() if s.get("status") == "model_reviewed")
        for row in seeds:
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
        report = summarize(rows, states, selected, "passed" if audit.returncode == 0 else "failed", queue)
        report["reused_prior_ai_verses"] = reused
        report["selection"] = json.loads((work / "selection.json").read_text())
        report["candidate_artifact_sha256"] = coverage["artifact_sha256"]
        report["artifact_promoted"] = audit.returncode == 0
        if audit.returncode:
            save(work / "report.json", report)
            raise ValueError(f"Playback audit failed; candidate withheld. See {work / 'playback.log'}")
        write_text(work / "english-ai-alignments.tsv", artifact)
        save(work / "coverage.json", coverage)
        save(work / "report.json", report)
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit("Interrupted; completed reviews and model stages are saved for resume.")
