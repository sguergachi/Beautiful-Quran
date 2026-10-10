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

private timing backend
  -> authenticated Content Sync: chapter_recitations:2,3,5,6,7,9
  -> maintained raw snapshots (private KV)
  -> Python canonical normalization + per-row acoustic verdicts
  -> immutable reviewed timing views (private KV)
  -> Durable Object: source checkpoints, accepted views, revocation
  -> GET /api/timings/<appReciterId> -> separate atomic device timing cache

offline build
  -> canonical Quran text + independent reviewed timing corpora
  -> bundled quran.db (no QF reader fields or timing rows)
```

The Worker is a credential boundary and private timing content service. Word/QCF
responses pass through to the device cache. Timing snapshots and reviewed views
are privately retained, with a separate small control store for checkpoints and
withdrawal. It stores no user data or device cache. It caches the short-lived
OAuth token in memory, retries one rejected token once, streams `no-store`
responses, and allows only the exact paths above. Browser requests are additionally restricted
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
   restoration retries a failed update automatically. Every failed update gets
   four bounded backoff attempts; a later launch, network-restoration event,
   reader entry, or manual refresh starts a new bounded episode. A successful
   first fill keeps the entrance cover over the empty Mushaf; a failed fill
   releases the app so offline scrolling and settings remain usable while the
   next retry or connectivity event repairs the leaf.
6. At day seven, QF-derived reader fields are withheld until a successful sync.
   Non-Content-Sync supplement rows are also removed from persistent storage.
7. A rejected sync checkpoint (HTTP 400, 404, or 410) discards no readable data immediately: the client
   obtains a fresh bootstrap, replaces cached resources inside the commit
   transaction, validates, and only then advances the checkpoint.
   Snapshot and supplement failures use ordinary backoff, not bootstrap.
8. Android derived views (Mushaf catalog, search index, English glosses and
   page translations) share an invalidation generation. Builds run outside
   the publication lock; if a refresh overtakes a build, its result is discarded
   and rebuilt before it can be returned or cached.

The initial exchange currently uses nine requests: one sync, three snapshots,
and five supplements. An unchanged refresh uses six: one incremental sync and
five supplements. QF invalidations add only the affected resource snapshots;
row changes use QF's native upsert/delete deltas. Identical supplement rows are
detected without rebuilding the reader view or repaginating the book. English
pagination is keyed by an SHA-256 digest of the exact prose, so an actual QF
gloss change can never reuse page breaks measured for older words. Refresh and
expiry timers are keyed by both checkpoint and commit time, so even a reused QF
token advances both deadlines. Developer Mode displays live progress, current
phase, update/refresh/expiry times, errors, calls this launch, and calls made by
the last successful refresh. Android shows a toast only after the atomic commit
succeeds.

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
  Evidence so far (2026-09-07): the production web client module was driven
  end to end against the deployed Worker with the Pages `Origin` header — clean
  bootstrap validated 77,429 words / 6,236 verses / 604 pages in 9 calls, and a
  cold relaunch read the checkpoint with 0 calls. This needed a web mapper fix
  (untranslated QF rows outside the reader alignment are now skipped, matching
  Android's long-standing behavior). The literal Pages-origin browser pass still
  wants a post-merge run: localhost can never clear the Worker's origin gate.
- [x] Force an Android refresh and confirm the stored checkpoint is used,
  unchanged content remains readable, and the call counter reports six.
- [ ] Force a browser refresh and confirm the stored checkpoint is used,
  unchanged content remains readable, and the call counter reports six.
  Evidence so far (2026-09-07): same harness as above — forced refresh of
  unchanged content used 6 calls with the checkpoint intact. Same Pages-origin
  caveat as the browser bootstrap item.
- [ ] Watch QF's update/deprecation notices and migrate within the announced
  window. Re-run the full-corpus mapper whenever resource schemas change.
- [ ] Rotate the Cloudflare secret immediately after any suspected exposure and
  update the Worker without recording the value in an issue, log, or screenshot.
- [ ] On voluntary termination, deploy the revocation response before disabling
  the Worker so installed clients purge their QF caches; access rejection from
  QF already triggers the same immediate purge automatically.
- [ ] Retain architecture and redacted operational evidence needed for a QF
  compliance audit; report any suspected API security incident within 24 hours.

## Runtime repeat timing

App IDs `1,2,3,4,5,7` correspond to QF chapter-recitation IDs `7,6,2,9,3,5`.
Each device downloads a complete reciter through `/api/timings/<appReciterId>`.
The response contains schema/reciter/audio identity, the `everyayah-ms` clock,
assembled source hash, native snapshot sequence and last successful source-sync
time, normalizer fingerprint, ordered occurrences, audio onsets, explicit withheld
verse keys, and a SHA-256 of compact JSON `{rows,withheldVerseKeys}`.
Clients validate the complete 6,236-verse contract before atomic publication;
they never clean or repair source timings.

The backend maintains each native source daily using an independent opaque
Content Sync checkpoint. It follows native pages, obtains a full replacement for
any row/snapshot change or rejected checkpoint, and advances only after storage
succeeds. The source copy and accepted canonical view are separate. A changed
source returns `503 qf_timing_review_required` until the maintainer completes
normalization and acoustic review. Successful unchanged checks advance source
freshness with the same canonical revision. Provider outages retain the previous
permitted copy without inventing a successful check.

SQLite-backed Durable Object control state serializes checkpoints, accepted
manifests, tombstones, and global revocation. KV holds only large immutable
source/view payloads; its eventual consistency never decides access or freshness.
Every accepted manifest names its exact current source and content revision.
An overtaken publication fails with 409. Source refreshes and streamed responses
are fenced against concurrent withdrawal or access rejection.

Timing copies refresh after six days. A first online download is required;
afterward they remain available through outages beyond seven days. Resource
withdrawal returns `410 qf_timing_resource_deleted` and removes that voice.
`403 qf_access_revoked` purges all QF timing and word/QCF copies. Android keeps
timings in `noBackupFilesDir`; web uses a separate IndexedDB store. Accepted
generation changes invalidate active reader preparations and Lab defaults.

Release assets exclude all 37,411 QF timing rows. The seven independent voices'
43,648 rows are unchanged, with exact hashes in
`data/qf-timing-transition.json`. The original v65 database remains a private
review reference. It is never uploaded as a runtime resource or release asset.

### Maintain and publish reviewed views

`tools/sync_qf_timings.py` requests a native refresh, downloads private snapshots,
runs the existing canonical pipeline into a private database, validates the full
timing clock/coverage, and exports immutable view payloads. `--publish` uploads
the payloads before accepting exact-source manifests. Keep the work directory
outside the checkout with mode 0700 and the maintenance key file at mode 0600.
The key permits only timing maintenance; QF credentials stay in Cloudflare.

```bash
python3 tools/sync_qf_timings.py \
  --key-file ~/.config/beautiful-quran/timing-maintenance.key \
  --work-dir ~/.local/share/beautiful-quran/timings/runtime \
  --timing-baseline /path/to/private/reviewed-v65.db \
  --source-cache /path/to/existing/tools/.cache \
  --wrangler /path/to/installed/wrangler/bin/wrangler.js \
  --publish
