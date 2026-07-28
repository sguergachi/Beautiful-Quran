# V2 structure — rebuild charter

**Status:** Dir 1 landed (2026-07-28) — full QUA Alafasy structure+letters in
`timings_v2`; structure gate locks 6:10. Animation / wash remains secondary.

## Why we are here

V2 shipped a **mono CTC** majority path. That path **cannot** represent phrase re-says.

Canonical fail: **6:10 Alafasy** (user screenshot, circled “فحاق / بالذين / سخروا” loop).

| Source | Positions | Backtracks |
|--------|-----------|------------|
| **V1** | `1…8, 6,7,8, 9…13` | 3 |
| **Shipped V2** | `1…13` only | **0** |
| **QUA Alafasy** (`mishary_rashid_al_afasy_mp3quran` v2.3.0) | `1…8, 6,7,8, 9…13` | **3** (matches V1) |

Lab-gold “100%” only scored **307** patched rows. It did **not** measure the **~800** V1 ayahs with backtracks. CTC mono flattened **~500+** of those in the shipped fork.

**Product rule:** wrong structure ⇒ wrong karaoke and orange chain. No letter polish or ink tuning fixes a missing re-say.

---

## Product requirements (99% for *our* purposes)

| # | Requirement | Gate idea |
|---|-------------|-----------|
| S1 | **Structure** = ordered word *occurrences* including phrase loops | Position sequence exact vs gold |
| S2 | **Word onsets** on the **EveryAyah file clock** | abs median / p90 / % within 100ms |
| S3 | **Letter keyframes** for wash hold/park | multi-kf rate; hold plateaus on long dwell |
| S4 | **Same audio take** as shipped MP3 | xcorr / duration / abstain |
| S5 | **Abstain → V1** when structure or clock is untrusted | explicit fallback counts in reader |

**Hard golden cases (must not regress):**

- `6:10` → `1,2,3,4,5,6,7,8,6,7,8,9,10,11,12,13`
- `5:54` → multi-loop (V1/QUA agree on several backtracks; minor topology may differ — lock after review)
- Fatiha mono ayahs → no false backtracks

**Forbidden metric:** “Lab gold only = 99% product.”

**Required metrics:**

1. **Backtrack recall** vs V1∪QUA structure gold (target ≥ 99% of gold backtrack *ayahs*, not tokens).  
2. **Structure exact** on golden set (100% of cases in `tools/timing_patch_cases/` / new `structure_cases/`).  
3. **Onset quality** on accepted V2 rows (p90 ≤ 100ms or stated bar).  
4. **Coverage** = accepted / mushaf (report separately from accuracy).

---

## Architecture: two layers (never merge)

```
                    ┌─────────────────────────┐
  audio + priors ──►│  A. STRUCTURE ENGINE    │──► occurrence list
                    │  (what was recited)     │    e.g. 1..8,6,7,8,9..
                    └───────────┬─────────────┘
                                │ fixed token sequence
                    ┌───────────▼─────────────┐
  EveryAyah MP3 ───►│  B. ACOUSTIC RECLOCK    │──► start/end + letter KFs
                    │  (when each unit sounds)│
                    └───────────┬─────────────┘
                                │
                    ┌───────────▼─────────────┐
                    │  C. GATES + ABSTAIN     │──► timings_v2 or V1 fallback
                    └─────────────────────────┘
```

**Invariant:** free / mono CTC **must not** invent structure. Aligners only place clocks on a **fixed** occurrence sequence.

---

## Direction stack (from investigation)

### Dir 1 — Import-first (QUA / QUL) — *spike result: GO*

**Spike (local `tools/.cache/qua/alafasy-v2.3.0.zip`):**

| Ayah | QUA structure | vs V1 | Letters |
|------|---------------|-------|---------|
| 1:1–1:7 | mono exact | match | yes |
| 5:54 | multi-loop | ~match V1 (small topology delta) | 210 letter tokens |
| **6:10** | **`…6,7,8,6,7,8…`** | **exact V1** | 72 letter tokens |

**Corpus (Alafasy QUA word tier vs V1):**

- V1 backtrack ayahs: ~808  
- QUA backtrack ayahs: ~830  
- Both: ~757  
- V1-only (QUA mono): ~51  
- QUA-only: ~73  

So QUA is **far closer** to truth than shipped CTC V2 (which kept only ~247 of V1’s backtrack ayahs).

**Why our old QUA lane was only 205 rows:** same-take xcorr against EveryAyah was ultra-strict; the release still has **full 6236** ayahs of structure+letters on **surah** audio (mp3quran). Rebuild should:

