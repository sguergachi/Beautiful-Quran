# Timing Content facade

This dependency-free Node service is the stable server boundary for Beautiful
Quran's repeat-aware word timings. Android and web use a small QF Content
Sync-shaped contract regardless of which upstream provider is active:

```text
GET /api/v4/resources/sync?bootstrap=true&resources=recitations:1
GET /api/v4/resources/sync?sync_token=<opaque>&resources=recitations:1
GET /api/v4/resources/snapshots/recitations/1
```

Resource IDs are Beautiful Quran reciter IDs, not upstream IDs. The raw legacy
response is never exposed publicly. Today the internal provider fetches the six
legacy, unauthenticated QDC reciters already used by the project. After Quran
Foundation (QF) approval, only that provider and its ID map are replaced; the
client protocol, device databases, and reader repositories remain unchanged.

This architecture does **not** claim QF approval or permission for transitional
legacy use. That question remains disclosed in
[`docs/QF_CONTENT_SYNC.md`](../docs/QF_CONTENT_SYNC.md).

## Normalization and cache contract

- One cold reciter bootstrap fetches its 114 fixed chapter responses. Upstream
  requests are serialized and spaced by 250 ms; concurrent client bootstraps
  collapse into one job.
- `tools/normalize_runtime_timings.py` reuses the canonical cleaner, quran-align
  clock rebase, typed corrections, CTC repairs, onset evidence, and physical
  finalizer from `tools/build_db.py`. Clients receive only validated
  `timing` records in the existing `[position,startMs,endMs]` contract.
- Raw provider responses and normalized snapshots live in separate private
  disk caches. Files are integrity-hashed, mode `0600`, and replaced atomically.
- Both layers revalidate after six days and fail closed after seven days. A
  failed refresh may use only a still-current cached copy, with a 15-minute
  retry backoff.
- Sync tokens are opaque snapshot hashes. A changed snapshot produces a
  `RESOURCE_INVALIDATE` with a relative URL; an unchanged token produces no
  mutation. Snapshot and sync responses are `no-store` at HTTP intermediaries.
- Every sync carries the normalized snapshot's actual `content_age_ms`. Devices
  subtract that duration from their local checkpoint instead of starting a new
  seven-day window, so backend and device caches can never stack their TTLs.
- Nonempty snapshots below 6,000 recognized ayahs are rejected before they can
  replace a complete device cache.
- Operational logs contain method, path, status, and duration—never IP
  addresses, authorization headers, query strings, response content, or
  reading activity.
- `DELETE /admin/cache` requires a constant-time bearer-token check and purges
  raw and normalized caches after in-flight writes stop.

The Android SQLite and browser IndexedDB caches independently commit all rows
and the next token only after the full exchange succeeds. They start refresh at
six days and reject runtime rows after seven days; bundled quran-align remains
the nonblocking first-install/offline fallback.

## Run locally

Requires Node 22 and Python 3:

```bash
npm --prefix backend test
CACHE_ADMIN_TOKEN="$(openssl rand -hex 32)" npm --prefix backend start
curl http://localhost:8787/healthz
curl 'http://localhost:8787/api/v4/resources/sync?bootstrap=true&resources=recitations:1'
```

The first request for a reciter intentionally takes tens of seconds because the
legacy provider has no whole-reciter snapshot. Prewarm all six reciters on a
persistent host before publishing its URL. QF's authenticated snapshot should
remove that transitional fan-out.

Build the production image from the **repository root**, because the runtime
normalizer needs the QDC-free quran-align reference database and timing rules:

```bash
docker build -f backend/Dockerfile -t beautiful-quran-timing .
docker run --read-only --tmpfs /tmp \
  -p 8787:8787 \
  -e CACHE_ADMIN_TOKEN='<secret>' \
  -v beautiful-quran-cache:/var/lib/beautiful-quran-cache \
  beautiful-quran-timing
```

## Configuration

| Variable | Default | Purpose |
|---|---:|---|
| `PORT` | `8787` | HTTP listen port; terminate TLS at the hosting edge. |
| `CACHE_DIR` | `backend/.cache` | Private persistent raw + normalized cache root. |
| `ALLOWED_ORIGINS` | GitHub Pages + local Vite | Browser origins. Android sends no Origin header. |
| `CACHE_ADMIN_TOKEN` | unset | Enables `DELETE /admin/cache`; secret-manager only. |
| `REQUESTS_PER_MINUTE` | `600` | Process-wide public ceiling without client identifiers. |
| `CACHE_REVALIDATE_MS` | six days | Must remain below `CACHE_MAX_AGE_MS`. |
| `CACHE_MAX_AGE_MS` | seven days | Hard maximum enforced by the cache. |
| `PYTHON` | `python3` | Canonical normalizer interpreter. |
| `TIMING_REFERENCE_DB` | `data/quran.db` | QDC-free quran-align clock/reference database. |

The public clients are enabled with the non-secret GitHub repository variable
`TIMING_CONTENT_BASE_URL`. Keep it unset until the HTTPS service is deployed,
prewarmed, monitored, and named in `docs/privacy.html`.

## Production deployment gate

1. Use a persistent service with TLS 1.2+, encrypted storage, a private volume,
   restricted operator access, edge rate limits, and an uptime/backup plan.
2. Name the host/provider and link its privacy policy before any client traffic.
3. Store `CACHE_ADMIN_TOKEN` in the host secret manager; test purge and restore.
4. Prewarm all six reciters and alert on upstream failures, cache age nearing
   seven days, normalization failures, abnormal traffic, and purge failures.
5. Run the full six-reciter timing-parity audit and retain the report.
6. Tell QF accurately that the current adapter is transitional legacy access.

## Authenticated provider swap after approval

The new adapter must:

1. Store `QF_CLIENT_ID` and the one-time `QF_CLIENT_SECRET` only in the backend
   secret manager.
2. obtain and cache an OAuth2 `client_credentials` token with `scope=content`;
3. send both `x-auth-token` and `x-client-id`, retrying one `401` once;
4. keep prelive credentials/hosts separate from production;
5. map app reciter IDs to approved QF chapter-reciter/resource IDs;
6. fetch QF Content Sync snapshots or the documented chapter-reciter endpoint
   with `segments=true`, then feed its `audio_file.timestamps` through the same
   normalizer; and
7. preserve timeouts, response bounds, host allowlists, redacted logs, cache
   expiry, purge, and client-facing snapshot schema.

The application checklist, unresolved QF questions, privacy/consent work,
incident obligations, and evidence package are maintained in
[`docs/QF_CONTENT_SYNC.md`](../docs/QF_CONTENT_SYNC.md).
