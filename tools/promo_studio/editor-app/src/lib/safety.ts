// Keeping edits safe in the browser: what the editor does when a save fails,
// when another tab saved first, or when the tab dies with unsaved edits.
// Pure helpers, tested in tests/safety.test.ts; the store wires them up.
import type { RawSpec } from "./types.ts";

/** Wait before the next automatic retry of a failed save (ms): quick at first, then every 30 s, never giving up. */
export const RETRY_MS = [1000, 2000, 5000, 10000, 30000];
export const backoff = (attempt: number) => RETRY_MS[Math.min(Math.max(0, attempt), RETRY_MS.length - 1)];

/** A page that is closing can only beacon a small body (fetch keepalive is capped near 64 kB). */
export const KEEPALIVE_LIMIT = 60_000;
export const fitsKeepalive = (body: string) => new TextEncoder().encode(body).length <= KEEPALIVE_LIMIT;

/** Unsaved edits kept in this browser, so a crash or a failed save loses nothing. */
export interface Draft { rev: string | null; at: number; raw: RawSpec }
export const draftKey = (name: string) => `promo.draft.v1.${name}`;

export function encodeDraft(rev: string | null, raw: RawSpec, at = Date.now()): string {
  return JSON.stringify({ rev, at, raw } satisfies Draft);
}
export function parseDraft(text: string | null): Draft | null {
  if (!text) return null;
  try {
    const d = JSON.parse(text);
    if (d && typeof d === "object" && d.raw && typeof d.raw === "object" && !Array.isArray(d.raw) && typeof d.at === "number") {
      return { rev: typeof d.rev === "string" ? d.rev : null, at: d.at, raw: d.raw };
    }
  } catch { /* a damaged draft is no draft */ }
  return null;
}

/** What to do with a draft found when a video opens: nothing (it is what the server has),
 *  adopt it (it was made against the current revision, so these are plain unsaved edits),
 *  or stash it (the video was saved elsewhere since: keep the draft in the history, don't overwrite). */
export function decideDraft(draft: Draft | null, serverRaw: RawSpec, serverRev: string | null): "none" | "adopt" | "stash" {
  if (!draft) return "none";
  if (JSON.stringify(draft.raw) === JSON.stringify(serverRaw)) return "none";
  return draft.rev !== null && draft.rev === serverRev ? "adopt" : "stash";
}

export interface Version { id: string; at: number; rev: string; reason: string; title: string | null; pieces: number; keys: number; size: number }

const REASONS: Record<string, string> = {
  save: "Autosave", replaced: "Replaced by a save from elsewhere", "before-restore": "Before a restore", deleted: "Deleted video",
  unsaved: "Unsaved copy kept for you", corrupt: "Damaged file", created: "Created",
};
export const reasonLabel = (reason: string) => REASONS[reason] ?? reason;

export function describeVersion(v: Pick<Version, "pieces" | "keys" | "size">): string {
  const kb = v.size < 1024 ? `${v.size} B` : `${(v.size / 1024).toFixed(1)} kB`;
  return `${v.pieces} clip${v.pieces === 1 ? "" : "s"} · ${v.keys} key${v.keys === 1 ? "" : "s"} · ${kb}`;
}

/** "just now", "12 min ago", "3 h ago", or the date. */
export function ago(atSeconds: number, nowMs = Date.now()): string {
  const s = Math.max(0, nowMs / 1000 - atSeconds);
  if (s < 90) return "just now";
  if (s < 5400) return `${Math.round(s / 60)} min ago`;
  if (s < 129600) return `${Math.round(s / 3600)} h ago`;
  return new Date(atSeconds * 1000).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}
