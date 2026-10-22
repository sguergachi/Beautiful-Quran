// Inspector focus follows deliberate actions, never playback or pointer hover.
import { apply, invert, phoneMatrix } from "./geometry.ts";
import type { Geo, Selection, Spec, State } from "./types.ts";

export const INSPECTOR = {
  keys: { tab: "animate", title: "Selected keys" },
  clip: { tab: "recording", title: "Selected clip" },
  camera: { tab: "animate", title: "Camera motion" },
  "phone-motion": { tab: "animate", title: "Phone motion" },
  "background-motion": { tab: "animate", title: "Background animation" },
  "phone-look": { tab: "look", title: "Phone appearance" },
  "background-look": { tab: "look", title: "Background appearance" },
  sound: { tab: "look", title: "Sound" },
  canvas: { tab: "look", title: "Canvas" },
  contents: { tab: "recording", title: "Take contents" },
} as const;
export type InspectorContext = keyof typeof INSPECTOR;

export const selectionContext = (sel: Selection): InspectorContext | null => sel?.type === "piece" ? "clip" : sel?.type === "keys" && sel.items.length ? "keys" : null;
export const motionContext = (group: string): InspectorContext => group === "Phone" ? "phone-motion" : group === "Background" ? "background-motion" : "camera";
export const activeContext = (context: InspectorContext | null, sel: Selection) => context === "keys" || context === "clip" ? (selectionContext(sel) === context ? context : null) : context;

/** Hit the transformed, rounded phone body; its surrounding shadow is background. */
export function previewContext(spec: Spec, geo: Geo, state: State, x: number, y: number): InspectorContext {
  const [px, py] = apply(invert(phoneMatrix(spec, geo, state)), x, y), g = geo.g;
  const qx = Math.abs(px - g.W / 2) - (g.bw / 2 - g.body_r), qy = Math.abs(py - g.H / 2) - (g.bh / 2 - g.body_r);
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) <= g.body_r ? "phone-look" : "background-look";
}
