import { describe, expect, it } from 'vitest'
import { pickLemmaGloss } from './lemmaGloss'

describe('pickLemmaGloss', () => {
  it('pools renderings that differ only by article or aside', () => {
    // كِتَٰب — 'the Book' wins once its variants vote together.
    expect(
      pickLemmaGloss([
        { translation: '(of) the Book', count: 43 },
        { translation: 'the Book', count: 93 },
        { translation: 'the Scripture', count: 14 },
        { translation: 'a Book', count: 9 },
        { translation: '(the) Book', count: 7 },
      ]),
    ).toBe('Book')
  })

  it('strips a leading conjunction and a trailing object', () => {
    expect(pickLemmaGloss([{ translation: 'and mercy', count: 3 }])).toBe('mercy')
    expect(pickLemmaGloss([{ translation: 'show mercy upon them', count: 3 }])).toBe('show mercy')
  })

  it('keeps a rendering that is nothing but framing words', () => {
    // كان: trimming 'is' to nothing would hand the lemma to a rare form.
    expect(
      pickLemmaGloss([
        { translation: 'is', count: 146 },
        { translation: 'And is', count: 37 },
        { translation: 'they used (to)', count: 29 },
      ]),
    ).toBe('is')
  })

  it('prefers the most used rendering inside the winning meaning', () => {
    expect(
      pickLemmaGloss([
        { translation: 'the All-Knower', count: 17 },
        { translation: 'All-Knowing', count: 26 },
        { translation: '(is) All-Knower', count: 26 },
        { translation: 'learned', count: 7 },
      ]),
    ).toBe('All-Knower')
  })

  it('resolves ties alphabetically so the gloss never flickers', () => {
    const votes = [
      { translation: 'wombs', count: 4 },
      { translation: 'kinship', count: 4 },
    ]

    expect(pickLemmaGloss(votes)).toBe('kinship')
    expect(pickLemmaGloss([...votes].reverse())).toBe('kinship')
  })

  it('returns nothing when no rendering survives', () => {
    expect(pickLemmaGloss([])).toBe('')
    expect(
      pickLemmaGloss([
        { translation: '(unused)', count: 2 },
        { translation: '  ', count: 1 },
      ]),
    ).toBe('')
  })
})
