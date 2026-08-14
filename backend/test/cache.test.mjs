import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  APP_TO_QDC_RECITER,
  ContentUnavailableError,
  DAY_MS,
  LegacyQdcCache,
  RecitationTimingCache,
  createLegacyRecitationProvider,
  createLegacyQdcFetcher,
} from "../src/cache.mjs";

const BODY = JSON.stringify({ audio_files: [{ id: 1, verse_timings: [] }] });

test("locks stable app reciter IDs to the transitional provider map", () => {
  assert.deepEqual([...APP_TO_QDC_RECITER], [
    [1, 7], [2, 6], [3, 2], [4, 9], [5, 3], [7, 5],
  ]);
});

test("caches one upstream response and collapses concurrent misses", async (context) => {
  const directory = await temporaryDirectory(context);
  let calls = 0;
  const cache = new LegacyQdcCache({
    cacheDir: directory,
    fetchContent: async () => {
      calls += 1;
      await Promise.resolve();
      return BODY;
    },
  });

  const [first, second] = await Promise.all([cache.get(7, 1), cache.get(7, 1)]);
  assert.equal(calls, 1);
  assert.equal(first.body, BODY);
  assert.equal(second.body, BODY);
  assert.equal((await cache.get(7, 1)).cacheStatus, "hit");
});

test("revalidates before seven days and fails closed after expiry", async (context) => {
  const directory = await temporaryDirectory(context);
  let now = 0;
  let fail = false;
  const cache = new LegacyQdcCache({
    cacheDir: directory,
    now: () => now,
    fetchContent: async () => {
      if (fail) throw new Error("offline");
      return BODY;
    },
  });
  await cache.get(7, 1);

  fail = true;
  now = 6 * DAY_MS + 1;
  assert.equal((await cache.get(7, 1)).cacheStatus, "refresh-failed");
  assert.equal((await cache.get(7, 1)).cacheStatus, "refresh-deferred");
  now = 7 * DAY_MS + 1;
  await assert.rejects(cache.get(7, 1), ContentUnavailableError);
});

test("does not serve content that expires while revalidation is failing", async (context) => {
  const directory = await temporaryDirectory(context);
  let now = 0;
  let calls = 0;
  const cache = new LegacyQdcCache({
    cacheDir: directory,
    now: () => now,
    fetchContent: async () => {
      calls += 1;
      if (calls > 1) {
        now = 7 * DAY_MS + 1;
        throw new Error("timed out");
      }
      return BODY;
    },
  });
  await cache.get(7, 1);
  now = 7 * DAY_MS - 1;
  await assert.rejects(cache.get(7, 1), ContentUnavailableError);
});

test("purge deletes cached content", async (context) => {
  const directory = await temporaryDirectory(context);
  let calls = 0;
  const cache = new LegacyQdcCache({
    cacheDir: directory,
    fetchContent: async () => {
      calls += 1;
      return BODY;
    },
  });
  await cache.get(7, 1);
  await cache.clear();
  await cache.get(7, 1);
  assert.equal(calls, 2);
});

test("purge prevents an in-flight fetch from repopulating the cache", async (context) => {
  const directory = await temporaryDirectory(context);
  let releaseFetch;
  let markFetchStarted;
  let calls = 0;
  const fetchPaused = new Promise((resolve) => { releaseFetch = resolve; });
  const fetchStarted = new Promise((resolve) => { markFetchStarted = resolve; });
  const cache = new LegacyQdcCache({
    cacheDir: directory,
    fetchContent: async () => {
      calls += 1;
      if (calls === 1) {
        markFetchStarted();
        await fetchPaused;
      }
      return BODY;
    },
  });

  const pending = cache.get(7, 1);
  await fetchStarted;
  const clearing = cache.clear();
  releaseFetch();
  await Promise.all([assert.rejects(pending, ContentUnavailableError), clearing]);
  assert.equal((await cache.get(7, 1)).cacheStatus, "miss");
  assert.equal(calls, 2);
});

test("does not trust a cache timestamp from the future", async (context) => {
  const directory = await temporaryDirectory(context);
  let now = 1_000;
  let calls = 0;
  const cache = new LegacyQdcCache({
    cacheDir: directory,
    now: () => now,
    fetchContent: async () => {
      calls += 1;
      return BODY;
    },
  });

  await cache.get(7, 1);
  now = 500;
  await cache.get(7, 1);
  assert.equal(calls, 2);
});

test("legacy fetcher constructs only the fixed QDC route", async () => {
  let requested;
  const fetchContent = createLegacyQdcFetcher({
    minIntervalMs: 0,
    fetchImpl: async (url, options) => {
      requested = { url: url.href, options };
      return new Response(BODY, { headers: { "content-type": "application/json" } });
    },
  });

  assert.equal(await fetchContent({ reciterId: 7, chapter: 1 }), BODY);
  assert.equal(
    requested.url,
    "https://api.quran.com/api/qdc/audio/reciters/7/audio_files?chapter_number=1&segments=true",
  );
  assert.equal(requested.options.redirect, "error");
  await assert.rejects(fetchContent({ reciterId: 8, chapter: 1 }), RangeError);
});

test("legacy fetcher stops reading a response that exceeds its size limit", async () => {
  const fetchContent = createLegacyQdcFetcher({
    minIntervalMs: 0,
    maxBytes: 8,
    fetchImpl: async () => new Response(BODY),
  });

  await assert.rejects(fetchContent({ reciterId: 7, chapter: 1 }), /size limit/);
});

test("normalizes all legacy chapters behind an app-reciter resource", async (context) => {
  const directory = await temporaryDirectory(context);
  const chapters = [];
  const rawCache = {
    get: async (reciterId, chapter) => {
      chapters.push([reciterId, chapter]);
      return { body: JSON.stringify({ chapter }) };
    },
  };
  const provider = createLegacyRecitationProvider({
    rawCache,
    normalize: async ({ appReciterId, chapters: payloads }) => JSON.stringify({
      schema_version: 1,
      resource_group: "recitations",
      resource_id: appReciterId,
      records: payloads,
    }),
  });
  const cache = new RecitationTimingCache({ cacheDir: directory, fetchSnapshot: provider });

  const result = JSON.parse((await cache.get(1)).body);
  assert.equal(result.records.length, 114);
  assert.deepEqual(chapters[0], [7, 1]);
  assert.deepEqual(chapters.at(-1), [7, 114]);
  assert.equal((await cache.get(1)).cacheStatus, "hit");
  assert.throws(() => cache.get(6), RangeError);
});

async function temporaryDirectory(context) {
  const directory = await mkdtemp(join(tmpdir(), "beautiful-quran-cache-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}
