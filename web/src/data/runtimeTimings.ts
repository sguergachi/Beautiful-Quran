import { queryAll } from './database'

export const QF_TIMING_RECITERS: Readonly<Record<number, readonly [number, string]>> = {
  1: [7, 'Alafasy_128kbps'],
  2: [6, 'Husary_64kbps'],
  3: [2, 'Abdul_Basit_Murattal_64kbps'],
  4: [9, 'Minshawy_Murattal_128kbps'],
  5: [3, 'Abdurrahmaan_As-Sudais_192kbps'],
  7: [5, 'Hani_Rifai_192kbps'],
}
export const TIMING_REFRESH_AFTER_MS = 6 * 24 * 60 * 60 * 1_000
const MAX_RESPONSE_CHARS = 16 * 1024 * 1024

export type TimingRow = [number, number, number[][], number]
export interface RuntimeTimingResource {
  schemaVersion: 1
  reciterId: number
  qfReciterId: number
  audioSlug: string
  clock: 'everyayah-ms'
  sourceSha256: string
  sourceSyncSequence: number
  sourceSyncedAtMs: number
  normalizerSha256: string
  revision: string
  rows: TimingRow[]
  withheldVerseKeys: string[]
}

export interface RuntimeTimingStore {
  get(reciterId: number): Promise<RuntimeTimingResource | null>
  put(resource: RuntimeTimingResource): Promise<void>
  remove(reciterId: number): Promise<void>
  clear(): Promise<void>
}

class TimingStore implements RuntimeTimingStore {
  private database: Promise<IDBDatabase> | null = null

  private open(): Promise<IDBDatabase> {
    return this.database ??= new Promise((resolve, reject) => {
      const request = indexedDB.open('beautiful-quran-qf-timings-v1', 1)
      request.onupgradeneeded = () => request.result.createObjectStore('reciters', { keyPath: 'reciterId' })
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
  }

  async get(reciterId: number): Promise<RuntimeTimingResource | null> {
    const db = await this.open()
    return new Promise((resolve, reject) => {
      const request = db.transaction('reciters').objectStore('reciters').get(reciterId)
      request.onsuccess = () => resolve(request.result ?? null)
      request.onerror = () => reject(request.error)
    })
  }

  private async write(action: (store: IDBObjectStore) => void): Promise<void> {
    const db = await this.open()
    const transaction = db.transaction('reciters', 'readwrite')
    action(transaction.objectStore('reciters'))
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve()
      transaction.onerror = () => reject(transaction.error)
      transaction.onabort = () => reject(transaction.error)
    })
  }

  put(resource: RuntimeTimingResource) { return this.write((store) => store.put(resource)) }
  remove(reciterId: number) { return this.write((store) => store.delete(reciterId)) }
  clear() { return this.write((store) => store.clear()) }
}

/** Cache only accepted server timings; all audio normalization stays in Python. */
export class RuntimeTimingsCache {
  private readonly resources = new Map<number, RuntimeTimingResource>()
  private readonly restores = new Map<number, Promise<void>>()
  private readonly requests = new Map<number, Promise<void>>()
  private readonly timers = new Map<number, number>()
  private readonly listeners = new Set<(reciterId: number | null) => void>()
  private writes: Promise<void> = Promise.resolve()
  private generation = 0
  private readonly resourceGenerations = new Map<number, number>()
  private revoked = false

  constructor(
    private readonly baseUrl: string,
    private readonly store: RuntimeTimingStore = new TimingStore(),
    private readonly fetchImpl: typeof fetch = (input, init) => fetch(input, init),
    private readonly now: () => number = Date.now,
    private readonly canonical: () => Map<string, number> = canonicalWordCounts,
  ) {
    if (new URL(baseUrl).protocol !== 'https:') throw new Error('Timing API must use HTTPS')
  }

