// The app build a take was recorded with, against the app now.
import type { AppBuild, BuildCheck } from "./types.ts";

/** `v0.12 (13) · ac1246014`, or what is known of it. */
export function buildLabel(b: AppBuild | null | undefined): string {
  const v = b?.version ? `v${b.version}` : "", c = b?.commit ? b.commit.slice(0, 7) : "";
  return [v, c].filter(Boolean).join(" · ") || "unknown build";
}

/** The warning for an old take, or null when there is nothing to warn about. */
export function oldTakeWarning(check: BuildCheck | null | undefined): string | null {
  if (check?.state !== "older") return null;
  const n = check.behind;
  const since = n ? ` · ${n} app change${n === 1 ? "" : "s"} since` : "";
  return `Recorded with an older app: ${buildLabel(check.take)}, now ${buildLabel(check.current)}${since}`;
}

/** One quiet line about a take whose build cannot be compared, or null. */
export function buildNote(check: BuildCheck | null | undefined): string | null {
  if (!check) return null;
  if (check.state === "untracked") return "The app build was not recorded for this take (it predates build tracking).";
  if (check.state === "unknown") return `Recorded with ${buildLabel(check.take)}; it cannot be ordered against the current app (${buildLabel(check.current)}).`;
  return null;
}
