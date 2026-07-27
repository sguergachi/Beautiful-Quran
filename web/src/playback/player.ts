/**
 * Audio playback controller — HTMLAudioElement + Media Session.
 *
 * Desktop browsers promote a preloaded standby element at verse joins. iOS
 * deliberately keeps one persistent element and changes its HTTPS source:
 * WebKit supports one media stream reliably, while dual-element/blob promotion
 * can wedge its playback pipeline. [AudioPrefetcher] still warms upcoming URLs.
 *
 * Default join path is Gapless-5 (HTML5 + Web Audio hybrid) at 1×. Non-1×
 * speeds use the dual-element path so [HTMLMediaElement.preservesPitch] keeps
 * natural voice (Gapless-5 Web Audio rate-change always shifts pitch).
 * [setGapless5Enabled] is the developer preference; effective transport also
 * depends on speed. HighlightEngine still receives per-ayah positionMs either way.
 *
 * `isPlaying` is set on play *intent* (before canplay / `HTMLMediaElement.play`)
 * so reader chrome can recede on the tap rather than after the buffer fills.
 *
 * Polls position for HighlightEngine; publishes only on word boundaries upstream.
 */
import {
  type Reciter,
  type SurahContent,
} from '../data/models'
import { BASMALAH_PLAYLIST_AYAH, surahOpensWithBasmalahPreface } from '../domain/Basmalah'
import { AudioPrefetcher } from './audioPrefetch'
import { VERSE_FADE_IN_MS } from './audioFade'
import { Gapless5Backend } from './gapless5Backend'
import { isIOSMediaEnvironment } from './iosMedia'
import { JoinCoordinator } from './joinCoordinator'
import {
  HAVE_FUTURE_DATA,
  MediaElementTransport,
  type AudioElementFactory,
} from './mediaElementTransport'
import { PlaybackStallWatchdog } from './playbackStallWatchdog'
import { peekPlaylistNextIndex } from './playlistNext'
import { MediaSessionBridge, browserMediaSession } from './mediaSessionBridge'
import { wantsGapless5Transport } from './gaplessPolicy'
import { buildPlaylist, type PlaylistItem } from './playlistPlan'

export { wantsGapless5Transport } from './gaplessPolicy'

export interface NowPlaying {
  surahId: number
  ayah: number // 0 = basmalah preface
  reciterId: number
}

export interface PlayerState {
  nowPlaying: NowPlaying | null
  isPlaying: boolean
  /** True while fetching / waiting for enough audio to play (Android parity). */
  isBuffering: boolean
  positionMs: number
  durationMs: number
  speed: number
  repeatMode: 'off' | 'ayah' | 'surah' | 'range'
  repeatRange: { first: number; last: number } | null
  error: string | null
}

type Listener = (state: PlayerState) => void

const initial: PlayerState = {
  nowPlaying: null,
  isPlaying: false,
  isBuffering: false,
  positionMs: 0,
  durationMs: 0,
  speed: 1,
  repeatMode: 'off',
  repeatRange: null,
  error: null,
}

/** How soon before ayah end we insist the next clip has been fetched. */
const JOIN_PREP_REMAINING_S = 4

/** Silent-stall recoveries before we surface a hard stop. */
const STALL_RECOVERY_ATTEMPTS = 3

/**
 * While an autoplay src swap is in flight, ignore this many pause events from
 * the active element (load/pause races can fire more than once).
 */
const PAUSE_SUPPRESS_EVENTS = 3

export class PlayerController {
  private state: PlayerState = { ...initial }
  private listeners = new Set<Listener>()
  private playlist: PlaylistItem[] = []
  private index = 0
  private surahId = 0
  private reciter: Reciter | null = null
  private content: SurahContent | null = null
  private tickTimer: number | null = null
  private gaplessTimer: number | null = null
  private readonly prefetcher: AudioPrefetcher
  private readonly transport: MediaElementTransport
  private readonly joins: JoinCoordinator
  /** Bumps on every playIndex entry so a superseded load aborts after awaits. */
  private playToken = 0
  private readonly stallWatchdog = new PlaybackStallWatchdog()
  private readonly mediaSession: MediaSessionBridge
  /** True while performing the pause/play cycle that revives frozen Safari audio. */
  private recoveringStall = false
  /** Prevents rAF + `ended` from advancing the same audible boundary twice. */
  private gaplessAdvancing = false
  /**
   * Remaining pause events to ignore while swapping `src` during autoplay.
   * Consumed by the `pause` listener (and cleared when playback resumes).
   * A single-shot flag was not enough: load()/pause races can emit twice and
   * the second would clear isPlaying mid-join.
   */
  private pauseSuppressRemaining = 0
  /**
   * Developer preference for Gapless-5 joins (Settings). Effective use also
   * requires 1× speed — see [wantsGapless5Transport].
   */
  private gapless5Preferred = false
  /** Live transport: Gapless-5 when preferred and speed is 1×. */
  private gapless5Enabled = false
  private gapless5: Gapless5Backend | null = null
  /** Serializes transport swaps when speed is cycled quickly. */
  private transportChain: Promise<void> = Promise.resolve()