1. Prefer QUA structure + letters.  
2. Reclock surah-absolute times → EveryAyah ayah file (xcorr window / verse span).  
3. Accept on clock match; if structure is gold but clock fails, **still prefer structure** with alternate reclock or mark partial.

**Sources:**  
- [Qur'anic Universal Audio](https://github.com/Wider-Community/quranic-universal-audio) (CC BY timestamps; audio upstream)  
- HF `hetchyy/quranic-universal-ayahs` subset `mishary_rashid_al_afasy_mp3quran`  
- [QUL](https://qul.tarteel.ai/) word segments as secondary import  

### Dir 2 — Grammar + reclock — *core long-term law*

Structure priors (priority):

1. Lab gold / Timings Lab  
2. QUA same-reciter (structure)  
3. V1 grammar-valid backtracks  
4. Constrained decode that **may only** insert re-says of already-seen spans  

Clock: phoneme/word forced align on the **fixed** sequence (lafzize/MMS, wav2vec2-quran-phonetics, existing `quran_phoneme_aligner.py`).

### Dir 3 — Multi-witness + abstain — *quality bar*

Witnesses: QUA structure, QUA/CTC letter edges, energy onsets, V1 structure.  
Agree → accept. Disagree → V1 fallback or Lab.  
Report **coverage** and **accuracy** separately.

---

## Rebuild plan (ordered)

1. **Structure cases**  
   - Add machine-checked cases: `6:10`, `5:54`, Fatiha monos.  
   - Gate: `python3 tools/…` fails if V2 flattens 6:10.

2. **QUA full Alafasy import (Dir 1)**  
   - Use cached `alafasy-v2.3.0` word+letter tiers.  
   - Map `[[widx,start,end],…]` (+ letter rows) → our `segments` + keyframes.  
   - Reclock to EveryAyah; bump `DB_FILE_NAME`.

3. **Priority merge rewrite**  
   - Lab > QUA structure+letters > constrained CTC > abstain V1.  
   - **Delete mono CTC as structure source.**

4. **Metrics**  
   - Backtrack recall vs V1∪QUA.  
   - Onset eval on accepted only.  
   - Reader badge: structure source + acoustic/fallback counts.

5. **Only then** wash / animation fidelity.

---

## Non-goals

- Animation-only “fixes” for missing orange on mono rows  
- Claiming 99% from Lab subset alone  
- Free full-ayah CTC structure  

---

## Spike artifact locations

- QUA pin: `tools/sync_lab/qua_timing.py` (`QUA_RELEASE = v2.3.0`)  
- Cache: `tools/.cache/qua/alafasy-v2.3.0.zip`  
- Layout: `word_timestamps.json.gz` key `"s:a"` → `[[verseStart,verseEnd], [[widx,start,end],…]]`  
- Letters: `letter_timestamps.json.gz` → `[[verseStart,verseEnd], words, [[widx,char,start,end],…]]`  

---

## Decision (2026-07-28)

Proceed with **Dir 1 first** (full QUA Alafasy structure+letter import + reclock), under **Dir 2 laws** (structure ≠ free CTC), with **Dir 3 gates** (abstain when clock/structure untrusted).

## Landed (same day)

| Piece | Location |
|-------|----------|
| Full QUA importer | `tools/sync_lab/generate_qua_full_v2.py@1` |
| Merge Lab > full QUA > CTC gap | `tools/sync_lab/merge_v2_priority.py` |
| Committed V2 sources | `tools/timing_v2/alafasy_{lab_gold,qua_full,ctc_auto}.json` |
| Loader pin | `load_timing_v2` accepts `generate_qua_full_v2.py@1` |
| Structure gate | `tools/test_build_db.py` → Fatiha mono + 5:54 multi-loop + 6:10 exact |
| Backtrack metrics | `tools/sync_lab/eval_v2_structure_metrics.py` (no-regression floors) |
| Orange wash | Whole-word opacity ease on chain join; hold; dissolve (no glint on repeat) |
| DB | `quran-v36.db` (`QuranDatabase.DB_FILE_NAME`) |

**6:10:** V2 positions = V1 = `1…8,6,7,8,9…13`.  
**5:54:** V2 multi-loop locked (QUA topology; slight V1 delta on 21–24).  
**Scale:** ~6229 QUA accepted / 7 fail; merged Lab 307 + QUA full ~5925 + CTC gap few = 6236 V2 rows.  
**Backtrack recall vs V1 (measured):** ~77% of V1-backtrack ayahs still have a backtrack in V2 (~624/808); exact topology match ~62% among V1-bt. Floors: recall ≥75%, V2 bt ayahs ≥600, Fatiha mono.
