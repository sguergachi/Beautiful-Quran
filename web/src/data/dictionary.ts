/**
 * English Wiktionary Arabic senses, in their own SQLite asset.
 *
 * Kept out of quran.db / lexicon.db: lemma-keyed, ~1 MB, fetched the first
 * time a reader opens a root. Soft-null on every failure path.
 */
import type { Database } from 'sql.js'
import { assetUrl } from '../assetUrl'
import { fetchBytes, loadSqlJs } from './database'

export interface DictionarySenseGroup {
  pos: string
  glosses: string[]
}

export interface DictionaryEntry {
  lemma: string
  /** Wiktionary headword that matched this lemma. */
  word: string
  groups: DictionarySenseGroup[]
  /** CC-BY-SA credit line carried with the extract. */
  credit: string
}

let dictionary: Database | null = null
let loadPromise: Promise<Database | null> | null = null
let credit = ''

async function openDictionary(): Promise<Database | null> {
  if (dictionary) return dictionary
  if (loadPromise) return loadPromise
  loadPromise = (async () => {
    try {
      const sql = await loadSqlJs()
      const buf = await fetchBytes(assetUrl('dictionary.db'))
      dictionary = new sql.Database(new Uint8Array(buf))
      const meta = dictionary.exec("SELECT value FROM meta WHERE key = 'credit'")
      credit = String(meta[0]?.values?.[0]?.[0] ?? '')
      return dictionary
    } catch {
      loadPromise = null
      return null
    }
  })()
  return loadPromise
}

export function parseDictionaryPayload(json: string): DictionarySenseGroup[] {
  if (!json.trim()) return []
  try {
    const parsed = JSON.parse(json) as Array<{ pos?: unknown; glosses?: unknown }>
    if (!Array.isArray(parsed)) return []
    return parsed.flatMap((group) => {
      const pos = String(group.pos ?? '')
      const glosses = Array.isArray(group.glosses)
        ? group.glosses.map((g) => String(g).trim()).filter(Boolean)
        : []
      return glosses.length ? [{ pos, glosses }] : []
    })
  } catch {
    return []
  }
}

/** Wiktionary senses for a QAC lemma, or null when unavailable. */
export async function dictionaryEntry(lemma: string): Promise<DictionaryEntry | null> {
  if (!lemma) return null
  const db = await openDictionary()
  if (!db) return null
  try {
    const stmt = db.prepare('SELECT word, payload FROM lemma_entries WHERE lemma = ?')
    stmt.bind([lemma])
    const found = stmt.step()
    const row = found ? (stmt.getAsObject() as { word: unknown; payload: unknown }) : null
    stmt.free()
    if (!row) return null
    const groups = parseDictionaryPayload(String(row.payload ?? ''))
    if (!groups.length) return null
    return {
      lemma,
      word: String(row.word ?? lemma),
      groups,
      credit,
    }
  } catch {
    return null
  }
}

/** Test seam — drops the cached asset so a test can re-open it. */
export function _resetDictionaryForTests(): void {
  dictionary = null
  loadPromise = null
  credit = ''
}
