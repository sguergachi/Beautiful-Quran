# Held-word split corpus review — 2026-10-17

The follow-up to #873 replaces the hand-entered 47:4 merge with a generated
acoustic class. Actual inference completed on **both models for all 1,021
adjacent same-position pairs**, covering 983 verses in 81,059 delivered timing
rows across all 13 timed reciters. Results: **71 repair candidates, 784 retained
pairs and 166 uncertain pairs; zero inference errors**. No production timing
resource or bundled database was published or changed.

## Why the existing tools missed the held word

The CTC repair already proposed one word. `preserve_peer_repeats` then restored
the substantial same-position pair because comparable spans can be genuine
re-says, as in Hani 4:4. Removing that safeguard would regress real repeats.
Its purpose was checked in introduction commit `73d57d79` (#625).

The subsequent automatic review also rejected 47:4 because XLSR assigned the
second label probability 0.666, exceeding the 0.5 removal ceiling introduced
in `256d3fa4`. That probability is conditional on forcing two copies into the
recording. It does not establish an independent second utterance. In the
one-word alignment, most of those phonetic frames remain assigned to speech
the candidate retains. The earlier exact-source merge had no reusable corpus
detector to distinguish this from real repetition.

The bounded fix retains the ordinary gate and adds an acoustic-ownership proof
only for an **exact flush same-word merge**. Both scores must improve by
more than 0.001/frame. The retained word must have probability at least 0.5
and start within 250 ms of both forced starts. For a model exceeding the
ordinary removal ceiling, every confident source-pair token must survive on
the identical acoustic frame in the retained word or its immediate neighbours.
The gate recomputes this from raw assignments. No other topology or boundary
edit can use the exception. Peer protection stays intact.

For 47:4, gains are +0.0063306/+0.0052544 per frame. XLSR's maximum unexplained
frame probability is 0.3393, so its confident-label exception passes. MMS's
removed occurrence is 0.3215 and passes the ordinary low-confidence route;
**MMS does not pass the ownership exception itself**. These are separate gates,
not a claim that both models have low unexplained-frame probabilities.

## Coverage and decisions

The predicate is every adjacent equal-position pair, with no exclusion for
correction, repair or hold ownership. Paused pairs are scored as controls and
retained. The remaining six independent voices have zero matching pairs.

| Reciter | Pairs | Repair | Retain | Uncertain |
|---|---:|---:|---:|---:|
| Alafasy | 295 | 16 | 230 | 49 |
| Husary Murattal | 55 | 8 | 37 | 10 |
| Abdul Basit Murattal | 79 | 43 | 16 | 20 |
| Minshawi Murattal | 49 | 1 | 39 | 9 |
| Sudais | 175 | 2 | 155 | 18 |
| Hani | 168 | 1 | 107 | 60 |
| Yasser | 200 | 0 | 200 | 0 |

The public runtime resources were captured twice and their semantic corpus
fingerprints matched. The fingerprint is
`4f610ee6442dad9f6d9ecc64846d520878f0a1873ef7db61e165f51958c2bc25`.
All six runtime revisions, response hashes, audio hashes and model revisions
are in the [provenance](../../tools/timing_evidence/word-split-corpus-provenance.json)
and [complete disposition index](../../tools/timing_evidence/word-split-corpus-review.json).

Source replay covers every candidate verse: pinned source, cleaner, clock
rebase, typed corrections, generated repairs, finalizer and existing holds.
Each accepted correction must produce the **entire exact audited output** after
finalization, not merely contain the same local pair. All 71 complete replays
passed locally and are hash-bound in provenance; CI exercises bounded operation
windows and acoustic proofs without bundling full QF timing rows. Private authenticated snapshots are unavailable;
the legacy replay does not certify authenticated parity.

All **60 open and closed repository issues** were fetched. All 31 timing
patches, including #634 under a different title, parsed without errors. The
[issue inventory](../../tools/timing_evidence/word-split-issue-inventory.json)
cross-references their candidate IDs and occurrence counts. Closed #565 still
has the same duplicate word-9 component in delivered Alafasy 5:57: merging it
improves scores by +0.02734/+0.00945. This review does not approve the report's
other topology or boundary edits. Alafasy 3:86 is another positive example,
with +0.05568/+0.04976 and removed probabilities 0.161/0.117.

## Original five verses

| Verse | Decision | Evidence / limit |
|---|---|---|
| Alafasy 5:2 | Repair the first word-15 phantom only. | The prior actual runs remain bound to the unchanged runtime payload: gains +0.008757/+0.005057; removed probabilities 0.002716/0.015451. The later word 15 and real rewinds survive. New opening/handoff boundaries remain held. This distinct pre-rewind class is not solved by the same-word detector. |
| Alafasy 24:25 | Retain. | Delivered row already matches the patch. Merging genuine word 6 loses 0.103850/0.043875 per frame in the new cohort. |
| Alafasy 47:4 | Repair to one full word-13 span, 16,565–19,000 ms. | Fresh sequence scores and retained-frame proof pass the bounded gate. The maintainer's earlier audition is recorded separately; no agent listening is claimed. |
| Hani 21:46 | Retain. | Removing the genuine word-8 repeat loses 0.013402/0.018898 in the cohort. The earlier boundary audit found speech extending 1,317–1,357 ms beyond the proposed 9,800 ms end. |
| Hani 21:63 | Retain. | Merging genuine word 6 loses 0.112012/0.035545 in the cohort. The −60 ms end-only proposal has unchanged start residuals and remains unproved. |

## Remaining uncertainty

Of the 166 uncertain same-word cases, 91 lack a sufficient dual-model sequence
preference, 43 have confident unexplained speech or insufficient ownership
proof, 21 lack confident support for the retained word, and six need a separate
onset repair. Four pairs in Abdul Basit 5:52 and 10:24 need joint multi-merge
inference. Sudais 59:5 is acoustically mergeable but does not reproduce the
audited candidate through its existing source/correction path; it remains
explicitly unresolved. These cases generate no correction.

The 5:2 pre-rewind problem is a **different class**. A separate reproducible
[structural inventory](../../tools/timing_evidence/rewind-onset-inventory.pending.json)
records 4,337 high-water-before-rewind candidates; 4,313 satisfy the existing
typed operation's predecessor constraint. That inventory is explicitly
incomplete acoustically. It does not authorize deleting other spoken words,
and the earlier audio-vetted 5:2 entry remains an exceptional correction.
The same-word audit must not be represented as resolving that class.

The reviewed runtime source checkpoint is 541039; its deployed normalizer
differs from this checkout. Before any QF publication, replay private
authenticated snapshots, reconcile **every** output delta with its source
profile ledger, and verify the delivered resources. The 71 candidate verdicts
are separate from that ledger. An APK cannot publish QF timing resources.

## Reproduction and regression contract

Follow [WORD_SPLIT_AUDIT.md](../WORD_SPLIT_AUDIT.md). Full actual JSONL is
preserved at `~/qasr/audits/timing-repeat-class-20261016/evidence/xlsr.jsonl`
and `mms.jsonl`, alongside the full private inputs, source traces and complete
row replays. Repository policy keeps full QF timing data private. Bounded
actual inference excerpts are committed as
[XLSR](../../tools/timing_evidence/word-splits-xlsr.excerpts.jsonl.gz) and
[MMS/uroman](../../tools/timing_evidence/word-splits-mms.excerpts.jsonl.gz).
Their scores are for the full sequences; their word arrays contain only the
changed pair and immediate neighbours. Provenance pins full private outputs,
individual original records, and raw/compressed excerpts separately.

CI verifies all 71 generated operations on bounded windows, complete inference
excerpt coverage, exact source guards, model/frame identity, tampered proofs,
extra-boundary rejection and incomplete-review rejection. It preserves the
Alafasy 24:25 and Hani 4:4/21:46/21:63 controls. Four additional patch cases
cover Alafasy 3:86/5:57, Husary 2:236 and Abdul Basit 2:34.

The repository instructions and every new Android Lab export now require the
broader search, source revisions, complete candidate dispositions, analogous
issues and genuine-repeat controls. No exact-verse fixture alone satisfies
that workflow.

Validation passed: 115 timing patch cases, all ten focused Python suites,
all 71 complete private pipeline replays, and 1,511 Android JVM tests with no
failures or skips. The existing flattened-re-say auditor also has a regression
ensuring the later correction phase cannot overwrite its original source input.
