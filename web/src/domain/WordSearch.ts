/** Quran-wide word search — mirrors Android `domain/WordSearch.kt`. */

export const WORD_SEARCH_MAX_HITS = 400
export const WORD_SEARCH_MIN_QUERY_LENGTH = 2
export const WORD_SEARCH_PREVIEW_LIMIT = 3

/** Keeps visible concept evidence ahead of any bounded corroboration bonus. */
const VISIBLE_CONCEPT_EVIDENCE_BONUS = 300

/** Text surfaces currently visible in the reader. Hidden translations must not affect search. */
export interface WordSearchSources {
  arabic: boolean
  wordGloss: boolean
  transliteration: boolean
  verseTranslation: boolean
}

export const ALL_WORD_SEARCH_SOURCES: WordSearchSources = {
  arabic: true,
  wordGloss: true,
  transliteration: true,
  verseTranslation: true,
}

export type WordSearchDisplaySource =
  | 'arabic'
  | 'word_gloss'
  | 'transliteration'
  | 'verse_translation'

export interface WordSearchHit {
  surahId: number
  ayahNumber: number
  position: number
  arabic: string
  translation: string
  transliteration: string
  ayahText: string
  ayahTranslation: string
  surahNameTransliteration: string
  surahNameArabic: string
  matchLabel?: string | null
  matchTerms?: string[]
  targetPositions?: number[]
  correctedQuery?: string | null
  matchReason?: string
  displayText?: string
  displaySource?: WordSearchDisplaySource
}

export interface SurahWordSearchSection {
  surahId: number
  surahNameTransliteration: string
  surahNameArabic: string
  hits: WordSearchHit[]
  totalCount: number
  expanded: boolean
  hiddenCount: number
}

export interface WordSearchIndexEntry {
  surahId: number
  ayahNumber: number
  position: number
  arabic: string
  arabicNorm: string
  translation: string
  translationLower: string
  transliteration: string
  transliterationLower: string
  root: string
  ayahText: string
  ayahTranslation: string
  surahNameTransliteration: string
  surahNameArabic: string
}

export interface SearchConcept {
  name: string
  primaryTerms: string[]
  secondaryTerms: string[]
  category: string
  domain: string
  ayahKeys: number[]
}

export interface RelatedSearchTerm {
  text: string
  distance: number
}

export interface ParsedSearchQuery {
  text: string
  exactOnly: boolean
}

/** Double quotes around the whole query disable spelling and semantic expansion. */
export function parseSearchQuery(query: string): ParsedSearchQuery {
  const trimmed = query.trim()
  const quoted =
    trimmed.length >= 2 &&
    ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
      (trimmed.startsWith('“') && trimmed.endsWith('”')))
  return {
    text: (quoted ? trimmed.slice(1, -1) : trimmed).trim(),
    exactOnly: quoted,
  }
}

const SEARCH_SEPARATOR = /[^\p{L}\p{N}]+/u
const QUERY_FILLERS = new Set([
  'a', 'an', 'about', 'and', 'find', 'for', 'from', 'in', 'me', 'of', 'on', 'quran',
  'regarding', 'related', 'show', 'the', 'to', 'verse', 'verses', 'with',
])

const canonicalWords = (text: string): string[] =>
  text.toLowerCase().split(SEARCH_SEPARATOR).filter(Boolean)

const stem = (word: string): string => {
  if (word.length > 6 && word.endsWith('ness')) return word.slice(0, -4)
  if (word.length > 5 && word.endsWith('ies')) return `${word.slice(0, -3)}y`
  if (word.length > 4 && word.endsWith('s')) return word.slice(0, -1)
  return word
}

/** Exact phrases lead, then reordered content words, substrings, and spelling. */
export function searchTextRelevance(
  text: string,
  query: ParsedSearchQuery,
  allowFuzzy = true,
): number {
  return searchLowerTextRelevance(
    text.toLowerCase(),
    query.text.toLowerCase(),
    query.exactOnly,
    allowFuzzy,
  )
}

/** Avoids repeated case-folding for index fields and parsed queries already in lowercase. */
function searchLowerTextRelevance(
  target: string,
  needle: string,
  exactOnly: boolean,
  allowFuzzy: boolean,
): number {
  if (!target || !needle) return 0
  if (target === needle) return 3_200
  if (containsBounded(target, needle)) return 3_000
  if (exactOnly) {
    const phrase = canonicalWords(target).join(' ')
    const canonicalNeedle = canonicalWords(needle).join(' ')
    return canonicalNeedle && containsBounded(phrase, canonicalNeedle) ? 3_000 : 0
  }
  if (target.includes(needle)) return 2_200
  const singleWord = /^[\p{L}\p{N}]+$/u.test(needle)
  if (allowFuzzy && singleWord) {
    return fuzzyWordContains(target, needle) ? 1_600 : 0
  }
  if (singleWord && !QUERY_FILLERS.has(needle)) return 0

  const queryWords = canonicalWords(needle)
  const meaningful = queryWords.filter((word) => !QUERY_FILLERS.has(word))
  const content = meaningful.length > 0 ? meaningful : queryWords
  if (content.length > 1 || content.length < queryWords.length) {
    const stems = new Set(canonicalWords(target).map(stem))
    if (content.every((word) => stems.has(stem(word)))) return 2_600
  }
  return 0
}

function containsBounded(text: string, needle: string): boolean {
  let at = text.indexOf(needle)
  while (at >= 0) {
    const end = at + needle.length
    const before = at === 0 || !/[\p{L}\p{N}]/u.test(text[at - 1]!)
    const after = end === text.length || !/[\p{L}\p{N}]/u.test(text[end]!)
    if (before && after) return true
    at = text.indexOf(needle, at + 1)
  }
  return false
}

/** Score ontology vocabulary below literal text and above broad hierarchy matches. */
export function conceptRelevance(
  concept: SearchConcept,
  query: ParsedSearchQuery,
  allowFuzzy = true,
): number {
  if (query.exactOnly || normalizeArabicForSearch(query.text)) return 0
  const best = (terms: string[]): number =>
    terms.reduce(
      (score, term) => Math.max(score, searchTextRelevance(term, query, allowFuzzy)),
      0,
    )
  const score = (text: string): number => searchTextRelevance(text, query, allowFuzzy)
  const relevance = Math.max(
    score(concept.name) - 1_400,
    best(concept.primaryTerms) - 1_400,
    best(concept.secondaryTerms) - 1_500,
    score(concept.category) - 1_900,
    score(concept.domain) - 2_100,
    0,
  )
  if (relevance === 0) return 0
  const specificity =
    concept.ayahKeys.length > 0
      ? Math.min(150, Math.trunc(800 / Math.sqrt(concept.ayahKeys.length)))
      : 0
  return relevance + specificity
}

