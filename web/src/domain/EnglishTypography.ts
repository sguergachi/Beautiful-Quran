import { normalizeArabicForSearch } from './WordSearch'

const TERMINAL_PUNCTUATION = /[.!?…]["'’”)]*$/u

/**
 * Closes the ayah without guessing sentence boundaries from capitalization.
 */
export function punctuateEnglishGlosses(glosses: readonly string[]): string[] {
  let lastGloss = glosses.length - 1
  while (lastGloss >= 0 && !glosses[lastGloss]) lastGloss--
  return glosses.map((gloss, index) =>
    index === lastGloss && !TERMINAL_PUNCTUATION.test(gloss) ? `${gloss}.` : gloss,
  )
}

/** Coalesces Quran.com's shared multi-word phrases for continuous English prose. */
export function lyricizeEnglishGlosses(
  glosses: readonly string[],
  arabicWords: readonly string[],
): string[] {
  if (glosses.length !== arabicWords.length) throw new Error('glosses and Arabic words must align')
  return punctuateEnglishGlosses(glosses.map((gloss, index) => (
    index > 0 &&
    gloss === glosses[index - 1] &&
    normalizeArabicForSearch(arabicWords[index]!) !== normalizeArabicForSearch(arabicWords[index - 1]!)
      ? ''
      : gloss
  )))
}

/** Visible owner of a shared gloss when `requestedIndex` was coalesced. */
export function coalescedGlossOwnerIndex(
  glosses: readonly string[],
  arabicWords: readonly string[],
  requestedIndex: number,
): number | null {
  if (glosses.length !== arabicWords.length) throw new Error('glosses and Arabic words must align')
  if (requestedIndex < 0 || requestedIndex >= glosses.length) return null
  let owner = requestedIndex
  while (
    owner > 0 &&
    glosses[owner] === glosses[owner - 1] &&
    normalizeArabicForSearch(arabicWords[owner]!) !== normalizeArabicForSearch(arabicWords[owner - 1]!)
  ) {
    owner--
  }
  return owner
}
