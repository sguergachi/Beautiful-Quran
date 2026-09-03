import { describe, expect, it } from 'vitest'
import {
  SearchHitFlash,
  searchHitWipeMs,
  searchHitFlashTotalMs,
} from '../SearchHitFlash'

describe('SearchHitFlash', () => {
  it('runs five distinct side wipes', () => {
    expect(SearchHitFlash.WIPES).toBe(5)
    expect(searchHitWipeMs()).toBe(480)
    expect(searchHitFlashTotalMs()).toBe(2720)
    expect(SearchHitFlash.REST_MS).toBeGreaterThanOrEqual(60)
    expect(SearchHitFlash.FEATHER).toBeGreaterThanOrEqual(0.2)
    expect(SearchHitFlash.FEATHER).toBeLessThanOrEqual(0.4)
    expect(searchHitFlashTotalMs()).toBeLessThan(3000)
  })
})
