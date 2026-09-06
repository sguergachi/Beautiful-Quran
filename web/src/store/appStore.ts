/**
 * App store — paper stack, playback, bookmarks, root viewer.
 *
 * Paper stack: -1=Bookmarks, 0=Chapters, 1=Reader, 2=Settings.
 * When no surah is open, Settings occupies layer 1.
 *
 * Prefer [useAppSelector] in screens that do not need word-tick ink state so
 * Home / Bookmarks / Settings do not re-render on every karaoke boundary.
 */
import { useCallback, useRef, useSyncExternalStore } from 'react'
import type { ActiveWord, Reciter, Surah, SurahContent, Segment, Word } from '../data/models'
import { dictionaryEntry, type DictionaryEntry } from '../data/dictionary'
import { lexiconEntry, type LexiconEntry } from '../data/lexicon'
import { QuranRepository } from '../data/repository'
import { runtimeMushafCache } from '../data/runtimeMushaf'
import {
  loadBookmarks,
  loadSettings,
  normalizeSettings,
  saveBookmarks,
  saveSettings,
  toggleBookmark,
  type Bookmark,
  type Settings,
} from '../data/settings'
import { HighlightEngine, PreparedTimings } from '../domain/HighlightEngine'
import {
  BASMALAH_PLAYLIST_AYAH,
  SURAH_FATIHA,
  surahOpensWithBasmalahPreface,
} from '../domain/Basmalah'
import { HighlightClock } from '../domain/HighlightClock'
import { OutputLatency } from '../domain/OutputLatency'
import {
  fastForwardAction,
  midpointMs,
  nextConsumedAyah,
} from '../domain/FastForwardPolicy'
import { getHighlightLeadMs } from '../ui/reader/InkEngine'
import { player, type PlayerState } from '../playback/player'
import {
  readerHighlightKey,
  readerHighlightState,
} from '../ui/reader/ReaderHighlightState'
import {
  BOOKMARKS_LAYER,
  COVER_LAYER,
  READER_LAYER,
  SETTINGS_LAYER,
  hasReaderOpen,
  settingsLayerFor,
  sheetAtLayer,
  type StackLayer,
} from '../ui/paper/stack'
import { wordClipBounds } from '../ui/root/wordClipBounds'

export type Sheet = 'bookmarks' | 'home' | 'reader' | 'settings'

export interface RootViewerState {
  surahId: number
  ayah: number
  position: number
  arabic: string
  translation: string
  transliteration: string
  root: string
  lemma: string
  pos: string
  features: string
  occurrenceCount: number
  lemmas: {
    lemma: string
    pos: string
    occurrenceCount: number
    gloss: string
  }[]
  occurrences: {
    surahId: number
    ayahNumber: number
    position: number
    arabic: string
    translation: string
    surahNameTransliteration: string
  }[]
  /** Lane's article for this root; null while loading or when he has none. */
  lexicon: LexiconEntry | null
  /** English Wiktionary senses for this lemma; null while loading or on miss. */
  dictionary: DictionaryEntry | null
  /** True while the header speaker is auditioning this word. */
  isPlayingWord: boolean
}

const START_SEEK_GRACE_MS = 1_500
const WORD_CLIP_POLL_MS = 16
const WORD_CLIP_READY_TIMEOUT_MS = 4_000
const WORD_CLIP_SEEK_SLACK_MS = 40

export interface AppState {
  ready: boolean
  error: string | null
  loadLabel: string
  /** 0..1 while the DB bytes stream in; null for indeterminate phases. */
  loadProgress: number | null
  /** Paper-stack position: -1 Bookmarks · 0 Chapters · 1 Reader · 2 Settings. */
  stackLayer: StackLayer
  /** Derived top sheet name (for labels / legacy checks). */
  sheet: Sheet
  surahs: Surah[]
  reciters: Reciter[]
  settings: Settings
  bookmarks: Bookmark[]
  content: SurahContent | null
  player: PlayerState
  activeWord: ActiveWord | null
  activeAyah: number | null
  activeBasmalah: boolean
  hasTimings: boolean
  rootViewer: RootViewerState | null
  /** True while the ink-bleed exit hole is animating; data stays until it ends. */
  rootViewerClosing: boolean
  followEnabled: boolean
  /**
   * Session-only verse the reader should settle on when a chapter opens.
   * Not Continue Listening — that only tracks verses actually recited
   * ([settings.lastSurah] / [settings.lastAyah]).
   */
  openAyah: number
  /** Bumps for each explicit reader open, including a bookmark in the current surah. */
  readerOpenRevision: number
  /**
   * Pending home word-search flash — set by [openSurah] with a word position,
   * consumed by the reader after focus settles.
   */
  pendingSearchFlash: {
    ayah: number
    wordPosition: number
    wordPositions: number[]
    text?: string
  } | null
}

type Listener = () => void

function deriveSheet(stackLayer: StackLayer, hasReader: boolean): Sheet {
  return sheetAtLayer(stackLayer, hasReader)
}

/**
 * Surah timings plus, for preface surahs, Al-Fatihah 1:1 segments under
 * [BASMALAH_PLAYLIST_AYAH] so the lead-in clip and the calligraphy wash share
 * the same word clock. Android `ReaderViewModel.timingsWithBasmalahLeadIn`.
 */
