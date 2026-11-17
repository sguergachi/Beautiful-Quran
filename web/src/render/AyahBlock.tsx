import { suppliedWords } from './suppliedWords'
import { foldEnglish } from '../domain/EnglishTypography'
import { memo, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode, type RefObject } from 'react'
import { markHold, MARK_HOLD_SLOP } from './markHold'
import { VerseAnnotation } from './VerseAnnotation'
import { GatherOrdinal } from './GatherOrdinal'
import { paperToggleHaptic } from '../ui/kit/paperHaptics'
import './readerVerse.css'
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
import { WordMotionProvider } from './WordMotionContext'
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
  /** True until the breaths finish; false during the surrounding-ink return fade. */
  searchFocusActive?: boolean
  /** Exact canonical-translation term when [flashWordPosition] is zero. */
  searchFlashText?: string | null
  /** Tap a word to start recitation at that word's timing. */
  onPlayWord: (ayah: number, wordPosition: number) => void
  onToggleBookmark: (ayah: number) => boolean
  onHoldWord: (ayah: number, word: Word) => void
  /** Gather mode. Holding the mark enters it; the verse body toggles once inside. */
  gathering?: boolean
  gatherOrdinal?: number | null
  onMarkTap?: () => void
  /** Holding ﴿N﴾ until its shamsa fills (Android `onAyahMarkHold`). */
  onMarkHold?: () => void
  onVerseBodyTap?: () => void
  /** A tap on the verse off its words: play from this ayah (Android `onAyahClick`). */
  onAyahTap?: (ayah: number) => void
  /** Passive green reading-place ribbon (Android `placeMarked`). */
  placeMarked?: boolean
  placeUnfurlSignal?: number
  /** Always draw the empty bookmark tip (touch / phone, as Android). */
  idleTipAlways?: boolean
  /** Verse note shown under the translation; null hides it. */
  annotationText?: string | null
  isEditingAnnotation?: boolean
  annotationsHidden?: boolean
  recededForAnnotationEdit?: boolean
  onAnnotationChange?: (text: string) => void
  onAnnotationEditDone?: (ayah: number) => void
  onEditAnnotation?: (ayah: number) => void
  onAnnotationDelete?: (ayah: number) => void
}

/** Android `highlightMatches`: a soft gold marker behind each match only. */
function markSearchMatches(text: string, query: string | null): ReactNode {
  if (!query) return suppliedWords(text)
  const folded = foldEnglish(text).toLowerCase()
  const parts: ReactNode[] = []
  let cursor = 0
  let i = folded.indexOf(query)
  while (i >= 0) {
    if (i > cursor) parts.push(...suppliedWords(text.slice(cursor, i), `m${cursor}`))
    parts.push(
      <mark className="translation-search-marker" key={`hit-${i}`}>
        {text.slice(i, i + query.length)}
      </mark>,
    )
    cursor = i + query.length
    i = folded.indexOf(query, cursor)
  }
  if (cursor === 0) return suppliedWords(text)
  if (cursor < text.length) parts.push(...suppliedWords(text.slice(cursor), `m${cursor}`))
  return parts
}

/**
 * Gather's gold soak — Android `inkSpotHighlight(fillBox)`: spreads from the
 * touch across the verse in 250 ms, and draws back when the verse is dropped.
 */
