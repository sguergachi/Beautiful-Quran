# Outstanding pipeline drift

Rows the pipeline produces but the shipped database does not carry, because no
verdict has reviewed them yet. The fail-closed delta gate withholds each of
them; this file keeps that from being silent. Remove an entry when its rows ship
with a verdict, are held with their model scores, or its cause is reverted.

## Merged fragment leads (2026-10-23)

The [complete delivered-source review](../../docs/quality-reviews/TIMING_FRAGMENT_LEADS_2026-10-23.md)
inspected 193 eligible source fragment pairs across the six authenticated
voices, within an 81,059-row delivered corpus. Eight qualify for the new
stolen-tail detector; all eight and four genuine-repeat/older-lead controls
have both acoustic witnesses. Five candidates pass; three remain at baseline:
Abdul Basit 34:27, Sudais 4:104 and Sudais 21:28. Their source-derived starts
improve both row means but remain 264–332 ms from an acoustic witness, beyond
this review's 250 ms onset limit. Do not silently promote those partial
boundary improvements. Their exact candidates and model metrics are held in
`qf-source-transition.json` and `../timing_evidence/fragment-lead-review.json`.

The legacy source scan found twelve qualifying rows. Four extra legacy
examples (Alafasy 4:171, 5:5, 7:117, 33:51) are absent from the authenticated
candidate cohort; that old-source replay is not a publication verdict.
Other non-duplicate boundary residuals in Alafasy 64:11 (positions 4, 9, 10)
are outside this detector and remain unchanged. Forced-label residuals alone
do not establish an exact spoken onset; these require their own waveform and
class review. No general timing-accuracy claim follows from this cohort.

## Audio-vetted rewind onset (#873)

The typed Alafasy 5:2 correction removes only the unspoken word 15 at
23,340–24,040 ms and folds that interval into word 14. Its candidate
now has an exact authenticated replay and refreshed dual-model evidence in
`qf-source-transition.json`; see the
[October 17 promotion report](../../docs/quality-reviews/TIMING_RUNTIME_PROMOTION_2026-10-17.md).
The returning word-11 boundary at 24,070 ms also remains unvalidated: both
models force it around 25,146–25,166 ms. No clock offset is inferred.

The later Pixel 10 5:2 report also shortens word 1 to 2,609 ms, advances word 2
to 3,072 ms, and moves the word-15/16 handoff to 33,660 ms. Fresh XLSR/MMS
inference rejects the opening edit: forced word-1 ends are 3,281/3,241 ms and
word 2's start error increases from 41 to 269 ms. Word 16 improves by 750 ms
but remains 1,933 ms late on both models. Keep these boundaries held, preserve
the existing topology-only fix, and review the exact word-16 onset separately.
The held proposal and full regression are recorded in
`../timing_evidence/device-lab-alafasy-5-2-boundaries.verdict.json`; no runtime
publication is implied.

The maintainer subsequently auditioned Alafasy 47:4 and reported that word 13,
مَنّا, must not highlight twice. An exact-source correction now merges it to
16,565–19,000 ms after repairs, preserving every other segment. Both models
prefer one word. The subsequent corpus audit now proves retained acoustic
ownership for XLSR's confident source label; the bounded same-word gate accepts
the exact candidate. `maintainer-word-split-class.json` records that evidence
and keeps the human report separate. The authenticated replay also moves
words 7–9, whose previous boundary review regresses. The combined candidate remains held; the merge proof cannot
approve those other boundary edits. Alafasy 24:25 and Hani 21:46/21:63 need no edit.

## Generated held-word merges: four authenticated candidates remain held

The [complete same-word audit](../../docs/quality-reviews/TIMING_WORD_SPLIT_CORPUS_2026-10-17.md)
ran actual XLSR/MMS inference for all 1,021 adjacent equal-position pairs in
81,059 delivered rows. It generates 71 accepted merges across the six reviewed
QF voices, including 47:4 and the duplicate component of closed #565 (5:57).
Every generated operation reproduces its complete audited output after the
finalizer. The 784 retained and 166 uncertain cases remain unchanged.

