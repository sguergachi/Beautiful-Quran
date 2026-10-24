import { describe, expect, it } from 'vitest'
import { englishRevealDurationMs } from '../EnglishRevealTiming'

describe('English Scroll completion pause', () => {
  it('finishes a normal word 100 ms early while retaining the next start', () => {
    const start = 1000, nextStart = 2000
    const finish = start + englishRevealDurationMs(nextStart - start, 140)
    expect(finish).toBe(1900)
    expect(nextStart - finish).toBe(100)
  })

  it('keeps the soft-edge floor and never stretches a short repeat', () => {
    expect(englishRevealDurationMs(140, 140)).toBe(140)
    expect(englishRevealDurationMs(220, 140)).toBe(140)
    expect(englishRevealDurationMs(80, 140)).toBe(80)
  })

  it('converts the wall-time saving once at each playback speed', () => {
    for (const speed of [0.5, 1, 1.5, 2]) {
      const wall = 1000 / speed
      expect((wall - englishRevealDurationMs(wall, 140)) * speed).toBeCloseTo(100 * speed)
    }
  })
})
