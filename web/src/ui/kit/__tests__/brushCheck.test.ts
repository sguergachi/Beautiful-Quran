import { describe, expect, it } from 'vitest'
import {
  BRUSH_CHECK_KNOB_KEYS,
  brushCheckPath,
  formatBrushCheckCopy,
  parseBrushCheckFromText,
  SHIPPED_CHECK_PARAMS,
} from '../brushCheck'

describe('brushCheckPath + lab params', () => {
  it('ships all lab knobs', () => {
    expect(BRUSH_CHECK_KNOB_KEYS).toHaveLength(15)
    for (const k of BRUSH_CHECK_KNOB_KEYS) {
      expect(SHIPPED_CHECK_PARAMS[k]).toBeTypeOf('number')
    }
  })

  it('locks the settled lab design bit-for-bit', () => {
    expect(SHIPPED_CHECK_PARAMS).toEqual({
      p0x: 0.14,
      p0y: 0.49,
      p1x: 0.39,
      p1y: 0.77,
      p2x: 0.73,
      p2y: 0.12,
      size: 24.7,
      peakHalf: 1.81,
      nibBias: 0.63,
      attack: 0.087,
      releaseStart: 0.74,
      bodyAmp: 0,
      bodyFreq: 0.5,
      paintMs: 301,
      alpha: 0.86,
    })
  })

  it('returns a closed filled path', () => {
    const d = brushCheckPath(24, 1, SHIPPED_CHECK_PARAMS)
    expect(d.startsWith('M')).toBe(true)
    expect(d.endsWith('Z')).toBe(true)
  })

  it('grows as the brush paints', () => {
    const early = brushCheckPath(24, 0.15, SHIPPED_CHECK_PARAMS)
    const full = brushCheckPath(24, 1, SHIPPED_CHECK_PARAMS)
    expect(early.length).toBeLessThan(full.length)
  })

  it('geometry knobs change the path', () => {
    const a = brushCheckPath(24, 1, SHIPPED_CHECK_PARAMS)
    const b = brushCheckPath(24, 1, { ...SHIPPED_CHECK_PARAMS, p1y: 0.9, peakHalf: 3.5 })
    expect(a).not.toBe(b)
  })

  it('round-trips copy/paste', () => {
    const custom = { ...SHIPPED_CHECK_PARAMS, p0x: 0.2, bodyAmp: 0.4, paintMs: 700 }
    const text = formatBrushCheckCopy(custom)
    const parsed = parseBrushCheckFromText(text)!
    expect(parsed.p0x).toBeCloseTo(0.2, 5)
    expect(parsed.bodyAmp).toBeCloseTo(0.4, 5)
    expect(parsed.paintMs).toBe(700)
  })
})
