import { test } from "node:test";
import assert from "node:assert/strict";
import { fitTimelineScale } from "../src/lib/timeline.ts";

test("Fit keeps an extended edit inside the viewport, including long recordings", () => {
  const width = 800, labels = 136;
  for (const duration of [10, 20, 120, 3600]) {
    const scale = fitTimelineScale(width, labels, duration);
    assert.ok(labels + duration * scale <= width - 60 + 1e-6);
  }
  assert.equal(fitTimelineScale(width, labels, 20), fitTimelineScale(width, labels, 10) / 2);
});

test("Fit follows pane and label resizing and stays finite for a short edit", () => {
  const wide = fitTimelineScale(800, 136, 10);
  assert.ok(fitTimelineScale(600, 136, 10) < wide);
  assert.ok(fitTimelineScale(800, 240, 10) < wide);
  assert.ok(Number.isFinite(fitTimelineScale(300, 280, 0)));
  assert.ok(fitTimelineScale(300, 280, 0) > 0);
  assert.ok(fitTimelineScale(1920, 136, 0.1) <= 500);
});
