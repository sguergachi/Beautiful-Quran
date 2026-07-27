# Word timing from first principles — handoff doc

**Status:** research + lab prototype; not production cutover  
**Owner goal:** clean dataset from **audio + Uthmani text only** — no permanent manual patches, no growing systematic repair rules; accept **~1% residual** error (flags), not a patch farm.  
**Last lab run:** 2026-07-26/27 (RTX 3080)

This is the **single handoff document**. Other files under `tools/sync_lab/` are evidence and code; treat this doc as the source of plan and decisions.

---

## 1. Product law

Beautiful Quran’s core is **word ↔ recitation lock**: each Arabic word lights in total sync with the reciter. Timing quality is the product, not a feature.

- Runtime stays offline: pure `HighlightEngine` over bundled `Segment(position, startMs, endMs)`.
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

### 3.4 Historical **manual** patches as true handoff gold

Recovered from git all unique `timing_overrides` edits → **409 ayahs**  
(`tools/sync_lab/historical_manual_patches.json`).

| Slice | Count | Intent |
|---|---:|---|
| Mono structure (mostly false-split **unsplits**) | 319 | Remove bogus backtracks |
| With real backtracks | 90 | Keep/fix re-say structure |
| Files | e.g. `alafasy-split-merges` (342), `hani-asr-drops` (40), `hani-asr-repeats` (19), `patches-from-417` (4 ear cases), one-offs | |

**Eval vs those 409 (audio-only, no repairs) — 2026-07-27:**

| Method | All 409 exact | Mono-gold 319 exact | Repeat-gold 90 exact |
|---|---:|---:|---:|
| mono | 72.1% | **92.5%** | 0% |
| **audio_only** | **51.6%** | 63.0% | **11.1%** |

Famous:

| Case | audio_only matches patch structure? |
|---|---|
| Alafasy 3:21 | **yes** |
| Hani 2:14 | **yes** |
| Hani 2:33 | no |
| Alafasy 5:59 / 5:44 | no |
| Several mono unsplits | mono yes; audio sometimes invents false resays |

**Conclusion:** current audio-only is **not shippable**. It is **too eager** on false resays (hurts the bulk unsplit set) and **too weak** on real re-says (~11% of 90).

### 3.5 Clock (ms)

- Force-aligning a **fixed** position sequence on everyayah audio works and is **pad-stable** (~0 ms pad residual).
- Comparing reclock to QDC/shipped ms is **not** ear truth (different clocks; med ~130 ms Δ vs shipped on hard set).
- Prefer **global fixed-sequence FA** over letter-weight run slicing until true episode anchors exist.
- Product bar (med ≤25 ms, p90 ≤60 ms, ≥99% within 100 ms) needs **ear-labeled onsets** — not done.

### 3.6 Scoring pitfall fixed once

Penalizing “extra words” in decode-similarity **rejected true multi-word re-says** when decode was noisy.  
**Gate with a small mono margin on raw similarity**, not a per-extra-token tax.

### 3.7 QDC role

| Wrong | Right |
|---|---|
| Unconditional fallback (“no decode hit → QDC”) | Preserves both real and false QDC |
| Delete QDC day one | Lose weak supervision early |
| **QDC as scored candidate** among mono + audio inserts | Clean arbitration; can override QDC when audio is clearer |

Long-term: drop QDC if audio-only clears 99% on eval gates.

---

## 4. Target architecture (first principles)

