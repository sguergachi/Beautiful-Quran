# Frozen independent timing labels

This directory is reserved for independently ear-labeled evaluation data. The
V2 generator never reads it. Labels measure the pipeline; they are not timing
patches and must never be copied into `timing_overrides/` or V2 artifacts.

Freeze validation/test membership before tuning a model or threshold:

```json
{
  "schema": 1,
  "independent": true,
  "edits": [{
    "reciterId": 1,
    "reciterSlug": "Alafasy_128kbps",
    "surahId": 2,
    "ayah": 214,
    "split": "test",
    "audioSha256": "64 lowercase hex characters",
    "segments": [[1, 120, 580], [2, 580, 1420]]
  }]
}
```

`segments` are occurrence-ordered and may repeat positions. Starts are true
audio-file word onsets, not renderer-compensated values. Label at least 300
random ayah×reciter rows across three reciters, plus a separate repeat-heavy
challenge set; double-label at least 10%. Keep annotator disagreements rather
than silently averaging them.

Evaluate without feeding labels back to generation:

```bash
/tmp/alignlab-venv/bin/python tools/sync_lab/eval_v2_onsets.py \
  --labels tools/sync_lab/independent_labels/frozen-v1.json \
  --split test \
  --out /tmp/v2-independent-test.json
```

A 99% claim requires the held-out gates in
`docs/TIMING_FIRST_PRINCIPLES.md`; historical Lab edits remain regression
evidence only.