function withBasmalahLeadIn(
  map: Map<number, Segment[]>,
  reciterId: number,
  surahId: number,
): Map<number, Segment[]> {
  if (!surahOpensWithBasmalahPreface(surahId)) return map
  const basmalah = QuranRepository.timings(reciterId, SURAH_FATIHA).get(1)
  if (!basmalah) return map
  // The repository caches its maps, so copy before adding the sentinel.
  return new Map(map).set(BASMALAH_PLAYLIST_AYAH, basmalah)
}

class AppStore {
  private listeners = new Set<Listener>()
  private prepared = new Map<number, PreparedTimings>()
  /** Raw timing segments for the open surah — PreparedTimings built on demand. */
  private timingSegments = new Map<number, Segment[]>()
  private lastActiveKey = ''
  private lastEmitKey = ''
  private readonly highlightClock = new HighlightClock()
  /** Bumps on genuine seeks so replaying the same Active word restarts ink. */
  private inkActivation = 0
  private lastInkMediaKey = ''
  private lastInkClockMs = -1
  /** Last applied word-ink lead so a lab change can reset the clock. */
  private lastHighlightLeadMs = -1
  /**
   * User seek target (ayah → ms) applied on the next poll once that ayah is
   * the media item — so ink jumps to the tapped word without waiting for the
   * media position estimate to catch up (Android [forcedHighlight] parity).
   */
  private forcedHighlight: { ayah: number; seekMs: number } | null = null
  /** Bumps when a newer openSurah supersedes an in-flight peel→load. */
  private openToken = 0
  /**
   * Reading session captured when the root viewer opened. Normal close restores
   * the chapter queue; autoplay only when [wasPlaying]. Concordance jumps
   * discard it. The isolated word speaker may move the playhead — this
   * snapshot is the source of truth for restore (Android
   * [ReaderPlaybackSnapshot] parity).
   */
  private rootViewerSnapshot: {
    ayah: number
    positionMs: number
    wasPlaying: boolean
  } | null = null
  /** Cancels an in-flight word-clip poll / ready wait. */
  private wordClipToken = 0
  private wordClipTimer: ReturnType<typeof setTimeout> | null = null
  /**
   * Long-ayah midpoint skip already issued for this ayah (0 = none).
   * Decided by intent, not positionMs, because seeks are async — a second FF
   * before position catches up must not re-seek midpoint (#560).
   */
  private longAyahMidpointConsumed = 0

  state: AppState = {
    ready: false,
    error: null,
    loadLabel: 'Opening the book…',
    loadProgress: null,
    stackLayer: COVER_LAYER,
    sheet: 'home',
    surahs: [],
    reciters: [],
    settings: loadSettings(),
    bookmarks: loadBookmarks(),
    content: null,
    player: player.getState(),
    activeWord: null,
    activeAyah: null,
    activeBasmalah: false,
    hasTimings: false,
    rootViewer: null,
    rootViewerClosing: false,
    followEnabled: true,
    openAyah: 1,
    readerOpenRevision: 0,
    pendingSearchFlash: null,
  }

  constructor() {
    runtimeMushafCache?.subscribe(() => {
      QuranRepository.invalidateRuntimeMushafViews()
      const current = this.state.content
      if (current) this.set({ content: QuranRepository.surahContent(current.surah.id) })
    })
    runtimeMushafCache?.subscribeDiagnostics(() => {
      if (this.state.ready) return
      const status = runtimeMushafCache.status()
      if (status.phase === 'refreshing') {
        const progress = runtimeMushafCache.downloadProgress()
        const loadLabel = runtimeMushafCache.haveRequestsSettled() && status.apiCalls > 0
          ? `Saving Quran pages… ${status.apiCalls} requests complete`
          : progress != null && progress.completed === progress.total
            ? 'Checking Quran pages…'
            : progress
              ? `Downloading Quran pages… ${progress.completed} of ${progress.total} requests`
          : status.apiCalls > 0
            ? `Downloading Quran pages… ${status.apiCalls} API requests`
            : 'Preparing Quran pages…'
        this.set({
          loadLabel,
          loadProgress: runtimeMushafCache.haveRequestsSettled() ? 1 : progress?.fraction ?? null,
        })
      }
    })
    window.addEventListener('online', () => {
      void runtimeMushafCache?.refreshIfNeeded()
    })
    player.subscribe((ps) => {
      const prev = this.state.player
      this.state = { ...this.state, player: ps }
      this.recomputeActive(ps)

      // Continue Listening tracks verses actually recited, not open/scroll.
      // Quiet chapter open parks a new nowPlaying while pause is async — never
      // treat a surah identity change while already "playing" as a listen.
      const np = ps.nowPlaying
      if (ps.isPlaying && np != null && np.ayah >= 1) {
        const surahChanged = prev.nowPlaying?.surahId !== np.surahId
        const ayahChanged = prev.nowPlaying?.ayah !== np.ayah
        const startedPlaying = !prev.isPlaying
        if (startedPlaying || (!surahChanged && ayahChanged)) {
          this.rememberListened(np.surahId, np.ayah)
        }
      }

      // Emit only on UI-visible changes — not every 33 ms position tick.
      // Chrome recess is derived in ReaderScreen from debounced recitingActive
      // (not raw isPlaying) so ayah joins do not flash the faded chrome.
      const emitKey = [
        ps.isPlaying,
        ps.isBuffering,
        ps.nowPlaying?.surahId,
        ps.nowPlaying?.ayah,
        ps.repeatMode,
        ps.error ?? '',
        this.lastActiveKey,
      ].join('|')
      if (emitKey !== this.lastEmitKey) {
        this.lastEmitKey = emitKey
        this.emit()
        return
      }
      // Still emit if nowPlaying identity changed without key catch
      if (
        prev.nowPlaying?.surahId !== ps.nowPlaying?.surahId ||
        prev.nowPlaying?.ayah !== ps.nowPlaying?.ayah ||
        prev.isPlaying !== ps.isPlaying ||
        prev.isBuffering !== ps.isBuffering ||
        prev.repeatMode !== ps.repeatMode ||
        prev.error !== ps.error
      ) {
        this.emit()
      }
    })
  }

