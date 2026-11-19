/**
 * Chapter pull gestures — Android `ReaderScreen` chapterPullConnection and
 * `ChapterTransitionMotion.kt`. Pure state math, shared by touch and wheel.
 */

/** Bottom overscroll that fills the Continue pill (Android 104dp). */
export const NEXT_PULL_FILL_PX = 104
/** Top overscroll that fills the previous Open pill (Android 220dp: a small tug never fills). */
export const PREVIOUS_PULL_FILL_PX = 220
/** List rubber at a full next pull (Android pullRubberMaxPx 56dp). */
export const NEXT_RUBBER_MAX_PX = 56
/** Previous reveal band at full pull — fits the whole invitation (156dp). */
export const PREVIOUS_RUBBER_MAX_PX = 156
/** Where the released page travels while it fades (360dp). */
export const PREVIOUS_EXIT_PX = 360
/** The previous chapter's header settles down this far into place (48dp). */
export const PREVIOUS_ENTER_PX = 48
export const PREVIOUS_EXIT_MS = 300
export const PREVIOUS_ENTER_MS = 360

export type PullState = { next: number; previous: number; nextArmed: boolean; previousArmed: boolean }

export const EMPTY_PULL: PullState = { next: 0, previous: 0, nextArmed: false, previousArmed: false }

const clamp01 = (v: number) => Math.min(1, Math.max(0, v))

/** Held and released page share one resistance curve. */
export function previousChapterPullOffset(progress: number, distance = PREVIOUS_RUBBER_MAX_PX): number {
  const t = clamp01(progress)
  return distance * t * (2 - t)
}

/** Elastic stretch of the list at the bottom: sin-eased. */
export function nextChapterPullOffset(progress: number, distance = NEXT_RUBBER_MAX_PX): number {
  return distance * Math.sin(clamp01(progress) * Math.PI * 0.5)
}

/** Pill ink flips from accent to on-accent once the wash covers the label. */
export function pillInkMix(fill: number): number {
  return clamp01((clamp01(fill) - 0.32) / 0.28)
}

/** FastOutSlowIn approximation (cubic-bezier .4,0,.2,1) for the invite fade. */
function fastOutSlowIn(t: number): number {
  // Solve the bezier x(t) = t by Newton iteration, then return y.
  const x1 = 0.4, y1 = 0, x2 = 0.2, y2 = 1
  const bx = (u: number) => 3 * x1 * u * (1 - u) ** 2 + 3 * x2 * u * u * (1 - u) + u ** 3
  const by = (u: number) => 3 * y1 * u * (1 - u) ** 2 + 3 * y2 * u * u * (1 - u) + u ** 3
  let u = t
  for (let i = 0; i < 8; i++) {
    const d = (bx(u + 1e-4) - bx(u - 1e-4)) / 2e-4
    if (Math.abs(d) < 1e-6) break
    u = clamp01(u - (bx(u) - t) / d)
  }
  return by(u)
}

/** Android footerMorph = pull * 0.4 fades only the invitation chrome. */
export function nextInviteAlpha(nextPull: number): number {
  return clamp01(1 - fastOutSlowIn(clamp01(nextPull * 0.4)))
}

export type PullInput = {
  /** Scroll movement the finger/wheel asks for: > 0 toward the top (finger down). */
  dy: number
  hasNext: boolean
  hasPrevious: boolean
  beganAtTop: boolean
  beganAtBottom: boolean
  atTop: boolean
  atBottom: boolean
}

export type PullStep = { state: PullState; consumed: boolean; armed: boolean }

/**
 * One gesture delta. Opposite motion drains a pull before the page may
 * scroll, so even a full invitation can be cancelled; a pull only grows when
 * the gesture began docked at that edge.
 */
export function stepPull(state: PullState, input: PullInput): PullStep {
  const { dy } = input
  let { next, previous, nextArmed, previousArmed } = state
  if (next > 0 && dy > 0) {
    next = clamp01(next - dy / NEXT_PULL_FILL_PX)
    if (next < 1) nextArmed = false
    return { state: { next, previous, nextArmed, previousArmed }, consumed: true, armed: false }
  }
  if (previous > 0 && dy < 0) {
    previous = clamp01(previous + dy / PREVIOUS_PULL_FILL_PX)
    if (previous < 1) previousArmed = false
    return { state: { next, previous, nextArmed, previousArmed }, consumed: true, armed: false }
  }
  if (input.hasNext && previous <= 0 && input.beganAtBottom && input.atBottom && dy < 0) {
    next = clamp01(next - dy / NEXT_PULL_FILL_PX)
    const armed = next >= 1 && !nextArmed
    if (armed) nextArmed = true
    return { state: { next, previous, nextArmed, previousArmed }, consumed: true, armed }
  }
  if (input.hasPrevious && next <= 0 && input.beganAtTop && input.atTop && dy > 0) {
    previous = clamp01(previous + dy / PREVIOUS_PULL_FILL_PX)
    const armed = previous >= 1 && !previousArmed
    if (armed) previousArmed = true
    return { state: { next, previous, nextArmed, previousArmed }, consumed: true, armed }
  }
  return { state, consumed: false, armed: false }
}

/** What a release does: open while still completely full, else unfill. */
export function settlePull(state: PullState): 'next' | 'previous' | 'unfill' | 'none' {
  if (state.next >= 1) return 'next'
  if (state.previous >= 1) return 'previous'
  if (state.next > 0 || state.previous > 0) return 'unfill'
  return 'none'
}