  constructor(
    prefetcher: AudioPrefetcher = new AudioPrefetcher(),
    singleElementPlayback = isIOSMediaEnvironment(),
    mediaSession: MediaSession | null = browserMediaSession(),
    createAudio?: AudioElementFactory,
  ) {
    this.prefetcher = prefetcher
    this.transport = new MediaElementTransport(
      singleElementPlayback,
      {
        timeUpdate: () => {
          this.onTime()
          this.maybePrepJoin()
        },
        ended: () => void this.onEnded(),
        play: () => {
          this.pauseSuppressRemaining = 0
          this.patch({ isPlaying: true, error: null })
        },
        playing: () => {
          this.pauseSuppressRemaining = 0
          this.setBuffering(false)
          this.patch({ isPlaying: true, error: null })
        },
        pause: () => this.onMediaPause(),
        waiting: () => this.setBuffering(true),
        stalled: () => this.setBuffering(true),
        error: () => this.onMediaError(),
        loadedMetadata: () => {
          this.patch({ durationMs: Math.round((this.active.duration || 0) * 1000) })
        },
        canPlayThrough: () => {
          this.setBuffering(false)
          void this.prepareStandby()
        },
      },
      createAudio,
    )
    this.joins = new JoinCoordinator(this.transport, prefetcher)
    this.mediaSession = new MediaSessionBridge(
      {
        play: () => void this.play(),
        pause: () => this.pause(),
        previous: () => void this.prev(),
        next: () => void this.next(),
      },
      mediaSession,
    )
  }

  private get active(): HTMLAudioElement {
    return this.transport.active
  }

  private get standby(): HTMLAudioElement | null {
    return this.transport.standby
  }

  private onMediaPause() {
    if (this.recoveringStall || this.gaplessAdvancing) return
    if (this.pauseSuppressRemaining > 0) {
      this.pauseSuppressRemaining--
      return
    }
    this.patch({ isPlaying: false })
  }

  private onMediaError() {
    this.setBuffering(false)
    if (this.playlist[this.index]?.ayah === BASMALAH_PLAYLIST_AYAH) {
      void this.playIndex(this.index + 1)
      return
    }
    this.patch({ error: 'Audio failed to load', isPlaying: false })
  }

  private setBuffering(isBuffering: boolean) {
    if (this.state.isBuffering === isBuffering) return
    this.patch({ isBuffering })
  }

  private beginPauseSuppress() {
    this.pauseSuppressRemaining = PAUSE_SUPPRESS_EVENTS
  }

  /**
   * In the last few seconds of an ayah, insist the next clip has been fetched.
   * Desktop then has a warm blob; iOS has a cache-warmed HTTPS request.
   */
  private maybePrepJoin() {
    if (this.gapless5Enabled) return
    const duration = this.active.duration
    if (!Number.isFinite(duration) || duration <= 0) return
    const remaining = duration - this.active.currentTime
    if (remaining > JOIN_PREP_REMAINING_S) return
    // Prefer join readiness over whole-surah warm bandwidth/CPU.
    this.prefetcher.pauseWarm()
    const nextIndex = this.peekNextIndex({ forStandby: true })
    if (nextIndex == null) return
    const url = this.playlist[nextIndex]?.url
    if (!url || this.prefetcher.isWarm(url)) {
      void this.prepareStandby()
      return
    }
    void this.prefetcher.ensure(url).then(() => {
      void this.prepareStandby()
    })
  }

  subscribe(fn: Listener): () => void {
    this.listeners.add(fn)
    fn(this.state)
    return () => this.listeners.delete(fn)
  }

  getState(): PlayerState {
    return this.state
  }

  get positionMs(): number {
    if (this.gapless5Enabled && this.gapless5) {
      return this.gapless5.getPositionMs()
    }
    return Math.round(this.active.currentTime * 1000)
  }

  /** Whether the experimental Gapless-5 join engine is active. */
  isGapless5Enabled(): boolean {
    return this.gapless5Enabled
  }

  /**
   * Developer preference for Gapless-5 joins. Effective only at 1× speed so
   * non-1× playback can keep natural pitch on the media-element path.
   * Reloads the playlist into the selected transport and resumes when possible.
   */
  async setGapless5Enabled(enabled: boolean): Promise<void> {
    this.gapless5Preferred = enabled
    await this.applyGapless5Transport()
  }

  /**
   * Apply the live Gapless-5 transport (or dual-element fallback). Idempotent.
   * Queued so rapid speed taps cannot interleave two swaps.
   */
  private applyGapless5Transport(): Promise<void> {
    const run = this.transportChain.then(() => this.applyGapless5TransportLocked())
    this.transportChain = run.then(
      () => undefined,
      () => undefined,
    )
    return run
  }

  private async applyGapless5TransportLocked(): Promise<void> {
    // Re-evaluate after waiting in the queue — a later setSpeed may have run.
    const enabled = wantsGapless5Transport(this.gapless5Preferred, this.state.speed)
    if (this.gapless5Enabled === enabled) return

    const resume = {
      playing: this.state.isPlaying,
      index: this.index,
      positionMs: this.positionMs,
      playlist: this.playlist,
      content: this.content,
      reciter: this.reciter,
      speed: this.state.speed,
      repeatMode: this.state.repeatMode,
    }

    this.playToken++
    this.pauseSuppressRemaining = 0
    this.recoveringStall = false
    this.gaplessAdvancing = false
    this.stopTick()
    this.setBuffering(false)

    if (this.gapless5Enabled) {
      this.gapless5?.dispose()
      this.gapless5 = null
    } else {
      this.joins.cancelFades()
      this.joins.stop()
    }

    this.gapless5Enabled = enabled

    if (enabled) {
      this.gapless5 = this.createGapless5Backend()
      // Prefetch the JS chunk only. Constructing Gapless5 creates AudioContext;
      // that must wait for a Play tap (Firefox blocks autoplay otherwise).
      try {
        await this.gapless5.preloadModule()
      } catch (e) {
        this.gapless5Enabled = false
        this.gapless5?.dispose()
        this.gapless5 = null
        this.patch({
          error: e instanceof Error ? e.message : 'Gapless-5 failed to load',
          isPlaying: false,
        })
        return
      }
    }

    if (!resume.content || !resume.reciter || resume.playlist.length === 0) {
      this.patch({ isPlaying: false })
      return
    }

    this.playlist = resume.playlist
    this.content = resume.content
    this.reciter = resume.reciter
    this.surahId = resume.content.surah.id
    this.index = Math.min(resume.index, this.playlist.length - 1)
    this.patch({ speed: resume.speed, isPlaying: false, error: null })
    this.gapless5?.applyRepeat(resume.repeatMode)
    this.gapless5?.setSpeed(resume.speed)
    this.transport.setSpeed(resume.speed)

    await this.playIndex(this.index, resume.playing, { fadeIn: false })
    if (resume.positionMs > 0) this.seekMs(resume.positionMs)
  }

