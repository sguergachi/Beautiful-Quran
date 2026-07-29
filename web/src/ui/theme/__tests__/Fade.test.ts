import { describe, expect, it } from 'vitest'
import {
  inkSmootherstep,
  inkWashAlpha,
  inkWashProfile,
  INK_PROFILE_STOPS,
  INK_WASH_BAND_COUNT,
  paperCoverMaskImage,
  washBandOffsetFraction,
  washMaskImage,
  washMaskImageFlat,
  wholeWordInkAlpha,
} from '../Fade'

describe('fade math', () => {
  it('smootherstep is 0 at 0 and 1 at 1', () => {
    expect(inkSmootherstep(0)).toBe(0)
    expect(inkSmootherstep(1)).toBe(1)
    expect(inkSmootherstep(0.5)).toBeCloseTo(0.5, 5)
  })

  it('wash profile is soft at ends and front-loads density (R2)', () => {
    expect(inkWashProfile(0)).toBe(0)
    expect(inkWashProfile(1)).toBe(1)
    expect(inkWashProfile(0.5)).toBeGreaterThan(0.5)
    expect(inkWashProfile(0.5)).toBeGreaterThan(inkSmootherstep(0.5))
    expect(inkWashProfile(0.25)).toBeGreaterThan(inkSmootherstep(0.25))
    expect(inkWashProfile(0.75)).toBeLessThan(1)
    expect(inkWashProfile(0.75)).toBeGreaterThan(inkWashProfile(0.5))
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

  it('washMaskImage builds banded diagonal gradients at mid progress', () => {
    const mask = washMaskImage(0.4, 0.22, true)
    expect(mask).not.toBe('none')
    if (mask === 'none') return
    expect(mask.image.split('linear-gradient').length - 1).toBe(INK_WASH_BAND_COUNT)
    // Diagonal (not "to right") so iso-alpha isn't a vertical wipe bar.
    expect(mask.image).toMatch(/linear-gradient\(\d+\.\d+deg/)
    // Each band has INK_PROFILE_STOPS rgba stops.
    expect(mask.image.split('rgba').length - 1).toBe(
      INK_WASH_BAND_COUNT * INK_PROFILE_STOPS,
    )
    expect(mask.size).toContain('%')
    expect(mask.position.split(',').length).toBe(INK_WASH_BAND_COUNT)
  })

  it('wash bands stagger deterministically and stay in [-1,1] (R3)', () => {
    const a = washBandOffsetFraction(12, 0)
    const b = washBandOffsetFraction(12, 1)
    expect(a).not.toBe(b)
    expect(a).toBeGreaterThanOrEqual(-1)
    expect(a).toBeLessThanOrEqual(1)
    expect(washBandOffsetFraction(12, 0)).toBe(a) // stable
  })

  it('paperCoverMaskImage is banded; flat progress 0 is full cover alpha', () => {
    expect(paperCoverMaskImage(1, 0.22, true)).toBe('none')
    const flat = washMaskImageFlat(0, 0.22, true)
    // Progress 0 → glyph at resting → paper cover (1 − resting) on flat path.
    expect(flat).toContain('rgba(0,0,0,0.2200)')
    const mask = paperCoverMaskImage(0, 0.22, true)
    expect(mask).not.toBe('none')
    if (mask === 'none') return
    expect(mask.image.startsWith('linear-gradient')).toBe(true)
    // Paper cover at progress 0 → uniform (1 − resting) = 0.78.
    expect(mask.image).toContain('rgba(0,0,0,0.7800)')
  })
})
