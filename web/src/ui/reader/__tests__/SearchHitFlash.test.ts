import { describe, expect, it } from 'vitest'
import {
  SearchHitFlash,
  searchHitTextRanges,
  searchHitBreathMs,
  searchHitFlashTotalMs,
} from '../SearchHitFlash'

describe('SearchHitFlash', () => {
  it('fades four complete full-word breaths in and out', () => {
    expect(SearchHitFlash.BREATHS).toBe(4)
    expect(searchHitBreathMs()).toBe(810)
    expect(searchHitFlashTotalMs()).toBe(3420)
    expect(SearchHitFlash.INHALE_MS).toBeLessThan(SearchHitFlash.EXHALE_MS)
    expect(SearchHitFlash.BACKGROUND_ALPHA).toBe(0.4)
    expect(SearchHitFlash.FOCUS_FADE_MS).toBe(280)
    expect(searchHitFlashTotalMs()).toBeLessThan(3500)
  })

  it('expands a translator-only prefix to the complete visible word', () => {
    expect(searchHitTextRanges('a companion [in Hellfire]', 'hell')).toEqual([[16, 24]])
    expect(searchHitTextRanges('a companion [in Hellfire]', '"Hell"')).toEqual([[16, 24]])
  })
})
