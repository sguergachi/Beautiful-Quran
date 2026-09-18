import { describe, expect, it } from 'vitest'
import {
  formatNuqtaCopy,
  NUQTA_KNOB_GROUPS,
  nuqtaDropRadii,
  nuqtaFingers,
  nuqtaPath,
  nuqtaInkStops,
  parseNuqtaFromText,
  SHIPPED_NUQTA,
  type NuqtaParams,
} from '../nuqta'

const knobs = NUQTA_KNOB_GROUPS.flatMap((g) => g.knobs)

describe('nuqta lab params', () => {
  it('has one slider for every shipped knob, and none extra', () => {
    expect(knobs.map((k) => k.key).sort()).toEqual(Object.keys(SHIPPED_NUQTA).sort())
  })

  it('ships values inside every slider range', () => {
    for (const k of knobs) {
      expect(SHIPPED_NUQTA[k.key]).toBeGreaterThanOrEqual(k.min)
      expect(SHIPPED_NUQTA[k.key]).toBeLessThanOrEqual(k.max)
    }
  })

  it('round-trips copy → paste', () => {
    const tuned: NuqtaParams = {
      ...SHIPPED_NUQTA,
      sizeDp: 24,
      bow: 1.4,
      spreadMs: 900,
      spreadSharpness: 4.5,
      liftY1: 0.1,
      originDx: -1.5,
      fingers: 0.6,
      seed: 12,
      fringeAlpha: 0.4,
    }
    expect(parseNuqtaFromText(formatNuqtaCopy(tuned), SHIPPED_NUQTA)).toEqual(tuned)
  })

  it('reads Android Kotlin-style pairs and refuses foreign knobs', () => {
    expect(parseNuqtaFromText('bow = 1.5f', SHIPPED_NUQTA)?.bow).toBe(1.5)
    expect(parseNuqtaFromText('{ p0x: 0.2, alpha: 0.9 }', SHIPPED_NUQTA)).toBeNull()
  })

  it('draws one drop that is ragged mid-spread and whole at the settle', () => {
    const fingers = nuqtaFingers(SHIPPED_NUQTA.seed)
    const mid = nuqtaDropRadii(0.2, SHIPPED_NUQTA, fingers)
    const mean = mid.reduce((a, b) => a + b, 0) / mid.length
    expect((Math.max(...mid) - Math.min(...mid)) / mean).toBeGreaterThan(0.12)
    for (const r of nuqtaDropRadii(1, SHIPPED_NUQTA, fingers)) expect(r).toBeCloseTo(1, 4)
  })

  it('is densest where it landed and soaks outward to full strength', () => {
    const fingers = nuqtaFingers(SHIPPED_NUQTA.seed)
    const stops = (t: number) =>
      nuqtaInkStops(t, SHIPPED_NUQTA, Math.max(...nuqtaDropRadii(t, SHIPPED_NUQTA, fingers)))
    for (let t = 0.01; t <= 1; t += 0.01) {
      const s = stops(t)
      for (let k = 1; k < 4; k++) expect(s[k * 2 + 1]).toBeLessThanOrEqual(s[(k - 1) * 2 + 1] + 1e-9)
    }
    expect(stops(0.7)[2]).toBeGreaterThan(stops(0.35)[2] + 0.2)
    expect(stops(1)[5]).toBeCloseTo(SHIPPED_NUQTA.inkAlpha, 4)
  })

  it('keeps the shipped qalam cut at bow 1', () => {
    expect(nuqtaPath(20, 1)).toBe('M9.8 1.8Q13.8 3.2 18.2 8.6Q17.2 13.2 10.6 18.2Q6.2 16.8 1.8 11.6Q2.8 6.6 9.8 1.8Z')
  })
})
