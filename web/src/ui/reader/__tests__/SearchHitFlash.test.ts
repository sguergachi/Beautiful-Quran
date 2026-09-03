import { describe, expect, it } from 'vitest'
import {
  SearchHitFlash,
  searchHitWipeMs,
  searchHitFlashTotalMs,
} from '../SearchHitFlash'

describe('SearchHitFlash', () => {
  it('runs six side wipes as one continuous loop', () => {
    expect(SearchHitFlash.WIPES).toBe(6)
    expect(searchHitWipeMs()).toBe(420)
    expect(searchHitFlashTotalMs()).toBe(2520)
    expect(SearchHitFlash.FEATHER).toBeGreaterThanOrEqual(0.2)
    expect(SearchHitFlash.FEATHER).toBeLessThanOrEqual(0.4)
    expect(searchHitFlashTotalMs()).toBeLessThan(3000)
  })
})
