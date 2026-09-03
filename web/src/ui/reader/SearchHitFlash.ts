/** Timing for the reader's orange search-hit wash; mirrors Android. */
export const SearchHitFlash = {
  START_DELAY_MS: 140,
  SWEEP_MS: 240,
  FADE_OUT_MS: 280,
  PULSES: 4,
} as const

export function searchHitFlashCycleMs(): number {
  return SearchHitFlash.SWEEP_MS + SearchHitFlash.FADE_OUT_MS
}

export function searchHitFlashTotalMs(): number {
  return SearchHitFlash.PULSES * searchHitFlashCycleMs()
}
