import { describe, expect, it } from 'vitest'
import {
  SearchHitFlash,
  searchHitFlashCycleMs,
  searchHitFlashTotalMs,
} from '../SearchHitFlash'

describe('SearchHitFlash', () => {
  it('runs four quick pulses faster than the old double wash', () => {
    const cycle = searchHitFlashCycleMs()
    expect(SearchHitFlash.PULSES).toBe(4)
    expect(cycle).toBe(520)
    expect(searchHitFlashTotalMs()).toBe(cycle * SearchHitFlash.PULSES)
    expect(searchHitFlashTotalMs()).toBeLessThan(2700)
  })
})
