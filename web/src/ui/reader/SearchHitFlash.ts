/** Timing for the reader's orange search-hit wash; mirrors Android. */
export const SearchHitFlash = {
  START_DELAY_MS: 140,
  SWEEP_MS: 240,
  CREST_MS: 80,
  FADE_OUT_MS: 300,
  REST_MS: 40,
  PULSES: 4,
  EASING: [0.37, 0, 0.63, 1] as const,
} as const

export function searchHitFlashCycleMs(): number {
  return SearchHitFlash.SWEEP_MS + SearchHitFlash.CREST_MS + SearchHitFlash.FADE_OUT_MS
}

export function searchHitFlashTotalMs(): number {
  return SearchHitFlash.PULSES * searchHitFlashCycleMs() +
    (SearchHitFlash.PULSES - 1) * SearchHitFlash.REST_MS
}
