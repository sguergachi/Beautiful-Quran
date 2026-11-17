import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { normalizeSettings, type Settings } from '../../data/settings'
import { ReadingPreview } from './ReadingPreview'

const render = (patch: Partial<Settings>) =>
  renderToStaticMarkup(createElement(ReadingPreview, { settings: normalizeSettings(patch) }))

/** What the reader sees: the unseen height-lock copies sit before the body. */
const body = (patch: Partial<Settings>) => {
  const html = render(patch)
  return html.slice(html.indexOf('reading-preview__body'))
}

describe('Customize reading preview', () => {
  it('scales Scroll ink and respects all optional text lines', () => {
    const sample = render({ fontScale: 1.2, showTranslation: true, showTransliteration: true, showWordGloss: false })
    expect(sample).toContain('--preview-scale:1.2')
    const seen = body({ fontScale: 1.2, showTranslation: true, showTransliteration: true, showWordGloss: false })
    expect(seen).toContain('reading-preview__translit')
    expect(seen).toContain('Wa-innahu la-qasam law taʿlamūna ʿaẓīm')
    expect(seen).toContain('reading-preview__english')
    expect(seen).not.toContain('reading-preview__gloss')
    expect(body({ showTranslation: false, showTransliteration: false })).not.toContain('reading-preview__english')
  })

  it('draws the page break in every Scroll view', () => {
    for (const readingMode of ['arabic_english', 'arabic_only', 'english_only'] as const) {
      expect(body({ readingMode })).toContain('Page 536')
    }
    // Arabic alone turns to 56:77 after the break; English sets both verses.
    expect(body({ readingMode: 'arabic_only' })).toContain('إِنَّهُۥ لَقُرۡءَانٞ كَرِيمٞ')
    expect(body({ readingMode: 'english_only' })).toContain('Indeed, it is a noble Qur')
  })

  it('samples three glossed words', () => {
    expect(body({ showWordGloss: true }).match(/reading-preview__gloss/g)).toHaveLength(3)
  })

  it('shows a sample verse note only on a Scroll page with notes on', () => {
    expect(body({ annotationsEnabled: true })).toContain('reading-preview__note')
    expect(body({ annotationsEnabled: false })).not.toContain('reading-preview__note')
    expect(body({ readingLayout: 'mushaf', readingMode: 'arabic_only', annotationsEnabled: true }))
      .not.toContain('reading-preview__note')
  })

  it('keeps one height: every view is laid unseen under the visible one', () => {
    expect(render({}).match(/reading-preview__ghost/g)).toHaveLength(3)
  })

  it('shows printed paper without Scroll scaling, rail or gloss', () => {
    const sample = render({ readingLayout: 'mushaf', readingMode: 'arabic_only', fontScale: 1.6 })
    expect(sample).toContain('data-layout="mushaf"')
    expect(sample).toContain('--preview-scale:1')
    expect(sample).not.toContain('class="reading-preview__rail ')
    const seen = body({ readingLayout: 'mushaf', readingMode: 'arabic_only' })
    expect(seen).toContain('Al-Anbya')
    expect(seen).not.toContain('reading-preview__gloss')
  })

  it('sets the English leaf honouring the folio and verse-mark scripts', () => {
    const seen = body({ readingLayout: 'mushaf', readingMode: 'english_only', pageNumberScript: 'english', verseNumberScript: 'english' })
    expect(seen).toContain('reading-preview__leaf-english')
    expect(seen).toContain('>330<')
    expect(seen).not.toContain('٣٣٠')
    expect(seen).not.toContain('٩١')
  })
})
