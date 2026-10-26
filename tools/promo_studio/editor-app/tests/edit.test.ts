// node --test tests/ : the editing operations the store wraps in undo steps.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import * as E from "../src/lib/edit.ts";
import { fadeAt, parseTime, pieceStart, resolve, soundGain, soundOnset, sourceTime, valueAt, videoTimes } from "../src/lib/model.ts";

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
  assert.deepEqual(raw.edit[1], { in: 5, out: 10, gap: 1 });
  ({ raw } = run(raw, (r) => E.trimPiece(r, 1, "out", 99, 12)));
  assert.equal(raw.edit[1].out, 12, "capped at the take's length");
  ({ raw } = run(raw, (r) => E.trimPiece(r, 0, "out", -3)));
  assert.ok(raw.edit[0].out > raw.edit[0].in, "a trim never empties a piece");
  ({ raw } = run(raw, (r) => E.deletePiece(r, 1)));
  assert.equal(raw.edit.length, 1);
  assert.equal(E.deletePiece(raw, 0), false, "the last piece stays");
});

test("left trims preserve duration, later clips, keys, and source alignment", () => {
  const raw = { edit: [{ in: 2, out: 12 }, { in: 20, out: 25 }], tracks: { zoom: [{ t: 7, v: 2 }] } };
  const before = ctx(raw).spec, keys = JSON.stringify(raw.tracks);
  for (const value of [3, 5, 6]) E.trimPiece(raw, 0, "in", value);
  const after = ctx(raw).spec;
  assert.equal(after.duration, before.duration);
  assert.equal(pieceStart(after, 0), 4);
  assert.equal(pieceStart(after, 1), pieceStart(before, 1));
  assert.deepEqual(sourceTime(after, 3.99), [0, -1]);
  assert.deepEqual(sourceTime(after, 4), [6, 0]);
  assert.deepEqual(sourceTime(after, 7), sourceTime(before, 7));
  assert.deepEqual(videoTimes(after, 2, 12), [[4, 10]]);
  assert.equal(JSON.stringify(raw.tracks), keys);
  E.trimPiece(raw, 0, "in", 0); // restore only as far as the video start
  assert.deepEqual(raw.edit[0], { in: 2, out: 12 });
  E.trimPiece(raw, 1, "in", 22);
  assert.equal(ctx(raw).spec.duration, 15);
  assert.equal(pieceStart(ctx(raw).spec, 1), 12);
  E.trimPiece(raw, 1, "in", 0); // never overlaps the preceding clip
  assert.deepEqual(raw.edit[1], { in: 20, out: 25 });
});

test("splits keep the gap before the first half and never split empty time", () => {
  const base = { edit: [{ in: 4, out: 10, gap: 3 }] };
  let { raw, out } = run(base, (r, c) => E.splitAt(r, c, 2));
  assert.equal(out, null);
  assert.deepEqual(raw, base);
  ({ raw, out } = run(base, (r, c) => E.splitAt(r, c, 5)));
  assert.equal(out, 1);
  assert.deepEqual(raw.edit, [{ in: 4, out: 6, gap: 3 }, { in: 6, out: 10 }]);
  assert.equal(ctx(raw).spec.duration, 9);
  assert.deepEqual(sourceTime(ctx(raw).spec, 5), [6, 1]);
});

test("gaps are silent and shift the fade onset without moving later source spans", () => {
  const s = ctx({ edit: [{ in: 0, out: 2, gap: 3 }, { in: 4, out: 8, gap: 2 }], audio: { fade_in: 1, fade_out: 0 } }).spec;
  assert.equal(s.duration, 11);
  assert.equal(soundOnset(s, 1), 4);
  assert.equal(soundGain(s, 2, 4), 0);
  assert.equal(soundGain(s, 6, 4), 0);
  assert.equal(soundGain(s, 4.5, 4), .5);
  assert.equal(soundGain(s, 7, 4), 1);
  assert.deepEqual(videoTimes(s, 0, 8), [[3, 5], [7, 11]]);
});

test("presets key a push to the active verse and back", () => {
  const geo = { W: 1080, H: 1080, g: meta.g, fps: 60, take: null, frame: "", clip: null, audio: null, peaks: null, plate: { w: 1, h: 1, k: 1 } };
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

test("the fade in starts with the first sound, after the take's silent lead", () => {
  const onset = (edit: { in: number; out: number }[]) => soundOnset(resolve({ edit }, meta), 1);
  assert.equal(onset([{ in: 0, out: 10 }]), 1);
  assert.equal(onset([{ in: 1.5, out: 10 }]), 0, "a cut into the recitation fades from the start");
  assert.ok(Math.abs(onset([{ in: 0.2, out: 0.8 }, { in: 0.5, out: 5 }]) - 1.1) < 1e-9, "silent pieces count");
  const s = resolve({ edit: [{ in: 0, out: 10 }], audio: { fade_in: 0.5, fade_out: 2, gain_db: 6 } }, meta);
  assert.equal(fadeAt(s, 0.5, 1), 0);
  assert.equal(fadeAt(s, 1.25, 1), 0.5);
  assert.equal(fadeAt(s, 5, 1), 1);
  assert.equal(fadeAt(s, 9, 1), 0.5);
  assert.ok(Math.abs(soundGain(s, 5, 1) - 10 ** (6 / 20)) < 1e-9);
  const short = resolve({ edit: [{ in: 0, out: 0.5 }], audio: { fade_in: 0, fade_out: 4 } }, meta);
  assert.equal(fadeAt(short, 0, 1), 1, "a fade longer than the video spans it, like ffmpeg's");
});
