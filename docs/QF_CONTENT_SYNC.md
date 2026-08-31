# Quran Foundation application and content plan

Beautiful Quran is an independent, free, ad-free, open-source Quran reader.
It has no accounts, analytics, developer-operated backend, or client secrets.
This document records the exact current data flow, what is already implemented,
and what requires written Quran Foundation (QF) approval.

A checked engineering item is not evidence of permission. The project must not
describe its legacy API access as an approved authenticated QF integration.

Official references:

- [Developer Terms](https://api-docs.quran.foundation/legal/developer-terms/)
- [Developer Privacy Requirements](https://api-docs.quran.foundation/legal/developer-privacy/)
- [API quickstart](https://api-docs.quran.foundation/docs/quickstart/)
- [Manual authentication](https://api-docs.quran.foundation/docs/quickstart/manual-authentication/)
- [Legacy API migration](https://api-docs.quran.foundation/docs/quickstart/migration/)
- [Content Sync getting started](https://api-docs.quran.foundation/docs/tutorials/content-sync/getting-started/)
- [Content Sync client flow](https://api-docs.quran.foundation/docs/tutorials/content-sync/client-flow/)
- [Offline cache patterns](https://api-docs.quran.foundation/docs/tutorials/content-sync/offline-cache-patterns/)

## Application URLs

| Field | Value |
|---|---|
| Project | Beautiful Quran |
| Client URL | `https://sguergachi.github.io/Beautiful-Quran/` |
| Logo URL | `https://sguergachi.github.io/Beautiful-Quran/app/apple-touch-icon.png` |
| Privacy Policy | `https://sguergachi.github.io/Beautiful-Quran/privacy.html` |
| Terms of Service | `https://sguergachi.github.io/Beautiful-Quran/terms.html` |
| Source | `https://github.com/sguergachi/Beautiful-Quran` |
| Contact | `sguergachi@gmail.com` and GitHub Issues |

Suggested application description:

> Beautiful Quran is an offline-first Quran reader whose Arabic words light up
> in sync with recitation audio. Android and web share one reviewed local
> database. Word gloss, transliteration, and QCF layout use an atomic client
> cache that refreshes after six days, expires after seven days, and retries
> when internet returns. Repeat-aware timings are normalized and verified
> offline and distributed only through app releases. We request content-only
> access and written guidance for this no-backend open-source architecture.

## Current data flow

```text
offline maintainer build
  quran-align clock + legacy QDC repeat topology
       -> clean, rebase, repair, validate
       -> bundled quran.db -> Android + web

Android / web at runtime
  legacy unauthenticated Quran.com chapter API
       -> fixed 114-chapter word/QCF snapshot
       -> atomic local cache (refresh day 6; unusable after day 7)
```

The bundled database contains Quran text, morphology, an open quran-align timing
clock/fallback, and QDC-derived repeat topology. It contains no Quran.com word
gloss, transliteration, QCF glyph, page, line, span, or ayah-page values. The
repeat rows never change between releases and require no client analysis.

The runtime word/QCF request sends no account, secret, reading position,
bookmark, note, search query, or app-generated user identifier. Standard HTTPS
connection data is still visible to the service receiving the request.

## Implemented engineering controls

- [x] Android and web use the same `quran.db` and the same repeat-aware rows.
- [x] Raw QDC is never downloaded or repaired on a user's device.
- [x] A normal `tools/build_db.py` run preserves the reviewed repeat table
  byte-for-byte. Refreshing QDC requires explicit `--refresh-qdc-timings`; the
  weaker one-pass build requires explicit `--quran-align-only`.
- [x] Full-corpus build gates enforce coverage, ordered unique starts,
  non-overlap, positive spans, audio onset, recording duration, accepted timing
  deltas, and exact known-repeat fixtures.
- [x] Hani 5:2 explicitly pins both passes of words 19–22 and the handoff to 23.
- [x] Database content changes require a version bump and SHA-256 fingerprint.
- [x] Word/QCF content is stored separately from the committed database on both
  platforms.
- [x] The first word/QCF bootstrap validates all 77,429 words and 604 QCF page
  runs before one atomic commit. The canonical/Quran.com token-boundary
  mismatches are aligned as groups, so their glosses cannot shift onto later
  words.
- [x] The opening cover remains locked while a missing or expired cache is checked,
  reports chapter progress, warms the complete local database, and then opens.
- [x] Fresh caches make zero API calls. Refresh starts after six days; content
  is withheld after seven days; offline failures retry when connectivity returns.
- [x] A corrupt fresh cache is withheld and replaced in the background instead
  of remaining unusable until its next scheduled refresh.
- [x] Runtime cache schema v2 forces one clean bootstrap when token-boundary
  mapping semantics change; older fresh snapshots are never reinterpreted.
- [x] Android compares complete legacy snapshots inside one transaction and
  mutates only added, changed, or removed rows.
- [x] Developer Mode shows phase, age, next refresh, expiry, last failure, API
  calls this launch, and calls made by the last successful refresh.
- [x] Developer Mode can force the same atomic refresh while content is still
  fresh; the legacy adapter replaces its snapshot today and the authenticated
  adapter will issue an incremental request from the stored sync checkpoint.
- [x] A short Android toast appears only after a successful atomic refresh.
- [x] Privacy Policy, Terms, attribution, project URL, logo, and contact are public.
- [x] No QF credential or secret exists in Android, web, Git, or CI.

## Approval questions and blockers

These need written answers before claiming compliance or releasing this data
flow as an approved QF integration:

1. May Beautiful Quran transform legacy QDC timing responses offline and
   redistribute the resulting repeat-aware timing rows in its public Git
   repository, APK, and static web database? This includes storage beyond seven
   days and updates only through app releases.
2. If not, may QF provide an approved downloadable/versioned timing snapshot
   suitable for the same app-release workflow?
3. Does the approved content product contain all required fields: word gloss,
   transliteration, QCF V2 glyph/page/line layout, ayah page, and repeat-aware
   per-word recitation segments for the six mapped reciters?
4. May this no-backend project continue direct legacy word/QCF requests using
   the implemented six-day/seven-day cache while migration is arranged?
5. Is there a supported content authentication method for public Android and
   browser clients that does not expose a client secret?
6. Which attribution, branding, and source links does QF require in-app?
7. Are email and GitHub Issues acceptable public contact methods for an
   individual open-source maintainer without a business address?
8. Do QF privacy rules require separate consent for notes/bookmarks that never
   leave the device, and must Android backup be disabled for those fields?
9. Separately from API approval, where can the project obtain written
   redistribution permission or an official license for the bundled KFGQPC
   Hafs and QCF V2 font files?

The authenticated Content API documentation uses a client ID and client secret
and says secrets must remain server-side. Therefore it is not a drop-in URL
swap for a public Android/browser app. Unless QF supplies a client-safe content
flow or grants an exception, authenticated Content API use requires a backend.
The repository intentionally has no placeholder backend and must not pretend
the unauthenticated endpoint is authenticated.

## Work after QF responds

### If QF approves the no-backend architecture

- [ ] Save the written permission and its exact scope with the release records.
- [ ] Map approved resource IDs and JSON fields using prelive responses.
- [ ] Replace the legacy word/QCF transport only if QF supplies a client-safe
  method; keep the existing cache database and atomic sync contract.
- [ ] Rebuild repeat timings from the approved snapshot, compare every row
  against the current database, resolve all rejected deltas, bump the DB version
  and fingerprint, and publish an app update.
- [ ] Implement any QF-provided version/change check needed between app releases.
- [ ] Update Privacy, Terms, attribution, provenance, and effective dates with
  the approved source and exact retention agreement.
- [ ] Add any required consent/withdrawal and local content-deletion controls.

### If QF requires authenticated server-side access

- [ ] Decide explicitly whether this project will operate a backend. Without
  one, the authenticated integration cannot ship under the documented secret
  model.
- [ ] If accepted, document the host and processor, TLS, encryption at rest,
  secret manager, least-privilege access, rate limits, monitoring, backups,
  purge, incident reporting, and privacy-policy changes before traffic begins.
- [ ] Keep the QF client ID/secret server-side, use only approved content scope,
  cache access tokens for their lifetime, and retry a `401` at most once after
  refreshing the token.
- [ ] Replace the transitional combined `mushafs:1` row mapper with the exact
  approved multi-resource filter. Current QF documentation separates Mushaf
  positioned words, word translations, and word transliterations into
  `mushafs`, `word_by_word_translations`, and
  `word_by_word_transliterations`; those snapshots must be joined by stable
  word identity and validated as one complete reader view before publication.
- [ ] Confirm the approved resource IDs in prelive. The current documentation
  examples use word-translation resource `85` and production transliteration
  resource `60`; examples are not approval or a substitute for the response
  attached to this application.
- [ ] Implement Content Sync bootstrap, relative cursors, snapshots, atomic
  upsert/delete application, opaque checkpoints, `resync_required`, termination
  purge, and seven-day freshness without stacking server and client TTLs.
- [ ] Add integration tests against QF prelive for pagination, token rollback,
  duplicate delivery, invalidation, partial failure, expiry, and revocation.

## Release decision

Technically, the repository is ready for the proposed offline experience: the
repeat dataset is bundled and verified, and word/QCF content uses the seven-day
client cache. Legally/contractually, public redistribution of the QDC-derived
repeat dataset remains blocked on written QF permission. If permission is denied
or conditioned on a backend the project will not operate, ship the explicit
quran-align-only database instead and disable the unsupported fields.
