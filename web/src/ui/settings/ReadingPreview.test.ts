import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { normalizeSettings, type Settings } from '../../data/settings'
import { ReadingPreview } from './ReadingPreview'

const render = (patch: Partial<Settings>) => renderToStaticMarkup(createElement(ReadingPreview, { settings: normalizeSettings(patch) }))

describe('Customize reading preview', () => {
  it('scales Scroll ink and respects all optional text lines', () => {
    const sample = render({ fontScale: 1.2, showTranslation: true, showTransliteration: true, showWordGloss: false })
    expect(sample).toContain('--preview-scale:1.2')
    expect(sample).toContain('reading-preview__translit')
    expect(sample).toContain('reading-preview__english')
    expect(sample).not.toContain('reading-preview__gloss')
    expect(render({ showTranslation: false, showTransliteration: false })).not.toContain('reading-preview__english')
  })

  it('shows printed paper without Scroll scaling, rail or gloss', () => {
    const sample = render({ readingLayout: 'mushaf', readingMode: 'arabic_only', fontScale: 1.6 })
    expect(sample).toContain('data-layout="mushaf"')
    expect(sample).toContain('--preview-scale:1')
    expect(sample).toContain('reading-preview__head')
    expect(sample).not.toContain('class="reading-preview__rail ')
    expect(sample).not.toContain('reading-preview__gloss')
  })
})
