import { describe, expect, it } from 'vitest'
import { parseDictionaryPayload } from '../../../data/dictionary'
import {
  DICTIONARY_PREVIEW_SENSES,
  dictionaryGlosses,
  dictionaryNeedsExpand,
  orderedDictionaryGroups,
  wiktionaryArabicUrl,
} from '../dictionaryText'

describe('parseDictionaryPayload', () => {
  it('reads POS groups', () => {
    const groups = parseDictionaryPayload(
      JSON.stringify([
        { pos: 'verb', glosses: ['to say', 'to tell'] },
        { pos: 'noun', glosses: ['speech'] },
      ]),
    )
    expect(groups).toHaveLength(2)
    expect(groups[0]).toEqual({ pos: 'verb', glosses: ['to say', 'to tell'] })
  })
})

describe('dictionaryGlosses', () => {
  it('puts the preferred QAC POS first', () => {
    const rows = dictionaryGlosses(
      {
        lemma: 'قَالَ',
        word: 'قال',
        groups: [
          { pos: 'noun', glosses: ['speech'] },
          { pos: 'verb', glosses: ['to say'] },
        ],
        credit: 'credit',
      },
      'V',
    )
    expect(rows[0]).toEqual({ pos: 'Verb', gloss: 'to say' })
  })

  it('keeps source order when QAC POS is unknown', () => {
    const groups = orderedDictionaryGroups(
      [
        { pos: 'noun', glosses: ['speech'] },
        { pos: 'verb', glosses: ['to say'] },
      ],
      'XYZ',
    )
    expect(groups.map((g) => g.pos)).toEqual(['noun', 'verb'])
  })
})

describe('dictionary preview', () => {
  it('expands past the preview budget', () => {
    expect(dictionaryNeedsExpand(DICTIONARY_PREVIEW_SENSES)).toBe(false)
    expect(dictionaryNeedsExpand(DICTIONARY_PREVIEW_SENSES + 1)).toBe(true)
  })
})

describe('wiktionaryArabicUrl', () => {
  it('points at the Arabic section', () => {
    expect(wiktionaryArabicUrl('كتاب').endsWith('#Arabic')).toBe(true)
  })
})
