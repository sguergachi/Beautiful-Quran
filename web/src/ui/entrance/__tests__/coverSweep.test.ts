import { describe, expect, it } from 'vitest'
import {
  COVER_SWEEP_FROM,
  COVER_SWEEP_MS,
  COVER_SWEEP_TO,
  coverSweep,
  fastOutSlowIn,
  glintPoint,
  loadSweepLeft,
} from '../coverSweep'

describe('coverSweep', () => {
  it('runs 0.18 → 0.82 over 6.5 s and back (RepeatMode.Reverse)', () => {
    expect(coverSweep(0)).toBeCloseTo(COVER_SWEEP_FROM, 5)
    expect(coverSweep(COVER_SWEEP_MS - 0.001)).toBeCloseTo(COVER_SWEEP_TO, 3)
    expect(coverSweep(COVER_SWEEP_MS * 2 - 0.001)).toBeCloseTo(COVER_SWEEP_FROM, 3)
    expect(coverSweep(COVER_SWEEP_MS * 1.5)).toBeCloseTo(coverSweep(COVER_SWEEP_MS * 0.5), 5)
  })

  it('stays within its range and eases (FastOutSlowIn)', () => {
    for (let ms = 0; ms < COVER_SWEEP_MS * 3; ms += 97) {
      const v = coverSweep(ms)
      expect(v).toBeGreaterThanOrEqual(COVER_SWEEP_FROM - 1e-6)
      expect(v).toBeLessThanOrEqual(COVER_SWEEP_TO + 1e-6)
    }
    // Fast out: past halfway in value before halfway in time.
    expect(fastOutSlowIn(0.5)).toBeGreaterThan(0.5)
    expect(fastOutSlowIn(0)).toBe(0)
    expect(fastOutSlowIn(1)).toBe(1)
  })

  it('moves the light diagonally across the board', () => {
    expect(glintPoint(0.5, 400, 800)).toEqual({ x: 200, y: 400 })
  })

  it('keeps the load segment on its track', () => {
    expect(loadSweepLeft(0, 156)).toBe(0)
    expect(loadSweepLeft(1, 156)).toBeCloseTo(156 * 0.72, 5)
    expect(loadSweepLeft(2, 156)).toBeCloseTo(156 * 0.72, 5)
  })
})
