import { describe, expect, it } from 'vitest'
import { VELLUM_FRAG, VELLUM_TUNING, VELLUM_VERT } from '../vellumShader'

describe('vellumShader', () => {
  it('keeps Android ContextualGuideTuning defaults', () => {
    expect(VELLUM_TUNING).toEqual({
      bodyEdge: 0.5,
      featherWidth: 0.2819,
      fadeSoftness: 1.3329,
      blurRadiusPx: 24,
      blurStrength: 1,
      vellumGrain: 0.0297,
      verticalTaper: 0.24,
    })
  })

  it('ships a full-screen triangle strip vertex stage and density field', () => {
    expect(VELLUM_VERT).toContain('a_pos')
    expect(VELLUM_FRAG).toContain('vellumDensity')
    expect(VELLUM_FRAG).toContain('brushedPigment')
    expect(VELLUM_FRAG).toContain('u_actionCenter')
    expect(VELLUM_FRAG).toContain('gl_FragColor')
  })

  it('uses a precision-safe hash and multi-octave grain, not sin-lattice noise', () => {
    expect(VELLUM_FRAG).toContain('fbm')
    expect(VELLUM_FRAG).toContain('0.1031')
    expect(VELLUM_FRAG).not.toContain('43758.5453')
    expect(VELLUM_FRAG).toContain('highp')
  })
})
