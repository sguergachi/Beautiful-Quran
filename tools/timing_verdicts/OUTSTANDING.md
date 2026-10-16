# Outstanding pipeline drift

Rows the pipeline produces but the shipped database does not carry, because no
verdict has reviewed them yet. The fail-closed delta gate withholds each of
them; this file keeps that from being silent. Remove an entry when its rows ship
with a verdict, are held with their model scores, or its cause is reverted.

## Lagged qdc peer pairs (#856)

`lagged_peer_positions` stops `preserve_peer_repeats` restoring a same-word
pair the repair shows to be qdc labels running one word late before a skip.
The bundled database is unchanged. A replay of public qdc plus
`tools/timing_repairs/` changes four QF runtime rows, all from a fake pair
to the repair's topology:

| Row | Before | After |
|---|---|---|
| Alafasy 24:25 | `1,1,2,3,4,5,6,6…` | `1,2,3,4,5,6,6…` (matches the #856 Lab edit) |
| Sudais 59:5 | `…5,6,6,7,8…` | `…5,6,7,8…` (real `10,11,10,11` kept) |
| Abdul Basit 33:6 | `15,15,28,29` | complete 1–29 |
| Abdul Basit 35:10 | `5,5,15,…` | complete 1–24 |

They need dual-model verdicts from a `--qf-snapshots` build before
`sync_qf_timings.py --publish`; until then the delta gate keeps the reviewed
rows. Note for the reviewer: `qf-source-transition.json` held an earlier
24:25 candidate because both models still heard word 1 in the removed
occurrence (0.83 / 0.75). In the new row that audio stays on word 1 (the
repair runs يَوۡمَئِذٖ to 2020 ms), so a high word-1 probability there is
expected, not evidence of a re-say.

**Runtime observation, 2026-10-16:** the public Alafasy runtime resource already
matches the 24:25 edit in #856/#873, including the retained word-6 repeat.
The deployed normalizer fingerprint differs from this checkout, so the table
above describes the reproducible local pipeline delta, not proof that the
service still carries the old row. Reconcile publication provenance and the
ledger during the next authenticated replay; do not reapply the Lab row or
remove another occurrence. See the
[#873 review](../../docs/quality-reviews/TIMING_PATCH_873_2026-10-16.md).

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
