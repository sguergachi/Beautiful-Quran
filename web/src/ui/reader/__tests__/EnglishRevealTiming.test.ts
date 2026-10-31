import { describe, expect, it } from 'vitest'
import { ENGLISH_PAUSE_MS, englishRevealDurationMs } from '../EnglishRevealTiming'

describe('English Scroll pacing', () => {
  it('flows through connected speech right up to the next onset', () => {
    const start = 1000, nextStart = 2000
    expect(start + englishRevealDurationMs(nextStart - start, 990)).toBe(nextStart)
    expect(start + englishRevealDurationMs(nextStart - start)).toBe(nextStart)
  })

  it('separates connected speech from a pause in media time', () => {
    expect(englishRevealDurationMs(1000, 1000 - ENGLISH_PAUSE_MS + 1)).toBe(1000)
    expect(englishRevealDurationMs(1000, 1000 - ENGLISH_PAUSE_MS)).toBe(1000 - ENGLISH_PAUSE_MS)
    expect(englishRevealDurationMs(500, 1000 - ENGLISH_PAUSE_MS + 1, 2)).toBe(500)
    expect(englishRevealDurationMs(500, 1000 - ENGLISH_PAUSE_MS, 2)).toBe(425)
  })

  it('finishes by the voiced end and leaves the entire following silence still', () => {
    const start = 1000, spoken = 700, nextStart = 2000
    const duration = englishRevealDurationMs(nextStart - start, spoken)
    expect(start + duration).toBe(1700)
    for (let at = start + spoken; at < nextStart; at += 11) {
      expect(Math.min(1, (at - start) / duration)).toBe(1)
      expect(Math.max(0, (at - nextStart) / duration)).toBe(0)
    }
  })

  it('fits short first-pass and repeat sweeps before their own voiced ends', () => {
    expect(englishRevealDurationMs(300, 80)).toBe(80)
    expect(englishRevealDurationMs(400, 60)).toBe(60)
  })

  it('never converts a paused deadline past speech at any playback speed', () => {
    for (const speed of [0.5, 1, 1.5, 2]) {
      for (const spoken of [1, 79, 140, 700, 1000]) {
        const wall = Math.max(140, 1000 / speed)
        const duration = Math.max(1, englishRevealDurationMs(wall, spoken, speed) * speed)
        expect(duration).toBeGreaterThan(0)
        expect(duration).toBeLessThanOrEqual(Math.max(spoken, wall * speed))
        if (wall * speed - spoken >= ENGLISH_PAUSE_MS) expect(duration).toBeLessThanOrEqual(spoken)
      }
    }
  })

  it('keeps the full hold when no positive voiced span is available', () => {
    expect(englishRevealDurationMs(1000, undefined)).toBe(1000)
    expect(englishRevealDurationMs(1000, 0)).toBe(1000)
  })
})
