import { describe, expect, it } from 'vitest'
import {
  buildMushafPage,
  inheritSpannedPlacement,
  mushafFacingPages,
  mushafTokenEndsAyah,
  pageAyahs,
  pageReadingPlace,
} from './mushafPage'

describe('spanned words', () => {
  it('puts the unmapped half of a fused pair on the owner line', () => {
    const placed = inheritSpannedPlacement([
      { surah_id: 2, ayah_number: 72, position: 4, qcf_page: 11, qcf_line: 3, qcf_span_end: 5 },
      { surah_id: 2, ayah_number: 72, position: 5, qcf_page: 0, qcf_line: 0, qcf_span_end: 5 },
    ])
    expect(placed[1]).toMatchObject({ qcf_page: 11, qcf_line: 3 })
  })

  it('marks only the ayah last word, not the last word of a line', () => {
    expect(mushafTokenEndsAyah(4, 9)).toBe(false)
    expect(mushafTokenEndsAyah(9, 9)).toBe(true)
  })
})

describe('mushaf page', () => {
  it('keeps all fifteen lines, including the empty ones', () => {
    const page = buildMushafPage(1, [
      { surahId: 1, ayah: 1, position: 1, line: 2, arabic: 'بِسْمِ' },
      { surahId: 1, ayah: 1, position: 2, line: 2, arabic: 'ٱللَّهِ' },
    ])
    expect(page.lines).toHaveLength(15)
    expect(page.lines[0]?.tokens).toEqual([])
    expect(page.lines[1]?.tokens.map((token) => token.arabic)).toEqual(['بِسْمِ', 'ٱللَّهِ'])
  })

  it('lists ayahs once, in line order', () => {
    expect(
      pageAyahs([
        { surahId: 1, ayah: 2, position: 1, line: 3, arabic: 'a' },
        { surahId: 1, ayah: 1, position: 2, line: 2, arabic: 'b' },
        { surahId: 1, ayah: 1, position: 1, line: 2, arabic: 'c' },
      ]),
    ).toEqual([
      { surahId: 1, ayah: 1 },
      { surahId: 1, ayah: 2 },
    ])
  })

  it('reserves the printed title and preface slots without moving any words', () => {
    const page = buildMushafPage(2, [
      { surahId: 2, ayah: 1, position: 1, line: 3, arabic: 'الم' },
      { surahId: 2, ayah: 2, position: 1, line: 3, arabic: 'ذَٰلِكَ' },
    ])
    expect(page.lines[0]).toMatchObject({ chapter: 2, tokens: [] })
    expect(page.lines[1]).toMatchObject({ basmalah: 2, tokens: [] })
    expect(page.lines[2]?.tokens.map(token => token.ayah)).toEqual([1, 2])
    expect(page.lines).toHaveLength(15)
  })

  it('does not add a second basmalah to Fatiha or one to Tawbah', () => {
    for (const surahId of [1, 9]) {
      const page = buildMushafPage(1, [{ surahId, ayah: 1, position: 1, line: 2, arabic: 'أ' }])
      expect(page.lines[0]?.chapter).toBe(surahId)
      expect(page.lines.some(line => line.basmalah != null)).toBe(false)
    }
  })

  it('keeps every chapter band on a shared leaf and never replaces a word row', () => {
    const page = buildMushafPage(604, [112, 113, 114].map((surahId, index) => ({
      surahId, ayah: 1, position: 1, line: 3 + index * 5, arabic: 'أ',
    })))
    expect(page.lines.filter(line => line.chapter).map(line => [line.number, line.chapter])).toEqual([[1, 112], [6, 113], [11, 114]])
    const crowded = buildMushafPage(1, [
      { surahId: 1, ayah: 7, position: 1, line: 1, arabic: 'أ' },
      { surahId: 2, ayah: 1, position: 1, line: 3, arabic: 'ب' },
    ])
    expect(crowded.lines[0]?.chapter).toBeUndefined()
    expect(crowded.lines[0]?.tokens).toHaveLength(1)
    expect(crowded.lines.some(line => line.chapter === 2)).toBe(true)
  })

  it('includes headings counted outside the source grid, even with no spare leading slots', () => {
    for (const line of [1, 2]) {
      const page = buildMushafPage(586, [{ surahId: 81, ayah: 1, position: 1, line, arabic: 'إِذَا' }])
      expect(page.lines[0]?.chapter).toBe(81)
      expect(page.lines[1]?.basmalah).toBe(81)
      expect(page.lines[2]?.tokens[0]?.arabic).toBe('إِذَا')
      expect(page.lines).toHaveLength(18 - line)
    }
  })
})

describe('mushafFacingPages', () => {
  it('keeps a linked verse on its leaf, then follows a manual turn into another chapter', () => {
    const opened = { surahId: 1, ayah: 5 }
    expect(pageReadingPlace([{ surahId: 1, ayah: 1 }, opened], opened)).toEqual(opened)
    expect(pageReadingPlace([{ surahId: 2, ayah: 6 }, { surahId: 2, ayah: 7 }], opened)).toEqual({ surahId: 2, ayah: 6 })
    expect(pageReadingPlace([], opened)).toBeNull()
  })

  it('stands the odd page on the right and the next on the left', () => {
    expect(mushafFacingPages(1)).toEqual({ right: 1, left: 2 })
    expect(mushafFacingPages(2)).toEqual({ right: 1, left: 2 })
    expect(mushafFacingPages(77)).toEqual({ right: 77, left: 78 })
    expect(mushafFacingPages(604)).toEqual({ right: 603, left: 604 })
  })

  it('clamps to the book', () => {
    expect(mushafFacingPages(0)).toEqual({ right: 1, left: 2 })
    expect(mushafFacingPages(900)).toEqual({ right: 603, left: 604 })
  })
})
