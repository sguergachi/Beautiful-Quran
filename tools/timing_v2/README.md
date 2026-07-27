# Timing V2 generated sources

Files here are machine-generated acoustic timing artifacts consumed by
`tools/build_db.py`. They are not Timing Lab patches and must not be hand
edited per ayah.

Each accepted row contains:

- an ordered occurrence sequence of word spans;
- acoustic sub-word keyframes as `(offsetMs, progress)` inside each span;
  equal-progress pairs explicitly hold the wash across CTC blank intervals;
- the model path score and the generator’s acceptance threshold;
- pinned model revision, generator version, and source-audio SHA-256.

Malformed, incomplete, duplicate, or below-threshold rows fail the database
build. Rows that the generator abstains on are omitted; the app uses its
immutable bundled V1 row for those ayahs. V2 never reads `timing_overrides/`.

Regenerate the initial calibration slice:

```bash
source /tmp/alignlab-venv/bin/activate
python tools/sync_lab/generate_timing_v2.py \
  --surah 1 --ayah-to 7 \
  --out tools/timing_v2/alafasy_fatiha.json
python tools/build_db.py
```

The current Fātiḥah slice validates the format and runtime path. Do not call
its provisional CTC path-score gate “99% accurate.” Expansion requires an
independent ear-labeled sub-word set, calibrated abstention, and separate
accuracy/coverage reporting described in `docs/TIMING_FIRST_PRINCIPLES.md`.
