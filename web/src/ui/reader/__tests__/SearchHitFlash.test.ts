import { describe, expect, it } from 'vitest'
import {
  SearchHitFlash,
  searchHitFlashCycleMs,
  searchHitFlashTotalMs,
} from '../SearchHitFlash'

describe('SearchHitFlash', () => {
  it('runs four eased breaths in a compact locator rhythm', () => {
    const cycle = searchHitFlashCycleMs()
    expect(SearchHitFlash.PULSES).toBe(4)
    expect(cycle).toBe(620)
    expect(searchHitFlashTotalMs()).toBe(2600)
    expect(searchHitFlashTotalMs()).toBeLessThan(2700)
  })
})
