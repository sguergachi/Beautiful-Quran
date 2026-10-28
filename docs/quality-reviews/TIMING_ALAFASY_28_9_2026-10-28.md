# Alafasy 28:9 — repeat chain expanding backward

The reader began its repeat correctly at word 10, then suddenly washed words
6–9 in orange. This was reproducible from delivered timings, rather than a
random animation reset: at 20,720 ms an old repair changed the active position
from 11 to 6. `repeatStart` correctly followed that bad label back to 6.

## Source trace and correction

The authenticated source already has repeat positions `10,11,12,13,14,15`.
The pinned legacy source contains `10,11,6,12,13,14,15`; the existing
non-contiguous-component cleaner removes its isolated 6. Both source paths
therefore arrive at the same cleaned topology. A legacy `unsplit` repair then
reintroduced 6 while attempting to merge the unrelated `16,16` pair. Peer
protection restored that pair, leaving the stray label in the published row.

`reject_reintroduced_orphans` runs after peer protection. It uses the existing
component adjudicator, and intervenes only when removing the repair's
orphans recovers the source's complete position sequence, with no gap repair.
For an interior insertion between consecutive owners, it retains the other
repair boundaries and returns the orphan interval to the preceding owner.
Otherwise it retains the cleaned source. Other structural repairs and genuine
same-word or connected phrase repeats retain their existing policy.

In 28:9, word 11 now owns 20,430–21,530 ms; word 12 retains its delivered
21,530 ms onset. The cleaner alone would start word 12 at 20,720 ms, so merely
skipping the old repair would introduce an unrelated early onset.

The separate `16,16` defect is a false lead. Both acoustic models place the
supported word 16 at 28,136 ms. A merge retaining its first 27,120 ms onset
would still start the word early. The existing typed false-lead operation
instead returns 27,120–28,090 ms to word 15 and keeps word 16 at
28,090–28,740 ms. Its exact source pair is pinned, and the operation runs after
repairs. Quran-align begins word 16 near the first label, so the ordinary
independent-onset rule cannot reach this case. This is an explicit acoustic
exception, not an inference from duplicate topology alone.

Only these two labels disappear. Every retained word start, unrelated end,
audio-onset value and audio interval is preserved. The repeat remains 10–15
throughout; word 16 is new speech and releases the chain. No renderer,
English owner, wash clock or animation policy changes.

## Acoustic evidence and the retained sound

Both models prefer the complete final candidate using the default canonical
spelling as well as an optional connected-noon acoustic spelling:

| Fixed transcript | XLSR before → after | MMS before → after |
|---|---|---|
| Canonical | −0.258500 → −0.238530 | −0.069659 → −0.055211 |
| Connected final noon | −0.252972 → −0.225214 | −0.066698 → −0.049088 |

These are Viterbi log probabilities per frame, not boundary-error metrics.
Each competing sequence shares exactly the same neural emissions, recording
hash and model revision. The canonical scores remain recorded alongside the
alternative spelling; they are not replaced or hidden.

