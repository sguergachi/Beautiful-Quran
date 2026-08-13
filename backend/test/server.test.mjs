import assert from "node:assert/strict";
import { createServer } from "node:http";
import test from "node:test";
import { createHandler } from "../src/server.mjs";

test("serves only the bounded legacy route with conditional caching", async (context) => {
  const content = {
    body: JSON.stringify({ audio_files: [] }),
    cacheStatus: "hit",
    etag: '"abc"',
    fetchedAtMs: 0,
  };
  const cache = { get: async () => content, clear: async () => {} };
  const base = await start(context, cache);

  const first = await fetch(`${base}/v1/legacy-qdc/recitations/7/chapters/1/audio-files`);
  assert.equal(first.status, 200);
  assert.equal(first.headers.get("x-content-source"), "legacy-qdc-transitional");
  assert.deepEqual(await first.json(), { audio_files: [] });

  const unchanged = await fetch(`${base}/v1/legacy-qdc/recitations/7/chapters/1/audio-files`, {
    headers: { "if-none-match": '"abc"' },
  });
  assert.equal(unchanged.status, 304);
  assert.equal((await fetch(`${base}/v1/legacy-qdc/recitations/999/chapters/1/audio-files`)).status, 400);
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

async function start(context, cache, adminToken = "", requestsPerMinute = 100) {
  const server = createServer(createHandler({
    cache,
    adminToken,
    allowedOrigins: new Set(),
    requestsPerMinute,
  }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();
  return `http://127.0.0.1:${address.port}`;
}
