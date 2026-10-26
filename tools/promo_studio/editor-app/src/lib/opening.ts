import type { RecordingStep, Spec } from "./types.ts";

/** Read old checklist drafts without losing their chosen screens. */
export function openingSteps(s: Spec): RecordingStep[] {
  const it = s.app.intro;
  if (it.steps != null) return it.steps;
  const steps: RecordingStep[] = [];
  for (const kind of ["cover", "home"] as const) if (it[kind]) steps.push({ kind, seconds: it[kind] });
  if (steps.length || it.header) steps.push({ kind: "open", seconds: 0.5, via: it.home ? it.open_by : "direct" });
  if (it.header) steps.push({ kind: "header", seconds: 1 });
  for (const kind of ["settings", "roots"] as const) if (it[kind]) steps.push({ kind, seconds: it[kind] });
  if (it.repeat) steps.push({ kind: "repeat", seconds: 2 });
  return steps;
}
export const openingEstimate = (s: Spec) => s.app.intro.dwell + openingSteps(s).reduce((t, x) => t + x.seconds + (x.kind === "wait" ? 0 : 2.5), 0);
