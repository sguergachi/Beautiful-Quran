# Word timing from first principles — handoff doc

**Status:** research + developer V2 calibration lane; not production cutover
**Owner goal:** clean dataset from **audio + Uthmani text only** — no permanent manual patches, no growing systematic repair rules; accept **~1% residual** error (flags), not a patch farm.  
**Last lab run:** 2026-07-26/27 (RTX 3080)

This is the **single handoff document**. Other files under `tools/sync_lab/` are evidence and code; treat this doc as the source of plan and decisions.

---

## 1. Product law

Beautiful Quran’s core is **word ↔ recitation lock**: each Arabic word lights in total sync with the reciter. Timing quality is the product, not a feature.

- Runtime stays offline: pure `HighlightEngine` over bundled word spans plus
  optional occurrence-specific acoustic keyframes.
- **Never** rewrite sacred Arabic text with ASR output.
- Alignment is **build-time only**.
- Ink karaoke fidelity (soft wash) is separate and non-negotiable; better timings feed it.

Related product docs: `docs/SYNC_FIDELITY.md`, `docs/HIGHLIGHT_ENGINE.md`, `AGENTS.md` invariant #8.

---

## 2. What production does today

```
everyayah audio (streamed)
        │
build:  tools/build_db.py
        ├── quran-align (one-pass Sphinx) for some reciters
        ├── quran.com QDC segments (repeat-aware) for QDC_REPEAT_RECITERS
        ├── tools/timing_repairs/*.json   ← automated structural “rule pile”
        └── tools/timing_overrides/*.json ← manual Lab patches (currently EMPTY)
                │
                ▼
        data/quran.db → app / web
```

| Term | Meaning |
|---|---|
| **QDC** | Quran.com word timing segments; can encode re-says via position backtracks |
| **quran-align** | One-pass open timings; cannot encode re-says |
| **timing_repairs** | ~1,185 auto edits across 6 reciters (unsplit / drop / restore / …) from external `~/qasr` CTC arbitration |
| **timing_overrides** | Manual Timings Lab patches; **folder empty now** — historical patches deleted after repairs claimed to cover them |

**User intent:** stop depending on repairs + manual patches; regenerate timings cleanly from audio.

---

## 3. What we learned (empirical)

### 3.1 Forced-align path score is the wrong structure signal

CTC forced alignment **prefers collapsing re-says** into one long word span.

Example (Alafasy 3:21 second chunk):

| Template | Path score |
|---|---:|
| `(16,17,18)` wrong collapsed | **−0.73** (wins) |
| `(16,16,17,18)` correct re-say | −0.88 (loses) |

**Do not** invest in path-score multi-hypothesis for “is this a repeat?”

### 3.2 Free CTC decode preserves re-say evidence

Greedy letter decode often still **emits the phrase twice**. Matching unique spans of the canonical word list against that decode string can recover real re-says **without** FA path score.

### 3.3 Hard-suite structure (11 repair-regression cases)

Gold = post-repair shipped DB structure on famous defect cases.

| Method | Exact | On **repeat-positive** (7) | Clean FP (4) |
|---|---:|---:|---:|
| mono 1..N | 4/11 | 0/7 | 0 |
| free-decode / grammar **audio-only** | 7/11 | **3/7 (~43%)** | 0 |
| grammar + QDC as **scored candidate** | 11/11* | 7/7* | 0 |

\*Circular if gold *is* QDC+repairs; useful as arbitration demo, not as 99% proof.

### 3.4 Historical overrides are regression evidence, not gold

Recovered from git all unique `timing_overrides` edits → **409 ayahs**  
(`tools/sync_lab/historical_manual_patches.json`).

The audit on 2026-07-26 found that the corpus is mixed provenance:

| Slice | Count | Status |
|---|---:|---|
| Grammar-valid mono | 295 | Useful regression evidence |
| Grammar-valid repeat | 74 | Useful repeat challenge evidence |
| Grammar-incompatible | **40** | Quarantine pending ear adjudication |

