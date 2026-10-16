import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { englishTranslationHighlightSpans, parseSearchQuery, searchTextRelevance } from '../WordSearch'

const cases = readFileSync(new URL('../../../../tools/testdata/search_contractions.tsv', import.meta.url), 'utf8')
  .trim().split('\n').map((line) => line.split('\t') as [string, string])

describe('contraction search', () => {
  it.each(cases)('%s matches %s in both directions and apostrophe styles', (contracted, expanded) => {
    for (const [source, query] of [[contracted, expanded], [expanded, contracted]] as const) {
      for (const mark of ["'", '’']) {
        const visible = source.replaceAll("'", mark)
        const text = `Before ${visible} after`
        const typed = query.replaceAll("'", mark)
        expect(searchTextRelevance(text, parseSearchQuery(typed), false)).toBeGreaterThan(0)
        const spans = englishTranslationHighlightSpans(text, typed, '')
        expect(spans.filter((s) => s.highlighted).map((s) => s.text).join('')).toBe(visible)
        expect(spans.map((s) => s.text).join('')).toBe(text)
      }
    }
  })

  it('highlights bracketed and repeated expanded phrases', () => {
    const text = 'Allah (does) not love corruption and does [not] love wrongdoing'
    const spans = englishTranslationHighlightSpans(text, 'doesn’t love', '')
    expect(spans.filter((s) => s.highlighted).map((s) => s.text))
      .toEqual(['(does) not love', 'does [not] love'])
    expect(spans.map((s) => s.text).join('')).toBe(text)
  })

  it.each([
    ["Allah's mercy", 'Allah is mercy'], ['his mercy', "he's mercy"],
    ['does love', "doesn't love"],
    ['undoes not love', "doesn't love"], ['he has mercy', 'he is mercy'],
    ['does not love', '"doesn\'t love"'], ["doesn't love", '"does not love"'],
  ])('keeps %s distinct from %s', (text, query) => {
    expect(searchTextRelevance(text, parseSearchQuery(query), false)).toBe(0)
    expect(englishTranslationHighlightSpans(text, query, '').some((s) => s.highlighted)).toBe(false)
  })
})
