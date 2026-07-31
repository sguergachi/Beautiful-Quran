/**
 * Lane's *Arabic-English Lexicon*, in its own SQLite asset.
 *
 * Kept out of quran.db deliberately: it is ~20 MB, and nobody should download
 * it to read the mushaf. The asset is fetched the first time a reader opens a
 * root, then cached for the session. Every failure path is soft — a Root
 * Viewer without Lane is the graceful degradation, never a broken screen.
 */
import type { Database } from 'sql.js'
import { assetUrl } from '../assetUrl'
import { fetchBytes, loadSqlJs } from './database'

export interface LexiconEntry {
  root: string
  /** Display text: English prose with Arabic inline, Lane's own divisions. */
  text: string
  /** First printed page of the article, or 0 when the source records none. */
  page: number
  /** Perseus' required credit line, carried with their text. */
  credit: string
}

let lexicon: Database | null = null
let loadPromise: Promise<Database | null> | null = null
let credit = ''

/** Fetch and open the lexicon once per session; null when unavailable. */
async function openLexicon(): Promise<Database | null> {
  if (lexicon) return lexicon
  if (loadPromise) return loadPromise
  loadPromise = (async () => {
    try {
      const sql = await loadSqlJs()
      const buf = await fetchBytes(assetUrl('lexicon.db'))
      lexicon = new sql.Database(new Uint8Array(buf))
      const meta = lexicon.exec("SELECT value FROM meta WHERE key = 'credit'")
      credit = String(meta[0]?.values?.[0]?.[0] ?? '')
      return lexicon
    } catch {
      // Asset missing or unreadable — the section simply does not appear.
      loadPromise = null
      return null
    }
  })()
  return loadPromise
}

/** Lane's article for a QAC root, or null when he has none for it. */
export async function lexiconEntry(root: string): Promise<LexiconEntry | null> {
  if (!root) return null
  const db = await openLexicon()
  if (!db) return null
  try {
    const stmt = db.prepare('SELECT entry, page FROM root_entries WHERE root = ?')
    stmt.bind([root])
    const found = stmt.step()
    const row = found ? (stmt.getAsObject() as { entry: unknown; page: unknown }) : null
    stmt.free()
    if (!row) return null
    const text = String(row.entry ?? '')
    if (!text) return null
    return { root, text, page: Number(row.page ?? 0), credit }
  } catch {
    return null
  }
}

/** Test seam — drops the cached asset so a test can re-open it. */
export function _resetLexiconForTests(): void {
  lexicon = null
  loadPromise = null
  credit = ''
}