/** The one meaningful English word eligible for thesaurus expansion. */
export function thesaurusLookupKey(query: ParsedSearchQuery): string | null {
  if (query.exactOnly || normalizeArabicForSearch(query.text)) return null
  const words = canonicalWords(query.text)
  const meaningful = words.filter((word) => !QUERY_FILLERS.has(word))
  const content = meaningful.length > 0 ? meaningful : words
  return content.length === 1 ? content[0]! : null
}

/**
 * Strips tashkeel / tatweel and unifies alef / ya variants so typed Arabic
 * can match Uthmani surface forms. Mirrors Android `normalizeArabicForSearch`
 * and `tools/build_db.py` `normalize_for_alignment`.
 *
 * Uses Arabic mark code-point ranges instead of `\p{M}` so index build over
 * ~77k words stays cheap on the main thread.
 */
export function normalizeArabicForSearch(input: string): string {
  if (!input) return input
  let out = ''
  for (let i = 0; i < input.length; i++) {
    let cp = input.charCodeAt(i)
    // Skip UTF-16 surrogate pairs' trail — Quran Arabic is BMP.
    if (cp >= 0xd800 && cp <= 0xdbff) {
      i++
      continue
    }
    if (cp === 0x0671 || cp === 0x0622 || cp === 0x0623 || cp === 0x0625) {
      cp = 0x0627
    } else if (cp === 0x0649) {
      cp = 0x064a
    } else if (cp === 0x0640) {
      continue
    }
    // Arabic diacritics / Quranic annotation marks (not full \p{M}).
    if (
      (cp >= 0x064b && cp <= 0x065f) ||
      cp === 0x0670 ||
      (cp >= 0x06d6 && cp <= 0x06ed) ||
      (cp >= 0x08d3 && cp <= 0x08ff)
    ) {
      continue
    }
    if (cp >= 0x0621 && cp <= 0x064a) out += String.fromCharCode(cp)
  }
  return out
}

export function isWordSearchQuery(query: string): boolean {
  return parseSearchQuery(query).text.length >= WORD_SEARCH_MIN_QUERY_LENGTH
}

export interface AyahReference {
  surah: number
  ayah: number | null
}

const ayahReferenceRegex = /^\s*(\d+)\s*:\s*(\d+)?\s*$/

export function parseAyahReference(query: string): AyahReference | null {
  const match = ayahReferenceRegex.exec(query)
  if (!match) return null
  const surah = Number(match[1])
  if (!Number.isFinite(surah)) return null
  const ayahText = match[2]
  if (ayahText == null || ayahText === '') return { surah, ayah: null }
  const ayah = Number(ayahText)
  if (!Number.isFinite(ayah)) return null
  return { surah, ayah }
}

/** Word search runs for typed queries that are not `surah:ayah` jumps. */
export function shouldRunWordSearch(query: string): boolean {
  if (!isWordSearchQuery(query)) return false
  return parseAyahReference(query.trim()) == null
}

export function toHit(entry: WordSearchIndexEntry): WordSearchHit {
  return {
    surahId: entry.surahId,
    ayahNumber: entry.ayahNumber,
    position: entry.position,
    arabic: entry.arabic,
    translation: entry.translation,
    transliteration: entry.transliteration,
    ayahText: entry.ayahText,
    ayahTranslation: entry.ayahTranslation,
    surahNameTransliteration: entry.surahNameTransliteration,
    surahNameArabic: entry.surahNameArabic,
  }
}

export function matchWordSearch(
  index: WordSearchIndexEntry[],
  query: string,
  maxHits = WORD_SEARCH_MAX_HITS,
  concepts: SearchConcept[] = [],
  thesaurus: Map<string, RelatedSearchTerm[]> = new Map(),
  sources: WordSearchSources = ALL_WORD_SEARCH_SOURCES,
): WordSearchHit[] {
  const state = createRanking(index, query, sources)
  if (!state || maxHits <= 0) return []
  scanLexical(state, 0, index.length, false, lexicalCaches())
  scanAyahText(state, false)
  scanConcepts(state, concepts, false)
  if (state.ranked.size < 3) {
    const key = thesaurusLookupKey(state.parsed)
    const related = key ? (thesaurus.get(key) ?? []) : []
    scanRelatedWords(state, related, 0, index.length, new Map())
    scanRelatedAyahs(state, related)
  }
  if (state.ranked.size === 0) {
    scanLexical(state, 0, index.length, true, lexicalCaches())
    scanAyahText(state, true)
    scanConcepts(state, concepts, true)
  }
  scanRoots(state, 0, index.length)
  return finishRanking(state, maxHits)
}

interface RankedHit {
  key: number
  indexAt: number
  position: number
  score: number
  matchLabel?: string
  matchTerms: string[]
  correctedQuery: string | null
  matchReason: string
}

interface RankingState {
  index: WordSearchIndexEntry[]
  parsed: ParsedSearchQuery
  arabic: string
  latin: ParsedSearchQuery
  ranked: Map<number, RankedHit>
  firstIndex: Map<number, number>
  matchedRoots: Set<string>
  sources: WordSearchSources
}

interface LexicalCaches {
  arabic: Map<string, number>
  gloss: Map<string, number>
  transliteration: Map<string, number>
}

const lexicalCaches = (): LexicalCaches => ({
  arabic: new Map(),
  gloss: new Map(),
  transliteration: new Map(),
})

function createRanking(
  index: WordSearchIndexEntry[],
  query: string,
  sources: WordSearchSources,
): RankingState | null {
  const parsed = parseSearchQuery(query)
  if (parsed.text.length < WORD_SEARCH_MIN_QUERY_LENGTH) return null
  const arabic = normalizeArabicForSearch(parsed.text)
  return {
    index,
    parsed,
    arabic,
    latin: arabic ? { ...parsed, text: '' } : { ...parsed, text: parsed.text.toLowerCase() },
    ranked: new Map(),
    firstIndex: new Map(),
    matchedRoots: new Set(),
    sources,
  }
}

