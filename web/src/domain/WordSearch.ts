/** Quran-wide word search — mirrors Android `domain/WordSearch.kt`. */

export const WORD_SEARCH_MAX_HITS = 400
export const WORD_SEARCH_MIN_QUERY_LENGTH = 2
export const WORD_SEARCH_PREVIEW_LIMIT = 3

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

export interface ParsedSearchQuery {
  text: string
  exactOnly: boolean
}

/** Double quotes around the whole query disable spelling and concept expansion. */
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
): number {
  const target = text.toLowerCase()
  const needle = query.text.toLowerCase()
  if (!target || !needle) return 0
  if (target === needle) return 3_200
  if (containsBounded(target, needle)) return 3_000
  if (query.exactOnly) {
    const phrase = canonicalWords(target).join(' ')
    const canonicalNeedle = canonicalWords(needle).join(' ')
    return canonicalNeedle && containsBounded(phrase, canonicalNeedle) ? 3_000 : 0
  }
  if (target.includes(needle)) return 2_200
  if (/^[\p{L}\p{N}]+$/u.test(needle)) {
    return fuzzyWordContains(target, needle) ? 1_600 : 0
  }

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
): number {
  if (query.exactOnly || normalizeArabicForSearch(query.text)) return 0
  const best = (terms: string[]): number =>
    terms.reduce((score, term) => Math.max(score, searchTextRelevance(term, query)), 0)
  const relevance = Math.max(
    searchTextRelevance(concept.name, query) - 1_400,
    best(concept.primaryTerms) - 1_400,
    best(concept.secondaryTerms) - 1_500,
    searchTextRelevance(concept.category, query) - 1_900,
    searchTextRelevance(concept.domain, query) - 2_100,
    0,
  )
  if (relevance === 0) return 0
  const specificity =
    concept.ayahKeys.length > 0
      ? Math.min(150, Math.trunc(800 / Math.sqrt(concept.ayahKeys.length)))
      : 0
  return relevance + specificity
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
): WordSearchHit[] {
  const state = createRanking(index, query)
  if (!state || maxHits <= 0) return []
  scanLexical(state, 0, index.length)
  scanAyahText(state)
  scanRoots(state, 0, index.length)
  scanConcepts(state, concepts)
  return finishRanking(state, maxHits)
}

interface RankedHit {
  key: number
  indexAt: number
  position: number
  score: number
  matchLabel?: string
}

interface RankingState {
  index: WordSearchIndexEntry[]
  parsed: ParsedSearchQuery
  arabic: string
  latin: ParsedSearchQuery
  ranked: Map<number, RankedHit>
  firstIndex: Map<number, number>
  matchedRoots: Set<string>
}

function createRanking(
  index: WordSearchIndexEntry[],
  query: string,
): RankingState | null {
  const parsed = parseSearchQuery(query)
  if (parsed.text.length < WORD_SEARCH_MIN_QUERY_LENGTH) return null
  const arabic = normalizeArabicForSearch(parsed.text)
  return {
    index,
    parsed,
    arabic,
    latin: arabic ? { ...parsed, text: '' } : parsed,
    ranked: new Map(),
    firstIndex: new Map(),
    matchedRoots: new Set(),
  }
}

function addRanked(
  state: RankingState,
  indexAt: number,
  position: number,
  score: number,
  matchLabel?: string,
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
    state.ranked.set(key, { key, indexAt, position, score, matchLabel })
  }
}

function scanLexical(state: RankingState, from: number, to: number): void {
  for (let i = from; i < to; i++) {
    const entry = state.index[i]!
    const score = Math.max(
      state.arabic
        ? searchTextRelevance(entry.arabicNorm, { ...state.parsed, text: state.arabic })
        : 0,
      searchTextRelevance(entry.translationLower, state.latin),
      searchTextRelevance(entry.transliterationLower, state.latin),
    )
    addRanked(state, i, entry.position, score)
    if (!state.parsed.exactOnly && score > 0 && entry.root) state.matchedRoots.add(entry.root)
    const key = entry.surahId * 1_000 + entry.ayahNumber
    if (!state.firstIndex.has(key)) state.firstIndex.set(key, i)
  }
}

function scanAyahText(state: RankingState): void {
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
    if (/\s/u.test(state.parsed.text)) {
      const glosses: string[] = []
      for (let i = at; i < end; i++) glosses.push(state.index[i]!.translation)
      glossScore = searchTextRelevance(glosses.join(' '), state.latin)
    }
    const score = Math.max(
      state.arabic
        ? searchTextRelevance(normalizeArabicForSearch(anchor.ayahText), {
            ...state.parsed,
            text: state.arabic,
          })
        : 0,
      searchTextRelevance(anchor.ayahTranslation, state.latin),
      glossScore,
    )
    addRanked(state, at, 0, score)
    at = end
  }
}

function scanRoots(state: RankingState, from: number, to: number): void {
  if (state.parsed.exactOnly || state.matchedRoots.size === 0) return
  for (let i = from; i < to; i++) {
    const entry = state.index[i]!
    if (state.matchedRoots.has(entry.root)) addRanked(state, i, entry.position, 1_450)
  }
}

