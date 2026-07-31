import { describe, expect, it } from 'vitest'
import { punctuateEnglishGlosses } from '../EnglishTypography'

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
})
