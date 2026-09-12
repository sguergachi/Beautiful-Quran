import { openDatabase, queryAll, queryOne, type LoadProgress } from './database'
import { pickLemmaGloss, type GlossVote } from './lemmaGloss'
import { runtimeMushafCache } from './runtimeMushaf'
import type {
  Ayah,
  Reciter,
  RootOccurrence,
  RootLemmaSummary,
  RootSummary,
  Segment,
  Surah,
  SurahContent,
  Word,
  WordMorphology,
} from './models'
import {
  matchWordSearch,
  matchWordSearchAsync,
  normalizeArabicForSearch,
  shouldRunWordSearch,
  WORD_SEARCH_MAX_HITS,
  type WordSearchHit,
  type WordSearchIndexEntry,
  type RelatedSearchTerm,
  type SearchConcept,
} from '../domain/WordSearch'
import { assetUrl } from '../assetUrl'

let surahsCache: Surah[] | null = null
let recitersCache: Reciter[] | null = null
let wordSearchIndex: WordSearchIndexEntry[] | null = null
let wordSearchIndexPromise: Promise<WordSearchIndexEntry[]> | null = null
let searchConcepts: SearchConcept[] | null = null
let searchThesaurus = new Map<string, RelatedSearchTerm[]>()
let searchConceptPromise: Promise<SearchConcept[]> | null = null
/** Per-surah content — reopening a chapter must not re-scan sql.js. */
const surahContentCache = new Map<number, SurahContent>()
/** Per-reciter+surah timing segments (raw); PreparedTimings are built lazily. */
const timingsCache = new Map<string, Map<number, Segment[]>>()

export async function ensureReady(
  onProgress?: (p: LoadProgress) => void,
): Promise<void> {
  await openDatabase(undefined, onProgress)
}

export function surahs(): Surah[] {
  if (surahsCache) return surahsCache
  surahsCache = queryAll(
    'SELECT id, name_arabic, name_transliteration, name_translation, revelation_place, ayah_count FROM surahs ORDER BY id',
    [],
    (r) => ({
      id: Number(r.id),
      nameArabic: String(r.name_arabic),
      nameTransliteration: String(r.name_transliteration),
      nameTranslation: String(r.name_translation),
      revelationPlace: String(r.revelation_place),
      ayahCount: Number(r.ayah_count),
    }),
  )
  return surahsCache
}

export function reciters(): Reciter[] {
  if (recitersCache) return recitersCache
  recitersCache = queryAll(
    'SELECT id, slug, name, style, has_timings FROM reciters ORDER BY id',
    [],
    (r) => ({
      id: Number(r.id),
      slug: String(r.slug),
      name: String(r.name),
      style: String(r.style),
      hasTimings: Number(r.has_timings) === 1,
    }),
  )
  return recitersCache
}

export function surahContent(surahId: number): SurahContent {
  const cached = surahContentCache.get(surahId)
  if (cached) return cached

  const surah = surahs().find((s) => s.id === surahId)
  if (!surah) throw new Error(`Unknown surah ${surahId}`)

  const wordsByAyah = new Map<number, Word[]>()
  queryAll(
    `SELECT ayah_number, position, arabic
     FROM words WHERE surah_id = ? ORDER BY ayah_number, position`,
    [surahId],
    (r) => {
      const ayah = Number(r.ayah_number)
      const position = Number(r.position)
      const runtime = runtimeMushafCache?.word(surahId, ayah, position)
      const list = wordsByAyah.get(ayah) ?? []
      list.push({
        position,
        arabic: String(r.arabic),
        translation: runtime?.translation_en ?? '',
        transliteration: runtime?.transliteration ?? '',
      })
      wordsByAyah.set(ayah, list)
      return null
    },
  )

  const ayahs: Ayah[] = queryAll(
    'SELECT ayah_number, text_uthmani, translation_en FROM ayahs WHERE surah_id = ? ORDER BY ayah_number',
    [surahId],
    (r) => {
      const n = Number(r.ayah_number)
      return {
        surahId,
        number: n,
        text: String(r.text_uthmani),
        translation: String(r.translation_en),
        page: runtimeMushafCache?.word(surahId, n, 1)?.ayah_page ?? 0,
        words: wordsByAyah.get(n) ?? [],
      }
    },
  )

  const content = { surah, ayahs }
  surahContentCache.set(surahId, content)
  return content
}

