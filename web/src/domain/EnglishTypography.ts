import { normalizeArabicForSearch } from './WordSearch'

export { foldEnglish } from './WordSearch'

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

const OPENING_CONTEXT = '([{–—-“‘'
const opensAfter = (prev: string | undefined) =>
  prev === undefined || /\s/u.test(prev) || OPENING_CONTEXT.includes(prev)
const isWordChar = (c: string | undefined) => c !== undefined && /[\p{L}\p{N}]/u.test(c)

/**
 * The translation as a typographer would set it: curled quotes and
 * apostrophes, and a spaced en dash where the source typed " - ". Display-only
 * and one character for one, so search ranges and word spans measured on the
 * stored text land on the same letters. The only addition is at the very end:
 * the source dropped each verse's final punctuation, closing quotes included,
 * so the closers of a still-open quotation are restored there — unless it
 * runs on into the next verse (`quoteContinues`). Mirrors Android's
 * `EnglishTypography.typeset`.
 */
export function typesetEnglish(raw: string, quoteContinues = false): string {
  let out = ''
  let doubles = 0
  let singles = 0
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i]!
    const prev = raw[i - 1]
    const next = raw[i + 1]
    if (c === '"') {
      // Never below zero: a verse opening by closing the last one's quotation still closes its own (3:73).
      if (opensAfter(prev)) { out += '“'; doubles++ } else { out += '”'; if (doubles > 0) doubles-- }
    } else if (c === "'") {
      if (isWordChar(prev) && isWordChar(next)) out += '’'
      else if (isAynApostrophe(raw, i)) out += '’'
      else if (opensAfter(prev)) { out += '‘'; singles++ } else { out += '’'; if (singles > 0) singles-- }
    } else if (c === '-' && prev === ' ' && next === ' ') {
      out += '–'
    } else {
      out += c
    }
  }
  if (!quoteContinues) out += '’'.repeat(singles) + '”'.repeat(Math.max(0, doubles))
  return out
}

const AYN_NAMES = new Set(['Aad', 'Ayn'])
const SPEECH_CONTEXT = ',:"“([{'
const isLetter = (c: string | undefined) => c !== undefined && /\p{L}/u.test(c)

/** A later `'` that closes a quotation: after a word or stop, before a non-letter. */
function closesLater(raw: string, from: number): boolean {
  for (let j = from; j < raw.length; j++) {
    if (raw[j] !== "'") continue
    const p = raw[j - 1]!
    if ((isWordChar(p) || '.,!?;:'.includes(p)) && !isWordChar(raw[j + 1])) return true
  }
  return false
}

/**
 * Whether the `'` opening a word is the transliterated ʿayn (al-'Azeez, 'Imran,
 * 'Aad, 'umrah, "Allah 's") rather than an opening quote. Mirrors Android's
 * `EnglishTypography.isAynApostrophe`; see there for the reasoning.
 */
function isAynApostrophe(raw: string, i: number): boolean {
  const next = raw[i + 1]
  if (!isLetter(next)) return false
  if (raw[i - 1] === '-') return true
  let end = i + 1
  while (end < raw.length && isLetter(raw[end])) end++
  if (AYN_NAMES.has(raw.slice(i + 1, end))) return true
  if (closesLater(raw, end)) return false
  if (next !== next!.toUpperCase()) return true
  let k = i - 1
  while (k >= 0 && raw[k] === ' ') k--
  return k >= 0 && !SPEECH_CONTEXT.includes(raw[k]!)
}

/** Whether a quotation open at a verse's end carries on: `next`'s first double quote closes. */
export function quoteContinuesInto(next: string | null | undefined): boolean {
  if (!next) return false
  const at = next.indexOf('"')
  return at >= 0 && !opensAfter(next[at - 1])
}

/** A surah name or gloss: apostrophes curled, nothing else touched. */
export const typesetEnglishName = (raw: string): string => raw.replaceAll("'", '’')