  private createGapless5Backend(): Gapless5Backend {
    return new Gapless5Backend({
      onTime: (positionMs, durationMs, index) => {
        if (index >= 0 && index !== this.index) {
          this.syncGapless5Index(index)
        }
        this.patch({ positionMs, durationMs })
      },
      onPlay: () => {
        this.patch({ isPlaying: true, error: null })
        this.setBuffering(false)
        this.startTick()
      },
      onPause: () => {
        if (!this.gapless5Enabled) return
        this.stopTick()
        this.patch({ isPlaying: false })
      },
      onIndex: (index) => this.syncGapless5Index(index),
      onFinishedAll: () => {
        this.setBuffering(false)
        this.stopTick()
        this.patch({ isPlaying: false })
      },
      onError: (message) => {
        this.setBuffering(false)
        this.patch({ error: message, isPlaying: false })
      },
      onBuffering: (buffering) => this.setBuffering(buffering),
    })
  }

  /** Apply Gapless-5 auto-advance; wrap range-repeat when it walks past last. */
  private syncGapless5Index(index: number) {
    if (!this.gapless5Enabled || index < 0 || index >= this.playlist.length) return

    if (this.state.repeatMode === 'range' && this.state.repeatRange) {
      const item = this.playlist[index]
      if (item && item.ayah > this.state.repeatRange.last) {
        const first = this.playlist.findIndex(
          (p) => p.ayah === this.state.repeatRange!.first,
        )
        if (first >= 0) {
          this.gapless5?.goto(first, true)
          index = first
        }
      }
    }

    if (index === this.index) return
    this.index = index
    const item = this.playlist[index]
    if (!item) return
    this.patch({
      nowPlaying: {
        surahId: this.surahId,
        ayah: item.ayah,
        reciterId: this.reciter?.id ?? 0,
      },
      error: null,
    })
    this.updateMediaSession()
    this.schedulePrefetch()
  }

  private patch(partial: Partial<PlayerState>) {
    this.state = { ...this.state, ...partial }
    for (const fn of this.listeners) fn(this.state)
  }

  private onTime() {
    this.patch({
      positionMs: this.positionMs,
      durationMs: this.gapless5Enabled && this.gapless5
        ? this.gapless5.getDurationMs()
        : Math.round((this.active.duration || 0) * 1000),
    })
  }

  /**
   * Position poll aligned to the display refresh (rAF) while playing so
   * highlight / ink stay at ≥60 fps. Stopped on pause — no idle wakeups.
   */
  private startTick() {
    this.stopTick()
    if (this.gapless5Enabled) {
      const loop = () => {
        if (!this.state.isPlaying) {
          this.tickTimer = null
          return
        }
        if (this.gapless5) {
          const idx = this.gapless5.getIndex()
          if (idx >= 0 && idx !== this.index) this.syncGapless5Index(idx)
        }
        this.onTime()
        this.tickTimer = window.requestAnimationFrame(loop)
      }
      this.tickTimer = window.requestAnimationFrame(loop)
      return
    }
    this.stallWatchdog.reset(this.active.currentTime, performance.now())
    const loop = (nowMs: number) => {
      if (!this.state.isPlaying) {
        this.tickTimer = null
        return
      }
      this.onTime()
      this.maybeAdvanceAtAudibleEnd()
      this.detectSilentStall(nowMs)
      this.tickTimer = window.requestAnimationFrame(loop)
    }
    this.tickTimer = window.requestAnimationFrame(loop)
    this.scheduleGaplessBoundary()
  }

  /**
   * Mobile Safari can leave an audio element looking playable while its clock
   * is frozen and emit none of the normal buffering events. Surface buffering
   * and reproduce the user's effective pause/play workaround automatically.
   */
  private detectSilentStall(nowMs: number) {
    const duration = this.active.duration
    const isNearEnd =
      Number.isFinite(duration) && duration > 0 && duration - this.active.currentTime < 0.15
    const stalled = this.stallWatchdog.observe({
      positionS: this.active.currentTime,
      nowMs,
      isPlaying: this.state.isPlaying,
      isBuffering: this.state.isBuffering,
      isVisible: typeof document === 'undefined' || document.visibilityState !== 'hidden',
      isNearEnd,
    })
    if (stalled) void this.recoverSilentStall()
  }

