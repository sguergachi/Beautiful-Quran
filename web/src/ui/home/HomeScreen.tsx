import { suppliedWords } from '../../render/suppliedWords'
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { IconTune } from '../icons/PlaybackIcons'
import { PaperInput } from '../kit/PaperInput'
import { ChapterRow } from './ChapterRow'
import { ContinueFloat } from './ContinueFloat'
import { FloatingPlayback } from './FloatingPlayback'
import { SearchDials } from './SearchDials'
import { PinChapterTip } from './PinChapterTip'
import { usePinChapterLesson } from './usePinChapterLesson'
import { chapterOpenAyah, shouldDismissSearchOnScroll } from './continuePlace'
import { readChapterListScroll, writeChapterListScroll } from './chapterListScroll'
import { chapterRowKey, pinnedChapterLists, reflowDeltas } from './pinnedChapters'
import {
  appStore,
  useAppSelector,
  COVER_LAYER,
  READER_LAYER,
} from '../../store/appStore'
import { QuranRepository } from '../../data/repository'
import { VerseBookmarkRibbon } from '../../render/VerseBookmarkRibbon'
import {
  searchHitHighlightSpans,
  filterSurahs,
  sectionWordSearchHits,
  shouldRunWordSearch,
  spellingCorrection,
  WORD_SEARCH_PREVIEW_LIMIT,
  type SurahWordSearchSection,
  type WordSearchHit,
} from '../../domain/WordSearch'
import { wordSearchSources } from '../../data/customizePolicy'
import { BOOKMARKS_LAYER, type StackLayer } from '../paper/stack'
import { createWheelTurn, isSidewaysWheel } from '../reader/wheelTurn'
import './home.css'

