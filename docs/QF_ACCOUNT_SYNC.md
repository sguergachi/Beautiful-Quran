# Quran Foundation accounts and bookmarks

Beautiful Quran has an optional public-client OAuth integration for Android and
web. Guest reading remains independent. This first phase synchronizes **verse
bookmarks only**, in the shared QF collection named **Beautiful Quran**. Notes,
preferences, reading progress, exact audio position and device presence remain
local. It never uses App State, the Content Worker, or a client secret.

**Live access is disabled.** `data/qf_account.json` has no client IDs or granted
scopes. Implementation and fake-server tests do not establish QF approval, CORS
access, policy acceptance or a successful live login. Existing Content API
credentials are confidential and must never be reused by these public clients.

## Provisioning

In [QF Developer Console](https://dev-console.quran.foundation/), register public
“Frontend or mobile app” clients. Use separate Android and web clients unless
QF explicitly asks for one shared registration. Link them to the same logical
Beautiful Quran application. Record exactly the approved scope strings; request
`openid`, `offline_access` and the collection/bookmark actions, without notes,
preferences, activity, publication or profile editing.

| Setting | Value |
|---|---|
| Android login callback | `com.beautifulquran:/oauth2redirect/qf` |
| Pages login callback | `https://sguergachi.github.io/Beautiful-Quran/app/` |
| Local web callback, if approved | `http://localhost:5173/` |
| Pages allowed origin | `https://sguergachi.github.io` |
| Local web origin, if approved | `http://localhost:5173` |
| Production issuer / API | `https://oauth2.quran.foundation` / `https://apis.quran.foundation/auth` |
| Pre-live issuer / API | `https://prelive-oauth2.quran.foundation` / `https://apis-prelive.quran.foundation/auth` |

Register callbacks exactly, including the trailing slash on web. The Pages
callback is the existing index page, so no hosting rewrite or SPA fallback is
required. Native OAuth uses a dedicated AppAuth scheme rather than the reader's
`beautifulquran:` links. The public client IDs and approved origins may be
committed; passwords, confidential secrets and user tokens may not.

The single config is packaged as an Android asset and imported by Vite. Set
the environment, both IDs, exact granted scopes, web origins and consent
version; set `enabled` only after testing the corresponding registrations.
Both issuer and API change together. A consent/client/environment change
invalidates the previous partition; export pending edits before a migration.
Production grants and origins require their own approval, independently of
pre-live testing and directory listing.

Before production enablement, resolve the remaining account eligibility,
collection visibility, public-client revocation and privacy/deletion questions
with QF; provide its required app/privacy/terms/contact information. No private
note upload is authorized by this phase. The unsent access-request draft from
the research is not evidence that QF has received or approved a request.

## Authentication and consent

Settings → Quran Foundation explains the religious information involved and
links the app's privacy notice plus Quran.com account terms/privacy. The reader
must affirm being 18 or older and separately consent to sending bookmark
activity. OAuth alone does not import existing guest saves.

AppAuth Android 0.11.1 and oidc-client-ts 3.5.0 own code exchange, S256 PKCE,
state/nonce and OIDC response validation. Native identity comes from the
provider's authenticated HTTPS `/userinfo`; web loads and matches userinfo.
Identity is scoped by issuer/client/config and the stable QF subject, never
email. The app has no password screen. Refresh is serialized and a native
replacement token is saved before it is used.

Android stores auth state, consent time, PKCE transaction and the bookmark
journal together in an atomic AES-GCM file in `noBackupFilesDir`, with an
AndroidKeyStore key. Clearing the account removes the file and key. Neither
cloud backup nor device transfer includes this account store.

Web stores access/refresh tokens in memory only. A reload requires a new hosted
sign-in to resume sync; cached account bookmarks and queued edits remain usable
offline. PKCE transactions use sessionStorage for at most ten minutes. Account
identity, consent time and the journal use a separate localStorage key, with no
tokens. A Web Lock permits one writer tab; other tabs show the library without
editing it. Closing that tab lets another claim it. Unsupported browsers keep
guest use. Callback query data is removed before reader routing, and the
service worker bypasses OAuth callback requests.

## Bookmark contract

The guest library is never overwritten or silently uploaded. Sign-in selects
the QF library; **Add N device bookmarks to QF** explicitly adds the original
guest saves and retains their device copy. Disconnect restores the guest
library. Reauthentication in a browser must match the cached subject; switching
accounts requires export/disconnect, which explicitly discards queued edits.

The adapter discovers the named collection across every cursor page, creating
it only if absent. Duplicate names stop sync rather than guessing ownership.
A collection ID is then durable in the account journal. Missing/deleted
collections and refused scopes produce a retryable account notice; they do not
cause an upload to Favorites or an automatic replacement collection.

Only `type: ayah`, `group: verses_6236` records are mapped to the canonical
Hafs verse references. Writes use `mushafId: 4`. Other groups and page/juz/surah
bookmarks are retained in QF. Membership is attached/detached through
`/auth/v1/collections/{id}/bookmarks`; standalone bookmark DELETE, reading
bookmark writes and Favorites are never used.

Every local membership change gets a monotonically increasing revision and is
saved with the visible library **before** publishing it. Complete remote reads
are overlaid with the pending desired memberships. Successful mutations are
acknowledged only by a complete confirming read and only for the matching
revision. Lost replies therefore survive restart and retry without toggles,
duplicates or resurrected removals. Partial pages, repeated cursors, changed
counts, failed writes and failed storage preserve the outbox.

Sync runs on connection, foreground, restored connectivity, edits and manual
retry. It does not poll while reading, seek the player, change a reciter or
change a reading destination. QF does not expose a compare-and-swap revision
for collection membership: a queued edit is applied on this device's next sync,
and subsequently observed remote edits are adopted once that local revision
has been confirmed. This is eventual bookmark continuity, not live presence.

## Export, erasure and disconnect

Export produces `beautiful-quran-bookmarks.json` containing only the active
verse library. **Remove synced bookmarks** is a separate confirmation on the
paper sheet. Its request survives offline/restart, includes unsent additions
and is complete only after QF returns an empty set of matching verse
memberships. It keeps the collection, unrelated memberships, history, notes
and the guest library. Clearing app/site storage before the request is sent
cancels the local queue; erase remotely in QF in that case.

Disconnect stops sync and clears the account cache/outbox immediately. The
clients attempt public token revocation without a secret; a refused or offline
request is reported as **unconfirmed**, with instructions to remove app access
in QF. Disconnect does not claim to delete the remote collection, other apps'
copies, the shared QF account or the provider's backups. Those belong to QF's
account controls. A local-storage removal failure is also reported honestly.

## Verification and launch gate

JVM/Vitest fault tests cover restart, lost accepted add/delete replies, newer
edits during a request, non-confirming success, partial confirmation, storage
failure, account change, offline erasure, full pagination, cursor cycles,
changing counts, canonical verse validation and collection-scoped DELETE.

After provisioning, test on **two actual clients of the same pre-live account**:
login/cancel/restart, native refresh and browser re-login, add/remove in both
directions, airplane-mode edits and process death, explicit guest import,
account switch with pending edits, unrelated Favorites/collections/notes,
revocation, export and offline erasure. Verify web token, userinfo and User API
CORS from the approved origin. Repeat with separately approved production
clients before enabling production. These live checks remain outstanding
until Console access and grants are available.

Sources: [public client setup](https://api-docs.quran.foundation/docs/tutorials/oidc/client-setup/),
[Android OAuth](https://api-docs.quran.foundation/docs/tutorials/oidc/mobile-apps/android/),
[public browser OAuth](https://api-docs.quran.foundation/docs/sdk/javascript/public-quickstart/),
[User API schema](https://github.com/quran/qf-api-docs/blob/660489b1ff1df770164388ec96ddccc571a97224/openAPI/user-related-apis/v1.json),
[developer terms](https://api-docs.quran.foundation/legal/developer-terms/),
[privacy packet](https://api-docs.quran.foundation/legal/developer-privacy/).
