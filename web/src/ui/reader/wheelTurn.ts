/** Sideways travel, in wheel pixels, that turns one leaf. */
export const WHEEL_TURN_TRAVEL = 60
/** A gesture is over once the wheel has been quiet this long. */
export const WHEEL_TURN_IDLE_MS = 160
/**
 * After a sweep has turned its leaf, nothing more turns for this long,
 * however the events are spaced. Starting a turn is the heaviest moment on
 * the page: when it held the thread up, the rest of the same sweep arrived
 * late enough to look like a new one, and turned a second leaf.
 */
export const WHEEL_TURN_HOLD_MS = 400

/**
 * Two fingers swept sideways on a trackpad turn one leaf. The browser
 * reports the sweep as a run of wheel events with a long momentum tail, so
 * one sweep is one turn: after it fires, nothing more does until the wheel
 * falls quiet.
 *
 * Returns the turn for each event: 1 on, -1 back, 0 none. Fingers moving
 * right scroll left (deltaX < 0) and turn on, as a finger dragged right
 * does on the leaf itself: the next page is to the left.
 */
export function createWheelTurn(
  travel = WHEEL_TURN_TRAVEL,
  idleMs = WHEEL_TURN_IDLE_MS,
  holdMs = WHEEL_TURN_HOLD_MS,
) {
  let sum = 0
  let last = -Infinity
  let fired = -Infinity
  let spent = false
  return (deltaX: number, deltaY: number, now: number): -1 | 0 | 1 => {
    if (now - last > idleMs && now - fired > holdMs) {
      sum = 0
      spent = false
    }
    last = now
    // A vertical scroll with a little drift is not a sweep.
    if (spent || Math.abs(deltaX) <= Math.abs(deltaY)) return 0
    sum += deltaX
    if (Math.abs(sum) < travel) return 0
    spent = true
    fired = now
    return sum < 0 ? 1 : -1
  }
}

/** Whether a wheel event is a sideways sweep the leaf should take from the browser. */
export function isSidewaysWheel(deltaX: number, deltaY: number): boolean {
  return Math.abs(deltaX) > Math.abs(deltaY) && deltaX !== 0
}
