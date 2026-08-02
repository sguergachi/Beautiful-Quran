/**
 * Shared Motion easing curves — match Android Compose / Material.
 *
 * Import these everywhere instead of hand-rolled cubic solvers or CSS-only
 * approximations so scroll, ink, and chrome share one curve vocabulary.
 */

/** Material `FastOutSlowInEasing` — cubic-bezier(0.4, 0, 0.2, 1). */
export const FAST_OUT_SLOW_IN = [0.4, 0, 0.2, 1] as const

/** Paper-stack / cover open — Android `CubicBezierEasing(0.24, 0.02, 0.12, 1)`. */
export const STACK_MOTION = [0.24, 0.02, 0.12, 1] as const

/** Bookmark unfurl — Android `UnfurlEasing`. */
export const UNFURL = [0.45, 0.02, 0.22, 1] as const

/** Bookmark retract — Android `RetractEasing`. */
export const RETRACT = [0.55, 0.05, 0.35, 1] as const

/** Ink sweep defaults from `InkEngine.Tuning` (overridable per wash). */
export const INK_SWEEP_DEFAULT = [0.3, 0.24, 0.7, 0.78] as const

/**
 * Android `InkExpandEasing` — exponential ease-out used by ink bloom and the
 * contextual-guide vellum enter. `1 - 2^(-9t)`.
 */
export function inkExpandProgress(t: number): number {
  if (t >= 1) return 1
  if (t <= 0) return 0
  return 1 - 2 ** (-9 * t)
}

export type CubicBezierEase = readonly [number, number, number, number]

export function cubicBezierTuple(
  x1: number,
  y1: number,
  x2: number,
  y2: number,
): CubicBezierEase {
  return [x1, y1, x2, y2]
}
