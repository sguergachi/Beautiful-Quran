import { queryAll } from './database'
import {
  normalizeQfMushaf,
  type RuntimeMushafWord,
  type StoredQfResource,
} from './qfMushafMapper'

export { assertQcfV2Runs, type RuntimeMushafWord } from './qfMushafMapper'

export const QF_MAX_CACHE_AGE_MS = 7 * 24 * 60 * 60 * 1_000
export const QF_REVALIDATE_AFTER_MS = 6 * 24 * 60 * 60 * 1_000

export interface RuntimeCacheStatus {
  phase: 'empty' | 'fresh' | 'refresh_due' | 'expired' | 'refreshing' | 'error'
  updatedAtMs: number | null
  refreshAtMs: number | null
  expiresAtMs: number | null
  apiCalls: number
  lastError: string | null
  lastRefreshApiCalls?: number | null
}

const MIN_WORDS = 77_429
const MAX_RESPONSE_CHARS = 40 * 1024 * 1024
const ALL_QCF_PAGES = Array.from({ length: 604 }, (_, index) => index + 1)
const QF_RESOURCES = 'mushafs:1;word_by_word_translations:59;word_by_word_transliterations:60'
const SUPPLEMENT_VERSES = ['1:1', '2:181', '8:6', '9:1', '36:52'] as const

export interface StoredMushaf {
  id: 1
  token: string
  updatedAtMs: number
  lastRefreshApiCalls?: number | null
  resources: StoredQfResource[]
  records: RuntimeMushafWord[]
}

export interface RuntimeMushafStore {
  get(): Promise<StoredMushaf | null>
  put(resource: StoredMushaf): Promise<void>
  clear(): Promise<void>
}

class MushafStore implements RuntimeMushafStore {
  private database: Promise<IDBDatabase> | null = null

  async get(): Promise<StoredMushaf | null> {
    const db = await this.open()
    return new Promise((resolve, reject) => {
      const request = db.transaction('resources').objectStore('resources').get(1)
      request.onsuccess = () => resolve(request.result ?? null)
      request.onerror = () => reject(request.error)
    })
  }

  async put(resource: StoredMushaf): Promise<void> {
    const db = await this.open()
    const transaction = db.transaction('resources', 'readwrite')
    transaction.objectStore('resources').put(resource)
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve()
      transaction.onerror = () => reject(transaction.error)
      transaction.onabort = () => reject(transaction.error)
    })
  }

  async clear(): Promise<void> {
    const db = await this.open()
    const transaction = db.transaction('resources', 'readwrite')
    transaction.objectStore('resources').delete(1)
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve()
      transaction.onerror = () => reject(transaction.error)
      transaction.onabort = () => reject(transaction.error)
    })
  }

  private open(): Promise<IDBDatabase> {
    if (this.database) return this.database
    this.database = new Promise((resolve, reject) => {
      const request = indexedDB.open('beautiful-quran-qf-mushaf-v3', 1)
      request.onupgradeneeded = () => request.result.createObjectStore('resources', { keyPath: 'id' })
      request.onsuccess = () => {
        resolve(request.result)
        // A separate name cannot be blocked by an old tab holding v1 open.
        for (const name of ['beautiful-quran-qf-mushaf-v1', 'beautiful-quran-qf-mushaf-v2']) {
          const stale = indexedDB.deleteDatabase(name)
          stale.onerror = () => undefined
        }
      }
      request.onerror = () => reject(request.error)
    })
    return this.database
  }
}

/** Browser-side seven-day cache for all authenticated QF word/QCF fields. */
export class RuntimeMushafCache {
  private readonly baseUrl: string
  private resource: StoredMushaf | null = null
  private state: StoredMushaf | null = null
  private inFlight: Promise<boolean> | null = null
  private readonly listeners = new Set<() => void>()
  private readonly diagnosticListeners = new Set<() => void>()
  private error: string | null = null
  private blockReadRefresh = false
  private apiCalls = 0
  private requestsSettled = false
  private progressCompleted = 0
  private progressTotal = 0
  private byKey = new Map<string, RuntimeMushafWord>()
  private refreshTimer: number | null = null
  private expiryTimer: number | null = null

