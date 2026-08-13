# Transitional content cache

This is a small, dependency-free Node service that caches the legacy,
unauthenticated Quran.com QDC audio-timing endpoint while Beautiful Quran waits
for Quran Foundation (QF) Content API approval.

It is deliberately **not** a general proxy and does not claim that QF has
approved legacy QDC use. The only public content route accepts the six QDC
reciters already used by the project and chapters 1–114:

```text
GET /v1/legacy-qdc/recitations/:reciterId/chapters/:chapter/audio-files
```

## Cache contract

- The first request fetches the fixed upstream QDC URL over HTTPS and stores
  the unmodified JSON body on a private persistent volume.
- Concurrent misses for the same resource collapse into one upstream request.
- Upstream requests are serialized and spaced by 250 ms; the service never
  prewarms or crawls all chapters.
- A resource is synchronously revalidated after six days.
- If revalidation fails, the old response is served only until it reaches seven
  days. Retries use a per-resource 15-minute backoff so an outage cannot turn
  client traffic into an upstream request storm. Older content fails closed
  with `503 content_unavailable`.
- `ETag`, `Last-Modified`, `X-Content-Fetched-At`, and
  `X-Content-Expires-At` let clients perform conditional checks and enforce
  their own seven-day local-cache limit. HTTP intermediaries must revalidate
  every response so a five-minute browser/CDN cache cannot cross that limit.
- Cache files are written atomically with mode `0600`. A protected purge route
  waits out in-flight writes and removes completed and temporary cache files.
  For revocation or termination, disable public traffic first and then purge so
  a later request cannot fetch the source again.
- Operational logs contain method, route, status, and duration—never IP
  addresses, authorization headers, query strings, or response content.

The seven-day behavior mirrors QF's current Content Sync freshness expectation,
but it does **not** convert the legacy endpoint into an approved Content Sync
integration or establish redistribution rights.

## Run locally

Requires Node 22 or newer:

```bash
npm --prefix backend test
CACHE_ADMIN_TOKEN="$(openssl rand -hex 32)" npm --prefix backend start
curl http://localhost:8787/healthz
curl http://localhost:8787/v1/legacy-qdc/recitations/7/chapters/1/audio-files
```

The build-time pipeline can use the local service without changing its response
parser:

```bash
BQ_QDC_CACHE_BASE_URL=http://localhost:8787 python3 tools/build_db.py
```

## Configuration

| Variable | Default | Purpose |
|---|---:|---|
| `PORT` | `8787` | HTTP listen port. Put TLS at the hosting edge. |
| `CACHE_DIR` | `backend/.cache` | Private persistent cache volume. |
| `ALLOWED_ORIGINS` | GitHub Pages + local Vite | Comma-separated browser origins. Android requests have no browser Origin header. |
| `CACHE_ADMIN_TOKEN` | unset | Enables `DELETE /admin/cache`; store only in the host's secret manager. |
| `REQUESTS_PER_MINUTE` | `600` | Process-wide public request ceiling without storing client identifiers. |
| `CACHE_REVALIDATE_MS` | six days | Must remain below `CACHE_MAX_AGE_MS`. |
| `CACHE_MAX_AGE_MS` | seven days | Hard-coded maximum accepted by the cache implementation. |

## Production deployment gate

Do not connect a released client until all of these are true:

1. The host provides TLS 1.2+, encryption at rest, a persistent private volume,
   edge rate limiting, restricted operator access, and secret rotation.
2. The host and its privacy-policy link are listed in Beautiful Quran's Privacy
   Policy; an appropriate data-processing agreement is in place if required.
3. `CACHE_ADMIN_TOKEN` is stored in the host's secret manager, rotated on a
   documented schedule, and tested against the purge route.
4. Monitoring alerts on upstream failures, content approaching seven days,
   abnormal traffic, and purge failures without recording client IP addresses.
5. QF receives an accurate architecture diagram and is told this is a
   transitional legacy source that will be replaced after approval.

## Authenticated migration

After QF approval, replace the legacy fetcher with the official backend-only
OAuth2 client-credentials flow (`content` scope), store the client secret only
in the host's secret manager, and use QF Content Sync tokens/snapshots rather
than this time-based QDC fetch. The Android cache types already model atomic
Content Sync changes; the remaining work is tracked in
[`docs/QF_CONTENT_SYNC.md`](../docs/QF_CONTENT_SYNC.md).
