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

A row may also be moved bodily from its source's window clock onto the clock of
the file the app streams. That is not a boundary judgement — no witness is asked
for one — so `file_clock_rebase` is checked mechanically instead: one shared
`clockOffsetMs` on every start and every internal boundary, the same positions in
the same order, a result that is still strictly increasing, and a final fade
clipped by at most `MAX_REBASE_TAIL_CLIP_MS` to the file's own
`measuredDurationMs`. Only the opening may sit off the shared offset, and only
in one of the two ways the build can put it there, each of which has to be named
because they bound the value from opposite sides: `openingStartMs` restores it
later from the reference alignment (the source clamps a negative first start to
zero), and `measuredOnsetMs` pins it back to the measured start of the voice in
`tools/audio_onsets/`. Anything else is a re-timing and needs two witnesses.

Rows the build would change but that no evidence can accept keep the shipped
baseline, and are listed in [OUTSTANDING.md](OUTSTANDING.md).

Each verdict contains:

- `kinds`, `baselinePayloadHash`, and `candidatePayloadHash`, so approval cannot
  silently apply to a later edit;
- the exact EveryAyah `audioSha256` and a durable in-repository artifact path;
- a method-specific evidence block.

This enforces *no known regression*, not an impossible claim of absolute
acoustic certainty. Ambiguous candidate changes retain the baseline until a
separate, reproducible review supplies the missing evidence.
