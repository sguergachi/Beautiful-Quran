/**
 * Gather is entered by holding a verse's `﴿N﴾`, and the hold is drawn as an
 * illuminator's gilded shamsa round the mark — Android `MarkHoldShamsa.kt`.
 *
 * 1. Press: faint stubs of sixteen rays ring the mark under the finger.
 * 2. Hold: the ring grows out round the fingertip and the rays catch in
 *    reading order. [MARK_HOLD_MS] fills it.
 * 3. Pop: the full shamsa bursts as gather begins.
 * 4. Let go early: the tap lesson — power up, the unlit rays pulse to show
 *    the gap, power down. Slide into a scroll and the rays simply draw back.
 *
 * Geometry and the per-frame look are pure so tests can hold them.
 */
export const MARK_HOLD_MS = 550
export const MARK_HOLD_BURST_MS = 420
/** How far into the burst the hold's action runs (the reader re-renders). */
export const MARK_HOLD_ACTION_DELAY_MS = 120
export const MARK_HOLD_RETRACT_MS = 180
export const MARK_TAP_TEASE = 0.5
export const MARK_TAP_TEASE_RISE_MS = 260
export const MARK_TAP_SURGE_IN_MS = 200
export const MARK_TAP_SURGE_OUT_MS = 300
export const MARK_TAP_HINT_DELAY_MS = 220
export const MARK_TAP_HINT_IN_MS = 120
export const MARK_TAP_HINT_OUT_MS = 240
export const MARK_TAP_DRAIN_MS = 460
/** Sixteen: an illuminator's count, and never a six-fold star. */
export const SHAMSA_RAY_COUNT = 16

export const CUP_HEIGHT_PER_WIDTH = 0.71
export const CUP_CENTRE_ABOVE_BASELINE_PER_WIDTH = 0.105
export const EXPANDED_RADIUS = 34
export const EXPANDED_LONG_RAY = 10
export const EXPANDED_SHORT_RAY = 5.5
export const EXPANDED_HALF_WIDTH = 1.6
export const RAY_STUB = 0.22
export const CHARGE_LIFT = 1.5
export const BURST_LIFT = 6
export const SURGE_LIFT = 3

export interface MarkFrame { cx: number; cy: number; rx: number; ry: number }
export interface Rect { left: number; top: number; width: number; height: number }

export function markTapTeasePeak(charge: number): number {
  return Math.max(charge, MARK_TAP_TEASE)
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v))
const lerp = (a: number, b: number, t: number) => a + (b - a) * t

export function easeOutCubic(t: number): number {
  return 1 - Math.pow(1 - clamp01(t), 3)
}

export function staggered(progress: number, index: number, count: number, spread: number): number {
  if (count <= 1) return clamp01(progress)
  const start = (spread * index) / (count - 1)
  return clamp01((progress - start) / (1 - spread))
}

/** The ellipse hugging the cups; placed on the baseline when known. */
export function markFrame(bounds: Rect, baseline: number | null, minRy: number): MarkFrame {
  const width = bounds.width
  const rx = width / 2
  const cx = bounds.left + width / 2
  if (baseline != null && Number.isFinite(baseline)) {
    return {
      cx,
      cy: baseline - CUP_CENTRE_ABOVE_BASELINE_PER_WIDTH * width,
      rx,
      ry: Math.max((CUP_HEIGHT_PER_WIDTH * width) / 2, minRy),
    }
  }
  const ry = Math.max(Math.min(bounds.height / 2, rx * 0.78), minRy)
  return { cx, cy: bounds.top + bounds.height / 2, rx, ry }
}

export interface ShamsaRay {
  baseX: number
  baseY: number
  dirX: number
  dirY: number
  length: number
  halfWidth: number
}

/** Rays round the mark from the top, running the way the line reads. */
export function shamsaRays(
  frame: MarkFrame,
  gap: number,
  longRay: number,
  shortRay: number,
  halfWidth: number,
  rtl: boolean,
  count = SHAMSA_RAY_COUNT,
): ShamsaRay[] {
  const a = frame.rx + gap
  const b = frame.ry + gap
  const turn = rtl ? -1 : 1
  return Array.from({ length: count }, (_, i) => {
    const theta = -Math.PI / 2 + (turn * i * 2 * Math.PI) / count
    const c = Math.cos(theta)
    const s = Math.sin(theta)
    const nx = c * b
    const ny = s * a
    const nLen = Math.max(Math.hypot(nx, ny), 1e-4)
    const long = i % 2 === 0
    return {
      baseX: frame.cx + a * c,
      baseY: frame.cy + b * s,
      dirX: nx / nLen,
      dirY: ny / nLen,
      length: long ? longRay : shortRay,
      halfWidth: long ? halfWidth : halfWidth * 0.72,
    }
  })
}

