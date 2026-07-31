# Historical override regression audit

**Goal:** Ship word timings from **audio + Uthmani text only** — no permanent
manual overrides, no growing systematic repair rules. Accept ~1% residual error
(flags / rare Lab), not a patch farm.

**Regression corpus:** every unique edit ever committed under
`tools/timing_overrides/`, recovered into
`historical_manual_patches.json`. This is mixed-provenance evidence, not
independent human gold.

**Eval:** `python tools/sync_lab/eval_vs_manual_patches.py`  
**Results:** `results/eval_vs_manual_patches.json` (2026-07-26 run)

---

## What “pass” means

For each grammar-compatible historical row, compare **audio-first structure**
(no `timing_repairs`, no override files) with the old position sequence
without encoding that ayah as a special case. Incompatible rows are
quarantined for ear adjudication.

Clock (ms) is a second gate: force-align that sequence on everyayah audio.

---

## Inventory of your patches (git history)

| Source file | Unique ayahs | Typical intent |
|---|---:|---|
| `alafasy-split-merges.json` | 342 | Unsplit false QDC repeats → mono |
| `hani-asr-drops.json` | 40 | Fill dropped words |
| `hani-asr-repeats.json` | 19 | Real re-say structure |
| `patches-from-417.json` | 4 | Ear-verified (2:14, 2:33, 4:143, 67:22) |
| one-offs (5:52, 5:54, 5:59, 2:214, …) | few | Ear-verified structure/timing |
| **Total unique** | **409** | |

The original inventory has 319 rows without backtracks and 90 with backtracks.
That split alone does not establish validity.

Today `timing_overrides/` is empty — those fixes live in auto-repairs / DB.
This suite is useful historical evidence for going patch-free.

### Grammar audit

| Slice | Rows |
|---|---:|
| Grammar-valid mono | 295 |
| Grammar-valid repeat | 74 |
| Incompatible, quarantined | **40** |

All 40 incompatible rows come from `alafasy-split-merges.json`; 24 skip
canonical positions and 16 also contain invalid jumps/backtracks. A decoder
whose first pass must cover `1..N` can match at most **369/409 = 90.2%** of the
unfiltered corpus, so “≥99% exact on all 409” was an impossible gate.

---

## Baseline: pure audio-only vs those 409 (first run)

Method **audio_only** = free-decode grammar candidates, **no QDC, no repairs**.

| Slice | mono exact | **audio_only exact** |
|---|---:|---:|
| **All 409** | 72.1% | **51.6%** |
| Grammar-valid 369 | 79.9% | **57.2%** |
| Valid mono 295 | **100%** | 68.1% |
| Valid repeat 74 | 0% | **13.5%** |

### Famous ear patches

| Case | audio_only match? |
|---|---|
| Alafasy 3:21 re-say | **yes** |
| Hani 2:14 span | **yes** |
| Hani 2:33 span | no |
| Hani 4:143 / Alafasy 67:22 (mono fix) | **yes** (mono) |
| Alafasy 5:59 real re-say | no |
| Alafasy 5:44 | no |

### Reading

1. Current audio_only is **too eager** to invent resays and retains only 68.1%
   of valid mono rows.
2. Repeat recovery is still weak (**13.5%** of valid repeat rows).
3. We are **not** at “drop all patches/repairs and ship” yet.
4. No global edit-similarity margin fixes the tradeoff: a margin retaining
   294/295 mono rows recovers only 1/74 repeats, and the oracle repeat candidate
   loses to mono under this objective on 15/74 rows.

---

## Plan (locked)

```text
1. First-principles generator (audio + text only)
2. Use historical_manual_patches.json as regression evidence, not test truth
3. Gate on a frozen independent ear-labeled random + repeat challenge set
4. Report accepted accuracy and coverage separately
5. No new per-ayah override rules — only model/scorer/confidence improvements
```

### Engineering order

| Step | Work |
|---|---|
| A | Adjudicate the 40 incompatible rows; freeze independent labels |
| B | Replace flattened edit score with time-localized constrained CTC decoding |
| C | Reclock ms on matched structures; sample ear onsets |
| D | Shadow full reciter; cutover when gates pass; freeze/delete repairs+overrides |

---

## Bottom line

- **Yes:** historical timepatches remain useful regression evidence.
- **No:** they are not sufficient or internally compatible proof of 99%.
- **Target:** ≥99% on frozen independent labels, with coverage and flag rate
  reported, and **zero** permanent manual patch pipeline.
