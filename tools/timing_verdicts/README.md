# Timing verdict ledger

The shipped `quran.db` is the timing baseline. A changed row may ship only when
this ledger binds its exact before/after payload hashes to its audio evidence.
`python3 tools/test_build_db.py` compares the current branch against
`git merge-base HEAD origin/master` and fails closed for every unmatched, stale,
rejected, or incomplete verdict.

The verdict is deliberately not an auto-repair instruction: unknown evidence
means the candidate row is not shipped. For a topology change, the evidence must
record two independent acoustic model witnesses and a clear waveform veto.
The two CTC witnesses are Arabic XLSR and MMS/uroman (`--romanize` on
`tools/audit_forced_alignment.py`). Quran-align MAE is not a second CTC. For
a boundary change, both witnesses must show a Pareto non-regression. A corrected
duration may instead use `duration_tail_clip`, but it can shorten only the final
segment end; it cannot move a word start or an internal boundary.

Each verdict contains:

- `kinds`, `baselinePayloadHash`, and `candidatePayloadHash`, so approval cannot
  silently apply to a later edit;
- the exact EveryAyah `audioSha256` and a durable in-repository artifact path;
- a method-specific evidence block.

This enforces *no known regression*, not an impossible claim of absolute
acoustic certainty. Ambiguous candidate changes retain the baseline until a
separate, reproducible review supplies the missing evidence.

## When the evidence says no

The gate is fail-closed, so a rejected row must not ship — but the pipeline is
deterministic and will keep producing it. `tools/timing_holds/audit-held-rows.json`
records those rows and `apply_audit_holds` restores the payload the baseline
already ships, which takes them out of the delta entirely. A hold can only
restore a shipped row, never invent one; `check_audit_holds` proves every held
row matches the merge-base database byte for byte, and each entry carries the
model scores that rejected it.

## Choosing the metric

`viterbiLogProbabilityPerFrame` scores the *fixed transcript*, so it only moves
when the stored occurrence sequence does. It is the right witness for a
**topology** change and is worthless for a **boundary** one — across the 336
timestamp-only rows in the quran-v54 rebuild it was identical on both models
for every single row. Boundary changes are judged instead on the mean absolute
start residual between the stored boundary and the model's forced boundary,
which is the Pareto non-regression this document asks for.
