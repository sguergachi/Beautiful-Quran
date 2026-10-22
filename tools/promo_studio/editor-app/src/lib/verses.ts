import { r3 } from "./model.ts";
import type { Piece, RawSpec, Spec, Timeline, VersePlan } from "./types.ts";

export type VerseAction = "add-start" | "remove-start" | "add-end" | "remove-end";
export interface VerseRange { start: number; end: number }

/** A verse plays when half a second of it (or all of a shorter one) is in a
 * piece: an out point a few frames into the next verse's silence doesn't count. */
const PLAYED = 0.5;
const plays = (v: Timeline["verses"][number], p: Piece) => Math.min(v.t1, p.out) - Math.max(v.t0, p.in) >= Math.min(PLAYED, v.t1 - v.t0) - 0.002;

/** First and last spoken verses in video order. */
export function verseRange(s: Spec, verses: Timeline["verses"]): VerseRange | null {
  const played = s.edit.flatMap((p) => verses.filter((v) => v.label !== "basmalah" && plays(v, p)));
  if (!played.length) return null;
  return { start: Number(played[0].label.split(":")[1]), end: Number(played.at(-1)!.label.split(":")[1]) };
}

/** Reject chapter crossings and removing the final remaining verse. */
export function nextVerseRange(range: VerseRange | null, action: VerseAction, count: number): VerseRange | null {
  if (!range || range.end < range.start) return null;
  const { start, end } = range;
  switch (action) {
    case "add-start": return start > 1 ? { start: start - 1, end } : null;
    case "add-end": return end < count ? { start, end: end + 1 } : null;
    case "remove-start": return start < end ? { start: start + 1, end } : null;
    case "remove-end": return start < end ? { start, end: end - 1 } : null;
  }
}

/** Apply a range as one undoable edit. Edge actions retain interior cuts;
 * explicitly choosing start/stop makes one continuous range. Keys keep their
 * video-time positions, matching the editor's existing trim contract.
 */
export function applyVersePlan(raw: RawSpec, spec: Spec, plan: VersePlan, action?: VerseAction, take = false): void {
  let pieces: Piece[] = spec.edit.map((p) => ({ ...p, in: r3(p.in + plan.shift), out: r3(p.out + plan.shift) }));
  if (!action) pieces = [{ in: plan.in, out: plan.out }];
  else if (action === "add-start") pieces[0].in = plan.in;
  else if (action === "add-end") pieces.at(-1)!.out = plan.out;
  else if (action === "remove-start") {
    while (pieces.length && pieces[0].out <= plan.in + 0.002) pieces.shift();
    if (pieces.length) pieces[0].in = Math.max(pieces[0].in, plan.in);
  } else {
    while (pieces.length && pieces.at(-1)!.in >= plan.out - 0.002) pieces.pop();
    if (pieces.length) pieces.at(-1)!.out = Math.min(pieces.at(-1)!.out, plan.out);
  }
  if (!pieces.length || pieces.some((p) => p.out - p.in <= 0.001)) throw new Error("Keep at least one verse in the edit.");
  raw.edit = pieces;
  raw.app = { ...spec.app, ...raw.app, ayah: plan.anchor, seconds: take ? plan.seconds : Math.max(spec.app.seconds, plan.seconds) };
}
