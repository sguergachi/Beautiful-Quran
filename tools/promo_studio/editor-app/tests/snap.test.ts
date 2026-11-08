import { test } from "node:test";
import assert from "node:assert/strict";
import { apply, phoneCentre, phoneMatrix, snapPhoneToCentre } from "../src/lib/geometry.ts";
import type { Geo, Spec, State } from "../src/lib/types.ts";

const spec = { phone: { height: .8 } } as Spec;
const geo = { W: 1080, H: 1920, g: { W: 500, H: 1000, bw: 460, bh: 960, body_r: 50 } } as Geo;
const state = (o: Partial<State>) => ({ rotate: 0, phone_tilt: 0, zoom: 1, pan_x: .5, pan_y: .5, phone_x: 0, phone_y: 0, phone_scale: 1, ...o }) as State;

test("phoneCentre is where the phone's own centre is drawn, through camera and tilt", () => {
  for (const o of [{}, { rotate: 30, phone_tilt: -15, zoom: 1.4, pan_x: .4, pan_y: .3 }, { rotate: -80, phone_tilt: 40, zoom: 2, pan_x: .3 }]) {
    const S = state({ ...o, phone_x: .1, phone_y: -.05, phone_scale: .9 });
    const [x, y] = apply(phoneMatrix(spec, geo, S), geo.g.W / 2, geo.g.H / 2), [cx, cy] = phoneCentre(geo, S, S.phone_x, S.phone_y);
    assert.ok(Math.hypot(x - cx, y - cy) < 1e-6, JSON.stringify(o));
  }
});

test("Shift snaps each axis to the frame's centre line, only when close", () => {
  for (const o of [{}, { rotate: 30, zoom: 1.4, pan_x: .4, pan_y: .6 }, { rotate: -80, zoom: 2 }]) {
    const S = state(o);
    // the phone a few px from both lines snaps onto the centre cross
    const [ax, ay] = phoneCentre(geo, S, 0, 0), [bx, by] = phoneCentre(geo, S, .002, .002);
    const both = snapPhoneToCentre(geo, S, .002, .002, Math.max(Math.hypot(ax - geo.W / 2, ay - geo.H / 2), Math.hypot(bx - geo.W / 2, by - geo.H / 2)) + 1);
    const [cx, cy] = phoneCentre(geo, S, both.x, both.y);
    assert.ok(both.snapX && both.snapY, JSON.stringify(o));
    assert.ok(Math.abs(cx - geo.W / 2) < 1e-6 && Math.abs(cy - geo.H / 2) < 1e-6, "lands on the centre cross");
  }
  // unrotated: x is 8 frame-px off its line, y is far from its line
  const S = state({}), x = 8 / geo.W, thr = 12;
  const one = snapPhoneToCentre(geo, S, x, .2, thr);
  assert.deepEqual([one.snapX, one.snapY, one.y], [true, false, .2], "only the near axis snaps; the far one stays free");
  assert.ok(Math.abs(one.x) < 1e-12, "and lands exactly on the line");
  const none = snapPhoneToCentre(geo, S, .2, .2, thr);
  assert.deepEqual([none.snapX, none.snapY, none.x, none.y], [false, false, .2, .2], "nothing snaps when far");
});
