import { test } from "node:test";
import assert from "node:assert/strict";
import { recordingReference, staleRecordingFields, type StaleApp } from "../src/lib/recorded.ts";

const app = (): StaleApp => ({
  surah: 1, ayah: 1, reciter: "alafasy", theme: "Paper", layout: "Scroll", view: "Both",
  text_size: "default", seconds: 95, capture: "1080p", repeat: "off", repeat_to: null,
  intro: { steps: [], play: true, dwell: 1, goto: "scroll" },
});

test("identical settings have no stale fields", () => {
  assert.equal(staleRecordingFields(app(), app()).size, 0);
});

test("no reference means nothing to mark stale", () => {
  assert.equal(staleRecordingFields(app(), null).size, 0);
  assert.equal(staleRecordingFields(app(), undefined).size, 0);
});

test("each recording setting marks its own field", () => {
  const ref = app();
  const changed: [Partial<StaleApp>, string][] = [
    [{ reciter: "hani" }, "reciter"],
    [{ surah: 2 }, "surah"],
    [{ ayah: 5 }, "ayah"],
    [{ theme: "Royal green" }, "theme"],
    [{ layout: "Mushaf" }, "layout"],
    [{ view: "Arabic" }, "view"],
    [{ text_size: "max" }, "text_size"],
    [{ capture: "2k" }, "capture"],
    [{ seconds: 120 }, "seconds"],
    [{ repeat: "ayah" }, "repeat"],
  ];
  for (const [patch, field] of changed) {
    assert.deepEqual([...staleRecordingFields({ ...app(), ...patch }, ref)], [field]);
  }
  assert.deepEqual([...staleRecordingFields({ ...app(), repeat: "range", repeat_to: 7 }, { ...ref, repeat: "range", repeat_to: 5 })], ["repeat_to"]);
  assert.deepEqual([...staleRecordingFields({ ...app(), intro: { ...app().intro, play: false } }, ref)], ["intro.play"]);
  assert.deepEqual([...staleRecordingFields({ ...app(), intro: { ...app().intro, dwell: 2 } }, ref)], ["intro.dwell"]);
  assert.deepEqual([...staleRecordingFields({ ...app(), intro: { ...app().intro, goto: "rail" } }, ref)], ["intro.goto"]);
  assert.deepEqual(
    [...staleRecordingFields({ ...app(), intro: { ...app().intro, steps: [{ kind: "cover", seconds: 2 }] } }, ref)],
    ["intro.steps"]);
});

test("unknown reference fields are skipped, known defaults still compare", () => {
  assert.equal(staleRecordingFields(app(), {}).size, 0);
  assert.deepEqual([...staleRecordingFields({ ...app(), capture: "2k" }, {})], ["capture"]);
  assert.deepEqual([...staleRecordingFields({ ...app(), intro: { ...app().intro, goto: "rail" } }, { intro: {} })], ["intro.goto"]);
});

test("reference prefers the submitted take on the displayed take's key", () => {
  const take = app(), submitted = { ...app(), seconds: 120 };
  assert.equal(recordingReference(take, submitted), submitted);
  assert.equal(recordingReference(take, null), take);
  assert.equal(recordingReference(undefined, submitted), submitted);
  assert.equal(recordingReference(undefined, null), null);
  const otherKey = { ...app(), reciter: "hani" };
  assert.equal(recordingReference(take, otherKey), take);
});
