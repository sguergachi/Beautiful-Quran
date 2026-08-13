import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, readdir, rename, unlink, writeFile } from "node:fs/promises";
import { join } from "node:path";

export const DAY_MS = 24 * 60 * 60 * 1000;
export const QDC_RECITER_IDS = new Set([2, 3, 5, 6, 7, 9]);

export class ContentUnavailableError extends Error {
  constructor(message, cause) {
    super(message, { cause });
    this.code = "content_unavailable";
    this.status = 503;
  }
}

export function validateQdcKey(reciterId, chapter) {
  if (!QDC_RECITER_IDS.has(reciterId)) throw new RangeError("Unsupported reciter");
  if (!Number.isInteger(chapter) || chapter < 1 || chapter > 114) {
    throw new RangeError("Chapter must be between 1 and 114");
  }
  return `${reciterId}-${chapter}`;
}

/**
 * Transitional, disk-backed cache for the legacy unauthenticated QDC endpoint.
 * The authenticated QF adapter will replace this source after approval.
 */
export class LegacyQdcCache {
  constructor({
    cacheDir,
    fetchContent,
    now = Date.now,
    revalidateAfterMs = 6 * DAY_MS,
    maxAgeMs = 7 * DAY_MS,
    retryAfterFailureMs = 15 * 60 * 1000,
  }) {
    if (revalidateAfterMs >= maxAgeMs || maxAgeMs > 7 * DAY_MS) {
      throw new RangeError("Cache must revalidate before the seven-day limit");
    }
    this.cacheDir = cacheDir;
    this.fetchContent = fetchContent;
    this.now = now;
    this.revalidateAfterMs = revalidateAfterMs;
    this.maxAgeMs = maxAgeMs;
    this.retryAfterFailureMs = retryAfterFailureMs;
    this.inFlight = new Map();
    this.retryAfter = new Map();
    this.generation = 0;
    this.purgePromise = null;
  }

  async get(reciterId, chapter) {
    const key = validateQdcKey(reciterId, chapter);
    const generation = this.generation;
    if (this.purgePromise) throw new ContentUnavailableError("QDC content is being purged");
    const stored = await this.#read(key);
    if (generation !== this.generation || this.purgePromise) {
      throw new ContentUnavailableError("QDC content was purged");
    }
    const nowMs = this.now();
    const cached = stored?.fetchedAtMs <= nowMs ? stored : null;
    const ageMs = cached ? nowMs - cached.fetchedAtMs : Infinity;
    if (cached && ageMs <= this.revalidateAfterMs) return result(cached, "hit");
    if (nowMs < (this.retryAfter.get(key) ?? 0)) {
      if (cached && ageMs <= this.maxAgeMs) return result(cached, "refresh-deferred");
      throw new ContentUnavailableError("No current QDC content is available");
    }

    try {
      return await this.#singleFlight(key, async () => {
        const body = await this.fetchContent({ reciterId, chapter });
        const entry = {
          version: 1,
          fetchedAtMs: this.now(),
          sha256: sha256(body),
          body,
        };
        await this.#write(key, entry, generation);
        this.retryAfter.delete(key);
        return result(entry, cached ? "refreshed" : "miss");
      });
    } catch (error) {
      if (generation !== this.generation) {
        throw new ContentUnavailableError("QDC content was purged", error);
      }
      this.retryAfter.set(key, this.now() + this.retryAfterFailureMs);
      const fallbackAgeMs = cached ? this.now() - cached.fetchedAtMs : Infinity;
      if (cached && fallbackAgeMs >= 0 && fallbackAgeMs <= this.maxAgeMs) {
        return result(cached, "refresh-failed");
      }
      throw new ContentUnavailableError("No current QDC content is available", error);
    }
  }

  clear() {
    if (this.purgePromise) return this.purgePromise;
    this.purgePromise = this.#clear().finally(() => { this.purgePromise = null; });
    return this.purgePromise;
  }

  async #clear() {
    this.generation += 1;
    this.retryAfter.clear();
    await Promise.allSettled(this.inFlight.values());
    await mkdir(this.cacheDir, { recursive: true });
    const names = await readdir(this.cacheDir);
    await Promise.all(
      names
        .filter((name) => name.endsWith(".json") || name.endsWith(".tmp"))
        .map((name) => unlinkIfExists(join(this.cacheDir, name))),
    );
  }

  async #singleFlight(key, load) {
    const existing = this.inFlight.get(key);
    if (existing) return existing;
    const pending = load().finally(() => this.inFlight.delete(key));
    this.inFlight.set(key, pending);
    return pending;
  }

  async #read(key) {
    try {
      const entry = JSON.parse(await readFile(join(this.cacheDir, `${key}.json`), "utf8"));
      if (
        entry.version !== 1 ||
        !Number.isFinite(entry.fetchedAtMs) ||
        typeof entry.body !== "string" ||
        sha256(entry.body) !== entry.sha256
      ) return null;
      return entry;
    } catch (error) {
      if (error.code === "ENOENT" || error instanceof SyntaxError) return null;
      throw error;
    }
  }

  async #write(key, entry, generation) {
    await mkdir(this.cacheDir, { recursive: true });
    const target = join(this.cacheDir, `${key}.json`);
    const temporary = `${target}.${process.pid}.${randomUUID()}.tmp`;
    try {
      await writeFile(temporary, JSON.stringify(entry), { encoding: "utf8", mode: 0o600 });
      if (generation !== this.generation) throw new ContentUnavailableError("QDC content was purged");
      await rename(temporary, target);
      if (generation !== this.generation) {
        await unlinkIfExists(target);
        throw new ContentUnavailableError("QDC content was purged");
      }
    } finally {
      await unlinkIfExists(temporary);
    }
  }
}