All 40 incompatible rows come from the bulk `alafasy-split-merges` import.
Twenty-four skip canonical positions; sixteen also contain jumps/backtracks
that cannot satisfy the decoder invariant that the first pass covers `1..N`.
Therefore exact match on all 409 has a hard ceiling of **369/409 = 90.2%** for
this architecture. The earlier “≥99% on all 409” gate was impossible.

**Eval after audit (audio-only, no repairs):**

| Method | Valid 369 exact | Valid mono 295 | Valid repeat 74 |
|---|---:|---:|---:|
| mono | 295 (79.9%) | **295 (100%)** | 0 |
| **audio_only** | **211 (57.2%)** | 201 (68.1%) | **10 (13.5%)** |

The failure is not a margin-tuning problem:

- Raising the margin enough to retain 294/295 valid mono rows recovers only
  **1/74** repeat rows.
- Even when the correct repeat sequence is injected as an oracle candidate,
  flattened decode edit similarity prefers it over mono on only **59/74**.
- Exact-only substring evidence reduces some false positives but does not
  separate true from false repeats.

**Conclusion:** current audio-only is **not shippable**, and the flattened
decode-string scorer is a negative result. Keep the decode model as acoustic
evidence; replace the structure objective with time-localized episode scoring.

### 3.5 Clock (ms)

- Force-aligning a **fixed** position sequence on everyayah audio works and is **pad-stable** (~0 ms pad residual).
- Comparing reclock to QDC/shipped ms is **not** ear truth (different clocks; med ~130 ms Δ vs shipped on hard set).
- Prefer **global fixed-sequence FA** over letter-weight run slicing until true episode anchors exist.
- Product bar (med ≤25 ms, p90 ≤60 ms, ≥99% within 100 ms) needs **ear-labeled onsets** — not done.

### 3.6 Structure scoring verdict

Global edit similarity loses where in time a phrase was emitted. False
substrings and real re-says have overlapping score gains, so no scalar margin
can supply both near-zero false repeats and high recall. The next scorer must
retain CTC frame times/log-probabilities and compare a repeat episode against
the corresponding local audio interval.

### 3.7 QDC role

| Wrong | Right |
|---|---|
| Unconditional fallback (“no decode hit → QDC”) | Preserves both real and false QDC |
| Delete QDC day one | Lose weak supervision early |
| **QDC as scored candidate** among mono + audio inserts | Clean arbitration; can override QDC when audio is clearer |

Long-term: drop QDC only after the time-localized decoder clears independent
ear-labeled gates.

### 3.8 V2 clock-convention regression run (2026-07-27)

`tools/sync_lab/eval_v2_onsets.py` ran the current mono CTC V2 generator over
all 369 grammar-valid historical edits. These are structure regression
evidence, not onset gold: 398/409 recovered rows start word 1 at zero and
318/409 are wholly contiguous. Most came from machine-merging QDC topology,
so a V2 acoustic onset is being compared with the prior word's machine end.
The absolute numbers are **clock-convention distance**, not accuracy:

| Metric | Current V2 |
|---|---:|
| Row coverage at provisional path score ≥ −1.0 | 89.7% |
| End-to-end exact structure | 71.8% |
| Accepted-row exact structure | 80.1% |
| Word-onset median absolute error | 93 ms |
| Word-onset p90 absolute error | 742 ms |
| Word onsets within 100 ms, structure-exact rows | 54.3% |
| Word onsets within 100 ms, end to end | 36.0% |

Re-analysis keeps the sign and removes each row's median clock offset. On 265
threshold-accepted, structure-exact rows, the interior-word residual is 36 ms
median, 594 ms p90, and 78.7% within 100 ms (Alafasy: 37 ms / 81.2%; Hani:
10.5 ms / 60.7%). The convention bias is real, and so are catastrophic
within-row failures. All 74 repeat rows still fail exact structure because the
bundled slice is a mono `1..N` generator. Path score does not separate the
outliers.

Widening the energy-onset refinement on 50 Alafasy mono rows leaves the
absolute QDC-convention delta flat (100–102 ms median, 654–658 ms p90). Stop
tuning that window against this corpus; only independent acoustic-onset labels
can rank it.