  private async recoverSilentStall() {
    if (this.recoveringStall || !this.state.isPlaying) return
    const token = this.playToken
    this.recoveringStall = true
    this.setBuffering(true)
    this.beginPauseSuppress()
    try {
      for (let attempt = 0; attempt < STALL_RECOVERY_ATTEMPTS; attempt++) {
        if (token !== this.playToken || !this.state.isPlaying) return
        try {
          this.active.pause()
          await this.active.play()
          if (token !== this.playToken || !this.state.isPlaying) return
          this.setBuffering(false)
          this.stallWatchdog.reset(this.active.currentTime, performance.now())
          return
        } catch {
          // Retry after a brief settle — one failed play() is common after
          // backgrounding; only hard-stop after the budget is spent.
          if (attempt + 1 >= STALL_RECOVERY_ATTEMPTS) break
          await new Promise<void>((r) => globalThis.setTimeout(r, 120 * (attempt + 1)))
        }
      }
      if (token !== this.playToken) return
      this.stopTick()
      this.setBuffering(false)
      this.patch({
        isPlaying: false,
        error: 'Playback stalled',
      })
    } finally {
      this.recoveringStall = false
    }
  }

  private stopTick() {
    if (this.tickTimer != null) {
      window.cancelAnimationFrame(this.tickTimer)
      this.tickTimer = null
    }
    if (this.gaplessTimer != null) {
      window.clearTimeout(this.gaplessTimer)
      this.gaplessTimer = null
    }
  }

  private playlistUrls(): string[] {
    return this.playlist.map((p) => p.url)
  }

  private schedulePrefetch() {
    const urls = this.playlistUrls()
    this.prefetcher.readAhead(urls, this.index)
    if (this.gapless5Enabled) return
    void this.prepareAudioBounds(this.index)
    const next = this.peekNextIndex({ forStandby: true })
    if (next != null) void this.prepareAudioBounds(next)
    void this.prepareStandby()
  }

  /** Decode only the current/next warm blobs; failures retain ordinary playback. */
  private async prepareAudioBounds(index: number): Promise<void> {
    const item = this.playlist[index]
    if (!item) return
    await this.joins.prepareBounds(item, () => this.scheduleGaplessBoundary())
  }

  /**
   * EveryAyah files carry encoded quiet on both sides. At the measured audible
   * end of the current clip, start the next verse immediately — skip the padded
   * silence and do not fade-out-then-fade-in (that sequence was the audible gap
   * between ayahs). Prefer a warm standby promote; otherwise load next early
   * rather than sitting in silence until natural `ended`.
   */
  private maybeAdvanceAtAudibleEnd() {
    if (this.gapless5Enabled || this.gaplessAdvancing || !this.state.isPlaying) return
    const current = this.playlist[this.index]
    if (!this.joins.crossedAudibleEnd(current)) return

    const next = this.peekNextIndex({ forStandby: false })
    if (next == null) return
    const nextItem = this.playlist[next]
    if (!nextItem) return

    // Dual-element: wait only if standby is still loading the wrong index.
    // Do not require next-clip bounds — seek-to-audible-start is best-effort.
    if (
      this.standby != null &&
      !this.joins.isStandbyReady(next) &&
      this.prefetcher.isWarm(nextItem.url)
    ) {
      // Blob is warm but element not ready — kick prepare and try again next tick.
      void this.prepareStandby()
      return
    }

    const fromIndex = this.index
    this.gaplessAdvancing = true
    this.prefetcher.pauseWarm()
    void (async () => {
      let advanced = false
      try {
        // Full volume handoff — no sequential fadeOut + fadeIn silence.
        await this.playIndex(next, true, { fadeIn: false })
        advanced = this.index === next && this.state.isPlaying
      } finally {
        this.gaplessAdvancing = false
        // If advance failed, fall through so a dropped natural `ended` cannot
        // leave us silent on the padded tail.
        if (!advanced && this.state.isPlaying && this.index === fromIndex) {
          const ended =
            this.active.ended ||
            (Number.isFinite(this.active.duration) &&
              this.active.duration > 0 &&
              this.active.currentTime >= this.active.duration - 0.05)
          if (ended || this.joins.crossedAudibleEnd(this.playlist[this.index])) {
            void this.advanceAfterClip()
            return
          }
        }
        this.scheduleGaplessBoundary()
        this.prefetcher.resumeWarm()
      }
    })()
  }

  /** Timer-backed boundary so a short trim window cannot fall between rAFs. */
  private scheduleGaplessBoundary() {
    if (this.gapless5Enabled) return
    if (this.gaplessTimer != null) window.clearTimeout(this.gaplessTimer)
    this.gaplessTimer = null
    if (!this.state.isPlaying || this.gaplessAdvancing) return
    const item = this.playlist[this.index]
    const bounds = this.joins.boundsFor(item)
    const duration = this.active.duration
    if (!bounds || !Number.isFinite(duration) || duration - bounds.endS < 0.04) return
    const remainingS = Math.max(0, bounds.endS - this.active.currentTime)
    const delayMs = Math.max(8, remainingS / Math.max(0.1, this.state.speed) * 1_000)
    const expectedIndex = this.index
    this.gaplessTimer = window.setTimeout(() => {
      this.gaplessTimer = null
      this.maybeAdvanceAtAudibleEnd()
      if (this.index === expectedIndex && this.state.isPlaying && !this.gaplessAdvancing) {
        this.scheduleGaplessBoundary()
      }
    }, delayMs)
  }

  private seekActiveToAudibleStart(index: number) {
    this.joins.seekToAudibleStart(this.playlist[index])
  }

  /**
   * Load the next playlist item into the standby element (blob URL when warm).
   * Safe to call repeatedly; superseded work is ignored by JoinCoordinator.
   */
  private async prepareStandby(): Promise<void> {
    if (this.gapless5Enabled) return
    const nextIndex = this.peekNextIndex({ forStandby: true })
    if (nextIndex == null) {
      this.joins.clearStandby()
      return
    }
    const item = this.playlist[nextIndex]!
    await this.joins.prepareStandby(
      nextIndex,
      item,
      this.state.speed,
      () => this.scheduleGaplessBoundary(),
    )
  }