function addRanked(
  state: RankingState,
  indexAt: number,
  position: number,
  score: number,
  matchLabel?: string,
  matchTerms: string[] = [],
  matchReason = 'Text match',
  correctedQuery: string | null = null,
): void {
  if (score <= 0) return
  const entry = state.index[indexAt]!
  const key = entry.surahId * 1_000 + entry.ayahNumber
  const current = state.ranked.get(key)
  if (
    current == null ||
    score > current.score ||
    (score === current.score && position > 0 && current.position === 0)
  ) {
    state.ranked.set(key, {
      key,
      indexAt,
      position,
      score,
      matchLabel,
      matchTerms,
      correctedQuery,
      matchReason,
    })
  }
}

function scanLexical(
  state: RankingState,
  from: number,
  to: number,
  allowFuzzy: boolean,
  caches: LexicalCaches,
): void {
  const arabicQuery = { ...state.parsed, text: state.arabic }
  const cached = (
    text: string,
    query: ParsedSearchQuery,
    cache: Map<string, number>,
  ): number => {
    const previous = cache.get(text)
    if (previous !== undefined) return previous
    const score = searchLowerTextRelevance(text, query.text, query.exactOnly, allowFuzzy)
    cache.set(text, score)
    return score
  }
  for (let i = from; i < to; i++) {
    const entry = state.index[i]!
    const arabic = state.sources.arabic && state.arabic
      ? cached(entry.arabicNorm, arabicQuery, caches.arabic)
      : null
    const gloss = state.sources.wordGloss
      ? cached(entry.translationLower, state.latin, caches.gloss)
      : null
    const transliteration = state.sources.transliteration
      ? cached(entry.transliterationLower, state.latin, caches.transliteration)
      : null
    const score = Math.max(arabic ?? 0, gloss ?? 0, transliteration ?? 0)
    const correction = allowFuzzy && score > 0
      ? state.sources.arabic && state.arabic
        ? fuzzyWordMatch(entry.arabicNorm, state.arabic)
        : (state.sources.wordGloss
            ? fuzzyWordMatch(entry.translationLower, state.latin.text)
            : null) ??
          (state.sources.transliteration
            ? fuzzyWordMatch(entry.transliterationLower, state.latin.text)
            : null)
      : null
    addRanked(
      state,
      i,
      entry.position,
      score,
      undefined,
      correction ? [correction] : [],
      allowFuzzy ? 'Spelling match' : 'Text match',
      correction,
    )
    if (!state.parsed.exactOnly && score > 0 && entry.root) state.matchedRoots.add(entry.root)
    const key = entry.surahId * 1_000 + entry.ayahNumber
    if (!state.firstIndex.has(key)) state.firstIndex.set(key, i)
  }
}

function scanAyahText(state: RankingState, allowFuzzy: boolean): void {
  let at = 0
  while (at < state.index.length) {
    const anchor = state.index[at]!
    let end = at + 1
    while (
      end < state.index.length &&
      state.index[end]!.surahId === anchor.surahId &&
      state.index[end]!.ayahNumber === anchor.ayahNumber
    ) {
      end++
    }
    let glossScore = 0
    if (state.sources.wordGloss && /\s/u.test(state.parsed.text)) {
      glossScore = searchTextRelevance(sameAyahGlossLine(state.index, at), state.latin, allowFuzzy)
    }
    const transliterationScore = state.sources.transliteration && /\s/u.test(state.parsed.text)
      ? searchTextRelevance(sameAyahTransliterationLine(state.index, at), state.latin, allowFuzzy)
      : 0
    const score = Math.max(
      state.sources.arabic && state.arabic
        ? searchTextRelevance(
            normalizeArabicForSearch(anchor.ayahText),
            { ...state.parsed, text: state.arabic },
            allowFuzzy,
          )
        : 0,
      state.sources.verseTranslation
        ? searchTextRelevance(anchor.ayahTranslation, state.latin, allowFuzzy)
        : 0,
      glossScore,
      transliterationScore,
    )
    const correction = allowFuzzy && score > 0
      ? state.sources.arabic && state.arabic
        ? fuzzyWordMatch(normalizeArabicForSearch(anchor.ayahText), state.arabic)
        : (state.sources.verseTranslation
            ? fuzzyWordMatch(anchor.ayahTranslation.toLowerCase(), state.latin.text)
            : null) ??
          (state.sources.wordGloss
            ? fuzzyWordMatch(sameAyahGlossLine(state.index, at).toLowerCase(), state.latin.text)
            : null) ??
          (state.sources.transliteration
            ? fuzzyWordMatch(sameAyahTransliterationLine(state.index, at).toLowerCase(), state.latin.text)
            : null)
      : null
    addRanked(
      state,
      at,
      0,
      score,
      undefined,
      correction ? [correction] : [],
      allowFuzzy ? 'Spelling match' : 'Text match',
      correction,
    )
    at = end
  }
}

function scanRoots(state: RankingState, from: number, to: number): void {
  if (state.parsed.exactOnly || state.matchedRoots.size === 0) return
  for (let i = from; i < to; i++) {
    const entry = state.index[i]!
    if (state.matchedRoots.has(entry.root)) {
      addRanked(
        state,
        i,
        entry.position,
        1_450,
        undefined,
        [state.sources.wordGloss ? entry.translation : entry.arabic],
        'Same Arabic root',
      )
    }
  }
}