A second-witness experiment on the same 50 rows compared the Arabic CTC model
with `MahmoudAshraf/mms-300m-1130-forced-aligner` (research only; production
licensing not approved). Raw onset agreement is 115 ms median / 423 ms p90,
with only 8.3% within 30 ms. Removing each row's signed offset improves the
interior-word residual to 35 ms median / 317 ms p90 and 45.8% within 30 ms —
still far below the 70% coverage gate proposed for disagreement-based
acceptance. Two-model agreement is not yet a usable confidence gate.

The next clock experiment must preserve these frozen diagnostics and compare
on the same rows. The independent label format and audio-hash gate live under
`tools/sync_lab/independent_labels/`.

### 3.9 Quran-phoneme and QUA evidence (2026-07-27)

The pinned Quran-specific `muaalem-model-v3_2` phoneme head exactly
free-decodes all seven Fātiḥah ayahs. Full-ayah forced alignment still drifts
on long verses. Re-aligning phonemes inside general-Arabic CTC word windows
raises low-PER model agreement to 98.94% within 100 ms, but only at 38.2% word
coverage. That is useful evidence, not a 99% gate.

Qur'anic Universal Audio release `v2.3.0` adds a stronger deterministic lane:

- 6,236 Alafasy rows, 80,135 word occurrences, 350,603 timed letters;
- 836 rows contain 2,706 repeat occurrences;
- 6 rows fail the app's complete first-pass position grammar;
- QUA QPC text maps strictly to the app's rendered base slots on 99.84% of
  rows; the rest abstain instead of using a text exception;
- decoded waveform matching proved 166/313 cached EveryAyah files are the
  same take. Other files are different recordings and must not inherit the
  QUA clock.

For accepted same-take rows, the source chapter clock transfers automatically
through normalized cross-correlation with a unique-peak gate. This removes
manual patches and preserves QUA repeat topology plus letter intervals. It
does **not** prove QUA's forced-aligned letter labels are human-correct, and
QUA cannot validate a row generated from QUA. A frozen human audit remains
the final accuracy authority.

---

## 4. Target architecture (first principles)

```text
BUILD-TIME only
──────────────
everyayah MP3 + canonical words[1..N]
        │
        ├─► CTC emissions with frame times/log-probabilities
        ├─► constrained graph/beam:
        │     • monotonic canonical path 1..N
        │     • contiguous backtrack episodes
        │     • optional QDC / second-model episode proposals
        ├─► score repeat-vs-mono on the local episode interval
        ├─► calibrate confidence on held-out ear labels
        ├─► uncertain or model-disagreeing rows → FLAG
        ├─► CLOCK: force-align chosen positions on same audio → start_ms
        ├─► holdEndMs = next start (display policy)
        └─► validate invariants → quran.db row  |  flag queue

RUNTIME: HighlightEngine + OutputLatency + optional keyframe-paced ink curve
```

**Invariants for a shipped row**

- Positions grammar-valid: first pass covers `1..N` in order; extras are re-emissions of already-covered spans.
- Starts ordered, inside audio duration.
- No fabricated “pad missing CTC spans” accepted as high-confidence.
- Model/processor/audio hash recorded in build diagnostics.

**What we delete after gates pass**

- Permanent `timing_overrides` workflow as product pipeline  
- Growing `timing_repairs` heuristic generator as structure truth  

**What residual 1% looks like**

- Flagged ayahs (no highlight or keep previous known-good until next model), rare Lab for eval — **not** new if/else per bug class.

---

## 5. Definition of done (~99% / ~1% residual)

Measure **separately** (never one composite score):

| Metric | Bar | Against |
|---|---|---|
| Structure exact on **random** sample (≥300 ayah×reciter) | ≥99% | Independent ear labels (not circular DB) |
| Repeat-event P/R on **hard/re-say** set | ≥99% | Ear / challenge set |
| Clean FP (false new backtracks) on non-repeat sample | ~0 | Ear / mono truth |
| Onset med / p90 | ≤25 / ≤60 ms (or agreed bar) | Ear Lab taps |
| Onsets within 100 ms | ≥99% | Ear Lab taps |
| Flag rate | Documented; Lab residual only | Full reciter shadow run |
| Historical override compatibility | Report valid 369 separately; never train on test | Regression evidence after adjudication |