  /**
   * Next playlist index after the current item, honouring surah/range wrap.
   * Returns null when playback should stop, or when ayah-repeat is looping
   * the current clip (standby should stay empty).
   */
  private peekNextIndex(opts: { forStandby?: boolean } = {}): number | null {
    return peekPlaylistNextIndex(
      this.playlist.map((p) => p.ayah),
      this.index,
      {
        repeatMode: this.state.repeatMode,
        repeatRange: this.state.repeatRange,
        forStandby: opts.forStandby,
      },
    )
  }

  /**
   * Build playlist from [startAyah]. When startAyah is 1 and the surah opens
   * with a basmalah preface, prepend the dedicated basmalah clip. Mid-surah
   * starts (word taps) skip the preface.
   *
   * [opts.warm] defaults true for play-from paths; openSurah passes false so
   * the sheet peel is not competing with a whole-surah MP3 warm.
   * [opts.quiet] skips Media Session / nowPlaying churn on chapter open.
   */
  loadSurah(
    content: SurahContent,
    reciter: Reciter,
    startAyah = 1,
    opts: { warm?: boolean; quiet?: boolean } = {},
  ) {
    this.content = content
    this.reciter = reciter
    this.surahId = content.surah.id
    this.playlist = buildPlaylist(content, reciter, startAyah)
    this.index = 0
    this.joins.clearStandby()
    this.gapless5?.clear()
    this.prefetcher.resumeWarm()
    const urls = this.playlistUrls()
    // Always read-ahead the opening window; whole-surah warm is optional.
    if (opts.warm !== false) this.prefetcher.warmSurah(urls)
    this.prefetcher.readAhead(urls, -1) // include index 0 as "next" from -1
    void this.playIndex(0, /*autoplay*/ false, { quiet: opts.quiet === true })
    if (!opts.quiet) this.updateMediaSession()
  }

  /** Start (or resume) from a specific ayah; optionally skip basmalah. */
  async playFrom(ayah: number, includeBasmalah = ayah === 1) {
    if (!this.content || !this.reciter) return
    const start = includeBasmalah && ayah === 1 ? 1 : Math.max(1, ayah)
    this.loadSurah(this.content, this.reciter, start)
    if (!includeBasmalah || ayah > 1) {
      const idx = this.playlist.findIndex((p) => p.ayah === ayah)
      if (idx >= 0) this.index = idx
    }
    await this.playIndex(this.index, true)
  }

  /**
   * Seek to [positionMs] within [ayah] and play — Android `seekToWordAndPlay`.
   * Word taps never include the basmalah preface. Range-repeat is kept when
   * [ayah] is inside the loop; jumping outside abandons it.
   */
  async seekToWordAndPlay(ayah: number, positionMs: number) {
    if (!this.content || !this.reciter) return
    const range = this.state.repeatRange
    const keepRange =
      this.state.repeatMode === 'range' &&
      range != null &&
      ayah >= range.first &&
      ayah <= range.last
    if (this.state.repeatMode === 'range' && !keepRange) {
      this.patch({ repeatMode: 'off', repeatRange: null })
      this.active.loop = false
    }

    const np = this.state.nowPlaying
    const sameSession =
      np != null &&
      np.surahId === this.surahId &&
      np.reciterId === this.reciter.id

    let idx = sameSession ? this.playlist.findIndex((p) => p.ayah === ayah) : -1
    if (!sameSession || idx < 0) {
      // Fresh load or ayah outside a truncated mid-surah playlist.
      this.loadSurah(this.content, this.reciter, Math.max(1, ayah))
      idx = this.playlist.findIndex((p) => p.ayah === ayah)
      if (idx < 0) return
      await this.playIndex(idx, true)
      if (positionMs > 0) this.seekMs(positionMs)
      return
    }

    if (this.index !== idx || np.ayah !== ayah) {
      await this.playIndex(idx, true)
    } else if (!this.state.isPlaying) {
      await this.play()
      if (!this.state.isPlaying) return
    }
    this.seekMs(Math.max(0, positionMs))
  }

