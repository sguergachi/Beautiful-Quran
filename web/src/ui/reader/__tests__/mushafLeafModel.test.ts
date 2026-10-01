import { describe, expect, it, vi } from 'vitest'
import type { SurahContent } from '../../../data/models'
import type { RuntimeMushafWord } from '../../../data/runtimeMushaf'
import { mushafLeafModel } from '../mushafLeafModel'

const content: SurahContent = {
  surah: { id: 1, nameArabic: '', nameTransliteration: 'Fatiha', nameTranslation: '', revelationPlace: '', ayahCount: 1 },
  ayahs: [{ surahId: 1, number: 1, text: '', translation: 'Meaning', page: 1, words: [
    { position: 1, arabic: 'أ', translation: '', transliteration: '' },
    { position: 2, arabic: 'ب', translation: '', transliteration: '' },
    { position: 3, arabic: 'ج', translation: '', transliteration: '' },
  ] }],
}
const row = (position: number): RuntimeMushafWord => ({
  record_type: 'mushaf_word', record_key: `1:1:${position}`, surah_id: 1, ayah_number: 1,
  position, translation_en: '', transliteration: '', qcf_v2: '', qcf_page: 1, qcf_line: 1, qcf_span_end: position, ayah_page: 1,
})

describe('cached leaf model', () => {
  it('shares construction and last-position scans across every strip and later store emits', () => {
    const rows = [row(2), row(1)]
    const lookup = vi.fn(() => content)
    const model = mushafLeafModel(1, rows, lookup)
    const calls = lookup.mock.calls.length
    for (let copy = 0; copy < 18; copy++) expect(mushafLeafModel(1, rows, lookup)).toBe(model)
    expect(lookup).toHaveBeenCalledTimes(calls)
    expect(model.leaf.lines[0]?.tokens.map((token) => token.arabic)).toEqual(['أ', 'ب'])
    // A verse can continue on the next leaf: its mark stays with its real last word.
    expect(model.lastPositions.get('1:1')).toBe(3)
    expect(model.englishAyahs[0]?.translation).toBe('Meaning')
  })

  it('rebuilds when the runtime page map supplies a new row array', () => {
    const before = mushafLeafModel(1, [row(1)], () => content)
    const after = mushafLeafModel(1, [row(1), row(2)], () => content)
    expect(after).not.toBe(before)
    expect(after.leaf.lines[0]?.tokens).toHaveLength(2)
  })
})
