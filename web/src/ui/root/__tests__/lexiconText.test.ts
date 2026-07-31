import { describe, expect, it } from 'vitest'
import { LEXICON_PREVIEW_CHARS, lexiconPreview, lexiconRuns } from '../lexiconText'

describe('lexiconRuns', () => {
  it("splits Lane's mixed prose into script runs", () => {
    const runs = lexiconRuns('inf. n. كِتَابٌ and كِتَابَةٌ (S, K)')

    expect(runs.map((run) => run.isArabic)).toEqual([false, true, false, true, false])
    expect(runs[0].text).toBe('inf. n. ')
    expect(runs[1].text.startsWith('كِتَابٌ')).toBe(true)
  })

  it('keeps neutral punctuation with the run it follows', () => {
    const runs = lexiconRuns('wrote it: (كَتَبَ) then')

    expect(runs).toHaveLength(3)
    expect(runs[0].text).toBe('wrote it: (')
    expect(runs[1].isArabic).toBe(true)
    expect(runs[2].text).toBe('then')
  })

  it('leaves single-script text as one run', () => {
    expect(lexiconRuns('He wrote it.')).toEqual([{ text: 'He wrote it.', isArabic: false }])
    expect(lexiconRuns('كَتَبَ كِتَابًا')).toHaveLength(1)
    expect(lexiconRuns('')).toEqual([])
  })

  it('rejoins to the entry exactly', () => {
    const entry = 'Form 1. كَتَبَهُ , aor. كَتُبَ , inf. n. كَتْبٌ ; (Msb;) ↓ اكتتبهُ (K)'

    expect(lexiconRuns(entry).map((run) => run.text).join('')).toBe(entry)
  })
})

describe('lexiconPreview', () => {
  it('returns a short article whole', () => {
    const short = 'يَدٌ The arm, from the shoulder-joint to the fingers. (Msb.)'

    expect(lexiconPreview(short)).toBe(short)
  })

  it("cuts at one of Lane's own divisions", () => {
    const article = `Form 1. ${'He wrote it. '.repeat(60)}\n• And he prescribed it. ${'More senses follow. '.repeat(60)}`

    const preview = lexiconPreview(article)

    expect(preview.length).toBeLessThan(article.length)
    expect(preview.endsWith('…')).toBe(true)
    expect(article.startsWith(preview.replace(/ …$/, ''))).toBe(true)
    expect(preview.replace(/ …$/, '').endsWith('it.')).toBe(true)
  })

  it('never returns a stub when no break is near', () => {
    expect(lexiconPreview('ا'.repeat(4_000))).toHaveLength(LEXICON_PREVIEW_CHARS + 2)
  })
})
