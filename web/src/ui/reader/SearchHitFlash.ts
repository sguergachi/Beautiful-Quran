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
