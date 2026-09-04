import { describe, expect, it } from 'vitest'
import {
  inkSmootherstep,
  inkWashAlpha,
  INK_PROFILE_STOPS,
  paperCoverMaskImage,
  washMaskImage,
  wholeWordInkAlpha,
} from '../Fade'

describe('fade math', () => {
  it('smootherstep is 0 at 0 and 1 at 1', () => {
    expect(inkSmootherstep(0)).toBe(0)
    expect(inkSmootherstep(1)).toBe(1)
    expect(inkSmootherstep(0.5)).toBeCloseTo(0.5, 5)
  })

  it('whole-word breath interpolates resting to full', () => {
    expect(wholeWordInkAlpha(0, 0.22)).toBeCloseTo(0.22, 5)
    expect(wholeWordInkAlpha(1, 0.22)).toBe(1)
  })

  it('wash at progress 1 is full ink everywhere', () => {
    expect(inkWashAlpha(0, 1, 0.22, true)).toBe(1)
    expect(inkWashAlpha(1, 1, 0.22, false)).toBe(1)
  })

  it('ahead of the wash rests at upcoming ink', () => {
    expect(inkWashAlpha(0.9, 0.1, 0.22, false)).toBeCloseTo(0.22, 4)
  })

  it('the first revealed letter leads the last in each direction', () => {
    expect(inkWashAlpha(0, 0.4, 0.22, false)).toBeGreaterThan(
      inkWashAlpha(1, 0.4, 0.22, false),
    )
    expect(inkWashAlpha(1, 0.4, 0.22, true)).toBeGreaterThan(
      inkWashAlpha(0, 0.4, 0.22, true),
    )
  })

  it('washMaskImage returns none when complete', () => {
    expect(washMaskImage(1, 0.22, true)).toBe('none')
  })

  it('washMaskImage builds a multi-stop gradient at mid progress', () => {
    const mask = washMaskImage(0.4, 0.22, true)
    expect(mask.startsWith('linear-gradient(to right,')).toBe(true)
    expect(mask.split('rgba').length - 1).toBe(INK_PROFILE_STOPS)
  })

  it('paperCoverMaskImage is the inverse of glyph wash alpha', () => {
    expect(paperCoverMaskImage(1, 0.22, true)).toBe('none')
    const mask = paperCoverMaskImage(0, 0.22, true)
    expect(mask.startsWith('linear-gradient(to right,')).toBe(true)
    // Progress 0 → uniform paper cover of (1 − restingAlpha).
    expect(mask).toContain('rgba(0,0,0,0.7800)')
  })
})
