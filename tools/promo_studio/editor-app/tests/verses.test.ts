// node --test tests/ : whole-verse ranges and the timeline's add/remove actions.
import { test } from "node:test";
import assert from "node:assert/strict";
import { applyVersePlan, nextVerseRange, verseRange } from "../src/lib/verses.ts";

const verses = [
  { label: "2:16", t0: 1, t1: 11 }, { label: "2:17", t0: 11, t1: 20 }, { label: "2:18", t0: 20, t1: 26 }, { label: "2:19", t0: 26, t1: 40 },
];
const spec = (edit: { in: number; out: number }[], ayah = 16): any => ({ edit, app: { surah: 2, ayah, seconds: 40 } });

test("a verse plays only when a real part of it is in the edit", () => {
  assert.deepEqual(verseRange(spec([{ in: 0, out: 26.2 }]), verses), { start: 16, end: 18 }, "0.2 s into 2:19 is its silence, not the verse");
  assert.deepEqual(verseRange(spec([{ in: 0, out: 27 }]), verses), { start: 16, end: 19 });
  assert.deepEqual(verseRange(spec([{ in: 10.8, out: 19 }]), verses), { start: 17, end: 17 });
});

test("edge actions stay inside the chapter and keep one verse", () => {
  assert.deepEqual(nextVerseRange({ start: 16, end: 18 }, "add-end", 286), { start: 16, end: 19 });
  assert.deepEqual(nextVerseRange({ start: 16, end: 18 }, "remove-start", 286), { start: 17, end: 18 });
  assert.equal(nextVerseRange({ start: 1, end: 3 }, "add-start", 7), null);
  assert.equal(nextVerseRange({ start: 7, end: 7 }, "add-end", 7), null);
  assert.equal(nextVerseRange({ start: 4, end: 4 }, "remove-end", 7), null);
});

test("adding and removing at the edges keeps the cuts in between", () => {
  const s = spec([{ in: 0, out: 5 }, { in: 8, out: 20 }]);
  const plan = (o: object) => ({ start: 16, end: 18, anchor: 16, shift: 0, in: 0, out: 26, seconds: 26, verses, ...o });
  let raw: any = { edit: s.edit, app: s.app };
  applyVersePlan(raw, s, plan({}), "add-end");
  assert.deepEqual(raw.edit, [{ in: 0, out: 5 }, { in: 8, out: 26 }]);
  raw = { edit: s.edit, app: s.app };
  applyVersePlan(raw, s, plan({ start: 17, in: 11 }), "remove-start");
  assert.deepEqual(raw.edit, [{ in: 11, out: 20 }], "the piece wholly before the new start goes");
  raw = { edit: s.edit, app: s.app };
  applyVersePlan(raw, s, plan({ start: 15, anchor: 15, shift: 9.5, in: 0, out: 35.5, seconds: 36 }), "add-start");
  assert.deepEqual(raw.edit, [{ in: 0, out: 14.5 }, { in: 17.5, out: 29.5 }], "an earlier verse moves the take's origin");
  assert.equal(raw.app.ayah, 15);
  assert.equal(raw.app.seconds, 40);
  raw = { edit: s.edit, app: s.app };
  applyVersePlan(raw, s, plan({ start: 17, end: 18, in: 11 }));
  assert.deepEqual(raw.edit, [{ in: 11, out: 26 }], "choosing start/stop makes one continuous range");
});
