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

  it('finishes by the voiced end and leaves the entire following silence still', () => {
    const start = 1000, spoken = 700, nextStart = 2000
    const duration = englishRevealDurationMs(nextStart - start, 140, spoken)
    expect(start + duration).toBe(1700)
    for (let at = start + spoken; at < nextStart; at += 11) {
      expect(Math.min(1, (at - start) / duration)).toBe(1)
      expect(Math.max(0, (at - nextStart) / duration)).toBe(0)
    }
  })

  it('fits short first-pass and repeat sweeps before their own voiced ends', () => {
    expect(englishRevealDurationMs(300, 140, 80)).toBe(80)
    expect(englishRevealDurationMs(400, 140, 60)).toBe(60)
    expect(englishRevealDurationMs(1000, 140, 1000)).toBe(900)
  })

  it('never converts the voiced deadline past speech at any playback speed', () => {
    for (const speed of [0.5, 1, 1.5, 2]) {
      for (const spoken of [1, 79, 140, 700, 1000]) {
        const wall = Math.max(140, 1000 / speed)
        const duration = Math.max(1, englishRevealDurationMs(wall, 140, spoken, speed) * speed)
        expect(duration).toBeGreaterThan(0)
        expect(duration).toBeLessThanOrEqual(spoken)
      }
    }
  })

  it('keeps the existing fallback when no positive voiced span is available', () => {
    expect(englishRevealDurationMs(1000, 140, undefined)).toBe(900)
    expect(englishRevealDurationMs(1000, 140, 0)).toBe(900)
  })
})