function scanConcepts(
  state: RankingState,
  concepts: SearchConcept[],
  allowFuzzy: boolean,
): void {
  if (state.parsed.exactOnly) return
  const semantic = new Map<
    number,
    { best: number; bonus: number; label: string; correction: string | null; terms: string[] }
  >()
  for (const concept of concepts) {
    const score = conceptRelevance(concept, state.parsed, allowFuzzy)
    if (score <= 0) continue
    const correction = allowFuzzy
      ? [concept.name, ...concept.primaryTerms, ...concept.secondaryTerms]
          .map((term) => fuzzyWordMatch(term.toLowerCase(), state.parsed.text.toLowerCase()))
          .find((term) => term != null) ?? null
      : null
    const terms = conceptHighlightTerms(concept)
    const evidenceQuery = { text: correction ?? state.parsed.text, exactOnly: false }
    for (const key of concept.ayahKeys) {
      const indexAt = state.firstIndex.get(key)
      const hasVisibleEvidence = indexAt != null && Math.max(
        state.sources.verseTranslation
          ? searchTextRelevance(state.index[indexAt]!.ayahTranslation, evidenceQuery, false)
          : 0,
        state.sources.wordGloss
          ? searchTextRelevance(sameAyahGlossLine(state.index, indexAt), evidenceQuery, false)
          : 0,
        state.sources.transliteration
          ? searchTextRelevance(sameAyahTransliterationLine(state.index, indexAt), evidenceQuery, false)
          : 0,
      ) > 0
      const groundedScore = score + (hasVisibleEvidence ? VISIBLE_CONCEPT_EVIDENCE_BONUS : 0)
      const current = semantic.get(key)
      semantic.set(
        key,
        current == null
          ? { best: groundedScore, bonus: 0, label: concept.name, correction, terms }
          : {
              best: Math.max(current.best, groundedScore),
              bonus: Math.min(
                250,
                current.bonus + Math.trunc(Math.min(current.best, groundedScore) / 5),
              ),
              label: groundedScore > current.best ? concept.name : current.label,
              correction: groundedScore > current.best ? correction : current.correction,
              terms: [...new Map([...current.terms, ...terms].map((term) => [term.toLowerCase(), term])).values()],
            },
      )
    }
  }
  for (const [key, match] of semantic) {
    const indexAt = state.firstIndex.get(key)
    if (indexAt != null) {
      addRanked(
        state,
        indexAt,
        0,
        match.best + match.bonus,
        match.label,
        [...(match.correction ? [match.correction] : []), ...match.terms],
        `Concept · ${match.label}`,
        match.correction,
      )
    }
  }
}

function bestRelated(
  text: string,
  related: RelatedSearchTerm[],
): { score: number; terms: string[] } | null {
  let bestScore = 0
  let bestTerm: string | null = null
  const terms: string[] = []
  for (const candidate of related) {
    const score =
      searchTextRelevance(
        text,
        { text: candidate.text, exactOnly: false },
        false,
      ) -
      (1_100 + candidate.distance * 150)
    if (score > bestScore) {
      bestScore = score
      bestTerm = candidate.text
    }
    if (score > 0) terms.push(candidate.text)
  }
  return bestTerm
    ? { score: bestScore, terms: [bestTerm, ...terms.filter((term) => term !== bestTerm)] }
    : null
}

function scanRelatedWords(
  state: RankingState,
  related: RelatedSearchTerm[],
  from: number,
  to: number,
  cache: Map<string, ReturnType<typeof bestRelated>>,
): void {
  if (related.length === 0 || !state.sources.wordGloss) return
  for (let i = from; i < to; i++) {
    const entry = state.index[i]!
    let match = cache.get(entry.translationLower)
    if (match === undefined) {
      match = bestRelated(entry.translationLower, related)
      cache.set(entry.translationLower, match)
    }
    if (!match) continue
    addRanked(
      state,
      i,
      entry.position,
      match.score,
      undefined,
      match.terms,
      `Related · ${match.terms[0]}`,
    )
  }
}

function scanRelatedAyahs(state: RankingState, related: RelatedSearchTerm[]): void {
  if (related.length === 0 || (!state.sources.wordGloss && !state.sources.verseTranslation)) return
  let at = 0
  while (at < state.index.length) {
    const anchor = state.index[at]!
    let end = at + 1
    while (
      end < state.index.length &&
      state.index[end]!.surahId === anchor.surahId &&
      state.index[end]!.ayahNumber === anchor.ayahNumber
    ) {
      end++
    }
    const translation = state.sources.verseTranslation
      ? bestRelated(anchor.ayahTranslation, related)
      : null
    const gloss = state.sources.wordGloss
      ? bestRelated(sameAyahGlossLine(state.index, at), related)
      : null
    const match = translation == null || (gloss != null && gloss.score > translation.score)
      ? gloss
      : translation
    if (match) {
      addRanked(
        state,
        at,
        0,
        match.score,
        undefined,
        match.terms,
        `Related · ${match.terms[0]}`,
      )
    }
    at = end
  }
}

function finishRanking(state: RankingState, maxHits: number): WordSearchHit[] {
  return [...state.ranked.values()]
    .sort((a, b) => b.score - a.score || a.key - b.key || a.position - b.position)
    .slice(0, maxHits)
    .map((match) => {
      const anchor = state.index[match.indexAt]!
      const base = {
        ...toHit(anchor),
        matchLabel: match.matchLabel ?? null,
        matchTerms: match.matchTerms,
        correctedQuery: match.correctedQuery,
        matchReason: match.matchReason,
      }
      const displayed = toHitWithDisplayTranslation(
        anchor,
        state.index,
        match.indexAt,
        state.parsed.text,
        base,
        match.matchLabel ?? '',
        match.matchTerms,
        state.sources,
      )
      const targetIndices = [...new Set([
        ...(match.position > 0 ? [match.indexAt] : []),
        ...visibleSearchTargetIndices(
          state.index,
          match.indexAt,
          displayed.displayText ?? displayed.ayahTranslation,
          state.parsed.text,
          match.matchLabel ?? '',
          match.matchTerms,
        ),
      ])].sort((a, b) => state.index[a]!.position - state.index[b]!.position)
      const targetAt = targetIndices[0]
      if (targetAt == null) {
        return {
          ...displayed,
          position: 0,
          targetPositions: [],
          arabic: '',
          translation: '',
          transliteration: '',
        }
      }
      return {
        ...toHit(state.index[targetAt]!),
        matchLabel: match.matchLabel ?? null,
        matchTerms: match.matchTerms,
        correctedQuery: match.correctedQuery,
        targetPositions: targetIndices.map((i) => state.index[i]!.position),
        matchReason: match.matchReason,
        displayText: displayed.displayText,
        displaySource: displayed.displaySource,
      }
    })
}

const FUZZY_WORD = /[\p{L}\p{N}]+/gu

/** The first whole word in text at most one edit from query. */
export function fuzzyWordMatch(text: string, query: string): string | null {
  if (query.length < 4 || !/^[\p{L}\p{N}]+$/u.test(query)) return null
  FUZZY_WORD.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = FUZZY_WORD.exec(text)) != null) {
    if (isWithinOneEdit(match[0], query)) return match[0]
  }
  return null
}

