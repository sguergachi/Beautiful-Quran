import { describe, expect, it } from 'vitest'
import {
  SearchHitFlash,
  searchHitBreathMs,
  searchHitFlashTotalMs,
} from '../SearchHitFlash'

describe('SearchHitFlash', () => {
  it('turns one wash into four full-word breaths', () => {
    expect(SearchHitFlash.PULSES).toBe(4)
    expect(searchHitBreathMs()).toBe(630)
    expect(searchHitFlashTotalMs()).toBe(2660)
    expect(SearchHitFlash.REST_ALPHA).toBeGreaterThanOrEqual(0.25)
    expect(SearchHitFlash.REST_ALPHA).toBeLessThanOrEqual(0.4)
    expect(searchHitFlashTotalMs()).toBeLessThan(2700)
  })
})
