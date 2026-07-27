# Sync fidelity — the core of Beautiful Quran

> **This is the most important thing in the product.**
>
> Beautiful Quran exists so that the **written word and the recited word
> feel like one act**. When the reciter says a word, that word is already
> lighting on the page. When the voice moves on, the ink has finished its
> wash. The user should feel more connected to the Quran because **text
> and recitation are linked through millisecond-accurate, word-by-word
> passage in total sync**.
>
> Everything else — themes, bookmarks, search, share, even the paper
> metaphor — is in service of that moment. If the highlight lags, jumps,
> or lands on the wrong word, the product has failed at its core function
> no matter how beautiful the rest looks.

This document states that product law, describes what we ship today, and
surveys how we can push word (and sub-word) timing toward **true
millisecond fidelity**.

Related:

| Doc | Role |
|---|---|
| [HIGHLIGHT_ENGINE.md](HIGHLIGHT_ENGINE.md) | Runtime: pure `(segments, t) → active word` |
| [INK_ENGINE.md](INK_ENGINE.md) | How the active word *looks* (directional wash) |
| [OUTPUT_LATENCY.md](OUTPUT_LATENCY.md) | Device/BT lag compensation so the ear and the ink agree |
| [REPEAT_HIGHLIGHTING.md](REPEAT_HIGHLIGHTING.md) | When the reciter re-says a phrase |
| [TIMINGS_LAB.md](TIMINGS_LAB.md) | Human ear-correction of bad segments |
| [tools/sync_lab/RESULTS.md](../tools/sync_lab/RESULTS.md) | **Live bake-off** — automated aligners ranked for scale+quality |
| [tools/timing_repairs/README.md](../tools/timing_repairs/README.md) | Automated structural repairs (CTC vs qdc) |
| [tools/timing_overrides/README.md](../tools/timing_overrides/README.md) | Manual patches applied at DB build |

### Lab verdict (2026-07-26)

**Clock (ms), fixed word list — bake-off on 108 ayah×reciter pairs:**

| Rank | Pipeline | Notes |
|---:|---|---|
| **1** | **Arabic CTC** + lead-in + onset±40 + trim | Best acoustic composite; pad recovery 0 ms |
| 2 | MMS romanized forced-align | Competitive on short surahs |
| ✗ | Path-score multi-hyp for *structure* | **Cannot** replace repairs — FA prefers collapsing re-says |

**Structure (repeats / backtracks) — gold hard-case suite:**

| Method | Exact seq | Notes |
|---|---:|---|
| Free **decode-string** unique span match | **7/11** | Hits 3:21, 2:14, 5:46; **no false BT** on 2:8 / 4:157 / muqattaʿāt |
| Pause + FA path-score multi-hyp | 4/11 | Safe on non-repeats; misses real re-says |
| Shipped qdc+repairs | 11/11 | Current production structure |

**Clean split (eliminates most `timing_repairs` rules):**

1. **Structure** = decode span match (or qdc fallback), never FA path score  
2. **Clock** = force-align the *fixed* position sequence  

**Handoff / plan:** [TIMING_FIRST_PRINCIPLES.md](TIMING_FIRST_PRINCIPLES.md).  
Lab evidence: [tools/sync_lab/](../tools/sync_lab/).

---

## Product law

1. **Sync is the product.** The signature experience is not "a Quran
   reader with highlighting." It is *recitation made visible* — each
   Arabic word revealed in lockstep with the voice so reading and
   listening become one continuous act of attention.
2. **Fidelity is non-negotiable.** A karaoke view that is slightly late
   feels broken; one that is early feels uncanny. We optimize for
   *felt* correctness at the ear, not just database numbers.
3. **Data quality beats UI cleverness.** No amount of soft ink wash
   paper-covers a systematically wrong `startMs`. Timing accuracy is a
   **build-time / data** problem first; the runtime engine is already
   pure and fast enough.
4. **Sub-word precision is the next ceiling.** The letter-level
   directional wash already *paces* within a word using
   `holdEndMs − startMs`. Better word boundaries make that wash land
   with the syllables; true phoneme or grapheme timings would let ink
   track the reciter *inside* long words (madd, ghunnah, elongated
   endings) instead of linearizing the word as a uniform sweep.