/** True when one whole word in text is at most one edit from query. */
export function fuzzyWordContains(text: string, query: string): boolean {
  return fuzzyWordMatch(text, query) != null
}

/** Corrected vocabulary term shown only when spelling fallback won. */
export function spellingCorrection(hits: Iterable<WordSearchHit>): string | null {
  for (const hit of hits) {
    if (hit.correctedQuery) return hit.correctedQuery
  }
  return null
}

function conceptHighlightTerms(concept: SearchConcept): string[] {
  const terms = [concept.name, ...concept.primaryTerms, ...concept.secondaryTerms]
    .flatMap((text) => text.match(/[\p{L}\p{N}]+/gu) ?? [])
    .filter((term) => term.length >= 3 && !HIGHLIGHT_FILLERS.has(term.toLowerCase()))
  return [...new Map(terms.map((term) => [term.toLowerCase(), term])).values()]
}

function isWithinOneEdit(word: string, query: string): boolean {
  if (Math.abs(word.length - query.length) > 1) return false
  let wordAt = 0
  let queryAt = 0
  let edits = 0
  while (wordAt < word.length && queryAt < query.length) {
    if (word[wordAt] === query[queryAt]) {
      wordAt++
      queryAt++
    } else {
      edits++
      if (edits > 1) break
      if (word.length >= query.length) wordAt++
      if (word.length <= query.length) queryAt++
    }
  }
  edits += word.length - wordAt + query.length - queryAt
  if (edits <= 1) return true

  if (word.length !== query.length) return false
  const first = [...word].findIndex((char, i) => char !== query[i])
  return (
    first >= 0 &&
    first + 1 < word.length &&
    word[first] === query[first + 1] &&
    word[first + 1] === query[first] &&
    word.slice(first + 2) === query.slice(first + 2)
  )
}

/** Chooses only among text surfaces visible under the active reader settings. */
function toHitWithDisplayTranslation(
  entry: WordSearchIndexEntry,
  index: WordSearchIndexEntry[],
  at: number,
  query: string,
  base: WordSearchHit = toHit(entry),
  semanticLabel = '',
  semanticTerms: string[] = [],
  sources: WordSearchSources = ALL_WORD_SEARCH_SOURCES,
): WordSearchHit {
  const candidates: Array<{ text: string; source: WordSearchDisplaySource }> = []
  if (sources.verseTranslation) {
    candidates.push({ text: entry.ayahTranslation, source: 'verse_translation' })
  }
  if (sources.wordGloss) {
    candidates.push({ text: sameAyahGlossLine(index, at), source: 'word_gloss' })
  }
  if (sources.transliteration) {
    candidates.push({ text: sameAyahTransliterationLine(index, at), source: 'transliteration' })
  }
  if (sources.arabic) candidates.push({ text: entry.ayahText, source: 'arabic' })
  const display = candidates.find(({ text, source }) =>
    highlightNeedles(
      text,
      query,
      source === 'word_gloss' ? entry.translation : '',
      semanticLabel,
      semanticTerms,
    ).length > 0,
  ) ?? candidates[0] ?? { text: '', source: 'word_gloss' as const }
  return { ...base, displayText: display.text, displaySource: display.source }
}

/** Every word gloss behind the visible highlighted terms, in Quran order. */
function visibleSearchTargetIndices(
  index: WordSearchIndexEntry[],
  at: number,
  displayText: string,
  query: string,
  semanticLabel = '',
  semanticTerms: string[] = [],
): number[] {
  if (at < 0 || at >= index.length) return []
  const anchor = index[at]!
  let lo = at
  while (
    lo > 0 &&
    index[lo - 1]!.surahId === anchor.surahId &&
    index[lo - 1]!.ayahNumber === anchor.ayahNumber
  ) lo--
  let hi = at
  while (
    hi + 1 < index.length &&
    index[hi + 1]!.surahId === anchor.surahId &&
    index[hi + 1]!.ayahNumber === anchor.ayahNumber
  ) hi++

  const arabicTerms = query
    .split(SEARCH_SEPARATOR)
    .map(normalizeArabicForSearch)
    .filter(Boolean)
  if (arabicTerms.length) {
    return Array.from({ length: hi - lo + 1 }, (_, offset) => lo + offset)
      .filter((i) => arabicTerms.some((term) => index[i]!.arabicNorm.includes(term)))
  }

  const needles = highlightNeedles(displayText, query, '', semanticLabel, semanticTerms)
  const terms = [...new Set(
    needles
      .flatMap((needle) => [needle, ...(needle.match(/[\p{L}\p{N}]+/gu) ?? [])])
      .filter((term) => term.length >= 3 && !HIGHLIGHT_FILLERS.has(term.toLowerCase()))
      .map((term) => term.toLowerCase()),
  )]
  const targets: number[] = []
  for (let i = lo; i <= hi; i++) {
    const score = terms.reduce(
      (best, term) => Math.max(best, glossAlignmentRelevance(index[i]!.translationLower, term)),
      0,
    )
    if (score > 0) targets.push(i)
  }
  if (targets.length) return targets

  const auxiliaryOnly = needles
    .flatMap((needle) => needle.match(/[\p{L}\p{N}]+/gu) ?? [])
    .every((term) => TRANSLATION_ONLY_AUXILIARIES.has(term.toLowerCase()))
  if (!auxiliaryOnly) return []

  // The canonical translation can add an auxiliary with no one-to-one word
  // gloss ("could see", "could have taken"). Pulse its nearest grounded verb.
  for (const term of neighboringVisibleTerms(displayText, needles)) {
    let target: number | null = null
    let targetScore = 0
    for (let i = lo; i <= hi; i++) {
      const score = glossAlignmentRelevance(index[i]!.translationLower, term)
      if (score > targetScore) {
        target = i
        targetScore = score
      }
    }
    if (target != null) return [target]
  }
  return []
}

const ALIGNMENT_WORD = /[\p{L}\p{N}]+/gu

function alignmentForm(word: string): string {
  if (word.length > 4 && word.endsWith('ing')) return word.slice(0, -3)
  if (word.length > 6 && word.endsWith('ness')) return word.slice(0, -4)
  if (word.length > 5 && word.endsWith('ies')) return `${word.slice(0, -3)}y`
  if (word.length > 4 && word.endsWith('s')) return word.slice(0, -1)
  return word
}

