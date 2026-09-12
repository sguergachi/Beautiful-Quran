/**
 * WASM SQLite wrapper over the committed quran.db asset.
 *
 * Vite resolves `sql.js` to `sql-wasm-browser.js`, which asks locateFile for
 * `sql-wasm-browser.wasm` (not `sql-wasm.wasm`). Both names are shipped under
 * `public/` — they are identical binaries from sql.js.
 *
 * Load order:
 *   1. Local WASM (wasmBinary + locateFile under the app base path)
 *   2. Bundled sql.js asm.js build (no WASM — fallback when wasm is blocked)
 */
import initSqlJsWasm, { type Database, type SqlJsStatic } from 'sql.js'
import { assetUrl } from '../assetUrl'

let SQL: SqlJsStatic | null = null
let db: Database | null = null
let loadPromise: Promise<Database> | null = null

export type LoadPhase = 'wasm' | 'asm' | 'db'
export type LoadProgress = { loaded: number; total: number; phase: LoadPhase }

/** Filenames the browser sql.js build may request via locateFile. */
export const LOCAL_WASM_CANDIDATES = [
  'sql-wasm-browser.wasm',
  'sql-wasm.wasm',
] as const

/**
 * Map a sql.js locateFile request onto a same-origin asset URL.
 * Unknown names still go through assetUrl so CDN/local paths stay consistent.
 */
export function resolveWasmAsset(file: string): string {
  const name = file.replace(/^\.\//, '').split('/').pop() || file
  if (
    name === 'sql-wasm-browser.wasm' ||
    name === 'sql-wasm.wasm' ||
    name.endsWith('.wasm')
  ) {
    return assetUrl(name)
  }
  return assetUrl(name)
}

/** Async fetch with optional Content-Length progress. */
export async function fetchBytesAsync(
  url: string,
  onProgress?: (loaded: number, total: number) => void,
): Promise<ArrayBuffer> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`Failed to fetch ${url} (${res.status})`)
  const total = Number(res.headers.get('content-length') || 0)
  if (!res.body || !onProgress) return res.arrayBuffer()

  const reader = res.body.getReader()
  const chunks: Uint8Array[] = []
  let loaded = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    chunks.push(value)
    loaded += value.byteLength
    onProgress(loaded, total || loaded)
  }
  const out = new Uint8Array(loaded)
  let offset = 0
  for (const chunk of chunks) {
    out.set(chunk, offset)
    offset += chunk.byteLength
  }
  return out.buffer
}

/**
 * Last-resort same-origin (or CORS) byte load via synchronous XHR.
 * Used only when async fetch fails — e.g. flaky mobile networks mid-stream.
 */
export function fetchBytesSync(url: string): ArrayBuffer {
  const xhr = new XMLHttpRequest()
  xhr.open('GET', url, /* async */ false)
  xhr.responseType = 'arraybuffer'
  xhr.send(null)
  if (xhr.status !== 200 && xhr.status !== 0) {
    throw new Error(`Sync fetch failed for ${url} (${xhr.status})`)
  }
  const buf = xhr.response as ArrayBuffer | null
  if (!buf || buf.byteLength === 0) {
    throw new Error(`Sync fetch returned empty body for ${url}`)
  }
  return buf
}

/** Prefer async fetch; fall back to sync XHR if it fails. */
export async function fetchBytes(
  url: string,
  onProgress?: (loaded: number, total: number) => void,
): Promise<ArrayBuffer> {
  try {
    return await fetchBytesAsync(url, onProgress)
  } catch (asyncErr) {
    try {
      return fetchBytesSync(url)
    } catch (syncErr) {
      const asyncMsg = asyncErr instanceof Error ? asyncErr.message : String(asyncErr)
      const syncMsg = syncErr instanceof Error ? syncErr.message : String(syncErr)
      throw new Error(`Failed to load ${url} (async: ${asyncMsg}; sync: ${syncMsg})`)
    }
  }
}

