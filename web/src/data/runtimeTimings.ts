import type { Segment } from './models'

export const QF_MAX_CACHE_AGE_MS = 7 * 24 * 60 * 60 * 1_000
export const QF_REVALIDATE_AFTER_MS = 6 * 24 * 60 * 60 * 1_000

const RUNTIME_RECITERS = new Set([1, 2, 3, 4, 5, 7])
const MAX_RESPONSE_CHARS = 20 * 1024 * 1024
const MAX_SYNC_PAGES = 100

export interface TimingRecord {
  record_type: 'timing'
  record_key: string
  surah_id: number
  ayah_number: number
  segments: number[][]
  audio_onset_ms?: number
}

export interface StoredTimingResource {
  reciterId: number
  token: string
  updatedAtMs: number
  records: TimingRecord[]
}

export interface TimingContentStore {
  get(reciterId: number): Promise<StoredTimingResource | null>
  put(resource: StoredTimingResource): Promise<void>
  clear(): Promise<void>
}

/** IndexedDB keeps QF content separate from the bundled read-only Quran DB. */
export class IndexedDbTimingContentStore implements TimingContentStore {
  private database: Promise<IDBDatabase> | null = null

  async get(reciterId: number): Promise<StoredTimingResource | null> {
    const db = await this.open()
    return requestResult<StoredTimingResource | undefined>(
      db.transaction('resources').objectStore('resources').get(reciterId),
    ).then((row) => row ?? null)
  }

  async put(resource: StoredTimingResource): Promise<void> {
    const db = await this.open()
    const transaction = db.transaction('resources', 'readwrite')
    transaction.objectStore('resources').put(resource)
    await transactionDone(transaction)
  }

  async clear(): Promise<void> {
    const db = await this.open()
    const transaction = db.transaction('resources', 'readwrite')
    transaction.objectStore('resources').clear()
    await transactionDone(transaction)
  }