Report **accuracy and coverage separately**: a 99% accurate accepted subset
with 40% coverage is not a 99%-accurate full-Quran generator.

**Do not claim 99% from:** 11 circular cases, the unadjudicated 409 overrides,
energy-rise composite, pad recovery alone, or “word count match.”

---

## 6. Roadmap

### Phase 0 — harness (done)

- [x] Lab code under `tools/sync_lab/`
- [x] Historical patch inventory + eval vs audio_only
- [x] Hard-suite + Codex review captured
- [x] This handoff doc
- [x] Developer V2 toggle and separate `timings_v2` table
- [x] Pure runtime keyframe path with V1 fallback and no Lab overrides
- [x] Machine-generated same-take Alafasy slice with a real repeat-heavy row
- [x] Pinned QUA letter/repeat importer with waveform identity abstention
- [x] Full Alafasy same-take expansion landed in `tools/timing_v2/alafasy_qua.json`
      (~2,700 ayahs / ~43% coverage / 321 repeat rows) with DB `timings_v2`
- [x] Objective validators + independent mono CTC onset witness
      (`validate_timing_v2.py`, `eval_v2_ctc_witness.py`,
      `results/v2_scale_snapshot.json`)

The same-take lane is scale-tested, not human-calibrated. Snapshot
(2026-07-27): hard duration flags **0/2700**; multi-window `sourceZeroMs`
spread p90 **0.25 ms** on n=40; mono CTC witness onsets **med 36.5 ms /
p90 91 ms / 93.3% ≤100 ms** (n=56 rows, 616 onsets). Still **not** a 99%
claim — coverage is 43%, witness ≠ ear gold, repeats need structure labels.

### Phase 1 — valid labels + time-localized structure

1. Add provenance/adjudication status to the historical corpus; ear-check the
   40 incompatible rows rather than weakening the sacred-text grammar to fit
   them.
2. Label a stratified random structure set (≥300) and a separate
   repeat-enriched challenge set. Freeze held-out splits before tuning.
3. [partial] Use QUA structure directly on same-take matches (**done at
   scale**). For unmatched takes (~57%), replace substring generation +
   global edit score with a constrained time-localized CTC graph/beam.
4. Calibrate abstention and second-model disagreement on validation data.
   Measure accepted accuracy, coverage, repeat P/R, and clean FP separately.
5. **Shadow one full reciter** (e.g. Alafasy)
   - Same-take accept/abstain already logged via QUA generator.
   - Remaining: different-take decoder + flag rate vs shipped structure.

### Phase 2 — onset gold + clock

1. Ear-label onsets on ≥50 ayahs / ≥1000 words (double-label 10%).
2. Clock: global FA; then anchor-bounded windows from decode char times.
3. Ablate lead-in / onset±40 / trailing trim **only if** ear gold improves.

### Phase 3 — cutover

1. Integrate generator into `tools/build_db.py` (or sibling job writing same schema).  
2. Regenerate `data/quran.db`, bump `QuranDatabase.DB_FILE_NAME`.  
3. Freeze/delete `timing_repairs` generator and override pipeline as truth.  
4. CI: adjudicated historical regression + frozen independent smoke suite.

### Explicit non-goals for now

- Whisper / seq2seq text for structure (LM erases re-says).  
- MFA as structure judge (clock/sub-word later).  
- More FA path multi-hyp for structure.  
- Deleting repairs before independent gates + shadow metrics are green.
- Claiming 99% without random ear sample.

---

## 7. Code map (`tools/sync_lab/`)

