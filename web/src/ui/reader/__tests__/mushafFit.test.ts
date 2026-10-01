import { describe, expect, it } from 'vitest'
import { mushafFit, mushafLeafFit, naturalLineWidth, solveLine } from '../mushafFit'

describe('incoming page fit', () => {
  it('waits for both incoming leaves, including the hidden recto', () => {
    expect(mushafFit(3, true, { 1: 1, 2: 1, 4: 0.9 })).toBeUndefined()
    expect(mushafFit(3, true, { 1: 1, 2: 1, 3: 0.7, 4: 0.9 })).toBe(0.7)
  })

  it('uses the incoming page on a phone and the tighter incoming verso on a spread', () => {
    expect(mushafFit(3, false, { 3: 0.8 })).toBe(0.8)
    expect(mushafFit(3, true, { 3: 0.8, 4: 0.6 })).toBe(0.6)
  })
})

describe('measure', () => {
  it('adds minimum word spaces to the items of a line', () => {
    expect(naturalLineWidth([], 8)).toBe(0)
    expect(naturalLineWidth([100], 8)).toBe(100)
    expect(naturalLineWidth([100, 50, 20], 8)).toBe(186)
  })

  it('shrinks the type when the widest line overflows the page', () => {
    const fit = mushafLeafFit(400, 500)
    expect(fit).toBeLessThan(0.8)
    expect(500 * fit).toBeLessThanOrEqual(400)
  })

  it('holds the type when there is no room to grow', () => {
    expect(mushafLeafFit(600, 500)).toBe(1)
    expect(mushafLeafFit(600, 600)).toBe(1)
  })

  it('grows the type to fill the measure, up to the leading floor', () => {
    const fit = mushafLeafFit(600, 500, 2)
    expect(fit).toBeGreaterThan(1.19)
    expect(500 * fit).toBeLessThanOrEqual(600)
    // The line pitch only allows 5% more: stop there, block drawn in instead.
    expect(mushafLeafFit(600, 500, 1.05)).toBe(1.05)
    // A pitch too tight for even the base size never shrinks a line that fits.
    expect(mushafLeafFit(600, 500, 0.8)).toBe(1)
  })

  it('is neutral with nothing to measure', () => {
    expect(mushafLeafFit(0, 0)).toBe(1)
    expect(mushafLeafFit(500, 0)).toBe(1)
  })
})

describe('solveLine', () => {
  const em = 20
  it('leaves a full line alone', () => {
    // 10 gaps at the 0.42 em target need 84px; ink 516 fills the 600 measure.
    expect(solveLine(516, 10, 600, em, 0.06)).toEqual({ widen: 1, short: false })
    expect(solveLine(580, 10, 600, em, 0.06)).toEqual({ widen: 1, short: false })
  })

  it('widens a loose line only as far as the target word space', () => {
    const { widen, short } = solveLine(500, 10, 600, em, 0.06)
    expect(short).toBe(false)
    expect(widen).toBeCloseTo(516 / 500, 4)
  })

  it('stops at the cap', () => {
    expect(solveLine(420, 8, 600, em, 0.06).widen).toBeCloseTo(1.06, 6)
    expect(solveLine(420, 8, 600, em, 0).widen).toBe(1)
  })

  it('centres a line too short to justify', () => {
    expect(solveLine(200, 3, 600, em, 0.06)).toEqual({ widen: 1, short: true })
  })

  it('has nothing to solve for one word or no measure', () => {
    expect(solveLine(300, 0, 600, em, 0.06)).toEqual({ widen: 1, short: false })
    expect(solveLine(0, 4, 600, em, 0.06)).toEqual({ widen: 1, short: false })
    expect(solveLine(300, 4, 0, em, 0.06)).toEqual({ widen: 1, short: false })
  })
})
