import { describe, expect, it } from 'vitest'
import {
  MUSHAF_MAX_CONDENSE,
  MUSHAF_MIN_GAP,
  mushafFit,
  mushafLeafFit,
  mushafMeasure,
  naturalLineWidth,
  solveLine,
} from '../mushafFit'

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

  it('measures a line as it would be set condensed', () => {
    expect(naturalLineWidth([100, 100], 10, 0.04)).toBeCloseTo(192 + 10, 6)
  })

  it('never sets a word space wider than Hafs sets its own', () => {
    expect(MUSHAF_MIN_GAP).toBeLessThanOrEqual(0.22)
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
    expect(fit).toBeGreaterThan(1.18)
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

describe('mushafMeasure', () => {
  const em = 20
  it('draws the block in to the widest line set condensed at minimum spaces', () => {
    const lines = [{ ink: 500, gaps: 10 }, { ink: 450, gaps: 9 }]
    expect(mushafMeasure(lines, em, 1000)).toBeCloseTo(500 * (1 - MUSHAF_MAX_CONDENSE) + 10 * MUSHAF_MIN_GAP * em, 6)
  })

  it('never asks for more than the page gives', () => {
    expect(mushafMeasure([{ ink: 900, gaps: 10 }], em, 600)).toBe(600)
  })

  it('takes the page with nothing to measure', () => {
    expect(mushafMeasure([], em, 600)).toBe(600)
  })

  it('leaves the long line room enough at the condense limit', () => {
    const lines = [{ ink: 500, gaps: 10 }, { ink: 420, gaps: 8 }]
    const measure = mushafMeasure(lines, em, 1000)
    const { widen } = solveLine(500, 10, measure, em, 0.06)
    expect(widen).toBeCloseTo(1 - MUSHAF_MAX_CONDENSE, 6)
    expect(500 * widen + 10 * MUSHAF_MIN_GAP * em).toBeLessThanOrEqual(measure + 1e-9)
  })
})

describe('solveLine', () => {
  const em = 20
  it('leaves a full line alone', () => {
    // 10 gaps at the 0.3 em target need 60px; ink 540 fills the 600 measure.
    expect(solveLine(540, 10, 600, em, 0.06)).toEqual({ widen: 1, short: false })
    expect(solveLine(550, 10, 600, em, 0.06)).toEqual({ widen: 1, short: false })
  })

  it('widens a loose line only as far as the target word space', () => {
    const { widen, short } = solveLine(530, 10, 600, em, 0.06)
    expect(short).toBe(false)
    expect(widen).toBeCloseTo(540 / 530, 4)
  })

  it('condenses a line that overruns at minimum spaces, up to the limit', () => {
    // 10 minimum spaces are 44px; 570 ink needs 614 > 600.
    expect(solveLine(570, 10, 600, em, 0.06).widen).toBeCloseTo(556 / 570, 6)
    expect(solveLine(700, 10, 600, em, 0.06).widen).toBe(1 - MUSHAF_MAX_CONDENSE)
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
