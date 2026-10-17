# Held-word versus repeat audit

Every new timing patch requires a search for the same cause across the delivered
corpus, including other reciters, existing corrections/holds and genuine-repeat
controls. A fixture for one verse does not establish that a class is fixed.
Record the exact search predicate and every candidate's repair, retain or
uncertain decision. Missing inference/source data means incomplete coverage.

## Why occurrence confidence needs acoustic ownership

Forced CTC alignment answers a question conditional on the supplied transcript.
When the transcript contains a duplicated word, its second label can confidently
occupy the very frames that the single-word transcript assigns to the retained
word. In Alafasy 47:4, XLSR's second word-13 label has probability about 0.666,
but most of its phonetic frames remain in the one-word candidate. The additional
label is not independent evidence of another utterance.

The ordinary removal gate remains: both models must prefer the new occurrence
sequence by more than 0.001 log-probability per frame, and a removed occurrence
must have baseline label probability below 0.5 on each model. A narrowly bounded
alternative permits an **exact contiguous same-word merge** when a high-confidence
label's speech is retained. It requires:

- Exactly one adjacent same-position pair joined into its complete time span;
  every other position, start and end unchanged.
- Both models' sequence preference, the existing waveform/decoded-duration gate,
  and a merged-word start within 250 ms of both forced starts.
- For any model exceeding the ordinary 0.5 removal ceiling, a raw emitting-frame
  proof: every confident baseline frame of the pair must be explained by the
  same token on the **identical frame** of the candidate's merged word or its
  immediate neighbours.
- Candidate-word probability at least 0.5 and no unexplained frame probability
  reaching 0.5. The gate recomputes this from raw frame assignments rather than
  trusting a declared zero or matching source label ordinals.

Token IDs are compared only within one model, using the same recording and
neural emission tensor for both hypotheses. XLSR Arabic and MMS/uroman remain
separate witnesses. No Bluetooth offset, duration heuristic, silence threshold
or text-only pattern establishes a repeat verdict. Paused source pairs remain
protected even if the model scores prefer merging them. Multiple prospective
merges in a row need joint inference before generation.

## Reproduce the corpus search

Use private local scratch. Raw runtime packs and the combined database must
not be committed. `capture` preserves independent bundled rows and fetches all
six reviewed QF timing views, validating identities, revisions and coverage.
An ordinary release database alone is incomplete because it excludes these
runtime voices; the auditor rejects that omission.

```bash
python3 tools/audit_word_splits.py capture \
  --db data/quran.db --out /private/timing-audit/live.db
python3 tools/audit_word_splits.py inventory \
  --db /private/timing-audit/live.db --out /private/timing-audit/inventory.json \
  --rewind-out /private/timing-audit/pre-rewind.pending.json
gh issue list --repo sguergachi/Beautiful-Quran --state all --limit 1000 \
  --json number,title,state,body > /private/timing-audit/issues.json
python3 tools/audit_word_splits.py issues \
  --db /private/timing-audit/live.db --issues-json /private/timing-audit/issues.json \
  --out /private/timing-audit/issue-inventory.json
python3 tools/audit_word_splits.py trace \
  --db /private/timing-audit/live.db --inventory /private/timing-audit/inventory.json \
  --qdc-cache ~/Dev/Beautiful-Quran/tools/.cache --out /private/timing-audit/trace.json
HF_HUB_OFFLINE=1 OMP_NUM_THREADS=4 ~/qasr/venv/bin/python tools/audit_word_splits.py score \
  --db /private/timing-audit/live.db --inventory /private/timing-audit/inventory.json \
  --model xlsr --out /private/timing-audit/xlsr.jsonl --resume
HF_HUB_OFFLINE=1 OMP_NUM_THREADS=4 ~/qasr/venv/bin/python tools/audit_word_splits.py score \
  --db /private/timing-audit/live.db --inventory /private/timing-audit/inventory.json \
  --model mms --out /private/timing-audit/mms.jsonl --resume
python3 tools/audit_word_splits.py review \
  --db /private/timing-audit/live.db --inventory /private/timing-audit/inventory.json \
  --trace /private/timing-audit/trace.json \
  --xlsr /private/timing-audit/xlsr.jsonl --mms /private/timing-audit/mms.jsonl \
  --out /private/timing-audit/review.json \
  --compact-out /private/timing-audit/review-index.json
python3 tools/audit_word_splits.py write \
  --db /private/timing-audit/live.db --review /private/timing-audit/review.json \
  --out /private/timing-audit/corrections.json \
  --verdict-out /private/timing-audit/candidate-verdicts.json
```

The existing `~/qasr/audio/<slug>_<surah><ayah>.mp3` cache supplies the exact
EveryAyah files. Fetch missing recordings and rerun `--resume`; errors are
recorded, never counted as successful inference. `review` writes partial
decisions but exits nonzero until both models have successfully covered every
candidate. Resumption checks exact row payloads and model revisions. The corpus
fingerprint includes all rows, not only the candidate verses. If the issue
query reaches its limit, paginate rather than treating a truncated catalogue
as complete. The pre-rewind inventory is a distinct, explicitly unscored class;
same-word merge verdicts cannot approve those removals.

`write --db ... --review ... --out ...` generates the complete accepted class
as source-guarded `merge_verified_word_split` operations. It refuses incomplete
or stale coverage. Reconcile each source pair with its actual pipeline replay
before admitting generated entries, including the entire finalizer output and
onset. Keep private source packs, full alignment JSONL and complete row replays
local, as required by `QF_TIMING_PARITY.md`. Commit bounded actual inference
excerpts, inventory/dispositions, model/audio hashes, local raw proofs,
regressions, and exact changed-row verdicts. An uncertain case stays at baseline.
The `trace` command verifies pinned legacy/QUA archives and records cleaner,
correction, repair, finalization and prior-hold evidence for every candidate
verse. The review retains cases whose current pipeline input does not match the
audited source pair. This legacy trace never implies authenticated-source parity.

## Promotion is separate from acoustic review

Trace candidates through raw source, cleaner, clock rebase, typed corrections,
generated repairs, finalization and reviewed holds. Compare analogous timing
issues, including closed reports whose correction may have been restored by a
later safeguard. A legacy-cache replay cannot replace authenticated-source
review. Public runtime capture establishes what clients receive; it does not
recover private snapshots or authorize a new runtime publication.

QF fixes need their authenticated replay and source-profile verdicts before
runtime publication. Independent asset changes need their full rebuild,
version/fingerprint bump and delta verdicts. An APK rebuild cannot publish QF
timing resources. The [2026-10-17 completed review](quality-reviews/TIMING_WORD_SPLIT_CORPUS_2026-10-17.md)
records 71 supported merges, 784 retained pairs and 166 uncertain pairs across
the complete 1,021-pair cohort.
