# Timing V2 generated sources (automated)

Machine-generated acoustic timings from **audio + models only**. No human
ear labels. Consumed by `tools/build_db.py`. Do not hand-edit per ayah.

## Ship bar (automated)

See [`docs/V2_99_PROTOCOL.md`](../../docs/V2_99_PROTOCOL.md):

On accepted rows, post-pause energy gold must satisfy:

| Metric | Bar | Current Alafasy CTC |
|---|---|---|
| Median \|error\| | ≤ 25 ms | **14 ms** |
| p90 \|error\| | ≤ 60 ms | **56 ms** |
| Within 100 ms | ≥ 99% | **100%** (n=1046) |
| Coverage | stated separately | **~89.9%** (5604 / 6236) |

## Files

| File | Generator | Role |
|---|---|---|
| `alafasy_ctc_auto.json` | `generate_timing_v2.py@3` via `generate_timing_v2_auto.py` | Mono CTC FA on everyayah |
| `alafasy_qua_repeats.json` | `generate_qua_timing_v2.py@1` | Same-take QUA **repeats only** |

## Regenerate

```bash
source /tmp/alignlab-venv/bin/activate

# ~40 min full Alafasy on RTX 3080
python tools/sync_lab/generate_timing_v2_auto.py \
  --min-path-score -1.5 \
  --min-onset-match-frac 0.0 \
  --out /tmp/alafasy_ctc_raw.json \
  --checkpoint tools/sync_lab/results/v2_auto_full.jsonl

# optional: drop rows that miss post-pause energy by >100ms
# (applied in the shipped artifact)

python tools/sync_lab/eval_v2_postpause_gold.py \
  --payload tools/timing_v2/alafasy_ctc_auto.json \
  --out tools/sync_lab/results/v2_postpause_gold_ctc_filtered.json

python tools/build_db.py
# bump QuranDatabase.DB_FILE_NAME
```