  private open(): Promise<IDBDatabase> {
    if (this.database) return this.database
    this.database = new Promise((resolve, reject) => {
      const request = indexedDB.open('beautiful-quran-qf-content-v1', 1)
      request.onupgradeneeded = () => {
        request.result.createObjectStore('resources', { keyPath: 'reciterId' })
      }
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    return this.database
  }
}

/**
 * Local-first QF-shaped timing cache. Reads never wait on the network: a fresh
 * device snapshot wins, otherwise quran-align remains the repository fallback.
 */
export class RuntimeTimingCache {
  private readonly baseUrl: string
  private readonly resources = new Map<number, StoredTimingResource>()
  private readonly syncStates = new Map<number, StoredTimingResource>()
  private readonly chapterCache = new Map<string, Map<number, Segment[]>>()
  private readonly inFlight = new Map<number, Promise<boolean>>()
  private readonly listeners = new Set<(reciterId: number) => void>()

  constructor(
    baseUrl: string,
    private readonly store: TimingContentStore,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly now: () => number = Date.now,
    private readonly minimumRecords = 6_000,
  ) {
    const parsed = new URL(baseUrl)
    if (parsed.protocol !== 'https:') throw new Error('Timing Content API must use HTTPS')
    this.baseUrl = parsed.href.replace(/\/$/, '')
  }

  subscribe(listener: (reciterId: number) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** Restore local rows immediately and start any required refresh in parallel. */
  async restore(reciterId: number): Promise<void> {
    if (!RUNTIME_RECITERS.has(reciterId)) return
    let saved: StoredTimingResource | null = null
    try {
      const candidate = await this.store.get(reciterId)
      if (candidate) saved = storedResource(candidate, reciterId, this.minimumRecords)
    } catch {
      // Corrupt/unavailable local storage is simply a cache miss.
    }
    if (saved) {
      this.syncStates.set(reciterId, saved)
      if (isFresh(saved.updatedAtMs, this.now())) this.install(saved)
    }
    if (!saved || needsRefresh(saved.updatedAtMs, this.now())) void this.refresh(reciterId)
  }

  /** A failed refresh preserves a still-current snapshot and never escapes. */
  refresh(reciterId: number): Promise<boolean> {
    if (!RUNTIME_RECITERS.has(reciterId)) return Promise.resolve(false)
    const current = this.inFlight.get(reciterId)
    if (current) return current
    const pending = this.sync(reciterId)
      .then(() => true)
      .catch(() => false)
      .finally(() => this.inFlight.delete(reciterId))
    this.inFlight.set(reciterId, pending)
    return pending
  }

  timings(reciterId: number, surahId: number): Map<number, Segment[]> | null {
    const state = this.syncStates.get(reciterId)
    if (!state || needsRefresh(state.updatedAtMs, this.now())) void this.refresh(reciterId)
    const resource = this.resources.get(reciterId)
    if (!resource || !isFresh(resource.updatedAtMs, this.now())) {
      this.resources.delete(reciterId)
      return null
    }
    const key = `${reciterId}:${surahId}`
    const cached = this.chapterCache.get(key)
    if (cached) return cached
    const rows = resource.records.filter((row) => row.surah_id === surahId)
    if (rows.length === 0) return null
    const timings = new Map<number, Segment[]>()
    for (const row of rows) {
      timings.set(
        row.ayah_number,
        row.segments.map(([position, startMs, endMs]) => ({
          position,
          startMs,
          endMs,
        })),
      )
    }
    this.chapterCache.set(key, timings)
    return timings
  }

  private async sync(reciterId: number): Promise<void> {
    const previous = this.syncStates.get(reciterId)
    const filter = encodeURIComponent(`recitations:${reciterId}`)
    let path = previous
      ? `/api/v4/resources/sync?sync_token=${encodeURIComponent(previous.token)}&resources=${filter}`
      : `/api/v4/resources/sync?bootstrap=true&resources=${filter}`
    let records = previous?.records ?? []
    let token: string | null = null
    let contentAgeMs: number | null = null

    for (let page = 0; page < MAX_SYNC_PAGES; page += 1) {
      const sync = asObject(asObject(await this.get(path)).sync)
      for (const value of asArray(sync.mutations)) {
        const mutation = asObject(value)
        const type = String(mutation.type)
        if (type === 'RESOURCE_CREATE' || type === 'RESOURCE_INVALIDATE') {
          const snapshot = asObject(await this.get(relativePath(mutation.snapshot_url)))
          if (
            snapshot.resource_group !== 'recitations' ||
            snapshot.schema_version !== 1 ||
            Number(snapshot.resource_id) !== reciterId
          ) throw new Error('Snapshot resource mismatch')
          const snapshotRecords = asArray(snapshot.records).map(timingRecord)
          if (snapshotRecords.length < this.minimumRecords) {
            throw new Error('Timing snapshot is incomplete')
          }
          records = snapshotRecords
        } else if (type === 'RESOURCE_DELETE') {
          records = []
        } else if (type === 'ROW_CREATE' || type === 'ROW_UPDATE') {
          const row = timingRecord(mutation.data)
          records = records.filter((item) => item.record_key !== row.record_key).concat(row)
        } else if (type === 'ROW_DELETE') {
          const recordKey = String(mutation.record_key)
          records = records.filter((item) => item.record_key !== recordKey)
        } else if (type !== 'RESOURCE_UPDATE') {
          throw new Error('Unsupported Content Sync mutation')
        }
      }
      if (sync.next_page_url != null) {
        path = relativePath(sync.next_page_url)
        continue
      }
      if (typeof sync.next_sync_token === 'string') token = sync.next_sync_token
      contentAgeMs = Number(sync.content_age_ms)
      if (
        !Number.isInteger(contentAgeMs) ||
        contentAgeMs < 0 ||
        contentAgeMs > QF_MAX_CACHE_AGE_MS
      ) throw new Error('Content Sync response has an invalid content age')
      break
    }

    if (!token || contentAgeMs == null) {
      throw new Error('Content Sync response has no final token')
    }
    const next = storedResource(
      { reciterId, token, updatedAtMs: this.now() - contentAgeMs, records },
      reciterId,
      this.minimumRecords,
    )
    await this.store.put(next)
    this.syncStates.set(reciterId, next)
    this.install(next)
  }

  private install(resource: StoredTimingResource) {
    this.resources.set(resource.reciterId, resource)
    const prefix = `${resource.reciterId}:`
    for (const key of this.chapterCache.keys()) {
      if (key.startsWith(prefix)) this.chapterCache.delete(key)
    }
    for (const listener of this.listeners) listener(resource.reciterId)
  }

  private async get(path: string): Promise<unknown> {
    const response = await this.fetchImpl(this.baseUrl + relativePath(path), {
      headers: { accept: 'application/json' },
      signal: AbortSignal.timeout(180_000),
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
}

function timingRecord(value: unknown): TimingRecord {
  const row = asObject(value)
  const surah = row.surah_id
  const ayah = row.ayah_number
  if (
    row.record_type !== 'timing' ||
    typeof row.record_key !== 'string' ||
    !Number.isInteger(surah) ||
    Number(surah) < 1 ||
    Number(surah) > 114 ||
    !Number.isInteger(ayah) ||
    Number(ayah) < 1 ||
    row.record_key !== `${surah}:${ayah}` ||
    !Array.isArray(row.segments) ||
    row.segments.some(
      (segment) =>
        !Array.isArray(segment) ||
        segment.length !== 3 ||
        !Number.isInteger(segment[0]) ||
        segment[0] < 1 ||
        !Number.isFinite(segment[1]) ||
        segment[1] < 0 ||
        !Number.isFinite(segment[2]) ||
        segment[2] <= segment[1],
    ) ||
    (row.audio_onset_ms != null &&
      (!Number.isInteger(row.audio_onset_ms) || Number(row.audio_onset_ms) < 0))
  ) {
    throw new Error('Invalid timing record')
  }
  return row as unknown as TimingRecord
}

function storedResource(
  value: StoredTimingResource,
  reciterId: number,
  minimumRecords: number,
): StoredTimingResource {
  if (
    value.reciterId !== reciterId ||
    typeof value.token !== 'string' ||
    value.token.length === 0 ||
    !Number.isFinite(value.updatedAtMs) ||
    !Array.isArray(value.records)
  ) throw new Error('Invalid stored timing resource')
  const records = value.records.map(timingRecord)
  if (records.length > 0 && records.length < minimumRecords) {
    throw new Error('Incomplete stored timing resource')
  }
  if (new Set(records.map((row) => row.record_key)).size !== records.length) {
    throw new Error('Duplicate stored timing record')
  }
  return { ...value, records }
}

function asObject(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Expected Content API object')
  }
  return value as Record<string, unknown>
}

function asArray(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('Expected Content API array')
  return value
}

function relativePath(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/api/v4/') || value.startsWith('//')) {
    throw new Error('Expected relative Content API path')
  }
  return value
}

function isFresh(updatedAtMs: number, nowMs: number): boolean {
  return nowMs - updatedAtMs >= 0 && nowMs - updatedAtMs <= QF_MAX_CACHE_AGE_MS
}

function needsRefresh(updatedAtMs: number, nowMs: number): boolean {
  const age = nowMs - updatedAtMs
  return age < 0 || age > QF_REVALIDATE_AFTER_MS
}

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function transactionDone(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(transaction.error)
    transaction.onabort = () => reject(transaction.error)
  })
}

const timingContentBaseUrl = import.meta.env.VITE_TIMING_CONTENT_BASE_URL?.trim()

function configuredTimingCache(): RuntimeTimingCache | null {
  if (!timingContentBaseUrl) return null
  try {
    return new RuntimeTimingCache(timingContentBaseUrl, new IndexedDbTimingContentStore())
  } catch {
    return null
  }
}

export const runtimeTimingCache = configuredTimingCache()
