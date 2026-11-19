/**
 * Warms upcoming ayah MP3s so verse joins do not stall on the network.
 *
 * Mirrors Android [AudioPrefetcher]: read-ahead during playback, optional
 * whole-surah warm when the connection is not data-saver. Writes go into a
 * dedicated Cache API store (separate from the PWA shell cache) and an
 * in-memory blob-URL map so `<audio>` can start from a local object URL.
 *
 * Desktop playback uses the blob URL for its standby element. iOS playback
 * keeps one element on the original HTTPS URLs; the same full fetches warm its
 * HTTP cache without putting blob media into WebKit. Prefetch is best-effort;
 * failures never block playback.
 */

/**
 * Listening cache (Android RecitationCache "listen"): playback and read-ahead
 * write here, and it may be evicted.
 */
export const AUDIO_CACHE = 'beautiful-quran-audio-v1'

/**
 * Downloads (Android RecitationCache "keep"): chapters the reader chose to keep
 * from the Download manager. Never evicted by playback; read before
 * [AUDIO_CACHE]. The service worker keeps every `beautiful-quran-audio-*` cache.
 */
export const AUDIO_KEEP_CACHE = 'beautiful-quran-audio-keep-v1'

/**
 * Upcoming ayahs to keep warm beyond the one currently playing.
 * Higher than Android's deeper read-ahead because web has no ExoPlayer
 * playlist preload covering the immediate next item.
 */
export const READ_AHEAD_COUNT = 8

/** Soft cap on in-memory blob URLs (Cache API still holds the bytes). */
const MEMORY_CAP = 40

/** Soft cap on Cache API entries; oldest inserted are evicted first. */
const CACHE_ENTRY_CAP = 200

/** Parallel fetches so mobile networks fill the read-ahead window faster. */
const FETCH_CONCURRENCY = 3

type ConnectionLike = {
  saveData?: boolean
  effectiveType?: string
}

export type PrefetchFetch = (input: string, init?: RequestInit) => Promise<Response>

export interface AudioPrefetcherOptions {
  /** Override for tests. */
  fetch?: PrefetchFetch
  /** Override Cache API open (tests). */
  openCache?: () => Promise<Cache>
  /** Override the downloads (keep) cache open; null when there is none. */
  openKeepCache?: () => Promise<Cache | null>
  /** Override connection probe (tests). */
  connection?: () => ConnectionLike | undefined
  readAheadCount?: number
  memoryCap?: number
  cacheEntryCap?: number
  concurrency?: number
}

/**
 * Returns a playable src for [url]: a blob object URL when the clip is already
 * cached in memory, otherwise the original remote URL (browser HTTP cache may
 * still help).
 */
export class AudioPrefetcher {
  private readonly fetchImpl: PrefetchFetch
  private readonly openCache: () => Promise<Cache>
  private readonly openKeepCache: () => Promise<Cache | null>
  private readonly connection: () => ConnectionLike | undefined
  private readonly readAheadCount: number
  private readonly memoryCap: number
  private readonly cacheEntryCap: number
  private readonly concurrency: number

  /** url → object URL */
  private readonly memory = new Map<string, string>()
  /** Insertion order for Cache API LRU eviction. */
  private readonly cacheOrder: string[] = []
  private readonly inflight = new Map<string, Promise<string | null>>()
  /** URLs the player is about to need — never evict their blob URLs. */
  private pinned = new Set<string>()
  private readAheadGen = 0
  private warmGen = 0
  private released = false
  /** When true, whole-surah warm workers yield so join/read-ahead own bandwidth. */
  private warmPaused = false
  /** Last whole-surah warm playlist — restarted on [resumeWarm] if still incomplete. */
  private lastWarmUrls: string[] | null = null

  constructor(options: AudioPrefetcherOptions = {}) {
    this.fetchImpl = options.fetch ?? ((input, init) => fetch(input, init))
    this.openCache =
      options.openCache ??
      (async () => {
        if (typeof caches === 'undefined') {
          throw new Error('Cache API unavailable')
        }
        return caches.open(AUDIO_CACHE)
      })
    this.openKeepCache =
      options.openKeepCache ??
      (options.openCache
        ? async () => null
        : async () => (typeof caches === 'undefined' ? null : caches.open(AUDIO_KEEP_CACHE)))
    this.connection =
      options.connection ??
      (() => {
        const nav = navigator as Navigator & { connection?: ConnectionLike }
        return nav.connection
      })
    this.readAheadCount = options.readAheadCount ?? READ_AHEAD_COUNT
    this.memoryCap = options.memoryCap ?? MEMORY_CAP
    this.cacheEntryCap = options.cacheEntryCap ?? CACHE_ENTRY_CAP
    this.concurrency = Math.max(1, options.concurrency ?? FETCH_CONCURRENCY)
  }

  /** Blob URL if warm in memory; otherwise the remote URL. */
  resolveSrc(url: string): string {
    return this.memory.get(url) ?? url
  }

  /** True when we already hold a decoded blob URL for [url]. */
  isWarm(url: string): boolean {
    return this.memory.has(url)
  }

  /**
   * Mark [urls] as non-evictable in the memory LRU (current + read-ahead
   * window). Call whenever the playhead moves.
   */
  setPinned(urls: string[]): void {
    this.pinned = new Set(urls)
  }

  /**
   * Ensure [url] is fetched into Cache API + memory. Returns a blob object URL
   * on success, or null on failure / release.
   */
  ensure(url: string): Promise<string | null> {
    if (this.released) return Promise.resolve(null)
    const existing = this.memory.get(url)
    if (existing) {
      // Touch LRU.
      this.memory.delete(url)
      this.memory.set(url, existing)
      return Promise.resolve(existing)
    }

    const pending = this.inflight.get(url)
    if (pending) return pending

    const work = this.load(url)
    this.inflight.set(url, work)
    return work.finally(() => {
      this.inflight.delete(url)
    })
  }

