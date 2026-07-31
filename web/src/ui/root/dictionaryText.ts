/**
 * Pure helpers for the Root Viewer Dictionary section.
 * Mirror of Android `DictionaryText.kt` — keep the two in step.
 */
import type { DictionaryEntry, DictionarySenseGroup } from '../../data/dictionary'

/** Senses shown under Lemma before the reader asks for the rest. */
export const DICTIONARY_PREVIEW_SENSES = 4

export function wiktionaryPosForQac(qacPos: string): string | null {
  switch (qacPos.toUpperCase()) {
    case 'V':
    case 'VERB':
      return 'verb'
    case 'N':
    case 'NOUN':
      return 'noun'
    case 'ADJ':
    case 'ADJECTIVE':
      return 'adj'
    case 'ADV':
    case 'ADVERB':
      return 'adv'
    case 'P':
    case 'PREP':
    case 'PREPOSITION':
      return 'prep'
    case 'CONJ':
    case 'CONJUNCTION':
      return 'conj'
    case 'PRON':
    case 'PRONOUN':
      return 'pron'
    case 'PART':
    case 'PARTICLE':
      return 'particle'
    case 'INL':
    case 'INTERJECTION':
      return 'intj'
    default:
      return null
  }
}

export function orderedDictionaryGroups(
  groups: DictionarySenseGroup[],
  qacPos: string,
): DictionarySenseGroup[] {
  const preferred = wiktionaryPosForQac(qacPos)
  if (!preferred) return groups
  const match = groups.filter((g) => g.pos.toLowerCase() === preferred)
  const rest = groups.filter((g) => g.pos.toLowerCase() !== preferred)
  return [...match, ...rest]
}

export function dictionaryPosLabel(pos: string): string {
  switch (pos.toLowerCase()) {
    case 'verb':
      return 'Verb'
    case 'noun':
      return 'Noun'
    case 'adj':
    case 'adjective':
      return 'Adjective'
    case 'adv':
    case 'adverb':
      return 'Adverb'
    case 'prep':
    case 'preposition':
      return 'Preposition'
    case 'conj':
    case 'conjunction':
      return 'Conjunction'
    case 'pron':
    case 'pronoun':
      return 'Pronoun'
    case 'particle':
      return 'Particle'
    case 'intj':
    case 'interjection':
      return 'Interjection'
    case 'name':
    case 'proper noun':
    case 'propn':
      return 'Proper noun'
    default:
      return pos ? pos[0]!.toUpperCase() + pos.slice(1) : pos
  }
}

/** Flat gloss rows: optional POS label on the first gloss of each group. */
export function dictionaryGlosses(
  entry: DictionaryEntry,
  qacPos: string,
): Array<{ pos: string | null; gloss: string }> {
  const out: Array<{ pos: string | null; gloss: string }> = []
  for (const group of orderedDictionaryGroups(entry.groups, qacPos)) {
    const label = dictionaryPosLabel(group.pos)
    group.glosses.forEach((gloss, index) => {
      out.push({ pos: index === 0 ? label : null, gloss })
    })
  }
  return out
}

export function dictionaryNeedsExpand(glossCount: number): boolean {
  return glossCount > DICTIONARY_PREVIEW_SENSES
}

export function wiktionaryArabicUrl(word: string): string {
  const encoded = encodeURIComponent(word).replace(/%20/g, '_')
  return `https://en.wiktionary.org/wiki/${encoded}#Arabic`
}
