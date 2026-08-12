# Quran Foundation Content Sync readiness

Beautiful Quran is applying for **content-only** access to the Quran Foundation
(QF) authenticated Content API. It will not request user scopes, create QF
accounts, or send bookmarks, notes, reading position, or other user data to QF.

This document is the implementation gate: no QF content may be fetched or
shipped until every applicable item below is complete.

## What QF permits

QF permits offline storage for content available through its Content Sync API,
including `recitations`, if the app performs its next sync at least every seven
days and applies all available changes. The local database is therefore a
readable cache, not a permanent source of truth.

- [Developer Terms — caching and offline sync](https://api-docs.quran.foundation/legal/developer-terms/)
- [Content Sync — supported offline resources](https://api-docs.quran.foundation/docs/tutorials/content-sync/getting-started/)
- [Offline cache patterns](https://api-docs.quran.foundation/docs/tutorials/content-sync/offline-cache-patterns/)

## Intended design

```text
QF Content API
  └─ authenticated sync client
       └─ versioned local cache + sync state
            ├─ reader reads immediately while offline
            └─ sync on install, launch, reconnect, and before seven days elapse
```

The sync state is separate from cached content and is keyed by the exact
resource filter. Changes must be applied atomically and idempotently. A full
snapshot replaces the affected resource in one transaction; the new sync token
is stored only after every page and snapshot has applied successfully.

## Implemented foundation

- A separate on-device SQLite cache keeps QF data and its sync checkpoint out
  of the packaged reader database.
- The sync core follows relative cursors, fetches every page and snapshot, and
  writes rows plus the new token in one transaction. A failed exchange leaves
  the previous checkpoint intact.
- Snapshot replacement, row upserts/deletes, resource deletes, a seven-day
  freshness predicate, and a full cache-purge operation are implemented and
  unit-tested.

## Before first production request

- [ ] QF approves this application and the requested `content` scope.
- [ ] Confirm that the required recitation resource includes the
  repeat-aware `segments` data used for word highlighting.
- [ ] Replace the legacy anonymous QDC importer. It must not be used as the
  authenticated API migration path.
- [ ] Keep client credentials out of Android, web bundles, Git history, and
  public CI logs. Use QF's approved authentication architecture and rotate
  secrets on a defined schedule.
- [ ] Implement the authenticated HTTP/JSON client for only the approved
  resource filters and attach it to the existing sync core.
- [ ] Run sync on first use, app launch, and network restoration. Record a
  successful sync locally and prevent a cache older than seven
  days from being represented as current. Retry when the device next has
  network access; surface an honest stale-content state if it cannot sync.
- [ ] Apply `ROW_DELETE`, `RESOURCE_DELETE`, invalidations, and snapshots so
  removed or corrected content is removed or replaced locally.
- [ ] Remove QF-origin raw content and derived timing rows from public source
  artifacts unless QF expressly confirms that distribution is permitted.
- [ ] Wire the existing termination/revocation purge operation to an
  administrative kill switch or credential-revocation flow.
- [ ] Ask QF to confirm that email and the public GitHub issue tracker are an
  acceptable contact channel for this independent open-source, content-only
  project; publish any contact detail QF requires before enabling the integration.
- [ ] Add the separate, affirmative religious-data consent required before
  enabling locally stored notes or any other sensitive religious information.
- [ ] Document the service architecture, access controls, secret rotation,
  incident response owner, and redacted audit logs. Report any suspected QF
  API security incident to QF within 24 hours.

## Current status

The committed database and current app do **not** access the authenticated QF
API, do not contain QF credentials, and do not implement Content Sync. The app
is offline-first today, but it is not yet an authenticated QF Content API
integration. This distinction is intentional: it avoids claiming compliance
before the seven-day sync and security controls exist.

## Database transition

Keep `data/quran.db` for the current release. Once QF approves the integration,
do not replace it blindly with one remote database: Content Sync currently
supports only translations, tafsirs, recitations, and articles. The app's
morphology, lexicons, layout metadata, and other independently licensed sources
need their own provenance and may remain packaged if their licenses allow it.

Instead, move only QF-origin fields (including any approved recitation timing
records) into the separate QF cache. The reader then needs an adapter that
prefers a fresh QF row and withholds an expired QF row. This avoids publishing
QF raw content in Git while keeping the open-source app usable with its
independently licensed data.
