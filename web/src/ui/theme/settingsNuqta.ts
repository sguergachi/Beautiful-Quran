/**
 * The swell past full size at [approach]. Android `settingsNuqtaStretch`:
 * a rubber band — each step of the drag buys less than the one before.
 */
export function settingsNuqtaStretch(approach: number): number {
  const over = Math.min(1, Math.max(0, approach))
  const limit = 0.8
  const stiffness = 1
  return limit * (1 - 1 / (1 + stiffness * over))
}

export const SETTINGS_NUQTA_SCALE = 1.6
export const SETTINGS_NUQTA_DRY_MS = 500
export const SETTINGS_NUQTA_REWET_MS = 180
export const SETTINGS_NUQTA_DRIED_TINT = 0.55
export const SETTINGS_NUQTA_STAGE_SWELL = 0.14
export const SETTINGS_TURN_EPSILON = 0.001
/** Android `STACK_PAGE_TURN_THRESHOLD` — letting go past this completes the turn. */
export const SETTINGS_COMMIT_AT = 0.15

const STIFFNESS_MEDIUM = 1500
const STIFFNESS_MEDIUM_LOW = 400

/** Semi-implicit spring, unit mass. [dt] is seconds. */
export function springStep(
  value: number,
  velocity: number,
  target: number,
  dt: number,
  stiffness: number,
  dampingRatio: number,
): { value: number; velocity: number } {
  const step = Math.min(Math.max(dt, 0), 0.032)
  const omega = Math.sqrt(stiffness)
  const damping = 2 * dampingRatio * omega
  const acceleration = -stiffness * (value - target) - damping * velocity
  const nextVelocity = velocity + acceleration * step
  return { value: value + nextVelocity * step, velocity: nextVelocity }
}

export type SettingsNuqtaPose = {
  spread: number
  /** 1 wet, 0 dried out. Android `presence`. */
  presence: number
  stage: number
  pull: number
}

/**
 * One settings glyph's nuqta. Spread follows the select clock; the rubber
 * band follows the sheet; a press or a short turn dries the coats in place.
 */
export class SettingsNuqtaMachine {
  spread = 0
  presence = 0
  stage = 0
  pull = 0
  pressed = false
  held = false

  private stageV = 0
  private pullV = 0
  private spreading = false
  private spreadElapsed = 0
  private spreadDur = 0
  private presenceElapsed = 0
  private presenceDur = 0
  private presenceFrom = 0
  private presenceTo = 0
  private presenceRunning = false

  pose(): SettingsNuqtaPose {
    return {
      spread: this.spread,
      presence: this.presence,
      stage: this.stage,
      pull: this.pull,
    }
  }

  /**
   * True only while something is still moving. A drop that has settled on
   * Settings stays painted and does not keep a frame loop alive.
   */
  busy(): boolean {
    const drying = this.presence > 0.001 && this.presence < 0.999
    const spreading = this.spread > 0.001 && this.spread < 0.999 && this.presence > 0
    return (
      this.pressed ||
      this.held ||
      this.spreading ||
      this.presenceRunning ||
      drying ||
      spreading ||
      Math.abs(this.stageV) > 0.02 ||
      Math.abs(this.pullV) > 0.02
    )
  }

  /**
   * [spreadMs] is the shipped nuqta's touch-to-settle clock.
   * [approach] is the stack's turn into Settings, 0..1.
   */
  step(dtMs: number, approach: number, spreadMs: number): void {
    const dt = dtMs / 1000
    const inked = approach > SETTINGS_TURN_EPSILON || this.pressed || this.held
    if (inked && !this.spreading && this.spread < 1) {
      // A fresh drop starts empty. Turned again while it dries, the colour
      // floods back into the drop as it stands.
      if (this.presence <= 0) this.spread = 0
      this.spreading = true
      this.spreadElapsed = 0
      this.spreadDur = Math.max(1, spreadMs * (1 - this.spread))
      this.startPresence(1, SETTINGS_NUQTA_REWET_MS)
    } else if (!inked && (this.spreading || this.presence > 0)) {
      this.spreading = false
      if (this.presenceTo !== 0 || !this.presenceRunning) {
        this.startPresence(0, SETTINGS_NUQTA_DRY_MS)
      }
    }
    if (approach >= 1 - SETTINGS_TURN_EPSILON) this.pressed = false

    if (this.spreading) {
      this.spreadElapsed += dtMs
      const u = Math.min(1, this.spreadElapsed / this.spreadDur)
      const from = 1 - this.spreadDur / spreadMs
      this.spread = from + (1 - from) * u
      if (u >= 1) {
        this.spread = 1
        this.spreading = false
      }
    }

    this.advancePresence(dtMs)

    const committed = this.held || this.pressed || approach >= SETTINGS_COMMIT_AT
    const stageTarget = committed ? 1 : 0
    const stage = springStep(this.stage, this.stageV, stageTarget, dt, STIFFNESS_MEDIUM, 0.38)
    if (Math.abs(stage.value - stageTarget) < 0.001 && Math.abs(stage.velocity) < 0.02) {
      this.stage = stageTarget
      this.stageV = 0
    } else {
      this.stage = stage.value
      this.stageV = stage.velocity
    }

    const pullTarget = this.held ? settingsNuqtaStretch(1) : 0
    const pull = springStep(this.pull, this.pullV, pullTarget, dt, STIFFNESS_MEDIUM_LOW, 0.5)
    if (Math.abs(pull.value - pullTarget) < 0.001 && Math.abs(pull.velocity) < 0.02) {
      this.pull = pullTarget
      this.pullV = 0
    } else {
      this.pull = pull.value
      this.pullV = pull.velocity
    }
  }

  private startPresence(to: number, dur: number): void {
    this.presenceFrom = this.presence
    this.presenceTo = to
    this.presenceElapsed = 0
    this.presenceDur = dur
    this.presenceRunning = true
  }

  private advancePresence(dtMs: number): void {
    if (!this.presenceRunning) return
    this.presenceElapsed += dtMs
    const u = Math.min(1, this.presenceElapsed / Math.max(1, this.presenceDur))
    this.presence = this.presenceFrom + (this.presenceTo - this.presenceFrom) * u
    if (u >= 1) {
      this.presence = this.presenceTo
      this.presenceRunning = false
      if (this.presence <= 0) this.spread = 0
    }
  }
}