/** Whole-token/stem score for mapping visible translation evidence to a Quran gloss. */
export function glossAlignmentRelevance(gloss: string, visibleTerm: string): number {
  const glossWords = gloss.toLowerCase().match(ALIGNMENT_WORD) ?? []
  const termWords = visibleTerm.toLowerCase().match(ALIGNMENT_WORD) ?? []
  if (!glossWords.length || !termWords.length) return 0
  for (let at = 0; at <= glossWords.length - termWords.length; at++) {
    if (termWords.every((word, offset) => glossWords[at + offset] === word)) return 2
  }
  const glossForms = new Set(glossWords.map(alignmentForm))
  return termWords.every((word) => glossForms.has(alignmentForm(word))) ? 1 : 0
}

/** Nearby content words that can ground a translation-only auxiliary match. */
function neighboringVisibleTerms(text: string, needles: string[]): string[] {
  const words = [...text.matchAll(/[\p{L}\p{N}]+/gu)]
  const match = needles
    .map((needle) => ({ at: text.toLowerCase().indexOf(needle.toLowerCase()), length: needle.length }))
    .find(({ at }) => at >= 0)
  if (!match) return []
  const first = words.findIndex((word) => word.index + word[0].length > match.at)
  let last = first
  while (last + 1 < words.length && words[last + 1]!.index < match.at + match.length) last++
  if (first < 0 || last < first) return []
  const terms: string[] = []
  const radius = Math.min(4, Math.max(words.length - last, first + 1))
  for (let distance = 1; distance <= radius; distance++) {
    for (const at of [last + distance, first - distance]) {
      const term = words[at]?.[0]
      if (term && term.length >= 2 && !TARGET_CONTEXT_FILLERS.has(term.toLowerCase())) {
        terms.push(term)
      }
    }
  }
  return [...new Map(terms.map((term) => [term.toLowerCase(), term])).values()]
}

/** Space-joined English glosses, coalescing adjacent shared-phrase copies. */
export function sameAyahGlossLine(
  index: WordSearchIndexEntry[],
  at: number,
): string {
  if (at < 0 || at >= index.length) return ''
  const anchor = index[at]!
  let lo = at
  while (
    lo > 0 &&
    index[lo - 1]!.surahId === anchor.surahId &&
    index[lo - 1]!.ayahNumber === anchor.ayahNumber
  ) {
    lo--
  }
  let hi = at
  while (
    hi + 1 < index.length &&
    index[hi + 1]!.surahId === anchor.surahId &&
    index[hi + 1]!.ayahNumber === anchor.ayahNumber
  ) {
    hi++
  }
  const parts: string[] = []
  for (let i = lo; i <= hi; i++) {
    const entry = index[i]!
    const part = entry.translation.trim()
    const previous = i > lo ? index[i - 1]! : null
    const sharedPhrase = previous != null &&
      part.toLocaleLowerCase() === previous.translation.trim().toLocaleLowerCase() &&
      normalizeArabicForSearch(entry.arabic) !== normalizeArabicForSearch(previous.arabic)
    if (part && !sharedPhrase) {
      parts.push(part)
    }
  }
  return parts.join(' ')
}

/** Space-joined transliteration shown beneath Arabic when that option is enabled. */
export function sameAyahTransliterationLine(
  index: WordSearchIndexEntry[],
  at: number,
): string {
  if (at < 0 || at >= index.length) return ''
  const anchor = index[at]!
  let lo = at
  while (
    lo > 0 &&
    index[lo - 1]!.surahId === anchor.surahId &&
    index[lo - 1]!.ayahNumber === anchor.ayahNumber
  ) lo--
  let hi = at
  while (
    hi + 1 < index.length &&
    index[hi + 1]!.surahId === anchor.surahId &&
    index[hi + 1]!.ayahNumber === anchor.ayahNumber
  ) hi++
  return index.slice(lo, hi + 1).map((entry) => entry.transliteration.trim()).join(' ').trim()
}

/** How many index rows to scan before yielding to the event loop. */
export const WORD_SEARCH_CHUNK = 4_000

/**
 * Cooperative match — same results as [matchWordSearch], but yields every
 * [WORD_SEARCH_CHUNK] rows so typing stays responsive on the main thread.
 * Callers pass [isCancelled] to drop stale queries (Android `collectLatest`).
 */
export async function matchWordSearchAsync(
  index: WordSearchIndexEntry[],
  query: string,
  maxHits = WORD_SEARCH_MAX_HITS,
  isCancelled: () => boolean = () => false,
  concepts: SearchConcept[] = [],
  thesaurus: Map<string, RelatedSearchTerm[]> = new Map(),
  sources: WordSearchSources = ALL_WORD_SEARCH_SOURCES,
): Promise<WordSearchHit[]> {
  const state = createRanking(index, query, sources)
  if (!state || maxHits <= 0) return []
  const scanChunks = async (
    scan: (from: number, to: number) => void,
  ): Promise<boolean> => {
    for (let from = 0; from < index.length; from += WORD_SEARCH_CHUNK) {
      if (from > 0) {
        if (isCancelled()) return false
        await yieldToEventLoop()
        if (isCancelled()) return false
      }
      scan(from, Math.min(index.length, from + WORD_SEARCH_CHUNK))
    }
    return true
  }

  const exactCaches = lexicalCaches()
  if (!(await scanChunks((from, to) => scanLexical(state, from, to, false, exactCaches)))) return []
  scanAyahText(state, false)
  scanConcepts(state, concepts, false)
  if (state.ranked.size < 3) {
    const key = thesaurusLookupKey(state.parsed)
    const related = key ? (thesaurus.get(key) ?? []) : []
    const relatedCache = new Map<string, ReturnType<typeof bestRelated>>()
    if (!(await scanChunks((from, to) => scanRelatedWords(state, related, from, to, relatedCache)))) {
      return []
    }
    scanRelatedAyahs(state, related)
  }
  if (state.ranked.size === 0) {
    const fuzzyCaches = lexicalCaches()
    if (!(await scanChunks((from, to) => scanLexical(state, from, to, true, fuzzyCaches)))) return []
    scanAyahText(state, true)
    scanConcepts(state, concepts, true)
  }
  if (state.matchedRoots.size > 0) {
    if (!(await scanChunks((from, to) => scanRoots(state, from, to)))) return []
  }
  return finishRanking(state, maxHits)
}

