import { describe, expect, it } from 'vitest'
import { mushafFit, mushafLeafFit, naturalLineWidth } from '../mushafFit'

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

  it('never grows the type: a wide page draws its block in instead', () => {
    expect(mushafLeafFit(600, 500)).toBe(1)
    expect(mushafLeafFit(600, 600)).toBe(1)
  })

  it('is neutral with nothing to measure', () => {
    expect(mushafLeafFit(0, 0)).toBe(1)
    expect(mushafLeafFit(500, 0)).toBe(1)
  })
})
