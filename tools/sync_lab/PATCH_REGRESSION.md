# Commitment: first-principles timing vs historical manual patches

**Goal:** Ship word timings from **audio + Uthmani text only** — no permanent
manual overrides, no growing systematic repair rules. Accept ~1% residual error
(flags / rare Lab), not a patch farm.

**Regression gold:** every unique edit ever committed under
`tools/timing_overrides/` (recovered from git into
`historical_manual_patches.json`).

**Eval:** `python tools/sync_lab/eval_vs_manual_patches.py`  
**Results:** `results/eval_vs_manual_patches.json` (2026-07-26 run)

---

## What “pass” means

For each historical patch ayah, **audio-first structure** (no
`timing_repairs`, no override files) must match the **position sequence** you
ear-fixed (or bulk-fixed) — without encoding that ayah as a special case.

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

Of those: **319** gold structures are mono (mostly unsplits); **90** have
backtracks (real re-says you wanted kept/fixed).

Today `timing_overrides/` is empty — those fixes live in auto-repairs / DB.
This suite is the **do-not-regress** list for going patch-free.

---

## Baseline: pure audio-only vs those 409 (first run)

Method **audio_only** = free-decode grammar candidates, **no QDC, no repairs**.

| Slice | mono exact | **audio_only exact** |
|---|---:|---:|
| **All 409** | 72.1% | **51.6%** |
| Gold was mono (319 unsplits) | **92.5%** | 63.0% |
| Gold had repeat (90) | 0% | **11.1%** |

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

1. **Bulk of your patches were “remove false repeats.”** Plain mono already
   recovers **~92.5%** of those structures. Current audio_only is **too eager**
   to invent resays and **hurts** that bulk (51% overall).
2. **Real re-say patches** are still weak under pure audio (**~11%** of 90).
3. We are **not** at “drop all patches/repairs and ship” yet.
4. The **comparison harness exists** — every future model must beat these
   numbers toward **≥99% exact on this 409-set** (and a random sample).

---

## Plan (locked)

```text
1. First-principles generator (audio + text only)
2. Always score against historical_manual_patches.json
3. Gate: ≥99% exact position match on that set before cutover
4. Separate random ear sample for true 1% residual claim
5. No new per-ayah override rules — only model/scorer/confidence improvements
```

### Engineering order

| Step | Work |
|---|---|
| A | Tighten **false-resay** control so audio_only ≥ mono on unsplit bulk (~99% of 319) |
| B | Improve **true-resay** recall on the 90 (+ famous misses) without new FPs |
| C | Reclock ms on matched structures; sample ear onsets |
| D | Shadow full reciter; cutover when gates pass; freeze/delete repairs+overrides |

---

## Bottom line

- **Yes:** we use your historical timepatches as the proof suite that we can
  go **without** systematic patch rules.
- **Today:** pure audio is **not** there yet (~52% exact on 409; ~11% on
  re-say patches).
- **Target:** ≥99% on this suite + ≤1% residual on a random sample, **zero**
  permanent manual patch pipeline.