`../timing_evidence/word-split-corpus-candidates.verdict.json` binds the actual
runtime baseline/candidate hashes, audio and both model proofs. The October 17
authenticated replay promotes 67 exact candidates into
`qf-source-transition.json`. Four remain at baseline: Alafasy 3:86 has a
different tail cutoff, 7:113 and 11:58 no longer contain the reviewed pairs,
and 47:4 also moves previously rejected boundaries. The three stale merge
operations retire only on their exact authenticated input hashes; their
existing whole-row holds remain. No bundled database asset changed.

The separate high-water-before-rewind inventory has 4,337 candidates, 4,313
compatible with the typed pre-rewind operation. Its acoustic coverage is
explicitly incomplete: 5:2 and the new 6:145 report have exact dual-model
verdicts. The inverse `one_utterance` audit covers all three existing operations
(repair 6:145, retain the legacy 2:229/2:235 controls and authenticated holds).
Those two reviewed rewind examples do not authorize class-wide drops;
the same-word detector must not be presented as fixing that distinct class.

## Lagged qdc peer pairs (#856)

`lagged_peer_positions` stops `preserve_peer_repeats` restoring a same-word
pair the repair shows to be qdc labels running one word late before a skip.
The bundled database is unchanged. A replay of public qdc plus
`tools/timing_repairs/` changes four QF runtime rows, all from a fake pair
to the repair's topology:

| Row | Before | After |
|---|---|---|
| Alafasy 24:25 | `1,1,2,3,4,5,6,6…` | `1,2,3,4,5,6,6…` (matches the #856 Lab edit) |
| Sudais 59:5 | `…5,6,6,7,8…` | `…5,6,7,8…` (real `10,11,10,11` kept) |
| Abdul Basit 33:6 | `15,15,28,29` | complete 1–29 |
| Abdul Basit 35:10 | `5,5,15,…` | complete 1–24 |

They need dual-model verdicts from a `--qf-snapshots` build before
`sync_qf_timings.py --publish`; until then the delta gate keeps the reviewed
rows. Note for the reviewer: `qf-source-transition.json` held an earlier
24:25 candidate because both models still heard word 1 in the removed
occurrence (0.83 / 0.75). In the new row that audio stays on word 1 (the
repair runs يَوۡمَئِذٖ to 2020 ms), so a high word-1 probability there is
expected, not evidence of a re-say.

**Runtime observation, 2026-10-16:** the public Alafasy runtime resource already
matches the 24:25 edit in #856/#873, including the retained word-6 repeat.
The deployed normalizer fingerprint differs from this checkout, so the table
above describes the reproducible local pipeline delta, not proof that the
service still carries the old row. Reconcile publication provenance and the
ledger during the next authenticated replay; do not reapply the Lab row or
remove another occurrence. See the
[#873 review](../../docs/quality-reviews/TIMING_PATCH_873_2026-10-16.md).

## Resolved: Sudais re-clock after #712 (quran-v64)

c4e17836 (#712) taught `parse_alignment_payload` to read Sudais' quran-align
file past its `Crashed Command …` prefix. Sudais had never had an alignment
reference, so every row used to abstain from `rebase_qdc_clock`; a refresh then
re-clocked 6,157 rows, completed six withheld ones and changed four
topologies, and 14 Alafasy/Hani boundaries had drifted since #625. None of it
had shipped.

Every row was judged against fresh Arabic XLSR and MMS/uroman forced alignment
of its EveryAyah file (`v64-pipeline-resync.json`):

- **6,099 timestamp rows ship** (6,095 Sudais, 4 Alafasy/Hani): mean absolute
  word-start residual no worse on both models. Across the accepted Sudais rows
  it falls from 151 to 96 ms (XLSR) and from 143 to 90 ms (MMS).
- **4 Sudais topology rows ship**: both models score the new occurrence
  sequence better, and its start residual falls.
- **2 withheld Sudais rows ship** (2:25, 7:5): both models place them inside
  the 95th percentile of the accepted Sudais rows.
- **54 rows are held** in `tools/timing_holds/audit-held-rows.json`: 40 Sudais
  and 10 Alafasy/Hani rows where either model saw the starts move away — among
  them the short muqatta'at openings (42:2, 37:1, 36:22) whose clock offset
  rests on too few boundaries — and 4 completed Sudais rows (2:198, 2:223,
  13:37, 73:4) that stay withheld.
