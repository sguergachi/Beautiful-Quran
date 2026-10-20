// node --test tests/ : the editing operations the store wraps in undo steps.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import * as E from "../src/lib/edit.ts";
import { parseTime, resolve, valueAt, videoTimes } from "../src/lib/model.ts";

const meta = JSON.parse(execFileSync("python3", ["-c", "import json,spec,phone; print(json.dumps(dict(defaults=spec.DEFAULTS, props=spec.PROPS, themes=spec.THEMES, g=phone.geometry(1080,2400))))"], { cwd: new URL("../..", import.meta.url).pathname }).toString());
const ctx = (raw: any): E.Ctx => ({ spec: resolve(raw, meta), props: meta.props, fps: 60 });
const run = (raw: any, fn: (raw: any, c: E.Ctx) => unknown) => { const r = JSON.parse(JSON.stringify(raw)); const out = fn(r, ctx(r)); return { raw: r, out }; };

test("setProp keys an animated property at t and sets a static one", () => {
  let { raw } = run({ tracks: { zoom: [{ t: 0, v: 1 }] } }, (r, c) => E.setProp(r, c, "zoom", 2, 1));
  assert.deepEqual(raw.tracks.zoom.map((k: any) => [k.t, k.v]), [[0, 1], [1, 2]]);
  ({ raw } = run({}, (r, c) => E.setProp(r, c, "rotate", 99, 1)));
  assert.equal(raw.props.rotate, 30, "clamped to the property's range");
});

test("toggleKey adds the current value and removing the last key keeps it static", () => {
  let { raw, out } = run({ props: { zoom: 1.5 } }, (r, c) => E.toggleKey(r, c, "zoom", 2));
  assert.deepEqual(out, { prop: "zoom", t: 2 });
  assert.equal(raw.tracks.zoom[0].v, 1.5);
  ({ raw } = run(raw, (r, c) => E.toggleKey(r, c, "zoom", 2.004)));
  assert.equal(raw.tracks.zoom, undefined);
  assert.equal(raw.props.zoom, 1.5);
});

test("keys move together, never onto another key, and stay in the video", () => {
  const base = { edit: [{ in: 0, out: 10 }], tracks: { zoom: [{ t: 1, v: 2 }, { t: 3, v: 4 }], rotate: [{ t: 2, v: 5 }] } };
  let { raw, out } = run(base, (r, c) => E.moveKeys(r, c, [{ prop: "zoom", t: 1 }, { prop: "rotate", t: 2 }], 0.5));
  assert.deepEqual(out, [{ prop: "zoom", t: 1.5 }, { prop: "rotate", t: 2.5 }]);
  ({ out } = run(base, (r, c) => E.moveKeys(r, c, [{ prop: "zoom", t: 1 }], 2)));
  assert.equal(out, null, "landing on the key at 3 is refused");
  ({ raw } = run(base, (r, c) => E.moveKeys(r, c, [{ prop: "rotate", t: 2 }], 50)));
  assert.equal(raw.tracks.rotate[0].t, 10);
  // a drag: positions so far, offsets from the original
  ({ out } = run(base, (r, c) => E.moveKeys(r, c, [{ prop: "zoom", t: 1 }], 0.7, [{ prop: "zoom", t: 1 }])));
  assert.deepEqual(out, [{ prop: "zoom", t: 1.7 }]);
});

test("copy / paste keeps the keys' spacing", () => {
  const base = { edit: [{ in: 0, out: 10 }], tracks: { zoom: [{ t: 1, v: 2 }], rotate: [{ t: 1.5, v: 5, ease: "linear" }] } };
  const clip = E.copyKeys(base, [{ prop: "zoom", t: 1 }, { prop: "rotate", t: 1.5 }]);
  const { raw, out } = run(base, (r, c) => E.pasteKeys(r, c, clip, 6));
  assert.deepEqual(out, [{ prop: "zoom", t: 6 }, { prop: "rotate", t: 6.5 }]);
  assert.equal(raw.tracks.rotate[1].ease, "linear");
});

test("deleting keys and easing them", () => {
  const base = { tracks: { zoom: [{ t: 1, v: 2 }, { t: 3, v: 4 }] } };
  let { raw } = run(base, (r) => E.setEase(r, [{ prop: "zoom", t: 3 }], "hold"));
  assert.equal(raw.tracks.zoom[1].ease, "hold");
  ({ raw } = run(raw, (r) => E.setEase(r, [{ prop: "zoom", t: 3 }], "inOutCubic")));
  assert.equal(raw.tracks.zoom[1].ease, undefined, "the default ease is not stored");
  ({ raw } = run(raw, (r) => E.deleteKeys(r, [{ prop: "zoom", t: 1 }, { prop: "zoom", t: 3 }])));
  assert.equal(raw.tracks.zoom, undefined);
  assert.equal(raw.props.zoom, 4);
});

test("split, trim and delete pieces of the recording", () => {
  const base = { edit: [{ in: 0, out: 10 }] };
  let { raw, out } = run(base, (r, c) => E.splitAt(r, c, 4));
  assert.equal(out, 1);
  assert.deepEqual(raw.edit, [{ in: 0, out: 4 }, { in: 4, out: 10 }]);
  ({ out } = run(raw, (r, c) => E.splitAt(r, c, 4.01)));
  assert.equal(out, null, "a split right beside a cut is refused");
  ({ raw } = run(raw, (r) => E.trimPiece(r, 1, "in", 5)));
  assert.deepEqual(raw.edit[1], { in: 5, out: 10 });
  ({ raw } = run(raw, (r) => E.trimPiece(r, 1, "out", 99, 12)));
  assert.equal(raw.edit[1].out, 12, "capped at the take's length");
  ({ raw } = run(raw, (r) => E.trimPiece(r, 0, "out", -3)));
  assert.ok(raw.edit[0].out > raw.edit[0].in, "a trim never empties a piece");
  ({ raw } = run(raw, (r) => E.deletePiece(r, 1)));
  assert.equal(raw.edit.length, 1);
  assert.equal(E.deletePiece(raw, 0), false, "the last piece stays");
});

test("presets key a push to the active verse and back", () => {
  const geo = { W: 1080, H: 1080, g: meta.g, fps: 60, take: null, frame: "", clip: null, audio: null, plate: { w: 1, h: 1, k: 1 } };
  const { raw } = run({ edit: [{ in: 0, out: 10 }] }, (r, c) => E.preset(r, c, geo, "push", 2));
  assert.deepEqual(raw.tracks.zoom.map((k: any) => k.t), [2, 2.9]);
  assert.ok(raw.tracks.zoom[1].v > 1.5);
  const spec = resolve(raw, meta);
  assert.ok(valueAt(spec, meta.props, "zoom", 2.45) > 1 && valueAt(spec, meta.props, "zoom", 2.45) < raw.tracks.zoom[1].v);
});

test("landmarks and time parsing", () => {
  const spec = resolve({ edit: [{ in: 0, out: 4 }, { in: 6, out: 9 }] }, meta);
  const ts = E.landmarks(spec, [{ t0: 1, t1: 3 }, { t0: 3, t1: 7 }], (a, b) => videoTimes(spec, a, b));
  assert.deepEqual(ts, [0, 1, 3, 4, 7]);
  assert.equal(parseTime("0:12.5"), 12.5);
  assert.equal(parseTime("3"), 3);
  assert.ok(Number.isNaN(parseTime("abc")));
});
