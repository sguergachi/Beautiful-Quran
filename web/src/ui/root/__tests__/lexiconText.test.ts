import { describe, expect, it } from 'vitest'
import {
  LEXICON_PREVIEW_CHARS,
  isLaneCitation,
  lexiconBlocks,
  lexiconFormCount,
  lexiconPreview,
  lexiconReflow,
  lexiconRootSense,
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
    // Arabic target stays a mushaf run — not swallowed into the Latin citation.
    expect(runs.some((run) => run.isArabic && run.text.includes('نَارَ'))).toBe(true)
    expect(runs.some((run) => run.isCitation && run.text.includes('نَارَ'))).toBe(false)
  })

  it('does not swallow Arabic after see into the citation', () => {
    const runs = lexiconRuns('قِ عَلَى ظَلْعِكَ: see ظَلَعَ.')
    expect(runs.some((run) => run.isCitation && run.text.trim() === 'see')).toBe(true)
    expect(runs.some((run) => run.isArabic && run.text.includes('ظَلَعَ'))).toBe(true)
    expect(runs.some((run) => run.isCitation && run.text.includes('ظَلَعَ'))).toBe(false)
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

describe('lexiconFormCount', () => {
  it('counts Lane Form labels', () => {
    expect(lexiconFormCount('')).toBe(0)
    expect(lexiconFormCount('Form 1. He wrote it.')).toBe(1)
    expect(lexiconFormCount('Form 1. He wrote it.\n\nForm 2. He made him write.')).toBe(2)
  })
})

describe('lexiconRootSense', () => {
  it('takes Lane Form 1 English lead', () => {
    expect(
      lexiconRootSense(
        'Form 1. كَتَبَهُ, aor. كَتُبَ, inf. n. كَتْبٌ (S, K) He wrote it: (S, K:) ' +
          'or كَتَبَهُ has this signification.\n• And he prescribed it.',
      ),
    ).toBe('He wrote it')

    expect(
      lexiconRootSense(
        'Form 1. رَحِمَهُ, (S, K,) aor. رَحَمَ, (K,) inf. n. رَحْمَةٌ, ' +
          '(S, * Msb, K, *) [He had mercy, or pity, or compassion, on him; ' +
          'or he treated him with mercy:] said of a man.',
      ),
    ).toBe('He had mercy, or pity, or compassion, on him')

    const alighted = lexiconRootSense(
      'Form 1. نَزَلَ بِالمَكَانِ (Kull) He alighted, descended and stopped ' +
        'or sojourned, in the place; syn. حَلَّ فِيهِ. (Kull.)\n' +
        '• نَزَلَ لَبَنُ الشَّاةِ [The milk of the ewe descended into her udder].',
    )
    expect(alighted?.startsWith('He alighted')).toBe(true)
    expect(alighted?.includes('milk')).toBe(false)
  })

  it('cuts before Lane citation after the gloss', () => {
    expect(
      lexiconRootSense(
        "Form 1. جَنَّهُ, (S, K,) It veiled, concealed, hid, covered, or protected, him; " +
          '(S, Mgh, K;) said of the night; (S, K;) as also جَنَّ عَلَيْهِ.',
      ),
    ).toBe('It veiled, concealed, hid, covered, or protected, him')
  })

  it('skips editorial brackets and later forms', () => {
    const said = lexiconRootSense(
      'Form 1. قَالَ. The objective complement of قال, meaning He said, ' +
        'must be a complete proposition. (Gr.) [This is what is meant where] ' +
        'it is said elsewhere.\n\nForm 2. قَوَّلَهُ He made him say.',
    )
    expect(said?.startsWith('The objective complement')).toBe(true)
    expect(said?.includes('This is what')).toBe(false)
    expect(said?.includes('made him say')).toBe(false)
  })

  it('follows Form 1 see-redirect to the real gloss', () => {
    // نور-shaped: Form 1 only points at أَنَارَ; Form 2 also redirects;
    // Form 4 carries "gave light". Must not use the tropical Form-1 aside.
    const light = lexiconRootSense(
      'Form 1. نَارَ intrans., in the sense of أَنَارَ: see the latter, in two places.\n\n' +
        'نُرْتُ البَعِيرَ (tropical:) I made a mark upon the camel with a hot iron. (M, K.)\n\n' +
        'Form 2. نوّر, intrans., in the sense of أَنَارَ: see 4, in two places.\n' +
        '• نوّر بِالفَجْرِ He performed the prayer of daybreak when the dawn had become light.\n\n' +
        'Form 4. انار, (inf. n. إِنَارَةٌ, Msb,) It (a thing) (S, Msb) gave light; ' +
        'or shone; or shone brightly; (S, A, Msb, K.)',
    )
    expect(light).toContain('gave light')
    expect(light).not.toContain('camel')
    expect(light).not.toContain('prayer')
  })
})