export function HomeScreen({
  stackLayer,
  playbackPinned = false,
  onCloseBook,
  closeStrip = false,
}: {
  /** Closes the book from its first page. */
  onCloseBook?: () => void
  /** Desktop book: the strip of this page's fore-edge that closes it. */
  closeStrip?: boolean
  stackLayer: StackLayer
  /** Phone scroll stack: the chapter bar is pinned, so this sheet keeps its float in place. */
  playbackPinned?: boolean
}) {
  // Chapters sheet: skip word-tick / active-ink emits from the reader.
  const state = useAppSelector(
    (s) => ({
      bookmarks: s.bookmarks,
      settings: s.settings,
      surahs: s.surahs,
      player: s.player,
      playbackSession: s.playbackSession,
      content: s.content,
      ready: s.ready,
    }),
    (a, b) =>
      a.bookmarks === b.bookmarks &&
      a.settings === b.settings &&
      a.surahs === b.surahs &&
      a.ready === b.ready &&
      a.content?.surah.id === b.content?.surah.id &&
      a.player.isPlaying === b.player.isPlaying &&
      a.player.isBuffering === b.player.isBuffering &&
      a.player.nowPlaying?.surahId === b.player.nowPlaying?.surahId &&
      a.player.nowPlaying?.ayah === b.player.nowPlaying?.ayah &&
      a.player.error === b.player.error &&
      a.playbackSession === b.playbackSession,
  )
  const hasBookmarks = state.bookmarks.length > 0
  // Local query — typing must not emit through appStore (that re-renders the
  // whole paper stack, including the mounted reader under the cover).
  const [search, setSearch] = useState('')
  const [searchFocused, setSearchFocused] = useState(false)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const searching = search.trim().length > 0
  // Search mode: the field focused, or a query still on the sheet (results
  // stay after the keyboard goes). The float and ribbons stand down for it.
  const searchActive = searchFocused || searching
  const sheetRef = useRef<HTMLDivElement>(null)
  const scrollerRef = useRef<HTMLDivElement>(null)
  const searchBoxRef = useRef<HTMLDivElement>(null)
  const { surahs: filtered, ayahTarget } = useMemo(
    () => filterSurahs(state.surahs, search),
    [state.surahs, search],
  )

  const [wordHits, setWordHits] = useState<WordSearchHit[]>([])
  const [wordHitsQuery, setWordHitsQuery] = useState('')
  const [wordLoading, setWordLoading] = useState(false)
  const [expandedSurahIds, setExpandedSurahIds] = useState<Set<number>>(
    () => new Set(),
  )
  const revealPinned = useRef(false)
  const focusAfterPin = useRef<string | null>(null)
  // A pin or unpin moves the very row (Android ChapterReflowSpec): where every
  // row stood just before, and which chapter is travelling, still wet.
  const rowTopsBefore = useRef<Map<string, number> | null>(null)
  const [dryingKey, setDryingKey] = useState<string | null>(null)
  const previousBookmarkCount = useRef(state.bookmarks.length)
  const pendingRibbonUnfurl = useRef(false)
  const [ribbonUnfurlSignal, setRibbonUnfurlSignal] = useState(0)
  const [placeUnfurlSignal, setPlaceUnfurlSignal] = useState(0)
  const visitedReader = useRef(false)
  const isTop = stackLayer === COVER_LAYER
  const depth = Math.max(0, stackLayer - COVER_LAYER)
  const readingLayout = state.settings.readingLayout

  useEffect(() => {
    const dismissSearch = (event: KeyboardEvent) => {
      if (
        event.key !== 'Escape' ||
        document.activeElement?.id !== 'chapter-search'
      ) return
      event.preventDefault()
      event.stopImmediatePropagation()
      document.getElementById('chapter-search')?.blur()
    }
    window.addEventListener('keydown', dismissSearch, true)
    return () => window.removeEventListener('keydown', dismissSearch, true)
  }, [])

  // Cover search is local, so the pinned chapter bar cannot see it through
  // the store. Hide that bar until the query is gone, without a keystroke emit.
  useEffect(() => {
    const shell = document.querySelector('.app-shell')
    if (!(shell instanceof HTMLElement)) return
    if (searchActive) shell.dataset.coverSearch = 'true'
    else delete shell.dataset.coverSearch
    return () => {
      delete shell.dataset.coverSearch
    }
  }, [searchActive])

  // Pinning from the very top of the sheet brings the band into view (as
  // Android scrolls to the first item); a reader deep in the list keeps
  // their place. A row confirmed from the keyboard keeps focus where it went.
  const pinnedSurahIds = state.settings.pinnedSurahIds
  // The reflow: each row slides from where it stood to its new seat, the
  // moved one above the rest, so nothing jumps.
  useLayoutEffect(() => {
    const before = rowTopsBefore.current
    rowTopsBefore.current = null
    if (!before || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const rows = rowElements(sheetRef.current)
    const deltas = reflowDeltas(before, rowTops(rows))
    for (const [key, delta] of deltas) {
      rows.get(key)?.closest('li')?.animate(
        [{ transform: `translateY(${delta}px)` }, { transform: 'none' }],
        { duration: 420, easing: 'cubic-bezier(0.4, 0, 0.2, 1)' },
      )
    }
  }, [pinnedSurahIds])
  useEffect(() => {
    if (!dryingKey) return
    const done = window.setTimeout(() => setDryingKey(null), 650)
    return () => window.clearTimeout(done)
  }, [dryingKey])
  useEffect(() => {
    if (revealPinned.current) scrollerRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
    revealPinned.current = false
    const key = focusAfterPin.current
    focusAfterPin.current = null
    if (key) {
      sheetRef.current
        ?.querySelector<HTMLElement>(`[data-row-key="${key}"]`)
        ?.focus({ preventScroll: true })
    }
  }, [pinnedSurahIds])

  // Quran-wide results read the selected layout's English, and a layout
  // change re-runs the search (Android combines query and sources).
  useEffect(() => {
    setExpandedSurahIds(new Set())
    if (!shouldRunWordSearch(search)) {
      setWordHits([])
      setWordHitsQuery('')
      setWordLoading(false)
      return
    }
    setWordLoading(true)
    let cancelled = false
    const handle = window.setTimeout(() => {
      void QuranRepository.searchWordsAsync(
        search,
        () => cancelled,
        wordSearchSources(readingLayout),
      ).then((hits) => {
        if (cancelled) return
        setWordHits(hits)
        setWordHitsQuery(search)
        setWordLoading(false)
      })
    }, 120)
    return () => {
      cancelled = true
      window.clearTimeout(handle)
    }
  }, [search, readingLayout])

  useEffect(() => {
    if (state.bookmarks.length > previousBookmarkCount.current) {
      pendingRibbonUnfurl.current = true
    }
    previousBookmarkCount.current = state.bookmarks.length
  }, [state.bookmarks.length])

  // A mark added in the reader unfurls once Chapters is uncovered; so does
  // the place ribbon, coming back from any reader visit (Android
  // `shouldUnfurlReadingPlaceRibbon`).
  useEffect(() => {
    if (stackLayer >= READER_LAYER) visitedReader.current = true
    if (stackLayer !== COVER_LAYER) return
    if (pendingRibbonUnfurl.current) {
      pendingRibbonUnfurl.current = false
      setRibbonUnfurlSignal((value) => value + 1)
    }
    if (visitedReader.current) {
      visitedReader.current = false
      setPlaceUnfurlSignal((value) => value + 1)
    }
  }, [stackLayer])

  const visibleWordHits = useMemo(
    () => wordHitsQuery === search ? wordHits : [],
    [search, wordHits, wordHitsQuery],
  )
  const wordSections = useMemo(
    () => sectionWordSearchHits(visibleWordHits, expandedSurahIds),
    [visibleWordHits, expandedSurahIds],
  )
  const correctedQuery = useMemo(() => spellingCorrection(visibleWordHits), [visibleWordHits])

  // Android's sole place marker: the last verse heard. Reading alone never
  // creates one. Its chapter row carries the green place ribbon in situ.
  const lastSurah = state.settings.lastSurah
  const lastAyah = Math.max(1, state.settings.lastAyah)
  const continueSurah =
    lastSurah > 0 ? state.surahs.find((s) => s.id === lastSurah) ?? null : null
  const placeKey = continueSurah ? chapterRowKey(continueSurah.id) : null

  // Two fingers swept back across Chapters close the book, as a finger
  // dragged back across it does. The sweep is taken from the browser's own
  // history gesture, so the listener cannot be passive; it is on this sheet
  // alone, not the window, where it would hold up every scroll in the app
  // until the handler had run.
  const closeBookRef = useRef(onCloseBook)
  closeBookRef.current = onCloseBook
  const canCloseBook = onCloseBook != null && isTop
  useEffect(() => {
    const sheet = sheetRef.current
    if (!sheet || !canCloseBook) return
    const wheelTurn = createWheelTurn()
    const onWheel = (event: WheelEvent) => {
      if (event.ctrlKey || !isSidewaysWheel(event.deltaX, event.deltaY)) return
      event.preventDefault()
      if (wheelTurn(event.deltaX, event.deltaY, event.timeStamp) === -1) closeBookRef.current?.()
    }
    sheet.addEventListener('wheel', onWheel, { passive: false })
    return () => sheet.removeEventListener('wheel', onWheel)
  }, [canCloseBook])

  const nowPlaying = state.player.nowPlaying
  // A listening session only (a chapter open parks a silent clip), and
  // never through the search session (focused field or query).
  const showFloat = nowPlaying != null && state.playbackSession && isTop && !searchActive
  const floatAyah = nowPlaying != null ? Math.max(1, nowPlaying.ayah) : 1
  const floatSurah =
    nowPlaying != null
      ? state.surahs.find((s) => s.id === nowPlaying.surahId) ?? null
      : null
  const chapterLabel = floatSurah?.nameTransliteration ?? ''
  const ayahLabel =
    floatSurah != null ? `${floatSurah.id}:${floatAyah}` : ''
  // The pinned cover keeps the chapter bar. The reduced float stays for an
  // idle session, and for the moment before the reader sheet can draw that bar.
  const pinnedCoverBar = showFloat && playbackPinned && state.content != null

  // Android: the return pill opens the reader AT the playing verse, even
  // when that chapter is the one already loaded.
  const openNowPlaying = () => {
    if (!nowPlaying) return
    appStore.openReading(nowPlaying.surahId, floatAyah)
  }

  const toggleSection = (surahId: number) => {
    setExpandedSurahIds((prev) => {
      const next = new Set(prev)
      if (next.has(surahId)) next.delete(surahId)
      else next.add(surahId)
      return next
    })
  }

  const showSurahMatches = searching && filtered.length > 0
  const showWordSections =
    searching && (wordSections.length > 0 || wordLoading)
  const showEmpty =
    searching &&
    filtered.length === 0 &&
    wordSections.length === 0 &&
    !wordLoading
  const prepareChapter = (surahId: number) => {
    appStore.prepareSurah(surahId)
  }

  // Focusing search lifts the list to the top; a deliberate scroll beyond
  // 24px then puts the keyboard away, leaving any results (Android
  // `shouldDismissSearchOnScroll`). The lift itself never counts.
  useEffect(() => {
    const scroller = scrollerRef.current
    if (!searchFocused || !scroller) return
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    let lifting = scroller.scrollTop > 0
    scroller.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' })
    const liftDone = window.setTimeout(() => {
      lifting = false
    }, 700)
    const onScroll = () => {
      if (lifting) {
        if (scroller.scrollTop <= 0) lifting = false
        return
      }
      if (shouldDismissSearchOnScroll(scroller.scrollTop)) searchInputRef.current?.blur()
    }
    scroller.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.clearTimeout(liftDone)
      scroller.removeEventListener('scroll', onScroll)
    }
  }, [searchFocused])

  // The list is the app's home: a reload opens on the chapter last under
  // the thumb, not the top (Android `chapterListAnchor`).
  const restoredScroll = useRef(false)
  const listReady = state.surahs.length > 0
  useLayoutEffect(() => {
    const scroller = scrollerRef.current
    if (restoredScroll.current || !listReady || !scroller) return
    restoredScroll.current = true
    const saved = readChapterListScroll()
    if (saved > 0 && scroller.scrollTop === 0) scroller.scrollTop = saved
  }, [listReady])
  useEffect(() => {
    const scroller = scrollerRef.current
    if (!scroller || searchActive || !isTop) return
    let idle = 0
    const onScroll = () => {
      window.clearTimeout(idle)
      idle = window.setTimeout(() => writeChapterListScroll(scroller.scrollTop), 180)
    }
    scroller.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.clearTimeout(idle)
      scroller.removeEventListener('scroll', onScroll)
    }
  }, [searchActive, isTop])

  // Pinned chapters lead the list on the band and leave the list below.
  const chapterLists = pinnedChapterLists(filtered, pinnedSurahIds, searching)
  const bandIds = new Set(chapterLists.pinned.map((s) => s.id))

  // The pin lesson (developer guides): offered after a long read of a late
  // chapter, pointing at that chapter's row once Chapters is back.
  const pinLesson = usePinChapterLesson({
    stackLayer,
    readingChapterId: state.content?.surah.id ?? 0,
    pinnedCount: pinnedSurahIds.length,
    developerMode: state.settings.developerMode,
    guidesEnabled: state.settings.educationGuidesEnabled,
  })

  const togglePinned = (surahId: number) => {
    const pinned = pinnedSurahIds.includes(surahId)
    // The lesson completes on a real pin.
    if (!pinned) pinLesson.complete()
    const scroller = scrollerRef.current
    revealPinned.current = !pinned && !searching && (scroller?.scrollTop ?? 0) <= 0
    if (document.activeElement?.closest('.surah-item')) {
      // Focus follows the row only when the reader will see where it went;
      // otherwise it stays in place, on the row that takes the moved one's.
      focusAfterPin.current = revealPinned.current
        ? chapterRowKey(surahId)
        : neighbourRowKey(surahId, pinned)
    }
    rowTopsBefore.current = rowTops(rowElements(sheetRef.current))
    setDryingKey(chapterRowKey(surahId))
    appStore.togglePinnedSurah(surahId)
  }
  /** The row beside [surahId] in the list it is leaving: the next, else the
   * previous. Focus lands there when the moved row goes out of view. */
  const neighbourRowKey = (surahId: number, fromBand: boolean): string | null => {
    const rows = fromBand ? chapterLists.pinned : chapterLists.listed
    const at = rows.findIndex((s) => s.id === surahId)
    const neighbour = rows[at + 1] ?? rows[at - 1]
    return neighbour ? chapterRowKey(neighbour.id) : null
  }
  const openChapter = (surahId: number) => {
    pinLesson.close()
    const ayah = chapterOpenAyah({ surahId, ayahTarget: ayahTarget ?? null, lastSurah, lastAyah })
    if (ayah != null) appStore.openReading(surahId, ayah)
    else appStore.openSurah(surahId)
  }
  const chapterRow = (s: (typeof filtered)[number]) => {
    const onBand = bandIds.has(s.id)
    const key = chapterRowKey(s.id)
    return (
      <ChapterRow
        key={key}
        surah={s}
        rowKey={key}
        pinned={pinnedSurahIds.includes(s.id)}
        onBand={onBand}
        drying={dryingKey === key}
        holdable={!searching}
        placeAyah={key === placeKey ? lastAyah : null}
        placeUnfurlSignal={key === placeKey ? placeUnfurlSignal : 0}
        onPrepare={() => prepareChapter(s.id)}
        onOpen={() => openChapter(s.id)}
        onToggle={() => togglePinned(s.id)}
      />
    )
  }

  const [continueHolding, setContinueHolding] = useState(false)
  const onContinueFloat = useCallback((holding: boolean) => setContinueHolding(holding), [])
  const continueEligible = continueSurah != null && isTop && !searchActive && !showFloat

  // Empty-query dials fade in under the focused field.
  const dialsVisible = searchFocused && search.length === 0

  const scrollToTop = () => {
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    scrollerRef.current?.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' })
  }

  return (
    <div
      className="sheet"
      data-name="home"
      data-layer={COVER_LAYER}
      data-depth={depth}
      data-active={isTop}
      ref={sheetRef}
    >
      {depth > 0 ? (
        <button
          type="button"
          className="sheet-edge-back"
          aria-label="Back to chapters"
          onClick={() => appStore.revealLayer(COVER_LAYER)}
        />
      ) : null}

      {/* The board the book closes on lies under this page's fore-edge:
          the strip there shuts it, as the leaves' own strips turn them. */}
      {onCloseBook && closeStrip && isTop ? (
        <button
          type="button"
          className="book-close"
          aria-label="Close the book"
          title="Close the book"
          onClick={onCloseBook}
        >
          <span aria-hidden="true">‹</span>
        </button>
      ) : null}

      <div
        className="sheet-frame"
        data-search-focused={searchFocused || undefined}
      >
        {/* The ribbon is the bookmarks entry; search owns the sheet while
            active (dials or results), so it steps aside until then. */}
        {hasBookmarks && !searchActive ? (
          <div className="home-bookmark-ribbon home-bookmark-top_bound">
            <VerseBookmarkRibbon
              bookmarked
              focused
              side="left"
              interactive={false}
              animateOnTap={false}
              topInset={0}
              bottomGap={0}
              edgeInset={7.5}
              unfurlSignal={ribbonUnfurlSignal}
              onToggle={() => true}
              decorative
            />
            <button
              type="button"
              className="home-bookmark-open"
              aria-label="Open bookmarks"
              onClick={() => appStore.revealLayer(BOOKMARKS_LAYER)}
            />
          </div>
        ) : null}
        <header
          className="home-header"
          data-has-bookmarks={hasBookmarks || undefined}
          data-search-focused={searchFocused || undefined}
          aria-hidden={searchFocused || undefined}
        >
          <h1>
            {/* The masthead is the way back to the top of the list. */}
            <button
              type="button"
              className="home-title"
              tabIndex={searchFocused ? -1 : undefined}
              onClick={scrollToTop}
            >
              Beautiful Quran
            </button>
          </h1>
          <button
            type="button"
            className="settings-button home-settings"
            aria-label="Open settings"
            disabled={searchFocused}
            onClick={() => appStore.setSheet('settings')}
          >
            <IconTune />
          </button>
        </header>

        <div className="edge-fade">
          <div
            ref={scrollerRef}
            className={`scroll${showFloat ? ' scroll-with-float' : ''}${pinnedCoverBar ? ' scroll-with-chapter' : ''}${continueHolding && !showFloat ? ' scroll-with-continue' : ''}`}
          >
            <div
              className="home-scroll-page"
              data-has-bookmarks={hasBookmarks || undefined}
              data-search-focused={searchFocused || undefined}
            >
              <div className="search-row">
                <div
                  ref={searchBoxRef}
                  className="home-search"
                  onFocusCapture={() => {
                    setSearchFocused(true)
                    pinLesson.close()
                    void Promise.all([
                      QuranRepository.warmWordSearchIndex(),
                      QuranRepository.warmSearchConcepts(),
                    ])
                  }}
                  onBlurCapture={(event) => {
                    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
                      setSearchFocused(false)
                    }
                  }}
                >
                  <SearchIcon />
                  <PaperInput
                    inputRef={searchInputRef}
                    id="chapter-search"
                    name="chapter-search"
                    type="search"
                    placeholder="Concept, “exact phrase”, or 2:255"
                    value={search}
                    onValueChange={setSearch}
                    aria-label="Search concepts, exact phrases, or ayah reference"
                  />
                  {search ? (
                    <button
                      type="button"
                      className="home-search-clear"
                      aria-label="Clear search"
                      // Keep the field focused so the masthead stays receded.
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={() => {
                        setSearch('')
                        searchInputRef.current?.focus()
                      }}
                    >
                      <ClearIcon />
                    </button>
                  ) : null}
                </div>
              </div>

            {showSurahMatches ? (
              <p className="search-section-label">Surahs</p>
            ) : null}

            {chapterLists.pinned.length > 0 ? (
              <>
                <p className="pinned-flag">Pinned</p>
                <ul className="surah-list surah-list-pinned">
                  {chapterLists.pinned.map(chapterRow)}
                </ul>
              </>
            ) : null}

            <ul className="surah-list">
              {chapterLists.listed.map(chapterRow)}
            </ul>

            {showWordSections ? (
              <div className="word-search-results">
                {correctedQuery ? (
                  <p className="search-correction">
                    Searching instead for <span>{correctedQuery}</span>
                  </p>
                ) : null}
                <p className="search-section-label">
                  {wordLoading && wordSections.length === 0
                    ? 'Searching ayahs…'
                    : `In the Quran · ${visibleWordHits.length} relevant ${visibleWordHits.length === 1 ? 'ayah' : 'ayahs'}${wordLoading ? ' · refining…' : ''}`}
                </p>
                {wordSections.map((section) => (
                  <WordSearchSection
                    key={section.surahId}
                    section={section}
                    query={search}
                    onToggle={() => toggleSection(section.surahId)}
                    onPrepareHit={(hit) => prepareChapter(hit.surahId)}
                    onOpenHit={(hit) =>
                      appStore.openSurah(
                        hit.surahId,
                        hit.ayahNumber,
                        hit.position,
                        search,
                        hit.targetPositions,
                      )
                    }
                  />
                ))}
              </div>
            ) : null}

            {showEmpty ? (
              <p className="search-empty">No relevant ayahs found</p>
            ) : null}
            </div>
          </div>
        </div>
      </div>

      <SearchDials
        visible={dialsVisible}
        surahs={state.surahs}
        anchorRef={searchBoxRef}
        sheetRef={sheetRef}
        onDismiss={() => searchInputRef.current?.blur()}
        onOpen={(surahId, ayah) => {
          searchInputRef.current?.blur()
          appStore.openReading(surahId, ayah)
        }}
      />

      <ContinueFloat
        surah={continueSurah}
        ayah={lastAyah}
        eligible={continueEligible}
        rowKey={placeKey}
        sheetRef={sheetRef}
        scrollerRef={scrollerRef}
        layoutKey={`${filtered.length}|${pinnedSurahIds.join(',')}|${showFloat}`}
        onFloatChange={onContinueFloat}
        onPrepare={() => continueSurah && prepareChapter(continueSurah.id)}
        onOpen={() => continueSurah && appStore.openReading(continueSurah.id, lastAyah)}
      />

      {!pinnedCoverBar ? (
        <FloatingPlayback
          visible={showFloat}
          chapterLabel={chapterLabel}
          ayahLabel={ayahLabel}
          onOpenNowPlaying={openNowPlaying}
        />
      ) : null}

      <PinChapterTip
        lesson={pinLesson}
        eligible={isTop && !searchActive && pinnedSurahIds.length === 0 && !showFloat}
        sheetRef={sheetRef}
        scrollerRef={scrollerRef}
      />
    </div>
  )
}

