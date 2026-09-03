import { describe, expect, it } from 'vitest'
import {
  applyReadingMode,
  customizeSummary,
  showsPreviewWordGloss,
  showsWordGlossChrome,
  wordSearchSources,
} from './customizePolicy'
import { HOME_BOOKMARK_STYLES, normalizeSettings } from './settings'

describe('home bookmark settings', () => {
  it('defaults removed or absent values to the top-bound ribbon', () => {
    expect(normalizeSettings().homeBookmarkStyle).toBe('top_bound')
    expect(
      normalizeSettings({ homeBookmarkStyle: 'removed_style' as never })
        .homeBookmarkStyle,
    ).toBe('top_bound')
  })

  it('preserves every experiment and keeps it independent of developer mode', () => {
    for (const homeBookmarkStyle of HOME_BOOKMARK_STYLES) {
      expect(
        normalizeSettings({ homeBookmarkStyle, developerMode: false })
          .homeBookmarkStyle,
      ).toBe(homeBookmarkStyle)
    }
  })
})

describe('gapless5Playback setting', () => {
  it('defaults on and coerces to boolean', () => {
    expect(normalizeSettings().gapless5Playback).toBe(true)
    expect(normalizeSettings({ gapless5Playback: false }).gapless5Playback).toBe(false)
    expect(normalizeSettings({ gapless5Playback: true }).gapless5Playback).toBe(true)
    expect(
      normalizeSettings({ gapless5Playback: 1 as unknown as boolean }).gapless5Playback,
    ).toBe(true)
  })
})

describe('educationGuidesEnabled setting', () => {
  it('defaults off and coerces to boolean', () => {
    expect(normalizeSettings().educationGuidesEnabled).toBe(false)
    expect(
      normalizeSettings({ educationGuidesEnabled: true }).educationGuidesEnabled,
    ).toBe(true)
    expect(
      normalizeSettings({ educationGuidesEnabled: 1 as unknown as boolean })
        .educationGuidesEnabled,
    ).toBe(true)
  })
})

describe('customize reading settings', () => {
  it('defaults to bilingual reading with Arabic verse marks and both folios', () => {
    const settings = normalizeSettings()
    expect(settings.readingMode).toBe('arabic_english')
    expect(settings.verseNumberScript).toBe('arabic')
    expect(settings.pageNumberScript).toBe('both')
  })

  it('falls back when stored customize enums are unknown', () => {
    expect(
      normalizeSettings({ verseNumberScript: 'roman' as never }).verseNumberScript,
    ).toBe('arabic')
    expect(
      normalizeSettings({ pageNumberScript: 'none' as never }).pageNumberScript,
    ).toBe('both')
  })

  it('offers word gloss only on bilingual scroll', () => {
    expect(showsWordGlossChrome('arabic_english')).toBe(true)
    expect(showsWordGlossChrome('arabic_only')).toBe(false)
    expect(showsPreviewWordGloss('arabic_english', true)).toBe(true)
    expect(showsPreviewWordGloss('arabic_english', false)).toBe(false)
  })

  it('searches only the selected reader text', () => {
    expect(wordSearchSources()).toEqual({
      arabic: false,
      wordGloss: true,
      transliteration: false,
      verseTranslation: false,
    })
  })

  it('applies every web reading mode', () => {
    expect(applyReadingMode('english_only')).toEqual({
      readingMode: 'english_only',
    })
    expect(applyReadingMode('arabic_english')).toEqual({
      readingMode: 'arabic_english',
    })
  })

  it('summarises view and verse-mark script', () => {
    expect(customizeSummary(normalizeSettings())).toBe(
      'Arabic & English · Arabic verse marks · System',
    )
    expect(
      customizeSummary(
        normalizeSettings({
          readingMode: 'arabic_only',
          verseNumberScript: 'english',
          themeMode: 'dark',
        }),
      ),
    ).toBe('Arabic · English verse marks · Nightfall')
  })
})
