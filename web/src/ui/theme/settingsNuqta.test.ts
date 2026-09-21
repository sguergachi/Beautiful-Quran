import { describe, expect, it } from 'vitest'
import { settingsNuqtaStretch } from './settingsNuqta'

describe('settings nuqta stretch', () => {
  it('starts slack and buys less swell as the turn runs on', () => {
    expect(settingsNuqtaStretch(0)).toBe(0)
    expect(settingsNuqtaStretch(1)).toBeCloseTo(0.4, 5)
    const early = settingsNuqtaStretch(0.2) - settingsNuqtaStretch(0)
    const late = settingsNuqtaStretch(1) - settingsNuqtaStretch(0.8)
    expect(early).toBeGreaterThan(late)
  })
})
