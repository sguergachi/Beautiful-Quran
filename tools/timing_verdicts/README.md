# Timing verdict ledger

The shipped `quran.db` is the timing baseline. A changed row may ship only when
this ledger binds its exact before/after payload hashes to its audio evidence.
`python3 tools/test_build_db.py` compares the current branch against
`git merge-base HEAD origin/master` and fails closed for every unmatched, stale,
rejected, or incomplete verdict.

The verdict is deliberately not an auto-repair instruction: unknown evidence
means the candidate row is not shipped. For a topology change, the evidence must
record two independent acoustic model witnesses and a clear waveform veto. For
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