  private async playIndex(
    i: number,
    autoplay = true,
    opts: { quiet?: boolean; fadeIn?: boolean } = {},
  ) {
    if (i < 0 || i >= this.playlist.length) {
      this.setBuffering(false)
      this.patch({ isPlaying: false })
      return
    }
    if (this.gapless5Enabled) {
      await this.playIndexGapless5(i, autoplay, opts)
      return
    }
    const token = ++this.playToken
    const quiet = opts.quiet === true
    this.joins.cancelFades()
    if (!opts.fadeIn) this.active.volume = 1

    // Fast path: standby already holds this index — swap and play immediately.
    if (
      autoplay &&
      this.joins.isStandbyReady(i)
    ) {
      this.joins.promoteStandby(i)
      this.index = i
      this.seekActiveToAudibleStart(i)
      this.active.volume = opts.fadeIn ? 0 : 1
      // Intent before await play() so chrome recess does not wait on the
      // media element (and so a mid-await pause still sees isPlaying).
      this.publishNowPlaying({ playing: true })
      this.active.loop = this.state.repeatMode === 'ayah'
      // Prefer transport helper so defaultPlaybackRate stays in sync.
      this.transport.setSpeed(this.state.speed)
      this.updateMediaSession()
      try {
        this.setBuffering(false)
        await this.active.play()
        if (token !== this.playToken) return
        if (!this.state.isPlaying) {
          if (!opts.fadeIn) return
          // An ended/pause event from the retiring clip can land between the
          // standby promotion and play() resolution. The token proves that no
          // user pause superseded this automatic join.
          this.patch({ isPlaying: true, error: null })
        }
        if (opts.fadeIn) {
          void this.joins.fade(this.active, 'in', VERSE_FADE_IN_MS)
        }
        this.startTick()
        this.schedulePrefetch()
        this.prefetcher.resumeWarm()
      } catch (e) {
        if (token !== this.playToken) return
        this.active.volume = 1
        this.setBuffering(false)
        this.patch({
          error: e instanceof Error ? e.message : 'Playback blocked',
          isPlaying: false,
        })
      }
      return
    }

    this.index = i
    const item = this.playlist[i]!
    // Quiet chapter-open: set nowPlaying without a store fan-out storm — patch
    // once after src is assigned. Loud path publishes intent immediately.
    if (!quiet) {
      this.publishNowPlaying(autoplay ? { playing: true } : undefined)
      this.updateMediaSession()
    } else {
      // Still record identity for the next Play tap without emitting yet.
      const nextNow = {
        surahId: this.surahId,
        ayah: item.ayah,
        reciterId: this.reciter?.id ?? 0,
      }
      this.state = {
        ...this.state,
        nowPlaying: nextNow,
        positionMs: 0,
        error: null,
      }
    }
    // Show the spinner while we fetch the clip before assigning its source.
    if (autoplay && !this.prefetcher.isWarm(item.url)) {
      this.setBuffering(true)
    }
    // Quiet open: prefer the resolved source immediately; warm in background.
    const warmedSrc = quiet
      ? this.prefetcher.resolveSrc(item.url)
      : (await this.prefetcher.ensure(item.url)) ?? this.prefetcher.resolveSrc(item.url)
    if (token !== this.playToken) return
    // User may have paused while warming.
    if (autoplay && !this.state.isPlaying) {
      this.setBuffering(false)
      return
    }
    // iOS gets one persistent media element and an ordinary HTTPS source.
    // The read-ahead fetch above warms the browser HTTP cache, while avoiding
    // WebKit's fragile blob-media and multi-element playback paths.
    const src = this.transport.singleElement ? item.url : warmedSrc

    // Only suppress the pause→isPlaying:false sync when we intend to keep
    // playing after the src swap. Quiet loads (openSurah) must still clear it.
    if (autoplay) this.beginPauseSuppress()
    this.transport.loadActive({
      src,
      loop: this.state.repeatMode === 'ayah',
      playbackRate: this.state.speed,
      volume: opts.fadeIn ? 0 : 1,
    })
    if (quiet) {
      // One emit after src assign — listeners see nowPlaying without a second
      // mid-mount patch from publishNowPlaying.
      this.patch({
        nowPlaying: {
          surahId: this.surahId,
          ayah: item.ayah,
          reciterId: this.reciter?.id ?? 0,
        },
        positionMs: 0,
        durationMs: Math.round((this.active.duration || 0) * 1000),
        error: null,
      })
      void this.prefetcher.ensure(item.url)
      this.schedulePrefetch()
      return
    }
    this.patch({
      durationMs: Math.round((this.active.duration || 0) * 1000),
    })
    this.schedulePrefetch()

    if (autoplay) {
      // Re-assert after the src swap — pause listener may have raced, and
      // chrome must stay recessed through canplay.
      this.patch({ isPlaying: true, error: null })
      try {
        if (this.active.readyState < HAVE_FUTURE_DATA) {
          this.setBuffering(true)
          await this.transport.waitForCanPlay()
        }
        if (token !== this.playToken || !this.state.isPlaying) {
          this.setBuffering(false)
          return
        }
        // After canplay: src/load can leave rate at 1 even when state.speed is
        // a restored 0.75. Cycling speed worked because it set rate on ready
        // media — do that here so the first Play is already correct.
        this.transport.setSpeed(this.state.speed)
        this.seekActiveToAudibleStart(i)
        await this.active.play()
        if (token !== this.playToken) return
        if (!this.state.isPlaying) {
          if (!opts.fadeIn) return
          this.patch({ isPlaying: true, error: null })
        }
        if (opts.fadeIn) {
          void this.joins.fade(this.active, 'in', VERSE_FADE_IN_MS)
        }
        this.setBuffering(false)
        this.startTick()
        this.prefetcher.resumeWarm()
      } catch (e) {
        if (token !== this.playToken) return
        this.active.volume = 1
        this.setBuffering(false)
        if (this.playlist[this.index]?.ayah === BASMALAH_PLAYLIST_AYAH) {
          void this.playIndex(this.index + 1)
          return
        }
        this.patch({
          error: e instanceof Error ? e.message : 'Playback blocked',
          isPlaying: false,
        })
      }
    } else {
      this.setBuffering(false)
    }
  }

