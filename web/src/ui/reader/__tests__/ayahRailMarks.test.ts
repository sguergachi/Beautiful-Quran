import { describe, expect, it } from 'vitest'
import {
  collapsedAyahBarIndex,
  HIZB_MARK,
  juzOpeningAt,
  juzStartByAyah,
  pageStartByAyah,
  railMarkPieces,
  railMarks,
  SAJDA_MARK,
  wheelCoast,
  wheelCoastAt,
} from '../ayahRailMarks'
import { railAnchorY } from '../ayahRailMath'

describe('railAnchorY', () => {
  it('rides between 72px margins like a scrollbar thumb', () => {
    expect(railAnchorY(1, 600, 286, 17)).toBe(72)
    expect(railAnchorY(286, 600, 286, 17)).toBe(528)
  })
  it('centres a short surah ladder', () => {
    // 7 verses span 6 gaps of 17px = 102px, centred in 600px.
    expect(railAnchorY(1, 600, 7, 17)).toBe(249)
    expect(railAnchorY(7, 600, 7, 17)).toBe(351)
  })
})

const ayah = (number: number, page: number, text = 'x', surahId = 2) => ({ surahId, number, page, text })

describe('ayah rail marks', () => {
  it('marks only the ayah that opens a page, skipping unknown pages', () => {
    const marks = pageStartByAyah([ayah(1, 2), ayah(2, 2), ayah(3, 0), ayah(4, 3), ayah(5, 3)])
    expect([...marks]).toEqual([[1, 2], [4, 3]])
  })

  it('finds juzʾ openings like Android juzOpeningAt', () => {
    expect(juzOpeningAt(1, 1)).toBe(1)
    expect(juzOpeningAt(2, 142)).toBe(2)
    expect(juzOpeningAt(2, 141)).toBeNull()
    expect(juzOpeningAt(15, 1)).toBe(14)
    expect(juzOpeningAt(78, 1)).toBe(30)
    expect(juzOpeningAt(78, 2)).toBeNull()
    expect([...juzStartByAyah([ayah(141, 1), ayah(142, 1), ayah(253, 1)])]).toEqual([[142, 2], [253, 3]])
  })

  it('orders pieces page, ۞, JUZ, ۩ with a dot standing in for a missing ۞', () => {
    const marks = railMarks([
      ayah(1, 22, 'a', 15),
      ayah(2, 22, `b ${HIZB_MARK}`, 15),
      ayah(3, 23, `c ${SAJDA_MARK}`, 15),
    ])
    expect(railMarkPieces(marks, 1).map((p) => p.text)).toEqual(['p. 22', '·', 'JUZ 14'])
    expect(railMarkPieces(marks, 2).map((p) => p.text)).toEqual([HIZB_MARK])
    expect(railMarkPieces(marks, 3).map((p) => p.text)).toEqual(['p. 23', SAJDA_MARK])
  })

  it('maps an ayah onto the nearest collapsed bar', () => {
    expect(collapsedAyahBarIndex(286, 1)).toBe(0)
    expect(collapsedAyahBarIndex(286, 286)).toBe(16)
    expect(collapsedAyahBarIndex(7, 4)).toBe(2)
  })

  it('coasts a fling and stops at the bounds', () => {
    expect(wheelCoast(10, 0, 50)).toEqual({ end: 10, durationMs: 0 })
    const free = wheelCoast(10, 20, 286)
    expect(free.end).toBeGreaterThan(12)
    expect(free.end).toBeLessThan(13.5)
    expect(wheelCoastAt(10, 20, free.durationMs)).toBeCloseTo(free.end, 1)
    const bounded = wheelCoast(5, 200, 7)
    expect(bounded.end).toBe(7)
    expect(wheelCoastAt(5, 200, bounded.durationMs)).toBeCloseTo(7, 3)
  })
})
