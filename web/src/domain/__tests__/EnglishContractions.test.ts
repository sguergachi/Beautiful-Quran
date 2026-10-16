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
        const partial = `Before ${visible} love after`
        expect(searchTextRelevance(partial, parseSearchQuery(`${typed} lov`), false)).toBe(2_200)
        expect(englishTranslationHighlightSpans(partial, `${typed} lov`, '').filter((s) => s.highlighted)
          .map((s) => s.text).join('')).toBe(`${visible} love`)
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
    ['(does) not love', "doesn't"], ["doesn't love", 'does not'],
  ])('keeps final prefixes through %s matching %s', (text, query) => {
    for (const prefix of ['l', 'lo', 'lov']) {
      const typed = `${query} ${prefix}`
      expect(searchTextRelevance(text, parseSearchQuery(typed), false)).toBe(2_200)
      expect(englishTranslationHighlightSpans(text, typed, '').filter((s) => s.highlighted)
        .map((s) => s.text).join('')).toBe(text)
      expect(searchTextRelevance(text, parseSearchQuery(`"${typed}"`), false)).toBe(0)
    }
  })

  it.each([
    ["doesn't love", 'does not love'], ["he's merciful", 'he is merciful'], ["'tis good", 'it is good'],
  ])('handles surrounding single quotes on %s', (source, query) => {
    for (const [open, close] of [["'", "'"], ['‘', '’']]) {
      const text = `He said ${open}${source}${close}`
      expect(searchTextRelevance(text, parseSearchQuery(query), false)).toBe(3_000)
      expect(englishTranslationHighlightSpans(text, query, '').filter((s) => s.highlighted)
        .map((s) => s.text).join('')).toBe(source)
    }
  })

  it.each([
    ["Allah's mercy", 'Allah is mercy'], ['his mercy', "he's mercy"],
    ['does love', "doesn't love"],
    ['does nothing', "doesn't"], ['can nothing', "can't"],
    ['he island', "he's"], ['would nothing have', "wouldn't've"],
    ['undoes not love', "doesn't love"], ['he has mercy', 'he is mercy'],
    ['does not love', '"doesn\'t love"'], ["doesn't love", '"does not love"'],
  ])('keeps %s distinct from %s', (text, query) => {
    expect(searchTextRelevance(text, parseSearchQuery(query), false)).toBe(0)
    expect(englishTranslationHighlightSpans(text, query, '').some((s) => s.highlighted)).toBe(false)
  })
})
