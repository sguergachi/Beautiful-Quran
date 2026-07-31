# Approach review — structure first, clock second

**Verdict:** the lab found the right architectural seam, but not yet a
99%-quality structure engine. Build a canonical repeat-grammar decoder over
free CTC emissions, then clock the chosen occurrence sequence. Do not delete
`timing_repairs/` yet.

The strongest finding is negative: forced-alignment path score is the wrong
signal for repetition structure. Alafasy 3:21 is decisive: the wrong collapsed
template scores -0.73 and the correct repeated template scores -0.88. More
forced-align hypotheses will not fix that objective.

The positive result is promising but overstated. Decode-string matching is
7/11 exact because it gets all four no-repeat cases and only 3/7 repeat-positive
cases exactly; 5:44 is partial. That is 43% exact on the defect class that
matters. It proves that free decoding contains useful repeat evidence. It does
not prove production readiness.

## A. Diagnosis

### There are four contracts, not one timing problem

| Contract | Output | Failure symptom | Correct owner |
|---|---|---|---|
| Structure | Canonical position occurrence sequence, e.g. `1,2,3,1,2,3,4` | Highlight jumps to the wrong written word or misses a re-say | Free acoustic decode constrained back to canonical positions |
| Acoustic clock | Start time, and optionally spoken end, for each occurrence | Correct word, wrong moment | Fixed-sequence aligner |
| Display clock | Karaoke hold end / wash pacing | Flicker, swallowed silence, bad visual pacing | Deterministic post-processing |
| Output latency | Device-route offset | Whole ayah looks uniformly early/late on a speaker or Bluetooth route | Runtime route calibration |

Do not repair one contract with another:

- A database offset must not absorb Bluetooth latency.
- A forced-align path score must not decide whether a repeat exists.
- A karaoke hold end must not be reported as an acoustically accurate word
  offset. If `endMs = next startMs`, it is display policy, not speech truth.

### What drives the repair pile

The `timing_repairs/` rules are mostly compensating for a representation error:
one pipeline tries to infer both occurrence order and time from a one-pass
forced alignment.

- QDC contains real repeats, false same-word splits, shifted repeat spans, and
  malformed/missing positions.
- CTC forced alignment collapses real re-says because one long allocation can
  have a better path score than two occurrences.
- Free CTC output drops or mangles words; aligning its leftovers directly to
  positions produces short-word phantoms.
- The repair layer then needs `dephantom`, span protection, per-position
  unsplitting, and shifted-span realignment to arbitrate those incompatible
  errors.

Those rules are not independent domain facts. They are symptoms of using a
clocking objective as a structure classifier.

### What the current experiments establish

They establish:

1. Free Arabic CTC decode preserves some re-says that forced alignment erases.
2. Canonical unique-span matching avoids the known false positives in 2:8,
   4:157, and the two muqatta'at cases.
3. Arabic wav2vec CTC is a credible clock candidate when the position sequence
   is fixed.
4. MMS is useful as an independent acoustic witness.

They do **not** establish:

1. **99% structure accuracy.** The structure gold has 11 selected cases, only
   four negatives, and is derived from shipped post-repair data rather than an
   independent blinded annotation.
2. **99% timing accuracy.** The 108-pair clock bake-off is explicitly ear-free.
   Word-count and monotonicity are mostly guaranteed by construction.
   `_ctc_token_spans()` even pads missing target spans at the last frame, so a
   count match can conceal alignment failure.
3. **Independent onset quality.** The winning post-process snaps starts to
   energy onsets, while the composite rewards energy rise at those starts.
   That metric is useful as a sanity check but circular as evidence that the
   snapped boundary is the correct word boundary.
4. **Absolute accuracy from pad recovery.** A 0 ms pad residual proves time
   translation consistency, not that the unpadded boundary is correct.
5. **That all four remaining structure misses are only word-form failures.**
   That diagnosis is plausible, but the evaluation JSON does not retain the
   raw decode, token frames, alternatives, or model revision needed to audit
   it.

`reclock_eval.json` is also a warning, not a validation. Against shipped
timestamps, the current fixed-sequence clock ranges from excellent medians on
some cases to p90 errors of several hundred milliseconds, a ~605 ms median
shift on 3:21, and >1 s first-word error on Husary 2:1. Shipped timestamps are
not gold, and 3:21 even has a negative shipped start, so this does not condemn
CTC. It does show that “solid” currently means promising, not measured to the
product bar.

### The current decoder is a probe, not the final structure model

`decode_structure.py` has important coverage limits:

- It can insert exactly one repeat episode.
- The episode is inserted immediately after its first occurrence.
- Span length is capped at four words.
- Matching is an exact substring count over one normalized character string,
  with one single-word truncation exception.
