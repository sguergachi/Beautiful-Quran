import { describe, expect, it } from 'vitest'
import {
  glintGlowAlpha,
  glintLetterLight,
  glintLightLagMs,
  glintLightLevel,
  glintResonance,
} from '../glintLight'

describe('Android glint light fidelity', () => {
  it('keeps still wet ink when no acoustic event is admitted', () => {
    expect(glintResonance(1, 0)).toBe(0)
    expect(glintLightLevel(0)).toBe(1)
    expect(glintGlowAlpha(0.4, 1)).toBeCloseTo(0.4)
  })
  it('preserves headroom and makes crests brighter than troughs', () => {
    expect(glintLetterLight(1)).toBeCloseTo(0.8858)
    expect(glintLetterLight(2)).toBe(1)
    expect(glintLightLevel(1)).toBeCloseTo(1.3)
    expect(glintLightLevel(-1)).toBeCloseTo(0.8899)
    expect(glintGlowAlpha(0.4, 1.1)).toBeGreaterThan(0.4)
    expect(glintGlowAlpha(0.4, 0.95)).toBeLessThan(0.4)
  })
  it('compensates filter phase lag rather than guessing a fixed delay', () => {
    expect(glintLightLagMs(139, 5)).toBeGreaterThan(0)
    expect(glintLightLagMs(139, 5)).toBeLessThan(50)
    expect(glintLightLagMs(0, 5)).toBe(0)
  })
})