  /**
   * Warm [readAheadCount] ayahs starting at the *next* playlist item
   * (index + 1). Cancels prior read-ahead generation so seeks do not stack.
   */
  readAhead(urls: string[], currentIndex: number): void {
    if (this.released || urls.length === 0) return
    const gen = ++this.readAheadGen
    const from = Math.max(0, currentIndex + 1)
    const next = urls.slice(from, from + this.readAheadCount)
    this.setPinned(urls.slice(Math.max(0, currentIndex), from + this.readAheadCount))
    void this.cacheAll(next, () => gen !== this.readAheadGen)
  }

  /**
   * Warm the whole surah when the user is not on a data-saver connection.
   * Cancels any prior warm job.
   */
  warmSurah(urls: string[]): void {
    if (this.released || urls.length === 0) return
    if (this.shouldSkipSurahWarm()) return
    this.lastWarmUrls = urls
    if (this.warmPaused) return
    const gen = ++this.warmGen
    void this.cacheAll(urls, () => gen !== this.warmGen || this.warmPaused)
  }

  /**
   * Pause whole-surah warm during verse joins so read-ahead + the active clip
   * own the network. In-flight warm workers exit on the next iteration.
   */
  pauseWarm(): void {
    if (this.warmPaused) return
    this.warmPaused = true
    this.warmGen++
  }

  /** Resume whole-surah warm after a join settles. */
  resumeWarm(): void {
    if (!this.warmPaused) return
    this.warmPaused = false
    const urls = this.lastWarmUrls
    if (urls) this.warmSurah(urls)
  }

  /** Drop in-flight work and revoke blob URLs. Cache API entries are kept. */
  release(): void {
    this.released = true
    this.readAheadGen++
    this.warmGen++
    this.warmPaused = false
    this.lastWarmUrls = null
    this.inflight.clear()
    this.pinned.clear()
    for (const objectUrl of this.memory.values()) {
      URL.revokeObjectURL(objectUrl)
    }
    this.memory.clear()
  }

  /** Test helper: how many blob URLs are held. */
  get memorySize(): number {
    return this.memory.size
  }

  private shouldSkipSurahWarm(): boolean {
    const conn = this.connection()
    if (!conn) return false
    if (conn.saveData) return true
    const slow = conn.effectiveType === 'slow-2g' || conn.effectiveType === '2g'
    return slow
  }

  private async cacheAll(urls: string[], cancelled: () => boolean): Promise<void> {
    const pending = urls.filter((url) => !this.memory.has(url))
    if (pending.length === 0) return

    let cursor = 0
    const workers = Array.from({ length: Math.min(this.concurrency, pending.length) }, async () => {
      while (true) {
        if (cancelled() || this.released || this.warmPaused) return
        const i = cursor++
        if (i >= pending.length) return
        await this.ensure(pending[i]!)
      }
    })
    await Promise.all(workers)
  }

  private async load(url: string): Promise<string | null> {
    if (this.released) return null
    try {
      let response: Response | undefined
      try {
        const cache = await this.openCache()
        // A downloaded chapter is read from the keep cache, never re-fetched.
        response = (await (await this.openKeepCache().catch(() => null))?.match(url)) ?? undefined
        response ??= await cache.match(url)
        if (!response) {
          const fetched = await this.fetchImpl(url, { mode: 'cors', credentials: 'omit' })
          if (!fetched.ok) return null
          // Store a clone before consuming the body.
          try {
            await cache.put(url, fetched.clone())
            this.trackCacheEntry(url, cache)
          } catch {
            // Quota or opaque edge case — still use the network body below.
          }
          response = fetched
        }
      } catch {
        // Cache API missing / broken — fall through to a direct fetch.
        const fetched = await this.fetchImpl(url, { mode: 'cors', credentials: 'omit' })
        if (!fetched.ok) return null
        response = fetched
      }

      if (this.released) return null
      const blob = await response.blob()
      if (this.released) return null
      const objectUrl = URL.createObjectURL(blob)
      this.remember(url, objectUrl)
      return objectUrl
    } catch {
      return null
    }
  }

  private remember(url: string, objectUrl: string): void {
    const prior = this.memory.get(url)
    if (prior && prior !== objectUrl) URL.revokeObjectURL(prior)
    // Refresh LRU position.
    this.memory.delete(url)
    this.memory.set(url, objectUrl)
    this.evictMemory()
  }

  private evictMemory(): void {
    // Prefer evicting unpinned entries. If everything is pinned, stop — better
    // to hold a few extra blobs than revoke the next ayah mid-join.
    while (this.memory.size > this.memoryCap) {
      let evicted = false
      for (const oldest of this.memory.keys()) {
        if (this.pinned.has(oldest)) continue
        const stale = this.memory.get(oldest)
        this.memory.delete(oldest)
        if (stale) URL.revokeObjectURL(stale)
        evicted = true
        break
      }
      if (!evicted) break
    }
  }

  private trackCacheEntry(url: string, cache: Cache): void {
    const existing = this.cacheOrder.indexOf(url)
    if (existing >= 0) this.cacheOrder.splice(existing, 1)
    this.cacheOrder.push(url)
    while (this.cacheOrder.length > this.cacheEntryCap) {
      const evict = this.cacheOrder.shift()
      if (evict && !this.pinned.has(evict)) void cache.delete(evict)
      else if (evict) this.cacheOrder.push(evict) // pinned — rotate to end and stop
      if (evict && this.pinned.has(evict)) break
    }
  }
}
