import { test } from "node:test";
import assert from "node:assert/strict";
import { buildLabel, buildNote, oldTakeWarning } from "../src/lib/build.ts";
import type { BuildCheck } from "../src/lib/types.ts";

const check = (o: Partial<BuildCheck>): BuildCheck => ({
  state: "current", take: { version: "0.12 (13)", commit: "ac12460149999" }, current: { version: "0.13 (14)", commit: "7dbfdd5dc" }, behind: 0, changes: [], ...o,
});

test("an older take warns, naming both builds and how far behind", () => {
  const w = oldTakeWarning(check({ state: "older", behind: 19 }))!;
  assert.match(w, /older app/);
  assert.match(w, /v0\.12 \(13\) · ac12460/);
  assert.match(w, /v0\.13 \(14\) · 7dbfdd5/);
  assert.match(w, /19 app changes since/);
  assert.match(oldTakeWarning(check({ state: "older", behind: 1 }))!, /1 app change since/);
  assert.doesNotMatch(oldTakeWarning(check({ state: "older", behind: null }))!, /since/);
});

test("a current, unknown or untracked take does not warn", () => {
  for (const state of ["current", "unknown", "untracked"] as const) assert.equal(oldTakeWarning(check({ state })), null);
  assert.equal(oldTakeWarning(null), null);
});

test("builds that cannot be compared get a quiet note, current ones none", () => {
  assert.match(buildNote(check({ state: "untracked" }))!, /not recorded/);
  assert.match(buildNote(check({ state: "unknown" }))!, /cannot be ordered/);
  assert.equal(buildNote(check({ state: "current" })), null);
  assert.equal(buildNote(check({ state: "older" })), null);
});

test("a label shows what is known", () => {
  assert.equal(buildLabel({ version: "0.12 (13)", commit: "ac12460149" }), "v0.12 (13) · ac12460");
  assert.equal(buildLabel({ commit: "ac12460149" }), "ac12460");
  assert.equal(buildLabel(null), "unknown build");
});
