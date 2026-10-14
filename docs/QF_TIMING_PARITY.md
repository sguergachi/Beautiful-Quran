# Authenticated repeat-timing transition

The authenticated Quran Foundation API does expose repeat-aware word timing.
The 2026-10-10 production audit fetched all six chapter-recitation snapshots
used by our repeat sources. Three matched the pinned legacy inputs exactly;
three differed. The transition now uses authenticated Content Sync for the six
QF timing voices, with canonical normalization and acoustic review before runtime
publication. Android and web retain complete reviewed resources in separate
offline caches. The release database and static web assets exclude these timings.

## Supported authenticated paths

QF documents word timing through both the
[chapter audio endpoint](https://api-docs.quran.foundation/docs/content_apis_versioned/4.0.0/chapter-reciter-audio-file/)
and [Content Sync](https://api-docs.quran.foundation/docs/tutorials/content-sync/getting-started/):

```text
GET /content/api/v4/chapter_recitations/{reciter_id}/{chapter_number}?segments=true
GET /content/api/v4/resources/snapshots/chapter_recitations/{reciter_id}
```

Use `chapter_recitations` for an independently addressable chapter source;
legacy `recitations` is a different resource identity. A snapshot includes
`chapter_audio_file` and `audio_segment` records. Word segments retain their
ordered occurrences, including backward jumps and consecutive same-word
occurrences. Their timestamps use the chapter clock, so comparisons subtract
the verse's `timestamp_from` without sorting or deduplicating segments.

The Worker serves the existing word/QCF resources and five fixed verse
supplements, plus six complete reviewed timing views at
`/api/timings/<appReciterId>`. Native timing sources/checkpoints and reviewed
canonical views are separate private resources. No raw snapshots, canonical
timing packs, authentication tokens, or private checkpoints are committed.

## Observed source differences

All six snapshots used schema version 1 and sync sequence 541039. Compare the
[machine-readable report](timing-audits/qf-2026-10-10.json) for snapshot hashes,
pinned and candidate source hashes, counts, and every changed verse key.

| Reciter | App ID | QF chapter ID | Compared rows | Occurrence sequence changes | Timestamp-only changes |
|---|---:|---:|---:|---:|---:|
| Alafasy | 1 | 7 | 6,236 | 394 | 546 |
| Husary | 2 | 6 | 6,236 | 1 | 4 |
| AbdulBasit murattal | 3 | 2 | 6,236 | 0 | 0 |
| Minshawi murattal | 4 | 9 | 6,236 | 1 | 0 |
| Sudais | 5 | 3 | 6,236 | 0 | 0 |
| Hani ar-Rifai | 7 | 5 | 6,235 | 0 | 0 |
| Total | | | 37,415 | 396 | 550 |

There were no added or missing assembled source rows. Hani's snapshot has
6,236 `audio_segment` records but one has no usable segments, matching the
pinned source's 6,235 rows. Ten unfinished segment labels across AbdulBasit,
Sudais, and Hani lack a start or end time; the audit skips them exactly as
`load_qdc_timings` does and reports their count. It never invents boundaries.

These are **source differences**, not acoustic verdicts. Raw repeats can be
real re-says or label artifacts. Matching a raw source also does not prove
parity with the shipped timings, which include cleaning, canonical word
alignment, quran-align clock fitting, typed corrections, CTC repairs, onset
offsets, and reviewed holds.

## Canonical regression review

The first isolated replay stopped at an existing Alafasy correction:

```text
Alafasy_128kbps.json: 2:229: one_utterance expected one [16, 17, 16, 17] loop, found 0
```

The authenticated input labels the old false phrase loops as same-word pairs.
The old assertions remain active on the legacy profile. An obsolete correction
or boundary repair retires on the authenticated profile only when its exact
input-segment hash matches the reviewed source. This preserves the reason for
the guard while allowing the new occurrence sequence to reach acoustic review.

The complete normalized candidate differed from the private v65 reference in
853 rows. Arabic XLSR and independent MMS/uroman witnesses scored every row;
both runs completed without errors. Changed topologies require improvement on
both models and sufficient confidence for inserted/relabelled occurrences.
Every removed occurrence must also have baseline label probability below 0.5
on both models; a confident baseline occurrence on either model retains the row.
Boundary changes use mean absolute start residual on both models. End-only
changes are held because start residuals cannot justify them. Duration and
model-voiced/PCM checks veto premature cutoffs.

| Canonical change | Accepted | Exact baseline retained |
|---|---:|---:|
| Occurrence sequence | 32 | 153 |
| Timing boundaries | 509 | 158 |
| Added row | 0 | 1 |
| Total | 541 | 312 |

All holds restore the complete baseline payload, including audio onset. Alafasy
37:152 remains withheld. Alafasy 2:229 retains its prior reviewed row because
the near-threshold candidate conflicts with the maintainer's #594 boundary;
no new ear verdict is claimed. 2:235 also retains its prior reviewed row.
Of 40 initially accepted rows with fewer occurrences, 19 now remain at baseline.
The other 21 meet the symmetric confidence gate. One additional occurrence
replacement is held by that gate. See
[`qf-source-transition.json`](../tools/timing_verdicts/qf-source-transition.json)
for per-row hashes, metrics, vetoes, and verdicts. Full model and PCM evidence is
retained privately with digest provenance; the ledger contains no timing rows.

The immutable review reference is `quran-v65.db`, SHA-256
`3489bd89a91177d2701a2bbc3e8e414d957e308a539c6ec71122a568015f7c8d`.
The reviewed private output has 37,411 QF timing rows. Public `quran-v67.db`
contains only the 43,648 unchanged independent timing rows. The exact removed
and retained corpora are hash-bound in
[`qf-timing-transition.json`](../data/qf-timing-transition.json); the ordinary
acoustic delta gate still applies to every independent change. Highlight and
ink engines remain unchanged.

The manifest also pins the v65 Git commit. Legacy audit holds are compared
against that exact removed corpus after the public transition; its complete
corpus digest must match before any archived row can serve as the reference.
Holds for independent reciters still compare against the merge-base asset.

## Repeat the source gate

Fetch authenticated Content Sync snapshots into private local scratch as
`snapshot-2.json`, `snapshot-3.json`, `snapshot-5.json`, `snapshot-6.json`,
`snapshot-7.json`, and `snapshot-9.json`. Keep credentials on the server.
Use an existing cache of the hash-pinned `qdc_<id>.json` reference inputs:

```bash
python3 tools/test_qf_timing_parity.py
python3 tools/qf_timing_parity.py \
  --snapshots /path/to/private/snapshots \
  --qdc-cache /path/to/existing/tools/.cache \
  --report /path/to/parity-report.json
```

Exit 0 means all assembled source rows match. Exit 1 means source changes
need review; this audit returned 1 for 946 changed rows. Exit 2 means the
input, snapshot identity/schema, or pinned reference cache is invalid. The
gate compares every ordered word occurrence and millisecond boundary, so a
flattened repeat cannot pass merely because every word position still exists.
It does not download data or modify a database. CI runs its offline regressions.

## Maintained offline delivery

The maintainer confirms that written QF permission for the bundled legacy
timing dataset has not been obtained. The acknowledgements and public policy
pages do not imply a request was sent or a grant was obtained. Attribution now
identifies authenticated Content Sync and the maintained offline timing cache.

The [Developer Terms, updated 2026-10-04](https://api-docs.quran.foundation/legal/developer-terms/)
require Content Sync for offline copies of resources available through it,
with an update at least every seven days when QF is reachable and promptly on
reconnection after an outage. The Content Sync storage exception does not
authorize a prepackaged database or build-time timing bundle. Switching the
build to an authenticated URL would not resolve distribution of a bundled
database. This transition instead removes the QF timing corpora from release
assets and maintains private sources through native Content Sync. The existing
Python normalization and reviewed repeat policy are preserved. Operational
commands, acceptance/withdrawal behavior, cache freshness, and the first-download
requirement are documented in [QF_CONTENT_SYNC.md](QF_CONTENT_SYNC.md).

## Live transition verification — 2026-10-10

The production Worker serves reviewed packs for app reciters 1, 2, 3, 4, 5,
and 7. Every returned occurrence and audio onset matches the accepted private
database: 37,411 rows in total. A native unchanged-source refresh advanced
`sourceSyncedAtMs` while retaining the exact canonical revision. The existing
word/QCF bootstrap also passes through the new control service. Private routes
reject unauthenticated requests, and the production origin restriction remains
in place.

The timing transition was exercised in a release APK on a clean Android emulator
with a 192 MB Java heap. Its first download matched all six reviewed corpora. After disabling
connectivity and stopping the process, all six cache states and every timing
row remained unchanged. Opening Alafasy 2:229 and tapping word 16 sought to
20,310 ms; cached playback showed the orange wash on its second occurrence
with no active network. This checked rendering and cache delivery, rather than
whether that occurrence was acoustically correct. The October 12 review restores
the prior 2:229 timing and boundary.

The web client downloaded all six packs into a clean IndexedDB timing store.
A new cache instance restored their exact revisions with provider requests
failing and an eight-day outage simulated. The live reader recognized the
second word-16 occurrence as a repeat, sought to its 21,660 ms start, and
retained the chapter's four-segment basmalah preface. That 2:229 repeat was
subsequently held; these initial checks establish delivery and rendering only.

All 1,325 Android JVM tests and the release build passed; all 669 web tests and
the production build passed. The 104 timing patch cases, 12 flattened re-say
checks, 9 source parity tests, 11 authenticated build tests, 4 runtime export
tests, and 3 release-exclusion tests passed. All 34 Worker tests passed, covering native
pagination, withdrawal, shared revocation, delayed streams, and competing
publications. Inspection of the actual APK and web output confirms zero bundled
QF timing rows and unchanged independent timing data.

## Opus review follow-up — 2026-10-12

The independent Opus 5.5 review identified an overly broad destructive access
latch and a conflict with the previously reviewed Alafasy 2:229 boundary.
Temporary OAuth failures, throttling, and bare or unrelated edge 403 responses
now preserve cached content. Persistent revocation requires a confirmed client
rejection, a content 401 after token retry, or QF's documented authorization-error
JSON. See the [QF error contract](https://api-docs.quran.foundation/docs/content_apis_versioned/4.0.0/resources-sync/).

The additional baseline-occurrence confidence audit holds 20 more topology
changes, alongside 2:229. The rebuilt private database differs from the initial
published corpus in exactly those 21 rows, each restored completely to v65.
All 853 verdicts can be reproduced from the preserved model outputs using the
private archive's `REVIEW_FOLLOWUP.md`; original evidence remains preserved.

The release builder now writes the committed provenance text directly. The v67
asset packages that direct rebuild with a new extraction version and fingerprint;
every table, including provenance and all 43,648 timing rows, equals v66. Pending
timing review is reported through structured Worker logs, with a persisted age
and repeated maintenance reporting after 24 hours. Authenticated snapshots are
checked before publication; the cache explicitly includes 312 retained baseline
corrections from the legacy-derived v65 reference. Those rows are maintained
runtime corrections and are excluded from release assets, along with all other
QF timings. Their provenance is documented in [QF_CONTENT_SYNC.md](QF_CONTENT_SYNC.md).

The follow-up review also restored word/QCF checkpoint recovery: HTTP 400/404
remain visible for sync requests, with the existing `410 resync_required`
contract intact. Snapshot errors, throttling, and edge blocks remain temporary
failures. The 47 Worker tests cover both proxy paths, subsequent bootstrap,
cache retention, confirmed revocation, and the earlier publication races.

Worker version `74565328-7081-4966-9090-78f8542a1ebd` serves the corrected packs.
A fresh live comparison verified every occurrence and onset in all 37,411 rows
against the reviewed private database, every revision and normalizer hash,
and all 312 holds against the exact v65 baseline. No voice is awaiting review.
Word/QCF bootstrap, origin restrictions, and private-route authorization also
passed. The two public rebuilds are byte-identical, including provenance.

Ink Lab's chapter timings, detected results, recordings, and active traces now
follow the selected timing generation. Withdrawal/revocation purges affected
voices even while the Lab is hidden; delayed reads, decoding, and detection
cannot restore an old result. Independent audio/reference caches and peer
voices remain available. Five regression tests exercise the actual computation
through replacement, withdrawal, global purge, and delayed read/decode races.

The final Opus 5.5 review closed both follow-up findings and found no remaining
blockers. The full 1,396 Android JVM tests and `assembleRelease` pass, including
all 13 Tarji pulse tests. The 669 web tests and production build also pass, as
do the Python suites above with 12 authenticated build tests. Inspection of
the final 177,089,186-byte APK and static web timing files confirms zero bundled
QF timing rows and the unchanged independent corpus.