Human perception for "in sync" karaoke highlighting is roughly a
**~50–150 ms** window. Our historical source (quran-align) published
mean error under ~73 ms — good enough for word-level feel, not enough
to treat as finished work. Gaps, trailing silence on last words,
repeat artifacts, and reciter-specific drift still break the bond.

---

## What we ship today

```
everyayah audio (per ayah MP3)
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  Build-time timing sources (tools/build_db.py)            │
│                                                           │
│  • cpfair/quran-align  — Sphinx forced align, one-pass    │
│  • quran.com qdc       — repeat-aware segments (selected  │
│                          reciters)                        │
│  • timing_repairs/     — CTC structural repair (repeats,  │
│                          false splits, shifted spans)     │
│  • timing_overrides/   — ear-verified manual patches      │
└───────────────────────────────────────────────────────────┘
        │
        ▼
data/quran.db  →  Segment(position, startMs, endMs) per word
        │
        ▼
runtime: positionMs − outputLatency  →  HighlightEngine  →  InkEngine wash
```

| Layer | Precision | Notes |
|---|---|---|
| Bundled segments | Word-level integer ms | One span per uttered word (repeats as backtracks) |
| Letter wash | Interpolated inside the word span | Linear/smootherstep over time; **not** phoneme-timed |
| Poll rate | ~33 ms | Engine lookup is O(log n); not the bottleneck |
| Output latency | Route presets (0 / 80 / 180 ms) | BT lag is a *playback path* problem, not timing data |
| Human correction | Timings Lab + overrides | Gold standard for hard cases |

**Invariant:** the app never runs ASR or alignment at runtime. Timing is
precomputed, validated, and bundled. Offline-first and purity of
`HighlightEngine` stay intact.

---

## What "super high fidelity" means here

Three nested targets:

| Level | Goal | User feels |
|---|---|---|
| **L1 — Word lock** | Word `startMs` within ~20–40 ms of the true onset | Highlight never "jumps ahead" or "lags" at word boundaries |
| **L2 — Gap honesty** | No fake multi-hundred-ms dark gaps; last-word doesn't swallow trailing silence | Continuous karaoke flow through the ayah |
| **L3 — Sub-word / phoneme** | Grapheme or phoneme spans *inside* each word (ms) | Ink wash tracks tajwīd elongation; long words reveal with the voice, not a uniform timer |

L1 + L2 are required for the product law. L3 is the quality ceiling that
makes the ink wash *acoustically true* rather than aesthetically paced.

---

## Approaches for millisecond (sub-word) timing

Forced alignment (not free-form transcription) is the right family of
methods: **we already know the canonical Uthmani text**. We must never
trust ASR to invent or "correct" Quranic wording. Take timestamps; map
them onto verified word positions.

### 1. CTC forced alignment on Arabic wav2vec / MMS  ★ best next step

**What:** Feed known text + audio into a CTC model; Viterbi (or CTC
segmentation) emits frame-level token spans → aggregate to words or
characters.

**Tools / models:**

