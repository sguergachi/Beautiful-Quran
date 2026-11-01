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

Alafasy 28:9 adds a separate, bounded proof for a disconnected interior orphan:
every confident emitting frame must remain on an adjacent retained owner,
with identical model/audio emissions and unchanged retained starts. Raw
confidence is recomputed, and connected repeat components remain protected.
The optional connected-noon spelling is an explicit audit target on both
hypotheses, with canonical scores retained alongside it; Quran text and the
default audit do not change. See the [complete review](../../docs/quality-reviews/TIMING_ALAFASY_28_9_2026-10-28.md).

An entirely new reciter uses the separate `tools/timing_sources/` corpus gate.
That gate accepts only added rows, requires the complete expected row count,
and binds every row key and payload hash to pinned source archives. It cannot
approve a timestamp or topology change to a voice that already ships; those
remain subject to the per-row evidence above.

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

## Reviewed runtime candidate, not a publication

`unspoken-rewind-onset-class.json` binds the Alafasy 5:2 correction from #873
to the currently delivered payload and the exact implemented candidate. Both
models were run locally; their revision, audio hash, scores and removed-word
confidence are recorded with the evidence.

`maintainer-word-split-class.json` records the later human adjudication of
47:4 as one held word and the exact merged candidate. The subsequent
[complete corpus review](../../docs/quality-reviews/TIMING_WORD_SPLIT_CORPUS_2026-10-17.md)
adds raw retained-frame evidence: XLSR's confident source label is explained
by speech retained on identical frames. It now passes the bounded same-word
exception while MMS passes the ordinary low-confidence gate. The exact
runtime candidate is `accept`; authenticated-source promotion remains pending.
The maintainer's report remains separate from model evidence and is not an
agent ear verdict. The ordinary peer guard remains active before generated
merges. The other 70 accepted runtime candidates are recorded in
`../timing_evidence/word-split-corpus-candidates.verdict.json`, separately from
the authenticated source ledger and earlier active database verdicts.

The [later Pixel 10 review](../timing_evidence/device-lab-alafasy-5-2-boundaries.verdict.json)
compares that report with the accepted local topology-only candidate. Both new inference runs
lower the row's mean start residual, but the opening word is truncated and
word 2's individual start residual worsens. The existing waveform veto holds
the complete boundary proposal. Word 16 moves closer to the models while
remaining 1,933 ms late; its exact onset needs separate review. The full Lab
proposal is retained in a regression fixture, without changing production rows
or weakening the gate. This is review evidence for an alternative candidate;
it does not replace the active accepted topology-only verdict for the same row.

This candidate verdict does not replace the authenticated source-transition
ledger. Before publication, replay the private authenticated snapshots, reconcile
every delta against the reviewed runtime baseline, and bind those actual output
payloads to the source-profile ledger. A stale verdict must continue to stop
publication. An APK rebuild cannot deliver this runtime correction.
