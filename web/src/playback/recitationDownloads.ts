/**
 * Recitation downloads for the web — Android `RecitationCache` +
 * `RecitationDownloads`, on the Cache API.
 *
 * Two stores, as on Android:
 *  - listen ([AUDIO_CACHE]): what playback and read-ahead warmed; evictable.
 *  - keep ([AUDIO_KEEP_CACHE]): chapters the reader downloaded; never evicted
 *    by playback, and read first by the prefetcher.
 *
 * The pure helpers at the top mirror Android's labels and verbs one to one so
 * the Download manager reads the same on both. The store below them owns one
 * sequential download queue (one chapter at a time, a few ayahs in parallel)
 * with per-chapter pause / resume.
 */
import { audioUrl, basmalahAudioUrl, type Reciter, type Surah } from '../data/models'
import { surahOpensWithBasmalahPreface } from '../domain/Basmalah'
import { AUDIO_CACHE, AUDIO_KEEP_CACHE } from './audioPrefetch'

// ── Pure model ────────────────────────────────────────────────────────────

export interface ChapterDownload {
  surah: Surah
  /** Ayahs of this chapter held in the keep cache. */
  cached: number
  bytes: number
}

export interface ReciterDownloads {
  reciter: Reciter
  chapters: ChapterDownload[]
}

export interface RecitationUsage {
  listenBytes: number
  keepBytes: number
}

export interface EveryayahRef {
  slug: string
  surah: number
  ayah: number
}

export const chapterComplete = (row: ChapterDownload) => row.cached >= row.surah.ayahCount
export const chapterEmpty = (row: ChapterDownload) => row.cached <= 0 && row.bytes <= 0
export const reciterBytes = (row: ReciterDownloads) =>
  row.chapters.reduce((sum, chapter) => sum + chapter.bytes, 0)

const EVERYAYAH_URL = /(?:everyayah\.com|verse-clips\.invalid)\/data\/([^/]+)\/(\d{3})(\d{3})\.mp3/

export function parseEveryayahUrl(url: string): EveryayahRef | null {
  const match = EVERYAYAH_URL.exec(url)
  if (!match) return null
  return { slug: match[1]!, surah: Number(match[2]), ayah: Number(match[3]) }
}

const MB = 1024 * 1024

export function formatBytesAmount(bytes: number): string {
  if (bytes <= 0) return '0 MB'
  if (bytes < MB) return '< 1 MB'
  return `${Math.floor(bytes / MB)} MB`
}

/** Android `formatUsage`: everything held, then the evictable share of it. */
export function formatUsage(usage: RecitationUsage): string {
  const total = usage.listenBytes + usage.keepBytes
  return `${formatBytesAmount(total)} stored · ${formatBytesAmount(usage.listenBytes)} cached`
}

export function verseCountLabel(count: number): string {
  return count === 1 ? '1 verse' : `${count} verses`
}

export function chapterDownloadCountLabel(completed: number, total: number): string {
  const safeTotal = Math.max(0, total)
  const safeCompleted = Math.min(safeTotal, Math.max(0, completed))
  return `${safeCompleted}/${safeTotal} ${safeTotal === 1 ? 'verse' : 'verses'}`
}

export function downloadPercent(ayah: number, ayahCount: number): number | null {
  if (ayahCount <= 0) return null
  if (ayah <= 0) return 0
  return Math.min(100, Math.max(1, Math.floor((100 * ayah) / ayahCount)))
}

export function chapterProgressFactLine(completed: number, total: number, percent: number | null): string {
  return chapterDownloadCountLabel(completed, total) + (percent != null ? ` · ${percent}%` : '')
}

export function chapterFactLine(row: ChapterDownload, waiting: boolean, paused: boolean): string {
  const verses = verseCountLabel(row.surah.ayahCount)
  const complete = chapterComplete(row)
  const empty = chapterEmpty(row)
  if (waiting) return `${verses} · Waiting`
  if (paused || (!empty && !complete)) {
    let out = verses
    if (!empty && !complete) out += ` · ${row.cached} of ${row.surah.ayahCount}`
    if (row.bytes > 0) out += ` · ${formatBytesAmount(row.bytes)}`
    return out
  }
  if (complete && row.bytes > 0) return `${verses} · ${formatBytesAmount(row.bytes)}`
  return verses
}

export function chapterActionLabel(
  row: ChapterDownload,
  downloading: boolean,
  waiting: boolean,
  paused: boolean,
): string {
  if (downloading || waiting) return 'Pause'
  if (paused || (!chapterComplete(row) && !chapterEmpty(row))) return 'Resume'
  if (chapterComplete(row)) return 'Delete'
  return 'Download'
}