/** Materialize every chapter a little at a time during startup idle periods. */
export async function preloadAllSurahContent(preferredSurahId?: number): Promise<void> {
  const allSurahs = surahs()
  if (surahContentCache.size >= allSurahs.length) return

  const ids = allSurahs.map((s) => s.id)
  if (preferredSurahId != null && ids.includes(preferredSurahId)) {
    ids.splice(ids.indexOf(preferredSurahId), 1)
    ids.unshift(preferredSurahId)
  }

  for (const id of ids) {
    if (surahContentCache.has(id)) continue
    await new Promise<void>((resolve) => {
      const run = () => {
        surahContent(id)
        resolve()
      }
      const ric = (
        globalThis as unknown as {
          requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number
        }
      ).requestIdleCallback
      if (typeof ric === 'function') ric(run, { timeout: 1_500 })
      else setTimeout(run, 0)
    })
  }
}

export function parseSegments(raw: string): Segment[] {
  try {
    const parsed = JSON.parse(raw) as number[][]
    const segments: Segment[] = []
    for (const row of parsed) {
      if (row.length < 3) continue
      segments.push({
        position: Number(row[0]),
        startMs: Number(row[1]),
        endMs: Number(row[2]),
      })
    }
    segments.sort((a, b) => a.startMs - b.startMs)
    return segments
  } catch {
    return []
  }
}

export function timings(reciterId: number, surahId: number): Map<number, Segment[]> {
  const key = `${reciterId}:${surahId}`
  const cached = timingsCache.get(key)
  if (cached) return cached

  const map = new Map<number, Segment[]>()
  queryAll(
    'SELECT ayah_number, segments FROM timings WHERE reciter_id = ? AND surah_id = ?',
    [reciterId, surahId],
    (r) => {
      map.set(Number(r.ayah_number), parseSegments(String(r.segments)))
      return null
    },
  )
  timingsCache.set(key, map)
  return map
}

export function wordMorphology(
  surahId: number,
  ayah: number,
  position: number,
): WordMorphology | null {
  return queryOne(
    `SELECT surah_id, ayah_number, position, root, lemma, pos, features
     FROM word_morphology
     WHERE surah_id = ? AND ayah_number = ? AND position = ?`,
    [surahId, ayah, position],
    (r) => ({
      surahId: Number(r.surah_id),
      ayahNumber: Number(r.ayah_number),
      position: Number(r.position),
      root: String(r.root),
      lemma: String(r.lemma),
      pos: String(r.pos),
      features: String(r.features),
    }),
  )
}

