/** Timing for the reader's orange search-hit wash; mirrors Android. */
export const SearchHitFlash = {
  START_DELAY_MS: 140,
  SWEEP_MS: 720,
  WIPES: 4,
  BAND_FRACTION: 0.72,
  EDGE_SHARE: 0.24,
  EASING: [0, 0, 1, 1] as const,
} as const

export function searchHitWipeMs(): number {
  return SearchHitFlash.SWEEP_MS
}

export function searchHitFlashTotalMs(): number {
  return SearchHitFlash.WIPES * searchHitWipeMs()
}
