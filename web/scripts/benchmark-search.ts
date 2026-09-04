import { readFileSync } from 'node:fs'
import initSqlJs from 'sql.js/dist/sql-asm.js'
import {
  matchWordSearch,
  normalizeArabicForSearch,
  type RelatedSearchTerm,
  type SearchConcept,
  type WordSearchIndexEntry,
  type WordSearchSources,
} from '../src/domain/WordSearch'

const SQL = await initSqlJs()
const db = new SQL.Database(readFileSync('../data/quran.db'))

const rows = <T extends Record<string, unknown>>(sql: string): T[] => {
  const statement = db.prepare(sql)
  const result: T[] = []
  while (statement.step()) result.push(statement.getAsObject() as T)
  statement.free()
  return result
}

const ayahs = new Map(
  rows<{ surah_id: number; ayah_number: number; text_uthmani: string; translation_en: string }>(
    'SELECT surah_id, ayah_number, text_uthmani, translation_en FROM ayahs',
  ).map((row) => [`${row.surah_id}:${row.ayah_number}`, row]),
)
const surahs = new Map(
  rows<{ id: number; name_transliteration: string; name_arabic: string }>(
    'SELECT id, name_transliteration, name_arabic FROM surahs',
  ).map((row) => [row.id, row]),
)
const index: WordSearchIndexEntry[] = rows<{
  surah_id: number
  ayah_number: number
  position: number
  arabic: string
  translation_en: string
  transliteration: string
  root: string
}>(`SELECT w.surah_id, w.ayah_number, w.position, w.arabic, w.translation_en,
           w.transliteration, COALESCE(m.root, '') AS root
    FROM words w
    LEFT JOIN word_morphology m
      ON m.surah_id = w.surah_id AND m.ayah_number = w.ayah_number AND m.position = w.position
    ORDER BY w.surah_id, w.ayah_number, w.position`).map((row) => {
  const ayah = ayahs.get(`${row.surah_id}:${row.ayah_number}`)!
  const surah = surahs.get(row.surah_id)!
  return {
    surahId: row.surah_id,
    ayahNumber: row.ayah_number,
    position: row.position,
    arabic: row.arabic,
    arabicNorm: normalizeArabicForSearch(row.arabic),
    translation: row.translation_en,
    translationLower: row.translation_en.toLowerCase(),
    transliteration: row.transliteration,
    transliterationLower: row.transliteration.toLowerCase(),
    root: row.root,
    ayahText: ayah.text_uthmani,
    ayahTranslation: ayah.translation_en,
    surahNameTransliteration: surah.name_transliteration,
    surahNameArabic: surah.name_arabic,
  }
})

const asset = JSON.parse(readFileSync('../data/search_concepts.json', 'utf8')) as {
  concepts: { n: string; p: string[]; s: string[]; c: string; d: string; a: number[] }[]
  thesaurus: Record<string, [string, number][]>
}
const concepts: SearchConcept[] = asset.concepts.map((concept) => ({
  name: concept.n,
  primaryTerms: concept.p,
  secondaryTerms: concept.s,
  category: concept.c,
  domain: concept.d,
  ayahKeys: concept.a,
}))
const thesaurus = new Map<string, RelatedSearchTerm[]>(
  Object.entries(asset.thesaurus).map(([term, related]) => [
    term,
    related.map(([text, distance]) => ({ text, distance })),
  ]),
)

const scroll: WordSearchSources = {
  arabic: false,
  wordGloss: true,
  transliteration: false,
  verseTranslation: false,
}
const mushaf: WordSearchSources = {
  arabic: false,
  wordGloss: false,
  transliteration: false,
  verseTranslation: true,
}
const cases: [string, WordSearchSources][] = [
  ['peace', scroll],
  ['calm', scroll],
  ['saving money', scroll],
  ['corrupy', scroll],
  ['"day of judgment"', scroll],
  ['hell', mushaf],
]

const medians: number[] = []
console.log(`${index.length.toLocaleString()} word rows · ${concepts.length} concepts`)
for (const [query, sources] of cases) {
  matchWordSearch(index, query, 400, concepts, thesaurus, sources)
  const times: number[] = []
  let hits = 0
  for (let repeat = 0; repeat < 9; repeat++) {
    const start = performance.now()
    hits = matchWordSearch(index, query, 400, concepts, thesaurus, sources).length
    times.push(performance.now() - start)
  }
  times.sort((a, b) => a - b)
  const median = times[4]!
  medians.push(median)
  console.log(`${query.padEnd(18)} ${median.toFixed(1).padStart(7)} ms  ${hits} hits`)
}
const suiteMean = medians.reduce((sum, ms) => sum + ms, 0) / medians.length
console.log(`suite mean${suiteMean.toFixed(1).padStart(13)} ms`)