  /** Gapless-5 path for [playIndex] — same nowPlaying / Media Session contract. */
  private async playIndexGapless5(
    i: number,
    autoplay: boolean,
    opts: { quiet?: boolean; fadeIn?: boolean },
  ) {
    const token = ++this.playToken
    const quiet = opts.quiet === true
    let backend = this.gapless5
    if (!backend) {
      backend = this.createGapless5Backend()
      this.gapless5 = backend
    }

    this.index = i
    const item = this.playlist[i]!
    if (!quiet) {
      this.publishNowPlaying(autoplay ? { playing: true } : undefined)
      this.updateMediaSession()
    } else {
      this.state = {
        ...this.state,
        nowPlaying: {
          surahId: this.surahId,
          ayah: item.ayah,
          reciterId: this.reciter?.id ?? 0,
        },
        positionMs: 0,
        error: null,
      }
    }

    if (autoplay) this.setBuffering(true)

    try {
      // Firefox: AudioContext must be created/resumed on the user-gesture stack.
      // Prefer bootSync (module preloaded when the flag was enabled) so we never
      // await import() before constructing the context.
      if (!backend.isReady()) {
        if (!backend.bootSync()) {
          await backend.ensureReady()
          if (token !== this.playToken) return
        }
      }
      // Kick resume immediately while still in the play-tap call stack.
      if (autoplay) void backend.resumeContext()

      backend.syncPlaylist(this.playlist, this.prefetcher)
      backend.applyRepeat(this.state.repeatMode)
      backend.setSpeed(this.state.speed)
      // Warm HTTP cache (never pass blob: to Gapless-5 — breaks HTML5 path).
      void this.prefetcher.ensure(item.url)
      this.schedulePrefetch()

      if (quiet) {
        this.patch({
          nowPlaying: {
            surahId: this.surahId,
            ayah: item.ayah,
            reciterId: this.reciter?.id ?? 0,
          },
          positionMs: 0,
          durationMs: backend.getDurationMs(),
          error: null,
        })
        this.setBuffering(false)
        return
      }

      if (autoplay) {
        // playToken is the only cancel signal — do not re-check isPlaying after
        // the library's stop/unload callbacks; those used to abort every start.
        if (token !== this.playToken) return
        // HTML5 element path starts here under the play gesture; WebAudio
        // promotes once decode finishes (Gapless-5 hybrid).
        backend.goto(i, true)
        this.startTick()
        this.prefetcher.resumeWarm()
      } else {
        backend.goto(i, false)
        this.setBuffering(false)
      }
    } catch (e) {
      if (token !== this.playToken) return
      this.setBuffering(false)
      this.patch({
        error: e instanceof Error ? e.message : 'Playback blocked',
        isPlaying: false,
      })
    }
  }

  /**
   * Publish the current playlist item as nowPlaying.
   * When [opts.playing] is true, also set isPlaying immediately (play intent)
   * so UI chrome does not wait on buffer / `HTMLMediaElement.play()`.
   */
  private publishNowPlaying(opts?: { playing?: boolean }) {
    const item = this.playlist[this.index]
    this.patch({
      nowPlaying: item
        ? {
            surahId: this.surahId,
            ayah: item.ayah,
            reciterId: this.reciter?.id ?? 0,
          }
        : null,
      positionMs: 0,
      durationMs: Math.round((this.active.duration || 0) * 1000),
      error: null,
      ...(opts?.playing ? { isPlaying: true } : {}),
    })
  }

  async toggle() {
    if (!this.playlist.length) return
    // Prefer play-intent state over the media element: optimistic isPlaying
    // can be true while still paused/buffering, and a second tap must pause
    // immediately (including mid-buffer).
    if (this.state.isPlaying) {
      this.pause()
      return
    }
    await this.play()
  }

  pause() {
    // Bump playToken so any in-flight playIndex / waitForCanPlay aborts without
    // restarting audio after the user already paused.
    this.playToken++
    // User intent to stop wins — never let a pending swap suppress this pause.
    this.pauseSuppressRemaining = 0
    this.recoveringStall = false
    this.joins.cancelFades()
    this.prefetcher.resumeWarm()
    if (this.gapless5Enabled) {
      this.gapless5?.pause()
    } else {
      this.active.pause()
    }
    this.setBuffering(false)
    this.stopTick()
    // Explicit — do not wait for the 'pause' event (none fires if play() never
    // started after an optimistic isPlaying).
    this.patch({ isPlaying: false })
  }

  async play() {
    if (!this.playlist.length) return
    this.joins.cancelFades()
    // Recede chrome / flip the transport on the tap, before canplay.
    this.patch({ isPlaying: true, error: null })
    if (this.gapless5Enabled) {
      // Already on this track (pause mid-ayah): resume without gotoTrack, which
      // would restart the clip from the beginning.
      const backend = this.gapless5
      if (backend?.isReady() && backend.getIndex() === this.index) {
        const token = ++this.playToken
        this.startTick()
        try {
          void backend.resumeContext()
          backend.setSpeed(this.state.speed)
          backend.play()
          if (token !== this.playToken || !this.state.isPlaying) return
          this.setBuffering(false)
          this.prefetcher.resumeWarm()
        } catch (e) {
          if (token !== this.playToken) return
          this.setBuffering(false)
          this.stopTick()
          this.patch({
            isPlaying: false,
            error: e instanceof Error ? e.message : 'Playback blocked',
          })
        }
        return
      }
      // Cold start / new index — playIndex owns the playToken and must not
      // await full-surah work before starting audio (autoplay gesture).
      await this.playIndexGapless5(this.index, true, {})
      return
    }
    const token = ++this.playToken
    // Start the rAF position loop immediately so ink stays live even while
    // the media element is still warming (optimistic play intent).
    this.startTick()
    try {
      if (this.active.readyState < HAVE_FUTURE_DATA) {
        this.setBuffering(true)
        await this.transport.waitForCanPlay()
      }
      if (token !== this.playToken || !this.state.isPlaying) return
      // Restored non-1× must stick on resume of a quiet-open clip too.
      this.transport.setSpeed(this.state.speed)
      this.seekActiveToAudibleStart(this.index)
      await this.active.play()
      if (token !== this.playToken || !this.state.isPlaying) return
      this.setBuffering(false)
      this.startTick()
    } catch (e) {
      if (token !== this.playToken) return
      this.setBuffering(false)
      this.stopTick()
      this.patch({
        isPlaying: false,
        error: e instanceof Error ? e.message : 'Playback blocked',
      })
    }
  }

