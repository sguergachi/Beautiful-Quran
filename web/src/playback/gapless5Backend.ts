/**
 * Default verse-join transport: Gapless-5 for seamless joins.
 * Position is per-track ms — the same contract HighlightEngine consumes from
 * the legacy dual-element path.
 *
 * Loaded dynamically so unit tests / cold boot never touch `window` from the
 * UMD package until the flag is on in a real browser.
 *
 * Important Gapless-5 constraints we work around:
 * - Its HTML5 blob path does `audio.srcObject = blob`, which modern browsers
 *   reject. We only pass remote HTTPS URLs (never `blob:`).
 * - Firefox (and Safari) refuse AudioContext start outside a user gesture.
 *   Constructing the player after `await import(...)` loses that gesture, so we
 *   preload the module early and construct/resume synchronously on play.
 * - Never block play on full-surah fetch; loadLimit keeps a small window warm.
 * - No crossfade — abut verses at full level.
 */
import type { AudioPrefetcher } from './audioPrefetch'
import type { PlaylistItem } from './playlistPlan'
import { describePlaybackError } from './playbackErrors'

/** Keep a small decoded window warm without holding a whole surah in PCM. */
const LOAD_LIMIT = 5

export interface Gapless5BackendHandlers {
  onTime(positionMs: number, durationMs: number, index: number): void
  onPlay(): void
  onPause(): void
  onIndex(index: number): void
  onFinishedAll(): void
  /** `message` is reader-facing copy; the raw error is already logged. */
  onError(message: string): void
  onBuffering(buffering: boolean): void
}

/** Minimal surface we touch on the Gapless-5 player instance. */
interface Gapless5Player {
  loop: boolean
  singleMode: boolean
  context: { resume: () => Promise<void>; state: string } | null
  ontimeupdate: (ms: number, index: number) => void
  onplay: (path: string, analyser?: object) => void
  onpause: (path: string) => void
  onstop: (path: string) => void
  onnext: (from: string, to: string) => void
  onprev: (from: string, to: string) => void
  onfinishedall: () => void
  onerror: (path: string, error?: Error | string) => void
  onloadstart: (path: string) => void
  onload: (path: string, fullyLoaded: boolean) => void
  removeAllTracks(flush?: boolean): void
  addTrack(path: string): void
  gotoTrack(index: number, forcePlay?: boolean): void
  play(): void
  pause(): void
  stop(): void
  setPosition(ms: number): void
  setPlaybackRate(rate: number): void
  setCrossfade(ms: number): void
  getPosition(): number
  currentLength(): number
  getIndex(): number
  isPlaying(): boolean
  totalTracks(): number
}

type Gapless5Module = {
  Gapless5?: new (opts: Record<string, unknown>) => Gapless5Player
  default?: new (opts: Record<string, unknown>) => Gapless5Player
}

export class Gapless5Backend {
  private g5: Gapless5Player | null = null
  private loadGen = 0
  private lastSrcsKey = ''
  private disposed = false
  /** Cached UMD module — import alone does not create AudioContext. */
  private module: Gapless5Module | null = null
  private modulePromise: Promise<Gapless5Module> | null = null
  /**
   * While we are driving play/goto/sync ourselves, ignore library pause/stop
   * callbacks so a track teardown cannot clear the controller's play intent.
   */
  private suppressPause = 0

  constructor(private readonly handlers: Gapless5BackendHandlers) {}

  isReady(): boolean {
    return this.g5 != null && !this.disposed
  }

  /** True when the dynamic import finished (player may not exist yet). */
  isModuleLoaded(): boolean {
    return this.module != null
  }

  /**
   * Prefetch the Gapless-5 chunk without constructing a player / AudioContext.
   * Call when the developer flag turns on so the first Play is gesture-safe.
   */
  preloadModule(): Promise<void> {
    if (this.disposed) return Promise.resolve()
    if (this.module) return Promise.resolve()
    this.modulePromise ??= import('@regosen/gapless-5')
      .then((mod) => {
        this.module = mod as unknown as Gapless5Module
        return this.module
      })
      .catch((err) => {
        this.modulePromise = null
        throw err
      })
    return this.modulePromise.then(() => undefined)
  }

  /**
   * Construct the player if the module is already loaded. Safe to call from a
   * click handler with no `await` so Firefox treats AudioContext as user-started.
   * Returns true when a player is available after the call.
   */
  bootSync(): boolean {
    if (this.disposed) return false
    if (this.g5) return true
    if (!this.module) return false
    this.constructFromModule(this.module)
    return this.g5 != null
  }

  /**
   * Ensure the player exists. Prefers a prior [preloadModule] + [bootSync] path;
   * falls back to import + construct (may lose autoplay on strict browsers).
   */
  async ensureReady(): Promise<void> {
    if (this.disposed) return
    if (this.g5) return
    await this.preloadModule()
    if (this.disposed) return
    if (!this.g5 && this.module) this.constructFromModule(this.module)
  }

