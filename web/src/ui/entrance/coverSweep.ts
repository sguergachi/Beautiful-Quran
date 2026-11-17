/**
 * The cover's idle light — Android `EntranceCover` `loadSweep`: without a
 * tilt sensor the gilding's sheen sweeps 0.18 → 0.82 over 6.5 s and back
 * (Compose `tween(6_500)`, FastOutSlowIn, `RepeatMode.Reverse`). The
 * indeterminate load bar rides the same value.
 */
export const COVER_SWEEP_MS = 6_500
export const COVER_SWEEP_FROM = 0.18
export const COVER_SWEEP_TO = 0.82
/** Indeterminate load bar: a segment this share of the track wide. */
export const LOAD_SWEEP_SEGMENT = 0.28

/** CSS/Compose cubic-bezier(x1, y1, x2, y2) at progress [t] (0..1). */
export function cubicBezier(x1: number, y1: number, x2: number, y2: number, t: number): number {
  if (t <= 0) return 0
  if (t >= 1) return 1
  const bez = (a: number, b: number, s: number) =>
    3 * a * (1 - s) * (1 - s) * s + 3 * b * (1 - s) * s * s + s * s * s
  // Bisection on x: monotone for 0 ≤ x1, x2 ≤ 1.
  let lo = 0
  let hi = 1
  let s = t
  for (let i = 0; i < 30; i++) {
    s = (lo + hi) / 2
    if (bez(x1, x2, s) < t) lo = s
    else hi = s
  }
  return bez(y1, y2, s)
}

/** FastOutSlowInEasing — Compose's tween default. */
export function fastOutSlowIn(t: number): number {
  return cubicBezier(0.4, 0, 0.2, 1, t)
}

/** The sweep's value [elapsedMs] after it started. */
export function coverSweep(elapsedMs: number): number {
  const cycle = Math.max(0, elapsedMs) / COVER_SWEEP_MS
  const leg = Math.floor(cycle)
  const within = cycle - leg
  const t = leg % 2 === 0 ? within : 1 - within
  return COVER_SWEEP_FROM + (COVER_SWEEP_TO - COVER_SWEEP_FROM) * fastOutSlowIn(t)
}

/** Where the light stands on a [width] × [height] board for a sweep value. */
export function glintPoint(sweep: number, width: number, height: number): { x: number; y: number } {
  return { x: width * sweep, y: height * sweep }
}

/** Left edge of the indeterminate bar's segment on a track [trackPx] wide. */
export function loadSweepLeft(sweep: number, trackPx: number): number {
  const segment = trackPx * LOAD_SWEEP_SEGMENT
  return Math.max(0, trackPx - segment) * Math.min(1, Math.max(0, sweep))
}
