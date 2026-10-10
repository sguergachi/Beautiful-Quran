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
Boundary changes use mean absolute start residual on both models. End-only
changes are held because start residuals cannot justify them. Duration and
model-voiced/PCM checks veto premature cutoffs.

| Canonical change | Accepted | Exact baseline retained |
|---|---:|---:|
| Occurrence sequence | 53 | 132 |
| Timing boundaries | 509 | 158 |
| Added row | 0 | 1 |
| Total | 562 | 291 |

All holds restore the complete baseline payload, including audio onset. Alafasy
37:152 remains withheld. Alafasy 2:229 accepts the real same-word repeat;
2:235 retains its prior reviewed row after the confidence veto. See
[`qf-source-transition.json`](../tools/timing_verdicts/qf-source-transition.json)
for per-row hashes, metrics, vetoes, and verdicts. Full model and PCM evidence is
retained privately with digest provenance; the ledger contains no timing rows.

The immutable review reference is `quran-v65.db`, SHA-256
`3489bd89a91177d2701a2bbc3e8e414d957e308a539c6ec71122a568015f7c8d`.
The reviewed private output has 37,411 QF timing rows. Public `quran-v66.db`
contains only the 43,648 unchanged independent timing rows. The exact removed
and retained corpora are hash-bound in
[`qf-timing-transition.json`](../data/qf-timing-transition.json); the ordinary
acoustic delta gate still applies to every independent change. Highlight and
ink engines remain unchanged.

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
with no active network.

The web client downloaded all six packs into a clean IndexedDB timing store.
A new cache instance restored their exact revisions with provider requests
failing and an eight-day outage simulated. The live reader recognized the
second word-16 occurrence as a repeat, sought to its 21,660 ms start, and
retained the chapter's four-segment basmalah preface.

All 1,325 Android JVM tests and the release build passed; all 669 web tests and
the production build passed. The 104 timing patch cases, 12 flattened re-say
checks, 9 source parity tests, 11 authenticated build tests, 4 runtime export
tests, and 3 release-exclusion tests passed. All 34 Worker tests passed, covering native
pagination, withdrawal, shared revocation, delayed streams, and competing
publications. Inspection of the actual APK and web output confirms zero bundled
QF timing rows and unchanged independent timing data.
