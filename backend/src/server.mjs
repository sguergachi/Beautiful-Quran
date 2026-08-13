import { timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import {
  ContentUnavailableError,
  DAY_MS,
  LegacyQdcCache,
  createLegacyQdcFetcher,
  validateQdcKey,
} from "./cache.mjs";

const CONTENT_ROUTE = /^\/v1\/legacy-qdc\/recitations\/(\d+)\/chapters\/(\d+)\/audio-files$/;

export function createHandler({ cache, allowedOrigins, adminToken, requestsPerMinute = 600, now = Date.now }) {
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
        return sendJson(response, 200, { status: "ok", source: "legacy-qdc-transitional" });
      }
      if (request.method === "DELETE" && url.pathname === "/admin/cache") {
        if (!adminToken) return sendJson(response, 404, { error: "not_found" });
        if (!validBearer(request.headers.authorization, adminToken)) {
          return sendJson(response, 401, { error: "unauthorized" });
        }
        await cache.clear();
        return sendEmpty(response, 204);
      }
      if (!allowRequest()) return sendJson(response, 429, { error: "rate_limited" }, { "retry-after": "60" });

      const match = request.method === "GET" && CONTENT_ROUTE.exec(url.pathname);
      if (!match) return sendJson(response, 404, { error: "not_found" });
      const reciterId = Number(match[1]);
      const chapter = Number(match[2]);
      validateQdcKey(reciterId, chapter);
      const content = await cache.get(reciterId, chapter);
      const headers = contentHeaders(content);
      if (request.headers["if-none-match"] === content.etag) return sendEmpty(response, 304, headers);
      status = 200;
      response.writeHead(status, { ...headers, "content-type": "application/json; charset=utf-8" });
      response.end(content.body);
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
    cacheDir: config.cacheDir,
    fetchContent: createLegacyQdcFetcher(),
    revalidateAfterMs: config.revalidateAfterMs,
    maxAgeMs: config.maxAgeMs,
  });
  createServer(createHandler({ cache, ...config })).listen(config.port, () => {
    console.info(JSON.stringify({ event: "listening", port: config.port, source: "legacy-qdc-transitional" }));
  });
}
