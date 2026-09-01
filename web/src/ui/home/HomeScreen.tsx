import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from 'react'
import {
  IconBuffering,
  IconClose,
  IconFastForward,
  IconFastRewind,
  IconPause,
  IconPlay,
} from '../icons/PlaybackIcons'
import { AlphaTag } from '../kit/AlphaTag'
import { PaperInput } from '../kit/PaperInput'
import {
  appStore,
  useAppSelector,
  COVER_LAYER,
  READER_LAYER,
} from '../../store/appStore'
import { QuranRepository } from '../../data/repository'
import { VerseBookmarkRibbon } from '../../render/VerseBookmarkRibbon'
import {
  englishTranslationHighlightSpans,
  filterSurahs,
  parseSearchQuery,
  sectionWordSearchHits,
  shouldRunWordSearch,
  WORD_SEARCH_PREVIEW_LIMIT,
  type SurahWordSearchSection,
  type WordSearchHit,
} from '../../domain/WordSearch'
import { BOOKMARKS_LAYER, type StackLayer } from '../paper/stack'

export function HomeScreen({ stackLayer }: { stackLayer: StackLayer }) {
  // Chapters sheet: skip word-tick / active-ink emits from the reader.
  const state = useAppSelector(
    (s) => ({
      bookmarks: s.bookmarks,
      settings: s.settings,
      surahs: s.surahs,
      player: s.player,
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
      a.player.error === b.player.error,
  )
  const hasBookmarks = state.bookmarks.length > 0
  const bookmarkStyle = state.settings.homeBookmarkStyle
  // Local query — typing must not emit through appStore (that re-renders the
  // whole paper stack, including the mounted reader under the cover).
  const [search, setSearch] = useState('')
  const [searchFocused, setSearchFocused] = useState(false)
  const searching = search.trim().length > 0
  const { surahs: filtered, ayahTarget } = useMemo(
    () => filterSurahs(state.surahs, search),
    [state.surahs, search],
  )

  const [wordHits, setWordHits] = useState<WordSearchHit[]>([])
  const [wordLoading, setWordLoading] = useState(false)
  const [expandedSurahIds, setExpandedSurahIds] = useState<Set<number>>(
    () => new Set(),
  )
  const previousBookmarkCount = useRef(state.bookmarks.length)
  const pendingRibbonUnfurl = useRef(false)
  const [ribbonUnfurlSignal, setRibbonUnfurlSignal] = useState(0)

  useEffect(() => {
    setExpandedSurahIds(new Set())
    if (!shouldRunWordSearch(search)) {
      setWordHits([])
      setWordLoading(false)
      return
    }
    setWordLoading(true)
    let cancelled = false
    const handle = window.setTimeout(() => {
      void QuranRepository.searchWordsAsync(search, () => cancelled).then((hits) => {
        if (cancelled) return
        setWordHits(hits)
        setWordLoading(false)
      })
    }, 120)
    return () => {
      cancelled = true
      window.clearTimeout(handle)
    }
  }, [search])

  useEffect(() => {
    if (state.bookmarks.length > previousBookmarkCount.current) {
      pendingRibbonUnfurl.current = true
    }
    previousBookmarkCount.current = state.bookmarks.length
  }, [state.bookmarks.length])

  useEffect(() => {
    if (stackLayer !== COVER_LAYER || !pendingRibbonUnfurl.current) return
    pendingRibbonUnfurl.current = false
    setRibbonUnfurlSignal((value) => value + 1)
  }, [stackLayer])

  const wordSections = useMemo(
    () => sectionWordSearchHits(wordHits, expandedSurahIds),
    [wordHits, expandedSurahIds],
  )

  const continueSurah =
    !searching && state.settings.lastSurah > 0
      ? state.surahs.find((s) => s.id === state.settings.lastSurah)
      : null

  const depth = Math.max(0, stackLayer - COVER_LAYER)
  const isTop = stackLayer === COVER_LAYER
  const nowPlaying = state.player.nowPlaying
  // Hide the float for the whole search session (focused field or query).
  const showFloat = nowPlaying != null && isTop && !searchFocused && !searching
  const floatAyah = nowPlaying != null ? Math.max(1, nowPlaying.ayah) : 1
  const floatSurah =
    nowPlaying != null
      ? state.surahs.find((s) => s.id === nowPlaying.surahId) ?? null
      : null
  const chapterLabel = floatSurah?.nameTransliteration ?? ''
  const ayahLabel =
    floatSurah != null ? `${floatSurah.id}:${floatAyah}` : ''

  const openNowPlaying = () => {
    if (!nowPlaying) return
    if (state.content?.surah.id === nowPlaying.surahId) {
      appStore.revealLayer(READER_LAYER)
      return
    }
    appStore.openSurah(nowPlaying.surahId, floatAyah)
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

  return (
    <div
      className="sheet"
      data-name="home"
      data-layer={COVER_LAYER}
      data-depth={depth}
      data-active={isTop}
    >
      {depth > 0 ? (
        <button
          type="button"
          className="sheet-edge-back"
          aria-label="Back to chapters"
          onClick={() => appStore.revealLayer(COVER_LAYER)}
        />
      ) : null}

      <div
        className="sheet-frame"
        data-search-focused={searchFocused || undefined}
      >
        {hasBookmarks && bookmarkStyle !== 'saved_passages' ? (
          <div className={`home-bookmark-ribbon home-bookmark-${bookmarkStyle}`}>
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
            <span>Beautiful Quran</span>
            <AlphaTag />
          </h1>
          <button
            type="button"
            className="home-settings"
            aria-label="Open settings"
            tabIndex={searchFocused ? -1 : undefined}
            onClick={() => appStore.setSheet('settings')}
          >
            <HomeRosette />
          </button>
        </header>

        <div className="edge-fade">
          <div className={`scroll${showFloat ? ' scroll-with-float' : ''}`}>
            <div
              className="home-scroll-page"
              data-has-bookmarks={hasBookmarks || undefined}
              data-search-focused={searchFocused || undefined}
            >
              <div className="search-row">
                <div className="home-search">
                  <SearchIcon />
                  <PaperInput
                    id="chapter-search"
                    name="chapter-search"
                    type="search"
                    placeholder="Search concept, “exact phrase”, or 2:255"
                    value={search}
                    onValueChange={setSearch}
                    onFocus={() => setSearchFocused(true)}
                    onBlur={() => setSearchFocused(false)}
                    aria-label="Search concepts, exact phrases, or ayah reference"
                  />
                  {search ? (
                    <button
                      type="button"
                      className="home-search-clear"
                      aria-label="Clear search"
                      // Keep the field focused so the masthead stays receded.
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={() => setSearch('')}
                    >
                      <ClearIcon />
                    </button>
                  ) : null}
                </div>
              </div>

            {continueSurah ? (
              <div className="continue-row">
                <button
                  type="button"
                  className="continue"
                  onPointerEnter={() => prepareChapter(continueSurah.id)}
                  onPointerDown={() => prepareChapter(continueSurah.id)}
                  onFocus={() => prepareChapter(continueSurah.id)}
                  onClick={() =>
                    appStore.openSurah(
                      continueSurah.id,
                      state.settings.lastAyah || 1,
                    )
                  }
                >
                  <span className="continue-copy">
                    <span className="continue-label">Continue listening</span>
                    <span className="continue-target">
                      {continueSurah.nameTransliteration}
                      {state.settings.lastAyah > 0
                        ? ` · Ayah ${state.settings.lastAyah}`
                        : ''}
                    </span>
                  </span>
                  <span className="continue-ar" lang="ar" dir="rtl">
                    {continueSurah.nameArabic}
                  </span>
                </button>
              </div>
            ) : null}

            {hasBookmarks && bookmarkStyle === 'saved_passages' && !searching ? (
              <div className="saved-passages-row">
                <span className="saved-passages-mark" aria-hidden="true">
                  <VerseBookmarkRibbon
                    bookmarked
                    focused
                    side="left"
                    interactive={false}
                    animateOnTap={false}
                    topInset={0}
                    bottomGap={0}
                    edgeInset={6.5}
                    unfurlSignal={ribbonUnfurlSignal}
                    onToggle={() => true}
                    decorative
                  />
                </span>
                <span className="saved-passages-copy">
                  <span className="saved-passages-title">Saved passages</span>
                  <span className="saved-passages-note">
                    {state.bookmarks.length}{' '}
                    {state.bookmarks.length === 1 ? 'saved ayah' : 'saved ayahs'}
                  </span>
                </span>
                <button
                  type="button"
                  className="saved-passages-open"
                  aria-label="Open bookmarks"
                  onClick={() => appStore.revealLayer(BOOKMARKS_LAYER)}
                />
              </div>
            ) : null}

            {showSurahMatches ? (
              <p className="search-section-label">Surahs</p>
            ) : null}

            <ul className="surah-list">
              {filtered.map((s) => (
                <li key={s.id}>
                  <button
                    type="button"
                    className="surah-row"
                    onPointerEnter={() => prepareChapter(s.id)}
                    onPointerDown={() => prepareChapter(s.id)}
                    onFocus={() => prepareChapter(s.id)}
                    onClick={() =>
                      appStore.openSurah(s.id, ayahTarget ?? 1)
                    }
                  >
                    <span className="surah-num">{s.id}</span>
                    <span className="surah-names">
                      <span className="en">{s.nameTransliteration}</span>
                      <span className="meta">
                        {s.nameTranslation} · {s.ayahCount}
                      </span>
                    </span>
                    <span className="surah-ar">{s.nameArabic}</span>
                  </button>
                </li>
              ))}
            </ul>

            {showWordSections ? (
              <div className="word-search-results">
                <p className="search-section-label">
                  {wordLoading && wordSections.length === 0
                    ? 'Searching ayahs…'
                    : `In the Quran · ${wordHits.length} relevant ${wordHits.length === 1 ? 'ayah' : 'ayahs'}`}
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

      {showFloat ? (
        <div className="floating-play" role="group" aria-label="Playback">
          <button
            type="button"
            className="float-close"
            aria-label="Close playback"
            onClick={() => appStore.dismissFloatingPlayback()}
          >
            <IconClose />
          </button>
          <button
            type="button"
            className="float-now-playing"
            aria-label={
              chapterLabel && ayahLabel
                ? `Open ${chapterLabel} · ${ayahLabel}`
                : 'Open now playing'
            }
            onClick={openNowPlaying}
          >
            <span className="float-chapter">{chapterLabel}</span>
            {chapterLabel && ayahLabel ? (
              <span className="float-sep" aria-hidden="true">
                {' '}
                ·{' '}
              </span>
            ) : null}
            <span className="float-ayah">{ayahLabel}</span>
          </button>
          <div className="float-transport">
            <button type="button" aria-label="Fast backward" onClick={() => void appStore.prev()}>
              <IconFastRewind />
            </button>
            <button
              type="button"
              aria-label={
                state.player.isBuffering && !state.player.isPlaying
                  ? 'Buffering'
                  : state.player.isPlaying
                    ? 'Pause'
                    : 'Play'
              }
              aria-busy={
                (state.player.isBuffering && !state.player.isPlaying) || undefined
              }
              onClick={() => {
                if (state.player.isBuffering && !state.player.isPlaying) return
                void appStore.playPause()
              }}
            >
              {state.player.isBuffering && !state.player.isPlaying ? (
                <IconBuffering />
              ) : state.player.isPlaying ? (
                <IconPause />
              ) : (
                <IconPlay />
              )}
            </button>
            <button type="button" aria-label="Fast forward" onClick={() => void appStore.next()}>
              <IconFastForward />
            </button>
          </div>
        </div>
      ) : null}
    </div>
  )
}

function HomeRosette() {
  const gradientId = useId()
  const octagram = Array.from({ length: 9 }, (_, i) => {
    const k = (i * 3) % 8
    const angle = ((22.5 + k * 45) * Math.PI) / 180
    return `${50 + 33 * Math.cos(angle)},${50 + 33 * Math.sin(angle)}`
  }).join(' ')
  const geometry = (
    <>
      <path d="M17.5 17.5H82.5V82.5H17.5ZM50 4L96 50 50 96 4 50Z" />
      <polyline points={octagram} />
    </>
  )

  return (
    <svg className="home-rosette" viewBox="0 0 100 100" aria-hidden="true">
      <defs>
        <linearGradient id={gradientId} x1="0" y1="35" x2="100" y2="65">
          <stop offset="0" stopColor="var(--gold-deep)" />
          <stop offset="0.5" stopColor="var(--gold-bright)" />
          <stop offset="1" stopColor="var(--gold-deep)" />
        </linearGradient>
      </defs>
      <g className="home-rosette-relief home-rosette-dark" transform="translate(0.8 0.8)">
        {geometry}
      </g>
      <g className="home-rosette-relief home-rosette-light" transform="translate(-0.8 -0.8)">
        {geometry}
      </g>
      <g className="home-rosette-face" stroke={`url(#${gradientId})`}>
        {geometry}
      </g>
      <circle cx="50" cy="50" r="3.5" fill={`url(#${gradientId})`} />
      {Array.from({ length: 8 }, (_, k) => {
        const angle = (k * Math.PI) / 4
        return (
          <circle
            key={k}
            cx={50 + 46 * Math.cos(angle)}
            cy={50 + 46 * Math.sin(angle)}
            r="1.8"
            fill={`url(#${gradientId})`}
          />
        )
      })}
    </svg>
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
  const displayQuery = parseSearchQuery(query).text
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
        {section.hits.map((hit) => (
          <li key={`${hit.ayahNumber}-${hit.position}`}>
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
                {englishTranslationHighlightSpans(
                  hit.ayahTranslation,
                  displayQuery,
                  hit.translation,
                  hit.matchLabel ?? '',
                  hit.matchTerms ?? [],
                ).map((span, i) =>
                  span.highlighted ? (
                    <mark key={i} className="word-search-mark">
                      {span.text}
                    </mark>
                  ) : (
                    <span key={i}>{span.text}</span>
                  ),
                )}
              </span>
            </button>
          </li>
        ))}
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
