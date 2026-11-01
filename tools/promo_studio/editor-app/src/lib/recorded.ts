// Recording settings vs what the take was recorded with. Gold dots mark what
// changed since, so nothing reaches the emulator by surprise.
import type { Spec } from "./types.ts";

export interface StaleStep { kind: string; seconds: number; via?: string; word?: number }
export interface StaleIntro { steps?: StaleStep[] | null; play?: boolean; dwell?: number; goto?: string }
// What recording comparisons read: Spec["app"] fits, and so do older takes'
// partial records, whose unknown fields are skipped rather than dotted.
export interface StaleApp {
  surah?: number; ayah?: number; reciter?: string; theme?: string; layout?: string; view?: string;
  text_size?: string; seconds?: number; capture?: string; repeat?: string; repeat_to?: number | null;
  intro?: StaleIntro;
}

const numEq = (a: number, b: number) => Math.abs(a - b) < 1e-6;

// Canonical step form: key order must not matter, since takes carry Python's.
const stepKey = (s: StaleStep) =>
  [s.kind, +(+s.seconds).toFixed(3), s.kind === "open" ? (s.via ?? "direct") : "", s.kind === "roots" ? (s.word ?? 1) : ""].join("|");

const stepsEq = (a: StaleStep[] | null | undefined, b: StaleStep[] | null | undefined) => {
  const x = a ?? [], y = b ?? [];
  return x.length === y.length && x.every((s, i) => stepKey(s) === stepKey(y[i]));
};

/** Field ids for recording settings that differ from the reference take. */
export function staleRecordingFields(current: StaleApp, ref?: StaleApp | null): Set<string> {
  const out = new Set<string>();
  if (!ref) return out;
  if (ref.reciter !== undefined && current.reciter !== ref.reciter) out.add("reciter");
  if (ref.surah !== undefined && (current.surah === undefined || !numEq(current.surah, ref.surah))) out.add("surah");
  if (ref.ayah !== undefined && (current.ayah === undefined || !numEq(current.ayah, ref.ayah))) out.add("ayah");
  if (ref.theme !== undefined && current.theme !== ref.theme) out.add("theme");
  if (ref.layout !== undefined && current.layout !== ref.layout) out.add("layout");
  if (ref.view !== undefined && current.view !== ref.view) out.add("view");
  if (ref.text_size !== undefined && current.text_size !== ref.text_size) out.add("text_size");
  if ((current.capture ?? "1080p") !== (ref.capture ?? "1080p")) out.add("capture");
  if (ref.seconds !== undefined && (current.seconds === undefined || !numEq(current.seconds, ref.seconds))) out.add("seconds");
  if (ref.repeat !== undefined && current.repeat !== ref.repeat) out.add("repeat");
  if ((current.repeat_to ?? null) !== (ref.repeat_to ?? null)) out.add("repeat_to");
  const ci = current.intro ?? {}, ri = ref.intro ?? {};
  if (ri.play !== undefined && ci.play !== ri.play) out.add("intro.play");
  if (ri.dwell !== undefined && (ci.dwell === undefined || !numEq(ci.dwell, ri.dwell))) out.add("intro.dwell");
  if ((ci.goto ?? "scroll") !== (ri.goto ?? "scroll")) out.add("intro.goto");
  if (ri.steps !== undefined && !stepsEq(ci.steps, ri.steps)) out.add("intro.steps");
  return out;
}

const sameTakeKey = (a: StaleApp, b: StaleApp) => {
  const fields = staleRecordingFields(a, b);
  fields.delete("seconds");            // the one recording setting outside the take key
  return fields.size === 0;
};

/** The settings to compare against: the last submitted take when it targets
 *  the displayed take's key (a newer submit still recording), else the
 *  displayed take's own recorded settings. */
export function recordingReference(take?: StaleApp | null, submitted?: StaleApp | null): StaleApp | null {
  if (submitted && (!take || sameTakeKey(submitted, take))) return submitted;
  return take ?? null;
}

// The app settings a video was last submitted with, per video.
const submittedKey = (name: string) => `promo.submitted.${name}`;
export function loadSubmitted(name: string | null): StaleApp | null {
  if (!name || typeof localStorage === "undefined") return null;
  try {
    const v = JSON.parse(localStorage.getItem(submittedKey(name)) || "null") as { app?: StaleApp } | null;
    return v?.app ?? null;
  } catch { return null; }
}
export function saveSubmitted(name: string, app: Spec["app"]): void {
  if (typeof localStorage === "undefined") return;
  try { localStorage.setItem(submittedKey(name), JSON.stringify({ app })); } catch { /* private mode: dots just won't survive */ }
}
