import { describe, expect, it } from 'vitest'
import {
  expandWindowToward,
  mountRangeForAyah,
  slidingAyahMountRange,
  WINDOW_AFTER,
  WINDOW_BEFORE,
} from '../useProgressiveAyahWindow'

describe('slidingAyahMountRange', () => {
  it('opens a tight window around the landing ayah', () => {
    expect(slidingAyahMountRange(286, 1)).toEqual({
      lo: 1,
      hi: 1 + WINDOW_AFTER,
      complete: false,
    })
    expect(slidingAyahMountRange(286, 100)).toEqual({
      lo: 100 - WINDOW_BEFORE,
      hi: 100 + WINDOW_AFTER,
      complete: false,
    })
  })

  it('marks short surahs complete immediately', () => {
    expect(slidingAyahMountRange(5, 1)).toEqual({ lo: 1, hi: 5, complete: true })
  })

  it('clamps the anchor into the surah', () => {
    expect(slidingAyahMountRange(10, 99)).toEqual({
      lo: Math.max(1, 10 - WINDOW_BEFORE),
      hi: 10,
      complete: Math.max(1, 10 - WINDOW_BEFORE) === 1,
    })
  })

  it('keeps long surahs incomplete even at mid-chapter', () => {
    const mid = slidingAyahMountRange(286, 150)
    expect(mid.complete).toBe(false)
    expect(mid.hi - mid.lo).toBeLessThan(286)
  })
})

describe('mountRangeForAyah', () => {
  it('re-centres a progressive window around a far selector target', () => {
    const current = slidingAyahMountRange(286, 1)
    expect(mountRangeForAyah(current, 286, 173)).toEqual(
      slidingAyahMountRange(286, 173),
    )
  })

  it('preserves a window when the requested ayah is already rendered', () => {
    const current = slidingAyahMountRange(286, 100)
    expect(mountRangeForAyah(current, 286, 104)).toBe(current)
  })

  it('clamps an out-of-range request before materializing it', () => {
    expect(
      mountRangeForAyah(slidingAyahMountRange(10, 1), 10, 99),
    ).toEqual(slidingAyahMountRange(10, 10))
  })
})

describe('expandWindowToward', () => {
  it('grows the high edge as the center advances', () => {
    const current = slidingAyahMountRange(286, 1)
    const next = expandWindowToward(current, 286, 40)
    expect(next.lo).toBe(1)
    expect(next.hi).toBeGreaterThan(current.hi)
    expect(next.hi).toBe(40 + WINDOW_AFTER)
  })

  it('never shrinks when the center moves back toward the start', () => {
    const grown = expandWindowToward(slidingAyahMountRange(286, 1), 286, 50)
    const back = expandWindowToward(grown, 286, 5)
    // Still holds the high water from scrolling down — no blank unmount.
    expect(back.lo).toBe(grown.lo)
    expect(back.hi).toBe(grown.hi)
  })

  it('is a no-op when the center is already comfortably covered', () => {
    // Window already wide enough that wantLo/wantHi sit inside lo/hi.
    const current = { lo: 50, hi: 150, complete: false as const }
    expect(expandWindowToward(current, 286, 100)).toBe(current)
  })
})
