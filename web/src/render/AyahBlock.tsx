import { memo, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import type { ActiveWord, Ayah, Word } from '../data/models'
import type { ReadingMode, VerseNumberScript } from '../data/settings'
import {
  coalescedGlossOwnerIndex,
  lyricizeEnglishGlosses,
} from '../domain/EnglishTypography'
import { InkEngine, InkState } from '../ui/reader/InkEngine'
import { ayahTranslationAlpha } from '../ui/reader/WordHighlight'
import { formatAyahNumberMark } from '../util/digits'
import { WordUnit } from './WordUnit'
import { HafsWord } from './HafsWord'
import { VerseBookmarkRibbon } from './VerseBookmarkRibbon'
import { RepeatWashGateProvider } from './RepeatWashContext'
import { runSearchHitWash } from './inkWash'
import { SearchHitFlash, searchHitTextRanges } from '../ui/reader/SearchHitFlash'

interface Props {
  ayah: Ayah
  activeWord: ActiveWord | null
  isActiveAyah: boolean
  dimmed: boolean
  focused: boolean
  /** Tall-verse secondary constraint — keep the active word in the reading band. */
  keepActiveWordInView?: boolean
  onKeepWordInView?: (wordEl: HTMLElement) => void
  readingMode: ReadingMode
  verseNumberScript?: VerseNumberScript
  showWordGloss: boolean
  showTransliteration: boolean
  showTranslation: boolean
  bookmarked: boolean
  bookmarkSide: 'left' | 'right'
  bookmarkChromeAlpha?: number
  bookmarkInteractive?: boolean
  speed: number
  fontScale: number
  /** Live in-surah English query (≥ 2 chars); highlights matching glosses. */
  searchQuery?: string | null
  /** 1-based word to orange-flash (home search hit); null = no flash. */
  flashWordPosition?: number | null
  /** Every grounded word in this ayah that should share the orange flash. */
  flashWordPositions?: number[]
  /** Exact canonical-translation term when [flashWordPosition] is zero. */
  searchFlashText?: string | null
  /** Tap a word to start recitation at that word's timing. */
  onPlayWord: (ayah: number, wordPosition: number) => void
  onToggleBookmark: (ayah: number) => boolean
  onHoldWord: (ayah: number, word: Word) => void
}

function AyahBlockInner({
  ayah,
  activeWord,
  isActiveAyah,
  dimmed,
  focused,
  keepActiveWordInView = false,
  onKeepWordInView,
  readingMode,
  verseNumberScript = 'arabic',
  showWordGloss,
  showTransliteration,
  showTranslation,
  bookmarked,
  bookmarkSide,
  bookmarkChromeAlpha = 1,
  bookmarkInteractive = true,
  speed,
  fontScale,
  searchQuery = null,
  flashWordPosition = null,
  flashWordPositions = [],
  searchFlashText = null,
  onPlayWord,
  onToggleBookmark,
  onHoldWord,
}: Props) {
  const englishOnly = readingMode === 'english_only'
  const arabicOnly = readingMode === 'arabic_only'
  const ayahMark = formatAyahNumberMark(ayah.number, verseNumberScript === 'arabic')
  // Inactive-ayah recess is one `.ayah-recess-veil` (paint-phase) — no per-word
  // inline dim so play/pause does not thrash every verse.
  const words = useMemo(() => ayah.words, [ayah.words])
  const englishWords = useMemo(
    () => lyricizeEnglishGlosses(
      words.map((word) => word.translation),
      words.map((word) => word.arabic),
    ),
    [words],
  )
  const searchTargets = useMemo(
    () => new Set([
      ...flashWordPositions,
      ...(flashWordPosition != null && flashWordPosition > 0 ? [flashWordPosition] : []),
    ]),
    [flashWordPosition, flashWordPositions],
  )
  const visibleEnglishFlashPositions = useMemo(() => {
    const glosses = words.map((word) => word.translation)
    const arabic = words.map((word) => word.arabic)
    return new Set([...searchTargets].flatMap((position) => {
      const requestedIndex = words.findIndex((word) => word.position === position)
      const owner = coalescedGlossOwnerIndex(glosses, arabic, requestedIndex)
      return owner == null ? [] : [words[owner]!.position]
    }))
  }, [words, searchTargets])
  const searchFocusActive = flashWordPosition != null
  // Derive ink policy once for the ayah, matching Android AyahBlock. The
  // renderer branches consume these decisions and never reinterpret playback.
  const activeSweepMs = InkEngine.sweepMs(activeWord, speed)
  const inks = words.map((word) =>
    InkEngine.word(word.position, activeWord, isActiveAyah, dimmed),
  )
  const activeWordRef = useRef<HTMLElement | null>(null)
  const [hovered, setHovered] = useState(false)
  const query = searchQuery?.toLowerCase() ?? null
  const translationHit =
    query != null && ayah.translation.toLowerCase().includes(query)
  const hits = (translation: string) =>
    query != null && translation.toLowerCase().includes(query)
  const translationRef = useRef<HTMLParagraphElement>(null)
  const translationContent = useMemo<ReactNode>(() => {
    if (flashWordPosition !== 0) return ayah.translation
    const ranges = searchHitTextRanges(ayah.translation, searchFlashText)
    if (!ranges.length) return ayah.translation
    const parts: ReactNode[] = []
    let cursor = 0
    for (const [start, end] of ranges) {
      if (start > cursor) parts.push(ayah.translation.slice(cursor, start))
      const match = ayah.translation.slice(start, end)
      parts.push(
        <span className="translation-search-term" key={`${start}-${end}`}>
          {match}
          <span className="translation-search-term__wash" data-search-flash-overlay aria-hidden>
            {match}
          </span>
        </span>,
      )
      cursor = end
    }
    if (cursor < ayah.translation.length) parts.push(ayah.translation.slice(cursor))
    return parts
  }, [ayah.translation, flashWordPosition, searchFlashText])

  useEffect(() => {
    if (flashWordPosition !== 0) return
    const overlays = translationRef.current
      ?.querySelectorAll<HTMLElement>('[data-search-flash-overlay]') ?? []
    const cancels = [...overlays].map((overlay) =>
      runSearchHitWash(overlay, SearchHitFlash),
    )
    return () => cancels.forEach((cancel) => cancel())
  }, [flashWordPosition, searchFlashText])

  useEffect(() => {
    if (!keepActiveWordInView || !onKeepWordInView) return
    const el = activeWordRef.current
    if (el) onKeepWordInView(el)
  }, [keepActiveWordInView, onKeepWordInView, activeWord?.wordPosition])

  return (
    <RepeatWashGateProvider>
    <article
      className="ayah-block"
      data-ayah={ayah.number}
      data-ayah-active={isActiveAyah || undefined}
      data-dimmed={dimmed || undefined}
      data-search-flash-ayah={searchFocusActive || undefined}
      style={{ ['--font-scale' as string]: String(fontScale) }}
      id={`ayah-${ayah.number}`}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {/* One paper veil per inactive ayah while reciting — not per-word covers.
          Avoids hundreds of simultaneous opacity transitions on play. */}
      <span className="ayah-recess-veil" aria-hidden="true" />
      <VerseBookmarkRibbon
        bookmarked={bookmarked}
        focused={focused}
        hovered={hovered}
        side={bookmarkSide}
        chromeAlpha={bookmarkChromeAlpha}
        interactive={bookmarkInteractive}
        onToggle={() => onToggleBookmark(ayah.number)}
      />

      {arabicOnly ? (
        <p className="hafs-ayah" dir="rtl">
          {words.map((w, index) => {
            const ink = inks[index]!
            const isActive = ink.state === InkState.Active
            return (
              <HafsWord
                key={w.position}
                word={w}
                ink={ink}
                sweepMs={isActive ? activeSweepMs : null}
                activation={isActive ? (activeWord?.activation ?? 0) : 0}
                searchFlash={searchTargets.has(w.position)}
                searchRecessed={searchFocusActive && !searchTargets.has(w.position)}
                rootRef={isActive ? activeWordRef : undefined}
                onPlay={() => onPlayWord(ayah.number, w.position)}
                onHold={() =>
                  onHoldWord(ayah.number, w)
                }
                onContextMenu={(e) => {
                  e.preventDefault()
                  onHoldWord(ayah.number, w)
                }}
              />
            )
          })}
          <span
            className={verseNumberScript === 'arabic' ? 'ayah-mark' : 'ayah-mark ayah-mark--ltr'}
            dir={verseNumberScript === 'arabic' ? undefined : 'ltr'}
          >
            {ayahMark}
          </span>
        </p>
      ) : (
        <div className="words" dir={englishOnly ? 'ltr' : 'rtl'} data-lyric={englishOnly ? 'english' : 'arabic'}>
          {words.map((w, index) => {
            const ink = inks[index]!
            const isActive = ink.state === InkState.Active
            if (englishOnly && !englishWords[index]) return null
            return (
              <span key={w.position} className={englishOnly ? 'english-word-run' : 'word-unit-run'}>
                <WordUnit
                  word={w}
                  englishText={englishOnly ? englishWords[index] : undefined}
                  ink={ink}
                  sweepMs={isActive ? activeSweepMs : null}
                  activation={isActive ? (activeWord?.activation ?? 0) : 0}
                  showGloss={!englishOnly && showWordGloss}
                  showTransliteration={showTransliteration}
                  englishMode={englishOnly}
                  searchHit={hits(w.translation)}
                  searchFlash={(englishOnly ? visibleEnglishFlashPositions : searchTargets).has(w.position)}
                  searchRecessed={
                    searchFocusActive &&
                    !(englishOnly ? visibleEnglishFlashPositions : searchTargets).has(w.position)
                  }
                  rootRef={isActive ? activeWordRef : undefined}
                  onPlay={() => onPlayWord(ayah.number, w.position)}
                  onHold={() =>
                    onHoldWord(ayah.number, w)
                  }
                  onContextMenu={(e) => {
                    e.preventDefault()
                    onHoldWord(ayah.number, w)
                  }}
                />
                {englishOnly ? ' ' : null}
              </span>
            )
          })}
          <span
            className={verseNumberScript === 'arabic' ? 'ayah-mark' : 'ayah-mark ayah-mark--ltr'}
            dir={verseNumberScript === 'arabic' ? undefined : 'ltr'}
          >
            {ayahMark}
          </span>
        </div>
      )}

      {showTranslation && readingMode === 'arabic_english' && ayah.translation ? (
        <p
          ref={translationRef}
          className="ayah-translation"
          data-search-recessed={(searchFocusActive && flashWordPosition !== 0) || undefined}
          data-search-hit={translationHit ? 'true' : undefined}
          // Combined alpha documented for tests/devtools; visual recess is CSS.
          style={{
            ['--ayah-translation-alpha' as string]: String(ayahTranslationAlpha(dimmed)),
          }}
        >
          {translationContent}
        </p>
      ) : null}
    </article>
    </RepeatWashGateProvider>
  )
}

export const AyahBlock = memo(AyahBlockInner, (prev, next) => {
  // Ignore callback identity — ReaderScreen stabilizes them, but a custom
  // comparator keeps inactive ayahs from reconciling on every word tick.
  return (
    prev.ayah === next.ayah &&
    prev.activeWord === next.activeWord &&
    prev.activeWord?.activation === next.activeWord?.activation &&
    prev.isActiveAyah === next.isActiveAyah &&
    prev.dimmed === next.dimmed &&
    prev.focused === next.focused &&
    prev.keepActiveWordInView === next.keepActiveWordInView &&
    prev.readingMode === next.readingMode &&
    prev.verseNumberScript === next.verseNumberScript &&
    prev.showWordGloss === next.showWordGloss &&
    prev.showTransliteration === next.showTransliteration &&
    prev.showTranslation === next.showTranslation &&
    prev.bookmarked === next.bookmarked &&
    prev.bookmarkSide === next.bookmarkSide &&
    prev.bookmarkChromeAlpha === next.bookmarkChromeAlpha &&
    prev.bookmarkInteractive === next.bookmarkInteractive &&
    prev.speed === next.speed &&
    prev.fontScale === next.fontScale &&
    prev.searchQuery === next.searchQuery &&
    prev.flashWordPosition === next.flashWordPosition &&
    prev.flashWordPositions === next.flashWordPositions &&
    prev.searchFlashText === next.searchFlashText
  )
})