/** Paused or partial chapters also offer Delete to the left of Resume. */
export function chapterOffersDelete(
  row: ChapterDownload,
  downloading: boolean,
  waiting: boolean,
  paused: boolean,
): boolean {
  return !downloading && !waiting && (paused || (!chapterComplete(row) && !chapterEmpty(row)))
}

export function chapterTrailingLabels(action: string, alsoDelete: boolean): string[] {
  return alsoDelete ? ['Delete', action] : [action]
}

export function actionIsFetch(action: string): boolean {
  return action === 'Download' || action === 'Download all' || action === 'Pause' || action === 'Resume'
}

export function chapterProgressFraction(
  downloading: boolean,
  paused: boolean,
  percent: number | null,
  complete: boolean,
): number | null {
  if (complete) return 1
  if (downloading || paused) return Math.min(100, Math.max(0, percent ?? 0)) / 100
  return null
}

export function reciterHeaderAction(o: {
  expanded: boolean
  busy: boolean
  paused: boolean
  hasDownloadable: boolean
  hasBytes: boolean
  confirming: boolean
  hasResumable: boolean
}): string | null {
  if (o.confirming) return null
  if (o.busy && !o.expanded) return 'Pause'
  if (o.paused && !o.expanded) return 'Resume'
  if (!o.expanded && o.hasResumable) return 'Resume'
  if (!o.expanded && o.hasBytes) return 'Delete'
  if (o.expanded && o.hasDownloadable) return 'Download all'
  return null
}

export function reciterDownloadLabel(row: ReciterDownloads): string {
  const bytes = reciterBytes(row)
  const done = row.chapters.filter(chapterComplete).length
  const count =
    bytes <= 0 ? 'Nothing downloaded' : done === row.chapters.length ? 'All chapters' : `${done} of ${row.chapters.length}`
  return bytes > 0 ? `${count} · ${formatBytesAmount(bytes)}` : count
}

/** Every URL a chapter's playlist plays: the preface basmalah, then each ayah. */
export function chapterAudioUrls(reciter: Reciter, surah: Surah): string[] {
  const urls: string[] = []
  if (surahOpensWithBasmalahPreface(surah.id)) urls.push(basmalahAudioUrl(reciter))
  for (let ayah = 1; ayah <= surah.ayahCount; ayah++) urls.push(audioUrl(reciter, surah.id, ayah))
  return urls
}

export const chapterKey = (reciterId: number, surahId: number) => `${reciterId}-${surahId}`

/**
 * Builds the catalog from the keep cache's URL → bytes map. Al-Fatihah 1:1
 * doubles as every other chapter's basmalah, so it only counts toward
 * Al-Fatihah when that chapter was itself downloaded ([owned]).
 */
export function buildReciterDownloads(
  reciters: Reciter[],
  surahs: Surah[],
  kept: ReadonlyMap<string, number>,
  owned: ReadonlySet<string>,
): ReciterDownloads[] {
  const bySlug = new Map<string, Map<number, Map<number, number>>>()
  for (const [url, bytes] of kept) {
    const ref = parseEveryayahUrl(url)
    if (!ref) continue
    let chapters = bySlug.get(ref.slug)
    if (!chapters) bySlug.set(ref.slug, (chapters = new Map()))
    let ayahs = chapters.get(ref.surah)
    if (!ayahs) chapters.set(ref.surah, (ayahs = new Map()))
    ayahs.set(ref.ayah, bytes)
  }
  return reciters.map((reciter) => {
    const chapters = bySlug.get(reciter.slug)
    return {
      reciter,
      chapters: surahs.map((surah) => {
        let cached = 0
        let bytes = 0
        const ayahs = chapters?.get(surah.id)
        if (ayahs) {
          for (const [ayah, size] of ayahs) {
            if (ayah < 1 || ayah > surah.ayahCount) continue
            if (surah.id === 1 && ayah === 1 && !owned.has(chapterKey(reciter.id, 1))) continue
            cached++
            bytes += size
          }
        }
        return { surah, cached, bytes }
      }),
    }
  })
}

/** Deleting [surahId] keeps the shared basmalah while another chapter needs it. */
export function keepsSharedBasmalahAfterDeleting(
  owned: ReadonlySet<string>,
  reciterId: number,
  surahId: number,
): boolean {
  for (const key of owned) {
    if (key === chapterKey(reciterId, surahId)) continue
    const [r, s] = key.split('-').map(Number)
    if (r !== reciterId) continue
    if (s === 1 || surahOpensWithBasmalahPreface(s!)) return true
  }
  return false
}

