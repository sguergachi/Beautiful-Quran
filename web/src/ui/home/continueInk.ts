import { cubicBezierEase } from '../theme/Fade'

/** Page-turn stems — Android `PageTurnSounds`. Wipe is scrubbed to these. */
export const SWEEP_AT = 0.42
export const DROP_AT = 0.88

/** A full sheet turn. Android `STACK_PAGE_DURATION_MS`. */
export const SHEET_TURN_MS = 460

const CONTINUE_TURN_EPSILON = 0.001
const CONTINUE_INK_SPREAD_MS = 420
const CONTINUE_INK_MAX_FRAME_MS = 20
/** Leftover wipes cover the sweep-to-drop window of a 460ms turn. */
export const CONTINUE_INK_DRY_MS = 212

const FLOOD_EASE = [0.3, 0, 0.2, 1] as const
const WIPE_EASE = [0.4, 0, 0.2, 1] as const
const REWET_EASE = [0, 0, 0.2, 1] as const

export type ContinueTurn = 'resting' | 'turning' | 'landed'

export function continueInkToContinueChapter(openSurahId: number, continueSurahId: number): boolean {
  return continueSurahId !== 0 && openSurahId === continueSurahId
}

/** Continue was chosen: a tap, or a swipe into that chapter. */
export function continueInkShouldFlood(
  tapped: boolean,
  fromReader: boolean,
  toContinueChapter: boolean,
): boolean {
  return !fromReader && (tapped || toContinueChapter)
}

/** Returning from a Continue-owned reader: wipe with the page-turn drop. */
export function continueInkShouldWipe(tapped: boolean, fromReader: boolean): boolean {
  return fromReader && !tapped
}

/** Landed on a Continue-owned reader: keep the row wet underneath. */
export function continueInkShouldFill(
  tapped: boolean,
  spread: number,
  toContinueChapter: boolean,
): boolean {
  return tapped || spread > 0 || toContinueChapter
}

/**
 * How far the continue row has wiped for a return swipe of [turnProgress]
 * (0 at the reader, 1 at home). 0 through the lift, 1 the frame the drop begins.
 */
export function continueWipeProgress(turnProgress: number): number {
  return clamp((turnProgress - SWEEP_AT) / (DROP_AT - SWEEP_AT))
}

/** Remaining leftover wipe, as a share of the sweep-to-drop window. */
export function continueWipeMs(dry: number): number {
  return Math.max(0, Math.round(CONTINUE_INK_DRY_MS * (1 - dry)))
}

function clamp(x: number, lo = 0, hi = 1): number {
  return Math.min(hi, Math.max(lo, x))
}

function ease(t: number, curve: readonly [number, number, number, number]): number {
  return cubicBezierEase(clamp(t), curve[0], curve[1], curve[2], curve[3])
}

type DryAnim = {
  from: number
  to: number
  elapsed: number
  dur: number
  curve: readonly [number, number, number, number]
  onEnd?: () => void
}

/**
 * The continue row's wash, without React. Floods on its own 420ms clock,
 * stays wet under a Continue-owned reader, and wipes back scrubbed to the
 * page-turn drop. Opening some other chapter leaves the row dry.
 */
export class ContinueInkMachine {
  spread = 0
  dry = 0
  tapped = false

  private clock = 0
  private fromReader = false
  private turn: ContinueTurn = 'resting'
  private flooding = false
  private scrubbing = false
  private dryAnim: DryAnim | null = null

  /** True while a flood, wipe, or scrub still has frames to paint. */
  busy(): boolean {
    return this.flooding || this.scrubbing || this.dryAnim != null || this.tapped
  }

  step(dtMs: number, approach: number, openSurahId: number, continueSurahId: number): void {
    const next = this.classify(approach)
    if (next !== this.turn) this.enter(next, approach, openSurahId, continueSurahId)
    if (this.scrubbing) {
      this.dry = continueWipeProgress(1 - approach)
    } else {
      this.advanceDry(dtMs)
    }
    if (this.flooding) this.advanceFlood(dtMs)
  }

  private classify(approach: number): ContinueTurn {
    if (approach >= 1 - CONTINUE_TURN_EPSILON) return 'landed'
    if (approach > CONTINUE_TURN_EPSILON || this.tapped) return 'turning'
    return 'resting'
  }

  private enter(
    turn: ContinueTurn,
    approach: number,
    openSurahId: number,
    continueSurahId: number,
  ): void {
    this.turn = turn
    this.dryAnim = null
    this.scrubbing = false
    this.flooding = false
    const toContinue = continueInkToContinueChapter(openSurahId, continueSurahId)
    if (turn === 'turning') {
      if (continueInkShouldWipe(this.tapped, this.fromReader)) {
        this.scrubbing = true
        this.dry = continueWipeProgress(1 - approach)
      } else if (continueInkShouldFlood(this.tapped, this.fromReader, toContinue)) {
        this.fromReader = false
        this.flooding = true
        this.startDry(0, CONTINUE_INK_DRY_MS * this.dry, REWET_EASE)
      }
      return
    }
    if (turn === 'landed') {
      const owned = continueInkShouldFill(this.tapped, this.spread, toContinue)
      this.tapped = false
      if (owned) {
        this.fromReader = true
        this.clock = 1
        this.spread = 1
        this.dry = 0
      } else {
        this.fromReader = false
      }
      return
    }
    this.fromReader = false
    if (this.spread <= 0) return
    if (this.dry < 1) {
      const curve = this.dry > 0 ? REWET_EASE : WIPE_EASE
      this.startDry(1, continueWipeMs(this.dry), curve, () => {
        this.clock = 0
        this.spread = 0
        this.dry = 0
      })
    } else {
      this.clock = 0
      this.spread = 0
      this.dry = 0
    }
  }

  private startDry(
    to: number,
    dur: number,
    curve: readonly [number, number, number, number],
    onEnd?: () => void,
  ): void {
    if (dur <= 0) {
      this.dry = to
      onEnd?.()
      return
    }
    this.dryAnim = { from: this.dry, to, elapsed: 0, dur, curve, onEnd }
  }

  private advanceDry(dtMs: number): void {
    const anim = this.dryAnim
    if (!anim) return
    anim.elapsed += dtMs
    const u = clamp(anim.elapsed / anim.dur)
    this.dry = anim.from + (anim.to - anim.from) * ease(u, anim.curve)
    if (u >= 1) {
      this.dry = anim.to
      this.dryAnim = null
      anim.onEnd?.()
    }
  }

  private advanceFlood(dtMs: number): void {
    if (this.clock >= 1) {
      this.spread = 1
      this.flooding = false
      return
    }
    const ms = Math.min(Math.max(dtMs, 0), CONTINUE_INK_MAX_FRAME_MS)
    this.clock = Math.min(1, this.clock + ms / CONTINUE_INK_SPREAD_MS)
    this.spread = ease(this.clock, FLOOD_EASE)
    if (this.clock >= 1) this.flooding = false
  }
}
