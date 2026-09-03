import { describe, expect, it } from 'vitest'
import {
  SearchHitFlash,
  searchHitBreathMs,
  searchHitFlashTotalMs,
} from '../SearchHitFlash'

describe('SearchHitFlash', () => {
  it('turns one wash into four full-word breaths', () => {
    expect(SearchHitFlash.PULSES).toBe(4)
    expect(searchHitBreathMs()).toBe(870)
    expect(searchHitFlashTotalMs()).toBe(3340)
    expect(SearchHitFlash.REST_ALPHA).toBe(0)
    expect(searchHitFlashTotalMs()).toBeLessThan(3500)
  })
})
