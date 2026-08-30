# Quran Foundation approval and Content Sync plan

Beautiful Quran is applying for **content-only** access to the Quran Foundation
(QF) authenticated API. It will not request user scopes, create QF accounts, or
send bookmarks, notes, reading position, search history, or other user data to
QF.

This is the implementation and evidence checklist for the application. A
checked item is implemented in this repository; it does not mean QF has
approved the project or licensed content obtained from a legacy endpoint.

Official references:

- [Developer Terms](https://api-docs.quran.foundation/legal/developer-terms/)
- [Developer Privacy Requirements](https://api-docs.quran.foundation/legal/developer-privacy/)
- [API quickstart](https://api-docs.quran.foundation/docs/quickstart/)
- [Content Sync client flow](https://api-docs.quran.foundation/docs/tutorials/content-sync/client-flow/)
- [Offline cache patterns](https://api-docs.quran.foundation/docs/tutorials/content-sync/offline-cache-patterns/)

## Application facts and public URLs

Use these values in the application:

| Field | Value |
|---|---|
| Project | Beautiful Quran — independent, free, ad-free, open source |
| Requested scope | `content` only |
| Client URL | `https://sguergachi.github.io/Beautiful-Quran/` |
| Logo URL | `https://sguergachi.github.io/Beautiful-Quran/app/apple-touch-icon.png` |
| Privacy Policy | `https://sguergachi.github.io/Beautiful-Quran/privacy.html` |
| Terms of Service | `https://sguergachi.github.io/Beautiful-Quran/terms.html` |
| Source | `https://github.com/sguergachi/Beautiful-Quran` |
| Contact | `sguergachi@gmail.com` and the public GitHub issue tracker |

Describe the use case accurately: the app is an offline-first Quran reader
whose Arabic words light up with recitation audio. It needs approved Quran and
repeat-aware word-timing content, stores that content locally, checks for
changes at least every seven days, and applies corrections atomically.

## Current data flow

```text
Android / web reader
  ├─ committed quran.db
  │    └─ independent Quran text/morphology + quran-align timing fallback
  ├─ mushafs:1 device cache
  │    └─ direct unauthenticated Quran.com chapter API today
  └─ recitations:* device cache
       └─ Content Sync facade → canonical timing normalizer
            └─ legacy QDC today / QF OAuth provider after approval
```

The current word/QCF clients make fixed-corpus unauthenticated requests directly
to Quran.com and never send a QF secret or any user state. Their transport is
replaceable with the QF-shaped Content Sync path after approval. Both caches
read local rows first, refresh after six days, reject runtime rows after seven
days, and retry when connectivity returns. Quran.com-derived gloss/QCF fields
are withheld when absent or stale; they are not present in the public database.
The backend runs the same cleaner, clock rebase, corrections, repairs, and
physical finalizer that historically produced the reader rows. Its earlier
Alafasy audit predates the restored `quran-v55` baseline and is no longer
sufficient release evidence. The facade URL must remain unset until all six
reciters pass a new full-corpus comparison against that baseline.

The transitional direct API and timing provider are engineering controls—not
permission to use a legacy endpoint and not an authenticated QF integration.
Their use must be disclosed to QF rather than represented as already approved.

## Already implemented

- [x] Public Privacy Policy, Terms, client page, logo, source, and contact URLs.
- [x] Clear independent-project disclaimer; no claim of QF, Quran.com, or
  QuranReflect endorsement.
- [x] Browser-level `notranslate` protection and element-level protection for
  rendered Arabic Quran words.
- [x] A separate Android SQLite cache for future QF content and sync state.
- [x] A separate web IndexedDB cache; QF rows are never written into the
  committed sql.js database.
- [x] Sync-domain support for relative cursors, all pages, snapshots, upserts,
  row/resource deletion, invalidation markers, per-filter checkpoints, atomic
  application, and a full termination purge.
- [x] The next token is committed only after all pages and snapshots succeed.
- [x] A tested seven-day on-device freshness predicate.
- [x] Local-first reader adapters on Android and web. They install refreshed
  boundaries only while playback is quiet, so a sync cannot move the active
  karaoke word underneath the listener.
- [x] Launch/resource-open bootstrap and incremental refresh, six-day early
  revalidation, network-restored retry, single-flight refresh, and verified
  bundled fallback on first use, offline use, upstream failure, or expiry.
- [x] Developer Mode reports cache state, next refresh, seven-day expiry,
  last failure, API calls made by the last successful refresh, and exact calls
  made during the current app/browser session.
- [x] A shared freshness clock: the facade reports the normalized snapshot's
  actual age and clients preserve it, so backend and device TTLs cannot stack
  into a 14-day window. Partial nonempty snapshots below 6,000 ayahs fail
  before replacing a complete cache.
- [x] A dependency-free transitional backend with a fixed endpoint allowlist,
  no arbitrary proxy URL, single-flight disk cache, atomic writes, integrity
  hashes, six-day revalidation, seven-day fail-closed behavior, conditional
  responses, bounded upstream traffic, redacted logs, and protected purge.
- [x] A stable backend facade matching the QF Content Sync shapes used by the
  clients. The legacy provider and future authenticated provider sit behind
  that boundary, so approval does not require an Android/web protocol change.
- [x] The canonical timing normalizer accepts both the legacy response and the
  authenticated chapter-reciter `audio_file.timestamps` response documented by
  QF.
- [x] `quran-v56.db` contains zero Quran.com-derived word gloss,
  transliteration, QCF glyph/page/line/span metadata, ayah-page, or QDC timing
  values. Its identity `qcf_span_end = position` defaults carry no provider data.
  CI checks every field, provenance, version, and fingerprint.
- [x] `mushafs:1` supplies exactly 77,429 normalized word records to Android
  and web through the same atomic six-day/seven-day cache contract. The direct
  adapters verify the 604 QCF page-font codepoint runs before installation.
- [x] A missing/expired first bootstrap is gated behind the entrance cover and
  reports 0–114 chapter progress on Android and web. Android full-snapshot
  comparisons mutate only changed cache rows; authenticated syncs already use
  row-level upsert/delete deltas after their bootstrap token.
- [x] CI tests the backend, Python timing pipeline, Android cache, web cache,
  and database provenance/freshness gates.

## Questions that require written QF confirmation

Include these in the application or follow-up email. Code cannot resolve them:

- [ ] Does the approved `recitations` Content Sync resource include the exact
  word segments and repeat topology needed for karaoke-style highlighting?
- [ ] Is transitional bundled and runtime use of timing obtained from the
  unauthenticated legacy QDC endpoint acceptable while credentials are pending?
  Ask whether QF expects removal or any history cleanup.
- [ ] Are transformations limited to timing cleanup, validation, indexing, and
  local storage acceptable, while the Quran text itself remains unchanged?
- [ ] Does QF want Beautiful Quran to use its upstream Content Sync snapshots,
  the authenticated chapter-reciter endpoint with `segments=true`, or another
  approved resource for repeat-aware chapter timing?
- [ ] For this independent project, are email and GitHub Issues acceptable
  contact methods in place of a public home/business postal address?
- [ ] Do QF's sensitive-religious-data consent requirements apply to notes and
  bookmarks that never leave the user's device? Until QF answers, treat them as
  applicable before releasing the QF integration.
- [ ] Confirm which QF attribution, branding, and source-link treatment they
  want in the app's settings/about surface.

## Work required after credentials are issued

### Backend authentication and isolation

- [ ] Choose and document a production host. Require TLS 1.2 or later,
  encryption at rest, a private persistent volume, restricted operator access,
  edge rate limits, backups, and a data-processing agreement where applicable.
- [ ] Name the host and link its privacy policy in `docs/privacy.html` before it
  receives production traffic.
- [ ] Store QF client ID/secret and the cache purge token only in the host's
  secret manager. Keep separate prelive and production credentials, rotate them
  on a documented cadence, and verify no secret reaches Android, web assets,
  Git history, CI output, logs, or error bodies.
- [ ] Replace the legacy fetch adapter with QF's backend-only OAuth2
  client-credentials flow using only the approved `content` scope. Cache access
  tokens for their lifetime, send the required QF client/auth headers, and on
  `401` refresh once and retry once—never loop.
- [ ] Allow only approved QF hosts and relative pagination/snapshot paths.
  Preserve the existing SSRF, response-size, timeout, and request-rate bounds.

### Content Sync correctness

- [ ] Map QF's real JSON schema into the existing sync-domain types; do not
  finalize upstream ID mappings before prelive access is available. The
  documented chapter-reciter response shape is already supported.
- [x] Keep independent sync state for each exact resource/filter combination.
- [x] Bootstrap without a token, follow every relative page cursor, download
  every referenced snapshot, and apply the entire exchange in one transaction.
- [x] Make change application idempotent. Implement and test row
  upserts/deletes, resource deletes, invalidations, snapshots, interrupted
  pagination, duplicate delivery, and token rollback.
- [x] Retry a due or missing resource when connectivity is restored. Current
  checkpoints make zero network calls, and single-flight prevents duplicate
  launch/resource/network triggers.
- [ ] Handle authenticated Content Sync recovery codes explicitly:
  `resync_required` must discard the affected token and bootstrap again;
  filter/cursor mismatch errors must restart only with the canonical filter and
  returned cursor contract. The legacy adapter cannot exercise these responses.
- [ ] Add bounded retry/backoff only if production operations require retries
  beyond launch, resource-open, and network-restored events. Clients currently
  avoid a background failure loop and wait for one of those concrete triggers.
- [x] Never label content older than seven days as current. Expired runtime
  rows are withheld and the independently licensed fallback remains readable.
- [ ] Alert before content reaches the seven-day ceiling and on repeated sync,
  snapshot, purge, authentication, or integrity failures.

### Reader and database migration

- [x] Create the reader repository adapter that reads fresh content rows from the
  separate cache while retaining independently licensed Arabic text, morphology,
  lexicon, dictionary, and other non-QF data.
- [x] Generate provider-origin content on the runtime cache path rather than committing
  a newly generated QF database to Git.
- [x] Remove QDC-derived compatibility rows from the committed public database;
  quran-align remains the independent monotonic offline timing fallback.
- [ ] Run and retain a new full-corpus parity report for all six runtime
  reciters against `quran-v55.db` before production deployment. The earlier
  Alafasy result used the superseded baseline and does not satisfy this gate.
- [ ] Replace the direct legacy word/QCF transport and the transitional timing
  provider after QF migration is accepted. Keep the cache schema and purge path
  so legacy and QF rows can be deleted safely.
- [ ] Do not rewrite Git history merely to hide old data; do so only if QF
  requires it and after a separate, explicit migration plan.

### Privacy, consent, and product behavior

- [ ] Add separate, affirmative consent before notes or other sensitive
  religious information is enabled; it may not be bundled into general Terms.
  Existing installs currently default notes on, so this is not complete.
- [ ] Make that consent explicitly cover Android backup, or exclude bookmarks
  and annotations from cloud backup before the QF integration. Today Android
  backup is enabled and only the rebuildable audio cache is excluded.
- [ ] Provide an equally easy way to withdraw consent and delete local notes,
  bookmarks, and QF cache data. If user accounts/scopes are ever added, also
  implement access, correction, token revocation, 30-day primary deletion, and
  90-day backup deletion.
- [ ] Keep bookmarks, notes, reading position, queries, and playback history
  out of QF requests and backend logs. Avoid IP retention at the application
  layer; accurately disclose unavoidable host/network processing.
- [ ] Update Privacy and Terms with the final host/processors, purposes,
  retention, international-transfer safeguards, contact method, effective
  date, and in-app notice before the data flow changes.

### Operations and audit evidence

- [ ] Write a one-page data-flow/threat model covering credentials, QF content,
  local sensitive data, host access, cache deletion, and trust boundaries.
- [ ] Document the security owner, access review, secret rotation, patching,
  backup/restore, monitoring, and incident runbook. Suspected QF API incidents
  must be reported to QF within 24 hours.
- [ ] Keep redacted audit logs for authentication, sync result/filter, cache
  age, purge, and operator changes without content, secrets, IP addresses, or
  user reading activity. Define and disclose a short retention period.
- [ ] Test credential revocation and termination: stop QF requests, purge QF
  content and tokens from the backend and devices, and produce evidence that
  deletion completed.
- [ ] Track QF API/deprecation notices and perform a quarterly review of scopes,
  processors, privacy text, cache age, alerts, and access permissions.

## Evidence to send or keep ready for QF

- This document and `backend/README.md` as the architecture/control summary.
- Test output for backend cache expiry/purge and Android atomic sync behavior.
- Screenshots/links for the client, Privacy Policy, Terms, attribution/about
  page, explicit-consent flow, stale-content state, and deletion controls.
- A redacted deployment diagram naming the host, secret manager, encrypted
  volume, logs, monitors, and operator roles.
- A sample redacted sync audit record and the 24-hour incident/termination
  runbooks.
- A provenance table identifying which database fields originate with QF and
  which remain under independent licenses.

## Approval status today

The repository now demonstrates the intended cache/sync architecture and its
public database excludes all audited Quran.com-derived fields. It is ready to
support an honest application, but it is **not ready for authenticated production
traffic**. The hard blockers are QF approval, final upstream resource/ID
mapping, a selected secure host, the OAuth provider adapter, final privacy
processor disclosure, network-restored scheduling/monitoring, full six-reciter
parity evidence, and the notes consent/deletion flow.

It is also not a legal conclusion that transitional legacy access is allowed.
That permission question remains explicit for QF. If QF asks for legacy access
to stop, disable both direct word/QCF traffic and the timing provider, then
purge their caches. The clients would keep quran-align timing fallback and
withhold Quran.com word/QCF fields.
