# Authenticated repeat-timing source audit

The authenticated Quran Foundation API does expose repeat-aware word timing.
The 2026-10-10 production audit fetched all six chapter-recitation snapshots
used by our repeat sources. Three matched the pinned legacy inputs exactly;
three differed. **The authenticated source has not replaced the shipped
timings.** The offline pipeline rejected a stale correction before it could
produce a complete candidate.

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

The existing production Worker serves word/QCF resources and five fixed verse
supplements. It does not currently serve timing resources. This audit used a
private, expiring Worker version preview with the existing server-held
credentials; it did not change production traffic or expose credentials to
either app. No raw snapshots, authentication tokens, or private sync
checkpoints are committed.

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

## Canonical replay blocked

An isolated replay of the existing pipeline with the authenticated inputs
stopped at the existing Alafasy correction:

```text
Alafasy_128kbps.json: 2:229: one_utterance expected one [16, 17, 16, 17] loop, found 0
```

The source has changed the shape that this reviewed correction expects. Do
not skip or weaken that assertion to complete the rebuild. Rebase the
correction against the new input and its acoustic evidence, then replay the
full corpus and review all deltas under the existing
[timing verdict gate](../tools/timing_verdicts/README.md). A changed row needs
the required dual-model evidence; unaccepted deltas stay at their shipped
baseline and are recorded in the outstanding audit.

The audit used revision `40b9e9b1` and left `data/quran.db` unchanged:
`quran-v65.db`, SHA-256
`3489bd89a91177d2701a2bbc3e8e414d957e308a539c6ec71122a568015f7c8d`.
No source pins, timing corrections, repairs, holds, database version, or
reader engines changed.

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

## Offline distribution remains separate

The maintainer confirms that written QF permission for the bundled legacy
timing dataset has not been obtained. The acknowledgements and public policy
pages now state that fact without implying a request was sent.

The [Developer Terms, updated 2026-10-04](https://api-docs.quran.foundation/legal/developer-terms/)
require Content Sync for offline copies of resources available through it,
with an update at least every seven days when QF is reachable and promptly on
reconnection after an outage. The Content Sync storage exception does not
authorize a prepackaged database or build-time timing bundle. Switching the
build to an authenticated URL therefore does not resolve distribution of
the bundled database. A maintained Content Sync integration still needs the
canonical timing pipeline and reviewed repeat behavior preserved.
