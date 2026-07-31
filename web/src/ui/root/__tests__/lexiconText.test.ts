import { describe, expect, it } from 'vitest'
import {
  LEXICON_PREVIEW_CHARS,
  isLaneCitation,
  lexiconBlocks,
  lexiconPreview,
  lexiconReflow,
  lexiconRuns,
} from '../lexiconText'

describe('lexiconRuns', () => {
  it("splits Lane's mixed prose into script runs", () => {
    const runs = lexiconRuns('inf. n. كِتَابٌ and كِتَابَةٌ (S, K)')

    expect(runs.map((run) => run.isArabic)).toEqual([false, true, false, true, false])
    expect(runs[0].text).toBe('inf. n. ')
    expect(runs[1].text.startsWith('كِتَابٌ')).toBe(true)
    expect(runs.at(-1)).toEqual({ text: '(S, K)', isArabic: false, isCitation: true })
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

describe('isLaneCitation', () => {
  it('marks source abbreviations but not English asides', () => {
    expect(isLaneCitation('S, K')).toBe(true)
    expect(isLaneCitation('Msb,')).toBe(true)
    expect(isLaneCitation('Ksh and Bd in ii. 1:')).toBe(true)
    expect(isLaneCitation('tropical:')).toBe(true)
    expect(isLaneCitation('a thing')).toBe(false)
    expect(isLaneCitation('see رِيبَةٌ;')).toBe(false)
  })

  it('quiets see cross-references', () => {
    const runs = lexiconRuns('I marked it. (M, K.) See نَارَ.')
    expect(runs.some((run) => run.isCitation && run.text.startsWith('See'))).toBe(true)
  })
})

describe('lexiconReflow / lexiconBlocks', () => {
  it('breaks gloss after an Arabic headword', () => {
    expect(lexiconReflow('Form 3. نَازَلَهُ He alighted with him.').includes('\n\nHe alighted')).toBe(
      true,
    )
  })

  it('puts Form on its own line and breaks morph from gloss', () => {
    const dense =
      'Form 1. رَابَنِى, (T, S, M, &c.,) aor. يَرِيبُ, (M, Msb,) ' +
      'inf. n. رَيْبٌ (T, M,) It (a thing) occasioned in me disquiet.'

    const reflowed = lexiconReflow(dense)

    expect(reflowed.startsWith('Form 1.\n')).toBe(true)
    expect(reflowed.includes('\n\nIt (a thing) occasioned')).toBe(true)
    expect(reflowed.includes('M,) It')).toBe(false)
  })

  it('exposes Form headings and spaced senses', () => {
    const article =
      'Form 1. كَتَبَهُ, (S,) aor. كَتُبَ, (K,) He wrote it.\n• And he prescribed it.'

    const blocks = lexiconBlocks(lexiconReflow(article))

    expect(blocks).toHaveLength(3)
    expect(blocks[0]?.form).toBe('Form 1.')
    expect(blocks[0]?.text.includes('كَتَبَهُ')).toBe(true)
    expect(blocks[1]?.text.startsWith('He wrote it.')).toBe(true)
    expect(blocks[2]?.text.startsWith('And he prescribed')).toBe(true)
    expect(blocks[2]?.text.startsWith('•')).toBe(false)
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
