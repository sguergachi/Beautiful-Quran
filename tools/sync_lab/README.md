# sync_lab — automated word-timing research + production aligner

Build-time only. Finds (and can regenerate) millisecond word timings for
everyayah recitations **without human ear passes**.

## Start here

**Handoff (single canonical doc):**  
**[docs/TIMING_FIRST_PRINCIPLES.md](../../docs/TIMING_FIRST_PRINCIPLES.md)** — learnings, audited regression evidence, honest 99% gates, and next tasks.

Supporting notes (evidence, not the plan):

1. [CODEX_APPROACH_REVIEW.md](CODEX_APPROACH_REVIEW.md) — Codex architecture review  
2. [PATH_FORWARD.md](PATH_FORWARD.md) / [PATCH_REGRESSION.md](PATCH_REGRESSION.md) — earlier summaries  
3. [RESULTS.md](RESULTS.md) / [STRUCTURE_RESULTS.md](STRUCTURE_RESULTS.md) — bake-offs

## Layout

| Path | Role |
|---|---|
| `decode_structure.py` | **Structure:** free decode + unique span resays |
| `reclock.py` | **Clock:** FA given fixed position sequence |
| `structure_engine.py` | Pause/path-score multi-hyp (negative result) |
| `aligners.py` | Shared CTC force-align primitives |
| `generate_timing_v2.py` | Emits confidence-gated word + acoustic keyframe rows |
| `generate_qua_timing_v2.py` | Transfers pinned QUA letter spans across a verified same-take audio clock |
| `qua_timing.py` | Pure waveform-match, text-map, repeat, and letter-curve gates |
| `quran_phoneme_aligner.py` | Pinned Quran-phoneme CTC experiment with Uthmani→glyph mapping |
| `eval_v2_onsets.py` | V1/V2 onset, structure, accuracy, and coverage report |
| `test_timing_v2.py` | Pure keyframe rebase/rejection tests |
| `test_decode_structure.py` | Gold hard-case gate (repair regression suite) |
| `gold_structure_cases.json` | Cases that created timing_repairs rules |
| `batch_align.py` | Earlier mono clock dry-run |
| `audio/` | Cached everyayah MP3s (gitignored) |
| `results/` | Lab JSON outputs |

## Quick commands

```bash
source /tmp/alignlab-venv/bin/activate   # CUDA torch + deps

# bake-off
python tools/sync_lab/run_lab.py --pad-test --out lab.json

# re-time one surah
python tools/sync_lab/batch_align.py \
  --reciter Alafasy_128kbps --surah 1 \
  --out tools/sync_lab/results/out.json

# regenerate the first app-testable same-take V2 slice
python tools/sync_lab/generate_qua_timing_v2.py \
  --surah 1 --ayah-to 7 \
  --out tools/timing_v2/alafasy_fatiha.json

# compare V2 with historical regression evidence (not independent gold)
python tools/sync_lab/eval_v2_onsets.py \
  --out /tmp/v2-onsets-historical.json
```

Winner (2026-07-26): **Arabic CTC + lead_in + onset±40 + silence trim**.
The V2 path retains character-edge frames and explicit CTC blank plateaus
instead of discarding them after word aggregation. Its current path-score
threshold is provisional; expand coverage only after calibration against the
audio-hashed frozen splits documented in `independent_labels/README.md`.

The preferred high-confidence source is the pinned CC-BY-4.0 Qur'anic
Universal Audio `v2.3.0` release. Its 42-token letter intervals and repeat
positions are transferred only when decoded waveform correlation proves that
the source chapter and EveryAyah clip are the same take. On the 313 cached
Alafasy rows, 166 passed the deliberately bimodal gate; different takes
abstained instead of inheriting another recording's clock.

The next candidate for unmatched takes is the MIT-licensed
`obadx/muaalem-model-v3_2` phoneme
head, pinned with the companion Quran Phonetic Script revision in
`quran_phoneme_aligner.py`. It aligns short vowels, doubled consonants, and
madd units acoustically, then subdivides only the owning rendered letter's
spatial slot. It is research-only until the independent split proves its
accuracy; the model remains a build-time dependency and never ships in-app.

QUA is a production input, not independent truth for rows generated from it.
Its large external forced-alignment corpus is useful for held-out calibration
of other models, but the final 99% claim still needs the frozen human audit.