```

Unknown assembled source hashes fail closed. Review and pin their complete
canonical delta with two independent acoustic models before publication;
rejected/ambiguous candidates retain exact reviewed baseline rows. No raw timing
snapshot, canonical pack, opaque checkpoint, maintenance key, or QF credential
belongs in Git or release artifacts. The
[transition report](QF_TIMING_PARITY.md) and source-profile ledger contain only
hashes, verse identities, and review metadata.

The v65 reference is the baseline for this first transition. For a later source
transition, retain the latest accepted private database and rebase the source
profile and acoustic ledger onto that generation before publishing. Keep each
accepted database, source snapshot, and review summary together outside Git.

Resource deletion remains tombstoned until a reviewed replacement is accepted.
Shared access rejection latches revocation and purges the private source/view
store; scheduled cleanup retries removal of uploads that KV exposes late. After
QF access has been restored, the operator can add `--regrant` to the maintenance
command. Recovery refuses to proceed while a purge is incomplete, then requires
fresh native sources and reviewed packs. Recovery is never automatic.

### Rendering assets

The Developer Terms updated 2026-10-04 separately permit integrated app
caching or bundling of font files and Mushaf images obtained through QF APIs
or documented CDN URLs, with an active Developer Console account and QF
credit. Content Sync itself supplies neither font files nor images. Check the
asset's source and these conditions rather than treating the timing dataset
and rendering assets as one permission question.

Written permission to redistribute a prepackaged legacy timing dataset has not
been obtained. The runtime transition removes that dataset from release assets.