/** Code-unit string order — what SQLite's default collation gives us. */
function compareRaw(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

export function rootSummary(root: string): RootSummary | null {
  const countRow = queryOne(
    'SELECT occurrence_count FROM roots WHERE root = ?',
    [root],
    (r) => Number(r.occurrence_count),
  )
  if (countRow == null) return null

  const occurrences: RootOccurrence[] = queryAll(
    `SELECT o.surah_id, o.ayah_number, o.position,
            w.arabic, w.translation_en, s.name_transliteration
     FROM root_occurrences o
     JOIN words w ON w.surah_id = o.surah_id AND w.ayah_number = o.ayah_number AND w.position = o.position
     JOIN surahs s ON s.id = o.surah_id
     WHERE o.root = ?
     ORDER BY o.surah_id, o.ayah_number, o.position`,
    [root],
    (r) => ({
      surahId: Number(r.surah_id),
      ayahNumber: Number(r.ayah_number),
      position: Number(r.position),
      arabic: String(r.arabic),
      translation: runtimeMushafCache?.word(
        Number(r.surah_id), Number(r.ayah_number), Number(r.position),
      )?.translation_en ?? String(r.translation_en),
      surahNameTransliteration: String(r.name_transliteration),
    }),
  )

  // Every rendering of every form under this root: the counts add up to the
  // form's frequency, and they elect its English gloss. Rows come back
  // ungrouped so each word's gloss can be overlaid from the runtime cache;
  // pickLemmaGloss pools the single votes by normalized key, which is what
  // the former GROUP BY translation_en did in SQL.
  const renderings = queryAll(
    `SELECT m.lemma, m.pos, m.surah_id, m.ayah_number, m.position
     FROM word_morphology m
     JOIN words w ON w.surah_id = m.surah_id AND w.ayah_number = m.ayah_number AND w.position = m.position
     WHERE m.root = ? AND m.lemma <> ''`,
    [root],
    (r) => ({
      lemma: String(r.lemma),
      pos: String(r.pos),
      vote: {
        translation: runtimeMushafCache?.word(
          Number(r.surah_id), Number(r.ayah_number), Number(r.position),
        )?.translation_en ?? '',
        count: 1,
      },
    }),
  )
  const forms = new Map<string, { lemma: string; pos: string; votes: GlossVote[] }>()
  for (const rendering of renderings) {
    const key = `${rendering.lemma}\u0000${rendering.pos}`
    const form = forms.get(key)
    if (form) form.votes.push(rendering.vote)
    else forms.set(key, { lemma: rendering.lemma, pos: rendering.pos, votes: [rendering.vote] })
  }
  const lemmas: RootLemmaSummary[] = [...forms.values()]
    .map((form) => ({
      lemma: form.lemma,
      pos: form.pos,
      occurrenceCount: form.votes.reduce((total, vote) => total + vote.count, 0),
      gloss: pickLemmaGloss(form.votes),
    }))
    // Code-unit order, matching SQLite's BINARY collation and Android.
    .sort(
      (a, b) =>
        b.occurrenceCount - a.occurrenceCount ||
        compareRaw(a.lemma, b.lemma) ||
        compareRaw(a.pos, b.pos),
    )

  return { root, occurrenceCount: countRow, occurrences, lemmas }
}

/**
 * Build the Quran-wide word-search index without a words⋈ayahs JOIN.
 *
 * Ayah text/translation and surah names are loaded once and shared by
 * reference across every word in that ayah — much cheaper than sql.js
 * duplicating those strings on ~77k joined rows (Android builds on IO;
 * web must stay responsive on the main thread).
 */
function buildWordSearchIndex(): WordSearchIndexEntry[] {
  const ayahMeta = new Map<string, { text: string; translation: string }>()
  queryAll(
    'SELECT surah_id, ayah_number, text_uthmani, translation_en FROM ayahs',
    [],
    (r) => {
      ayahMeta.set(`${Number(r.surah_id)}:${Number(r.ayah_number)}`, {
        text: String(r.text_uthmani),
        translation: String(r.translation_en),
      })
      return null
    },
  )

  const surahNames = new Map<number, { en: string; ar: string }>()
  for (const s of surahs()) {
    surahNames.set(s.id, { en: s.nameTransliteration, ar: s.nameArabic })
  }

  const index: WordSearchIndexEntry[] = []
  queryAll(
    `SELECT w.surah_id, w.ayah_number, w.position, w.arabic, w.translation_en,
            w.transliteration, COALESCE(m.root, '') AS root
     FROM words w
     LEFT JOIN word_morphology m
       ON m.surah_id = w.surah_id AND m.ayah_number = w.ayah_number AND m.position = w.position
     ORDER BY w.surah_id, w.ayah_number, w.position`,
    [],
    (r) => {
      const surahId = Number(r.surah_id)
      const ayahNumber = Number(r.ayah_number)
      const position = Number(r.position)
      const arabic = String(r.arabic)
      const runtime = runtimeMushafCache?.word(surahId, ayahNumber, position)
      const translation = runtime?.translation_en ?? String(r.translation_en)
      const transliteration = runtime?.transliteration ?? String(r.transliteration)
      const meta = ayahMeta.get(`${surahId}:${ayahNumber}`)
      const names = surahNames.get(surahId)
      index.push({
        surahId,
        ayahNumber,
        position: Number(r.position),
        arabic,
        arabicNorm: normalizeArabicForSearch(arabic),
        translation,
        translationLower: translation.toLowerCase(),
        transliteration,
        transliterationLower: transliteration.toLowerCase(),
        root: String(r.root),
        // Shared string refs — one ayah text object for every word in it.
        ayahText: meta?.text ?? '',
        ayahTranslation: meta?.translation ?? '',
        surahNameTransliteration: names?.en ?? '',
        surahNameArabic: names?.ar ?? '',
      })
      return null
    },
  )
  return index
}

interface SearchConceptAsset {
  version: number
  sourceCommit: string
  thesaurusSha256: string
  concepts: {
    n: string
    p: string[]
    s: string[]
    c: string
    d: string
    a: number[]
  }[]
  thesaurus: Record<string, [string, number][]>
}

/** Load the attributed concept and thesaurus index only when search first needs it. */
export function warmSearchConcepts(): Promise<SearchConcept[]> {
  if (searchConcepts) return Promise.resolve(searchConcepts)
  if (searchConceptPromise) return searchConceptPromise
  searchConceptPromise = fetch(assetUrl('search_concepts.json'))
    .then((response) => {
      if (!response.ok) throw new Error(`Concept search asset: HTTP ${response.status}`)
      return response.json() as Promise<SearchConceptAsset>
    })
    .then((asset) => {
      if (
        asset.version !== 2 ||
        asset.sourceCommit !== 'cb3852b127bfdda6668c5eec9e5c1d9cdcde3810' ||
        asset.thesaurusSha256 !==
          '38b16326159f51853626b7d24a44c453fa88ab33f06fce5ec8fc5996d1c2be93'
      ) {
        throw new Error(`Unsupported concept search asset ${asset.version}/${asset.sourceCommit}`)
      }
      searchConcepts = asset.concepts.map((concept) => ({
        name: concept.n,
        primaryTerms: concept.p,
        secondaryTerms: concept.s,
        category: concept.c,
        domain: concept.d,
        ayahKeys: concept.a,
      }))
      searchThesaurus = new Map(
        Object.entries(asset.thesaurus).map(([query, related]) => [
          query,
          related.map(([text, distance]) => ({ text, distance })),
        ]),
      )
      return searchConcepts
    })
    .catch(() => {
      searchConceptPromise = null
      return []
    })
  return searchConceptPromise
}

function wordSearchIndexRows(): WordSearchIndexEntry[] {
  if (wordSearchIndex) return wordSearchIndex
  wordSearchIndex = buildWordSearchIndex()
  return wordSearchIndex
}

/** Drop views that embed runtime word content after an atomic cache update. */
export function invalidateRuntimeMushafViews(): void {
  surahContentCache.clear()
  wordSearchIndex = null
  wordSearchIndexPromise = null
}

/**
 * Build the word-search index on demand after the user starts a word query.
 * It is intentionally not warmed at boot: chapter taps take priority over a
 * full-Quran scan. Safe to call repeatedly; concurrent callers share a build
 * promise.
 */
export function warmWordSearchIndex(): Promise<WordSearchIndexEntry[]> {
  if (wordSearchIndex) return Promise.resolve(wordSearchIndex)
  if (wordSearchIndexPromise) return wordSearchIndexPromise
  wordSearchIndexPromise = new Promise((resolve) => {
    // Let the loading ink paint, then honor the active query immediately.
    setTimeout(() => {
      try {
        resolve(wordSearchIndexRows())
      } catch {
        wordSearchIndexPromise = null
        resolve([])
      }
    }, 0)
  })
  return wordSearchIndexPromise
}

/**
 * Quran-wide word search for the cover sheet. Blank / too-short /
 * `surah:ayah` queries yield an empty list (caller should gate with
 * `shouldRunWordSearch`).
 */
export function searchWords(
  query: string,
  sources?: import('../domain/WordSearch').WordSearchSources,
): WordSearchHit[] {
  if (!shouldRunWordSearch(query)) return []
  return matchWordSearch(
    wordSearchIndexRows(),
    query,
    WORD_SEARCH_MAX_HITS,
    searchConcepts ?? [],
    searchThesaurus,
    sources,
  )
}

/**
 * Async cover-sheet search — yields during the scan and honours cancellation
 * so rapid typing does not stack main-thread work (Android `collectLatest`).
 */
export async function searchWordsAsync(
  query: string,
  isCancelled: () => boolean = () => false,
  sources?: import('../domain/WordSearch').WordSearchSources,
): Promise<WordSearchHit[]> {
  if (!shouldRunWordSearch(query)) return []
  const [index, concepts] = await Promise.all([warmWordSearchIndex(), warmSearchConcepts()])
  if (isCancelled()) return []
  return matchWordSearchAsync(
    index,
    query,
    WORD_SEARCH_MAX_HITS,
    isCancelled,
    concepts,
    searchThesaurus,
    sources,
  )
}

export const QuranRepository = {
  ensureReady,
  surahs,
  reciters,
  surahContent,
  preloadAllSurahContent,
  timings,
  parseSegments,
  wordMorphology,
  rootSummary,
  searchWords,
  searchWordsAsync,
  invalidateRuntimeMushafViews,
  warmWordSearchIndex,
  warmSearchConcepts,
}
