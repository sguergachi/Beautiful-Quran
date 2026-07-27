# Sync lab results — automated high-fidelity word timing

**Goal:** scale + quality. No human ear in the loop for production.
Lab code lives in this directory; eval audio is downloaded from everyayah.com.

**Date:** 2026-07-26 · **GPU:** RTX 3080 · **Throughput:** ~0.4 s/ayah (CTC+post)

---

## Product law (reminder)

Word↔recitation lock **is** the product. Timing must be millisecond-honest so
the ink wash and the voice feel like one act. See
[docs/SYNC_FIDELITY.md](../../docs/SYNC_FIDELITY.md).

---

## Hypotheses tested

| ID | Hypothesis | Result |
|---|---|---|
| H1 | Arabic wav2vec2 CTC forced-align beats energy/equal-split baselines | **Confirmed** — 100% word-count match, best composite score |
| H2 | Lead-in snap (first speech energy) improves first-word starts | **Confirmed** — lifts score; removes silence-at-t=0 artifacts |
| H3 | Narrow onset refine (±40 ms) > wide (±90 ms) | **Confirmed** — ±40 cleaner; ±90 over-snaps continuous speech |
| H4 | Trailing-silence trim fixes last-word inflation | **Confirmed** — lower last-word tail ratio |
| H5 | Quran-phonetic HF model (`TBOGamer22/…`) beats general Arabic | **Rejected** — stable path scores but **~1.2 s median drift vs shipped data** (wrong token alphabet for our letter mapping) |
| H6 | MMS (FAIR) + romanize via `ctc-forced-aligner` is competitive | **Partial** — strong on short surahs (med \|Δ\| ~45 ms vs baseline); on full eval set ~90 ms, slightly behind Arabic CTC on acoustic composite |
| H7 | Ensemble median(MMS, CTC) beats either alone | **Rejected** for mono/structure; median can smear boundaries |
| H8 | Pad-shift recovery (200 ms silence) measures aligner stability | **Confirmed** — CTC residual **0 ms median** (perfect shift tracking) |
| H9 | Shipped baseline alone is already “good enough” | **False for scale path** — baseline mono rate only ~67–78% under our checks; last-word tail higher; CTC post-pipeline scores higher on automated acoustic metrics |

---

## Ranking (lab_v3 — 108 ayah×reciter, 3 reciters, pad-test)

Eval set: 36 ayahs × {Alafasy, Husary, Hani}. Metrics are **ear-free**.

| Rank | Method | Score | Count% | Mono% | Padε med | Path | \|Δbase\| med |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | **ctc_arabic_lead_onset40_trim** | **88.4** | 100 | 100 | **0.0** | −0.49 | 100 ms |
| 2 | ctc_quran_lead_onset40_trim | 87.4 | 100 | 100 | 0.0 | −0.55 | **1225 ms** ⚠ |
| 3 | ctc_arabic_lead / +trim | 86.2 | 100 | 100 | 0.0 | −0.49 | 95 ms |
| 4 | ctc_arabic raw | 82.6 | 100 | 100 | 0.0 | −0.49 | 100 ms |
| 5 | ctc_quran raw | 82.1 | 100 | 100 | 0.0 | −0.55 | **1242 ms** ⚠ |
| … | equal_split / energy_uniform | 63–66 | 100 | 100 | — | — | 0.5–0.9 s |
| … | baseline (shipped) | 51.4 | 99 | 78 | — | — | 0 (self) |

⚠ High composite + huge \|Δbase\| = **false friend**: path score is not enough;
always gate with a second source or structural sanity.

### MMS bake-off (lab_v4 — same 108)

| Method | Score | \|Δbase\| med | \|Δ\| p90 |
|---|---:|---:|---:|
| **ctc_ar_best** (lead+onset40+trim) | **73.4** | 100 | 395 |
| ensemble_mms_ctc | 61.9 | 68 | 443 |
| mms_lead_onset40_trim | 61.2 | 90 | 538 |
| mms_raw | 58.4 | 98 | 538 |
| baseline | 51.4 | 0 | 0 |

On **Fatiha+Ikhlas only**, official MMS package hit **med \|Δstart\| = 45 ms** vs
baseline — excellent agreement on clean short ayahs. Full mixed set widens both
methods to ~90–100 ms median distance from Sphinx-era baseline (expected: both
can be *better* than baseline, so distance ≠ error).

---

## Winner pipeline (production)

```
everyayah MP3 + canonical words from quran.db
        │
        ▼
Arabic CTC forced align
  model: jonatasgrosman/wav2vec2-large-xlsr-53-arabic
  engine: torchaudio.functional.forced_align (char→word merge)
        │
        ▼
Post (cheap, deterministic)
  1. snap_lead_in     — first word → first speech energy (≤400 ms)
  2. onset refine     — each start ±40 ms to local energy onset
  3. trim_trailing    — last end ≤ last speech frame
  4. karaoke hold     — end_i = start_{i+1}
        │
        ▼
segments [[pos, start_ms, end_ms], …]
```

**Optional confidence gate (scale-safe):**

- Run MMS (`MahmoudAshraf/mms-300m-1130-forced-aligner`, `romanize=True`, lang=`ara`) in parallel.
- If median \|start_CTC − start_MMS\| ≤ 60 ms → accept CTC (or median).
- If larger → keep shipped baseline **or** flag for Timings Lab (do not silent-ship).

**Do not use:** free Whisper transcription text; Quran-phonetic model with letter
mapping as-is; wide onset windows; ensemble that breaks mono.

---

## Scale estimate (this machine)

| Scope | ~Ayahs | Time @ 0.4 s |
|---|---:|---:|
| Eval set (36 × 3) | 108 | ~1 min |
| One reciter full Quran | 6,236 | **~40 min** |
| All 7 reciters | 43,652 | **~5 h** |

Fully automated; no ear pass required for bulk. Ear/Lab only for confidence-flagged tails.

---

## How to reproduce

```bash
# venv with CUDA torch (once)
python3 -m venv /tmp/alignlab-venv
source /tmp/alignlab-venv/bin/activate
pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu130  # or matching CUDA
pip install numpy scipy soundfile librosa transformers accelerate
pip install git+https://github.com/MahmoudAshraf97/ctc-forced-aligner.git

# bake-off
python tools/sync_lab/run_lab.py --pad-test --out lab_v2.json

# production dry-run (one surah)
python tools/sync_lab/batch_align.py \
  --reciter Alafasy_128kbps --surah 1 \
  --out tools/sync_lab/results/fatiha_alafasy.json
```

Audio for the eval set is under `tools/sync_lab/audio/<slug>/`. Download more
from `https://everyayah.com/data/<slug>/SSSAAA.mp3` as needed.

---

## Next experiments (not yet run)

1. **Char-level wash keyframes** — emit per-grapheme spans from the same CTC path for L3 ink pacing.
2. **MFA + Hafs lexicon** — phone TextGrids for tajwīd-true sub-word (higher investment).
3. **WhisperX timestamps-only** — bulk gap cleanup control; never for repeats.
4. **Full-reciter job** writing patch JSON compatible with `tools/timing_overrides/`.
5. **Repeat-aware pass** — keep qdc/repair structure; only re-time boundaries (surgical), never one-pass-flatten repeats.
