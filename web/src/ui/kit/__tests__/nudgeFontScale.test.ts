import { describe, expect, it } from 'vitest'
import { nudgeFontScale } from '../../../data/settings'

describe('nudgeFontScale', () => {
  it('decreases by one stop', () => {
    expect(nudgeFontScale(1.0, -1)).toBeCloseTo(0.9)
  })

  it('increases by one stop', () => {
    expect(nudgeFontScale(1.0, +1)).toBeCloseTo(1.1)
  })

  it('clamps at min and max', () => {
    expect(nudgeFontScale(0.8, -1)).toBeCloseTo(0.8)
    expect(nudgeFontScale(1.6, +1)).toBeCloseTo(1.6)
  })
})
