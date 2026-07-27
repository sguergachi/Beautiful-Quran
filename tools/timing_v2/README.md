# Timing V2 generated sources

Files here are machine-generated acoustic timing artifacts consumed by
`tools/build_db.py`. They are not Timing Lab patches and must not be hand
edited per ayah.

Each accepted row contains:

- an ordered occurrence sequence of word spans;
- acoustic sub-word keyframes as `(offsetMs, progress)` inside each span;
  equal-progress pairs explicitly hold the wash across CTC blank intervals;
- the model path score / clock correlation and the generator’s acceptance threshold;
- pinned model revision, generator version, and source-audio SHA-256.

Malformed, incomplete, duplicate, or below-threshold rows fail the database
build. Rows that the generator abstains on are omitted; the app uses its
immutable bundled V1 row for those ayahs. V2 never reads `timing_overrides/`.

## Current Alafasy same-take slice (energy-gated)

`alafasy_qua.json` =

1. `generate_qua_timing_v2.py@1` — QUA `v2.3.0` letters **only if** EveryAyah
   waveform identity matches (`corr ≥ 0.70`, peak margin ≥ 0.25)
2. `gate_timing_v2.py@1` — energy-snap starts ±80 ms + drop dead-zone onsets

Different takes abstain. Dead-zone onsets abstain. This is **not** ear gold.

Approx scale (see `results/v2_99_path_snapshot.json`):

- ~2,549 accepted ayahs (~**40.9%** of Alafasy) after energy/silence gate
- Post-pause objective gold: **med ~21 ms / p90 ~53 ms / ~98% ≤100 ms**
- Dual-witness CTC maxAbs≤60 (sample): **100% ≤60 ms** on a small high-agreement subset

Regenerate:

```bash
source /tmp/alignlab-venv/bin/activate
python tools/sync_lab/generate_qua_timing_v2.py --all --out /tmp/alafasy_raw.json
python tools/sync_lab/gate_timing_v2.py --in /tmp/alafasy_raw.json \
  --ctc-max-abs-ms -1 --out tools/timing_v2/alafasy_qua.json
python tools/build_db.py
# bump QuranDatabase.DB_FILE_NAME
```

**99% claim protocol:** [`docs/V2_99_PROTOCOL.md`](../../docs/V2_99_PROTOCOL.md)

```bash
python tools/sync_lab/freeze_label_sample.py   # once
python tools/sync_lab/label_onsets.py --list   # fill labels (~3h)
python tools/sync_lab/eval_v2_against_labels.py --split test
python tools/sync_lab/eval_v2_postpause_gold.py
python tools/sync_lab/gate_timing_v2.py --ctc-max-abs-ms 60  # tighter subset
```