function yieldToEventLoop(): Promise<void> {
  return new Promise((resolve) => {
    const channel = new MessageChannel()
    channel.port1.onmessage = () => {
      channel.port1.close()
      channel.port2.close()
      resolve()
    }
    channel.port2.postMessage(undefined)
  })
}

export function sectionWordSearchHits(
  hits: WordSearchHit[],
  expandedSurahIds: Set<number>,
  previewLimit = WORD_SEARCH_PREVIEW_LIMIT,
): SurahWordSearchSection[] {
  if (hits.length === 0) return []
  const grouped = new Map<number, WordSearchHit[]>()
  for (const hit of hits) {
    const list = grouped.get(hit.surahId)
    if (list) list.push(hit)
    else grouped.set(hit.surahId, [hit])
  }
  const sections: SurahWordSearchSection[] = []
  for (const [surahId, surahHits] of grouped) {
    const expanded = expandedSurahIds.has(surahId)
    const visible =
      expanded || surahHits.length <= previewLimit
        ? surahHits
        : surahHits.slice(0, previewLimit)
    const first = surahHits[0]!
    sections.push({
      surahId,
      surahNameTransliteration: first.surahNameTransliteration,
      surahNameArabic: first.surahNameArabic,
      hits: visible,
      totalCount: surahHits.length,
      expanded,
      hiddenCount: Math.max(0, surahHits.length - visible.length),
    })
  }
  return sections
}

export interface AyahTextSpan {
  text: string
  highlighted: boolean
}

export function ayahHighlightSpans(
  ayahText: string,
  position: number,
  fallbackWord: string,
): AyahTextSpan[] {
  if (!ayahText) return []
  const tokens = ayahText.split(/\s+/).filter((t) => t.length > 0)
  if (position >= 1 && position <= tokens.length) {
    const spans: AyahTextSpan[] = []
    tokens.forEach((token, index) => {
      if (index > 0) spans.push({ text: ' ', highlighted: false })
      spans.push({ text: token, highlighted: index + 1 === position })
    })
    return spans
  }
  if (!fallbackWord) return [{ text: ayahText, highlighted: false }]
  const spans: AyahTextSpan[] = []
  let start = 0
  let i = ayahText.indexOf(fallbackWord)
  if (i < 0) return [{ text: ayahText, highlighted: false }]
  while (i >= 0) {
    if (i > start) {
      spans.push({ text: ayahText.slice(start, i), highlighted: false })
    }
    spans.push({ text: fallbackWord, highlighted: true })
    start = i + fallbackWord.length
    i = ayahText.indexOf(fallbackWord, start)
  }
  if (start < ayahText.length) {
    spans.push({ text: ayahText.slice(start), highlighted: false })
  }
  return spans
}

/** How many whole words of context to keep on each side of a search hit. */
export const SNIPPET_WORDS_BEFORE = 8
export const SNIPPET_WORDS_AFTER = 14

/**
 * Builds spans for an English search snippet: a short window centered on the
 * match (query or word gloss) with the match highlighted.
 */
export function englishTranslationHighlightSpans(
  ayahTranslation: string,
  query: string,
  wordGloss: string,
  semanticLabel = '',
  semanticTerms: string[] = [],
): AyahTextSpan[] {
  if (!ayahTranslation) return []
  const needles = highlightNeedleSpecs(
    ayahTranslation,
    query.trim(),
    wordGloss.trim(),
    semanticLabel.trim(),
    semanticTerms,
  )
  const snippet = windowAroundMatch(
    ayahTranslation,
    needles[0]?.text ?? null,
    SNIPPET_WORDS_BEFORE,
    SNIPPET_WORDS_AFTER,
    needles[0]?.wholeWord ?? false,
  )
  return highlightAllOccurrences(snippet, needles)
}

/**
 * Trims [text] to roughly [wordsBefore]…[wordsAfter] words around the first
 * occurrence of [needle], adding an ellipsis when the ends were cut.
 */
export function windowAroundMatch(
  text: string,
  needle: string | null,
  wordsBefore = SNIPPET_WORDS_BEFORE,
  wordsAfter = SNIPPET_WORDS_AFTER,
  wholeWord = false,
): string {
  if (!needle || !text) return text
  const matchStart = firstOccurrence(text, needle, wholeWord)
  if (matchStart < 0) return text
  const matchEnd = matchStart + needle.length
  const words = [...text.matchAll(/\S+/g)]
  if (words.length === 0) return text
  let matchWord = 0
  for (let i = 0; i < words.length; i++) {
    const m = words[i]!
    const start = m.index ?? 0
    const end = start + m[0]!.length
    if (matchStart < end && matchEnd > start) {
      matchWord = i
      break
    }
  }
  const from = Math.max(0, matchWord - wordsBefore)
  const to = Math.min(words.length - 1, matchWord + wordsAfter)
  const startChar = words[from]!.index ?? 0
  const endWord = words[to]!
  const endChar = (endWord.index ?? 0) + endWord[0]!.length
  const core = text.slice(startChar, endChar).trim()
  const prefix = from > 0 ? '…' : ''
  const suffix = to < words.length - 1 ? '…' : ''
  return prefix + core + suffix
}

/** Finds every visible term that genuinely helped the result rank. */
export function highlightNeedles(
  haystack: string,
  query: string,
  wordGloss: string,
  semanticLabel = '',
  semanticTerms: string[] = [],
): string[] {
  return highlightNeedleSpecs(haystack, query, wordGloss, semanticLabel, semanticTerms)
    .map(({ text }) => text)
}

interface HighlightNeedle {
  text: string
  wholeWord: boolean
}

