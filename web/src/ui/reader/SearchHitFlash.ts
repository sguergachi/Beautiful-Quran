/** Timing for the reader's orange search-hit wash; mirrors Android. */
export const SearchHitFlash = {
  START_DELAY_MS: 140,
  SWEEP_MS: 240,
  INHALE_MS: 320,
  CREST_MS: 70,
  EXHALE_MS: 420,
  REST_MS: 60,
  PULSES: 4,
  REST_ALPHA: 0,
  EASING: [0.45, 0, 0.55, 1] as const,
} as const

export function searchHitBreathMs(): number {
  return SearchHitFlash.INHALE_MS + SearchHitFlash.CREST_MS +
    SearchHitFlash.EXHALE_MS + SearchHitFlash.REST_MS
}

export function searchHitFlashTotalMs(): number {
  return SearchHitFlash.SWEEP_MS +
    SearchHitFlash.PULSES * (SearchHitFlash.CREST_MS + SearchHitFlash.EXHALE_MS) +
    (SearchHitFlash.PULSES - 1) * (SearchHitFlash.REST_MS + SearchHitFlash.INHALE_MS)
}
