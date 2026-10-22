import { test } from "node:test";
import assert from "node:assert/strict";
import { activeContext, INSPECTOR, motionContext, previewContext, selectionContext } from "../src/lib/inspector.ts";
import { apply, phoneMatrix } from "../src/lib/geometry.ts";
import type { Geo, Spec } from "../src/lib/types.ts";

test("selection contexts disappear when their selection is cleared or changes kind", () => {
  const keys = { type: "keys", items: [{ prop: "zoom", t: 1 }] } as const;
  assert.equal(selectionContext({ ...keys, items: [...keys.items] }), "keys");
  assert.equal(selectionContext({ type: "keys", items: [] }), null);
  assert.equal(selectionContext({ type: "piece", i: 2 }), "clip");
  assert.equal(activeContext("keys", null), null);
  assert.equal(activeContext("keys", { type: "piece", i: 0 }), null);
  assert.equal(activeContext(null, { ...keys, items: [...keys.items] }), null, "manual full tabs remain full despite a selection");
  assert.equal(activeContext("sound", null), "sound", "scrubbing does not change sound context");
  assert.equal(INSPECTOR.clip.tab, "recording");
  assert.equal(INSPECTOR.keys.tab, "animate");
  assert.equal(INSPECTOR.sound.tab, "look");
  assert.equal(motionContext("Camera"), "camera");
  assert.equal(motionContext("Phone"), "phone-motion");
  assert.equal(motionContext("Background"), "background-motion");
});

test("preview selection follows the rounded phone through camera and phone transforms", () => {
  const spec = { phone: { height: .8 } } as Spec;
  const geo = { W: 1080, H: 1080, g: { W: 500, H: 1000, bw: 460, bh: 960, body_r: 50 } } as Geo;
  for (const [rotate, phone_tilt, zoom, pan_x] of [[0, 0, 1, .5], [30, -15, 1.4, .4], [-80, 40, 2, .8]]) {
    const state = { rotate, phone_tilt, zoom, pan_x, pan_y: .6, phone_x: .1, phone_y: -.05, phone_scale: .9 };
    const hit = (x: number, y: number) => previewContext(spec, geo, state, ...apply(phoneMatrix(spec, geo, state), x, y));
    assert.equal(hit(250, 500), "phone-look");
    assert.equal(hit(250, 30), "phone-look", "bezel belongs to the phone");
    assert.equal(hit(20, 20), "background-look", "outside the rounded body");
    assert.equal(hit(0, 500), "background-look", "shadow margin is background");
  }
});
