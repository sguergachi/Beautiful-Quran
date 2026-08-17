# TimingEngine V1.5 patch cases

Regression fixtures for **systematic** timing fixes. Each `*.json` file is one
defect (or a shape that must survive) derived from a Timings Lab report, ear
check, or GitHub timings patch.

`python3 tools/test_build_db.py` loads every file here and asserts the pipeline
step named in `pipeline` turns `input_*` into `expected_*`.

## Policy

**Timing patches are fixed systematically, then locked with a unit test.**

Agents landing a GitHub `Timings patch` issue must follow the full checklist in
[AGENTS.md](../../AGENTS.md#landing-timings-lab--github-timing-patches)
(invariant #8). This directory is where that checklist's unit tests live.

1. **Classify** the defect (forward spike, non-contiguous / gap phantom, false
   split, repair that flattens a span, boundary misalign, clock/coverage
   failure, …). Diff Lab expected
   vs raw qdc vs post-clean vs post-repair — not only vs shipped DB.
2. **Prefer a pipeline fix** that covers the *class*:
   - structural qdc noise → `clean_qdc_artifacts` in `tools/build_db.py`
   - drop repair erasing a multi-word re-say → `erases_span_repeat` / span-protect
   - repair erasing a peer re-say while fixing elsewhere → per-position
     `preserve_peer_repeats`
   - stale full-row repair timing → `rebase_timing_repair`
   - repair row on a translated source clock → `clock_shifted_repair`
   - qdc row on the wrong MP3 clock → `qdc_clock_rebase`
   - restore invents a flush same-word pair (gap < 300 ms) → `invented_flush_restore`
   - independently supported local boundary → `boundary_repair`
   - irreducible verified topology → a typed operation in
     `tools/timing_corrections/`
   - repeat-vs-split / CTC disagreement → `tools/timing_repairs/` generator
   - whole-ayah encoded lead-in → `tools/detect_audio_onsets.py`
   - complete phrase re-say whose last fade alone overruns the file →
     `complete_repeat_topology`
   - incomplete or physically impossible row → `finalize_timing_rows`
3. **Add a case here** whose `input_*` is the broken shape and `expected_*` is
   the corrected shape (from the Lab patch, ASR/ear, or the intended clean
   positions). The case *is* the patch verification.
4. A local `tools/timing_overrides/` JSON may reproduce the report, but delete
   it before committing. CI rejects shipped one-off overrides.

Do **not** land per-ayah overrides, and do not merge a cleaner change without
a case under this directory.

## Case shape

```json
{
  "id": "noncontiguous-alafasy-5-54",
  "label": "Alafasy 5:54 phantom word 4 at re-say onset",
  "refs": ["PR #567", "ear report"],
  "pipeline": "clean_qdc_artifacts",
  "notes": "optional free text",
  "input_positions": [1, 2, 3, "...", 4, 21, 22, 23, 24],
  "expected_positions": [1, 2, 3, "...", 21, 22, 23, 24]
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | stable slug; should match the filename stem |
| `label` | yes | one-line human name (shown on failure) |
| `pipeline` | yes | `clean_qdc_artifacts`, `timing_correction`, `preserve_peer_repeats`, `erases_span_repeat`, `invented_flush_restore`, `rebase_timing_repair`, `clock_shifted_repair`, `complete_repeat_topology`, `qdc_clock_rebase`, `boundary_repair`, `leading_silence_offset`, `recover_negative_opening`, or `adjust_qdc_segments` |
| `input_positions` | * | 1-based word indices in time order (synthetic equal durations) |
| `expected_positions` | * | positions after the pipeline step |
| `input_segments` | * | full `[[pos, start_ms, end_ms], …]` when times matter |
| `expected_segments` | * | full segments after the step (compared when present) |
| `recover_singleton_gap` | for `clean_qdc_artifacts` | opt into the last-resort singleton-gap coverage candidate |
| `repair_positions` / `repair_segments` | for repair pipelines | candidate repair row |
| `occurrence` | for `boundary_repair` | 1-based repeated occurrence to replace without changing its peer |
| `clock_offset_ms` | for `clock_shifted_repair` | source-to-MP3 translation applied before merge |
| `expected_erases` | for `erases_span_repeat` | bool — must the guard refuse this repair? |
| `correction_positions` | for `timing_correction` | positions named by the typed operation |
| `audio_onset_ms` | for `leading_silence_offset` | measured first sustained voice onset |
| `n_words` | for `complete_repeat_topology` | canonical word count; source must cover it completely |
| `exact_file_clock` | no | false when word 2 proves the complete row predates voice |
| `reference_segments` | for `qdc_clock_rebase` | quran-align boundaries on the everyayah MP3 clock |
| `audio_duration_ms` | no | measured recording length; a translation past it is refused |
| `refs` | no | issue/PR/doc pointers |
| `notes` | no | why this shape is real / what must not regress |

\* For `clean_qdc_artifacts`, provide either the `*_positions` pair **or** the
`*_segments` pair (or both; when both are present, segments are authoritative).

## Adding a case from a Timings Lab / GitHub patch

1. Extract the **broken** position sequence (or segments) from the shipped/raw
   qdc row *before* your fix.
2. Extract the **expected** sequence from the Lab patch (or from the
   post-cleaner result you ear-verified).
3. Drop a new `tools/timing_patch_cases/<id>.json`.
4. Implement or adjust the systematic rule until `python3 tools/test_build_db.py`
   is green.
5. Rebuild `data/quran.db` and bump `DB_FILE_NAME` only if content changes.

## Run

```bash
python3 tools/test_build_db.py
```

No network, no CTC cache, no device.
