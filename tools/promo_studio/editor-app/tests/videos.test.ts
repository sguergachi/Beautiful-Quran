import { test } from "node:test";
import assert from "node:assert/strict";
import { copyTitle, searchText, videoSlug } from "../src/lib/videos.ts";

test("human video titles produce usable names and searchable transliteration", () => {
  assert.equal(videoSlug("  Ar-Raḥmān • Hani  "), "ar-rahman-hani");
  assert.equal(videoSlug("../../"), "");
  assert.equal(videoSlug("..."), "");
  assert.ok(searchText("Sūrat Ar-Raḥmān · Hani").includes(searchText("rahman")));
});

test("duplicate suggestions skip names already used by earlier copies", () => {
  assert.equal(copyTitle("Ar-Raḥmān", ["ar-rahman", "ar-rahman-copy", "ar-rahman-copy-2"]), "Ar-Raḥmān copy 3");
  assert.equal(copyTitle("Fatiha", []), "Fatiha copy");
});