```text
BUILD-TIME only
──────────────
everyayah MP3 + canonical words[1..N]
        │
        ├─► free CTC decode (timed characters)     [evidence]
        ├─► candidates:
        │     • mono 1..N
        │     • grammar inserts (contiguous re-say spans from decode evidence)
        │     • optional: QDC / second model paths as candidates only
        ├─► score = similarity(decode, concat(norm(words[pos])))
        ├─► pick best; non-mono needs score ≥ mono + margin
        ├─► if top-2 too close → FLAG (do not invent a rule)
        ├─► CLOCK: force-align chosen positions on same audio → start_ms
        ├─► holdEndMs = next start (display policy)
        └─► validate invariants → quran.db row  |  flag queue

RUNTIME: unchanged (HighlightEngine + OutputLatency)
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
| Structure exact on **historical 409 patches** | **≥99%** | `historical_manual_patches.json` |
| Structure exact on **random** sample (≥300 ayah×reciter) | ≥99% | Independent ear labels (not circular DB) |
| Repeat-event P/R on **hard/re-say** set | ≥99% | Ear / challenge set |
| Clean FP (false new backtracks) on non-repeat sample | ~0 | Ear / mono truth |
| Onset med / p90 | ≤25 / ≤60 ms (or agreed bar) | Ear Lab taps |
| Onsets within 100 ms | ≥99% | Ear Lab taps |
| Flag rate | Documented; Lab residual only | Full reciter shadow run |

**Do not claim 99% from:** 11 circular cases, energy-rise composite, pad recovery alone, or “word count match.”

---

## 6. Roadmap

### Phase 0 — harness (done)

- [x] Lab code under `tools/sync_lab/`
- [x] Historical patch inventory + eval vs audio_only
- [x] Hard-suite + Codex review captured
- [x] This handoff doc

### Phase 1 — beat the 409-patch gate (next agent priority)

1. **False-resay control**  
   - audio_only must reach **≥99% exact on the 319 mono-gold patches** (should be near mono; stop inventing BT).  
   - Margin / evidence thresholds only — **no per-ayah rules**.

2. **True-resay recall**  
   - Raise exact match on the **90** repeat-gold patches toward 99%.  
   - Multi-episode + span length >4 (production DB has multi-episode and long spans).  
   - Timed decode + grammar DP/beam as in Codex review.

3. **Re-run**  
   `python tools/sync_lab/eval_vs_manual_patches.py`  
   Gate: **≥99% exact on all 409**.

4. **Shadow one full reciter** (e.g. Alafasy)  
   - No DB write.  
   - Log winner, mono_sim, flag, disagree-with-shipped.  
   - Report flag rate.

### Phase 2 — independent gold + clock

1. Ear-label structure on random ~100–300 + all remaining misses.  
2. Ear-label onsets on ≥50 ayahs / ≥1000 words (double-label 10%).  
3. Clock: global FA; then anchor-bounded windows from decode char times.  
4. Ablate lead-in / onset±40 / trailing trim **only if** ear gold improves.

### Phase 3 — cutover

1. Integrate generator into `tools/build_db.py` (or sibling job writing same schema).  
2. Regenerate `data/quran.db`, bump `QuranDatabase.DB_FILE_NAME`.  
3. Freeze/delete `timing_repairs` generator and override pipeline as truth.  
4. CI: regression on `historical_manual_patches.json` + smoke suite.

### Explicit non-goals for now

- Whisper / seq2seq text for structure (LM erases re-says).  
- MFA as structure judge (clock/sub-word later).  
- More FA path multi-hyp for structure.  
- Deleting repairs before 409-gate + shadow metrics are green.  
- Claiming 99% without random ear sample.

---

## 7. Code map (`tools/sync_lab/`)

| Path | Role |
|---|---|
| `historical_manual_patches.json` | **409-patch regression gold** (from git overrides) |
| `eval_vs_manual_patches.py` | Score methods vs that gold |
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
python tools/sync_lab/test_codex_path.py             # hard suite + clock proxy
python tools/sync_lab/test_decode_structure.py
```

Audio auto-downloads from `https://everyayah.com/data/<slug>/SSSAAA.mp3`.

---

## 9. Decision log (do not re-litigate without new data)

1. **Structure ≠ clock** — separate modules and metrics.  
2. **FA path score is not for structure** — proven on 3:21.  
3. **Free decode is structure evidence** — foundation, incomplete.  
4. **Historical 409 patches are the primary engineering gate** for “no more manual/systematic patches.”  
5. **QDC is optional candidate**, never unconditional truth.  
6. **1% residual = flags**, not a new rule file per bug.  
7. **Ship only after ≥99% on 409 + independent sample** — current audio_only ~52% on 409.

---

## 10. Immediate next task for the continuing agent

**Priority 1:** Improve `grammar_structure.select_structure` / evidence so that:

1. On **319 mono-gold** historical patches → **≥99% exact** (fix false resay rate).  
2. On **90 repeat-gold** → maximize exact without regressing (1).  
3. Re-run `eval_vs_manual_patches.py` and update numbers in this doc §3.4 if they change materially.

**Priority 2:** Shadow full Alafasy generation (structure + global reclock), write disagreement/flag report — still no DB cutover.

**Priority 3:** Only then design ear-gold sampling and build_db integration.

---

## 11. One-paragraph summary for humans

We want word timings derived purely from reciter audio and the known Quran text, without maintaining manual Lab patches or a thick stack of structural repair heuristics. Lab work showed that forced-alignment path scores cannot decide re-recitation structure, but free CTC decode can expose re-says; a candidate scorer (mono vs audio-derived vs optional QDC) plus force-aligned clocks is the clean architecture. Against 409 historical manual patch ayahs, pure audio-only is only ~52% exact today—too many false resays on bulk unsplits and too few true resays recovered—so we are not ready to delete repairs. The continuing work is to clear a ≥99% match on that patch suite without per-ayah rules, then reclock, shadow a full reciter, ear-validate, and cut over to a first-principles `quran.db`.
