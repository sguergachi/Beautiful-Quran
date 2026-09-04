/** Timing for the reader's orange search-hit wash; mirrors Android. */
export const SearchHitFlash = {
  START_DELAY_MS: 140,
  INHALE_MS: 320,
  CREST_MS: 70,
  EXHALE_MS: 420,
  REST_MS: 60,
  BREATHS: 4,
  BACKGROUND_ALPHA: 0.4,
  FOCUS_FADE_MS: 280,
  EASING: [0.45, 0, 0.55, 1] as const,
} as const

export function searchHitBreathMs(): number {
  return SearchHitFlash.INHALE_MS + SearchHitFlash.CREST_MS + SearchHitFlash.EXHALE_MS
}

export function searchHitFlashTotalMs(): number {
  return SearchHitFlash.BREATHS * searchHitBreathMs() +
    (SearchHitFlash.BREATHS - 1) * SearchHitFlash.REST_MS
}

/** Exact text ranges for a translator-only hit; prefix matches own the full word. */
export function searchHitTextRanges(text: string, rawQuery?: string | null): Array<[number, number]> {
  const trimmed = rawQuery?.trim() ?? ''
  const quoted = trimmed.length >= 2 &&
    ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
      (trimmed.startsWith('“') && trimmed.endsWith('”')))
  const query = quoted ? trimmed.slice(1, -1).trim() : trimmed
  if (!query) return []
  const ranges: Array<[number, number]> = []
  const lowerText = text.toLowerCase()
  const lowerQuery = query.toLowerCase()
  const expandWord = /^[\p{L}\p{N}]+$/u.test(query)
  let from = 0
  while (from < text.length) {
    const match = lowerText.indexOf(lowerQuery, from)
    if (match < 0) break
    let start = match
    let end = match + query.length
    if (expandWord) {
      while (start > 0 && /[\p{L}\p{N}]/u.test(text[start - 1]!)) start--
      while (end < text.length && /[\p{L}\p{N}]/u.test(text[end]!)) end++
    }
    if (!ranges.some(([a, b]) => a === start && b === end)) ranges.push([start, end])
    from = Math.max(end, match + 1)
  }
  return ranges
}