// ── Live state ────────────────────────────────────────────────────────────

export interface DownloadRequest {
  reciter: Reciter
  surah: Surah
}

export interface DownloadProgress {
  /** The chapter being fetched now, as `reciterId-surahId`. */
  active: string | null
  /** Ayahs of the active chapter already held. */
  ayah: number
  ayahCount: number
  queued: string[]
  /** Paused chapters and the ayah clock they stopped at. */
  paused: Map<string, { ayah: number; ayahCount: number }>
  /** Bumps whenever the keep cache changed. */
  revision: number
  error: string | null
}

const EMPTY_PROGRESS: DownloadProgress = {
  active: null,
  ayah: 0,
  ayahCount: 0,
  queued: [],
  paused: new Map(),
  revision: 0,
  error: null,
}

export const isReciterBusy = (p: DownloadProgress, reciterId: number) =>
  p.active?.startsWith(`${reciterId}-`) === true || p.queued.some((key) => key.startsWith(`${reciterId}-`))

export const isReciterPaused = (p: DownloadProgress, reciterId: number) =>
  [...p.paused.keys()].some((key) => key.startsWith(`${reciterId}-`))

/** Reciter-subtitle progress: the chapter in hand and what waits behind it. */
export function reciterProgressLabel(
  p: DownloadProgress,
  reciterId: number,
  surahName: (surahId: number) => string,
): string {
  if (!isReciterBusy(p, reciterId) && !isReciterPaused(p, reciterId)) return ''
  const parts: string[] = []
  if (p.active?.startsWith(`${reciterId}-`)) {
    parts.push(surahName(Number(p.active.split('-')[1])))
    const percent = downloadPercent(p.ayah, p.ayahCount)
    if (percent != null) parts.push(`${percent}%`)
  }
  const waiting = p.queued.filter((key) => key.startsWith(`${reciterId}-`)).length
  if (waiting > 0) parts.push(`${waiting} waiting`)
  return parts.join(' · ')
}

const OWNED_KEY = 'beautiful-quran-downloaded-chapters'
const FETCH_CONCURRENCY = 3

function readOwned(): Set<string> {
  try {
    const raw = localStorage.getItem(OWNED_KEY)
    const list = raw ? (JSON.parse(raw) as unknown) : []
    return new Set(Array.isArray(list) ? list.filter((v): v is string => typeof v === 'string') : [])
  } catch {
    return new Set()
  }
}

function writeOwned(owned: Set<string>) {
  try {
    localStorage.setItem(OWNED_KEY, JSON.stringify([...owned]))
  } catch {
    /* private mode: downloads still work, Al-Fatihah 1:1 just reads as shared */
  }
}

async function responseBytes(response: Response): Promise<number> {
  const header = Number(response.headers.get('content-length'))
  if (Number.isFinite(header) && header > 0) return header
  try {
    return (await response.blob()).size
  } catch {
    return 0
  }
}

/** URL → bytes for every everyayah clip in [cacheName]. */
async function scanCache(cacheName: string): Promise<Map<string, number>> {
  const out = new Map<string, number>()
  if (typeof caches === 'undefined') return out
  const cache = await caches.open(cacheName)
  const requests = await cache.keys()
  await Promise.all(
    requests.map(async (request) => {
      const response = await cache.match(request)
      if (response) out.set(request.url, await responseBytes(response))
    }),
  )
  return out
}

class RecitationDownloadStore {
  private progress: DownloadProgress = EMPTY_PROGRESS
  private readonly listeners = new Set<() => void>()
  private readonly queue: DownloadRequest[] = []
  private cancelActive = false
  private running = false
  private kept: Map<string, number> | null = null
  private owned = readOwned()

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getProgress = (): DownloadProgress => this.progress

  private publish(patch: Partial<DownloadProgress>) {
    this.progress = {
      ...this.progress,
      ...patch,
      queued: this.queue.map((r) => chapterKey(r.reciter.id, r.surah.id)),
    }
    this.listeners.forEach((listener) => listener())
  }

  private touched() {
    this.publish({ revision: this.progress.revision + 1 })
  }

  /** The keep cache index, scanned once and then kept current by our writes. */
  private async keptIndex(force = false): Promise<Map<string, number>> {
    if (!this.kept || force) this.kept = await scanCache(AUDIO_KEEP_CACHE)
    return this.kept
  }

