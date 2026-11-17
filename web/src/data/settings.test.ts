import { describe, expect, it } from 'vitest'
import {
  applyReadingMode,
  readingAppearanceKey,
  customizeSummary,
  showsPreviewWordGloss,
  showsWordGlossChrome,
  wordSearchSources,
} from './customizePolicy'
import { normalizeSettings, type Settings } from './settings'

describe('favorite reciters', () => {
  it('keeps page pictures across listening updates and invalidates visual changes', () => {
    const key = readingAppearanceKey(normalizeSettings())
    expect(readingAppearanceKey(normalizeSettings({ lastSurah: 2, lastAyah: 255 }))).toBe(key)
    expect(readingAppearanceKey(normalizeSettings({ themeMode: 'dark' }))).not.toBe(key)
    expect(readingAppearanceKey(normalizeSettings({ mushafGlyphWiden: 8 }))).not.toBe(key)
  })

  it('persists only the last-heard place, dropping the retired reading place', () => {
    const settings = normalizeSettings({ lastSurah: 1, lastAyah: 5, pagePresentation: 'single' })
    expect([settings.lastSurah, settings.lastAyah]).toEqual([1, 5])
    const legacy = normalizeSettings(
      { lastSurah: 2, lastAyah: 255, lastReadSurah: 1, lastReadAyah: 7 } as Parameters<typeof normalizeSettings>[0],
    )
    expect([legacy.lastSurah, legacy.lastAyah]).toEqual([2, 255])
    expect(legacy).not.toHaveProperty('lastReadSurah')
    expect(legacy).not.toHaveProperty('lastReadAyah')
    expect(normalizeSettings({ pagePresentation: 'broken' as never }).pagePresentation).toBe('facing')
  })

  it('migrates existing settings to Android\'s original seven without changing the selected voice', () => {
    const settings = normalizeSettings({ reciterId: 9 })
    expect(settings.favoriteReciterIds).toEqual([1, 2, 3, 4, 5, 6, 7])
    expect(settings.reciterId).toBe(9)
  })

  it('keeps a deliberately empty list and favorites outside the original catalog', () => {
    expect(normalizeSettings({ favoriteReciterIds: [] }).favoriteReciterIds).toEqual([])
    expect(normalizeSettings({ favoriteReciterIds: [9, 16, 19] }).favoriteReciterIds).toEqual([9, 16, 19])
  })

  it('removes duplicate and malformed stored IDs, and falls back for a broken list', () => {
    expect(normalizeSettings({ favoriteReciterIds: [7, 7, -1, 0, NaN, 1.5, '9' as never] }).favoriteReciterIds).toEqual([7])
    expect(normalizeSettings({ favoriteReciterIds: '7' as never }).favoriteReciterIds).toEqual([1, 2, 3, 4, 5, 6, 7])
  })

  it('starts with nothing pinned and cleans a stored pinned list the same way', () => {
    expect(normalizeSettings().pinnedSurahIds).toEqual([])
    expect(normalizeSettings({ pinnedSurahIds: [36, 36, 0, -2, 1.5, '18' as never, 18] }).pinnedSurahIds).toEqual([36, 18])
    expect(normalizeSettings({ pinnedSurahIds: '18' as never }).pinnedSurahIds).toEqual([])
  })

  it('gives each settings snapshot its own favorites array', () => {
    const settings = normalizeSettings()
    settings.favoriteReciterIds.length = 0
    expect(normalizeSettings().favoriteReciterIds).toHaveLength(7)
  })
})

describe('home bookmark settings', () => {
  it('drops the retired Chapters bookmark experiment on load', () => {
    const legacy = normalizeSettings({ homeBookmarkStyle: 'saved_passages' } as Parameters<typeof normalizeSettings>[0])
    expect(legacy).not.toHaveProperty('homeBookmarkStyle')
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

describe('retired colorSystem setting', () => {
  it('is stripped from stored settings', () => {
    const stored = { colorSystem: 'legacy' } as unknown as Partial<Settings>
    expect('colorSystem' in normalizeSettings(stored)).toBe(false)
  })
})

describe('educationGuidesEnabled setting', () => {
  it('is off by default, as on Android', () => {
    expect(normalizeSettings().educationGuidesEnabled).toBe(false)
  })

  it('is a developer toggle: a stored true only counts in developer mode', () => {
    expect(normalizeSettings({ educationGuidesEnabled: true }).educationGuidesEnabled).toBe(false)
    expect(
      normalizeSettings({ developerMode: true, educationGuidesEnabled: true }).educationGuidesEnabled,
    ).toBe(true)
    expect(
      normalizeSettings({ developerMode: true, educationGuidesEnabled: false }).educationGuidesEnabled,
    ).toBe(false)
    expect(
      normalizeSettings({ developerMode: true, educationGuidesEnabled: 1 as unknown as boolean })
        .educationGuidesEnabled,
    ).toBe(true)
  })
})

describe('fontScale setting', () => {
  it('defaults to the 1.0 stop and lifts the retired 0.95 default onto it', () => {
    expect(normalizeSettings().fontScale).toBe(1)
    expect(normalizeSettings({ fontScale: 0.95 }).fontScale).toBe(1)
    expect(normalizeSettings({ fontScale: 0.9 }).fontScale).toBe(0.9)
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
    expect(wordSearchSources('mushaf')).toEqual({
      arabic: false,
      wordGloss: false,
      transliteration: false,
      verseTranslation: true,
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
