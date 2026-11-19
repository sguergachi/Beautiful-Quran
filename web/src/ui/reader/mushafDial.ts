/**
 * The page dial's arithmetic — Android `MushafPageDial.kt`. The hairline
 * under the leaf carries the reader's place among the book's leaves and,
 * under a hand, scrubs: chapters by default (a comb nailed to the rule), and
 * the leaves of one chapter once the hand holds still (the trough).
 *
 * Everything here is pure, in CSS pixels, with the rule [width] wide and
 * [inset] held back at each end (the thumb's travel stops short of the
 * paper's edge). A mushaf's first leaf is at the right of the rule; an
 * English book's at the left ([rightToLeft]).
 */

/** The rule's own band, the line's place in it, and grab paper beneath. */
export const DIAL_SLOT = 13
export const DIAL_RULE_Y = 11.5
export const DIAL_BELOW_GRAB = 8
export const DIAL_THUMB_H = 3
export const DIAL_THUMB_HELD_H = 4.5
export const DIAL_THUMB_ASPECT = 3.2
export const DIAL_EDGE_INSET = 14
export const DIAL_CHAPTER_TICK = 5
export const DIAL_PAGE_TICK = 7
/** Bare rule past the trough's last leaf at each end: the way out along it. */
export const DIAL_RUN_OUT = 26
/** Off the line the finger pressed on, the way out across it. */
export const DIAL_STRAY = 42
/** The trough opens after this long held still … */
export const DIAL_HOLD_S = 1.62
/** … where "still" is under this speed (px/s). */
export const DIAL_HOLD_PX_S = 16
/** Seconds a finger may stand in the run-out before the trough shuts. */
export const DIAL_RUNOUT_S = 0.18
const SPEED_RISE_TAU_S = 0.05
const SPEED_FALL_TAU_S = 0.06
/** The return roundel's touch target, dot and life. */
export const DIAL_RETURN_HIT = 44
export const DIAL_RETURN_DOT = 26
export const MUSHAF_DIAL_RETURN_MS = 5_000

/** Where a page sits along the book, 0 at leaf 1 and 1 at the last. */
export function dialFraction(page: number, pageCount: number): number {
  if (pageCount <= 1) return 0
  return clamp((page - 1) / (pageCount - 1), 0, 1)
}

/** A book fraction as a place on the rule, given which way the book turns. */
export function dialAlong(fraction: number, rightToLeft: boolean): number {
  return rightToLeft ? 1 - fraction : fraction
}

export function dialTrackX(fraction: number, width: number, inset: number): number {
  const edge = clamp(inset, 0, width / 2)
  return edge + (width - 2 * edge) * clamp(fraction, 0, 1)
}

export function dialTrackFraction(x: number, width: number, inset: number): number {
  const edge = clamp(inset, 0, width / 2)
  const track = width - 2 * edge
  if (track <= 0) return 0
  return clamp((x - edge) / track, 0, 1)
}

export function dialClampToTrack(x: number, width: number, inset: number): number {
  const edge = clamp(inset, 0, width / 2)
  return clamp(x, edge, Math.max(edge, width - edge))
}

/** The run of leaves of the chapter holding leaf [at]; [marks] open each chapter. */
export function dialChapterRun(marks: readonly number[], at: number, pageCount: number): { first: number; last: number } {
  const count = Math.max(1, pageCount)
  if (marks.length === 0) return { first: 1, last: count }
  let start = marks[0]!
  let end = count
  for (let index = 0; index < marks.length; index++) {
    const mark = marks[index]!
    if (mark > at) {
      end = mark - 1
      break
    }
    start = mark
    if (index === marks.length - 1) end = count
  }
  return { first: start, last: Math.max(start, end) }
}

/** The last chapter whose opening is at or before [at]. */
export function dialChapterIndex(marks: readonly number[], at: number): number {
  let lo = 0
  let hi = marks.length - 1
  let found = 0
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (marks[mid]! <= at) {
      found = mid
      lo = mid + 1
    } else hi = mid - 1
  }
  return found
}

/** The 25th chapter on gets equal cells, so every short surah stays selectable. */
const TAIL_START_IDX = 24
const TAIL_HEAD_FRACTION = 0.3

/** Where a page sits on the chapter tier, 0 at leaf 1 and 1 at the far end. */
export function dialChapterFraction(page: number, marks: readonly number[], pageCount: number): number {
  if (marks.length <= TAIL_START_IDX) return dialFraction(page, pageCount)
  const at = clamp(Math.trunc(page), 1, pageCount)
  const idx = dialChapterIndex(marks, at)
  if (idx < TAIL_START_IDX) {
    const tail = dialFraction(marks[TAIL_START_IDX]!, pageCount)
    return (dialFraction(page, pageCount) / tail) * TAIL_HEAD_FRACTION
  }
  const tailCount = Math.max(1, marks.length - TAIL_START_IDX)
  const run = dialChapterRun(marks, at, pageCount)
  const span = Math.max(1, run.last - run.first + 1)
  const within = (page - run.first) / span
  return TAIL_HEAD_FRACTION + ((idx - TAIL_START_IDX + within) / tailCount) * (1 - TAIL_HEAD_FRACTION)
}

/**
 * Each chapter's stable seat on the rule: co-located openings spread apart,
 * relaxed to a minimum gap and kept inside the track. Selection reads these,
 * so every chapter owns a slice of the rule that never moves.
 */