function SearchIcon() {
  return (
    <svg className="home-search-icon" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="m15.4 15.4 4.2 4.2" />
    </svg>
  )
}

function ClearIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="m7 7 10 10M17 7 7 17" />
    </svg>
  )
}

function WordSearchSection({
  section,
  query,
  onToggle,
  onPrepareHit,
  onOpenHit,
}: {
  section: SurahWordSearchSection
  query: string
  onToggle: () => void
  onPrepareHit: (hit: WordSearchHit) => void
  onOpenHit: (hit: WordSearchHit) => void
}) {
  return (
    <section className="word-search-section">
      <header className="word-search-surah-header">
        <span className="word-search-surah-en">
          {section.surahNameTransliteration}
        </span>
        <span className="word-search-surah-count">{section.totalCount}</span>
        <span className="word-search-surah-ar" lang="ar" dir="rtl">
          {section.surahNameArabic}
        </span>
      </header>
      <ul className="word-search-hits">
        {section.hits.map((hit) => {
          const spans = searchHitHighlightSpans(hit, query)
          return <li key={`${hit.ayahNumber}-${hit.position}`}>
            <button
              type="button"
              className="word-search-hit"
              onPointerEnter={() => onPrepareHit(hit)}
              onPointerDown={() => onPrepareHit(hit)}
              onFocus={() => onPrepareHit(hit)}
              onClick={() => onOpenHit(hit)}
            >
              <span className="word-search-ref">
                {hit.surahId}:{hit.ayahNumber}
                {` · ${hit.matchReason ?? 'Text match'}`}
              </span>
              <span className="word-search-translation">
                {spans.map((span, i) =>
                  span.highlighted ? (
                    <mark key={i} className="word-search-mark">
                      {suppliedWords(span.text, `s${i}`)}
                    </mark>
                  ) : (
                    <span key={i}>{suppliedWords(span.text, `s${i}`)}</span>
                  ),
                )}
              </span>
            </button>
          </li>
        })}
      </ul>
      {section.hiddenCount > 0 ? (
        <button type="button" className="word-search-more" onClick={onToggle}>
          Show {section.hiddenCount} more in {section.surahNameTransliteration}
        </button>
      ) : section.expanded && section.totalCount > WORD_SEARCH_PREVIEW_LIMIT ? (
        <button type="button" className="word-search-less" onClick={onToggle}>
          Show less
        </button>
      ) : null}
    </section>
  )
}

/** Every chapter row on the sheet, by its key. */
function rowElements(sheet: HTMLElement | null): Map<string, HTMLElement> {
  const rows = new Map<string, HTMLElement>()
  sheet?.querySelectorAll<HTMLElement>('[data-row-key]').forEach((row) => {
    rows.set(row.dataset.rowKey!, row)
  })
  return rows
}

/** Where each row stands, in viewport px. */
function rowTops(rows: Map<string, HTMLElement>): Map<string, number> {
  const tops = new Map<string, number>()
  for (const [key, row] of rows) tops.set(key, row.getBoundingClientRect().top)
  return tops
}