  subscribe(listener: (reciterId: number | null) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  resource(reciterId: number): RuntimeTimingResource | null {
    return this.resources.get(reciterId) ?? null
  }

  /** Read a retained copy immediately, including during a prolonged outage. */
  async ensure(reciterId: number): Promise<void> {
    if (!QF_TIMING_RECITERS[reciterId]) throw new Error('Unsupported QF timing reciter')
    if (!this.restores.has(reciterId)) this.restores.set(reciterId, this.restore(reciterId))
    await this.restores.get(reciterId)
    const resource = this.resources.get(reciterId)
    if (!resource) return this.refresh(reciterId)
    if (this.refreshDue(resource)) void this.refresh(reciterId).catch(() => undefined)
  }

  async refreshLoaded(): Promise<void> {
    await Promise.all([...this.restores.keys()].map((id) => this.refresh(id).catch(() => undefined)))
  }

  refresh(reciterId: number): Promise<void> {
    const pending = this.requests.get(reciterId)
    if (pending) return pending
    const request = this.fetchResource(reciterId).finally(() => this.requests.delete(reciterId))
    this.requests.set(reciterId, request)
    return request
  }

  /** Shared credential revocation invalidates every voice, including active ink. */
  async revoke(): Promise<void> {
    if (this.revoked) return
    this.revoked = true
    this.generation += 1
    this.resources.clear()
    for (const timer of this.timers.values()) window.clearTimeout(timer)
    this.timers.clear()
    this.publish(null)
    await this.write(() => this.store.clear())
  }

  private async restore(reciterId: number): Promise<void> {
    if (this.revoked || this.resourceGenerations.has(reciterId)) return
    const generation = this.generationFor(reciterId)
    try {
      const resource = await this.store.get(reciterId)
      if (!resource) return
      await validateTimingResource(resource, reciterId, this.canonical())
      if (generation === this.generationFor(reciterId)) this.install(resource)
    } catch {
      await this.write(async () => {
        if (generation === this.generationFor(reciterId)) await this.store.remove(reciterId)
      })
    }
  }

  private async fetchResource(reciterId: number): Promise<void> {
    if (!QF_TIMING_RECITERS[reciterId]) throw new Error('Unsupported QF timing reciter')
    const generation = this.generationFor(reciterId)
    const response = await this.fetchImpl(`${this.baseUrl.replace(/\/$/, '')}/api/timings/${reciterId}`, {
      headers: { accept: 'application/json' }, signal: AbortSignal.timeout(180_000),
    })
    if (Number(response.headers.get('content-length')) > MAX_RESPONSE_CHARS) {
      throw new Error('Timing response exceeded size limit')
    }
    const text = await response.text()
    if (text.length > MAX_RESPONSE_CHARS) throw new Error('Timing response exceeded size limit')
    const body = JSON.parse(text) as RuntimeTimingResource & { error?: { code?: string } }
    if (response.status === 403 && body.error?.code === 'qf_access_revoked') {
      await this.revoke()
      throw new Error('QF timing access revoked')
    }
    if (response.status === 410 && body.error?.code === 'qf_timing_resource_deleted') {
      this.resourceGenerations.set(reciterId, (this.resourceGenerations.get(reciterId) ?? 0) + 1)
      this.resources.delete(reciterId)
      const timer = this.timers.get(reciterId)
      if (timer != null) window.clearTimeout(timer)
      this.timers.delete(reciterId)
      this.publish(reciterId)
      await this.write(() => this.store.remove(reciterId))
      throw new Error('QF timing resource deleted')
    }
    if (!response.ok) throw new Error(`Timing corpus ${reciterId}: HTTP ${response.status}`)
    await validateTimingResource(body, reciterId, this.canonical())
    await this.write(async () => {
      if (generation !== this.generationFor(reciterId)) throw new Error('Timing refresh superseded by revocation')
      await this.store.put(body)
      if (generation === this.generationFor(reciterId)) this.install(body)
    })
  }

  private generationFor(reciterId: number): string {
    return `${this.generation}:${this.resourceGenerations.get(reciterId) ?? 0}`
  }

  private write(action: () => Promise<void>): Promise<void> {
    const next = this.writes.then(action)
    this.writes = next.catch(() => undefined)
    return next
  }

  private install(resource: RuntimeTimingResource) {
    const changed = this.resources.get(resource.reciterId)?.revision !== resource.revision
    this.resources.set(resource.reciterId, resource)
    this.revoked = false
    if (typeof window !== 'undefined') {
      const previous = this.timers.get(resource.reciterId)
      if (previous != null) window.clearTimeout(previous)
      const delay = Math.min(TIMING_REFRESH_AFTER_MS,
        resource.sourceSyncedAtMs + TIMING_REFRESH_AFTER_MS - this.now())
      if (delay > 0) this.timers.set(resource.reciterId, window.setTimeout(() => {
        this.timers.delete(resource.reciterId)
        void this.refresh(resource.reciterId).catch(() => undefined)
      }, delay))
    }
    if (changed) this.publish(resource.reciterId)
  }

  private refreshDue(resource: RuntimeTimingResource) {
    const age = this.now() - resource.sourceSyncedAtMs
    return age < 0 || age >= TIMING_REFRESH_AFTER_MS
  }

  private publish(reciterId: number | null) {
    for (const listener of this.listeners) listener(reciterId)
  }
}

/** Validate completeness and exact occurrence order before an atomic commit. */
export async function validateTimingResource(
  resource: RuntimeTimingResource, reciterId: number, canonical: Map<string, number>,
): Promise<void> {
  const identity = QF_TIMING_RECITERS[reciterId]
  if (!identity || !resource || resource.schemaVersion !== 1 || resource.reciterId !== reciterId ||
      resource.qfReciterId !== identity[0] || resource.audioSlug !== identity[1] ||
      resource.clock !== 'everyayah-ms') throw new Error('Timing resource identity mismatch')
  for (const hash of [resource.sourceSha256, resource.normalizerSha256, resource.revision]) {
    if (typeof hash !== 'string' || !/^[a-f0-9]{64}$/.test(hash)) throw new Error('Invalid timing digest')
  }
  if (!integer(resource.sourceSyncSequence) || !integer(resource.sourceSyncedAtMs) || resource.sourceSyncedAtMs === 0 ||
      !Array.isArray(resource.rows) || !resource.rows.length || !Array.isArray(resource.withheldVerseKeys)) {
    throw new Error('Invalid timing resource metadata')
  }
  const seen = new Set<string>()
  let previous = 0
  for (const row of resource.rows) {
    if (!Array.isArray(row) || row.length !== 4) throw new Error('Invalid timing row')
    const [surah, ayah, segments, onset] = row
    const key = `${surah}:${ayah}`
    const wordCount = canonical.get(key)
    const order = surah * 1_000 + ayah
    if (!integer(surah) || !integer(ayah) || !wordCount || order <= previous ||
        !integer(onset) || !Array.isArray(segments) || !segments.length) {
      throw new Error(`Invalid timing verse ${key}`)
    }
    previous = order
    const positions = new Set<number>()
    let previousEnd = onset
    for (const segment of segments) {
      if (!Array.isArray(segment) || segment.length !== 3 || !segment.every(integer) ||
          segment[0]! < 1 || segment[0]! > wordCount || segment[1]! < previousEnd ||
          segment[2]! <= segment[1]!) throw new Error(`Invalid timing segment ${key}`)
      positions.add(segment[0]!)
      previousEnd = segment[2]!
    }
    if (positions.size !== wordCount) throw new Error(`Incomplete timing verse ${key}`)
    seen.add(key)
  }
  previous = 0
  for (const key of resource.withheldVerseKeys) {
    if (typeof key !== 'string' || !canonical.has(key) || seen.has(key)) {
      throw new Error('Invalid withheld timing verse')
    }
    const [surah, ayah] = key.split(':').map(Number)
    const order = surah! * 1_000 + ayah!
    if (order <= previous) throw new Error('Unordered withheld timing verses')
    previous = order
    seen.add(key)
  }
  if (seen.size !== canonical.size) throw new Error('Timing corpus coverage mismatch')
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify({
    rows: resource.rows, withheldVerseKeys: resource.withheldVerseKeys,
  })))
  const hash = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
  if (hash !== resource.revision) throw new Error('Timing corpus digest mismatch')
}

function integer(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

let wordCounts: Map<string, number> | null = null
function canonicalWordCounts(): Map<string, number> {
  return wordCounts ??= new Map(queryAll(
    'SELECT surah_id, ayah_number, COUNT(*) AS words FROM words GROUP BY surah_id, ayah_number',
    [], (row) => [`${row.surah_id}:${row.ayah_number}`, Number(row.words)] as const,
  ))
}

export const runtimeTimingsCache = new RuntimeTimingsCache(
  import.meta.env.VITE_QF_CONTENT_BASE_URL || 'https://beautiful-quran.sguergachi.workers.dev',
)
