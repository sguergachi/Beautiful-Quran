# Timing overrides

> **Agents: stop.** If you are about to save a GitHub "Timings patch" JSON
> here, you are almost certainly on the wrong path. Read
> [AGENTS.md — Landing Timings Lab / GitHub timing patches](../../AGENTS.md#landing-timings-lab--github-timing-patches)
> first. Overrides are **last resort**, not the default apply step.

Correction patches produced by the in-app **Timings Lab** live here. Every
`*.json` file in this directory is applied on top of the open-dataset timings
when `python3 tools/build_db.py` runs — so a committed override is permanent:
every future DB rebuild reapplies it.

See [docs/TIMINGS_LAB.md](../../docs/TIMINGS_LAB.md) for the full workflow.

## Systematic first — overrides are the last resort

**Do not default to a one-off override for every Timings Lab / GitHub patch.**

### Anti-pattern (do not repeat)

Issue #570 (Alafasy 5:59) was first landed as a one-off file in this directory.
That was wrong: raw qdc already had the re-say, a **gap phantom** mislabeled
word 12, and a CTC **`drop` repair** had flattened the span. The correct fix
(#571) is pipeline rules + `timing_patch_cases`, with **no** override.

If the Lab/GitHub positions differ from shipped DB by **topology** (extra /
missing backtracks, skipped word indices, collapsed long spans), it is a class
bug until proven otherwise — not an override.

| Defect class | Fix where | Verify with |
|---|---|---|
| Forward spikes, isolated strays, split slivers, **non-contiguous / gap phantoms** | `clean_qdc_artifacts` in `tools/build_db.py` | `tools/timing_patch_cases/*.json` + `python3 tools/test_build_db.py` |
| Drop repair flattening a real multi-word re-say | `apply_timing_repairs` span-protect | `pipeline: erases_span_repeat` case |
| Repeat-vs-split / CTC disagreement | `tools/timing_repairs/` generator | case in `~/qasr` + rebuild repairs |
| True one-off (single boundary nudge, no structural rule) | **this directory** | ear-check + commit override; note *why* pipeline cannot fix it |

When a Lab patch reveals a **class** of bugs (same wrong topology on many
ayahs), implement the rule in the pipeline and add a
[timing_patch_cases](../timing_patch_cases/README.md) unit test whose input is
the broken shape and whose expected output is the corrected shape from the
patch. The unit test *is* the patch verification — not a manual checklist alone.

Overrides remain correct for:

* word-boundary misalignments with no reliable structural signal (e.g. one word
  stealing time from its neighbour without a backtrack artifact);
* ear-verified corrections the CTC repair generator cannot yet reproduce.

If you add an override for something the cleaner already handles, delete the
override and extend the cleaner + patch case instead.

## File shape

```json
{
  "schema": 1,
  "device": "Google/Pixel 8",
  "appVersion": "0.1",
  "notes": "Why this cannot be a clean_qdc / repair rule (required for new files).",
  "edits": [
    {
      "reciterId": 1,
      "reciterSlug": "Alafasy_128kbps",
      "surahId": 2,
      "ayah": 14,
      "segments": [[7, 6400, 8212], [8, 8212, 9016]]
    }
  ]
}
```

Each `edits[]` entry replaces (or adds) the `(reciter, surah, ayah)` row in the
`timings` table. `segments` is `[position_1based, start_ms, end_ms]`, sorted by
`start_ms`; positions may backtrack to encode repeats (the reader renders those
with the orange wash).

`reciterId` is authoritative; a mismatched `reciterSlug` warns but still
applies. Out-of-range positions fail the build rather than shipping a bad row.

## How to land a Lab / GitHub patch

1. **Classify** — structural (spikes, phantoms, false splits) vs boundary-only.
2. **Systematic path (preferred)**
   1. Fix `clean_qdc_artifacts` or regenerate timing repairs.
   2. Add `tools/timing_patch_cases/<id>.json` from the broken input + expected
      fix (see [timing_patch_cases/README.md](../timing_patch_cases/README.md)).
   3. `python3 tools/test_build_db.py` must pass.
   4. `python3 tools/build_db.py`, bump `DB_FILE_NAME`, commit.
3. **Override path (last resort)**
   1. Tap **Submit** in the Timings Lab (or **Copy patch JSON**).
   2. Save under this directory with a `notes` field explaining why no pipeline
      rule applies.
   3. `python3 tools/build_db.py`, bump `DB_FILE_NAME`, commit the DB + file.