  async usage(): Promise<RecitationUsage> {
    const [listen, keep] = await Promise.all([scanCache(AUDIO_CACHE), this.keptIndex(true)])
    let listenBytes = 0
    for (const [url, bytes] of listen) if (!keep.has(url)) listenBytes += bytes
    let keepBytes = 0
    for (const bytes of keep.values()) keepBytes += bytes
    return { listenBytes, keepBytes }
  }

  async catalog(reciters: Reciter[], surahs: Surah[]): Promise<ReciterDownloads[]> {
    return buildReciterDownloads(reciters, surahs, await this.keptIndex(), this.owned)
  }

  downloadChapter(reciter: Reciter, surah: Surah) {
    this.enqueue([{ reciter, surah }])
  }

  downloadAll(reciter: Reciter, surahs: Surah[]) {
    this.enqueue(surahs.map((surah) => ({ reciter, surah })))
  }

  resumeChapter(reciter: Reciter, surah: Surah) {
    this.enqueue([{ reciter, surah }])
  }

  /** Resume every paused chapter of [reciter], in book order. */
  resumeReciter(reciter: Reciter, surahs: Surah[]) {
    const paused = surahs.filter((surah) => this.progress.paused.has(chapterKey(reciter.id, surah.id)))
    this.enqueue(paused.map((surah) => ({ reciter, surah })))
  }

  pauseChapter(reciterId: number, surahId: number) {
    const key = chapterKey(reciterId, surahId)
    const paused = new Map(this.progress.paused)
    const at = this.queue.findIndex((r) => chapterKey(r.reciter.id, r.surah.id) === key)
    if (at >= 0) {
      const [request] = this.queue.splice(at, 1)
      paused.set(key, { ayah: 0, ayahCount: request!.surah.ayahCount })
    }
    if (this.progress.active === key) {
      this.cancelActive = true
      paused.set(key, { ayah: this.progress.ayah, ayahCount: this.progress.ayahCount })
    }
    this.publish({ paused })
  }

  pauseReciter(reciterId: number) {
    const keys = [
      ...(this.progress.active ? [this.progress.active] : []),
      ...this.queue.map((r) => chapterKey(r.reciter.id, r.surah.id)),
    ].filter((key) => key.startsWith(`${reciterId}-`))
    keys.forEach((key) => {
      const [r, s] = key.split('-').map(Number)
      this.pauseChapter(r!, s!)
    })
  }

  async clearChapter(reciter: Reciter, surah: Surah) {
    this.forget(reciter.id, surah.id)
    const keep = await this.openKeep()
    if (!keep) return
    const ownedBefore = new Set(this.owned)
    const keepBasmalah = keepsSharedBasmalahAfterDeleting(ownedBefore, reciter.id, surah.id)
    this.owned.delete(chapterKey(reciter.id, surah.id))
    writeOwned(this.owned)
    const basmalah = basmalahAudioUrl(reciter)
    const index = await this.keptIndex()
    const urls = chapterAudioUrls(reciter, surah).filter((url) => !(keepBasmalah && url === basmalah))
    await Promise.all(
      urls.map(async (url) => {
        await keep.delete(url)
        index.delete(url)
      }),
    )
    this.touched()
  }

  async clearReciter(reciter: Reciter) {
    for (const key of [...this.progress.paused.keys(), ...this.queue.map((r) => chapterKey(r.reciter.id, r.surah.id)), this.progress.active ?? '']) {
      if (!key.startsWith(`${reciter.id}-`)) continue
      const [, s] = key.split('-').map(Number)
      this.forget(reciter.id, s!)
    }
    for (const key of [...this.owned]) if (key.startsWith(`${reciter.id}-`)) this.owned.delete(key)
    writeOwned(this.owned)
    const keep = await this.openKeep()
    if (!keep) return
    const index = await this.keptIndex()
    await Promise.all(
      [...index.keys()]
        .filter((url) => parseEveryayahUrl(url)?.slug === reciter.slug)
        .map(async (url) => {
          await keep.delete(url)
          index.delete(url)
        }),
    )
    this.touched()
  }

  /** Delete all: every download and everything playback warmed. */
  async clearAll() {
    this.queue.length = 0
    if (this.progress.active) this.cancelActive = true
    this.owned.clear()
    writeOwned(this.owned)
    if (typeof caches !== 'undefined') {
      await Promise.all([caches.delete(AUDIO_KEEP_CACHE), caches.delete(AUDIO_CACHE)])
    }
    this.kept = new Map()
    this.publish({ paused: new Map(), revision: this.progress.revision + 1 })
  }

