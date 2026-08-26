import {
  QF_MAX_CACHE_AGE_MS,
  QF_REVALIDATE_AFTER_MS,
  type RuntimeCacheStatus,
} from './runtimeTimings'

const MIN_WORDS = 77_429
const MAX_RESPONSE_CHARS = 40 * 1024 * 1024

export interface RuntimeMushafWord {
  record_type: 'mushaf_word'
  record_key: string
  surah_id: number
  ayah_number: number
  position: number
  translation_en: string
  transliteration: string
  qcf_v2: string
  qcf_page: number
  qcf_line: number
  qcf_span_end: number
  ayah_page: number
}

export interface StoredMushaf {
  id: 1
  token: string
  updatedAtMs: number
  records: RuntimeMushafWord[]
}

export interface RuntimeMushafStore {
  get(): Promise<StoredMushaf | null>
  put(resource: StoredMushaf): Promise<void>
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

  private open(): Promise<IDBDatabase> {
    if (this.database) return this.database
    this.database = new Promise((resolve, reject) => {
      const request = indexedDB.open('beautiful-quran-qf-mushaf-v1', 1)
      request.onupgradeneeded = () => request.result.createObjectStore('resources', { keyPath: 'id' })
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    return this.database
  }
}

/** Browser-side seven-day cache for all Quran.com-derived word/QCF fields. */
export class RuntimeMushafCache {
  private resource: StoredMushaf | null = null
  private state: StoredMushaf | null = null
  private inFlight: Promise<boolean> | null = null
  private readonly listeners = new Set<() => void>()
  private readonly diagnosticListeners = new Set<() => void>()
  private error: string | null = null
  private apiCalls = 0
  private byKey = new Map<string, RuntimeMushafWord>()
  private refreshTimer: number | null = null
  private expiryTimer: number | null = null

  constructor(
    private readonly baseUrl: string,
    private readonly store: RuntimeMushafStore = new MushafStore(),
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly now: () => number = Date.now,
    private readonly minimumRecords = MIN_WORDS,
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
    }
  }

  async restore(): Promise<void> {
    try {
      const saved = await this.store.get()
      if (saved) {
        this.state = validateStored(saved, this.minimumRecords)
        if (fresh(saved.updatedAtMs, this.now())) this.install(saved)
      }
    } catch { /* A corrupt local cache is a miss. */ }
    this.notifyDiagnostics()
    if (!this.state || refreshDue(this.state.updatedAtMs, this.now())) void this.refresh()
  }

  refreshIfNeeded(): Promise<boolean> {
    return this.state && !refreshDue(this.state.updatedAtMs, this.now())
      ? Promise.resolve(false) : this.refresh()
  }

  refresh(): Promise<boolean> {
    if (this.inFlight) return this.inFlight
    this.error = null
    this.inFlight = this.sync().then(() => true).catch((error: unknown) => {
      this.error = error instanceof Error ? error.message : String(error)
      return false
    }).finally(() => {
      this.inFlight = null
      this.notifyDiagnostics()
    })
    this.notifyDiagnostics()
    return this.inFlight
  }

  word(surah: number, ayah: number, position: number): RuntimeMushafWord | null {
    if (!this.state || refreshDue(this.state.updatedAtMs, this.now())) void this.refreshIfNeeded()
    if (!this.resource || !fresh(this.resource.updatedAtMs, this.now())) return null
    return this.byKey.get(`${surah}:${ayah}:${position}`) ?? null
  }

  private async sync(): Promise<void> {
    let records = this.state?.records ?? []
    const filter = encodeURIComponent('mushafs:1')
    let path = this.state
      ? `/api/v4/resources/sync?sync_token=${encodeURIComponent(this.state.token)}&resources=${filter}`
      : `/api/v4/resources/sync?bootstrap=true&resources=${filter}`
    let token: string | null = null
    let contentAge: number | null = null
    for (let page = 0; page < 100; page += 1) {
      const sync = object(object(await this.get(path)).sync)
      for (const raw of array(sync.mutations)) {
        const mutation = object(raw)
        const type = String(mutation.type)
        if (type === 'RESOURCE_CREATE' || type === 'RESOURCE_INVALIDATE') {
          const snapshot = object(await this.get(relativePath(mutation.snapshot_url)))
          if (snapshot.resource_group !== 'mushafs' || Number(snapshot.resource_id) !== 1 || snapshot.schema_version !== 1) {
            throw new Error('Mushaf snapshot resource mismatch')
          }
          records = array(snapshot.records).map(mushafWord)
        } else if (type === 'RESOURCE_DELETE') records = []
        else if (type === 'ROW_CREATE' || type === 'ROW_UPDATE') {
          const row = mushafWord(mutation.data)
          if (mutation.record_type !== row.record_type || mutation.record_key !== row.record_key) {
            throw new Error('Mushaf row mutation identity mismatch')
          }
          records = records.filter((record) => record.record_key !== row.record_key).concat(row)
        } else if (type === 'ROW_DELETE') {
          records = records.filter((record) => record.record_key !== String(mutation.record_key))
        } else if (type !== 'RESOURCE_UPDATE') throw new Error('Unsupported Content Sync mutation')
      }
      if (sync.next_page_url != null) {
        path = relativePath(sync.next_page_url)
        continue
      }
      token = typeof sync.next_sync_token === 'string' ? sync.next_sync_token : null
      contentAge = Number(sync.content_age_ms)
      break
    }
    if (!token || !Number.isInteger(contentAge) || contentAge! < 0 || contentAge! > QF_MAX_CACHE_AGE_MS) {
      throw new Error('Content Sync response has no valid final state')
    }
    const next = validateStored({
      id: 1, token, updatedAtMs: this.now() - contentAge!, records,
    }, this.minimumRecords)
    await this.store.put(next)
    this.state = next
    this.error = null
    this.install(next)
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
      this.resource = null
      this.byKey.clear()
      for (const listener of this.listeners) listener()
      void this.refreshIfNeeded()
    }, expiryDelay)
  }

  private async get(path: string): Promise<unknown> {
    this.apiCalls += 1
    this.notifyDiagnostics()
    const response = await this.fetchImpl(this.baseUrl + relativePath(path), {
      headers: { accept: 'application/json' }, signal: AbortSignal.timeout(180_000),
    })
    if (!response.ok) throw new Error(`Content API returned ${response.status}`)
    const declared = Number(response.headers.get('content-length'))
    if (Number.isFinite(declared) && declared > MAX_RESPONSE_CHARS) {
      throw new Error('Content API response exceeded size limit')
    }
    const body = await response.text()
    if (body.length > MAX_RESPONSE_CHARS) throw new Error('Content API response exceeded size limit')
    return JSON.parse(body) as unknown
  }

  private notifyDiagnostics() {
    for (const listener of this.diagnosticListeners) listener()
  }
}

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
  if (value.id !== 1 || !value.token || !Number.isFinite(value.updatedAtMs) || !Array.isArray(value.records)) {
    throw new Error('Invalid stored mushaf resource')
  }
  const records = value.records.map(mushafWord)
  if (records.length < minimum || new Set(records.map((row) => row.record_key)).size !== records.length) {
    throw new Error('Incomplete stored mushaf resource')
  }
  return { ...value, records }
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected object')
  return value as Record<string, unknown>
}
function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('Expected array')
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

const contentBaseUrl = import.meta.env.VITE_TIMING_CONTENT_BASE_URL?.trim()
function configuredMushafCache(): RuntimeMushafCache | null {
  if (!contentBaseUrl) return null
  try { return new RuntimeMushafCache(contentBaseUrl) } catch { return null }
}
export const runtimeMushafCache = configuredMushafCache()
