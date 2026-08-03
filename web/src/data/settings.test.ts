import { describe, expect, it } from 'vitest'
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
