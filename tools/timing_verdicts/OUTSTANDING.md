# Outstanding pipeline drift

Rows the pipeline now produces but the shipped database does not carry, because
no verdict has reviewed them yet. The fail-closed delta gate withholds each of
them; this file keeps that from being silent. Remove an entry when its rows ship
with a verdict or its cause is reverted.

## Sudais re-clock after #712 (measured at abdb218e, quran-v62)

`python3 tools/build_db.py --refresh-qdc-timings` on unchanged master code
differs from the shipped database in 6,181 rows:

- **Abdurrahmaan_As-Sudais_192kbps — 6,157 timestamp, 4 topology, 6 added.**
  c4e17836 (#712) taught `parse_alignment_payload` to read Sudais' quran-align
  file past its `Crashed Command …` prefix. Sudais had never had an alignment
  reference, so every row used to abstain from `rebase_qdc_clock`; a refresh
  now translates nearly every row onto the EveryAyah clock (typically +30 to
  +480 ms), completes six previously withheld rows (2:25, 2:198, 2:223, 7:5,
  13:37, 73:4) and changes four topologies (6:136, 24:31, 39:44, 71:28). The
  reference is probably right, but a whole-reciter re-clock needs the
  dual-model boundary review, not a transplant.
- **Alafasy_128kbps — 10 timestamp** (5:6, 5:48, 7:37, 9:122, 12:100, 13:16,
  17:99, 34:12, 46:4, 65:5) and **Hani_Rifai_192kbps — 4 timestamp** (4:21,
  5:72, 24:21, 49:9): single-boundary moves of the same refresh, not yet
  attributed.

The rewind-onset class (`qdc-rewind-onset-class.json`) touches 22 Sudais rows.
Those ship on the shipped clock — the rule applied to the baseline row — so the
re-clock above stays out of the database until it is reviewed on its own.
