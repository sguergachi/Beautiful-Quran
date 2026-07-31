# Timing V2 sources (parallel fork of V1)

`timings_v2` never overwrites `timings`. Priority when building
(see `docs/V2_STRUCTURE.md`):

1. **`alafasy_lab_gold.json`** — Timings Lab ground truth (grammar-valid historical patches)
2. **`alafasy_v1_replay_fallback.json`** — complete V1 re-says when a lower-priority acoustic lane is mono
3. **`alafasy_qua_full.json`** — full QUA Alafasy structure + letters (phrase re-says preserved)
4. **`alafasy_ctc_auto.json`** — mono CTC **gap-fill only** (never invents structure over QUA)

Regenerate QUA full + re-merge:

```bash
python3 tools/sync_lab/generate_qua_full_v2.py --all \
  --out tools/timing_v2/alafasy_qua_full_raw.json
python3 tools/sync_lab/merge_v2_priority.py \
  --lab-gold tools/timing_v2/alafasy_lab_gold.json \
  --ctc tools/timing_v2/alafasy_ctc_auto.json \
  --qua tools/timing_v2/alafasy_qua_full_raw.json \
  --v1-db data/quran.db \
  --out-dir tools/timing_v2
# then rebuild DB + bump QuranDatabase.DB_FILE_NAME
```

## Structure gate (must not flatten 6:10)

```bash
python3 tools/test_build_db.py   # includes V2 structure gate (6:10)
```

Hard case: Alafasy 6:10 positions must be
`1…8, 6,7,8, 9…13` (phrase re-say), not mono `1…13`.

## Lab gold unit gate (≥99%)

```bash
python3 tools/sync_lab/eval_v2_vs_lab_gold.py --require-pass
python3 tools/test_build_db.py   # includes the Lab gold gate
```

Lab gold is frozen from `tools/sync_lab/historical_manual_patches.json` via
`generate_lab_gold_v2.py`. Regenerating Lab gold requires that file + Alafasy audio hashes.

## Letter-level + audible wasl (hyper precision)

```bash
# densify letter keyframes (CTC edges) + detect wasl from energy+nūn rules
python tools/sync_lab/enrich_v2_precision.py \
  --in tools/timing_v2/alafasy_lab_gold.json \
  --out tools/timing_v2/alafasy_lab_gold.json
# same tool works on CTC rows
```

- **Letter keyframes:** CTC character edges rebased into word spans → multi-point
  wash progress per letter (not Tajweed text weights).
- **Wasl:** orthographic nūn-rule candidates + continuous energy across the
  boundary → `waslFromPrevMs` on the receiving word. V2 ink blooms only when
  audio measured a join (not text-only V1 wasl).