/** Load the first available local wasm binary (browser name first). */
export async function fetchLocalWasmBinary(
  onProgress?: (loaded: number, total: number) => void,
): Promise<ArrayBuffer> {
  const errors: string[] = []
  for (const name of LOCAL_WASM_CANDIDATES) {
    const url = assetUrl(name)
    try {
      return await fetchBytes(url, onProgress)
    } catch (e) {
      errors.push(`${name}: ${e instanceof Error ? e.message : String(e)}`)
    }
  }
  throw new Error(`No local sql.js wasm found (${errors.join('; ')})`)
}

async function initWasmLocal(
  onProgress?: (p: LoadProgress) => void,
): Promise<SqlJsStatic> {
  onProgress?.({ loaded: 0, total: 0, phase: 'wasm' })
  const wasmBinary = await fetchLocalWasmBinary((loaded, total) => {
    onProgress?.({ loaded, total, phase: 'wasm' })
  })
  // Pass both wasmBinary and locateFile: if the runtime still asks for a
  // filename, resolveWasmAsset serves the correctly named public asset.
  return initSqlJsWasm({
    wasmBinary,
    locateFile: resolveWasmAsset,
  })
}

async function initAsm(
  onProgress?: (p: LoadProgress) => void,
): Promise<SqlJsStatic> {
  onProgress?.({ loaded: 0, total: 0, phase: 'asm' })
  // Dynamic import so the asm build is only downloaded when WASM is unusable.
  const mod = await import('sql.js/dist/sql-asm.js')
  const initAsmJs = (mod.default ?? mod) as typeof initSqlJsWasm
  return initAsmJs()
}

/**
 * Resolve a working sql.js runtime without contacting a third-party CDN.
 */
export async function loadSqlJs(
  onProgress?: (p: LoadProgress) => void,
): Promise<SqlJsStatic> {
  if (SQL) return SQL

  const errors: string[] = []

  try {
    SQL = await initWasmLocal(onProgress)
    return SQL
  } catch (e) {
    errors.push(`local-wasm: ${e instanceof Error ? e.message : String(e)}`)
  }

  try {
    SQL = await initAsm(onProgress)
    return SQL
  } catch (e) {
    errors.push(`asm: ${e instanceof Error ? e.message : String(e)}`)
  }

  throw new Error(`sql.js failed to initialize (${errors.join('; ')})`)
}

/** Open (or return) the read-only Quran database. Cached after first load. */
export async function openDatabase(
  dbUrl = assetUrl('quran.db'),
  onProgress?: (p: LoadProgress) => void,
): Promise<Database> {
  if (db) return db
  if (loadPromise) return loadPromise
  loadPromise = (async () => {
    try {
      const sql = await loadSqlJs(onProgress)
      onProgress?.({ loaded: 0, total: 0, phase: 'db' })
      const buf = await fetchBytes(dbUrl, (loaded, total) => {
        onProgress?.({ loaded, total, phase: 'db' })
      })
      db = new sql.Database(new Uint8Array(buf))
      return db
    } catch (e) {
      // Allow a later retry after a transient failure.
      loadPromise = null
      throw e
    }
  })()
  return loadPromise
}

export function getDatabase(): Database {
  if (!db) throw new Error('Database not open — call openDatabase() first')
  return db
}

export function queryAll<T>(
  sql: string,
  params: (string | number)[] = [],
  map: (row: Record<string, unknown>) => T,
): T[] {
  const database = getDatabase()
  const stmt = database.prepare(sql)
  stmt.bind(params)
  const out: T[] = []
  while (stmt.step()) {
    out.push(map(stmt.getAsObject() as Record<string, unknown>))
  }
  stmt.free()
  return out
}

export function queryOne<T>(
  sql: string,
  params: (string | number)[] = [],
  map: (row: Record<string, unknown>) => T,
): T | null {
  const rows = queryAll(sql, params, map)
  return rows[0] ?? null
}

/** Test helper — reset module singletons between Vitest cases. */
export function _resetDatabaseForTests(): void {
  SQL = null
  db = null
  loadPromise = null
}
