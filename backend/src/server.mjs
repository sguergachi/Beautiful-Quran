import { timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import {
  ContentUnavailableError,
  DAY_MS,
  LegacyQdcCache,
  RecitationTimingCache,
  createLegacyRecitationProvider,
  createLegacyQdcFetcher,
  validateAppReciterId,
} from "./cache.mjs";
import { createRuntimeTimingNormalizer } from "./normalize.mjs";

const SNAPSHOT_ROUTE = /^\/api\/v4\/resources\/snapshots\/recitations\/(\d+)$/;

export function createHandler({
  cache,
  timingCache,
  allowedOrigins,
  adminToken,
  requestsPerMinute = 600,
  now = Date.now,
}) {
  let windowStartedAt = now();
  let requestCount = 0;

  return async (request, response) => {
    const startedAt = now();
    let status = 500;
    try {
      const url = new URL(request.url, "http://cache.local");
      const origin = request.headers.origin;
      response.setHeader("x-content-type-options", "nosniff");
      response.setHeader("referrer-policy", "no-referrer");
      if (origin && !allowedOrigins.has(origin)) return sendJson(response, 403, { error: "origin_not_allowed" });
      setCors(response, origin);

      if (request.method === "OPTIONS") return sendEmpty(response, 204);
      if (request.method === "GET" && url.pathname === "/healthz") {
        return sendJson(response, 200, { status: "ok", source: "normalized-timing-facade" });
      }
      if (request.method === "DELETE" && url.pathname === "/admin/cache") {
        if (!adminToken) return sendJson(response, 404, { error: "not_found" });
        if (!validBearer(request.headers.authorization, adminToken)) {
          return sendJson(response, 401, { error: "unauthorized" });
        }
        await Promise.all([cache.clear(), timingCache?.clear()]);
        return sendEmpty(response, 204);
      }
      if (!allowRequest()) return sendJson(response, 429, { error: "rate_limited" }, { "retry-after": "60" });

      if (request.method === "GET" && url.pathname === "/api/v4/resources/sync") {
        if (!timingCache) return sendJson(response, 404, { error: "not_found" });
        return serveSync(response, await syncedContent(url, timingCache, now));
      }
      const snapshotMatch = request.method === "GET" && SNAPSHOT_ROUTE.exec(url.pathname);
      if (snapshotMatch) {
        if (!timingCache) return sendJson(response, 404, { error: "not_found" });
        const content = await timingCache.get(Number(snapshotMatch[1]));
        const headers = { ...contentHeaders(content), "cache-control": "no-store" };
        if (request.headers["if-none-match"] === content.etag) return sendEmpty(response, 304, headers);
        response.writeHead(200, { ...headers, "content-type": "application/json; charset=utf-8" });
        response.end(content.body);
        return 200;
      }

      return sendJson(response, 404, { error: "not_found" });
    } catch (error) {
      if (error instanceof RangeError) status = sendJson(response, 400, { error: "invalid_resource" });
      else if (error instanceof ContentUnavailableError) status = sendJson(response, error.status, { error: error.code });
      else status = sendJson(response, 500, { error: "internal_error" });
    } finally {
      if (!response.headersSent) status = response.statusCode;
      console.info(JSON.stringify({
        at: new Date(startedAt).toISOString(),
        method: request.method,
        path: request.url?.split("?", 1)[0],
        status: response.statusCode || status,
        durationMs: Math.max(0, now() - startedAt),
      }));
    }

    function allowRequest() {
      const current = now();
      if (current - windowStartedAt >= 60_000) {
        windowStartedAt = current;
        requestCount = 0;
      }
      requestCount += 1;
      return requestCount <= requestsPerMinute;
    }
  };
}

async function syncedContent(url, timingCache, now) {
  const match = /^recitations:(\d+)$/.exec(url.searchParams.get("resources") || "");
  if (!match) throw new RangeError("Expected one recitation resource");
  const reciterId = validateAppReciterId(Number(match[1]));
  const bootstrap = url.searchParams.get("bootstrap") === "true";
  const previousToken = url.searchParams.get("sync_token");
  if (bootstrap === Boolean(previousToken)) throw new RangeError("Expected bootstrap or sync_token");
  const content = await timingCache.get(reciterId);
  const contentAgeMs = now() - content.fetchedAtMs;
  if (!Number.isFinite(contentAgeMs) || contentAgeMs < 0 || contentAgeMs > 7 * DAY_MS) {
    throw new ContentUnavailableError("No current content is available");
  }
  const token = content.etag.slice(1, -1);
  const changed = bootstrap || previousToken !== token;
  return {
    content,
    body: {
      sync: {
        sync_until_sequence: content.fetchedAtMs,
        content_age_ms: contentAgeMs,
        has_more: false,
        next_page_url: null,
        next_sync_token: token,
        mutations: changed ? [{
          sequence: content.fetchedAtMs,
          type: bootstrap ? "RESOURCE_CREATE" : "RESOURCE_INVALIDATE",
          resource_group: "recitations",
          resource_id: reciterId,
          record_type: null,
          record_key: null,
          changed_at: new Date(content.fetchedAtMs || now()).toISOString(),
          data: null,
          snapshot_url: `/api/v4/resources/snapshots/recitations/${reciterId}`,
        }] : [],
      },
    },
  };
}

function serveSync(response, { content, body }) {
  return sendJson(response, 200, body, {
    ...contentHeaders(content),
    "cache-control": "no-store",
  });
}

function contentHeaders(content) {
  const fetchedAt = new Date(content.fetchedAtMs);
  return {
    "cache-control": "no-cache, must-revalidate",
    etag: content.etag,
    "last-modified": fetchedAt.toUTCString(),
    "x-content-fetched-at": fetchedAt.toISOString(),
    "x-content-expires-at": new Date(content.fetchedAtMs + 7 * DAY_MS).toISOString(),
    "x-content-source": "legacy-qdc-transitional",
    "x-cache-status": content.cacheStatus,
  };
}

function setCors(response, origin) {
  if (origin) response.setHeader("access-control-allow-origin", origin);
  response.setHeader("access-control-allow-methods", "GET, OPTIONS");
  response.setHeader("access-control-allow-headers", "If-None-Match");
  response.setHeader("access-control-expose-headers", "ETag, Last-Modified, X-Content-Fetched-At, X-Content-Expires-At, X-Content-Source, X-Cache-Status");
  response.setHeader("vary", "Origin");
}

function validBearer(header, expected) {
  const actual = header?.startsWith("Bearer ") ? header.slice(7) : "";
  const actualBytes = Buffer.from(actual);
  const expectedBytes = Buffer.from(expected);
  return actualBytes.length === expectedBytes.length && timingSafeEqual(actualBytes, expectedBytes);
}

function sendJson(response, status, body, headers = {}) {
  response.writeHead(status, { ...headers, "content-type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(body));
  return status;
}

function sendEmpty(response, status, headers = {}) {
  response.writeHead(status, headers);
  response.end();
  return status;
}

export function configuration(environment = process.env) {
  const maxAgeMs = numberSetting(environment.CACHE_MAX_AGE_MS, 7 * DAY_MS);
  const revalidateAfterMs = numberSetting(environment.CACHE_REVALIDATE_MS, 6 * DAY_MS);
  return {
    port: numberSetting(environment.PORT, 8787),
    cacheDir: environment.CACHE_DIR || resolve(fileURLToPath(new URL("../.cache", import.meta.url))),
    maxAgeMs,
    revalidateAfterMs,
    requestsPerMinute: numberSetting(environment.REQUESTS_PER_MINUTE, 600),
    allowedOrigins: new Set(
      (environment.ALLOWED_ORIGINS || "https://sguergachi.github.io,http://localhost:5173")
        .split(",")
        .map((origin) => origin.trim())
        .filter(Boolean),
    ),
    adminToken: environment.CACHE_ADMIN_TOKEN || "",
    python: environment.PYTHON || "python3",
    timingDatabase: environment.TIMING_REFERENCE_DB || resolve(fileURLToPath(new URL("../../data/quran.db", import.meta.url))),
  };
}

function numberSetting(value, fallback) {
  const parsed = value === undefined ? fallback : Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) throw new RangeError("Invalid numeric setting");
  return parsed;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const config = configuration();
  const cache = new LegacyQdcCache({
    cacheDir: resolve(config.cacheDir, "legacy"),
    fetchContent: createLegacyQdcFetcher(),
    revalidateAfterMs: config.revalidateAfterMs,
    maxAgeMs: config.maxAgeMs,
  });
  const normalize = createRuntimeTimingNormalizer({
    python: config.python,
    database: config.timingDatabase,
  });
  const timingCache = new RecitationTimingCache({
    cacheDir: resolve(config.cacheDir, "timings"),
    fetchSnapshot: createLegacyRecitationProvider({ rawCache: cache, normalize }),
    revalidateAfterMs: config.revalidateAfterMs,
    maxAgeMs: config.maxAgeMs,
  });
  createServer(createHandler({ cache, timingCache, ...config })).listen(config.port, () => {
    console.info(JSON.stringify({ event: "listening", port: config.port, source: "normalized-timing-facade" }));
  });
}
