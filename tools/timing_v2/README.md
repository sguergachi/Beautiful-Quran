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

## Current Alafasy same-take slice

`alafasy_qua.json` is produced by `tools/sync_lab/generate_qua_timing_v2.py@1`
from pinned Qur'anic Universal Audio `v2.3.0` **only when** decoded waveform
identity matches EveryAyah (`corr ≥ 0.70`, peak margin ≥ 0.25). Different takes
abstain. This proves same-take clock transfer, not human-correct letter labels.

Approximate scale (regenerate to refresh counts):

- ~2,700 accepted ayahs / ~34k word occurrences / ~137k keyframes
- ~43% of Alafasy’s 6,236 ayahs — **coverage, not accuracy**
- includes ~321 repeat-structure rows

Regenerate:

```bash
source /tmp/alignlab-venv/bin/activate
# one surah or full reciter (slow: downloads + xcorr per ayah)
python tools/sync_lab/generate_qua_timing_v2.py --surah 1 \
  --out /tmp/alafasy_1.json
python tools/sync_lab/generate_qua_timing_v2.py --all \
  --out tools/timing_v2/alafasy_qua.json
python tools/build_db.py
# then bump QuranDatabase.DB_FILE_NAME
```

Quality gates (not 99% proof):

```bash
python tools/sync_lab/validate_timing_v2.py \
  --max-audio-rows 200 \
  --out tools/sync_lab/results/v2_validate.json
# independent CTC onset witness on a mono sample (GPU)
python tools/sync_lab/eval_v2_ctc_witness.py --sample 80 \
  --out tools/sync_lab/results/v2_ctc_witness.json
```

Do **not** call this “99% accurate.” That bar needs frozen independent ear
labels plus separate accuracy/coverage reporting in
`docs/TIMING_FIRST_PRINCIPLES.md`.