  subscribe = (fn: Listener) => {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  getSnapshot = () => this.state

  private emit() {
    for (const fn of this.listeners) fn()
  }

  private set(partial: Partial<AppState>) {
    this.state = { ...this.state, ...partial }
    this.emit()
  }

  private hasReader(): boolean {
    // Match App.tsx — retain the sheet check for transient reader ownership.
    return hasReaderOpen(this.state.content, this.state.sheet)
  }

  /** Animate / snap the paper stack to a layer. */
  setStackLayer(layer: number) {
    const max = settingsLayerFor(this.hasReader())
    const min = this.state.bookmarks.length > 0 ? BOOKMARKS_LAYER : COVER_LAYER
    const stackLayer = Math.max(min, Math.min(max, Math.round(layer))) as StackLayer
    this.set({
      stackLayer,
      sheet: deriveSheet(stackLayer, this.hasReader()),
    })
  }

  /** Peel one sheet back (Settings → Reader → Chapters). */
  goBack() {
    // Match Android: only consume back while the bleed is open (not mid-exit).
    if (this.state.rootViewer && !this.state.rootViewerClosing) {
      this.closeRootViewer()
      return
    }
    if (this.state.stackLayer === BOOKMARKS_LAYER) {
      this.setStackLayer(COVER_LAYER)
    } else {
      this.setStackLayer(this.state.stackLayer - 1)
    }
  }

  /** Jump to a specific sheet by clicking its peek. */
  revealLayer(layer: StackLayer) {
    this.setStackLayer(layer)
  }

  async init() {
    try {
      await QuranRepository.ensureReady((p) => {
        if (p.phase === 'wasm') {
          this.set({ loadLabel: 'Preparing the reader…', loadProgress: null })
          return
        }
        if (p.phase === 'asm') {
          this.set({
            loadLabel: 'Preparing the reader (compatibility)…',
            loadProgress: null,
          })
          return
        }
        if (p.total > 0) {
          const pct = Math.min(99, Math.round((p.loaded / p.total) * 100))
          this.set({
            loadLabel: `Loading the book… ${pct}%`,
            loadProgress: Math.min(0.99, p.loaded / p.total),
          })
        } else {
          this.set({ loadLabel: 'Loading the book…', loadProgress: null })
        }
      })
      // Authenticated QF resources align onto the bundled canonical word rows.
      this.set({ loadLabel: 'Preparing Quran pages…', loadProgress: null })
      await runtimeMushafCache.restore()
      const surahs = QuranRepository.surahs()
      const reciters = QuranRepository.reciters()
      this.set({
        ready: true,
        surahs,
        reciters,
        error: null,
        loadLabel: '',
        loadProgress: 1,
      })
      player.setSpeed(this.state.settings.playbackSpeed)
      if (this.state.settings.gapless5Playback) {
        // Prefetch the Gapless-5 chunk at boot so the first Play can construct
        // AudioContext synchronously under the click (Firefox autoplay policy).
        void player.setGapless5Enabled(true)
      }
      // sql.js is main-thread only: warm one chapter per idle slice instead of
      // freezing the opening ceremony with a single whole-Quran object scan.
      void QuranRepository.preloadAllSurahContent(this.state.settings.lastSurah)
      // Only install the offline worker after a successful boot so a failed
      // first paint cannot pin a poisoned shell in the Cache API.
      void import('../swRegistration').then((m) => m.registerServiceWorker())
    } catch (e) {
      this.set({
        ready: false,
        loadProgress: null,
        error: e instanceof Error ? e.message : 'Failed to load Quran data',
      })
    }
  }

  setSheet(sheet: Sheet) {
    if (sheet === 'bookmarks') this.setStackLayer(BOOKMARKS_LAYER)
    else if (sheet === 'home') this.setStackLayer(COVER_LAYER)
    else if (sheet === 'reader') this.setStackLayer(READER_LAYER)
    else this.setStackLayer(settingsLayerFor(this.hasReader()))
  }

  updateSettings(patch: Partial<Settings>) {
    const settings = normalizeSettings({ ...this.state.settings, ...patch })
    saveSettings(settings)
    this.set({ settings })
    if (patch.playbackSpeed != null) player.setSpeed(patch.playbackSpeed)
    if (patch.gapless5Playback != null) {
      void player.setGapless5Enabled(settings.gapless5Playback)
    }
    if (patch.reciterId != null && this.state.content) {
      const reciter = this.state.reciters.find((r) => r.id === patch.reciterId)
      if (reciter) this.reloadTimingsAndReciter(reciter)
    }
  }

  private reloadTimingsAndReciter(reciter: Reciter) {
    if (!this.state.content) return
    const surahId = this.state.content.surah.id
    const map = withBasmalahLeadIn(
      QuranRepository.timings(reciter.id, surahId),
      reciter.id,
      surahId,
    )
    this.timingSegments = map
    this.prepared = new Map()
    const ayah = this.state.player.nowPlaying?.ayah ?? this.state.settings.lastAyah
    const start = ayah > 0 ? ayah : 1
    this.ensurePrepared(start)
    this.ensurePrepared(start + 1)
    player.loadSurah(this.state.content, reciter, start, { warm: false })
    this.set({ hasTimings: reciter.hasTimings && map.size > 0 })
  }

  /** Build PreparedTimings for [ayah] on first use (open must not prep the whole surah). */
  private ensurePrepared(ayah: number): PreparedTimings | null {
    if (ayah < 1) return null
    const existing = this.prepared.get(ayah)
    if (existing) return existing
    const segs = this.timingSegments.get(ayah)
    if (!segs || segs.length === 0) return null
    const prepared = HighlightEngine.PreparedTimings.prepare(segs)
    this.prepared.set(ayah, prepared)
    return prepared
  }

  /**
   * Decode one chapter into the repository cache before navigation commits.
   * Home calls this on hover, focus, and pointer-down so the subsequent click
   * only swaps already-materialized data into the paper stack.
   */
  prepareSurah(surahId: number) {
    QuranRepository.surahContent(surahId)
  }

  openSurah(
    surahId: number,
    ayah = 1,
    wordPosition?: number,
    searchText?: string,
    wordPositions: number[] = [],
  ) {
    const reciter =
      this.state.reciters.find((r) => r.id === this.state.settings.reciterId) ??
      this.state.reciters[0]
    if (!reciter) return

    // openAyah is session navigation only — Continue Listening stays put until
    // the user actually plays a verse (see [rememberListened]).
    const openAyah = Math.max(1, ayah)
    const readerOpenRevision = this.state.readerOpenRevision + 1
    const flashWord = wordPosition != null && wordPosition >= 0 ? wordPosition : null
    const flash = flashWord != null
      ? {
          ayah: openAyah,
          wordPosition: flashWord,
          wordPositions: [...new Set([
            ...wordPositions.filter((position) => position > 0),
            ...(flashWord > 0 ? [flashWord] : []),
          ])],
          text: searchText,
        }
      : null
    // Navigating away from the lexicon never resumes the pre-open session.
    const rootViewerClosing = this.state.rootViewer != null
    if (rootViewerClosing) {
      this.rootViewerSnapshot = null
      this.stopWordAudition(true)
    }

    // Same chapter already loaded — peel only (no remount). The CSS sheet
    // glide is the whole point of this path.
    if (this.state.content?.surah.id === surahId) {
      this.set({
        stackLayer: READER_LAYER,
        sheet: 'reader',
        openAyah,
        readerOpenRevision,
        followEnabled: true,
        rootViewer: this.state.rootViewer,
        rootViewerClosing,
        pendingSearchFlash: flash,
      })
      return
    }

    const token = ++this.openToken

    // Materialize chapter text before changing sheets. Most clicks hit the
    // pointer/focus cache warmed by Home; cold programmatic opens do the same
    // work while the current paper remains visible, never on an empty reader.
    const content = QuranRepository.surahContent(surahId)
    if (token !== this.openToken) return

    // Never let highlight lookups from the previous chapter leak into the new
    // sheet while audio metadata hydrates independently.
    this.timingSegments = new Map()
    this.prepared = new Map()
    this.longAyahMidpointConsumed = 0

    // One state commit: the first reader frame already contains Quran text.
    this.set({
      stackLayer: READER_LAYER,
      sheet: 'reader',
      content,
      openAyah,
      readerOpenRevision,
      hasTimings: false,
      activeWord: null,
      activeAyah: null,
      activeBasmalah: false,
      followEnabled: true,
      rootViewer: this.state.rootViewer,
      rootViewerClosing,
      pendingSearchFlash: flash,
    })

    // Audio and timings begin only after the content-bearing reader frame has
    // been handed to the browser. Neither can delay chapter navigation.
    requestAnimationFrame(() => {
      const runAudio = () => {
        if (token !== this.openToken) return
        player.loadSurah(content, reciter, ayah, { warm: false, quiet: true })
      }
      setTimeout(runAudio, 0)

      // Timings are independent of the initial text render. Parse them in an
      // idle task and refresh the current highlight if Play was tapped first.
      const loadTimings = () => {
        if (token !== this.openToken) return
        const map = withBasmalahLeadIn(
          QuranRepository.timings(reciter.id, surahId),
          reciter.id,
          surahId,
        )
        if (token !== this.openToken) return
        this.timingSegments = map
        this.ensurePrepared(ayah)
        this.ensurePrepared(ayah + 1)
        this.recomputeActive(this.state.player)
        this.set({ hasTimings: reciter.hasTimings && map.size > 0 })
      }
      const ric = (
        globalThis as unknown as {
          requestIdleCallback?: (fn: () => void, opts?: { timeout: number }) => number
        }
      ).requestIdleCallback
      if (typeof ric === 'function') ric(loadTimings, { timeout: 250 })
      else setTimeout(loadTimings, 32)
    })
  }

  /** Clears the one-shot search-hit flash after the reader finishes pulsing. */
  clearSearchFlash() {
    if (this.state.pendingSearchFlash == null) return
    this.set({ pendingSearchFlash: null })
  }

  /**
   * Session open/jump anchor — Android reader focus without Continue update.
   * Rail jumps call this so Play starts here; Continue Listening only updates
   * when audio actually plays ([rememberListened]).
   */
  onAyahBecameActive(ayah: number) {
    if (!this.state.content || ayah < 1) return
    const clamped = Math.min(
      this.state.content.surah.ayahCount,
      Math.max(1, ayah),
    )
    if (this.state.openAyah === clamped) return
    this.set({ openAyah: clamped })
  }

  /**
   * Persist Continue Listening for a verse the user actually heard.
   * [surahId] may come from the player when content is briefly out of sync.
   */
  private rememberListened(surahId: number, ayah: number) {
    if (surahId < 1 || surahId > 114 || ayah < 1) return
    const count = this.state.content?.surah.id === surahId
      ? this.state.content.surah.ayahCount
      : null
    const clamped = count != null ? Math.min(count, ayah) : ayah
    if (
      this.state.settings.lastSurah === surahId &&
      this.state.settings.lastAyah === clamped
    ) {
      return
    }
    const settings = {
      ...this.state.settings,
      lastSurah: surahId,
      lastAyah: clamped,
    }
    saveSettings(settings)
    this.set({ settings, openAyah: clamped })
  }

  /**
   * Play / pause — Android reader `onPlayPause` parity when [opts] is passed.
   *
   * Without opts (home float): toggle the loaded clip, or start from lastAyah.
   * With opts from the reader: start at the selected / jumped ayah instead of
   * resuming the chapter-opening clip left by `openSurah` / `loadSurah`.
   */
  async playPause(opts?: {
    selectedAyah?: number
    pendingJump?: boolean
    /** When true, enable lyric follow in the same emit as play (no double render). */
    enableFollow?: boolean
  }) {
    if (!this.state.content) return
    const ps = this.state.player
    const content = this.state.content
    const np = ps.nowPlaying
    const thisSurahLoaded = np?.surahId === content.surah.id
    const selected =
      opts?.selectedAyah != null && opts.selectedAyah > 0
        ? opts.selectedAyah
        : this.state.settings.lastAyah || 1

    if (opts?.enableFollow && !ps.isPlaying) {
      // Batch before the player emit so React sees follow + isPlaying together.
      this.state = { ...this.state, followEnabled: true }
    }

    if (thisSurahLoaded) {
      if (ps.isPlaying) {
        await player.toggle()
        return
      }
      // Paused but loaded: a pending rail jump must start there — not resume
      // the chapter-opening clip parked by loadSurah.
      if (opts?.pendingJump) {
        this.noteInkRestart(selected, 0)
        await player.playLoadedFromAyah(selected)
        this.rememberListened(content.surah.id, selected)
        return
      }
      await player.toggle()
      return
    }

    await this.playAyah(selected)
  }

  /** Seek within the loaded playlist without forcing play (rail jump while loaded). */
  async seekToAyah(ayah: number) {
    this.noteInkRestart(ayah, 0)
    await player.seekToAyah(ayah)
  }

  async playAyah(ayah: number, includeBasmalah = ayah === 1) {
    this.noteInkRestart(ayah, 0)
    await player.playFrom(ayah, includeBasmalah && ayah === 1)
    const surahId = this.state.content?.surah.id
    if (surahId != null) this.rememberListened(surahId, ayah)
    this.set({ followEnabled: true })
  }

  /**
   * Start recitation on the tapped word — Android `playFromWord`.
   * Uses the word's timing `startMs` when available; otherwise falls back to
   * the ayah start (no basmalah preface).
   */
  async playFromWord(ayah: number, wordPosition: number) {
    const startMs = this.segmentStartMs(ayah, wordPosition)
    if (startMs != null) {
      this.noteInkRestart(ayah, startMs)
      await player.seekToWordAndPlay(ayah, startMs)
    } else {
      this.noteInkRestart(ayah, 0)
      await player.playFrom(ayah, false)
    }
    const surahId = this.state.content?.surah.id
    if (surahId != null) this.rememberListened(surahId, ayah)
    this.set({ followEnabled: true })
  }

  /**
   * Word timings of the basmalah lead-in clip (Al-Fatihah 1:1) for the open
   * chapter's reciter, so the calligraphy wash can ride the same word clock the
   * clip does — Android `ReaderViewModel.timingsWithBasmalahLeadIn` parity.
   */
  basmalahSegments(): Segment[] | null {
    return this.timingSegments.get(BASMALAH_PLAYLIST_AYAH) ?? null
  }

  /** First timing segment start for [ayah]/[wordPosition], if timings are loaded. */
  segmentStartMs(ayah: number, wordPosition: number): number | null {
    const prepared = this.ensurePrepared(ayah)
    const segment = prepared?.segments.find((s) => s.position === wordPosition)
    return segment != null ? segment.startMs : null
  }

  async next() {
    await player.next()
  }

  async prev() {
    await player.prev()
  }

  /** Reader transport — Android [ReaderViewModel.fastForward] parity. */
  async fastForward() {
    const content = this.state.content
    if (!content) return
    const np = this.state.player.nowPlaying
    if (!np || np.surahId !== content.surah.id) return

    if (np.ayah === BASMALAH_PLAYLIST_AYAH) {
      this.longAyahMidpointConsumed = 0
      await this.seekToAyah(1)
      return
    }

    const action = fastForwardAction({
      ayah: np.ayah,
      positionMs: this.state.player.positionMs,
      ayahCount: content.surah.ayahCount,
      midpointMs: this.midpointForLongAyah(np.ayah),
      midpointConsumedForAyah: this.longAyahMidpointConsumed,
    })
    this.longAyahMidpointConsumed = nextConsumedAyah(action)
    if (action.kind === 'midpoint') {
      this.noteInkRestart(action.ayah, action.positionMs)
      await player.seekToWord(action.ayah, action.positionMs)
    } else if (action.kind === 'ayah') {
      await this.seekToAyah(action.ayah)
    }
  }

  /** Reader transport — Android [ReaderViewModel.fastBackward] parity. */
  async fastBackward() {
    const content = this.state.content
    if (!content) return
    const np = this.state.player.nowPlaying
    if (!np || np.surahId !== content.surah.id) return

    if (np.ayah === BASMALAH_PLAYLIST_AYAH) {
      this.noteInkRestart(BASMALAH_PLAYLIST_AYAH, 0)
      player.seekToBasmalah()
      return
    }

    if (this.state.player.positionMs > START_SEEK_GRACE_MS) {
      await this.seekToAyah(np.ayah)
      return
    }

    if (np.ayah > 1) {
      await this.seekToAyah(np.ayah - 1)
    } else if (np.ayah === 1) {
      this.noteInkRestart(1, 0)
      await player.playLoadedFromAyah(1)
    }
  }

  private midpointForLongAyah(ayah: number): number | null {
    const prepared = this.ensurePrepared(ayah)
    return prepared ? midpointMs(prepared.segments) : null
  }

  /** Dismiss the cover float and end the playback session. */
  dismissFloatingPlayback() {
    player.stop()
  }

  setRepeat(mode: PlayerState['repeatMode'], range: PlayerState['repeatRange'] = null) {
    player.setRepeatMode(mode, range)
  }

  /** Returns true when the verse is *now* bookmarked. */
  toggleBookmark(ayah: number): boolean {
    if (!this.state.content) return false
    return this.toggleBookmarkAt(this.state.content.surah.id, ayah)
  }

  /** Toggle any saved verse, including rows in the global bookmark index. */
  toggleBookmarkAt(surahId: number, ayah: number): boolean {
    const bookmarks = toggleBookmark(
      this.state.bookmarks,
      surahId,
      ayah,
    )
    saveBookmarks(bookmarks)
    const removingLast = bookmarks.length === 0 && this.state.stackLayer === BOOKMARKS_LAYER
    this.set({
      bookmarks,
      ...(removingLast
        ? { stackLayer: COVER_LAYER as StackLayer, sheet: 'home' as Sheet }
        : {}),
    })
    return bookmarks.some((b) => b.surahId === surahId && b.ayah === ayah)
  }

  isBookmarked(ayah: number): boolean {
    if (!this.state.content) return false
    return this.state.bookmarks.some(
      (b) => b.surahId === this.state.content!.surah.id && b.ayah === ayah,
    )
  }

  setFollowEnabled(followEnabled: boolean) {
    this.set({ followEnabled })
  }

  /**
   * Begin the exit hole-punch; content stays until [finishCloseRootViewer].
   * When [resumeReading] is true (default), restores chapter playback if the
   * viewer paused it — Android [closeRootViewer] parity. Concordance jumps
   * pass false so the old location is not resumed.
   */
  closeRootViewer(resumeReading = true) {
    if (!this.state.rootViewer || this.state.rootViewerClosing) return
    this.stopWordAudition(true)
    const snapshot = this.rootViewerSnapshot
    this.rootViewerSnapshot = null
    this.set({ rootViewerClosing: true })
    if (resumeReading && snapshot) void this.resumeAfterRootViewer(snapshot)
  }

  /** Drop root-viewer state after the bleed-out animation completes. */
  finishCloseRootViewer() {
    if (!this.state.rootViewer) return
    this.stopWordAudition(true)
    this.rootViewerSnapshot = null
    this.set({ rootViewer: null, rootViewerClosing: false })
  }

  openRootViewer(surahId: number, ayah: number, word: Word) {
    this.stopWordAudition(true)
    // Android pauseForRootViewer: capture when this chapter is on the player
    // (playing or paused) so exit can restore a full queue after word audition.
    const ps = this.state.player
    const np = ps.nowPlaying
    const readerSurahId = this.state.content?.surah.id
    if (np != null && readerSurahId != null && np.surahId === readerSurahId) {
      this.rootViewerSnapshot = {
        ayah: np.ayah,
        positionMs: player.positionMs,
        wasPlaying: ps.isPlaying,
      }
      if (ps.isPlaying) player.pause()
    } else {
      this.rootViewerSnapshot = null
    }

    const morph = QuranRepository.wordMorphology(surahId, ayah, word.position)
    if (!morph) {
      this.set({
        rootViewerClosing: false,
        rootViewer: {
          surahId,
          ayah,
          position: word.position,
          arabic: word.arabic,
          translation: word.translation,
          transliteration: word.transliteration,
          root: '',
          lemma: '',
          pos: '',
          features: '',
          occurrenceCount: 0,
          lemmas: [],
          occurrences: [],
          lexicon: null,
          dictionary: null,
          isPlayingWord: false,
        },
      })
      return
    }
    const summary = morph.root ? QuranRepository.rootSummary(morph.root) : null
    this.set({
      rootViewerClosing: false,
      rootViewer: {
        surahId,
        ayah,
        position: word.position,
        arabic: word.arabic,
        translation: word.translation,
        transliteration: word.transliteration,
        root: morph.root,
        lemma: morph.lemma,
        pos: morph.pos,
        features: morph.features,
        occurrenceCount: summary?.occurrenceCount ?? 0,
        lemmas: summary?.lemmas ?? [],
        occurrences: summary?.occurrences ?? [],
        lexicon: null,
        dictionary: null,
        isPlayingWord: false,
      },
    })
    // Lane lives in its own ~20 MB asset, fetched on the first root a reader
    // opens. The analysis above must never wait on it, and a reader who has
    // moved on by the time it lands must not have the screen change under them.
    if (morph.root) {
      void lexiconEntry(morph.root).then((entry) => {
        if (entry && this.state.rootViewer?.root === entry.root) {
          this.set({ rootViewer: { ...this.state.rootViewer, lexicon: entry } })
        }
      })
    }
    // Wiktionary is lemma-keyed — load even when QAC has no root.
    if (morph.lemma) {
      void dictionaryEntry(morph.lemma).then((entry) => {
        if (entry && this.state.rootViewer?.lemma === entry.lemma) {
          this.set({ rootViewer: { ...this.state.rootViewer, dictionary: entry } })
        }
      })
    }
  }

  /**
   * Plays **only** the open root-viewer word with the current reciter —
   * from its timing mark to its end, then pauses. Without a usable
   * segment the speaker is a no-op (never starts the rest of the ayah).
   * Android [RootViewerViewModel.playCurrentWord] parity.
   */
  async playRootViewerWord() {
    const rv = this.state.rootViewer
    if (!rv || this.state.rootViewerClosing) return
    // Prefer the in-memory map; fall back to the repo if idle load hasn't landed.
    let segments = this.timingSegments.get(rv.ayah)
    if (!segments || segments.length === 0) {
      const reciterId =
        this.state.reciters.find((r) => r.id === this.state.settings.reciterId)?.id ??
        this.state.reciters[0]?.id
      if (reciterId != null) {
        segments = QuranRepository.timings(reciterId, rv.surahId).get(rv.ayah)
      }
    }
    const clip = wordClipBounds(segments ?? [], rv.position)
    if (!clip) return

    this.stopWordAudition(true)
    this.set({ rootViewer: { ...rv, isPlayingWord: true } })
    const token = ++this.wordClipToken
    // Word audition is not chapter listening — no basmalah, no follow toggle.
    await player.seekToWordAndPlay(rv.ayah, clip.startMs)
    if (token !== this.wordClipToken) return

    const readyDeadline = performance.now() + WORD_CLIP_READY_TIMEOUT_MS
    const poll = () => {
      if (token !== this.wordClipToken) return
      const ps = this.state.player
      const np = ps.nowPlaying
      const onTarget = np?.surahId === rv.surahId && np.ayah === rv.ayah
      const engaged = ps.isPlaying || ps.isBuffering
      const pos = player.positionMs

      if (!onTarget) {
        // Playlist moved away — stop the audition flag.
        this.finishWordAudition(token)
        return
      }
      if (engaged && pos >= clip.startMs - WORD_CLIP_SEEK_SLACK_MS) {
        if (pos >= clip.endMs) {
          player.pause()
          this.finishWordAudition(token)
          return
        }
      } else if (performance.now() >= readyDeadline) {
        // Timed out before engaging — don't leave a runaway ayah playing.
        player.pause()
        this.finishWordAudition(token)
        return
      }
      this.wordClipTimer = setTimeout(poll, WORD_CLIP_POLL_MS)
    }
    this.wordClipTimer = setTimeout(poll, WORD_CLIP_POLL_MS)
  }

  /** Restore the chapter playhead displaced by the root viewer's word clip. */
  private async resumeAfterRootViewer(snapshot: {
    ayah: number
    positionMs: number
    wasPlaying: boolean
  }) {
    if (snapshot.wasPlaying) {
      await player.seekToWordAndPlay(snapshot.ayah, snapshot.positionMs)
    } else {
      await player.seekToWord(snapshot.ayah, snapshot.positionMs)
    }
  }

  private stopWordAudition(pauseIfPlaying: boolean) {
    this.wordClipToken++
    if (this.wordClipTimer != null) {
      clearTimeout(this.wordClipTimer)
      this.wordClipTimer = null
    }
    const rv = this.state.rootViewer
    if (!rv?.isPlayingWord) return
    if (pauseIfPlaying) player.pause()
    this.set({ rootViewer: { ...rv, isPlayingWord: false } })
  }

  private finishWordAudition(token: number) {
    if (token !== this.wordClipToken) return
    if (this.wordClipTimer != null) {
      clearTimeout(this.wordClipTimer)
      this.wordClipTimer = null
    }
    const rv = this.state.rootViewer
    if (rv?.isPlayingWord) {
      this.set({ rootViewer: { ...rv, isPlayingWord: false } })
    }
  }

  /**
   * User-initiated play/seek: bump ink activation, accept the next clock
   * sample, and pin highlight to [seekMs] on [ayah] so the wash restarts on
   * the word being played (Android [noteInkRestart] parity).
   */
  private noteInkRestart(ayah: number, seekMs: number) {
    this.inkActivation++
    this.highlightClock.acceptNextSample()
    this.forcedHighlight = { ayah, seekMs }
  }

  /**
   * Karaoke query time: heard playhead + word-ink lead (ramped after the first
   * segment). Forced word seeks stay on the media timeline. Web uses LOCAL
   * output lag (0); route presets live in OutputLatency for a future monitor.
   */
  private highlightPositionMs(
    mediaPositionMs: number,
    forcedMediaMs: number | null,
    firstWordStartMs: number,
  ): number {
    const latencyMs = OutputLatency.LOCAL_MS
    const leadMs = getHighlightLeadMs()
    if (leadMs !== this.lastHighlightLeadMs) {
      this.lastHighlightLeadMs = leadMs
      this.highlightClock.acceptNextSample()
    }
    if (forcedMediaMs != null) return forcedMediaMs
    return OutputLatency.highlightMs(
      mediaPositionMs,
      latencyMs,
      leadMs,
      firstWordStartMs,
    )
  }

  private recomputeActive(ps: PlayerState) {
    const np = ps.nowPlaying
    if (!np || np.surahId !== this.state.content?.surah.id) {
      if (this.state.activeWord || this.state.activeAyah || this.state.activeBasmalah) {
        this.state = {
          ...this.state,
          activeWord: null,
          activeAyah: null,
          activeBasmalah: false,
        }
        this.lastActiveKey = ''
      }
      return
    }

    const forced = this.forcedHighlight
    let forcedMs: number | null = null
    if (forced != null && forced.ayah === np.ayah) {
      forcedMs = forced.seekMs
      this.forcedHighlight = null
    }
    const prepared = this.ensurePrepared(np.ayah)
    const firstWordStartMs = prepared?.segments[0]?.startMs ?? 0
    const heardMs = OutputLatency.heardMs(ps.positionMs, OutputLatency.LOCAL_MS)
    const rawMs = this.highlightPositionMs(ps.positionMs, forcedMs, firstWordStartMs)
    const mediaKey = `${np.surahId}:${np.ayah}:${np.reciterId}`
    const highlightPositionMs = this.highlightClock.sample(mediaKey, rawMs)
    if (mediaKey !== this.lastInkMediaKey) {
      this.lastInkMediaKey = mediaKey
    } else if (
      this.lastInkClockMs >= 0 &&
      highlightPositionMs + HighlightClock.SEEK_THRESHOLD_MS < this.lastInkClockMs
    ) {
      // Large backward jump within the same media item: word tap / scrub.
      this.inkActivation++
    }
    this.lastInkClockMs = highlightPositionMs
    const next = readerHighlightState(
      {
        ayah: np.ayah,
        positionMs: heardMs,
        highlightPositionMs,
        durationMs: ps.durationMs,
        isPlaying: ps.isPlaying,
        ayahCount: this.state.content?.surah.ayahCount ?? 0,
        repeatRange: ps.repeatRange,
        activation: this.inkActivation,
      },
      prepared ?? undefined,
    )
    const key = readerHighlightKey(next)
    if (key === this.lastActiveKey) return
    this.lastActiveKey = key
    this.state = {
      ...this.state,
      ...next,
    }
  }
}

export const appStore = new AppStore()

export function useAppState(): AppState {
  return useSyncExternalStore(appStore.subscribe, appStore.getSnapshot, appStore.getSnapshot)
}

/** Shallow-compare plain object/array snapshots from [useAppSelector]. */
export function shallowEqual<T>(a: T, b: T): boolean {
  if (Object.is(a, b)) return true
  if (typeof a !== 'object' || a == null || typeof b !== 'object' || b == null) {
    return false
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) return false
    for (let i = 0; i < a.length; i++) {
      if (!Object.is(a[i], b[i])) return false
    }
    return true
  }
  const aKeys = Object.keys(a as object)
  const bKeys = Object.keys(b as object)
  if (aKeys.length !== bKeys.length) return false
  for (const key of aKeys) {
    if (
      !Object.is(
        (a as Record<string, unknown>)[key],
        (b as Record<string, unknown>)[key],
      )
    ) {
      return false
    }
  }
  return true
}