The initial canonical removal gate correctly held the orphan: XLSR assigned
its conditional `لي` label mean confidence 0.572602. The held-word merge
exception cannot approve deletion of a different word, and remains unchanged.
Inspection showed that only its ي frames were confident. In connected speech,
the final silent noon of أَن assimilates into the following ي; the independent
cached greedy decoder also gives `أي` at 20.52–20.78 seconds. The optional
audit spelling applies the same final-noon rule to both competing transcripts.
It changes no Quran text, word position or default audit target. Its scope is
final silent noon, not a general phonetic transcription or pause detector.
See the [Noon/Tanween teaching chart](https://download.understandquran.com/fileadmin/user_upload/extras/tajweed/Tajweed_Rules.pdf).

The new disconnected-orphan proof verifies that XLSR's confident ي emissions
at frames 1037 and 1038 (probabilities 0.718186 and 0.999618) remain on word 11
at those identical frames and token IDs. The orphan's only unexplained frame,
its supposed ل at frame 1026, has probability 0.000001426. MMS's removed-label
confidence stays below the ordinary 0.5 gate. The extra word-16 label also
stays below that gate on both models.

This proof requires surgical deletions with unchanged retained starts, exact
adjacent windows, valid raw frame probabilities and matching neural emissions.
A confident deletion must be an isolated interior repeat component bracketed
by consecutive retained owners. Every confident phone must survive on a
supported adjacent owner. Connected repeats, first-pass words, unrelated
boundary edits, stale evidence and any unexplained confident frame fail closed.
The original confidence threshold and the exact held-word exception remain.
Their history was checked in `3541b9209`; the cleaner and peer guards were
checked in `78c57a90c` and `73d57d79f`.

FFmpeg decodes the exact recording to 32,118 ms. The final 32,065 ms timing
end is retained. Removed-label intervals extend the preceding owners, with no
retained end shortened, so there is no voiced-tail cut or lost audio interval.
This is model/frame evidence; the agent did not claim manual listening.

## Corpus coverage and remaining work

The fresh delivery capture contains **81,059 rows across thirteen voices**.
The disconnected-component predicate scans every delivered row and every
committed repair, with no correction or hold exclusions. It finds exactly one
delivered case and one old repair containing that class: Alafasy 28:9.
The other voices and the complete genuine repeat controls are retained.

The separate false-lead audit inventories **all 955 adjacent same-position
pairs**. It reuses exact whole-row evidence for 942 pairs and runs fresh XLSR
and MMS inference for thirteen current rows: nine screened leads, one new
6:145 pair and three whole rows changed since the previous review. All fresh
inference completed without unresolved errors. Fresh inference rejects merging
the genuine 6:145 pair on both models; it remains a negative control.

Dispositions are **946 retained, one accepted (28:9), and eight held pending
complete authenticated pipeline review**: Alafasy 2:58, 7:45, 7:140, 12:107,
18:18, 25:3, 29:33 and 37:150. The eight have promising acoustic hypotheses,
but no publication is implied. Their complete pipeline interactions and any
other changed boundaries still require review. This follow-up class promotion
is explicitly partial and recorded in `tools/timing_verdicts/OUTSTANDING.md`.

The report retains every pair's disposition, all source revisions, exact
payload/recording hashes, model revisions, raw proof and private report hashes:
`tools/timing_evidence/alafasy-28-9-repeat-review.json`. Genuine-repeat controls
include Alafasy 3:21 and Hani 4:4; the earlier orphan fix in Alafasy 5:54 remains
unchanged. The open/closed issue search returned all 31 matching timing issues
with no truncation and no additional 28:9 report.

The complete authenticated rebuild accepts 75 historical/current changes and
holds 318 at its pinned baseline. Compared with fresh delivery, exactly 28:9
changes and all **81,058 other rows remain identical**. No bundled database
asset changes, so no asset-version bump applies. Publication and a fresh public
read must be verified separately; installing an APK cannot invalidate an
already-fresh timing cache.

## Publication check

The reviewed views were published through `sync_qf_timings.py --publish`.
A fresh public capture matches the complete reviewed output in all 81,059
rows and differs from the pre-publication capture only in Alafasy 28:9.
The Alafasy payload revision is
`c583d81a3e99ee2b816983caaf25b4ba691ca246c4298a450a0bc48576d18912`.
The genuine-repeat controls and all eight pending false-lead candidates remain
unchanged. Existing phones can fetch it immediately with Settings → Developer
→ **Force Quran cache update**; the normal timing-cache revalidation also
obtains it when due. No local timing override is injected into the client.

Validation: 122 timing patch cases; the forced-alignment, orphan-ownership,
repair integration, existing word-split, authenticated-build and publication
helper regressions; 1,735 JVM tests (zero failures, one skipped); release R8
assembly. The Kotlin regression samples the chain at its former 20,720 ms jump,
across repeated reads and seeks, and at the transition to new word 16.
