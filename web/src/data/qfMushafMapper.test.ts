import { describe, expect, it } from 'vitest'
import { normalizeQfMushaf, type StoredQfResource } from './qfMushafMapper'

describe('normalizeQfMushaf', () => {
  it('keeps later words aligned when one QF word spans two canonical positions', () => {
    const rows = map('2:181', 14, 13)

    expect(rows[2]?.qcf_span_end).toBe(4)
    expect(rows[2]?.translation_en).toBe('t3')
    expect(rows[3]?.qcf_v2).toBe('')
    expect(rows[4]?.translation_en).toBe('t4')
    expect(rows.at(-1)?.qcf_v2).toContain(codePoint(14))
  })

  it('joins two QF words onto one canonical word and prefers a live supplement', () => {
    const rows = map('15:7', 7, 8, true)

    expect(rows[0]?.qcf_v2).toBe(`${codePoint(1)} ${codePoint(2)}`)
    expect(rows[0]?.translation_en).toBe('t1 t2')
    expect(rows[0]?.transliteration).toBe('live tr2')
    expect(rows[1]?.translation_en).toBe('t3')
  })

  it('ignores untranslated rows QF ships outside the reader alignment', () => {
    const rows = map('2:181', 14, 13, false, [{ word_id: 9001, text: null }, { word_id: 9002 }])

    expect(rows[2]?.translation_en).toBe('t3')
    expect(rows).toHaveLength(14)
  })

  it('still fails closed when a referenced word has no translation text', () => {
    expect(() => map('2:181', 14, 13, false, [], [104])).toThrow('QF translation is missing for word 104')
  })
})

function map(
  verseKey: string,
  canonicalCount: number,
  qfCount: number,
  supplement = false,
  extraTranslations: Record<string, unknown>[] = [],
  untranslatedIds: number[] = [],
) {
  const translations = wordResource('word_by_word_translations', 59, qfCount, 't').records.map((row) => {
    const record = row as Record<string, unknown>
    return untranslatedIds.includes(Number(record.word_id)) ? { ...record, text: null } : record
  })
  const resources: StoredQfResource[] = [
    {
      resourceGroup: 'mushafs', resourceId: 1,
      records: [
        { id: 1, record_type: 'mushaf', pages_count: 1, lines_per_page: 15 },
        { id: 2, record_type: 'mushaf_page', page_number: 1 },
        ...Array.from({ length: qfCount }, (_, index) => ({
          id: 10 + index, record_type: 'mushaf_word', verse_id: 1,
          word_id: 101 + index, text: codePoint(index + 1), char_type_name: 'word',
          page_number: 1, line_number: 1, position_in_verse: index + 1,
        })),
        {
          id: 999, record_type: 'mushaf_word', verse_id: 1, word_id: 999,
          text: codePoint(qfCount + 1), char_type_name: 'end', page_number: 1,
          line_number: 1, position_in_verse: qfCount + 1,
        },
      ],
    },
    { resourceGroup: 'word_by_word_translations', resourceId: 59, records: [...translations, ...extraTranslations] },
    wordResource('word_by_word_transliterations', 60, qfCount, 'tr'),
    {
      resourceGroup: 'word_supplements', resourceId: 1,
      records: supplement ? [{ word_id: 101, text: 'live' }] : [],
    },
  ]
  return normalizeQfMushaf(
    new Map([[verseKey, Array.from({ length: canonicalCount }, () => 'word')]]),
    resources,
    [1],
  )
}

function wordResource(group: string, id: number, count: number, prefix: string): StoredQfResource {
  return {
    resourceGroup: group, resourceId: id,
    records: Array.from({ length: count }, (_, index) => ({
      id: 1_000 + index, word_id: 101 + index, text: `${prefix}${index + 1}`,
    })),
  }
}

function codePoint(position: number) {
  return String.fromCodePoint(0xFC40 + position)
}