- [MahmoudAshraf97/ctc-forced-aligner](https://github.com/MahmoudAshraf97/ctc-forced-aligner) — HF CTC models, word **or character** split, Arabic via e.g. `jonatasgrosman/wav2vec2-large-xlsr-53-arabic` or MMS
- TorchAudio CTC forced alignment APIs
- Our existing external `~/qasr` pipeline already uses general-Arabic CTC for *structural* repair (repeats / splits) — see [timing_repairs](../tools/timing_repairs/README.md)

**Why it fits us:**

- **Text is sacred and fixed** — CTC alignment does not re-transcribe; it places known tokens on the timeline (same philosophy as Bilawal's WhisperX work: trust timestamps, not ASR text)
- Character-level mode gives a path to **L3** (sub-word wash)
- We already trust CTC as the *acoustic* witness for repeats (seq2seq/Whisper LM priors erase re-recitations — the wrong tool for that job)

**Limits:** Frame stride is often ~20 ms (can interpolate); elongations/madd produce blank frames that split words unless post-processed (we already encode that lesson in the repair discriminator). Low-bitrate everyayah MP3s hurt boundary sharpness.

**Fidelity ceiling:** Word onsets commonly within tens of ms when the model matches the language; character spans are coarser but usable for wash pacing.

### 2. Montreal Forced Aligner (MFA) — phoneme gold standard

**What:** Kaldi GMM-HMM (or DNN) aligner with a pronunciation dictionary → word *and phone* TextGrids.

**Evidence:** Comparative work (e.g. arXiv:2406.19363) finds **MFA often beats WhisperX / MMS on boundary accuracy** when a good lexicon exists; median word-boundary errors can sit in the low tens of ms.

**Why it matters for Quran:**

- Phone-level TextGrids are the natural representation for **tajwīd-aware** sub-word timing (madd length, sakinah, etc.)
- Published work on improving forced alignment for Qur'anic phoneme segmentation (long vowels / madd) shows domain-specific tuning helps

**Cost for us:**

- Need a **Quranic Arabic pronunciation dictionary** (Uthmani orthography → phones, including tajwīd allophones) and preferably reciter-adapted acoustic models
- Heavier offline training/adaptation pipeline than "pip install + run CTC"
- Still one-pass by default — **repeats need a separate detection pass** (CTC structure, or human Lab), same as today

**Fidelity ceiling:** Best published general-purpose word/phone alignment when lexicon + model are strong; closest path to true **phoneme-ms** for L3.

### 3. WhisperX (Whisper + wav2vec2 align)

**What:** Whisper transcribes; a wav2vec2 aligner snaps word timestamps to the audio.

**Field report (Quran apps):** Used to fix noisy segment files (large gaps, last-word swallowing silence). Large reduction in gap count when word counts are reconciled to the canonical text.

**Critical rule (same as ours):** **Do not use Whisper's text.** Use only alignment timestamps; merge/split to the expected word count from our DB.

**Limits:**

- Whisper's LM prior **normalizes away re-recitations** — catastrophic for repeat-aware data (documented in our timing_repairs README)
- Word timestamps are often weaker than MFA on controlled benchmarks
- Still not true phoneme timing unless you run a second phone aligner

**Use when:** Bulk L1/L2 cleanup of one-pass word spans where quran-align/qdc are visibly gappy — always with word-count reconciliation and **without** replacing our repeat logic.

### 4. Domain-specific Quran ASR / Tarteel-class models

**What:** Models trained on Qur'anic recitation (tajwīd, multiple qirāʾāt, elongated vowels). Research and commercial stacks (Tarteel and academic ASR papers) aim at word- and phoneme-level recognition for teaching.

**Why interesting:** Better acoustic match to murattal/mujawwad than general MSA Arabic models → fewer boundary errors on madd and ghunnah.

**Why not a silver bullet:**

- Forced *alignment* of known text still beats free decoding for our bundling pipeline
- Licensing, model weights, and whether we can run them offline in `tools/` matter
- Same repeat problem as any seq2seq decoder

**Use when:** As the **acoustic backbone** inside a forced-alignment or CTC pipeline, not as a live transcriber in the app.

### 5. Human-in-the-loop (Timings Lab + overrides) — irreplaceable gold

No automated system matches a careful ear on pathological ayahs (false
splits, shifted repeats, basmalah lead-in). The Lab already implements
the Musixmatch workflow: tap-pass re-sync, per-word slide, live reader
preview.

**Role in a high-fidelity pipeline:**

- **Training / eval set:** Export Lab-corrected ayahs as ground truth to measure aligner median error in ms
- **Hard-case patches:** Always win over automatic repair (`timing_overrides` after `timing_repairs`)
- **Never the only path for 6,236 × N reciters** — scale requires automation; Lab is the quality bar and the escape hatch

### 6. Signal-processing helpers (not primary aligners)

| Technique | Role |
|---|---|
| Energy / onset detection | Nudge word starts to the nearest energy rise after a coarse align |
| DTW vs reference TTS (aeneas-style) | Weak on Arabic tajwīd; useful as a second opinion, not the source of truth |
| Cross-reciter transfer | Align one gold reciter densely, warp others with DTW — risky for style differences |
| Sub-frame interpolation | MFA-style interpolation past the 10 ms frame grid for smoother phone edges |

These polish L1/L2; they do not replace forced alignment.

---

## Recommended roadmap (build-time only)

Stay inside the existing invariant: **all alignment is offline**; the app
only loads `Segment` lists.

### Phase A — Measure (short)

1. Pick a **gold set**: 50–100 ayahs already ear-verified in Timings Lab / overrides, across 2–3 reciters (fast + slow, murattal).
2. Score every candidate aligner: median / p90 absolute error on word `startMs` and `endMs`, plus gap histogram and last-word overshoot.
3. Fail any method that destroys known repeats (compare to qdc + Lab).

**Success bar for L1:** median onset error ≤ 25 ms, p90 ≤ 60 ms on the gold set.

### Phase B — CTC re-time pipeline (**lab-validated winner**)

Implemented and measured in `tools/sync_lab/`:

1. Offline job: `tools/sync_lab/batch_align.py`
   - Input: everyayah MP3 + canonical word list from `quran.db`
   - Model: `jonatasgrosman/wav2vec2-large-xlsr-53-arabic` + `torchaudio.functional.forced_align`
   - Post: lead-in snap → onset ±40 ms → trailing silence trim → karaoke hold
   - Output: `[[position, start_ms, end_ms], …]` per ayah
2. **Do not** replace qdc's repeat structure blindly:
   - Keep the repair philosophy: CTC for *acoustic* truth on spans; preserve qdc/Lab span-repeats; only re-time boundaries
   - Surgical updates (same spirit as `realign_span` in timing_repairs)
3. Confidence gate (optional second model):
   - MMS via `ctc-forced-aligner` (`romanize=True`, `ara`)
   - Auto-accept when word count matches and median \|CTC−MMS\| ≤ ~60 ms
   - Else keep baseline or flag Timings Lab
4. Character mode experiment on long words: store optional
   `char_spans` (or derive wash keyframes) without changing the runtime
   schema until measured.

Scale on one RTX 3080-class GPU: **~40 min / reciter**, **~5 h / all 7**.

### Phase C — MFA / phoneme path (higher investment, L3)

1. Build or adopt a **Hafs pronunciation lexicon** (Uthmani → phones).
2. Train or adapt MFA acoustic models on everyayah + gold alignments.
3. Export phone TextGrids → map phones to graphemes for ink wash control
   points (optional column or side file; bump DB version if content ships).
4. Use phone durations to pace `letterFadeIn` / `shapedWordBloom` on
   elongated segments instead of uniform time.

### Phase D — Continuous quality

1. Regression suite: gold-set error metrics in CI (or a scheduled offline job) so a "better" aligner cannot silently regress.
2. Timings Lab remains the human circuit-breaker.
3. Per-reciter quality score in docs or Lab (median error vs gold).

**Do not:**

- Run Whisper/ASR at app runtime
- Let any model rewrite Arabic text
- Collapse repeat handling into a one-pass aligner
- Degrade the soft ink wash "for performance" once timings get denser

---

## How runtime already supports fidelity

Once segments are good, the stack is already built for *felt* sync:

| Mechanism | Why it matters |
|---|---|
| `HighlightEngine` pure binary search | Deterministic; unit-tested; no UI race can invent the wrong word |
| Hold across small gaps | Avoids flicker between words when data has micro-gaps |
| `holdEndMs` paces letter wash | Ink finishes as the voice moves on |
| Soft directional wash (not hard peels) | Masks residual 1–2 frame error; must stay soft even if data is perfect |
| `OutputLatency` | Corrects BT/speaker path so ear and ink share one clock |
| Repeat orange wash | Honest about re-recitation instead of skipping or double-dimming |

Better data multiplies all of these. Bad data nullifies them.

---

## Decision summary

| Priority | Choice |
|---|---|
| Product core | **Word ↔ recitation sync fidelity** above every other feature |
| Alignment method | **Forced alignment of known text**, never free transcription of wording |
| Near-term engine | **Arabic CTC / wav2vec forced align** (word, then char) + existing repeat repair logic |
| Long-term ceiling | **MFA or Quran-adapted phoneme models** for true sub-word ms |
| Human gold | **Timings Lab + overrides** for eval and hard cases |
| Runtime | Stay pure, offline, bundled — no on-device ASR |

When choosing any future work — a new reciter, a visual effect, a
performance hack — ask first:

> Does this make the word and the voice feel more perfectly one, or does
> it risk that bond?

If it risks the bond, it is not ready.
