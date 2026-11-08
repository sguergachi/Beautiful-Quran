// The playback engine's pure parts: the sound schedule and the blur kernel.
import { test } from "node:test";
import assert from "node:assert/strict";
import { schedule } from "../src/state/sound.ts";
import { gaussTaps } from "../src/lib/glpreview.ts";

test("the sound schedule plays each piece at its video time, from the playhead", () => {
  const spec = { edit: [{ in: 3, out: 6, gap: 2 }, { in: 8, out: 10, gap: 1 }, { in: 10, out: 12 }] };
  // from 0: piece 1 heard after its 2 s gap, piece 2 after another 1 s, piece 3 straight on
  assert.deepEqual(schedule(spec, 0, 100, 60), [
    { when: 102, offset: 3, dur: 3 }, { when: 106, offset: 8, dur: 2 }, { when: 108, offset: 10, dur: 2 },
  ]);
  // from inside piece 1: its rest, starting now
  const mid = schedule(spec, 3.5, 100, 60);
  assert.deepEqual(mid[0], { when: 100, offset: 4.5, dur: 1.5 });
  assert.equal(mid.length, 3);
  // finished pieces are skipped; a take shorter than the edit is clipped, not overrun
  assert.deepEqual(schedule(spec, 7, 0, 11), [{ when: 0, offset: 9, dur: 1 }, { when: 1, offset: 10, dur: 1 }]);
  assert.deepEqual(schedule(spec, 99, 0, 60), []);
});

test("blur taps are a normalised Gaussian with two texels per tap", () => {
  for (const sigma of [0.5, 3, 12.5, 20]) {
    const { w, o } = gaussTaps(sigma);
    const total = w[0] + 2 * w.slice(1).reduce((a, b) => a + b, 0);
    assert.ok(Math.abs(total - 1) < 1e-6, `sigma ${sigma}: ${total}`);
    assert.equal(o[0], 0);
    for (let i = 1; i < o.length; i++) assert.ok(o[i] > o[i - 1], "offsets grow");
    assert.ok(w.length <= 64, "fits the shader's tap array");
  }
});