- It discards token frame times and all decode alternatives.
- “Unique in ayah” is computed on concatenated characters, so matches can cross
  canonical word boundaries.
- A no-hit result has no calibrated confidence.

The shipped database shows why that matters. Treating its position lists only
as a topology inventory, not as truth:

- 3,079 ayah-reciter rows contain structurally valid repeat episodes.
- 313 of those contain two or more repeat episodes.
- 855 repeat episodes are longer than four words; the maximum is 21.
- 169 rows do not even fit “canonical first pass plus contiguous re-says” and
  include skipped or badly ordered positions.

The 11-case suite does not exercise that production shape.

There is also no safe rule saying “no decode hit means use QDC.” On 4:169 that
fallback preserves a real span the decode missed. On 2:8 the same fallback
preserves QDC's false split. The architecture needs candidate comparison and
calibrated abstention, not a source-precedence slogan.

## B. Recommended primary architecture

### Canonical repeat-grammar decoder, then occurrence clock

```text
audio
  -> one CTC forward pass
  -> free decoded emissions WITH frame intervals and N-best alternatives
                                      |
Uthmani words -> matching normalization -> canonical repeat grammar
raw QDC positions ---------------------> optional candidate / prior
                                      |
                                      v
                        canonical position sequence
                        + confidence + coarse anchors
                                      |
                                      v
                    fixed-sequence, anchor-bounded clock
                                      |
                                      v
                 onsetMs + holdEndMs -> validation -> DB
```

The only text that can reach the database is the canonical Uthmani word list.
Decoded text is disposable acoustic evidence.

### 1. Structure: align free decode to a small canonical grammar

The grammar should encode the actual allowed topology:

1. The base path covers positions `1..N` in order exactly once.
2. Between base positions, zero or more contiguous spans of already-covered
   positions may be re-emitted.
3. The grammar supports multiple episodes and long spans. Do not hard-code
   four words or one episode.
4. Any topology outside this grammar is rejected until an ear-verified case
   proves the grammar incomplete.

Use character edit distance or a similarly simple decode-string likelihood to
align free decoded emissions to that grammar. This is deliberately **not**
forced-align path score. A single global repeat prior, calibrated on held-out
data, is acceptable; token lists and case-shaped exceptions are not.

Candidate sources should be:

- the monotonic canonical path;
- top paths from the canonical repeat grammar;
- raw QDC structure after schema validation, as one candidate or prior;
- optionally the corresponding top paths from a second CTC decoder.

QDC is useful weak supervision. It is not a fallback oracle. The decoder should
be able to select mono over a false QDC split and a shifted decode span over a
mis-positioned QDC span.

Keep the CTC emission frame intervals. Once decoded characters are aligned to
canonical occurrences, they provide coarse time anchors for each repeat
episode almost for free.

### 2. Clock: align the fixed occurrences inside acoustic anchors

Given the selected position sequence:

1. Partition at repeat-episode anchors obtained from the timed free decode.
2. Force-align the exact canonical characters inside each anchored interval.
3. Aggregate character starts to occurrence `startMs`.
4. Apply lead-in, narrow onset refinement, and trailing trim only if each
   ablation wins against independent onset gold.
5. Derive `holdEndMs` as display policy after the acoustic onsets are fixed.

Do not use `reclock_by_runs()` as the production fallback in its current form.
It divides audio by canonical letter weight; repeated runs can be delivered at
very different rates, so proportional slicing can place the correct words in
the wrong acoustic interval.

Do not silently ship the current equal-letter fallback either. For this
product, fabricated complete timings are worse than no highlight. Alignment
failure should retain the last known-good row during migration, or fail the
new-reciter quality gate.

### 3. Confidence and validation are part of the architecture

Each generated row should retain build-time diagnostics:

- model and processor revision;
- exact audio hash and duration;
- normalized matching text hash;
- selected position sequence and runner-up;
- decode/grammar score margin;
- QDC agreement;
- cross-model agreement when available;
- forced-align coverage without padded/fabricated spans.

Hard invariants:

- every output position is in `1..N`;
- base coverage is exactly `1..N` in order;
- every extra occurrence is grammar-valid;
- starts are strictly ordered and inside the audio;
- no target character span was synthesized to make counts pass;
- every repeat occurrence receives a distinct, positive-duration interval.

For the first production rollout, low-confidence rows keep the current shipped
timings. That is an automated migration fallback, not a permanent structure
source. For a new reciter with no trusted baseline, low-confidence coverage
must fail the release gate. Shipping guesses is not “fully automated quality.”

### 4. Define the 99% claim before optimizing it

Report separate metrics:

