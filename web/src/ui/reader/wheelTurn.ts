/** Sideways travel, in wheel pixels, that turns one leaf. */
export const WHEEL_TURN_TRAVEL = 60
/** A gesture is over once the wheel has been quiet this long. */
export const WHEEL_TURN_IDLE_MS = 160

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
export function createWheelTurn(travel = WHEEL_TURN_TRAVEL, idleMs = WHEEL_TURN_IDLE_MS) {
  let sum = 0
  let last = -Infinity
  let spent = false
  return (deltaX: number, deltaY: number, now: number): -1 | 0 | 1 => {
    if (now - last > idleMs) {
      sum = 0
      spent = false
    }
    last = now
    // A vertical scroll with a little drift is not a sweep.
    if (spent || Math.abs(deltaX) <= Math.abs(deltaY)) return 0
    sum += deltaX
    if (Math.abs(sum) < travel) return 0
    spent = true
    return sum < 0 ? 1 : -1
  }
}

/** Whether a wheel event is a sideways sweep the leaf should take from the browser. */
export function isSidewaysWheel(deltaX: number, deltaY: number): boolean {
  return Math.abs(deltaX) > Math.abs(deltaY) && deltaX !== 0
}
