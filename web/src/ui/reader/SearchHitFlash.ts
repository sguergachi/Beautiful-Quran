/** Timing for the reader's orange search-hit wash; mirrors Android. */
export const SearchHitFlash = {
  START_DELAY_MS: 140,
  SWEEP_MS: 360,
  RELEASE_MS: 120,
  REST_MS: 80,
  WIPES: 5,
  FEATHER: 0.28,
  EASING: [0.45, 0, 0.55, 1] as const,
} as const

export function searchHitWipeMs(): number {
  return SearchHitFlash.SWEEP_MS + SearchHitFlash.RELEASE_MS
}

export function searchHitFlashTotalMs(): number {
  return SearchHitFlash.WIPES * searchHitWipeMs() +
    (SearchHitFlash.WIPES - 1) * SearchHitFlash.REST_MS
}
