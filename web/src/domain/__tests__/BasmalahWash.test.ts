import { describe, expect, it } from 'vitest'
import type { Segment } from '../../data/models'
import {
  BASMALAH_WASH_WORDS,
  WORD_END_PROGRESS,
  basmalahWashProgress,
} from '../BasmalahWash'
import { prefaceRampProgress, prefaceWashProgress } from '../../ui/reader/InkEngine'

/** Alafasy's `001001.mp3` word timings, straight out of the shipped DB. */
const alafasy: Segment[] = [
  { position: 1, startMs: 60, endMs: 610 },
  { position: 2, startMs: 610, endMs: 1439 },
  { position: 3, startMs: 1439, endMs: 2532 },
  { position: 4, startMs: 2532, endMs: 5870 },
]

const at = (positionMs: number, segments: Segment[] = alafasy): number => {
  const progress = basmalahWashProgress(positionMs, segments)
  expect(progress).not.toBeNull()
  return progress as number
}

describe('BasmalahWash', () => {
  it('tiles the artwork with the four words in reading order', () => {
    expect(WORD_END_PROGRESS).toHaveLength(BASMALAH_WASH_WORDS.length)
    expect(BASMALAH_WASH_WORDS).toHaveLength(4)
    let previous = 0
    for (const end of WORD_END_PROGRESS) {
      expect(end).toBeGreaterThan(previous)
      previous = end
    }
    expect(WORD_END_PROGRESS[WORD_END_PROGRESS.length - 1]).toBe(1)
  })

  it('lays no ink before the reciter starts', () => {
    expect(at(0)).toBe(0)
    expect(at(59)).toBe(0)
    expect(at(61)).toBeGreaterThan(0)
  })

  it('crosses each word band while that word is spoken', () => {
    alafasy.forEach((segment, index) => {
      const bandStart = index === 0 ? 0 : WORD_END_PROGRESS[index - 1]
      const bandEnd = WORD_END_PROGRESS[index]
      expect(at(segment.startMs)).toBeCloseTo(bandStart, 3)
      expect(at(segment.endMs - 1)).toBeLessThanOrEqual(bandEnd + 1e-3)
      expect(at(segment.endMs - 1)).toBeGreaterThan(bandStart)
    })
  })

  it('settles as the closing madd ends, not at a fraction of the file', () => {
    expect(at(5870)).toBe(1)
    expect(at(9000)).toBe(1)
    expect(at(5000)).toBeLessThan(1)
  })

  it('never moves backwards', () => {
    let previous = -1
    for (let ms = 0; ms <= 6200; ms += 8) {
      const progress = at(ms)
      expect(progress).toBeGreaterThanOrEqual(previous - 1e-6)
      expect(progress).toBeGreaterThanOrEqual(0)
      expect(progress).toBeLessThanOrEqual(1)
      previous = progress
    }
  })

  it('holds a word gap into its own band', () => {
    // Ash-Shuraym leaves 10 ms gaps between words.
    const gapped: Segment[] = [
      { position: 1, startMs: 140, endMs: 650 },
      { position: 2, startMs: 660, endMs: 1160 },
      { position: 3, startMs: 1170, endMs: 2110 },
      { position: 4, startMs: 2120, endMs: 2560 },
    ]
    const settled = at(659, gapped)
    expect(settled).toBeGreaterThan(WORD_END_PROGRESS[0] * 0.99)
    expect(settled).toBeLessThanOrEqual(WORD_END_PROGRESS[0])
  })

  it('returns null for timings it cannot map', () => {
    expect(basmalahWashProgress(100, null)).toBeNull()
    expect(basmalahWashProgress(100, [])).toBeNull()
    expect(basmalahWashProgress(100, alafasy.slice(0, 3))).toBeNull()
    // A repeated word — the wash would have to jump bands.
    expect(
      basmalahWashProgress(100, [
        { position: 1, startMs: 60, endMs: 610 },
        { position: 2, startMs: 610, endMs: 1439 },
        { position: 2, startMs: 1439, endMs: 2000 },
        { position: 3, startMs: 2000, endMs: 2532 },
      ]),
    ).toBeNull()
  })

  it('is what the preface wash uses, with the clip ramp as fallback', () => {
    expect(prefaceWashProgress(3000, 7000, alafasy)).toBe(at(3000))
    expect(prefaceWashProgress(3000, 7000, alafasy.slice(0, 3))).toBe(
      prefaceRampProgress(3000, 7000),
    )
    expect(prefaceWashProgress(3000, 7000)).toBe(prefaceRampProgress(3000, 7000))
  })
})