async function unlinkIfExists(path) {
  try {
    await unlink(path);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
}

export function createLegacyQdcFetcher({
  fetchImpl = fetch,
  upstreamBase = "https://api.quran.com",
  timeoutMs = 10_000,
  maxBytes = 10 * 1024 * 1024,
  minIntervalMs = 250,
} = {}) {
  let queue = Promise.resolve();
  let lastStartedAt = 0;
  const fetchOne = async ({ reciterId, chapter }) => {
    validateQdcKey(reciterId, chapter);
    const waitMs = Math.max(0, lastStartedAt + minIntervalMs - Date.now());
    if (waitMs) await new Promise((resolve) => setTimeout(resolve, waitMs));
    lastStartedAt = Date.now();
    const url = new URL(
      `/api/qdc/audio/reciters/${reciterId}/audio_files?chapter_number=${chapter}&segments=true`,
      upstreamBase,
    );
    const response = await fetchImpl(url, {
      headers: { accept: "application/json", "user-agent": "Beautiful-Quran-Cache/1" },
      redirect: "error",
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) throw new Error(`QDC upstream returned ${response.status}`);
    const body = await readBoundedBody(response, maxBytes);
    const parsed = JSON.parse(body);
    if (!parsed || !Array.isArray(parsed.audio_files)) {
      throw new Error("QDC response did not contain audio_files");
    }
    return body;
  };
  return (resource) => {
    const request = queue.then(() => fetchOne(resource));
    queue = request.catch(() => {});
    return request;
  };
}

async function readBoundedBody(response, maxBytes) {
  const declaredBytes = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredBytes) && declaredBytes > maxBytes) {
    throw new Error("QDC response exceeded size limit");
  }
  if (!response.body) throw new Error("QDC response had no body");

  const reader = response.body.getReader();
  const chunks = [];
  let totalBytes = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    totalBytes += value.byteLength;
    if (totalBytes > maxBytes) {
      await reader.cancel();
      throw new Error("QDC response exceeded size limit");
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks, totalBytes).toString("utf8");
}

function result(entry, cacheStatus) {
  return {
    body: entry.body,
    cacheStatus,
    etag: `"${entry.sha256}"`,
    fetchedAtMs: entry.fetchedAtMs,
  };
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
