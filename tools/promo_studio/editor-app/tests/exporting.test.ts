// node --test tests/ : export file names match formats.py; sizes are sane.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { estimateBytes, exportFilename, exportFps } from "../src/lib/exporting.ts";
import type { ExportOptions } from "../src/lib/types.ts";

const cwd = new URL("../..", import.meta.url).pathname;
const py = (code: string) => JSON.parse(execFileSync("python3", ["-c", `import json,formats,spec\n${code}`], { cwd }).toString());
const m = py("print(json.dumps(formats.meta()))");
const aspects = py("print(json.dumps(spec.ASPECTS))");

test("file names match formats.filename", () => {
  const cases: [string, number, Partial<ExportOptions>][] = [
    ["1:1", 60, {}], ["9:16", 60, { format: "hevc", scale: 2 / 3, fps: 30 }], ["4:5", 30, { format: "gif", scale: 0.5, fps: 20 }],
    ["16:9", 60, { format: "prores", fps: 60 }], ["1:1", 30, { format: "vp9", scale: 4 / 3, fps: 60 }],
  ];
  for (const [aspect, fps, partial] of cases) {
    const o = { ...m.defaults, ...partial } as ExportOptions;
    const want = py(`s=spec.resolve({"aspect":"${aspect}","fps":${fps}}); s["name"]="v"; print(json.dumps(formats.filename(s, formats.options(json.loads('${JSON.stringify(o)}')))))`);
    assert.equal(exportFilename("v", { aspect, fps }, o, m, aspects), want, JSON.stringify(partial));
  }
});

test("estimates order the formats sensibly", () => {
  const spec = { aspect: "1:1", fps: 60, duration: 30 };
  const size = (p: Partial<ExportOptions>) => estimateBytes(spec, { ...m.defaults, ...p }, m, aspects);
  assert.ok(size({}) > 5e6 && size({}) < 2e7, `H.264 high, 30 s: ${size({})}`);
  assert.ok(size({ quality: "small" }) < size({}) && size({}) < size({ quality: "best" }));
  assert.ok(size({ format: "av1" }) < size({}) && size({ format: "prores" }) > 20 * size({}));
  assert.ok(size({ scale: 0.5 }) < size({}) / 2);
  assert.equal(exportFps({ ...m.defaults, format: "gif", fps: 60 }, spec, m), 25);
});