  constructor(
    baseUrl: string,
    private readonly store: RuntimeMushafStore = new MushafStore(),
    private readonly fetchImpl: typeof fetch = (input, init) => fetch(input, init),
    private readonly now: () => number = Date.now,
    private readonly minimumRecords = MIN_WORDS,
    private readonly loadCanonical: () => Map<string, string[]> = loadCanonicalWords,
    private readonly expectedQcfPages: readonly number[] = ALL_QCF_PAGES,
  ) {
    if (new URL(baseUrl).protocol !== 'https:') throw new Error('Content API must use HTTPS')
    this.baseUrl = baseUrl.replace(/\/$/, '')
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  subscribeDiagnostics(listener: () => void): () => void {
    this.diagnosticListeners.add(listener)
    return () => this.diagnosticListeners.delete(listener)
  }

  status(now = this.now()): RuntimeCacheStatus {
    const updated = this.state?.updatedAtMs ?? null
    return {
      phase: this.inFlight ? 'refreshing' : this.error ? 'error' : updated == null ? 'empty'
        : !fresh(updated, now) ? 'expired' : refreshDue(updated, now) ? 'refresh_due' : 'fresh',
      updatedAtMs: updated,
      refreshAtMs: updated == null ? null : updated + QF_REVALIDATE_AFTER_MS,
      expiresAtMs: updated == null ? null : updated + QF_MAX_CACHE_AGE_MS,
      apiCalls: this.apiCalls,
      lastError: this.error,
      lastRefreshApiCalls: this.state?.lastRefreshApiCalls ?? null,
    }
  }

  haveRequestsSettled(): boolean {
    return this.requestsSettled
  }

  downloadProgress(): { completed: number; total: number; fraction: number } | null {
    return this.progressTotal > 0
      ? {
          completed: this.progressCompleted,
          total: this.progressTotal,
          fraction: this.progressCompleted / this.progressTotal,
        }
      : null
  }

  async restore(): Promise<void> {
    try {
      const saved = await this.store.get()
      if (saved) {
        validateCacheState(saved)
        if (fresh(saved.updatedAtMs, this.now())) {
          this.state = validateStored(saved, this.minimumRecords)
          this.install(saved)
        } else {
          this.state = withoutExpiringSupplements(saved)
          await this.store.put(this.state)
        }
      }
    } catch { /* A corrupt local cache is a miss. */ }
    this.notifyDiagnostics()
    if (!this.state || !fresh(this.state.updatedAtMs, this.now())) {
      await this.refresh()
    } else if (refreshDue(this.state.updatedAtMs, this.now())) {
      await this.refresh()
    }
  }

  refreshIfNeeded(): Promise<boolean> {
    this.blockReadRefresh = false
    return this.state && !refreshDue(this.state.updatedAtMs, this.now())
      ? Promise.resolve(false) : this.refresh()
  }

  refresh(): Promise<boolean> {
    if (this.inFlight) return this.inFlight
    this.blockReadRefresh = false
    this.error = null
    this.requestsSettled = false
    this.progressCompleted = 0
    this.progressTotal = 0
    this.inFlight = this.sync().then(() => true).catch(async (error: unknown) => {
      this.error = error instanceof Error ? error.message : String(error)
      this.blockReadRefresh = true
      if (error instanceof AccessRevoked) {
        await this.store.clear()
        this.state = null
        this.resource = null
        this.byKey.clear()
        for (const listener of this.listeners) listener()
      }
      return false
    }).finally(() => {
      this.inFlight = null
      this.notifyDiagnostics()
    })
    this.notifyDiagnostics()
    return this.inFlight
  }

  word(surah: number, ayah: number, position: number): RuntimeMushafWord | null {
    if (!this.blockReadRefresh && (!this.state || refreshDue(this.state.updatedAtMs, this.now()))) {
      void this.refresh()
    }
    if (!this.resource || !fresh(this.resource.updatedAtMs, this.now())) return null
    return this.byKey.get(`${surah}:${ayah}:${position}`) ?? null
  }

  private async sync(): Promise<void> {
    const callsBefore = this.apiCalls
    const incremental = this.state
      ? `/api/v4/resources/sync?sync_token=${encodeURIComponent(this.state.token)}&resources=${encodeURIComponent(QF_RESOURCES)}`
      : null
    const bootstrap = `/api/v4/resources/sync?bootstrap=true&resources=${encodeURIComponent(QF_RESOURCES)}`
    let next: StoredMushaf
    try {
      next = await this.syncFrom(incremental ?? bootstrap, callsBefore)
    } catch (error) {
      if (!(error instanceof ResyncRequired) || !incremental) throw error
      next = await this.syncFrom(bootstrap, callsBefore)
    }
    this.markRequestsSettled()
    await this.store.put(next)
    this.state = next
    this.error = null
    this.install(next)
  }

  private async syncFrom(firstPath: string, callsBefore: number): Promise<StoredMushaf> {
    let resources = firstPath.includes('bootstrap=true')
      ? [] : cloneResources(this.state?.resources ?? [])
    let changed = !this.state
    let path = firstPath
    let token: string | null = null
    for (let page = 0; page < 100; page += 1) {
      const sync = object(object(await this.get(path)).sync)
      const mutations = array(sync.mutations).map(object)
      const snapshotCount = mutations.filter((mutation) =>
        mutation.type === 'RESOURCE_CREATE' || mutation.type === 'RESOURCE_INVALIDATE').length
      this.progressTotal = 1 + snapshotCount + SUPPLEMENT_VERSES.length
      this.progressCompleted = 1
      this.notifyDiagnostics()
      for (const mutation of mutations) {
        mutationResource(mutation)
        const type = String(mutation.type)
        if (type === 'RESOURCE_CREATE' || type === 'RESOURCE_INVALIDATE') {
          const snapshot = object(await this.get(relativePath(mutation.snapshot_url)))
          resources = replaceSnapshot(resources, snapshot, mutation)
          changed = true
          this.progressCompleted += 1
          this.notifyDiagnostics()
        } else if (type === 'RESOURCE_DELETE') {
          resources = deleteResource(resources, mutation)
          changed = true
        }
        else if (type === 'ROW_CREATE' || type === 'ROW_UPDATE') {
          resources = upsertResourceRow(resources, mutation)
          changed = true
        } else if (type === 'ROW_DELETE') {
          resources = deleteResourceRow(resources, mutation)
          changed = true
        } else if (type !== 'RESOURCE_UPDATE') throw new Error('Unsupported Content Sync mutation')
      }
      if (sync.next_page_url != null) {
        path = relativePath(sync.next_page_url)
        continue
      }
      token = typeof sync.next_sync_token === 'string' ? sync.next_sync_token : null
      break
    }
    if (!token) throw new Error('Content Sync response has no final checkpoint')
    const supplements = await Promise.all(SUPPLEMENT_VERSES.map(async (verseKey) => {
      const response = object(await this.get(`/api/v4/verses/by_key/${verseKey}?words=true&language=en`))
      this.progressCompleted += 1
      this.notifyDiagnostics()
      return wordSupplements(verseKey, response)
    }))
    resources = replaceLocalResource(resources, 'word_supplements', 1, supplements.flat())
    changed = true
    const records = changed
      ? normalizeQfMushaf(this.loadCanonical(), resources, this.expectedQcfPages)
      : this.state!.records
    return validateStored({
      id: 1,
      token,
      updatedAtMs: this.now(),
      lastRefreshApiCalls: this.apiCalls - callsBefore,
      resources,
      records,
    }, this.minimumRecords)
  }

  private install(resource: StoredMushaf) {
    this.resource = resource
    this.byKey = new Map(resource.records.map((row) => [row.record_key, row]))
    this.scheduleChecks(resource)
    for (const listener of this.listeners) listener()
  }

  private scheduleChecks(resource: StoredMushaf) {
    if (typeof window === 'undefined') return
    if (this.refreshTimer != null) window.clearTimeout(this.refreshTimer)
    if (this.expiryTimer != null) window.clearTimeout(this.expiryTimer)
    const refreshDelay = resource.updatedAtMs + QF_REVALIDATE_AFTER_MS - this.now()
    if (refreshDelay > 0) {
      this.refreshTimer = window.setTimeout(() => void this.refresh(), refreshDelay)
    }
    const expiryDelay = Math.max(0, resource.updatedAtMs + QF_MAX_CACHE_AGE_MS - this.now()) + 1
    this.expiryTimer = window.setTimeout(() => {
      if (this.resource !== resource || fresh(resource.updatedAtMs, this.now())) return
      const expired = withoutExpiringSupplements(resource)
      this.state = expired
      this.resource = null
      this.byKey.clear()
      for (const listener of this.listeners) listener()
      void this.store.put(expired).then(() => this.refreshIfNeeded())
    }, expiryDelay)
  }

  private async get(path: string): Promise<unknown> {
    this.apiCalls += 1
    this.requestsSettled = false
    this.notifyDiagnostics()
    const response = await this.fetchImpl(this.requestUrl(path), {
      headers: { accept: 'application/json' },
      signal: AbortSignal.timeout(180_000),
    })
    const declared = Number(response.headers.get('content-length'))
    if (Number.isFinite(declared) && declared > MAX_RESPONSE_CHARS) {
      throw new Error('Content API response exceeded size limit')
    }
    const body = await response.text()
    if (body.length > MAX_RESPONSE_CHARS) throw new Error('Content API response exceeded size limit')
    const parsed = JSON.parse(body) as unknown
    if (response.status === 410 && object(object(parsed).error).code === 'resync_required') {
      throw new ResyncRequired()
    }
    if (response.status === 403 && object(object(parsed).error).code === 'qf_access_revoked') {
      throw new AccessRevoked('QF content access was revoked')
    }
    if (!response.ok) throw new Error(`Content API returned ${response.status}`)
    return parsed
  }

  private markRequestsSettled() {
    this.requestsSettled = true
    this.notifyDiagnostics()
  }

  private requestUrl(path: string): string {
    return this.baseUrl + relativePath(path)
  }

  private notifyDiagnostics() {
    for (const listener of this.diagnosticListeners) listener()
  }
}

function loadCanonicalWords(): Map<string, string[]> {
  const canonical = new Map<string, string[]>()
  queryAll(
    'SELECT surah_id,ayah_number,arabic FROM words ORDER BY surah_id,ayah_number,position',
    [],
    (row) => {
      const key = `${row.surah_id}:${row.ayah_number}`
      const words = canonical.get(key) ?? []
      words.push(String(row.arabic))
      canonical.set(key, words)
      return null
    },
  )
  return canonical
}

function cloneResources(resources: StoredQfResource[]): StoredQfResource[] {
  return resources.map((resource) => ({ ...resource, records: resource.records.slice() }))
}

function replaceSnapshot(
  resources: StoredQfResource[],
  snapshot: Record<string, unknown>,
  mutation: Record<string, unknown>,
): StoredQfResource[] {
  const group = String(snapshot.resource_group)
  const id = integer(snapshot.resource_id, 'resource_id')
  if (snapshot.schema_version !== 1 || group !== mutation.resource_group || id !== mutation.resource_id ||
      !isReaderResource(group, id)) throw new Error('QF snapshot resource mismatch')
  return replaceLocalResource(resources, group, id, array(snapshot.records).map(object))
}

function replaceLocalResource(
  resources: StoredQfResource[],
  resourceGroup: string,
  resourceId: number,
  records: Record<string, unknown>[],
): StoredQfResource[] {
  return resources.filter((resource) =>
    resource.resourceGroup !== resourceGroup || resource.resourceId !== resourceId)
    .concat({ resourceGroup, resourceId, records })
}

function deleteResource(resources: StoredQfResource[], mutation: Record<string, unknown>) {
  const [group, id] = mutationResource(mutation)
  return resources.filter((resource) => resource.resourceGroup !== group || resource.resourceId !== id)
}

function upsertResourceRow(resources: StoredQfResource[], mutation: Record<string, unknown>) {
  const [group, id] = mutationResource(mutation)
  const type = string(mutation.record_type, 'record_type')
  const key = string(mutation.record_key, 'record_key')
  const row = object(mutation.data)
  if (String(row.id) !== key || rowType(group, row) !== type) {
    throw new Error('QF row mutation identity mismatch')
  }
  const current = resources.find((resource) => resource.resourceGroup === group && resource.resourceId === id)
  if (!current) throw new Error(`QF resource ${group}:${id} is missing`)
  return replaceLocalResource(resources, group, id, current.records
    .filter((value) => rowType(group, value) !== type || String(value.id) !== key).concat(row))
}

function deleteResourceRow(resources: StoredQfResource[], mutation: Record<string, unknown>) {
  const [group, id] = mutationResource(mutation)
  const type = string(mutation.record_type, 'record_type')
  const key = string(mutation.record_key, 'record_key')
  const current = resources.find((resource) => resource.resourceGroup === group && resource.resourceId === id)
  if (!current) return resources
  return replaceLocalResource(resources, group, id, current.records
    .filter((value) => rowType(group, value) !== type || String(value.id) !== key))
}

function mutationResource(mutation: Record<string, unknown>): [string, number] {
  const group = string(mutation.resource_group, 'resource_group')
  const id = integer(mutation.resource_id, 'resource_id')
  if (!isReaderResource(group, id)) throw new Error('Unexpected QF resource mutation')
  return [group, id]
}

function isReaderResource(group: string, id: number) {
  return (group === 'mushafs' && id === 1) ||
    (group === 'word_by_word_translations' && id === 59) ||
    (group === 'word_by_word_transliterations' && id === 60)
}

function rowType(group: string, row: Record<string, unknown>) {
  if (group === 'word_by_word_translations') return 'word_translation'
  if (group === 'word_by_word_transliterations') return 'word_transliteration'
  return String(row.record_type)
}

function wordSupplements(verseKey: string, response: Record<string, unknown>) {
  const verse = object(response.verse)
  if (verse.verse_key !== verseKey) throw new Error('QF verse supplement mismatch')
  const rows = array(verse.words).map(object).filter((word) => word.char_type_name === 'word').map((word) => {
    const wordId = integer(word.id, 'id')
    const text = string(object(word.transliteration).text, 'text').trim()
    if (!text) throw new Error(`QF supplement omitted transliteration ${wordId}`)
    return { word_id: wordId, text }
  })
  if (!rows.length) throw new Error(`QF verse supplement ${verseKey} has no words`)
  return rows
}

class ResyncRequired extends Error {}
class AccessRevoked extends Error {}

function mushafWord(value: unknown): RuntimeMushafWord {
  const row = object(value)
  const key = `${row.surah_id}:${row.ayah_number}:${row.position}`
  const qcfValid = row.qcf_v2 === ''
    ? row.qcf_page === 0 && row.qcf_line === 0
    : Number.isInteger(row.qcf_page) && Number(row.qcf_page) >= 1 && Number(row.qcf_page) <= 604 &&
      Number.isInteger(row.qcf_line) && Number(row.qcf_line) >= 1
  if (row.record_type !== 'mushaf_word' || row.record_key !== key ||
      !Number.isInteger(row.surah_id) || Number(row.surah_id) < 1 || Number(row.surah_id) > 114 ||
      !Number.isInteger(row.ayah_number) || Number(row.ayah_number) < 1 ||
      !Number.isInteger(row.position) || Number(row.position) < 1 ||
      typeof row.translation_en !== 'string' || typeof row.transliteration !== 'string' ||
      typeof row.qcf_v2 !== 'string' || !qcfValid || !Number.isInteger(row.qcf_span_end) ||
      Number(row.qcf_span_end) < Number(row.position) || !Number.isInteger(row.ayah_page) ||
      Number(row.ayah_page) < 1 || Number(row.ayah_page) > 604) {
    throw new Error('Invalid mushaf word record')
  }
  return row as unknown as RuntimeMushafWord
}

function validateStored(value: StoredMushaf, minimum: number): StoredMushaf {
  validateCacheState(value)
  const records = value.records.map(mushafWord)
  if (records.length < minimum || new Set(records.map((row) => row.record_key)).size !== records.length) {
    throw new Error('Incomplete stored mushaf resource')
  }
  return { ...value, records }
}

function validateCacheState(value: StoredMushaf) {
  if (value.id !== 1 || !value.token || !Number.isFinite(value.updatedAtMs) ||
      !Array.isArray(value.resources) || !Array.isArray(value.records)) {
    throw new Error('Invalid stored mushaf resource')
  }
}

/** Verse lookups are ordinary API data, so never retain them past one week. */
function withoutExpiringSupplements(value: StoredMushaf): StoredMushaf {
  return {
    ...value,
    resources: value.resources.filter((resource) => resource.resourceGroup !== 'word_supplements'),
    records: [],
  }
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected object')
  return value as Record<string, unknown>
}
function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('Expected array')
  return value
}
function integer(value: unknown, name: string): number {
  if (!Number.isInteger(value)) throw new Error(`QF field ${name} is missing`)
  return Number(value)
}
function string(value: unknown, name: string): string {
  if (typeof value !== 'string') throw new Error(`QF field ${name} is missing`)
  return value
}
function relativePath(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/api/v4/') || value.startsWith('//')) {
    throw new Error('Expected relative Content API path')
  }
  return value
}
function fresh(updated: number, now: number) {
  return now - updated >= 0 && now - updated <= QF_MAX_CACHE_AGE_MS
}
function refreshDue(updated: number, now: number) {
  const age = now - updated
  return age < 0 || age > QF_REVALIDATE_AFTER_MS
}

const QF_CONTENT_BASE_URL = import.meta.env.VITE_QF_CONTENT_BASE_URL ||
  'https://beautiful-quran.sguergachi.workers.dev'

export const runtimeMushafCache = new RuntimeMushafCache(QF_CONTENT_BASE_URL)
