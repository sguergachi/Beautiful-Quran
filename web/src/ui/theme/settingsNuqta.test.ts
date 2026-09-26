import { describe, expect, it } from 'vitest'
import { SettingsNuqtaMachine, settingsNuqtaStretch } from './settingsNuqta'

describe('settings nuqta stretch', () => {
  it('starts slack and buys less swell as the turn runs on', () => {
    expect(settingsNuqtaStretch(0)).toBe(0)
    expect(settingsNuqtaStretch(1)).toBeCloseTo(0.4, 5)
    const early = settingsNuqtaStretch(0.2) - settingsNuqtaStretch(0)
    const late = settingsNuqtaStretch(1) - settingsNuqtaStretch(0.8)
    expect(early).toBeGreaterThan(late)
  })

  it('stops asking for frames once the drop has settled on Settings', () => {
    const machine = new SettingsNuqtaMachine()
    machine.step(16, 1, 200)
    expect(machine.busy()).toBe(true)
    for (let i = 0; i < 90; i++) machine.step(16, 1, 200)
    expect(machine.pose().spread).toBe(1)
    expect(machine.pose().presence).toBe(1)
    expect(machine.busy()).toBe(false)
  })
})