  private constructFromModule(mod: Gapless5Module): void {
    if (this.g5 || this.disposed) return
    const Gapless5Ctor = mod.Gapless5 ?? mod.default
    if (typeof Gapless5Ctor !== 'function') {
      throw new Error('Gapless-5 module did not export Gapless5')
    }
    // HTML5 + WebAudio: HTML5 starts on the play gesture (Firefox-friendly);
    // WebAudio takes over once decoded for sample-accurate joins. HTTPS only
    // — never blob: (HTML5 blob path uses illegal srcObject = Blob).
    // No crossfade — abut verses at full level (recitation should not blend).
    const player = new Gapless5Ctor({
      useWebAudio: true,
      useHTML5Audio: true,
      loadLimit: LOAD_LIMIT,
      crossfade: 0,
      shuffleButton: false,
      loop: false,
      singleMode: false,
    })

    player.ontimeupdate = (ms, index) => {
      this.handlers.onTime(
        Math.round(ms),
        Math.round(player.currentLength()),
        index,
      )
    }
    player.onplay = () => this.handlers.onPlay()
    player.onpause = () => {
      if (this.suppressPause > 0) return
      this.handlers.onPause()
    }
    player.onstop = () => {
      if (this.suppressPause > 0) return
      this.handlers.onPause()
    }
    player.onnext = () => this.handlers.onIndex(player.getIndex())
    player.onprev = () => this.handlers.onIndex(player.getIndex())
    player.onfinishedall = () => this.handlers.onFinishedAll()
    player.onerror = (_path, error) => {
      this.handlers.onError(describePlaybackError(error, 'gapless track failed'))
    }
    player.onloadstart = () => this.handlers.onBuffering(true)
    player.onload = () => this.handlers.onBuffering(false)
    player.setCrossfade(0)
    this.g5 = player
  }

  /**
   * Replace the track list with remote HTTPS URLs only.
   * [prefetcher] is unused for src selection (blob: URLs break Gapless-5) but
   * kept so the caller can still warm the HTTP cache in parallel.
   */
  syncPlaylist(items: PlaylistItem[], _prefetcher: AudioPrefetcher): void {
    const player = this.g5
    if (!player || this.disposed) return

    const srcs = items.map((item) => item.url)
    const key = srcs.join('\0')
    if (key === this.lastSrcsKey && player.totalTracks() === srcs.length) return

    this.withSuppressedPause(() => {
      this.lastSrcsKey = key
      player.removeAllTracks(true)
      for (const src of srcs) player.addTrack(src)
    })
  }

  /**
   * Unlock AudioContext. Must be invoked from a user-gesture stack (play tap
   * or flag toggle). Returns a promise for callers that want to await running.
   */
  resumeContext(): Promise<void> {
    const ctx = this.g5?.context
    if (!ctx) return Promise.resolve()
    if (ctx.state === 'running') return Promise.resolve()
    try {
      return ctx.resume().then(
        () => undefined,
        () => undefined,
      )
    } catch {
      return Promise.resolve()
    }
  }

  goto(index: number, autoplay: boolean): void {
    const player = this.g5
    if (!player) return
    this.withSuppressedPause(() => {
      player.gotoTrack(index, autoplay)
      // gotoTrack only plays when forcePlay is set; re-assert play so a
      // same-index re-entry still starts after a prior silent load.
      if (autoplay) player.play()
    })
  }

  play(): void {
    this.g5?.play()
  }

  pause(): void {
    this.g5?.pause()
  }

  /** Stop audio and drop tracks so the next sync starts clean. */
  clear(): void {
    this.loadGen++
    this.lastSrcsKey = ''
    this.withSuppressedPause(() => {
      try {
        this.g5?.stop()
        this.g5?.removeAllTracks(true)
      } catch {
        // dispose path — ignore mid-teardown errors
      }
    })
  }

  seekMs(ms: number): void {
    this.g5?.setPosition(Math.max(0, ms))
  }

  setSpeed(rate: number): void {
    this.g5?.setPlaybackRate(rate)
  }

  /**
   * Map our repeat modes onto Gapless-5's loop / singleMode flags.
   * Range wrap is handled by the player controller on index change.
   */
  applyRepeat(mode: 'off' | 'ayah' | 'surah' | 'range'): void {
    const player = this.g5
    if (!player) return
    if (mode === 'ayah') {
      player.singleMode = true
      player.loop = true
      return
    }
    player.singleMode = false
    player.loop = mode === 'surah'
  }

  getPositionMs(): number {
    return Math.round(this.g5?.getPosition() ?? 0)
  }

  getDurationMs(): number {
    return Math.round(this.g5?.currentLength() ?? 0)
  }

  getIndex(): number {
    return this.g5?.getIndex() ?? -1
  }

  isPlaying(): boolean {
    return Boolean(this.g5?.isPlaying())
  }

  dispose(): void {
    this.disposed = true
    this.clear()
    this.g5 = null
    this.module = null
    this.modulePromise = null
  }

  private withSuppressedPause(fn: () => void): void {
    this.suppressPause++
    try {
      fn()
    } finally {
      this.suppressPause--
    }
  }
}