function highlightNeedleSpecs(
  haystack: string,
  query: string,
  wordGloss: string,
  semanticLabel: string,
  semanticTerms: string[],
): HighlightNeedle[] {
  const needles = new Map<string, HighlightNeedle>()
  const add = (text: string, wholeWord = true): void => {
    const term = text.trim()
    if (term && firstOccurrence(haystack, term, wholeWord) >= 0) {
      if (!needles.has(term.toLowerCase())) needles.set(term.toLowerCase(), { text: term, wholeWord })
    }
  }
  add(query, false)
  const parsed = parseSearchQuery(query)
  const arabicQuery = Boolean(normalizeArabicForSearch(query))
  const visibleTerm = (term: string): string | null => {
    if (firstOccurrence(haystack, term, true) >= 0) return term
    const sourceWords = term.match(/[\p{L}\p{N}]+/gu) ?? []
    if (sourceWords.length !== 1) return null
    const form = alignmentForm(sourceWords[0]!.toLowerCase())
    return (haystack.match(/[\p{L}\p{N}]+/gu) ?? [])
      .find((word) => alignmentForm(word.toLowerCase()) === form) ?? null
  }
  const presentTokens = (text: string): string[] =>
    text
      .split(/[\s,;:]+/)
      .map((token) => token.trim().replace(/^[([{"']+|[)\]}"']+$/g, ''))
      .filter(
        (token) => token.length >= 3,
      )
      .map(visibleTerm)
      .filter((term): term is string => term != null)

  const glossTokens = presentTokens(wordGloss)
    .filter((token) => !HIGHLIGHT_FILLERS.has(token.toLowerCase()))
  if (arabicQuery) {
    glossTokens.forEach((term) => add(term))
  } else {
    glossTokens
      .filter((token) => searchTextRelevance(token, parsed) > 0)
      .forEach((term) => add(term))
  }
  semanticTerms.flatMap(presentTokens).forEach((term) => add(term))
  presentTokens(semanticLabel)
    .filter((token) => !HIGHLIGHT_FILLERS.has(token.toLowerCase()))
    .forEach((term) => add(term))
  return [...needles.values()]
}

function firstOccurrence(text: string, term: string, wholeWord: boolean, startAt = 0): number {
  const lowerText = text.toLowerCase()
  const lowerTerm = term.toLowerCase()
  let at = lowerText.indexOf(lowerTerm, startAt)
  while (at >= 0) {
    const end = at + term.length
    const before = at > 0 ? text[at - 1]! : ''
    const after = end < text.length ? text[end]! : ''
    if (!wholeWord || (!/[\p{L}\p{N}]/u.test(before) && !/[\p{L}\p{N}]/u.test(after))) return at
    at = lowerText.indexOf(lowerTerm, at + 1)
  }
  return -1
}

/** Backward-compatible first visible term for focused callers. */
export function highlightNeedle(
  haystack: string,
  query: string,
  wordGloss: string,
  semanticLabel = '',
  semanticTerms: string[] = [],
): string | null {
  return highlightNeedles(haystack, query, wordGloss, semanticLabel, semanticTerms)[0] ?? null
}

const HIGHLIGHT_FILLERS = new Set([
  'and', 'are', 'for', 'from', 'has', 'have', 'into', 'that', 'the', 'their', 'then',
  'they', 'this', 'those', 'was', 'were', 'will', 'with', 'you', 'your',
])

const TARGET_CONTEXT_FILLERS = new Set([
  ...HIGHLIGHT_FILLERS,
  'but', 'can', 'could', 'had', 'he', 'her', 'him', 'his', 'how', 'if', 'in', 'is', 'it',
  'its', 'may', 'might', 'nor', 'not', 'or', 'shall', 'she', 'should', 'so', 'than',
  'them', 'there', 'these', 'to', 'we', 'what', 'when', 'where', 'which', 'who',
  'whom', 'whose', 'why', 'would',
])

const TRANSLATION_ONLY_AUXILIARIES = new Set([
  'can', 'could', 'may', 'might', 'shall', 'should', 'will', 'would',
])

function highlightAllOccurrences(text: string, needles: HighlightNeedle[]): AyahTextSpan[] {
  if (needles.length === 0) return [{ text, highlighted: false }]
  const ranges: { start: number; end: number }[] = []
  for (const needle of needles) {
    let at = firstOccurrence(text, needle.text, needle.wholeWord)
    while (at >= 0) {
      ranges.push({ start: at, end: at + needle.text.length })
      at = firstOccurrence(text, needle.text, needle.wholeWord, at + needle.text.length)
    }
  }
  ranges.sort((a, b) => a.start - b.start || b.end - a.end)
  const visible: { start: number; end: number }[] = []
  for (const range of ranges) {
    if (visible.at(-1)?.end == null || range.start >= visible.at(-1)!.end) visible.push(range)
  }
  if (visible.length === 0) return [{ text, highlighted: false }]
  const spans: AyahTextSpan[] = []
  let start = 0
  for (const range of visible) {
    if (range.start > start) {
      spans.push({ text: text.slice(start, range.start), highlighted: false })
    }
    spans.push({ text: text.slice(range.start, range.end), highlighted: true })
    start = range.end
  }
  if (start < text.length) spans.push({ text: text.slice(start), highlighted: false })
  return spans
}

export interface SurahFilterResult {
  surahs: { id: number; nameArabic: string; nameTransliteration: string; nameTranslation: string; ayahCount: number }[]
  ayahTarget: number | null
}

/** Home surah filter — mirrors Android `filterSurahs`. */
export function filterSurahs<T extends {
  id: number
  nameArabic: string
  nameTransliteration: string
  nameTranslation: string
  ayahCount: number
}>(surahs: T[], query: string): { surahs: T[]; ayahTarget: number | null } {
  const reference = parseAyahReference(query)
  if (!query.trim()) return { surahs, ayahTarget: null }
  if (reference != null) {
    const surah = surahs.find((s) => s.id === reference.surah)
    const ayahInRange =
      reference.ayah == null ||
      (surah != null && reference.ayah >= 1 && reference.ayah <= surah.ayahCount)
    if (surah != null && ayahInRange) {
      return { surahs: [surah], ayahTarget: reference.ayah }
    }
    return { surahs: [], ayahTarget: null }
  }
  const parsed = parseSearchQuery(query)
  const arabic = normalizeArabicForSearch(parsed.text)
  return {
    surahs: surahs
      .map((surah) => ({
        surah,
        score: Math.max(
          searchTextRelevance(surah.nameTransliteration, parsed),
          searchTextRelevance(surah.nameTranslation, parsed),
          arabic
            ? searchTextRelevance(normalizeArabicForSearch(surah.nameArabic), {
                ...parsed,
                text: arabic,
              })
            : 0,
          String(surah.id) === parsed.text ? 3_200 : 0,
        ),
      }))
      .filter((match) => match.score > 0)
      .sort((a, b) => b.score - a.score || a.surah.id - b.surah.id)
      .map((match) => match.surah),
    ayahTarget: null,
  }
}
