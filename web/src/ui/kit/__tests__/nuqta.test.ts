import { describe, expect, it } from 'vitest'
import {
  formatNuqtaCopy,
  NUQTA_KNOB_GROUPS,
  nuqtaPath,
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
      liftY1: 0.1,
      wetDx: -1.5,
      poolAlpha: 0.8,
    }
    expect(parseNuqtaFromText(formatNuqtaCopy(tuned), SHIPPED_NUQTA)).toEqual(tuned)
  })

  it('reads Android Kotlin-style pairs and refuses foreign knobs', () => {
    expect(parseNuqtaFromText('bow = 1.5f', SHIPPED_NUQTA)?.bow).toBe(1.5)
    expect(parseNuqtaFromText('{ p0x: 0.2, alpha: 0.9 }', SHIPPED_NUQTA)).toBeNull()
  })

  it('keeps the shipped qalam cut at bow 1', () => {
    expect(nuqtaPath(20, 1)).toBe('M9.8 1.8Q13.8 3.2 18.2 8.6Q17.2 13.2 10.6 18.2Q6.2 16.8 1.8 11.6Q2.8 6.6 9.8 1.8Z')
  })
})
