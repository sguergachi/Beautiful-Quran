// Reads {props, g, cases} on stdin, prints what the editor computes for each
// case; test_studio.py compares it with anim.py / compose.Renderer.
import { readFileSync } from "node:fs";
import { sourceTime, stateAt, videoTimes } from "../src/lib/model.ts";
import { bgMatrix, phoneMatrix } from "../src/lib/geometry.ts";

const { props, g, cases } = JSON.parse(readFileSync(0, "utf8"));
const out = cases.map((c: any) => {
  const S = stateAt(c.spec, props, c.t), geo = { W: c.W, H: c.H, g };
  return { state: S, phone: phoneMatrix(c.spec, geo, S), bg: bgMatrix(c.spec, geo, c.plate, S), source: sourceTime(c.spec, c.t)[0], spans: videoTimes(c.spec, c.a, c.b) };
});
process.stdout.write(JSON.stringify(out));