export function dialCombSeats(
  marks: readonly number[],
  pageCount: number,
  inset: number,
  width: number,
  rightToLeft: boolean,
  rule = 1,
): number[] {
  const seats = marks.map((mark, idx) => {
    let x = dialTrackX(dialAlong(dialChapterFraction(mark, marks, pageCount), rightToLeft), width, inset)
    let gStart = idx
    while (gStart > 0 && marks[gStart - 1] === mark) gStart--
    let gEnd = idx
    while (gEnd + 1 < marks.length && marks[gEnd + 1] === mark) gEnd++
    const forward = rightToLeft ? -1 : 1
    if (gEnd > gStart) x -= forward * ((gEnd - gStart) / 2 - (idx - gStart)) * rule * 3
    return clamp(x, inset, width - inset)
  })
  const span = Math.max(0, width - 2 * inset)
  const minGap = Math.min(rule * 1.5, span / Math.max(1, marks.length - 1))
  if (rightToLeft) {
    let prev = width - inset + minGap
    for (let i = 0; i < seats.length; i++) {
      seats[i] = Math.min(seats[i]!, prev - minGap)
      prev = seats[i]!
    }
    let floor = inset
    for (let i = seats.length - 1; i >= 0; i--) {
      seats[i] = Math.max(seats[i]!, floor)
      floor = seats[i]! + minGap
    }
  } else {
    let prev = inset - minGap
    for (let i = 0; i < seats.length; i++) {
      seats[i] = Math.max(seats[i]!, prev + minGap)
      prev = seats[i]!
    }
    let ceiling = width - inset
    for (let i = seats.length - 1; i >= 0; i--) {
      seats[i] = Math.min(seats[i]!, ceiling)
      ceiling = seats[i]! - minGap
    }
  }
  return seats
}

/** Which chapter's cell [x] falls in. */
export function dialChapterAt(seats: readonly number[], x: number): number {
  let best = 0
  let bestDist = Infinity
  seats.forEach((seat, idx) => {
    const d = Math.abs(seat - x)
    if (d < bestDist) {
      bestDist = d
      best = idx
    }
  })
  return best
}

/** Keeps the last chapter until the hand has moved decisively past the midpoint. */
export function dialChapterAtHysteresis(seats: readonly number[], x: number, lastIdx: number, hysteresis = 4): number {
  const cur = dialChapterAt(seats, x)
  if (cur === lastIdx || lastIdx < 0 || lastIdx >= seats.length) return cur
  if (Math.abs(cur - lastIdx) !== 1) return cur
  const gap = Math.abs(seats[lastIdx]! - seats[cur]!)
  const adaptive = Math.max(gap * 0.6, hysteresis * 0.85)
  const mid = (seats[lastIdx]! + seats[cur]!) / 2
  const h = Math.max(0, Math.min(adaptive / 2, gap / 2 - 1.2))
  const towardsHigher = seats[seats.length - 1]! < seats[0]! ? -1 : 1
  const past = (x - mid) * towardsHigher
  return cur > lastIdx ? (past > h ? cur : lastIdx) : (past < -h ? cur : lastIdx)
}

/** The leaf a finger at [fraction] of the trough's measure names, inside [run]. */
export function dialTroughPage(fraction: number, run: { first: number; last: number }, rightToLeft: boolean): number {
  const t = dialAlong(clamp(fraction, 0, 1), rightToLeft)
  return Math.round(run.first + t * (run.last - run.first))
}

/** Whether [x] stands in the run-out beyond the trough's measure. */
export function dialPastTrough(x: number, width: number, troughInset: number): boolean {
  const edge = clamp(troughInset, 0, width / 2)
  return x < edge || x > width - edge
}

/** A frame-rate independent speed estimate, px/s. */
export function dialSpeed(previous: number, instant: number, dtSeconds: number): number {
  if (dtSeconds <= 0) return previous
  const speed = Math.abs(instant)
  const tau = speed > previous ? SPEED_RISE_TAU_S : SPEED_FALL_TAU_S
  return previous + (speed - previous) * (1 - Math.exp(-dtSeconds / tau))
}

export function dialShouldOpen(speed: number, heldSeconds: number): boolean {
  return Math.abs(speed) < DIAL_HOLD_PX_S && heldSeconds >= DIAL_HOLD_S
}

/** Left edge of the return roundel's target, centred on its leaf's seat. */
export function dialReturnLeft(
  page: number,
  pageCount: number,
  width: number,
  inset: number,
  bubble: number,
  rightToLeft: boolean,
): number {
  const seat = dialTrackX(dialAlong(dialFraction(page, pageCount), rightToLeft), width, inset)
  return clamp(seat - bubble / 2, 0, Math.max(0, width - bubble))
}

export interface DialLabel {
  number: number
  chapter: string
  fromAyah: number
  toAyah: number
}

/** Chapter tier: "N  ·  Name"; trough: "Name  ·  p. X". */
export function dialLabelHead(label: DialLabel, zoomed: boolean, page: number): string {
  return zoomed ? `${label.chapter}  ·  p. ${page}` : `${label.number}  ·  ${label.chapter}`
}

/** Chapter tier: the leaf it opens on; trough: the verses on the leaf. */
export function dialLabelFoot(label: DialLabel, zoomed: boolean, startPage = 0): string {
  if (!zoomed) return startPage > 0 ? `p. ${startPage}` : ''
  const verses = label.toAyah <= label.fromAyah ? `${label.fromAyah}` : `${label.fromAyah}–${label.toAyah}`
  return `Ayah ${verses}`
}

/** Which way the reader travels to reach [target] from [current] — Android `mushafReturnWay`. */
export function mushafReturnWay(current: number, target: number, rightToLeft: boolean): 'left' | 'right' | null {
  if (target === current) return null
  const later = rightToLeft ? 'left' : 'right'
  const earlier = rightToLeft ? 'right' : 'left'
  return target > current ? later : earlier
}

function clamp(value: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, value))
}
