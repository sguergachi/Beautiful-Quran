import { describe, expect, it } from 'vitest'
import {
  BOOKMARKS_LAYER,
  COVER_LAYER,
  READER_LAYER,
  SETTINGS_LAYER,
  backDestination,
  hasReaderOpen,
  nextSettingsReturn,
  settingsLayerFor,
  sheetAtLayer,
  showPinnedChapterBar,
} from '../stack'

describe('hasReaderOpen', () => {
  it('maps the left-hand layer to the bookmark sheet', () => {
    expect(sheetAtLayer(BOOKMARKS_LAYER, false)).toBe('bookmarks')
    expect(sheetAtLayer(BOOKMARKS_LAYER, true)).toBe('bookmarks')
  })

  it('is false on home with no content', () => {
    expect(hasReaderOpen(null, 'home')).toBe(false)
  })

  it('is true while an explicit reader state owns the layer before content', () => {
    // Regression: content null + sheet reader must keep Settings off layer 1.
    expect(hasReaderOpen(null, 'reader')).toBe(true)
    expect(settingsLayerFor(true)).toBe(2)
    expect(sheetAtLayer(READER_LAYER, true)).toBe('reader')
  })

  it('is true when content is loaded', () => {
    expect(hasReaderOpen({ surah: { id: 1 } }, 'reader')).toBe(true)
    expect(hasReaderOpen({ surah: { id: 1 } }, 'home')).toBe(true)
  })

  it('pins the scroll bar across the cover and the reader only', () => {
    const base = {
      hasReader: true,
      mushaf: false,
      gathering: false,
      stackLayer: READER_LAYER,
      coverSession: false,
    }
    expect(showPinnedChapterBar(base)).toBe(true)
    expect(showPinnedChapterBar({ ...base, stackLayer: COVER_LAYER, coverSession: true })).toBe(true)
    expect(showPinnedChapterBar({ ...base, stackLayer: COVER_LAYER, coverSession: false })).toBe(false)
    expect(showPinnedChapterBar({ ...base, mushaf: true })).toBe(false)
    expect(showPinnedChapterBar({ ...base, gathering: true })).toBe(false)
    expect(showPinnedChapterBar({ ...base, stackLayer: SETTINGS_LAYER })).toBe(false)
    expect(showPinnedChapterBar({ ...base, stackLayer: BOOKMARKS_LAYER })).toBe(false)
    expect(showPinnedChapterBar({ ...base, hasReader: false })).toBe(false)
  })

  it('would flash settings if peel used content-only hasReader', () => {
    // Documents the bug: content-null peel with hasReader=false maps layer 1 → settings.
    expect(sheetAtLayer(READER_LAYER, false)).toBe('settings')
    expect(settingsLayerFor(false)).toBe(READER_LAYER)
  })
})

describe('settings origin and back', () => {
  it('records where Settings was opened from and forgets it on the way out', () => {
    expect(nextSettingsReturn(COVER_LAYER, SETTINGS_LAYER, true, null)).toBe(COVER_LAYER)
    expect(nextSettingsReturn(READER_LAYER, SETTINGS_LAYER, true, null)).toBe(READER_LAYER)
    expect(nextSettingsReturn(BOOKMARKS_LAYER, SETTINGS_LAYER, true, null)).toBe(BOOKMARKS_LAYER)
    // Staying on Settings (sub-pages, setting changes) keeps the origin.
    expect(nextSettingsReturn(SETTINGS_LAYER, SETTINGS_LAYER, true, COVER_LAYER)).toBe(COVER_LAYER)
    // Leaving Settings forgets it.
    expect(nextSettingsReturn(SETTINGS_LAYER, READER_LAYER, true, COVER_LAYER)).toBe(null)
    expect(nextSettingsReturn(SETTINGS_LAYER, COVER_LAYER, true, COVER_LAYER)).toBe(null)
    // Other moves never record.
    expect(nextSettingsReturn(COVER_LAYER, READER_LAYER, true, null)).toBe(null)
    // Without a reader, Settings owns layer 1.
    expect(nextSettingsReturn(COVER_LAYER, READER_LAYER, false, null)).toBe(COVER_LAYER)
  })

  it('returns Back from Settings to its origin sheet', () => {
    expect(backDestination(SETTINGS_LAYER, true, COVER_LAYER)).toBe(COVER_LAYER)
    expect(backDestination(SETTINGS_LAYER, true, READER_LAYER)).toBe(READER_LAYER)
    expect(backDestination(SETTINGS_LAYER, true, BOOKMARKS_LAYER)).toBe(BOOKMARKS_LAYER)
    // No recorded origin peels one layer, as before.
    expect(backDestination(SETTINGS_LAYER, true, null)).toBe(READER_LAYER)
    expect(backDestination(READER_LAYER, true, null)).toBe(COVER_LAYER)
    expect(backDestination(BOOKMARKS_LAYER, true, null)).toBe(COVER_LAYER)
    expect(backDestination(READER_LAYER, false, null)).toBe(COVER_LAYER)
  })
})