function scanConcepts(state: RankingState, concepts: SearchConcept[]): void {
  if (state.parsed.exactOnly) return
  const semantic = new Map<number, { best: number; bonus: number; label: string }>()
  for (const concept of concepts) {
    const score = conceptRelevance(concept, state.parsed)
    if (score <= 0) continue
    for (const key of concept.ayahKeys) {
      const current = semantic.get(key)
      semantic.set(
        key,
        current == null
          ? { best: score, bonus: 0, label: concept.name }
          : {
              best: Math.max(current.best, score),
              bonus: Math.min(250, current.bonus + Math.trunc(Math.min(current.best, score) / 5)),
              label: score > current.best ? concept.name : current.label,
            },
      )
    }
  }
  for (const [key, match] of semantic) {
    const indexAt = state.firstIndex.get(key)
    if (indexAt != null) addRanked(state, indexAt, 0, match.best + match.bonus, match.label)
  }
}

function finishRanking(state: RankingState, maxHits: number): WordSearchHit[] {
  return [...state.ranked.values()]
    .sort((a, b) => b.score - a.score || a.key - b.key || a.position - b.position)
    .slice(0, maxHits)
    .map((match) => {
      const entry = state.index[match.indexAt]!
      const base = { ...toHit(entry), matchLabel: match.matchLabel ?? null }
      return match.position > 0
        ? toHitWithDisplayTranslation(entry, state.index, match.indexAt, state.parsed.text, base)
        : { ...base, position: 0, arabic: '', translation: '', transliteration: '' }
    })
}

const FUZZY_WORD = /[\p{L}\p{N}]+/gu

/** True when one whole word in text is at most one edit from query. */
export function fuzzyWordContains(text: string, query: string): boolean {
  if (query.length < 4 || !/^[\p{L}\p{N}]+$/u.test(query)) return false
  FUZZY_WORD.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = FUZZY_WORD.exec(text)) != null) {
    if (isWithinOneEdit(match[0], query)) return true
  }
  return false
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

/**
 * SI ayah text when it can show the match; otherwise the same-ayah word-gloss
 * line when that can. Falls back to SI when neither hosts a highlight.
 */
function toHitWithDisplayTranslation(
  entry: WordSearchIndexEntry,
  index: WordSearchIndexEntry[],
  at: number,
  query: string,
  base: WordSearchHit = toHit(entry),
): WordSearchHit {
  if (highlightNeedle(entry.ayahTranslation, query, entry.translation) != null) {
    return base
  }
  const glossLine = sameAyahGlossLine(index, at)
  if (highlightNeedle(glossLine, query, entry.translation) != null) {
    return { ...base, ayahTranslation: glossLine }
  }
  return base
}

/** Space-joined English glosses for every word of the same ayah as [at]. */
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
  for (let i = lo; i <= hi; i++) parts.push(index[i]!.translation)
  return parts.join(' ')
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
): Promise<WordSearchHit[]> {
  const state = createRanking(index, query)
  if (!state || maxHits <= 0) return []
  for (let from = 0; from < index.length; from += WORD_SEARCH_CHUNK) {
    if (from > 0) {
      if (isCancelled()) return []
      await yieldToEventLoop()
      if (isCancelled()) return []
    }
    scanLexical(state, from, Math.min(index.length, from + WORD_SEARCH_CHUNK))
  }
  scanAyahText(state)
  if (state.matchedRoots.size > 0) {
    for (let from = 0; from < index.length; from += WORD_SEARCH_CHUNK) {
      if (from > 0) {
        if (isCancelled()) return []
        await yieldToEventLoop()
        if (isCancelled()) return []
      }
      scanRoots(state, from, Math.min(index.length, from + WORD_SEARCH_CHUNK))
    }
  }
  scanConcepts(state, concepts)
  return finishRanking(state, maxHits)
}

function yieldToEventLoop(): Promise<void> {
  return new Promise((resolve) => {
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(() => resolve())
    } else {
      setTimeout(resolve, 0)
    }
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
): AyahTextSpan[] {
  if (!ayahTranslation) return []
  const needle = highlightNeedle(
    ayahTranslation,
    query.trim(),
    wordGloss.trim(),
  )
  const snippet = windowAroundMatch(
    ayahTranslation,
    needle,
    SNIPPET_WORDS_BEFORE,
    SNIPPET_WORDS_AFTER,
  )
  if (!needle) return [{ text: snippet, highlighted: false }]
  return highlightAllOccurrences(snippet, needle)
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
): string {
  if (!needle || !text) return text
  const matchStart = text.toLowerCase().indexOf(needle.toLowerCase())
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

/** Prefers the typed query when present; otherwise the word gloss / a token. */
export function highlightNeedle(
  haystack: string,
  query: string,
  wordGloss: string,
): string | null {
  if (query && haystack.toLowerCase().includes(query.toLowerCase())) {
    return query
  }
  if (wordGloss && haystack.toLowerCase().includes(wordGloss.toLowerCase())) {
    return wordGloss
  }
  const tokens = wordGloss
    .split(/[\s,;:]+/)
    .map((t) => t.trim().replace(/^[([{"']+|[)\]}"']+$/g, ''))
    .filter((t) => t.length >= 3)
    .sort((a, b) => b.length - a.length)
  for (const token of tokens) {
    if (haystack.toLowerCase().includes(token.toLowerCase())) return token
  }
  return null
}

function highlightAllOccurrences(text: string, needle: string): AyahTextSpan[] {
  const spans: AyahTextSpan[] = []
  const lowerText = text.toLowerCase()
  const lowerNeedle = needle.toLowerCase()
  let start = 0
  let i = lowerText.indexOf(lowerNeedle)
  if (i < 0) return [{ text, highlighted: false }]
  while (i >= 0) {
    if (i > start) spans.push({ text: text.slice(start, i), highlighted: false })
    const end = i + needle.length
    spans.push({ text: text.slice(i, end), highlighted: true })
    start = end
    i = lowerText.indexOf(lowerNeedle, start)
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