function GatherSpot({
  selected,
  seed,
  origin,
}: {
  selected: boolean
  seed: number
  origin: RefObject<{ x: number; y: number } | null>
}) {
  const ref = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    const el = ref.current
    const box = el?.parentElement?.getBoundingClientRect()
    if (!el || !box || !selected) return
    const o = origin.current
    const x = o ? ((o.x - box.left) / Math.max(1, box.width)) * 100 : 50
    const y = o ? ((o.y - box.top) / Math.max(1, box.height)) * 100 : 30
    el.style.setProperty('--spot-x', `${Math.min(100, Math.max(0, x))}%`)
    el.style.setProperty('--spot-y', `${Math.min(100, Math.max(0, y))}%`)
  }, [selected, origin])
  // Seeded, so each verse's stain has its own slightly different reach.
  const wobble = ((seed * 9301 + 49297) % 233280) / 233280
  return (
    <span
      ref={ref}
      className="gather-spot"
      data-selected={selected || undefined}
      style={{ ['--spot-soft' as string]: `${10 + wobble * 8}%` }}
      aria-hidden="true"
    />
  )
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
  searchFocusActive = false,
  searchFlashText = null,
  onPlayWord,
  onToggleBookmark,
  onHoldWord,
  gathering = false,
  gatherOrdinal = null,
  onMarkTap,
  onMarkHold,
  onVerseBodyTap,
  onAyahTap,
  placeMarked = false,
  placeUnfurlSignal = 0,
  idleTipAlways = false,
  annotationText = null,
  isEditingAnnotation = false,
  annotationsHidden = false,
  recededForAnnotationEdit = false,
  onAnnotationChange,
  onAnnotationEditDone,
  onEditAnnotation,
  onAnnotationDelete,
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
  const searchTargetAyah = flashWordPosition != null
  // Derive ink policy once for the ayah, matching Android AyahBlock. The
  // renderer branches consume these decisions and never reinterpret playback.
  const activeSweepMs = InkEngine.sweepMs(activeWord, speed)
  const inks = words.map((word) =>
    InkEngine.word(word.position, activeWord, isActiveAyah, dimmed),
  )
  const activeWordRef = useRef<HTMLElement | null>(null)
  const ayahRef = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (!focused) ayahRef.current?.querySelectorAll<HTMLElement>('[data-reader-word]').forEach((word) => { word.tabIndex = -1 })
  }, [focused])
  const [hovered, setHovered] = useState(false)
  const spotOrigin = useRef<{ x: number; y: number } | null>(null)
  const query = searchQuery?.toLowerCase() ?? null
  const hits = (translation: string) =>
    query != null && foldEnglish(translation).toLowerCase().includes(query)
  const translationRef = useRef<HTMLParagraphElement>(null)
  const translationContent = useMemo<ReactNode>(() => {
    if (flashWordPosition !== 0) return markSearchMatches(ayah.translation, query)
    const ranges = searchHitTextRanges(ayah.translation, searchFlashText)
    if (!ranges.length) return suppliedWords(ayah.translation)
    const parts: ReactNode[] = []
    let cursor = 0
    for (const [start, end] of ranges) {
      if (start > cursor) parts.push(...suppliedWords(ayah.translation.slice(cursor, start), `p${cursor}`))
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
    if (cursor < ayah.translation.length) parts.push(...suppliedWords(ayah.translation.slice(cursor), `p${cursor}`))
    return parts
  }, [ayah.translation, flashWordPosition, searchFlashText, query])

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

  // A hold that popped owns its own lift: the click that follows must not
  // also toggle the verse the reader just gathered.
  const poppedThisPress = useRef(false)
  const startMarkHold = (event: ReactPointerEvent<HTMLButtonElement>) => {
    poppedThisPress.current = false
    if (gathering || !onMarkHold) return
    if (event.pointerType === 'mouse' && event.button !== 0) return
    const glyph = event.currentTarget.querySelector<HTMLElement>('.ayah-mark-glyph') ?? event.currentTarget
    const r = glyph.getBoundingClientRect()
    const id = event.pointerId
    const sx = event.clientX
    const sy = event.clientY
    markHold.send('down', {
      bounds: { left: r.left, top: r.top, width: r.width, height: r.height },
      baseline: null,
      finger: { x: sx, y: sy },
      rtl: !englishOnly,
      onHold: () => {
        poppedThisPress.current = true
        onMarkHold()
      },
    })
    const end = (kind: 'released' | 'cancelled') => {
      window.removeEventListener('pointermove', move, true)
      window.removeEventListener('pointerup', up, true)
      window.removeEventListener('pointercancel', cancel, true)
      markHold.send(kind)
    }
    const move = (e: PointerEvent) => {
      if (e.pointerId === id && Math.hypot(e.clientX - sx, e.clientY - sy) > MARK_HOLD_SLOP) end('cancelled')
    }
    const up = (e: PointerEvent) => {
      if (e.pointerId === id) end('released')
    }
    const cancel = (e: PointerEvent) => {
      if (e.pointerId === id) end('cancelled')
    }
    window.addEventListener('pointermove', move, true)
    window.addEventListener('pointerup', up, true)
    window.addEventListener('pointercancel', cancel, true)
  }
  const markButton = (
    <button
      type="button"
      className={verseNumberScript === 'arabic' ? 'ayah-mark' : 'ayah-mark ayah-mark--ltr'}
      dir={verseNumberScript === 'arabic' ? undefined : 'ltr'}
      aria-label={gathering ? `Ayah ${ayah.number}` : `Hold to gather ayah ${ayah.number}`}
      tabIndex={focused ? 0 : -1}
      onPointerDown={startMarkHold}
      onContextMenu={(event) => event.preventDefault()}
      onClick={(event) => {
        event.stopPropagation()
        if (poppedThisPress.current) {
          poppedThisPress.current = false
          return
        }
        if (gathering) {
          paperToggleHaptic(gatherOrdinal == null)
          onMarkTap?.()
        } else if (event.detail === 0) {
          // Keyboard / assistive activation has no hold to watch.
          onMarkHold?.()
        }
      }}
    >
      <span className="ayah-mark-glyph">{ayahMark}</span>
    </button>
  )
  // Word tiles only: prose (English, continuous Hafs) breaks inline already.
  const markWithLastWord = !arabicOnly && !englishOnly && words.length > 0

  return (
    <WordMotionProvider>
    <article
      ref={ayahRef}
      className="ayah-block"
      data-ayah={ayah.number}
      data-word-group=""
      data-gathered={gatherOrdinal != null || undefined}
      data-annotation-recede={recededForAnnotationEdit || undefined}
      onPointerDown={(event) => {
        spotOrigin.current = { x: event.clientX, y: event.clientY }
      }}
      onClick={(event) => {
        const target = event.target as HTMLElement
        if (gathering) {
          if (target.closest('button')) return
          paperToggleHaptic(gatherOrdinal == null)
          onVerseBodyTap?.()
          return
        }
        // Off the words (translation, paper between words): play from here.
        if (!onAyahTap || target.closest('[data-reader-word], button, a, input, textarea, .verse-annotation')) return
        const selection = window.getSelection()
        if (selection && !selection.isCollapsed) return
        onAyahTap(ayah.number)
      }}
      data-ayah-active={isActiveAyah || undefined}
      data-dimmed={dimmed || undefined}
      data-search-flash-ayah={searchTargetAyah || undefined}
      style={{ ['--font-scale' as string]: String(fontScale) }}
      id={`ayah-${ayah.number}`}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {/* One paper veil per inactive ayah while reciting — not per-word covers.
          Avoids hundreds of simultaneous opacity transitions on play. */}
      <span className="ayah-recess-veil" aria-hidden="true" />
      {gathering ? (
        <>
          <GatherSpot selected={gatherOrdinal != null} seed={ayah.surahId * 1000 + ayah.number} origin={spotOrigin} />
          {gatherOrdinal != null ? (
            <GatherOrdinal ordinal={gatherOrdinal} side={bookmarkSide} />
          ) : null}
        </>
      ) : (
        <VerseBookmarkRibbon
          bookmarked={bookmarked}
          placeMarked={placeMarked}
          placeUnfurlSignal={placeUnfurlSignal}
          reservePlaceLane
          idleTipAlways={idleTipAlways}
          focused={focused}
          hovered={hovered}
          side={bookmarkSide}
          chromeAlpha={bookmarkChromeAlpha}
          interactive={bookmarkInteractive}
          tabIndex={focused ? 0 : -1}
          onToggle={() => onToggleBookmark(ayah.number)}
          onLongPress={bookmarked && onEditAnnotation ? () => onEditAnnotation(ayah.number) : undefined}
        />
      )}

      {arabicOnly ? (
        <p className="hafs-ayah" lang="ar" dir="rtl" translate="no">
          {words.map((w, index) => {
            const ink = inks[index]!
            const isActive = ink.state === InkState.Active
            return (
              <HafsWord
                key={w.position}
                tabIndex={focused && index === 0 ? 0 : -1}
                word={w}
                ink={ink}
                sweepMs={isActive ? activeSweepMs : null}
                  motion={{ key: `${ayah.surahId}:${ayah.number}:${w.position}`, timing: isActive ? activeWord : null,
                    isAyahFinal: index === words.length - 1, previousArabic: words[index - 1]?.arabic,
                    nextArabic: words[index + 1]?.arabic, nextIsAyahFinal: index + 1 === words.length - 1 }}
                activation={isActive ? (activeWord?.activation ?? 0) : 0}
                searchFlash={searchFocusActive && searchTargets.has(w.position)}
                searchRecessed={searchTargetAyah && !searchTargets.has(w.position)}
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
          {markButton}
        </p>
      ) : (
        <div className="words" dir={englishOnly ? 'ltr' : 'rtl'} data-lyric={englishOnly ? 'english' : 'arabic'}>
          {words.map((w, index) => {
            const ink = inks[index]!
            const isActive = ink.state === InkState.Active
            if (englishOnly && !englishWords[index]) return null
            return (
              <span
                key={w.position}
                className={
                  englishOnly
                    ? 'english-word-run'
                    : markWithLastWord && index === words.length - 1
                      ? 'word-tail'
                      : 'word-unit-run'
                }
              >
                <WordUnit
                  word={w}
                  tabIndex={focused && index === (englishOnly ? englishWords.findIndex(Boolean) : 0) ? 0 : -1}
                  englishText={englishOnly ? englishWords[index] : undefined}
                  ink={ink}
                  sweepMs={isActive ? activeSweepMs : null}
                  motion={{ key: `${ayah.surahId}:${ayah.number}:${w.position}`, timing: isActive ? activeWord : null,
                    isAyahFinal: index === words.length - 1, previousArabic: words[index - 1]?.arabic,
                    nextArabic: words[index + 1]?.arabic, nextIsAyahFinal: index + 1 === words.length - 1 }}
                  activation={isActive ? (activeWord?.activation ?? 0) : 0}
                  showGloss={!englishOnly && showWordGloss}
                  showTransliteration={showTransliteration}
                  englishMode={englishOnly}
                  searchHit={hits(w.translation)}
                  searchFlash={
                    searchFocusActive &&
                    (englishOnly ? visibleEnglishFlashPositions : searchTargets).has(w.position)
                  }
                  searchRecessed={
                    searchTargetAyah &&
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
                {markWithLastWord && index === words.length - 1 ? markButton : null}
              </span>
            )
          })}
          {markWithLastWord ? null : markButton}
        </div>
      )}

      {showTranslation && readingMode === 'arabic_english' && ayah.translation ? (
        <p
          ref={translationRef}
          className="ayah-translation"
          data-search-recessed={(searchTargetAyah && flashWordPosition !== 0) || undefined}
          // Combined alpha documented for tests/devtools; visual recess is CSS.
          style={{
            ['--ayah-translation-alpha' as string]: String(ayahTranslationAlpha(dimmed)),
          }}
        >
          {translationContent}
        </p>
      ) : null}

      {bookmarked && !gathering && (isEditingAnnotation || annotationText != null) ? (
        <VerseAnnotation
          text={annotationText ?? ''}
          isEditing={isEditingAnnotation}
          hidden={annotationsHidden && !isEditingAnnotation}
          onChange={onAnnotationChange}
          onEditDone={() => onAnnotationEditDone?.(ayah.number)}
          onStartEdit={onEditAnnotation ? () => onEditAnnotation(ayah.number) : undefined}
          onDelete={() => onAnnotationDelete?.(ayah.number)}
        />
      ) : null}
    </article>
    </WordMotionProvider>
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
    prev.searchFocusActive === next.searchFocusActive &&
    prev.searchFlashText === next.searchFlashText &&
    prev.gathering === next.gathering &&
    prev.gatherOrdinal === next.gatherOrdinal &&
    prev.placeMarked === next.placeMarked &&
    prev.placeUnfurlSignal === next.placeUnfurlSignal &&
    prev.idleTipAlways === next.idleTipAlways &&
    prev.annotationText === next.annotationText &&
    prev.isEditingAnnotation === next.isEditingAnnotation &&
    prev.annotationsHidden === next.annotationsHidden &&
    prev.recededForAnnotationEdit === next.recededForAnnotationEdit &&
    (prev.onAyahTap == null) === (next.onAyahTap == null) &&
    (prev.onEditAnnotation == null) === (next.onEditAnnotation == null)
  )
})
