import assert from "node:assert/strict";
import { createServer } from "node:http";
import test from "node:test";
import { createHandler } from "../src/server.mjs";

test("never exposes the raw legacy provider", async (context) => {
  const cache = { clear: async () => {} };
  const base = await start(context, cache);

  assert.equal(
    (await fetch(`${base}/v1/legacy-qdc/recitations/7/chapters/1/audio-files`)).status,
    404,
  );
  assert.equal((await fetch(`${base}/v1/legacy-qdc/recitations/999/chapters/1/audio-files`)).status, 404);
  assert.equal((await fetch(`${base}/anything?url=https://example.com`)).status, 404);
});

test("protects the purge endpoint", async (context) => {
  let cleared = false;
  const cache = { get: async () => {}, clear: async () => { cleared = true; } };
  const base = await start(context, cache, "secret");
  assert.equal((await fetch(`${base}/admin/cache`, { method: "DELETE" })).status, 401);
  assert.equal((await fetch(`${base}/admin/cache`, {
    method: "DELETE",
    headers: { authorization: "Bearer secret" },
  })).status, 204);
  assert.equal(cleared, true);
});

test("public rate limiting cannot block an authorized purge", async (context) => {
  let cleared = false;
  const cache = {
    get: async () => ({ body: "{}", cacheStatus: "hit", etag: '"a"', fetchedAtMs: 0 }),
    clear: async () => { cleared = true; },
  };
  const base = await start(context, cache, "secret", 1);
  assert.equal((await fetch(`${base}/anything`)).status, 404);
  assert.equal((await fetch(`${base}/anything`)).status, 429);
  assert.equal((await fetch(`${base}/admin/cache`, {
    method: "DELETE",
    headers: { authorization: "Bearer secret" },
  })).status, 204);
  assert.equal(cleared, true);
});

test("emulates Content Sync over normalized timing snapshots", async (context) => {
  const snapshot = JSON.stringify({
    schema_version: 1,
    resource_group: "recitations",
    resource_id: 1,
    records: [{
      record_type: "timing",
      record_key: "1:1",
      surah_id: 1,
      ayah_number: 1,
      segments: [[1, 0, 1]],
    }],
  });
  const content = {
    body: snapshot,
    cacheStatus: "hit",
    etag: '"abc"',
    fetchedAtMs: 42,
  };
  const timingCache = { get: async () => content, clear: async () => {} };
  const base = await start(
    context,
    { get: async () => content, clear: async () => {} },
    "",
    100,
    timingCache,
    () => 1_000,
  );

  const bootstrap = await fetch(
    `${base}/api/v4/resources/sync?bootstrap=true&resources=recitations:1`,
  );
  assert.equal(bootstrap.status, 200);
  assert.equal(bootstrap.headers.get("cache-control"), "no-store");
  const first = await bootstrap.json();
  assert.equal(first.sync.next_sync_token, "abc");
  assert.equal(first.sync.content_age_ms, 958);
  assert.equal(first.sync.mutations[0].type, "RESOURCE_CREATE");
  assert.equal(
    first.sync.mutations[0].snapshot_url,
    "/api/v4/resources/snapshots/recitations/1",
  );

  const unchanged = await fetch(
    `${base}/api/v4/resources/sync?sync_token=abc&resources=recitations:1`,
  );
  assert.deepEqual((await unchanged.json()).sync.mutations, []);

  const fetchedSnapshot = await fetch(
    `${base}/api/v4/resources/snapshots/recitations/1`,
  );
  assert.equal(fetchedSnapshot.headers.get("etag"), '"abc"');
  assert.deepEqual(await fetchedSnapshot.json(), JSON.parse(snapshot));
});

async function start(
  context,
  cache,
  adminToken = "",
  requestsPerMinute = 100,
  timingCache,
  now = Date.now,
) {
  const server = createServer(createHandler({
    cache,
    timingCache,
    adminToken,
    allowedOrigins: new Set(),
    requestsPerMinute,
    now,
  }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();
  return `http://127.0.0.1:${address.port}`;
}
