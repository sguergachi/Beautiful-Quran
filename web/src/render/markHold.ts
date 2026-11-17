/**
 * The reader's one mark hold — Android `MarkHoldHost`. Only one finger can
 * hold a ﴿N﴾ at a time, so one set of animations serves every verse; the
 * shamsa is painted by [MarkHoldOverlay] above the verse list.
 */
import { paperToggleHaptic } from '../ui/kit/paperHaptics'
import {
  HOLD_IDLE,
  MARK_HOLD_ACTION_DELAY_MS,
  MARK_HOLD_BURST_MS,
  MARK_HOLD_MS,
  MARK_HOLD_RETRACT_MS,
  MARK_TAP_DRAIN_MS,
  MARK_TAP_HINT_DELAY_MS,
  MARK_TAP_HINT_IN_MS,
  MARK_TAP_HINT_OUT_MS,
  MARK_TAP_SURGE_IN_MS,
  MARK_TAP_SURGE_OUT_MS,
  MARK_TAP_TEASE_RISE_MS,
  markTapTeasePeak,
  nextHoldStep,
  type HoldEvent,
  type HoldState,
  type Rect,
} from './markHoldShamsa'

export interface MarkHoldTarget {
  /** The painted cups' box, viewport pixels. */
  bounds: Rect
  baseline: number | null
  finger: { x: number; y: number }
  rtl: boolean
  onHold: () => void
}

type Ease = (t: number) => number
const linear: Ease = (t) => t

function bezier(x1: number, y1: number, x2: number, y2: number): Ease {
  const cx = 3 * x1
  const bx = 3 * (x2 - x1) - cx
  const ax = 1 - cx - bx
  const cy = 3 * y1
  const by = 3 * (y2 - y1) - cy
  const ay = 1 - cy - by
  const sx = (t: number) => ((ax * t + bx) * t + cx) * t
  const sy = (t: number) => ((ay * t + by) * t + cy) * t
  return (x) => {
    if (x <= 0) return 0
    if (x >= 1) return 1
    let lo = 0
    let hi = 1
    let t = x
    for (let i = 0; i < 24; i++) {
      const v = sx(t)
      if (Math.abs(v - x) < 1e-4) break
      if (v < x) lo = t
      else hi = t
      t = (lo + hi) / 2
    }
    return sy(t)
  }
}

const fastOutSlowIn = bezier(0.4, 0, 0.2, 1)
const fastOutLinearIn = bezier(0.4, 0, 1, 1)

type Channel = 'charge' | 'burst' | 'hint' | 'surge'

class MarkHoldHost {
  values: Record<Channel, number> = { charge: 0, burst: 0, hint: 0, surge: 0 }
  target: MarkHoldTarget | null = null
  private state: HoldState = HOLD_IDLE
  private generation = 0
  private burstGeneration = 0
  private frames = new Map<Channel, number>()
  private listeners = new Set<() => void>()
  private timers: number[] = []

  get visible() {
    return this.values.charge > 0 || this.values.burst > 0
  }

  subscribe(listener: () => void) {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  private emit() {
    for (const listener of this.listeners) listener()
  }

  private stop(channel: Channel) {
    const frame = this.frames.get(channel)
    if (frame != null) cancelAnimationFrame(frame)
    this.frames.delete(channel)
  }

  private snap(channel: Channel, value: number) {
    this.stop(channel)
    this.values[channel] = value
    this.emit()
  }

  private tween(channel: Channel, to: number, ms: number, ease: Ease): Promise<void> {
    this.stop(channel)
    const from = this.values[channel]
    return new Promise((resolve) => {
      if (ms <= 0 || from === to) {
        this.values[channel] = to
        this.emit()
        resolve()
        return
      }
      const start = performance.now()
      const step = (now: number) => {
        const t = Math.min(1, (now - start) / ms)
        this.values[channel] = from + (to - from) * ease(t)
        this.emit()
        if (t >= 1) {
          this.frames.delete(channel)
          resolve()
        } else this.frames.set(channel, requestAnimationFrame(step))
      }
      this.frames.set(channel, requestAnimationFrame(step))
    })
  }

  private delay(ms: number) {
    return new Promise<void>((resolve) => {
      this.timers.push(window.setTimeout(resolve, ms))
    })
  }

  private replace() {
    this.generation += 1
    for (const t of this.timers) window.clearTimeout(t)
    this.timers = []
    this.stop('charge')
    this.stop('hint')
    this.stop('surge')
    return this.generation
  }

  send(event: HoldEvent, target?: MarkHoldTarget) {
    const [next, action] = nextHoldStep(this.state, event)
    this.state = next
    if (action === 'startCharge' && target) void this.charge(target)
    else if (action === 'startLesson') void this.lesson()
    else if (action === 'retract') void this.retract()
  }

  private async charge(target: MarkHoldTarget) {
    this.target = target
    const gen = this.replace()
    this.snap('hint', 0)
    this.snap('surge', 0)
    await this.tween('charge', 1, MARK_HOLD_MS * (1 - this.values.charge), linear)
    if (gen !== this.generation) return
    // Still down when the ring closed: that is the hold.
    this.state = { phase: 'popped', ignoringPress: this.state.ignoringPress }
    paperToggleHaptic(true)
    const burstGen = ++this.burstGeneration
    window.setTimeout(() => target.onHold(), MARK_HOLD_ACTION_DELAY_MS)
    await this.tween('burst', 1, MARK_HOLD_BURST_MS, linear)
    if (burstGen !== this.burstGeneration) return
    this.snap('charge', 0)
    this.snap('burst', 0)
    this.state = { phase: 'idle', ignoringPress: this.state.ignoringPress }
  }

  private async lesson() {
    const gen = this.replace()
    const peak = markTapTeasePeak(this.values.charge)
    const parts: Promise<void>[] = []
    if (this.values.charge < peak) parts.push(this.tween('charge', peak, MARK_TAP_TEASE_RISE_MS, fastOutSlowIn))
    parts.push(
      this.tween('surge', 1, MARK_TAP_SURGE_IN_MS, fastOutSlowIn).then(() =>
        gen === this.generation ? this.tween('surge', 0, MARK_TAP_SURGE_OUT_MS, fastOutSlowIn) : undefined,
      ),
    )
    parts.push(
      this.delay(MARK_TAP_HINT_DELAY_MS)
        .then(() => (gen === this.generation ? this.tween('hint', 1, MARK_TAP_HINT_IN_MS, fastOutSlowIn) : undefined))
        .then(() => (gen === this.generation ? this.tween('hint', 0, MARK_TAP_HINT_OUT_MS, fastOutSlowIn) : undefined)),
    )
    await Promise.all(parts)
    if (gen !== this.generation) return
    await this.tween('charge', 0, MARK_TAP_DRAIN_MS, fastOutLinearIn)
    if (gen !== this.generation) return
    this.state = HOLD_IDLE
  }

  private async retract() {
    const gen = this.replace()
    this.snap('hint', 0)
    this.snap('surge', 0)
    await this.tween('charge', 0, MARK_HOLD_RETRACT_MS, fastOutSlowIn)
    if (gen === this.generation) this.state = HOLD_IDLE
  }
}


export const markHold = new MarkHoldHost()

/** Touch slop, px — Android's default ViewConfiguration at mdpi scale. */
export const MARK_HOLD_SLOP = 8
