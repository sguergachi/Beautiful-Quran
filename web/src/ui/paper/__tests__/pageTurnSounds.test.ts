import { describe, expect, it } from 'vitest'
import {
  BOOK_OPEN_SCHEDULE,
  COVER_OPEN_SCHEDULE,
  PAGE_TURN_SCHEDULE,
  nextFlipIndex,
} from '../pageTurnSounds'

describe('nextFlipIndex', () => {
  it('never repeats the last recording', () => {
    for (let last = 0; last < 3; last++) {
      for (const random of [0, 0.2, 0.34, 0.5, 0.67, 0.99, 1]) {
        const next = nextFlipIndex(last, random)
        expect(next).not.toBe(last)
        expect(next).toBeGreaterThanOrEqual(0)
        expect(next).toBeLessThan(3)
      }
    }
  })
})

describe('flip schedules', () => {
  it('run lift, sweep, drop in order inside their motion', () => {
    for (const [schedule, motionMs] of [
      [PAGE_TURN_SCHEDULE, 760],
      [COVER_OPEN_SCHEDULE, 1150],
      [BOOK_OPEN_SCHEDULE, 1700],
    ] as const) {
      expect(schedule.lift).toBeLessThan(schedule.sweep)
      expect(schedule.sweep).toBeLessThan(schedule.drop)
      expect(schedule.drop).toBeLessThan(motionMs)
    }
  })

  it('holds the desktop cover silent while the closed book slides', () => {
    // entrance-book-open spends its first 30% (510 ms of 1700) sliding.
    expect(BOOK_OPEN_SCHEDULE.lift).toBeGreaterThanOrEqual(480)
  })
})