/** 0 at the cups, 1 round the finger; reached by 45% of the hold. */
export function shamsaExpansion(charge: number): number {
  return easeOutCubic(clamp01(charge / 0.45))
}

export function expandedFrame(
  frame: MarkFrame,
  finger: { x: number; y: number } | null,
  expandedRadius: number,
  expansion: number,
): MarkFrame {
  const e = clamp01(expansion)
  const centre = finger ?? { x: frame.cx, y: frame.cy }
  return {
    cx: lerp(frame.cx, centre.x, e),
    cy: lerp(frame.cy, centre.y, e),
    rx: lerp(frame.rx, expandedRadius, e),
    ry: lerp(frame.ry, expandedRadius, e),
  }
}

export interface ShamsaRayLook { length: number; width: number; lift: number; alpha: number }

export function shamsaRayLook(
  index: number,
  count: number,
  charge: number,
  burst: number,
  hint = 0,
  surge = 0,
): ShamsaRayLook {
  const lit = easeOutCubic(staggered(charge, index, count, 0.65))
  const arrive = clamp01(charge / 0.06)
  let length = RAY_STUB + (1 - RAY_STUB) * lit
  let width = 0.7 + 0.3 * lit
  let lift = CHARGE_LIFT * clamp01(charge)
  let alpha = (0.22 + 0.66 * lit) * arrive
  if (hint > 0) {
    const unlit = (1 - lit) * clamp01(hint)
    alpha += 0.32 * unlit * arrive
    length += 0.2 * unlit
  }
  if (surge > 0) {
    const s = clamp01(surge)
    length *= 1 + 0.3 * s
    width *= 1 + 0.2 * s
    lift += SURGE_LIFT * s
    alpha = Math.min(1, alpha + 0.14 * s * arrive)
  }
  if (burst > 0) {
    const fly = easeOutCubic(burst)
    length *= 1 + 0.7 * fly
    width *= 1 - 0.45 * fly
    lift += BURST_LIFT * fly
    alpha *= Math.pow(1 - clamp01(burst), 1.6)
  }
  return { length, width, lift, alpha }
}

export type HoldPhase = 'idle' | 'charging' | 'popped' | 'lesson' | 'retracting'
export type HoldEvent = 'down' | 'released' | 'cancelled'
export type HoldAction = 'startCharge' | 'startLesson' | 'retract' | 'ignore'
export interface HoldState { phase: HoldPhase; ignoringPress: boolean }
export const HOLD_IDLE: HoldState = { phase: 'idle', ignoringPress: false }

/**
 * The hold's rules (Android `nextHoldStep`): a press charges from wherever
 * the ring stands; a lift while charging is the lesson; a slide retracts;
 * once popped nothing a finger does takes the pop back, and a press landing
 * mid-burst is ignored along with its own lift.
 */
export function nextHoldStep(state: HoldState, event: HoldEvent): [HoldState, HoldAction] {
  if (event === 'down') {
    if (state.phase === 'popped') return [{ ...state, ignoringPress: true }, 'ignore']
    return [{ phase: 'charging', ignoringPress: false }, 'startCharge']
  }
  if (state.ignoringPress) return [{ ...state, ignoringPress: false }, 'ignore']
  if (state.phase === 'charging') {
    return event === 'released'
      ? [{ phase: 'lesson', ignoringPress: false }, 'startLesson']
      : [{ phase: 'retracting', ignoringPress: false }, 'retract']
  }
  return [state, 'ignore']
}

/** Base and tip gold for the page: deep→gold on cream, gold→bright on dark. */
export function markHoldGold(
  gold: string,
  goldBright: string,
  goldDeep: string,
  darkPage: boolean,
): { base: string; tip: string } {
  return darkPage ? { base: gold, tip: goldBright } : { base: goldDeep, tip: gold }
}

/** The ray geometry for one frame, for the painter. */
export function shamsaFrameRays(
  bounds: Rect,
  baseline: number | null,
  finger: { x: number; y: number } | null,
  rtl: boolean,
  charge: number,
): { frame: MarkFrame; rays: ShamsaRay[]; expansion: number } {
  const expansion = shamsaExpansion(charge)
  const frame = expandedFrame(markFrame(bounds, baseline, 9), finger, EXPANDED_RADIUS, expansion)
  const rays = shamsaRays(
    frame,
    2.5,
    lerp(5, EXPANDED_LONG_RAY, expansion),
    lerp(2.8, EXPANDED_SHORT_RAY, expansion),
    lerp(1, EXPANDED_HALF_WIDTH, expansion),
    rtl,
  )
  return { frame, rays, expansion }
}
