import { describe, expect, it } from 'vitest'
import {
  buildMushafPage,
  inheritSpannedPlacement,
  mushafTokenEndsAyah,
  pageAyahs,
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
})
