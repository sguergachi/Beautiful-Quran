import { describe, expect, it } from 'vitest'
import {
  SearchHitFlash,
  searchHitTextRanges,
  searchHitWipeMs,
  searchHitFlashTotalMs,
} from '../SearchHitFlash'

describe('SearchHitFlash', () => {
  it('runs four complete traveling wipes as one continuous loop', () => {
    expect(SearchHitFlash.WIPES).toBe(4)
    expect(searchHitWipeMs()).toBe(720)
    expect(searchHitFlashTotalMs()).toBe(2880)
    expect(SearchHitFlash.BAND_FRACTION).toBeGreaterThanOrEqual(0.6)
    expect(SearchHitFlash.BAND_FRACTION).toBeLessThanOrEqual(0.8)
    expect(SearchHitFlash.EDGE_SHARE).toBeGreaterThanOrEqual(0.15)
    expect(SearchHitFlash.EDGE_SHARE).toBeLessThanOrEqual(0.3)
    expect(SearchHitFlash.BACKGROUND_ALPHA).toBe(0.4)
    expect(SearchHitFlash.FOCUS_FADE_MS).toBe(280)
    expect(searchHitFlashTotalMs()).toBeLessThan(3000)
  })

  it('expands a translator-only prefix to the complete visible word', () => {
    expect(searchHitTextRanges('a companion [in Hellfire]', 'hell')).toEqual([[16, 24]])
    expect(searchHitTextRanges('a companion [in Hellfire]', '"Hell"')).toEqual([[16, 24]])
  })
})