- **Structure exactness:** exact occurrence sequence per ayah.
- **Repeat event precision/recall:** episode start, end, and multiplicity.
- **Onset accuracy:** percentage of word occurrences within a declared
  tolerance.
- **Tail risk:** p90 and p99 onset error.
- **Route sync:** measured separately after output-latency compensation.

A defensible initial product bar is:

- >=99% exact position sequences on a representative random set;
- >=99% repeat-event precision and recall on a repeat-enriched challenge set;
- median onset error <=25 ms;
- p90 <=60 ms;
- >=99% of onsets within 100 ms;
- zero collapsed or swapped repeat occurrences.

“99% of words exist in the output” is not an accuracy metric.

## C. Alternatives ranked for this defect class

The defect class is repetition structure, not ordinary monotonic clocking.

| Rank | Approach | Fit | Verdict |
|---:|---|---|---|
| 1 | Free CTC emissions -> canonical repeat-grammar alignment; QDC as candidate/prior | Uses the signal that exposes re-says, always returns canonical positions, supports general repeat topology | Build this |
| 2 | Same grammar with two independent CTC decoders / N-best consensus | Best path to confidence calibration and recovery from model-specific word-form misses | Add after the one-model decoder works |
| 3 | QDC primary plus current surgical repairs | Best current production safety; 11/11 on the selected suite | Keep as migration baseline, not destination |
| 4 | Quran-domain CTC fine-tuning for free decode | Likely fixes the remaining word-form class, but only after labels and decoder architecture exist | Train later; it is an acoustic upgrade, not the architecture |
| 5 | Full CTC lattice or WFST repeat graph | More principled uncertainty handling than greedy decode, but materially more code and still needs a calibrated repeat prior | Use only if timed greedy/N-best graph alignment plateaus |
| 6 | MFA / phone alignment | Potentially excellent clock and sub-word boundaries after structure is known | Wrong tool for deciding repeats; later L3 work |
| 7 | WhisperX or Quran seq2seq transcription | Language-model normalization tends to erase re-says | Do not use for structure; timestamps-only clock control at most |
| 8 | Forced-align path-score multi-hypothesis | Empirically prefers the wrong collapsed structure on 3:21 and scores 4/11 | Stop investing |

MMS is currently most valuable as a second clock/structure witness, not as a
median boundary generator. Median ensembles can smear a good boundary.

## D. Next three experiments

### Experiment 1 — independent gold and honest metrics

**Hypothesis:** the current rankings and confidence thresholds change when
measured against independent occurrence and onset labels.

**Method:**

1. Create a representative random structure set of at least 300
   ayah-reciter rows, plus a separately reported challenge set containing the
   11 current cases, repair disagreements, long repeats, multiple episodes,
   muqatta'at, and malformed QDC rows.
2. Ear-label only the occurrence sequence for the structure set. This is an
   evaluation asset, not a production patch workflow.
3. On a 50-ayah / >=1,000-occurrence subset, label word onsets in Timings Lab.
   Double-label at least 10% and adjudicate disagreements over 30 ms.
4. Store audio hashes, annotations, and fixed train/tune/test splits.
5. Replace the composite winner claim with exact structure, repeat
   precision/recall, median/p90/p99 onset error, and tolerance hit rates.

**Success metric:** reproducible gold with <=25 ms median annotator
disagreement; no tuning on the final test split. To claim a 1% error rate with
useful confidence, 11 selected cases are nowhere near enough: even zero errors
in roughly 300 independent samples only puts the usual 95% upper error bound
near 1%.

**Effort:** medium, 2-4 days including annotation.

### Experiment 2 — general repeat-grammar decoder

**Hypothesis:** timed free-decode edit evidence can choose mono, QDC, and
decode-derived repeat candidates without token-specific repair rules.

**Method:**

1. Change greedy decode to retain each collapsed token's frame interval and
   preserve several alternatives if cheap.
2. Implement the base-plus-contiguous-re-says grammar with dynamic programming
   or a narrow beam. Support multiple episodes and spans longer than four.
3. Score canonical candidates against the free decode; include validated raw
   QDC as a candidate, never as unconditional fallback.
4. Tune one repeat prior and one abstention margin on the tune split.
5. Save the raw decode, selected path, runner-up, and score breakdown for every
   evaluation row.
6. Run against independent gold and the existing repair corpus. Report the
   repair corpus as a challenge set, not ground truth.

**Success metric:** 11/11 on the current suite; >=99% exact structure on the
representative test set; >=99% repeat-event precision and recall on the
repeat-enriched set; zero special-case word lists; full coverage of the
observed multi-episode and >4-word topology.

**Effort:** medium, 2-4 days for a lab prototype.