  /** Drop [surahId] from the queue, the paused set and the active slot. */
  private forget(reciterId: number, surahId: number) {
    const key = chapterKey(reciterId, surahId)
    const at = this.queue.findIndex((r) => chapterKey(r.reciter.id, r.surah.id) === key)
    if (at >= 0) this.queue.splice(at, 1)
    if (this.progress.active === key) this.cancelActive = true
    const paused = new Map(this.progress.paused)
    paused.delete(key)
    this.publish({ paused })
  }

  private async openKeep(): Promise<Cache | null> {
    if (typeof caches === 'undefined') return null
    try {
      return await caches.open(AUDIO_KEEP_CACHE)
    } catch {
      return null
    }
  }

  private enqueue(requests: DownloadRequest[]) {
    const paused = new Map(this.progress.paused)
    for (const request of requests) {
      const key = chapterKey(request.reciter.id, request.surah.id)
      paused.delete(key)
      if (this.progress.active === key) continue
      if (this.queue.some((r) => chapterKey(r.reciter.id, r.surah.id) === key)) continue
      this.queue.push(request)
    }
    this.publish({ paused, error: null })
    // Ask the browser not to evict what the reader chose to keep.
    void navigator.storage?.persist?.().catch(() => false)
    void this.run()
  }

  private async run() {
    if (this.running) return
    this.running = true
    try {
      while (this.queue.length > 0) {
        const request = this.queue.shift()!
        this.cancelActive = false
        if (await this.fetchChapter(request)) continue
        // The network went: park everything behind it too, for one Resume.
        const paused = new Map(this.progress.paused)
        for (const waiting of this.queue.splice(0)) {
          paused.set(chapterKey(waiting.reciter.id, waiting.surah.id), { ayah: 0, ayahCount: waiting.surah.ayahCount })
        }
        this.publish({ paused })
      }
    } finally {
      this.running = false
      this.publish({ active: null, ayah: 0, ayahCount: 0 })
    }
  }

  /** False when the chapter could not be fetched (offline / refused). */
  private async fetchChapter({ reciter, surah }: DownloadRequest): Promise<boolean> {
    const key = chapterKey(reciter.id, surah.id)
    const keep = await this.openKeep()
    if (!keep) {
      this.publish({ error: 'Downloads need a browser with offline storage.' })
      return false
    }
    const listen = await caches.open(AUDIO_CACHE).catch(() => null)
    const index = await this.keptIndex()
    const urls = chapterAudioUrls(reciter, surah)
    const ayahUrls = new Set(urls.filter((url) => parseEveryayahUrl(url)?.surah === surah.id))
    let held = [...ayahUrls].filter((url) => index.has(url)).length
    this.owned.add(key)
    writeOwned(this.owned)
    this.publish({ active: key, ayah: held, ayahCount: surah.ayahCount })

    const pending = urls.filter((url) => !index.has(url))
    let cursor = 0
    let failed = false
    const worker = async () => {
      while (!this.cancelActive && !failed) {
        const url = pending[cursor++]
        if (!url) return
        try {
          let response = (await listen?.match(url)) ?? null
          if (!response) {
            const fetched = await fetch(url, { mode: 'cors', credentials: 'omit' })
            if (!fetched.ok) throw new Error(`HTTP ${fetched.status}`)
            response = fetched
          }
          const blob = await response.blob()
          await keep.put(
            url,
            new Response(blob, {
              headers: {
                'Content-Type': response.headers.get('content-type') ?? 'audio/mpeg',
                'Content-Length': String(blob.size),
              },
            }),
          )
          index.set(url, blob.size)
          // The keep copy wins; drop the evictable duplicate.
          void listen?.delete(url)
          if (ayahUrls.has(url)) held++
          if (this.progress.active === key) this.publish({ ayah: held })
        } catch {
          failed = true
        }
      }
    }
    await Promise.all(Array.from({ length: FETCH_CONCURRENCY }, worker))
    if (failed && !this.cancelActive) {
      // Offline or refused: park the chapter as paused so Resume picks it up.
      const paused = new Map(this.progress.paused)
      paused.set(key, { ayah: held, ayahCount: surah.ayahCount })
      this.publish({ paused, error: "Couldn't reach the recitation server. Resume when you're back online." })
    }
    this.touched()
    return !failed || this.cancelActive
  }
}

export const recitationDownloads = new RecitationDownloadStore()
