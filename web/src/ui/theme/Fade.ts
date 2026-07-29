/**
 * Ink wash math — port of Android `ui/theme/Fade.kt` helpers.
 */

/** Sample count for directional wash gradients (Android `InkProfileStops`). */
export const INK_PROFILE_STOPS = 9
/** Feather relative to word width (~1–2 letters; see INK_WASH_FEEL R1). */
export const INK_WASH_FEATHER = 0.5

/** smootherstep — non-wash easing (glint, whole-word breath). */
export function inkSmootherstep(t: number): number {
  const c = t < 0 ? 0 : t > 1 ? 1 : t
  return c * c * c * (c * (c * 6 - 15) + 10)
}

/**
 * Ink wash edge profile (R2): steep toe, long shoulder.
 * Front-loads density via smootherstep(√t) so the edge reads as arrival + soak,
 * not a symmetric fade. Soft ends (zero slope at 0 and 1).
 */
export function inkWashProfile(t: number): number {
  const c = t < 0 ? 0 : t > 1 ? 1 : t
  return inkSmootherstep(Math.sqrt(c))
}

/**
 * Alpha at normalized position [pos] ∈ [0,1] across a word for wash progress [progress].
 * RTL: wash travels right→left (high pos first).
 */
export function inkWashAlpha(
  pos: number,
  progress: number,
  restingAlpha: number,
  rtl: boolean,
  feather = INK_WASH_FEATHER,
): number {
  const p = Math.min(1, Math.max(0, progress))
  if (p >= 1) return 1
  const edge = feather
  // Head travels one edge past the end so the final letter finishes at p=1.
  const travel = 1 + edge
  const head = p * travel
  const local = rtl ? (1 - pos) : pos
  // Distance behind the wash head, normalized by feather width.
  const behind = (head - local) / edge
  const s = inkWashProfile(behind)
  return restingAlpha + (1 - restingAlpha) * s
}

/** Whole-word breath alpha (marketing / fallback when directional mask unavailable). */
export function wholeWordInkAlpha(progress: number, restingAlpha: number): number {
  const p = Math.min(1, Math.max(0, progress))
  return restingAlpha + (1 - restingAlpha) * inkSmootherstep(p)
}

/**
 * CSS mask-image for the directional ink wash.
 * Samples [inkWashAlpha] across the word (layout L→R) so the reveal matches
 * Android's asymmetric wash bloom instead of a blunt 3-stop wipe.
 */
export function washMaskImage(
  progress: number,
  restingAlpha: number,
  rtl: boolean,
  feather = INK_WASH_FEATHER,
  stopCount = INK_PROFILE_STOPS,
): string {
  const p = Math.min(1, Math.max(0, progress))
  if (p >= 1) return 'none'
  const n = Math.max(2, stopCount)
  const stops: string[] = []
  for (let i = 0; i < n; i++) {
    const pos = i / (n - 1)
    const a = inkWashAlpha(pos, p, restingAlpha, rtl, feather)
    stops.push(`rgba(0,0,0,${a.toFixed(4)}) ${(pos * 100).toFixed(2)}%`)
  }
  return `linear-gradient(to right, ${stops.join(', ')})`
}

/**
 * Paper-cover mask for Arabic-only shaped bloom (Android `shapedWordBloom`
 * InkReveal). Glyphs stay full ink; this masks a paper overlay whose alpha is
 * `1 − glyphAlpha`, so progress 0 matches UpcomingDim cover strength.
 */
export function paperCoverMaskImage(
  progress: number,
  restingAlpha: number,
  rtl: boolean,
  feather = INK_WASH_FEATHER,
  stopCount = INK_PROFILE_STOPS,
): string {
  const p = Math.min(1, Math.max(0, progress))
  if (p >= 1) return 'none'
  const n = Math.max(2, stopCount)
  const stops: string[] = []
  for (let i = 0; i < n; i++) {
    const pos = i / (n - 1)
    const glyphA = inkWashAlpha(pos, p, restingAlpha, rtl, feather)
    const paperA = Math.min(1, Math.max(0, 1 - glyphA))
    stops.push(`rgba(0,0,0,${paperA.toFixed(4)}) ${(pos * 100).toFixed(2)}%`)
  }
  return `linear-gradient(to right, ${stops.join(', ')})`
}

/** Cubic-bezier sample for sweep easing (matches InkEngine tuning defaults). */
export function cubicBezierEase(
  t: number,
  x1: number,
  y1: number,
  x2: number,
  y2: number,
): number {
  // Simplified: treat as CSS cubic-bezier on the unit interval via Newton.
  const cx = 3 * x1
  const bx = 3 * (x2 - x1) - cx
  const ax = 1 - cx - bx
  const cy = 3 * y1
  const by = 3 * (y2 - y1) - cy
  const ay = 1 - cy - by

  function sampleX(u: number) {
    return ((ax * u + bx) * u + cx) * u
  }
  function sampleY(u: number) {
    return ((ay * u + by) * u + cy) * u
  }
  function sampleDX(u: number) {
    return (3 * ax * u + 2 * bx) * u + cx
  }

  let u = t
  for (let i = 0; i < 6; i++) {
    const x = sampleX(u) - t
    const d = sampleDX(u)
    if (Math.abs(x) < 1e-5 || Math.abs(d) < 1e-6) break
    u -= x / d
  }
  u = Math.min(1, Math.max(0, u))
  return sampleY(u)
}
