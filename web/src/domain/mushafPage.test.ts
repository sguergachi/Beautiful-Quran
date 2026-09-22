import { describe, expect, it } from 'vitest'
import { buildMushafPage, pageAyahs } from './mushafPage'

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
