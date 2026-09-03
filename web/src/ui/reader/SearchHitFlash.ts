/** Timing for the reader's orange search-hit wash; mirrors Android. */
export const SearchHitFlash = {
  START_DELAY_MS: 140,
  SWEEP_MS: 240,
  INHALE_MS: 230,
  CREST_MS: 90,
  EXHALE_MS: 260,
  REST_MS: 50,
  FINAL_FADE_MS: 180,
  PULSES: 4,
  REST_ALPHA: 0.32,
  EASING: [0.37, 0, 0.63, 1] as const,
} as const

export function searchHitBreathMs(): number {
  return SearchHitFlash.INHALE_MS + SearchHitFlash.CREST_MS +
    SearchHitFlash.EXHALE_MS + SearchHitFlash.REST_MS
}

export function searchHitFlashTotalMs(): number {
  return SearchHitFlash.SWEEP_MS +
    SearchHitFlash.PULSES * (SearchHitFlash.CREST_MS + SearchHitFlash.EXHALE_MS) +
    (SearchHitFlash.PULSES - 1) * (SearchHitFlash.REST_MS + SearchHitFlash.INHALE_MS) +
    SearchHitFlash.FINAL_FADE_MS
}
