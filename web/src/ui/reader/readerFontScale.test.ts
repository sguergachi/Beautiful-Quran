import { describe, expect, it } from 'vitest'
import { FONT_SCALE_MAX, FONT_SCALE_MIN, FONT_SCALE_STEP } from '../../data/settings'
import { pinchFontScale } from './readerFontScale'

describe('pinchFontScale', () => {
  it('holds inside the deadband', () => {
    expect(pinchFontScale(1, 1.05, 1)).toBe(1)
  })

  it('steps one stop once past 60% of a step', () => {
    const next = pinchFontScale(1, (1 + FONT_SCALE_STEP * 0.65) / 1, 1)
    expect(next).toBeCloseTo(1 + FONT_SCALE_STEP)
  })

  it('clamps to the range and ignores bad zoom', () => {
    expect(pinchFontScale(1, 10, 1)).toBeCloseTo(FONT_SCALE_MAX)
    expect(pinchFontScale(1, 0.1, 1)).toBeCloseTo(FONT_SCALE_MIN)
    expect(pinchFontScale(1, Number.NaN, 1.2)).toBe(1.2)
  })
})
