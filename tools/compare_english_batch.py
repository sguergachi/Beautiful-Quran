#!/usr/bin/env python3
"""Measure agreement between the batched review and the per-verse review."""
import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import review_english_chapters as runner
import english_alignment_ledger as ledger


def agreement(baseline, batched):
    heads = [(a["head"], b["head"]) for a, b in zip(baseline["tokens"], batched["tokens"])]
    inks = [(a["ink_anchor"], b["ink_anchor"]) for a, b in zip(baseline["tokens"], batched["tokens"])]
    return {
        "head_agreement": sum(a == b for a, b in heads) / len(heads) if heads else 1.0,
        "ink_agreement": sum(a == b for a, b in inks) / len(inks) if inks else 1.0,
        "heads": heads,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--chapters", default="24")
    parser.add_argument("--batch", type=int, default=6)
    parser.add_argument("--limit", type=int, default=24)
    parser.add_argument("--model", default="gpt-6.1-sol")
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--attempts", type=int, default=2)
    parser.add_argument("--workers", type=int, default=2)
    args = parser.parse_args()
    work = args.work_dir.resolve()
    states = json.loads((work / "state.json").read_text())
    export = {r["verse"]: r for r in map(json.loads, (work / "export" / "ledger.jsonl").read_text().splitlines())}
    manifest = json.loads((work / "export" / "manifest.json").read_text())
    selected = runner.chapters(args.chapters)
    candidates = [key for key in export if int(key.split(":")[0]) in selected
                  and states.get(key, {}).get("status") == "model_reviewed"
                  and states[key].get("origin") != "prior_ai_review"][:args.limit]
    if not candidates:
        raise SystemExit("No per-verse reviewed verses in those chapters to compare against")
    scratch = Path(tempfile.mkdtemp(prefix="bq-batch-agreement-"))
    try:
        usage = runner.UsageWait(scratch / "usage-wait.json", __import__("threading").Event())
        usage.wait()
        evidence_by_key = {}
        for key in candidates:
            evidence = runner.stored_evidence(work, export[key])
            if evidence is None:
                from english_alignment_evidence import QuranEvidence
                client = QuranEvidence(cancelled=usage.cancelled if hasattr(usage, "cancelled") else None)
                evidence = runner.fetch_evidence(client, export[key], work / "evidence", "ar-jalalayn")
            evidence_by_key[key] = evidence
        items = [{"row": export[key], "binding": manifest["verses"][key],
                  "evidence": evidence_by_key[key],
                  "directory": scratch / "jobs" / key.replace(":", "-")} for key in candidates]
        call = lambda payload, schema, destination: runner.cached_model_call(
            payload, schema, destination, args.model, args.timeout, None, usage)
        batches = [items[index:index + args.batch] for index in range(0, len(items), args.batch)]
        results = {}
        with __import__("concurrent.futures").ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = []
            for batch in batches:
                signature = runner.batch_signature(batch, args.model)
                directory = runner.batch_directory(scratch, batch, args.model)
                future = pool.submit(runner.review_batch, batch, signature, directory, args.model, args.attempts, call)
                futures.append((batch, future))
            for batch, future in futures:
                for key, state in future.result().items():
                    results[key] = state
        total_heads = total_inks = verses = exact = 0
        mismatches = []
        for key in candidates:
            baseline = states[key]["row"]
            batched = results[key].get("row") if results[key].get("status") == "model_reviewed" else None
            if batched is None:
                print(f"{key}: NOT REVIEWED ({results[key].get('status')}, {results[key].get('issues')})")
                continue
            stats = agreement(baseline, batched)
            total_heads += sum(a == b for a, b in stats["heads"])
            total_inks += sum(a == b for a, b in stats["inks"])
            verses += 1
            if stats["head_agreement"] < 1.0:
                mismatches.append((key, stats["head_agreement"], stats["heads"]))
            if stats["head_agreement"] == 1.0 and stats["ink_agreement"] == 1.0:
                exact += 1
        n_heads = sum(len(states[k]["row"]["tokens"]) for k in candidates if results[k].get("status") == "model_reviewed")
        print(f"verses compared: {verses} of {len(candidates)}")
        print(f"exact match: {exact} ({100 * exact / verses:.0f}%)")
        print(f"head agreement: {100 * total_heads / n_heads:.1f}% ({total_heads}/{n_heads})")
        print(f"ink agreement: {100 * total_inks / n_heads:.1f}% ({total_inks}/{n_heads})")
        print("worst mismatches:")
        for key, score, heads in sorted(mismatches, key=lambda item: item[1])[:10]:
            print(f"  {key}: {score:.0%} {heads}")
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


if __name__ == "__main__":
    main()
PY
chmod +x /home/sammy/.t3/worktrees/Beautiful-Quran/t3-d08c8ff8/tools/compare_english_batch.py
python3 -m py_compile /home/sammy/.t3/worktrees/Beautiful-Quran/t3-d08c8ff8/tools/compare_english_batch.py && echo ok