### Experiment 3 — clock ablation on fixed, gold structure

**Hypothesis:** anchor-bounded Arabic CTC alignment beats global alignment,
proportional run slicing, and MMS on true occurrence onsets, especially around
re-says.

**Method:**

Compare on the onset-gold subset:

1. global Arabic CTC forced alignment of the exact occurrence sequence;
2. Arabic CTC alignment bounded by timed decode/repeat anchors;
3. current proportional `reclock_by_runs`;
4. MMS fixed-sequence alignment;
5. lead-in / onset-40 / trailing-trim as separate ablations.

Reject any run that pads missing character spans. Score first-word onset,
ordinary boundaries, both copies of repeated spans, final spoken end, and
display hold end separately.

**Success metric:** median onset error <=25 ms, p90 <=60 ms, >=99% within
100 ms, and no repeat occurrence collapsed or assigned to the wrong interval.
The onset-40 post-process stays only if it improves held-out gold rather than
just energy-rise score.

**Effort:** small-to-medium, 1-2 days after Experiment 1 supplies labels.

## E. Risks and false confidence

1. **Selected-case inflation.** 7/11 sounds close to solved; 3/7 exact on
   repeat-positive cases does not.
2. **Circular gold.** Post-repair QDC is a regression oracle for current
   behavior, not independent acoustic truth.
3. **Class imbalance.** Most words and ayahs are monotonic. A system can exceed
   99% word-level accuracy while still failing a large fraction of repeats.
4. **Unauditable decode failures.** Current eval output omits the evidence
   needed to verify the claimed word-form cause.
5. **Forced completeness.** Padding missing CTC target spans and equal-split
   fallbacks make coverage look perfect while hiding failure.
6. **QDC ambiguity.** The same no-hit state occurs when QDC is right and when
   QDC is wrong. Source precedence cannot solve it.
7. **Matcher coverage.** One immediate <=4-word repeat does not resemble the
   full shipped topology.
8. **Normalization collisions.** Removing Uthmani marks, hamza distinctions,
   and word boundaries can make different canonical spans identical. Matching
   normalization is necessary, but ambiguity must be surfaced in confidence.
9. **Clock collapse after correct structure.** Fixing the target sequence does
   not guarantee the two repeated copies receive the right durations. The clock
   needs acoustic episode anchors.
10. **Metric gaming.** Monotonicity, word count, energy rise, baseline distance,
    and pad consistency are sanity metrics. None alone measures word identity at
    a millisecond.
11. **Audio identity.** QDC offsets, everyayah files, and evaluated MP3s must be
    byte-identical or explicitly rebased. Encoder delay or a replaced file can
    look like model drift.
12. **Latency ceiling.** Perfect database onsets cannot yield total perceived
    sync if route latency presets are wrong for a device. Test data quality and
    route compensation separately.
13. **Model reproducibility.** Pin weights, tokenizer, normalization, Torch,
    and audio decoder versions. A mutable Hugging Face model ID is not a
    reproducible build input.
14. **“Fully automated” as permission to guess.** Automation should generate
    and validate everything. It should not force a low-confidence new reciter
    through the release gate.

## F. Decision

### Build first this week

1. Build the independent structure/onset evaluation artifact and replace the
   permissive test gate. The current gate passes at mean repeat recall >=50%;
   that is a research smoke test, not a product gate.
2. Prototype the timed free-decode -> canonical repeat-grammar decoder in
   `sync_lab/`. Reuse one CTC forward pass and keep raw evidence in results.
3. Run the fixed-structure clock ablation against real onset labels.
4. Keep current DB generation and `timing_repairs/` unchanged while the new
   path runs in shadow.

### Build after those gates pass

1. Add Arabic-CTC/MMS or Arabic-CTC/domain-CTC disagreement calibration.
2. Run all reciters, inspect aggregate confidence and topology distributions,
   and compare every changed row against the frozen production baseline.
3. Integrate one structure artifact and one clock artifact into
   `build_db.py`.
4. Remove `timing_repairs/` in one deliberate cutover, regenerate
   `quran.db`, and bump `QuranDatabase.DB_FILE_NAME`.

### Later

- Fine-tune a Quran-recitation CTC decoder using the error corpus if word-form
  misses remain the dominant tail.
- Explore MFA or a Hafs phone lexicon for sub-word wash timing only after word
  structure and onset targets are met.
- Evaluate character/grapheme keyframes after the word clock is trustworthy.

Do not spend this week on more forced-align structure hypotheses, Whisper
transcription, MFA infrastructure, or deleting repair files. The thin path is:
**free acoustic evidence -> canonical repeat grammar -> fixed occurrence
clock -> independent gate**.
