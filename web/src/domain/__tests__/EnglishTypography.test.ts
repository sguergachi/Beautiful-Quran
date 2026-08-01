import { describe, expect, it } from 'vitest'
import { lyricizeEnglishGlosses, punctuateEnglishGlosses } from '../EnglishTypography'

describe('punctuateEnglishGlosses', () => {
  it('adds a stop only at the ayah end', () => {
    expect(punctuateEnglishGlosses(['and killed', 'Dawood', 'Jalut', 'And if not']))
      .toEqual(['and killed', 'Dawood', 'Jalut', 'And if not.'])
  })

  it('does not infer internal sentence boundaries from capitals', () => {
    expect(punctuateEnglishGlosses(['that which', 'He willed', 'And if not', 'the worlds']))
      .toEqual(['that which', 'He willed', 'And if not', 'the worlds.'])
  })

  it('preserves existing punctuation', () => {
    expect(punctuateEnglishGlosses(['Why?', 'Then', 'listen!']))
      .toEqual(['Why?', 'Then', 'listen!'])
  })

  it('coalesces a shared phrase spanning different Arabic words', () => {
    expect(lyricizeEnglishGlosses(
      ['guide', 'the wrongdoing people', 'the wrongdoing people'],
      ['يَهۡدِي', 'ٱلۡقَوۡمَ', 'ٱلظَّـٰلِمِينَ'],
    )).toEqual(['guide', 'the wrongdoing people.', ''])
  })

  it('keeps a genuine repeated Arabic word', () => {
    expect(lyricizeEnglishGlosses(
      ['a saying', 'Peace', 'Peace'],
      ['قِيلٰا', 'سَلَـٰمٰا', 'سَلَـٰمٰا'],
    )).toEqual(['a saying', 'Peace', 'Peace.'])
  })
})
