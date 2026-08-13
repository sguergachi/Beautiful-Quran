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
Released Android/web readers
  ├─ read the committed quran.db (including legacy QDC-derived timing rows)
  └─ stream recitation audio from EveryAyah

Optional transitional build path (not connected to a released client)
  └─ tools/build_db.py
       └─ bounded Beautiful Quran cache service
            └─ legacy unauthenticated api.quran.com QDC endpoint

Approved target
  └─ Android/web reader
       └─ Beautiful Quran backend (no QF secret in a client)
            └─ QF OAuth client-credentials + Content Sync API
       └─ separate, atomic on-device QF cache
```

The transitional service mirrors the seven-day freshness ceiling, but that is
an engineering control—not permission to use the legacy endpoint and not an
authenticated Content Sync implementation. The legacy source and committed
database must be disclosed to QF rather than represented as already approved.

## Already implemented

- [x] Public Privacy Policy, Terms, client page, logo, source, and contact URLs.
- [x] Clear independent-project disclaimer; no claim of QF, Quran.com, or
  QuranReflect endorsement.
- [x] Browser-level `notranslate` protection and element-level protection for
  rendered Arabic Quran words.
- [x] A separate Android SQLite cache for future QF content and sync state.
- [x] Sync-domain support for relative cursors, all pages, snapshots, upserts,
  row/resource deletion, invalidation markers, per-filter checkpoints, atomic
  application, and a full termination purge.
- [x] The next token is committed only after all pages and snapshots succeed.
- [x] A tested seven-day on-device freshness predicate.
- [x] A dependency-free transitional backend with a fixed endpoint allowlist,
  no arbitrary proxy URL, single-flight disk cache, atomic writes, integrity
  hashes, six-day revalidation, seven-day fail-closed behavior, conditional
  responses, bounded upstream traffic, redacted logs, and protected purge.
- [x] CI tests the transitional service and the existing Android sync core.
- [x] The database builder can opt into the cache service with
  `BQ_QDC_CACHE_BASE_URL`; the released app remains unchanged for now.

## Questions that require written QF confirmation

Include these in the application or follow-up email. Code cannot resolve them:

- [ ] Does the approved `recitations` Content Sync resource include the exact
  word segments and repeat topology needed for karaoke-style highlighting?
- [ ] May the current legacy-QDC-derived timing rows remain in the public
  repository and existing releases while migration is underway? If not, ask
  whether removing them from future releases is sufficient or whether QF
  requires a repository-history purge.
- [ ] Are transformations limited to timing cleanup, validation, indexing, and
  local storage acceptable, while the Quran text itself remains unchanged?
- [ ] Is the transitional use of the unauthenticated legacy endpoint acceptable
  until credentials are issued? The absence of a login or published legacy
  terms must not be presented as affirmative permission.
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
  guess endpoint paths or response fields before prelive access is available.
- [ ] Keep independent sync state for each exact resource/filter combination.
- [ ] Bootstrap without a token, follow every relative page cursor, download
  every referenced snapshot, and apply the entire exchange in one transaction.
- [ ] Make change application idempotent. Implement and integration-test row
  upserts/deletes, resource deletes, invalidations, snapshots, interrupted
  pagination, duplicate delivery, and token rollback.
- [ ] Trigger sync on first use, app launch, network restoration, and early
  enough to complete before seven days. Add retries with bounded backoff.
- [ ] Never label content older than seven days as current. Keep it readable
  offline only if QF confirms that stale-on-failed-sync behavior; otherwise
  withhold it and explain the state to the user.
- [ ] Alert before content reaches the seven-day ceiling and on repeated sync,
  snapshot, purge, authentication, or integrity failures.

### Reader and database migration

- [ ] Create the reader repository adapter that reads fresh QF rows from the
  separate cache while retaining independently licensed morphology, layout,
  lexicon, dictionary, and other non-QF data.
- [ ] Generate QF-origin content on the client/cache path rather than committing
  a newly generated QF database to Git.
- [ ] Confirm parity for every supported reciter/chapter and the repeat-aware
  HighlightEngine before removing legacy rows from `data/quran.db`.
- [ ] Remove the direct legacy importer and transitional endpoint after the QF
  migration is accepted. Keep the purge path long enough to delete legacy and
  QF caches safely.
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

The repository is ready to make an honest application and demonstrate the
cache/sync direction, but it is **not ready to enable authenticated production
traffic**. The hard blockers are QF approval and schema access, confirmation
that repeat-aware recitation segments are available, a selected secure host,
the authenticated adapter, client scheduling/reader wiring, final privacy
processor disclosure, and the notes consent/deletion flow.

Keep `data/quran.db` for the current release as previously decided. Its
continued legacy timing use is a permission question to disclose to QF, not a
compliance conclusion this repository can make.