| Path | Role |
|---|---|
| `historical_manual_patches.json` | 409 historical overrides; mixed-provenance regression evidence |
| `eval_vs_manual_patches.py` | Audit grammar compatibility and score methods |
| `results/eval_vs_manual_patches.json` | Latest 409-run numbers |
| `grammar_structure.py` | Timed free decode, candidates, score, select, reclock stub |
| `decode_structure.py` | Earlier unique-span insert (baseline) |
| `structure_engine.py` | Pause/path multi-hyp (**negative result** — do not extend for structure) |
| `reclock.py` | Fixed-sequence clock helpers |
| `aligners.py` | Shared CTC load / force-align / audio I/O |
| `gold_structure_cases.json` | 11 famous repair cases |
| `test_codex_path.py` | Multi-method hard-suite + clock proxy |
| `test_decode_structure.py` | Decode-only hard suite |
| `PATH_FORWARD.md` | Earlier decision summary (superseded by **this** doc for handoff) |
| `CODEX_APPROACH_REVIEW.md` | Codex 5.6-sol high architecture review |
| `PATCH_REGRESSION.md` | Patch-suite commitment (detail; this doc is canonical) |
| `STRUCTURE_RESULTS.md` / `RESULTS.md` | Early experiment notes |
| `audio/` | Cached everyayah MP3s (gitignored) |

**App/DB:** `tools/build_db.py`, `tools/timing_repairs/`, `tools/timing_overrides/`, `data/quran.db`, `QuranDatabase.DB_FILE_NAME`.

**External:** historical repair generator lived in `~/qasr` (not required for first-principles path).

---

## 8. How to run (agent setup)

```bash
# CUDA venv (example used in lab)
source /tmp/alignlab-venv/bin/activate
# torch CUDA, transformers, soundfile, numpy, scipy, librosa
# model: jonatasgrosman/wav2vec2-large-xlsr-53-arabic

cd <repo>
python tools/sync_lab/eval_vs_manual_patches.py      # full 409 (~6–10 min GPU)
python tools/sync_lab/eval_vs_manual_patches.py 20   # smoke
python tools/sync_lab/eval_vs_manual_patches.py --reuse-decodes  # CPU rescore
python tools/sync_lab/test_codex_path.py             # hard suite + clock proxy
python tools/sync_lab/test_decode_structure.py
```

Audio auto-downloads from `https://everyayah.com/data/<slug>/SSSAAA.mp3`.

---

## 9. Decision log (do not re-litigate without new data)

1. **Structure ≠ clock** — separate modules and metrics.  
2. **FA path score is not for structure** — proven on 3:21.  
3. **Free decode is structure evidence** — foundation, incomplete.  
4. **Historical overrides are regression evidence, not independent gold.**
   Quarantine grammar-incompatible rows until ear-adjudicated.
5. **QDC is optional candidate**, never unconditional truth.  
6. **1% residual = flags**, not a new rule file per bug.  
7. **Ship only after ≥99% on frozen independent samples**, with accuracy and
   coverage reported separately.
8. **Global decode edit similarity is not the structure objective** — oracle
   repeats lose to mono on 15/74 valid repeat cases.

---

## 10. Immediate next task for the continuing agent

**Done (2026-07-27 scale pass):** land 2,700 same-take QUA rows; duration +
multi-window clock falsification; mono CTC witness; metrics snapshot under
`tools/sync_lab/results/v2_scale_snapshot.json`.

**Priority 1:** Create the frozen independent structure/onset labels and
adjudicate the 40 incompatible historical rows. Without this, 99% is
undefendable.

**Priority 2:** Time-localized constrained CTC for the ~57% different-take
ayahs; retire flattened substring/edit scoring as the primary path.

**Priority 3:** Calibrate abstention on ear validation; publish accuracy and
coverage separately; only then raise coverage toward full-reciter cutover.

---

## 11. One-paragraph summary for humans

We want word timings derived from reciter audio and known Quran text without a
patch farm. The lab correctly split structure from clock, but its 409-row
“gold” gate was impossible: 40 bulk-import rows violate the canonical grammar,
and the current flattened decode score cannot distinguish real repeats from
false ones even with oracle candidates. The credible path to 99% is frozen
independent ear labels, a time-localized constrained CTC decoder, calibrated
abstention with coverage reported, then fixed-structure reclocking and a full
reciter shadow run. Existing repairs stay until those gates pass.
