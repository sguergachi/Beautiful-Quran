import { describe, expect, it } from 'vitest'
import { foldEnglish, quoteContinuesInto, typesetEnglish, typesetEnglishName } from '../EnglishTypography'

// Same cases as Android's EnglishTypesetTest: the two must set the same text.
describe('typesetEnglish', () => {
  it('curls quotes and apostrophes and dashes a spaced hyphen', () => {
    expect(typesetEnglish('some who say, "We believe in Allah and the Last Day," but they are not believers'))
      .toBe('some who say, “We believe in Allah and the Last Day,” but they are not believers')
    expect(typesetEnglish('those who disbelieve - it is all the same')).toBe('those who disbelieve – it is all the same')
    expect(typesetEnglish("Allah's Ever-Living")).toBe('Allah’s Ever-Living')
  })

  it('closes the quotations the source left open, inner first', () => {
    expect(typesetEnglish('they say, "We are but reformers')).toBe('they say, “We are but reformers”')
    expect(typesetEnglish('and say, "Say, \'Relieve us')).toBe('and say, “Say, ‘Relieve us’”')
    expect(typesetEnglish('they say, "We are but reformers', true)).toBe('they say, “We are but reformers')
  })

  it('a quotation continues when the next verse opens by closing it', () => {
    expect(quoteContinuesInto('all of them," He said')).toBe(true)
    expect(quoteContinuesInto('He said, "Indeed')).toBe(false)
    expect(quoteContinuesInto(undefined)).toBe(false)
  })

  it('fold restores the stored text character for character', () => {
    const raw = 'And [recall] when We said, "Enter - and say, \'Relieve us.\' We will'
    const set = typesetEnglish(raw, true)
    expect(set.length).toBe(raw.length)
    expect(foldEnglish(set)).toBe(raw)
    expect(typesetEnglishName("Ali 'Imran")).toBe('Ali ’Imran')
  })
})
