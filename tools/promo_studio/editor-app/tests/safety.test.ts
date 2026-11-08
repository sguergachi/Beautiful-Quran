import { test } from "node:test";
import assert from "node:assert/strict";
import { ago, backoff, decideDraft, describeVersion, draftKey, encodeDraft, fitsKeepalive, parseDraft, reasonLabel, RETRY_MS } from "../src/lib/safety.ts";

test("a failed save retries quickly at first, then every 30 s, and never gives up", () => {
  assert.deepEqual([0, 1, 2, 3, 4, 5, 50].map(backoff), [1000, 2000, 5000, 10000, 30000, 30000, 30000]);
  assert.equal(backoff(-3), RETRY_MS[0]);
});

test("a closing page only beacons a body small enough to be sent", () => {
  assert.ok(fitsKeepalive("x".repeat(1000)));
  assert.ok(!fitsKeepalive("x".repeat(70_000)));
  assert.ok(!fitsKeepalive("é".repeat(40_000)), "bytes, not characters");
});

test("a draft survives a round trip and a damaged one is no draft", () => {
  const raw = { title: "A", edit: [{ in: 0, out: 5 }] };
  const d = parseDraft(encodeDraft("abc123", raw, 1234));
  assert.deepEqual(d, { rev: "abc123", at: 1234, raw });
  assert.equal(parseDraft(null), null);
  assert.equal(parseDraft("{not json"), null);
  assert.equal(parseDraft(JSON.stringify({ rev: "x", at: 1, raw: [1] })), null);
  assert.equal(parseDraft(JSON.stringify({ rev: "x", raw: {} })), null);
  assert.equal(draftKey("01-fatiha"), "promo.draft.v1.01-fatiha");
});

test("a draft is adopted only when it was made against what the server still has", () => {
  const server = { title: "S", edit: [{ in: 0, out: 5 }] }, mine = { title: "Mine", edit: [{ in: 0, out: 5 }] };
  const draft = (rev: string | null, raw: object) => ({ rev, at: 1, raw });
  assert.equal(decideDraft(null, server, "r1"), "none");
  assert.equal(decideDraft(draft("r1", server), server, "r1"), "none", "identical to the server: nothing to recover");
  assert.equal(decideDraft(draft("r1", mine), server, "r1"), "adopt", "plain unsaved edits on the current version");
  assert.equal(decideDraft(draft("r0", mine), server, "r1"), "stash", "the video was saved elsewhere since: keep the draft aside");
  assert.equal(decideDraft(draft(null, mine), server, "r1"), "stash", "no known base: never overwrite");
});

test("versions read plainly", () => {
  assert.equal(describeVersion({ pieces: 2, keys: 29, size: 12_800 }), "2 clips · 29 keys · 12.5 kB");
  assert.equal(describeVersion({ pieces: 1, keys: 1, size: 900 }), "1 clip · 1 key · 900 B");
  assert.equal(reasonLabel("deleted"), "Deleted video");
  assert.equal(reasonLabel("something-new"), "something-new");
  const now = Date.UTC(2026, 10, 8, 12, 0, 0);
  assert.equal(ago(now / 1000 - 30, now), "just now");
  assert.equal(ago(now / 1000 - 12 * 60, now), "12 min ago");
  assert.equal(ago(now / 1000 - 3 * 3600, now), "3 h ago");
});
