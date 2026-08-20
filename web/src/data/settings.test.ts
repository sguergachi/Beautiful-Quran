import { describe, expect, it } from 'vitest'
import {
  applyReadingLayout,
  applyReadingMode,
  customizeSummary,
  showsPreviewAyahRail,
  showsPreviewWordGloss,
  showsScrollChrome,
  showsWordGlossChrome,
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
  it('defaults to bilingual scroll with Arabic verse marks and both folios', () => {
    const settings = normalizeSettings()
    expect(settings.readingMode).toBe('arabic_english')
    expect(settings.readingLayout).toBe('scroll')
    expect(settings.verseNumberScript).toBe('arabic')
    expect(settings.pageNumberScript).toBe('both')
  })

  it('falls back when stored customize enums are unknown', () => {
    expect(
      normalizeSettings({ readingLayout: 'codex' as never }).readingLayout,
    ).toBe('scroll')
    expect(
      normalizeSettings({ verseNumberScript: 'roman' as never }).verseNumberScript,
    ).toBe('arabic')
    expect(
      normalizeSettings({ pageNumberScript: 'none' as never }).pageNumberScript,
    ).toBe('both')
  })

  it('forces Arabic-only when mushaf is chosen', () => {
    const next = applyReadingLayout(
      normalizeSettings({ readingMode: 'english_only' }),
      'mushaf',
    )
    expect(next).toEqual({ readingLayout: 'mushaf', readingMode: 'arabic_only' })
  })

  it('hides annotation and ayah-rail chrome on mushaf', () => {
    expect(showsScrollChrome('scroll')).toBe(true)
    expect(showsScrollChrome('mushaf')).toBe(false)
    expect(showsPreviewAyahRail('scroll')).toBe(true)
    expect(showsPreviewAyahRail('mushaf')).toBe(false)
  })

  it('offers word gloss only on bilingual scroll', () => {
    expect(showsWordGlossChrome('scroll', 'arabic_english')).toBe(true)
    expect(showsWordGlossChrome('scroll', 'arabic_only')).toBe(false)
    expect(showsWordGlossChrome('mushaf', 'arabic_english')).toBe(false)
    expect(showsPreviewWordGloss('scroll', 'arabic_english', true)).toBe(true)
    expect(showsPreviewWordGloss('scroll', 'arabic_english', false)).toBe(false)
  })

  it('ignores view-mode changes while mushaf is on', () => {
    const mushaf = normalizeSettings({
      readingLayout: 'mushaf',
      readingMode: 'arabic_only',
    })
    expect(applyReadingMode(mushaf, 'english_only')).toEqual({})
    expect(applyReadingMode(mushaf, 'arabic_english')).toEqual({})
  })

  it('summarises layout and verse-mark script', () => {
    expect(customizeSummary(normalizeSettings())).toBe(
      'Arabic & English · Arabic verse marks · System',
    )
    expect(
      customizeSummary(
        normalizeSettings({
          readingLayout: 'mushaf',
          readingMode: 'arabic_only',
          verseNumberScript: 'english',
          themeMode: 'dark',
        }),
      ),
    ).toBe('Mushaf · Nightfall')
  })
})
