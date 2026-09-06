# Quran Foundation authenticated content integration

Beautiful Quran is an independent, free, ad-free, open-source Quran reader.
Quran Foundation (QF) has issued Production content credentials for the app.
The credentials live only in Cloudflare's encrypted Worker secret store; they
must never be added to Git, GitHub Actions, Android, or the web bundle.

Official references:

- [Developer Terms](https://api-docs.quran.foundation/legal/developer-terms/)
- [Developer Privacy Requirements](https://api-docs.quran.foundation/legal/developer-privacy/)
- [Manual authentication](https://api-docs.quran.foundation/docs/quickstart/manual-authentication/)
- [Content Sync client flow](https://api-docs.quran.foundation/docs/tutorials/content-sync/client-flow/)
- [Offline cache patterns](https://api-docs.quran.foundation/docs/tutorials/content-sync/offline-cache-patterns/)

## Public application URLs

| Field | Value |
|---|---|
| Client URL | `https://sguergachi.github.io/Beautiful-Quran/` |
| Logo URL | `https://sguergachi.github.io/Beautiful-Quran/app/apple-touch-icon.png` |
| Privacy Policy | `https://sguergachi.github.io/Beautiful-Quran/privacy.html` |
| Terms of Service | `https://sguergachi.github.io/Beautiful-Quran/terms.html` |
| Source | `https://github.com/sguergachi/Beautiful-Quran` |
| Contact | `sguergachi@gmail.com` and GitHub Issues |

## Production data flow

```text
Android / web
  -> HTTPS to beautiful-quran.sguergachi.workers.dev
     -> OAuth2 client credentials held only by Cloudflare
     -> QF authenticated Production Content API
        -> Content Sync: mushafs:1
                         word_by_word_translations:59
                         word_by_word_transliterations:60
        -> five fixed by-verse transliteration supplements
  -> atomic device cache + opaque sync checkpoint

offline build
  -> canonical Quran text + quran-align clock + reviewed repeat topology
  -> bundled quran.db (no QF word gloss, transliteration, or QCF layout)
```

The Worker is a credential boundary, not a content host. It stores no Quran
content, user data, or device cache. It caches only the short-lived OAuth token
in memory, retries one rejected token once, streams `no-store` responses, and
allows only the exact paths above. Browser requests are additionally restricted
to the GitHub Pages origin. No account, reading position, bookmark, note, search
query, analytics identifier, or device identifier is sent to the Worker or QF.

The Production transliteration snapshot currently has three missing QCF word
owners and one triplicated owner. Five small authenticated by-verse responses
(`1:1`, `2:181`, `8:6`, `9:1`, `36:52`) supply the authoritative values for
those affected verses. These ordinary API responses are purged if the cache
passes one week; the three Content Sync resources retain their permitted
offline-sync state.

## Offline cache contract

The implementation follows QF's recommended separate sync-state and cached-row
tables, with a unique `(resource group, resource ID, record type, record key)`
identity.

1. A new device bootstraps the exact three-resource filter, follows QF-provided
   relative cursors, fetches required snapshots, and fetches the five verse
   supplements.
2. The client joins QF rows to the 77,429 canonical word positions. Ten known
   canonical/QCF token-boundary differences are explicit and fail closed if QF
   changes their topology.
3. Before publication, the client verifies all canonical words, all 6,236
   verses, the 604-page QCF layout, and every contiguous page-font codepoint.
4. Android spools large snapshots to temporary cache files, parses one record
   at a time into SQLite, and materializes a typed 77,429-row reader view in the
   same transaction. The files are deleted after success or failure. Rows,
   supplements, reader view, and the final opaque checkpoint commit atomically;
   a failed request, parse, validation, or write preserves the prior cache.
5. A normal refresh starts at day six, leaving a retry margin before the
   seven-day limit. A current cache makes zero requests on launch. Network
   restoration retries a failed update automatically.
6. At day seven, QF-derived reader fields are withheld until a successful sync.
   Non-Content-Sync supplement rows are also removed from persistent storage.
7. A `410 resync_required` discards no readable data immediately: the client
   obtains a fresh bootstrap, replaces cached resources inside the commit
   transaction, validates, and only then advances the checkpoint.

The initial exchange currently uses nine requests: one sync, three snapshots,
and five supplements. An unchanged refresh uses six: one incremental sync and
five supplements. QF invalidations add only the affected resource snapshots;
row changes use QF's native upsert/delete deltas. Identical supplement rows are
detected without rebuilding the reader view. Developer Mode displays live
progress, current phase, update/refresh/expiry times, errors, calls this launch,
and calls made by the last successful refresh. Android shows a toast only after
the atomic commit succeeds.

## Implemented compliance controls

- [x] Production QF client credentials are stored only as Cloudflare secrets.
- [x] Clients contain only the public Worker URL and never receive an OAuth
  access token, client ID, or client secret.
- [x] Only the minimum `content` scope and a fixed read-only endpoint allowlist
  are used; there is no QF user login or user-data scope.
- [x] QF content is displayed only in the reader and is not sold, sublicensed,
  exposed as raw data, indexed, used for advertising, or used to train models.
- [x] `quran.db`, the APK, Git source, and the Pages artifact contain no QF word
  gloss, transliteration, QCF glyph, QCF page/line, span, or ayah-page values.
- [x] Android stores QF rows in `noBackupFilesDir/qf-content-cache.db`; web uses
  IndexedDB. Neither cache is committed or included in a release artifact.
- [x] Content Sync checkpoints, idempotent mutations, relative paths, snapshot
  replacement, transaction rollback, invalidation, and resync are implemented.
- [x] Explicit QF access rejection is propagated as a cache-purge signal; both
  clients delete all retained QF rows and the checkpoint immediately.
- [x] The six-day refresh and seven-day withholding/purge behavior is automatic.
- [x] Public Privacy Policy and Terms identify QF and the Cloudflare processor.
- [x] Worker and client tests verify allowlists, secret non-disclosure, token
  retry, API-call accounting, offline behavior, atomic rollback, topology, and
  QCF glyph-run integrity.

## Release and maintenance checklist

- [ ] Merge the Worker/client change and verify the stable Production Worker
  returns `{"ok":true,"environment":"production"}`.
- [x] On a 192 MB-heap clean Android emulator, complete one live Production
  bootstrap, validate 77,429 words / 6,236 verses / 604 pages, open the reader,
  then process-cold relaunch in 1.4 seconds with zero API calls.
- [ ] Repeat the clean-bootstrap and relaunch check in a clean browser profile.
- [x] Force an Android refresh and confirm the stored checkpoint is used,
  unchanged content remains readable, and the call counter reports six.
- [ ] Force a browser refresh and confirm the stored checkpoint is used,
  unchanged content remains readable, and the call counter reports six.
- [ ] Watch QF's update/deprecation notices and migrate within the announced
  window. Re-run the full-corpus mapper whenever resource schemas change.
- [ ] Rotate the Cloudflare secret immediately after any suspected exposure and
  update the Worker without recording the value in an issue, log, or screenshot.
- [ ] On voluntary termination, deploy the revocation response before disabling
  the Worker so installed clients purge their QF caches; access rejection from
  QF already triggers the same immediate purge automatically.
- [ ] Retain architecture and redacted operational evidence needed for a QF
  compliance audit; report any suspected API security incident within 24 hours.

## Separate unresolved content questions

Authenticated word/QCF caching does not resolve two independently sourced
release questions:

1. `quran.db` contains repeat topology derived offline from the legacy QDC audio
   endpoint and updated only through app releases. Obtain written QF permission
   to redistribute that derived dataset, or ship the quran-align-only fallback.
2. Content Sync's offline exception does not license Mushaf font files or page
   images. Keep or distribute KFGQPC/QCF font assets only under permission from
   the provider identified by QF's Mushaf Fonts and Images documentation.

These questions do not change the authenticated runtime cache architecture, but
they remain release/compliance gates for the corresponding bundled assets.
