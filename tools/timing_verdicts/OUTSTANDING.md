# Outstanding pipeline drift

Rows the pipeline produces but the shipped database does not carry, because no
verdict has reviewed them yet. The fail-closed delta gate withholds each of
them; this file keeps that from being silent. Remove an entry when its rows ship
with a verdict, are held with their model scores, or its cause is reverted.

**Nothing is outstanding.** `python3 tools/build_db.py --refresh-qdc-timings`
reproduces the shipped database (quran-v64) row for row.

## Resolved: Sudais re-clock after #712 (quran-v64)

c4e17836 (#712) taught `parse_alignment_payload` to read Sudais' quran-align
file past its `Crashed Command …` prefix. Sudais had never had an alignment
reference, so every row used to abstain from `rebase_qdc_clock`; a refresh then
re-clocked 6,157 rows, completed six withheld ones and changed four
topologies, and 14 Alafasy/Hani boundaries had drifted since #625. None of it
had shipped.

Every row was judged against fresh Arabic XLSR and MMS/uroman forced alignment
of its EveryAyah file (`v64-pipeline-resync.json`):

- **6,099 timestamp rows ship** (6,095 Sudais, 4 Alafasy/Hani): mean absolute
  word-start residual no worse on both models. Across the accepted Sudais rows
  it falls from 151 to 96 ms (XLSR) and from 143 to 90 ms (MMS).
- **4 Sudais topology rows ship**: both models score the new occurrence
  sequence better, and its start residual falls.
- **2 withheld Sudais rows ship** (2:25, 7:5): both models place them inside
  the 95th percentile of the accepted Sudais rows.
- **54 rows are held** in `tools/timing_holds/audit-held-rows.json`: 40 Sudais
  and 10 Alafasy/Hani rows where either model saw the starts move away — among
  them the short muqatta'at openings (42:2, 37:1, 36:22) whose clock offset
  rests on too few boundaries — and 4 completed Sudais rows (2:198, 2:223,
  13:37, 73:4) that stay withheld.
