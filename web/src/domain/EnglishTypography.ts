const TERMINAL_PUNCTUATION = /[.!?…]["'’”)]*$/u

/**
 * Closes the ayah without guessing sentence boundaries from capitalization.
 */
export function punctuateEnglishGlosses(glosses: readonly string[]): string[] {
  return glosses.map((gloss, index) =>
    index === glosses.length - 1 && !TERMINAL_PUNCTUATION.test(gloss) ? `${gloss}.` : gloss,
  )
}