/**
 * Subscribe to a derived slice of app state. Re-renders only when the selected
 * value changes under [isEqual] (default Object.is; use [shallowEqual] for
 * small object snapshots).
 */
export function useAppSelector<T>(
  selector: (state: AppState) => T,
  isEqual: (a: T, b: T) => boolean = Object.is,
): T {
  const selectorRef = useRef(selector)
  selectorRef.current = selector
  const equalRef = useRef(isEqual)
  equalRef.current = isEqual
  const selectedRef = useRef<T | undefined>(undefined)
  const hasValueRef = useRef(false)

  const subscribe = useCallback((onStoreChange: () => void) => {
    return appStore.subscribe(() => {
      const next = selectorRef.current(appStore.getSnapshot())
      if (hasValueRef.current && equalRef.current(selectedRef.current as T, next)) {
        return
      }
      selectedRef.current = next
      hasValueRef.current = true
      onStoreChange()
    })
  }, [])

  const getSnapshot = useCallback(() => {
    const next = selectorRef.current(appStore.getSnapshot())
    if (hasValueRef.current && equalRef.current(selectedRef.current as T, next)) {
      return selectedRef.current as T
    }
    selectedRef.current = next
    hasValueRef.current = true
    return next
  }, [])

  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}

export { COVER_LAYER, READER_LAYER, SETTINGS_LAYER, settingsLayerFor }
