# Frozen independent timing labels

This directory holds **ear-label evaluation data only**. The V2 generator never
reads it. Labels measure the pipeline; they are not timing patches and must
never be copied into `timing_overrides/` or V2 artifacts.

**Protocol:** [`docs/V2_99_PROTOCOL.md`](../../../docs/V2_99_PROTOCOL.md)

## Freeze membership first (done once)

```bash
python tools/sync_lab/freeze_label_sample.py
# → independent_labels/frozen_sample_v1.json  (120 ayahs, test/validation)
```

Do not change which ayahs are in `test` after threshold tuning begins.

## Label (waveform, blind to V2)

```bash
python tools/sync_lab/label_onsets.py --list
python tools/sync_lab/label_onsets.py --export-csv /tmp/label_queue.csv
python tools/sync_lab/label_onsets.py --surah 1 --ayah 1 \
  --segments '[[1,60,600],[2,600,1380],...]'
```

Rules: spectrogram/waveform cursor only; no live ear tapping; never seed from
V2 starts; double-label ≥15% of the test split.

`segments` are occurrence-ordered and may repeat positions. Starts are true
audio-file word onsets. Smallest set for a Wilson-stable 99% within-100 ms
claim: ~120 ayahs / ~1.2–1.5k onsets (~3 hours careful labeling).

## Evaluate

```bash
python tools/sync_lab/eval_v2_onsets.py \
  --labels tools/sync_lab/independent_labels/frozen_sample_v1.json \
  --split test \
  --out /tmp/v2-independent-test.json
```

A 99% claim requires the held-out gates in `docs/V2_99_PROTOCOL.md` and
`docs/TIMING_FIRST_PRINCIPLES.md` §5. Historical Lab edits remain regression
evidence only.
