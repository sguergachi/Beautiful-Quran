import assert from "node:assert/strict";
import test from "node:test";
import { validateSnapshot } from "../src/normalize.mjs";

test("accepts only the stable normalized timing snapshot contract", () => {
  const snapshot = {
    schema_version: 1,
    resource_group: "recitations",
    resource_id: 1,
    records: [{
      record_type: "timing",
      record_key: "1:1",
      surah_id: 1,
      ayah_number: 1,
      segments: [[1, 0, 1]],
    }],
  };
  assert.throws(() => validateSnapshot(snapshot, 1), /invalid snapshot/);
  assert.equal(validateSnapshot(snapshot, 1, 1), snapshot);
  assert.throws(() => validateSnapshot({ ...snapshot, resource_id: 7 }, 1, 1));
  assert.throws(() => validateSnapshot({ ...snapshot, records: [{}] }, 1, 1));
  assert.throws(() => validateSnapshot({
    ...snapshot,
    records: [snapshot.records[0], snapshot.records[0]],
  }, 1, 1));
});