  async next() {
    // Media-session / UI next always advances (ayah-repeat only loops on ended).
    const next = this.peekNextIndex({ forStandby: false })
    if (next == null) {
      if (this.gapless5Enabled) this.gapless5?.pause()
      else this.active.pause()
      this.patch({ isPlaying: false })
      return
    }
    await this.playIndex(next, true)
  }

  async prev() {
    if (this.positionMs > 2000) {
      this.seekMs(0)
      return
    }
    const prev = this.index - 1
    if (prev < 0) {
      this.seekMs(0)
      return
    }
    await this.playIndex(prev, true)
  }

  /** Seek to [ayah] at [positionMs] within the loaded playlist (Android `seekTo`). */
  async seekToAyah(ayah: number, positionMs = 0) {
    let idx = this.playlist.findIndex((p) => p.ayah === ayah)
    const autoplay = this.state.isPlaying
    // Mid-surah playlists (Continue / word-tap) may omit earlier ayahs — rebuild.
    if (idx < 0) {
      if (!this.content || !this.reciter) return
      this.loadSurah(this.content, this.reciter, Math.max(1, ayah))
      idx = this.playlist.findIndex((p) => p.ayah === ayah)
      if (idx < 0) return
      await this.playIndex(idx, autoplay)
      this.seekMs(Math.max(0, positionMs))
      return
    }
    if (this.index !== idx) {
      await this.playIndex(idx, autoplay)
    }
    this.seekMs(Math.max(0, positionMs))
  }

  /** Restart the chapter-opening basmalah clip (no-op when the playlist has none). */
  seekToBasmalah() {
    void this.seekToAyah(BASMALAH_PLAYLIST_AYAH, 0)
  }

  /** Seek to [ayah] in the loaded playlist and play from its start. */
  async playLoadedFromAyah(ayah: number) {
    const startAtBasmalah =
      ayah === 1 &&
      this.content != null &&
      surahOpensWithBasmalahPreface(this.content.surah.id)
    const target = startAtBasmalah ? BASMALAH_PLAYLIST_AYAH : ayah
    let idx = this.playlist.findIndex((p) => p.ayah === target)
    if (idx < 0) {
      // Truncated playlist — rebuild from the requested ayah (Android always
      // has the full chapter loaded; web may have started mid-surah).
      await this.playFrom(ayah, startAtBasmalah)
      return
    }
    await this.playIndex(idx, true)
    this.seekMs(0)
  }

  async seekToWord(ayah: number, positionMs: number) {
    await this.seekToAyah(ayah, positionMs)
  }

  seekMs(ms: number) {
    if (this.gapless5Enabled) {
      this.gapless5?.seekMs(ms)
      this.onTime()
      return
    }
    this.active.currentTime = Math.max(0, ms / 1000)
    this.onTime()
    this.scheduleGaplessBoundary()
  }

  setSpeed(speed: number) {
    this.transport.setSpeed(speed)
    this.gapless5?.setSpeed(speed)
    this.patch({ speed })
    this.scheduleGaplessBoundary()
    // Non-1× → media path (preservesPitch); 1× → Gapless-5 if preferred.
    void this.applyGapless5Transport()
  }

  setRepeatMode(mode: PlayerState['repeatMode'], range: PlayerState['repeatRange'] = null) {
    this.patch({ repeatMode: mode, repeatRange: range })
    this.active.loop = mode === 'ayah'
    this.gapless5?.applyRepeat(mode)
    // Standby target may change when entering/leaving range or surah repeat.
    void this.prepareStandby()
  }

  stop() {
    this.playToken++
    this.pauseSuppressRemaining = 0
    this.recoveringStall = false
    this.joins.cancelFades()
    this.joins.stop()
    this.gapless5?.clear()
    this.prefetcher.resumeWarm()
    this.playlist = []
    this.index = 0
    this.stopTick()
    this.patch({ ...initial, speed: this.state.speed })
  }

  private async onEnded() {
    if (this.gapless5Enabled || this.gaplessAdvancing) return
    await this.advanceAfterClip()
  }

  /** Shared advance used by natural `ended` and the gapless safety net. */
  private async advanceAfterClip() {
    if (this.state.repeatMode === 'ayah') {
      // Prefer native loop; this is a fallback if loop was unset.
      this.active.currentTime = 0
      await this.active.play()
      return
    }
    const next = this.peekNextIndex({ forStandby: false })
    if (next == null) {
      this.setBuffering(false)
      this.active.pause()
      this.patch({ isPlaying: false })
      return
    }
    // If the next ayah is not warm yet, keep the play button spinning rather
    // than going silent at the verse boundary.
    const nextUrl = this.playlist[next]?.url
    if (nextUrl && !this.prefetcher.isWarm(nextUrl)) {
      this.setBuffering(true)
    }
    // Full volume — sequential join fades created an audible hole between ayahs.
    await this.playIndex(next, true, { fadeIn: false })
  }

  private updateMediaSession() {
    if (!this.content || !this.reciter) return
    const ayah = this.state.nowPlaying?.ayah
    const title =
      ayah === BASMALAH_PLAYLIST_AYAH
        ? `${this.content.surah.nameTransliteration} — Basmalah`
        : `${this.content.surah.nameTransliteration} ${ayah ?? ''}`
    this.mediaSession.update({
      title,
      artist: this.reciter.name,
      album: 'Beautiful Quran',
    })
  }
}

export const player = new PlayerController()
