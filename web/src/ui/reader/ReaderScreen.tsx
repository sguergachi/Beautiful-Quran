import { useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react'
import { AyahBlock } from '../../render/AyahBlock'
import { BasmalahCalligraphy } from '../../render/BasmalahCalligraphy'
import { getTuning, prefaceState } from './InkEngine'
import { TRANSLITERATION_COLOR_ALPHA } from './WordHighlight'
import {
  isAway,
  playbackFocusTarget,
  pointUp,
  startsFullAyahRepeat,
} from './focus/FocusEngine'
import { ReturnToAyahButton } from './ReturnToAyahButton'
import { surahOpensWithBasmalahPreface } from '../../domain/Basmalah'
import {
  appStore,
  useAppState,
  COVER_LAYER,
  READER_LAYER,
} from '../../store/appStore'
import type { StackLayer } from '../paper/stack'
import { PaperInput } from '../kit/PaperInput'
import {
  IconBuffering,
  IconChevronDown,
  IconChevronUp,
  IconClose,
  IconFastForward,
  IconFastRewind,
  IconPause,
  IconPlay,
  IconRepeat,
  IconRepeatOne,
  IconSearch,
  IconTune,
} from '../icons/PlaybackIcons'
import { AyahSelectorRail, type AyahSelectorRailHandle } from './AyahSelectorRail'
import { AyahRailTip } from './AyahRailTip'
import { BookmarkNoteTip } from './BookmarkNoteTip'
import {
  dismissEducation,
  shouldShowAyahRailTip,
  shouldShowBookmarkNoteTip,
} from '../../data/education'
import { OrnateSurahTitle } from './OrnateSurahTitle'
import { NextChapterFooter } from './NextChapterFooter'
import { PageBreak } from './PageBreak'
import { buildReaderItems, sliceReaderItems } from './readerItems'
import { ReaderFocusController } from './focus/ReaderFocusController'
import { selectedPlaybackAyah } from './selectedPlaybackAyah'
import { shouldPauseFollowOnDrag } from './followGesture'
import { isRecitingSession } from './recitingActive'
import { readerAyahInkPolicyActive, readerInkAyah } from './ReaderHighlightState'
import {
  AYAH_SPACER_EST_PX,
  useProgressiveAyahWindow,
} from './useProgressiveAyahWindow'
import { RootViewer } from '../root/RootViewer'
import { SearchHitFlash, searchHitFlashTotalMs } from './SearchHitFlash'
import { fieldWeaveBackground, GeneratedRosette } from '../theme/GeneratedOrnament'
import { chapterOrnamentSeed, generateChapterOrnament } from '../theme/ornamentGenerator'
import { resolveTheme } from '../App'
import type { Word } from '../../data/models'
import { isKeyboardControl, readerKeyboardAction } from './keyboardNavigation'

/** Usable in-surah query — mirrors Android `SurahSearchState.activeQuery`. */
function activeSearchQuery(active: boolean, query: string): string | null {
  const trimmed = query.trim()
  return active && trimmed.length >= 2 ? trimmed : null
}

/**
 * A data-URI SVG background can't resolve `var(--ink)` (it isn't part of
 * the live cascade), so the whisper-faint field weave needs a literal
 * color resolved from the reader's own theme instead — same `--ink` values
 * as styles.css, at the same ~4% alpha Android's `onBackground.copy(alpha
 * = 0.04f)` uses. `--emboss-light` is white at low alpha in every theme.
 */
function chapterWeaveInk(themeMode: string): string {
  return resolveTheme(themeMode) === 'light' ? 'rgba(28, 27, 24, 0.04)' : 'rgba(232, 226, 213, 0.04)'
}

/**
 * The surah header's own ornament: a distinct rosette and backing field per
 * chapter, grown from one seed — ayah count is the dominant term (length as
 * fingerprint), folded with the chapter number so all 114 chapters render
 * distinctly even though many share an ayah count. Both are static — part
 * of the page's fixed typography, not a ceremony — and themed off the
 * reader's own gold/emboss/ink custom properties rather than the entrance
 * cover's fixed leather gold, since this sits on the page background.
 */
function SurahHeaderOrnament({
  chapterNumber,
  ayahCount,
  themeMode,
}: {
  chapterNumber: number
  ayahCount: number
  themeMode: string
}) {
  const ornament = useMemo(
    () => generateChapterOrnament(chapterOrnamentSeed(chapterNumber, ayahCount)),
    [chapterNumber, ayahCount],
  )
  const weave = useMemo(
    () => fieldWeaveBackground(ornament.field, chapterWeaveInk(themeMode), 'rgba(255, 255, 255, 0.05)'),
    [ornament.field, themeMode],
  )
  return (
    <>
      <div className="surah-header-weave" style={weave} aria-hidden="true" />
      <GeneratedRosette
        spec={ornament.rosette}
        className="rosette"
        built
        animated={false}
        ruleWidth={4.6}
        hairWidth={4.6}
        brightGold="var(--gold-bright)"
        deepGold="var(--gold-deep)"
        embossDark="var(--emboss-dark)"
        embossLight="var(--emboss-light)"
      />
    </>
  )
}

export function ReaderScreen({ stackLayer }: { stackLayer: StackLayer }) {
  const state = useAppState()
  const inkTuning = getTuning()
  const content = state.content
  const scrollRef = useRef<HTMLDivElement>(null)
  const headerRef = useRef<HTMLElement>(null)
  const focusRef = useRef<ReaderFocusController | null>(null)
  if (focusRef.current == null) {
    focusRef.current = new ReaderFocusController()
  }
  // Lazily created once — definite after the null check above.
  const focus = focusRef.current
  const initialAyah = Math.max(1, state.openAyah || 1)
  const [focusedAyah, setFocusedAyah] = useState(initialAyah)
  /**
   * Continuous readout for the rail marker (Android `focusedPosition`).
   *
   * Kept in a ref rather than React state because it changes every scroll
   * frame; routing it through React would re-render the whole reader (and
   * walk every mounted ayah's memo comparator) 60×/s during scroll. The
   * rail reads it live via [AyahSelectorRailHandle.schedulePaint] and
   * redraws its canvas on the next animation frame — no React reconciliation
   * for the continuous position. Discrete ayah boundaries still go through
   * [setFocusedAyah] so the focused prop on the matching ayah block flips.
   */
  const focusedPositionRef = useRef(initialAyah)
  const railHandleRef = useRef<AyahSelectorRailHandle | null>(null)
  const bumpRail = useCallback(() => {
    railHandleRef.current?.schedulePaint()
  }, [])
  const [showReturn, setShowReturn] = useState(false)
  const [returnPointUp, setReturnPointUp] = useState(false)
  const [activeExceedsViewport, setActiveExceedsViewport] = useState(false)
  const [searchActive, setSearchActive] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchIndex, setSearchIndex] = useState(0)
  /** Android `showTopTitle` — header scrolled fully off the page. */
  const [showTopTitle, setShowTopTitle] = useState(false)
  /**
   * Previous chapter name held in the top bar during next-chapter advance so
   * it can fade out instead of vanishing when content remounts.
   */
  const [pinnedTopNavTitle, setPinnedTopNavTitle] = useState<{
    id: number
    nameArabic: string
    nameTransliteration: string
  } | null>(null)
  const [chapterAdvancing, setChapterAdvancing] = useState(false)
  /** 0..1 invitation → header morph during continuous advance. */
  const [headerMorph, setHeaderMorph] = useState(0)
  /**
   * 0 = verse body parked below the header after next-chapter handoff;
   * 1 = settled. Rises as soon as the header lands (Android parity).
   */
  const [verseReveal, setVerseReveal] = useState(1)
  const followWasEnabled = useRef(true)
  const chapterAdvancingRef = useRef(false)
  const headerMorphRef = useRef(0)
  const verseRevealRef = useRef(1)
  /** True between next-chapter handoff and the delayed verse rise finishing. */
  const verseRevealPendingRef = useRef(false)
  /** Surah id the delayed rise belongs to (ignore if the reader navigated away). */
  const verseRevealForSurahRef = useRef(0)
  const nextSurahRef = useRef<{
    id: number
    nameArabic: string
    nameTransliteration: string
    nameTranslation: string
    revelationPlace: string
    ayahCount: number
  } | null>(null)
  /** While a rail/search jump is in flight, pin focus UI to the commit target. */
  const pendingJumpAyah = useRef<number | null>(null)
  const [mountRequest, setMountRequest] = useState<{
    surahId: number
    ayah: number
  } | null>(null)
  const requestedMountAyah =
    mountRequest != null && mountRequest.surahId === content?.surah.id
      ? mountRequest.ayah
      : null

  /** Ensure progressive rendering has a real block before focus measures it. */
  const focusAyah = useCallback(
    (
      ayah: number,
      options: { animate?: boolean; preRoll?: boolean } = {},
    ) => {
      const surahId = content?.surah.id
      if (surahId != null && ayah > 0) {
        setMountRequest({ surahId, ayah })
      }
      return focus.focus(ayah, options)
    },
    [content?.surah.id, focus],
  )

  // Stable callbacks so memo(AyahBlock) can skip inactive verses on word ticks.
  const onKeepWordInView = useCallback((wordEl: HTMLElement) => {
    focus.keepWordInView(wordEl)
  }, [])
  const onPlayWord = useCallback((ayah: number, pos: number) => {
    void appStore.playFromWord(ayah, pos)
  }, [])
  const sheetRef = useRef<HTMLDivElement>(null)
  const [sheetSize, setSheetSize] = useState({ width: 0, height: 0 })
  const [initialFocusSettled, setInitialFocusSettled] = useState(false)
  const [ayahRailTipOpen, setAyahRailTipOpen] = useState(false)
  const [ayahRailTipCenterY, setAyahRailTipCenterY] = useState(Number.NaN)
  const [bookmarkNoteTipOpen, setBookmarkNoteTipOpen] = useState(false)
  const [bookmarkNoteTipRendered, setBookmarkNoteTipRendered] = useState(false)
  const [bookmarkNoteTipAyah, setBookmarkNoteTipAyah] = useState(0)
  const [bookmarkNoteTipCenterY, setBookmarkNoteTipCenterY] = useState(Number.NaN)
  const [ayahRailTipRendered, setAyahRailTipRendered] = useState(false)
  const bookmarkNoteTipOpenRef = useRef(false)
  bookmarkNoteTipOpenRef.current = bookmarkNoteTipOpen
  const ayahRailTipOpenRef = useRef(false)
  ayahRailTipOpenRef.current = ayahRailTipOpen

  const dismissBookmarkNoteTip = useCallback(() => {
    dismissEducation('bookmark_note')
    setBookmarkNoteTipOpen(false)
  }, [])
  const dismissAyahRailTip = useCallback(() => {
    dismissEducation('ayah_rail')
    setAyahRailTipOpen(false)
  }, [])

  const onToggleBookmark = useCallback(
    (n: number) => {
      const nowBookmarked = appStore.toggleBookmark(n)
      const settings = appStore.getSnapshot().settings
      if (
        shouldShowBookmarkNoteTip({
          developerMode: settings.developerMode,
          educationGuidesEnabled: settings.educationGuidesEnabled,
          nowBookmarked,
        })
      ) {
        if (ayahRailTipOpenRef.current) {
          dismissEducation('ayah_rail')
          setAyahRailTipOpen(false)
        }
        setBookmarkNoteTipCenterY(Number.NaN)
        setBookmarkNoteTipAyah(n)
        void focusAyah(n, { animate: true, preRoll: true }).finally(() => {
          setBookmarkNoteTipOpen(true)
        })
      }
      return nowBookmarked
    },
    [focusAyah],
  )
  const surahIdRef = useRef(content?.surah.id ?? 0)
  surahIdRef.current = content?.surah.id ?? 0
  const onHoldWord = useCallback(
    (a: number, word: Word) => {
      appStore.openRootViewer(surahIdRef.current, a, word)
    },
    [],
  )

  const bookmarkedAyahs = useMemo(() => {
    const id = content?.surah.id
    if (id == null) return new Set<number>()
    const set = new Set<number>()
    for (const b of state.bookmarks) {
      if (b.surahId === id) set.add(b.ayah)
    }
    return set
  }, [state.bookmarks, content?.surah.id])

  const side = state.settings.ayahSelectorSide
  // Keep depth/active correct for the parked empty reader at app start.
  const depth = Math.max(0, stackLayer - READER_LAYER)
  const isTop = stackLayer === READER_LAYER
  const peeking = stackLayer > READER_LAYER
  const active = isTop || peeking
  // Instant recess — hold across ayah-join buffering, release on user pause
  // in the same tick (no 350 ms debounce lag on the transport).
  const recitingActive = isRecitingSession({
    sameSurah: state.player.nowPlaying?.surahId === content?.surah.id,
    isPlaying: state.player.isPlaying,
    isBuffering: state.player.isBuffering,
  })
  const receded = recitingActive && !searchActive

  // Virtualization center: follow the reciting ayah, else the open/focus verse.
  // Window only expands during scroll (never unmounts under the viewport).
  const mountCenter =
    state.activeAyah != null && state.activeAyah > 0
      ? state.activeAyah
      : focusedAyah || state.openAyah || 1
  const mountRange = useProgressiveAyahWindow(
    content?.surah.ayahCount ?? 0,
    mountCenter,
    content?.surah.id,
    requestedMountAyah,
  )
  const nextSurah = useMemo(() => {
    const id = content?.surah.id
    if (id == null || id >= 114) return null
    return state.surahs.find((s) => s.id === id + 1) ?? null
  }, [content?.surah.id, state.surahs])
  nextSurahRef.current = nextSurah

  /**
   * Continuous next-chapter advance (web: button tap only — no overscroll).
   * Fly a cloned opening from the footer up to the header slot, then hand off.
   */
  const advanceToNextChapter = useCallback(async (nextId: number) => {
    if (chapterAdvancingRef.current) return
    const el = scrollRef.current
    const next = nextSurahRef.current
    const prevContent = content
    if (!el || !next || next.id !== nextId) {
      appStore.prepareSurah(nextId)
      appStore.openSurah(nextId, 1)
      return
    }
    // Pin the top-nav title (usually visible at chapter end) for a soft fade.
    if (prevContent && showTopTitle) {
      setPinnedTopNavTitle({
        id: prevContent.surah.id,
        nameArabic: prevContent.surah.nameArabic,
        nameTransliteration: prevContent.surah.nameTransliteration,
      })
    }

    chapterAdvancingRef.current = true
    setChapterAdvancing(true)
    appStore.setFollowEnabled(false)
    appStore.prepareSurah(nextId)

    const ease = (t: number) => {
      const x = Math.min(1, Math.max(0, t))
      return x * x * (3 - 2 * x)
    }
    const animate = (ms: number, onFrame: (e: number) => void) =>
      new Promise<void>((resolve) => {
        const t0 = performance.now()
        const tick = (now: number) => {
          const u = Math.min(1, (now - t0) / ms)
          onFrame(ease(u))
          if (u < 1) requestAnimationFrame(tick)
          else resolve()
        }
        requestAnimationFrame(tick)
      })

    await new Promise<void>((r) => requestAnimationFrame(() => r()))
    const opening =
      (el.querySelector('.next-chapter-opening') as HTMLElement | null) ??
      (el.querySelector('.next-chapter') as HTMLElement | null)
    const scrollRect = el.getBoundingClientRect()
    const openRect = opening?.getBoundingClientRect()
    const startTop = openRect?.top ?? scrollRect.bottom - 200
    const endTop = scrollRect.top + 8
    const startLeft = openRect?.left ?? scrollRect.left
    const width = openRect?.width ?? scrollRect.width

    headerMorphRef.current = 0
    setHeaderMorph(0)

    // Flying clone of the opening slides continuously to the header slot.
    let flyer: HTMLElement | null = null
    if (opening) {
      flyer = opening.cloneNode(true) as HTMLElement
      flyer.classList.add('chapter-fly-header')
      flyer.setAttribute('aria-hidden', 'true')
      Object.assign(flyer.style, {
        position: 'fixed',
        top: `${startTop}px`,
        left: `${startLeft}px`,
        width: `${width}px`,
        margin: '0',
        zIndex: '40',
        pointerEvents: 'none',
        opacity: '1',
        transform: 'translate3d(0,0,0)',
        willChange: 'transform, opacity',
        transition: 'none',
      })
      document.body.appendChild(flyer)
      opening.style.transition = 'opacity 200ms ease'
      opening.style.opacity = '0'
    }

    // Soft-fade invitation chrome (NEXT + pill).
    const inviteBits = el.querySelectorAll(
      '.next-chapter-invite-top, .next-chapter-invite-pill',
    )
    inviteBits.forEach((node) => {
      const n = node as HTMLElement
      n.style.transition = 'opacity 280ms ease'
      n.style.opacity = '0'
    })

    const flyerTravel = startTop - endTop
    // Scroll previous verses clear of the viewport (not just a soft nudge).
    const listEndLift = Math.max(flyerTravel, window.innerHeight * 0.55, 320)

    await animate(780, (e) => {
      headerMorphRef.current = Math.min(1, e / 0.35)
      setHeaderMorph(headerMorphRef.current)
      if (flyer) {
        flyer.style.transform = `translate3d(0, ${-flyerTravel * e}px, 0)`
      }
      // Front-load exit: previous page fully faded/scrolled by ~28% of the fly.
      const exitT = Math.min(1, e / 0.28)
      const exitE = 1 - (1 - exitT) * (1 - exitT)
      el.style.willChange = 'transform, opacity'
      el.style.transform = `translate3d(0, ${-listEndLift * exitE}px, 0)`
      el.style.opacity = String(1 - exitE)
    })

    // Handoff under the flyer (covers the remount).
    verseRevealPendingRef.current = true
    verseRevealForSurahRef.current = nextId
    verseRevealRef.current = 0
    setVerseReveal(0)
    appStore.openSurah(nextId, 1)
    await new Promise<void>((r) => requestAnimationFrame(() => r()))
    el.scrollTop = 0
    el.style.transform = ''
    el.style.opacity = ''
    // Crossfade: flyer out, settled header in — nothing snaps off.
    const realHeader = el.querySelector(
      '.surah-header:not(.chapter-fly-header):not(.next-chapter-opening)',
    ) as HTMLElement | null
    if (realHeader) {
      realHeader.style.opacity = '0'
      realHeader.style.transition = 'opacity 320ms ease'
    }
    await new Promise<void>((r) => requestAnimationFrame(() => r()))
    if (realHeader) realHeader.style.opacity = '1'
    if (flyer) {
      flyer.style.transition = 'opacity 320ms ease'
      flyer.style.opacity = '0'
    }
    await new Promise<void>((r) => window.setTimeout(r, 320))
    if (flyer?.parentNode) flyer.parentNode.removeChild(flyer)
    if (realHeader) {
      realHeader.style.transition = ''
      realHeader.style.opacity = ''
    }
    // Clean residual inline styles on the (now unmounted) old footer if still present.
    inviteBits.forEach((node) => {
      const n = node as HTMLElement
      n.style.transition = ''
      n.style.opacity = ''
    })
    headerMorphRef.current = 0
    setHeaderMorph(0)
    chapterAdvancingRef.current = false
    setChapterAdvancing(false)
    // Top-nav pin has finished fading with data-advancing.
    setPinnedTopNavTitle(null)

    // Verses fade and rise in as soon as the header has landed.
    if (
      !verseRevealPendingRef.current ||
      verseRevealForSurahRef.current !== nextId
    ) {
      return
    }
    await animate(640, (e) => {
      verseRevealRef.current = e
      setVerseReveal(e)
    })
    if (verseRevealForSurahRef.current !== nextId) return
    verseRevealRef.current = 1
    setVerseReveal(1)
    verseRevealPendingRef.current = false
  }, [content, showTopTitle])

  // Clear morph when the chapter changes.
  useEffect(() => {
    headerMorphRef.current = 0
    setHeaderMorph(0)
    // Keep the delayed rise only for the surah the advance just handed off.
    const keepParked =
      verseRevealPendingRef.current &&
      content?.surah.id === verseRevealForSurahRef.current
    if (!keepParked) {
      verseRevealPendingRef.current = false
      verseRevealRef.current = 1
      setVerseReveal(1)
    }
  }, [content?.surah.id])

  const activeQuery = activeSearchQuery(searchActive, searchQuery)
  // Keep the input snappy — match/highlight work trails by a frame or two.
  const deferredQuery = useDeferredValue(activeQuery)
  const searchableAyahs = useMemo(() => {
    if (!content) return [] as { number: number; translationLower: string; glossLowers: string[] }[]
    return content.ayahs.map((a) => ({
      number: a.number,
      translationLower: a.translation.toLowerCase(),
      glossLowers: a.words.map((w) => w.translation.toLowerCase()),
    }))
  }, [content])
  const searchMatches = useMemo(() => {
    if (!deferredQuery) return [] as number[]
    const q = deferredQuery.toLowerCase()
    const matches: number[] = []
    for (const a of searchableAyahs) {
      if (
        a.translationLower.includes(q) ||
        a.glossLowers.some((g) => g.includes(q))
      ) {
        matches.push(a.number)
      }
    }
    return matches
  }, [searchableAyahs, deferredQuery])
  const matchAyahSet = useMemo(() => new Set(searchMatches), [searchMatches])
  const matchIndex = Math.min(
    Math.max(0, searchIndex),
    Math.max(0, searchMatches.length - 1),
  )

  // Reset search / collapsed title when the surah changes.
  useEffect(() => {
    setSearchActive(false)
    setSearchQuery('')
    setSearchIndex(0)
    setShowTopTitle(false)
  }, [content?.surah.id])

  // Unread-style chrome: once the opening header leaves the scrollport,
  // the surah name reappears centred in the top bar (Android OrnateSurahTitle).
  useEffect(() => {
    const root = scrollRef.current
    const header = headerRef.current
    if (!root || !header || !content || !isTop) return
    const io = new IntersectionObserver(
      (entries) => {
        const entry = entries[0]
        if (!entry) return
        setShowTopTitle(!entry.isIntersecting)
      },
      { root, threshold: 0 },
    )
    io.observe(header)
    return () => io.disconnect()
  }, [content?.surah.id, isTop])

  // New query → first match.
  useEffect(() => {
    setSearchIndex(0)
  }, [activeQuery])

  // Jump the reading line to the current search hit.
  // Debounce while the query is changing so each keystroke does not start a
  // Motion glide; prev/next (matchIndex only) jumps immediately.
  const prevSearchQueryRef = useRef(deferredQuery)
  useEffect(() => {
    if (!isTop || !content || deferredQuery == null) return
    const target = searchMatches[matchIndex]
    if (target == null) return
    const queryChanged = prevSearchQueryRef.current !== deferredQuery
    prevSearchQueryRef.current = deferredQuery
    const delay = queryChanged ? 140 : 0
    const handle = window.setTimeout(() => {
      pendingJumpAyah.current = target
      setFocusedAyah(target)
      focusedPositionRef.current = target
      bumpRail()
      void focusAyah(target, { animate: true, preRoll: true }).finally(() => {
        setFocusedAyah(target)
        focusedPositionRef.current = target
        bumpRail()
        pendingJumpAyah.current = null
      })
    }, delay)
    return () => window.clearTimeout(handle)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deferredQuery, matchIndex, searchMatches, isTop, content?.surah.id])

  useEffect(() => {
    const el = scrollRef.current
    const count = content?.surah.ayahCount ?? 1
    focus.bind(el, count, 0)
  }, [content?.surah.id, content?.surah.ayahCount, isTop])

  // Settle every explicit reader open — one frame after mount so the peel
  // keeps its first paint. The revision distinguishes bookmark/search opens
  // from playback and rail updates to openAyah.
  // Continuous next-chapter advance owns scroll/transform — only pin focus.
  useEffect(() => {
    if (!content || !isTop) return
    setInitialFocusSettled(false)
    if (chapterAdvancingRef.current) {
      setFocusedAyah(Math.max(1, state.openAyah || 1))
      setShowTopTitle(false)
      return
    }
    const ayah = state.openAyah || 1
    let cancelled = false
    const raf = requestAnimationFrame(() => {
      if (cancelled) return
      void focusAyah(ayah, { animate: false, preRoll: false }).then(() => {
        if (cancelled) return
        setFocusedAyah(focus.focusedAyah())
        setInitialFocusSettled(true)
      })
    })
    return () => {
      cancelled = true
      cancelAnimationFrame(raf)
    }
    // Explicit opens only — not playback updates to the session anchor.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [content?.surah.id, state.readerOpenRevision])

  // Measure the reader sheet for tip placement.
  useEffect(() => {
    const el = sheetRef.current
    if (!el) return
    const measure = () => {
      const rect = el.getBoundingClientRect()
      setSheetSize({ width: rect.width, height: rect.height })
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [content?.surah.id])

  // Force-close guides when the developer gate turns off.
  useEffect(() => {
    if (
      !state.settings.developerMode ||
      !state.settings.educationGuidesEnabled
    ) {
      setBookmarkNoteTipOpen(false)
      setAyahRailTipOpen(false)
    }
  }, [state.settings.developerMode, state.settings.educationGuidesEnabled])

  // Offer the rail lesson only as a chapter settles. Enabling the developer
  // gate over an already-open reader waits for the next chapter opening —
  // do not key this effect on the guides toggle itself.
  useEffect(() => {
    if (
      !initialFocusSettled ||
      !Number.isFinite(ayahRailTipCenterY) ||
      chapterAdvancing ||
      verseReveal < 1 ||
      recitingActive
    ) {
      return
    }
    const timer = window.setTimeout(() => {
      const settings = appStore.getSnapshot().settings
      if (
        shouldShowAyahRailTip({
          developerMode: settings.developerMode,
          educationGuidesEnabled: settings.educationGuidesEnabled,
        }) &&
        !bookmarkNoteTipOpenRef.current
      ) {
        setAyahRailTipOpen(true)
      }
    }, 320)
    return () => window.clearTimeout(timer)
  }, [
    content?.surah.id,
    initialFocusSettled,
    ayahRailTipCenterY,
    chapterAdvancing,
    verseReveal,
    recitingActive,
  ])

  // Measure the live ribbon while the bookmark lesson is open.
  useEffect(() => {
    if (!bookmarkNoteTipOpen || bookmarkNoteTipAyah <= 0) return
    const sheet = sheetRef.current
    if (!sheet) return
    const measure = () => {
      const ribbon = sheet.querySelector(
        `#ayah-${bookmarkNoteTipAyah} .verse-ribbon`,
      )
      if (!(ribbon instanceof HTMLElement)) return
      const sheetRect = sheet.getBoundingClientRect()
      const ribbonRect = ribbon.getBoundingClientRect()
      setBookmarkNoteTipCenterY(
        ribbonRect.top + ribbonRect.height / 2 - sheetRect.top,
      )
    }
    measure()
    const raf = requestAnimationFrame(measure)
    return () => cancelAnimationFrame(raf)
  }, [bookmarkNoteTipOpen, bookmarkNoteTipAyah, focusedAyah])

  // Page drag dismisses open lessons (Android listDragged parity).
  useEffect(() => {
    const el = scrollRef.current
    if (!el || (!bookmarkNoteTipOpen && !ayahRailTipOpen)) return
    let lastTop = el.scrollTop
    const onScroll = () => {
      if (Math.abs(el.scrollTop - lastTop) < 2) return
      lastTop = el.scrollTop
      if (bookmarkNoteTipOpenRef.current) dismissBookmarkNoteTip()
      if (ayahRailTipOpenRef.current) dismissAyahRailTip()
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  }, [
    bookmarkNoteTipOpen,
    ayahRailTipOpen,
    dismissBookmarkNoteTip,
    dismissAyahRailTip,
  ])

  // Escape dismisses an open guide before other reader shortcuts.
  useEffect(() => {
    if (!isTop || (!bookmarkNoteTipOpen && !ayahRailTipOpen)) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopPropagation()
      if (bookmarkNoteTipOpenRef.current) dismissBookmarkNoteTip()
      else if (ayahRailTipOpenRef.current) dismissAyahRailTip()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [
    isTop,
    bookmarkNoteTipOpen,
    ayahRailTipOpen,
    dismissBookmarkNoteTip,
    dismissAyahRailTip,
  ])

  // Progressive mount expands — refresh focus geometry without re-homing.
  useEffect(() => {
    if (!content || !isTop || mountRange.complete) return
    focus.invalidateLayout()
  }, [mountRange.lo, mountRange.hi, content?.surah.id, isTop])

  // Home word-search hit: orange repeat wash (wash in → dissolve × 2) on the
  // matched word once the verse is on screen (Android SearchHitFlash).
  const pendingFlash = state.pendingSearchFlash
  const [flashTarget, setFlashTarget] = useState<{
    ayah: number
    wordPosition: number
  } | null>(null)
  useEffect(() => {
    if (!content || !isTop || !initialFocusSettled || pendingFlash == null) {
      setFlashTarget(null)
      return
    }
    const ayah = pendingFlash.ayah
    const word = pendingFlash.wordPosition
    const ayahRow = content.ayahs.find((a) => a.number === ayah)
    if (!ayahRow || !ayahRow.words.some((w) => w.position === word)) {
      appStore.clearSearchFlash()
      return
    }
    let cancelled = false
    let clearTimer = 0
    const startTimer = window.setTimeout(() => {
      if (cancelled) return
      setFlashTarget({ ayah, wordPosition: word })
      clearTimer = window.setTimeout(() => {
        if (cancelled) return
        setFlashTarget(null)
        appStore.clearSearchFlash()
      }, searchHitFlashTotalMs())
    }, SearchHitFlash.START_DELAY_MS)
    return () => {
      cancelled = true
      window.clearTimeout(startTimer)
      window.clearTimeout(clearTimer)
    }
  }, [
    content?.surah.id,
    isTop,
    initialFocusSettled,
    pendingFlash?.ayah,
    pendingFlash?.wordPosition,
  ])

  // Scroll readout + return-to-verse placement.
  // Follow pauses only on user vertical drag / wheel — never on FocusEngine
  // or keepWordInView scrollTop writes (Android pointerInput parity).
  useEffect(() => {
    const el = scrollRef.current
    if (!el || !content || !isTop) return
    const focusTarget = playbackFocusTarget(state.activeAyah, state.activeBasmalah)

    const update = () => {
      // Skip heavy DOM readout while Motion is writing scrollTop — per-frame
      // React updates were the main mobile lag source during verse glides.
      if (focus.isAnimating()) return

      if (pendingJumpAyah.current != null) {
        const pinned = pendingJumpAyah.current
        setFocusedAyah((prev) => (prev === pinned ? prev : pinned))
        if (focusedPositionRef.current !== pinned) {
          focusedPositionRef.current = pinned
          bumpRail()
        }
        setActiveExceedsViewport(focus.exceedsViewport(state.activeAyah))
        if (focusTarget != null && !state.followEnabled) {
          const placement = focus.placementOf(focusTarget)
          setShowReturn(isAway(placement))
          setReturnPointUp(pointUp(placement))
        } else {
          setShowReturn(false)
        }
        return
      }
      const nextPos = focus.readoutPosition()
      const next = Math.min(
        content.surah.ayahCount,
        Math.max(1, Math.floor(nextPos)),
      )
      // Continuous scroll readout: write the ref + nudge the rail canvas to
      // repaint — never React state, otherwise the whole reader reconciles
      // 60×/s during scroll (every mounted ayah's memo comparator runs).
      if (focusedPositionRef.current !== nextPos) {
        focusedPositionRef.current = nextPos
        bumpRail()
      }
      setFocusedAyah((prev) => (prev === next ? prev : next))
      setActiveExceedsViewport(focus.exceedsViewport(state.activeAyah))

      if (focusTarget != null && !state.followEnabled) {
        const placement = focus.placementOf(focusTarget)
        setShowReturn(isAway(placement))
        setReturnPointUp(pointUp(placement))
      } else {
        setShowReturn(false)
      }
    }

    // Coalesce scroll → one readout per animation frame (mobile scroll storms).
    let raf = 0
    const onScroll = () => {
      if (raf) return
      raf = requestAnimationFrame(() => {
        raf = 0
        update()
      })
    }

    const pauseFollowFromUser = () => {
      focus.cancel()
      if (state.followEnabled) appStore.setFollowEnabled(false)
      followWasEnabled.current = false
      update()
    }

    let pointerId: number | null = null
    let startX = 0
    let startY = 0
    let dragStarted = false

    const onPointerDown = (e: PointerEvent) => {
      if (e.pointerType === 'mouse' && e.button !== 0) return
      pointerId = e.pointerId
      startX = e.clientX
      startY = e.clientY
      dragStarted = false
    }
    const onPointerMove = (e: PointerEvent) => {
      if (pointerId !== e.pointerId || dragStarted) return
      const dx = e.clientX - startX
      const dy = e.clientY - startY
      if (shouldPauseFollowOnDrag(dx, dy)) {
        dragStarted = true
        pauseFollowFromUser()
      }
    }
    const onPointerUp = (e: PointerEvent) => {
      if (pointerId === e.pointerId) pointerId = null
    }
    const onWheel = () => {
      pauseFollowFromUser()
    }

    el.addEventListener('scroll', onScroll, { passive: true })
    el.addEventListener('pointerdown', onPointerDown)
    el.addEventListener('pointermove', onPointerMove)
    el.addEventListener('pointerup', onPointerUp)
    el.addEventListener('pointercancel', onPointerUp)
    el.addEventListener('wheel', onWheel, { passive: true })
    update()
    return () => {
      if (raf) cancelAnimationFrame(raf)
      el.removeEventListener('scroll', onScroll)
      el.removeEventListener('pointerdown', onPointerDown)
      el.removeEventListener('pointermove', onPointerMove)
      el.removeEventListener('pointerup', onPointerUp)
      el.removeEventListener('pointercancel', onPointerUp)
      el.removeEventListener('wheel', onWheel)
    }
  }, [content?.surah.id, state.activeAyah, state.activeBasmalah, state.followEnabled, isTop])

  // Lyric-style auto-scroll: keep the active target on its adaptive anchor
  // (verse, or chapter-top basmalah header while the lead-in plays).
  // Also re-homes when the media item advances — fade-lead may have already
  // bumped activeAyah, so nowPlaying.ayah is the boundary that must not miss.
  // Defer the glide one frame so play/pause paint (icon + CSS recess) lands
  // first and the Motion scroll does not contend for the same frame budget.
  useEffect(() => {
    if (!isTop || !content) return
    const target = playbackFocusTarget(state.activeAyah, state.activeBasmalah)
    if (target == null || !state.followEnabled) {
      if (!state.followEnabled) followWasEnabled.current = false
      return
    }
    const justEnabled = !followWasEnabled.current
    followWasEnabled.current = true
    let cancelled = false
    const raf = requestAnimationFrame(() => {
      if (cancelled) return
      void focusAyah(target, { animate: true, preRoll: justEnabled }).then(() => {
        if (cancelled) return
        setShowReturn(false)
        setFocusedAyah(focus.focusedAyah())
        setActiveExceedsViewport(focus.exceedsViewport(state.activeAyah))
      })
    })
    return () => {
      cancelled = true
      cancelAnimationFrame(raf)
    }
  }, [
    state.activeAyah,
    state.activeBasmalah,
    state.followEnabled,
    state.player.nowPlaying?.ayah,
    isTop,
    content?.surah.id,
  ])

  // A full-ayah repeat backtracks inside the same audio item, so neither the
  // active ayah nor media key changes. Treat word one as a fresh focus event.
  const wasAtFullAyahRepeatStart = useRef(false)
  useEffect(() => {
    const word = state.activeWord
    const atRepeatStart =
      word != null &&
      startsFullAyahRepeat(word.wordPosition, word.isRepeat, word.repeatStart)
    if (
      atRepeatStart &&
      !wasAtFullAyahRepeatStart.current &&
      state.followEnabled &&
      isTop
    ) {
      void focusAyah(word.ayah, { animate: true, preRoll: false })
    }
    wasAtFullAyahRepeatStart.current = atRepeatStart
  }, [state.activeWord, state.followEnabled, isTop, focusAyah])

  // While reciting, the rail tracks the active ayah (not the scroll readout,
  // which [update] skips during programmatic glides). Sync the ref whenever
  // the active ayah changes so the rail's next paint lands on the new verse.
  useEffect(() => {
    if (recitingActive && state.activeAyah != null) {
      focusedPositionRef.current = state.activeAyah
      bumpRail()
    }
  }, [state.activeAyah, recitingActive, bumpRail])

  // Display reflow (font / mode / gloss) — re-home the pinned verse.
  const layoutReady = useRef(false)
  useEffect(() => {
    if (!isTop || !content) return
    if (!layoutReady.current) {
      layoutReady.current = true
      return
    }
    focus.invalidateLayout()
    const focusTarget = playbackFocusTarget(state.activeAyah, state.activeBasmalah)
    const pin =
      state.followEnabled && focusTarget != null
        ? focusTarget
        : focusedAyah
    void focusAyah(pin, { animate: true, preRoll: false })
    // Intentionally keyed on layout-affecting settings only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    state.settings.readingMode,
    state.settings.showWordGloss,
    state.settings.showTransliteration,
    state.settings.showTranslation,
    state.settings.fontScale,
  ])

  useEffect(() => {
    layoutReady.current = false
  }, [content?.surah.id])

  /**
   * Selector / hand jump — Android `requestedJumpAyah` path.
   * Always uses FocusEngine `planJump` (preRoll) so near verses glide the
   * full path and far verses teleport to a doorstep then home-scroll.
   * The rail must not call this while dragging; only on commit.
   *
   * Also parks playback on the jumped ayah (seek while loaded) and updates the
   * session open anchor so Play starts here — not at the chapter opening left
   * by loadSurah. Continue Listening is unchanged until audio actually plays.
   * pendingJump stays set until focus *and* seek settle so an early Play still
   * routes through playLoadedFromAyah (web audio fetch can outlast the glide).
   */
  const jumpToAyah = (ayah: number) => {
    if (!content) return
    const count = content.surah.ayahCount
    const targetAyah = Math.min(count, Math.max(1, Math.round(ayah)))
    const thisSurahLoaded =
      state.player.nowPlaying?.surahId === content.surah.id
    appStore.setFollowEnabled(thisSurahLoaded)
    followWasEnabled.current = thisSurahLoaded
    appStore.onAyahBecameActive(targetAyah)
    pendingJumpAyah.current = targetAyah
    setFocusedAyah(targetAyah)
    focusedPositionRef.current = targetAyah
    bumpRail()
    const seekPromise = thisSurahLoaded
      ? appStore.seekToAyah(targetAyah)
      : Promise.resolve()
    void Promise.all([
      focusAyah(targetAyah, { animate: true, preRoll: true }),
      seekPromise,
    ]).finally(() => {
      // Keep the committed jump target — readout can briefly report the
      // previous ayah while the home-scroll settles on the anchor.
      setFocusedAyah(targetAyah)
      focusedPositionRef.current = targetAyah
      bumpRail()
      // Clear only if no newer jump superseded this one (Android finally guard).
      if (pendingJumpAyah.current === targetAyah) {
        pendingJumpAyah.current = null
      }
    })
  }

  const togglePlayback = useCallback(() => {
    if (!content || (state.player.isBuffering && !state.player.isPlaying)) return
    const thisSurahLoaded = state.player.nowPlaying?.surahId === content.surah.id
    const selected = selectedPlaybackAyah({
      ayahCount: content.surah.ayahCount,
      requestedJumpAyah: pendingJumpAyah.current,
      isThisSurahLoaded: thisSurahLoaded,
      followEnabled: state.followEnabled,
      activeAyah: state.activeAyah,
      scrolledAyah: focusedAyah,
    })
    if (!state.player.isPlaying) followWasEnabled.current = false
    void appStore.playPause({
      selectedAyah: selected,
      pendingJump: pendingJumpAyah.current != null,
      enableFollow: !state.player.isPlaying,
    })
  }, [content, focusedAyah, state.activeAyah, state.followEnabled, state.player])

  // The reading line is the keyboard cursor. Reuse rail jumps so keys get the
  // same progressive mount, focus glide, playback parking, and follow policy.
  useEffect(() => {
    if (!isTop || !content || state.rootViewer) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
      if (isKeyboardControl(event.target)) return
      const action = readerKeyboardAction(
        event.key,
        pendingJumpAyah.current ?? focusedAyah,
        content.surah.ayahCount,
      )
      if (!action) return
      // Holding a navigation key should glide continuously; commands such as
      // Space and B fire once per physical press.
      if (event.repeat && action.type !== 'jump') return
      event.preventDefault()
      if (action.type === 'jump') jumpToAyah(action.ayah)
      else if (action.type === 'playPause') {
        // Words are focusable so Enter can audition one. Space belongs to the
        // reader transport, even if the last Tab/click left a word focused.
        if (event.target instanceof HTMLElement) event.target.blur()
        event.stopPropagation()
        togglePlayback()
      }
      else if (action.type === 'search') setSearchActive(true)
      else onToggleBookmark(focusedAyah)
    }
    // Capture before React's word-level key handler can audition the word.
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [
    content,
    focusedAyah,
    isTop,
    state.rootViewer,
    togglePlayback,
    onToggleBookmark,
  ])

  const closeSearch = () => {
    setSearchActive(false)
    setSearchQuery('')
    setSearchIndex(0)
  }

  const stepSearch = (delta: number) => {
    if (searchMatches.length === 0) return
    setSearchIndex(
      (matchIndex + delta + searchMatches.length) % searchMatches.length,
    )
  }

  const readerItems = useMemo(
    () => (content ? buildReaderItems(content.ayahs) : []),
    [content],
  )
  const visibleItems = useMemo(() => {
    if (!content || mountRange.complete) return readerItems
    return sliceReaderItems(readerItems, mountRange.lo, mountRange.hi)
  }, [content, readerItems, mountRange.lo, mountRange.hi, mountRange.complete])
  const topPad =
    !mountRange.complete && mountRange.lo > 1
      ? (mountRange.lo - 1) * AYAH_SPACER_EST_PX
      : 0
  const bottomPad =
    content && !mountRange.complete && mountRange.hi < content.surah.ayahCount
      ? (content.surah.ayahCount - mountRange.hi) * AYAH_SPACER_EST_PX
      : 0

  if (!content) {
    return (
      <div
        className="sheet"
        data-name="reader"
        data-layer={READER_LAYER}
        data-depth={depth}
        data-active={active}
        data-empty={!isTop && !peeking ? 'true' : undefined}
      />
    )
  }

  // Rise is linear in --verse-reveal; fade uses a delayed smootherstep so
  // ink washes in clearly as the verses settle (Android parity).
  const verseFade = (() => {
    const t = Math.min(1, Math.max(0, verseReveal))
    const u = Math.min(1, Math.max(0, (t - 0.08) / 0.92))
    return u * u * (3 - 2 * u)
  })()
  const verseRiseStyle = {
    ['--verse-reveal' as string]: String(verseReveal),
    ['--verse-fade' as string]: String(verseFade),
  } as import('react').CSSProperties

  // Recess is one paper veil per inactive ayah (`.ayah-recess-veil` under
  // `.scroll[data-reciting]`) — not per-word opacity. Inactive ayahs keep
  // dimmed=false so memo(AyahBlock) skips them on play/pause. Karaoke ink
  // still flows through isActiveAyah + activeWord.
  const preface = prefaceState(state.activeBasmalah, false)
  const showBasmalah = surahOpensWithBasmalahPreface(content.surah.id)
  const ayahCount = content.surah.ayahCount
  const pageNumberScript = state.settings.pageNumberScript
  // Rail tracks the recitation only while playing; otherwise the reading line.
  const railAyah =
    recitingActive && state.activeAyah != null ? state.activeAyah : focusedAyah

  // Keep the rail off under-sheets — when Settings (or any sheet above) is
  // open, a peek of the reader must not show the dial hanging beside it.
  const rail = isTop ? (
    <AyahSelectorRail
      ref={railHandleRef}
      ayahCount={ayahCount}
      currentAyah={railAyah}
      currentPositionRef={focusedPositionRef}
      bookmarkedAyahs={bookmarkedAyahs}
      side={side}
      receded={receded}
      onJump={jumpToAyah}
      onExpandedChange={(expanded) => {
        if (expanded && ayahRailTipOpenRef.current) dismissAyahRailTip()
      }}
      onCollapsedCenterY={setAyahRailTipCenterY}
    />
  ) : null

  const bookmarkTipSide = side === 'left' ? 'right' : 'left'
  const ayahRailTipVisible =
    ayahRailTipOpen && Number.isFinite(ayahRailTipCenterY)
  const bookmarkNoteTipVisible =
    bookmarkNoteTipOpen &&
    bookmarkNoteTipAyah > 0 &&
    Number.isFinite(bookmarkNoteTipCenterY)

  const repeatMode = state.player.repeatMode
  const keepWordInView =
    state.followEnabled && recitingActive && activeExceedsViewport
  // Focus leads by 500 ms to begin the glide before an ayah ends. Karaoke ink
  // stays with the actual word/media owner until the audio item hands off.
  // Fade-lead still prepares the *next* verse (Upcoming + recess lift) so the
  // first word's wash is not buried under the veil at handoff.
  const inkAyah = recitingActive
    ? readerInkAyah(state.activeWord, state.player.nowPlaying?.ayah)
    : null
  const leadAyah = recitingActive ? state.activeAyah : null
  const reciterName =
    state.reciters.find((r) => r.id === state.settings.reciterId)?.name ?? 'Reciter'
  const matchLabel =
    searchMatches.length === 0
      ? activeQuery == null
        ? ''
        : '0/0'
      : `${matchIndex + 1}/${searchMatches.length}`

  return (
    <div
      ref={sheetRef}
      className="sheet"
      data-name="reader"
      data-layer={READER_LAYER}
      data-depth={depth}
      data-active={active}
      style={
        {
          ['--reader-paper' as string]: 'var(--paper)',
          ['--reader-ink' as string]: 'var(--ink)',
        } as import('react').CSSProperties
      }
    >
      {peeking ? (
        <button
          type="button"
          className="sheet-edge-back"
          aria-label="Back to reader"
          onClick={() => appStore.revealLayer(READER_LAYER)}
        />
      ) : null}

      <div className="reader-top" data-receded={receded} data-search={searchActive}>
        <button
          type="button"
          className="back"
          aria-label={searchActive ? 'Close search' : 'Back to chapters'}
          disabled={!searchActive && recitingActive}
          onClick={() => {
            if (searchActive) closeSearch()
            else appStore.revealLayer(COVER_LAYER)
          }}
        >
          {searchActive ? <IconClose /> : <span aria-hidden="true">←</span>}
        </button>

        {!searchActive && (pinnedTopNavTitle || content) ? (
          <div
            className="reader-top-title"
            data-visible={showTopTitle && !chapterAdvancing}
            data-advancing={chapterAdvancing && pinnedTopNavTitle != null ? true : undefined}
            aria-hidden={!showTopTitle && !pinnedTopNavTitle}
          >
            <OrnateSurahTitle
              chapterNumber={
                pinnedTopNavTitle?.id ?? content!.surah.id
              }
              nameArabic={
                pinnedTopNavTitle?.nameArabic ?? content!.surah.nameArabic
              }
              nameTransliteration={
                pinnedTopNavTitle?.nameTransliteration ??
                content!.surah.nameTransliteration
              }
            />
          </div>
        ) : null}

        {searchActive ? (
          <div className="reader-search">
            <PaperInput
              id="surah-search"
              name="surah-search"
              type="search"
              placeholder="Find an English word…"
              value={searchQuery}
              onValueChange={setSearchQuery}
              aria-label="Search in surah"
              autoFocus
              className="reader-search-input"
              onKeyDown={(e) => {
                if (e.key === 'Escape') {
                  e.preventDefault()
                  closeSearch()
                } else if (e.key === 'Enter') {
                  e.preventDefault()
                  stepSearch(e.shiftKey ? -1 : 1)
                }
              }}
            />
            <span className="reader-search-count" aria-live="polite">
              {matchLabel}
            </span>
            <button
              type="button"
              className="icon-btn"
              aria-label="Previous match"
              disabled={searchMatches.length === 0}
              onClick={() => stepSearch(-1)}
            >
              <IconChevronUp />
            </button>
            <button
              type="button"
              className="icon-btn"
              aria-label="Next match"
              disabled={searchMatches.length === 0}
              onClick={() => stepSearch(1)}
            >
              <IconChevronDown />
            </button>
          </div>
        ) : (
          <div className="reader-actions">
            <button
              type="button"
              className="icon-btn"
              aria-label="Search in surah"
              aria-keyshortcuts="/"
              disabled={recitingActive}
              onClick={() => setSearchActive(true)}
            >
              <IconSearch />
            </button>
            <button
              type="button"
              className="icon-btn"
              aria-label="Settings"
              disabled={recitingActive}
              onClick={() => appStore.setSheet('settings')}
            >
              <IconTune />
            </button>
          </div>
        )}
      </div>

      {/* Overlay on the sheet edge — never a flex sibling of the verse column. */}
      {rail}

      <div className="reader-body">
        <div className="reader-main">
          <div className="edge-fade">
            <div
              className="scroll"
              ref={scrollRef}
              data-reciting={recitingActive || undefined}
              data-chapter-advancing={chapterAdvancing || undefined}
              style={{
                ['--upcoming-alpha' as string]: String(inkTuning.upcomingAlpha),
                ['--upcoming-cover' as string]: String(1 - inkTuning.upcomingAlpha),
                ['--ink-fade-ms' as string]: `${inkTuning.inkFadeMs}ms`,
                ['--ayah-mark-fade-ms' as string]: `${inkTuning.ayahMarkFadeMs}ms`,
                ['--recess-ms' as string]: `${inkTuning.recessMs}ms`,
                ['--translit-alpha' as string]: String(TRANSLITERATION_COLOR_ALPHA),
              }}
            >
              <header className="surah-header" ref={headerRef}>
                <SurahHeaderOrnament
                  chapterNumber={content.surah.id}
                  ayahCount={content.surah.ayahCount}
                  themeMode={state.settings.themeMode}
                />
                <h2>{content.surah.nameTransliteration}</h2>
                <p className="ar-title">{content.surah.nameArabic}</p>
                <p className="sub">
                  {content.surah.nameTranslation} · {content.surah.ayahCount} ayahs ·{' '}
                  {content.surah.revelationPlace}
                </p>
              </header>
              {showBasmalah ? (
                <div
                  id="ayah-0"
                  className="basmalah-block reader-verse-rise"
                  data-ayah="0"
                  data-ayah-active={state.activeBasmalah || undefined}
                  style={verseRiseStyle}
                >
                  <BasmalahCalligraphy
                    className="basmalah"
                    data-state={preface}
                    active={state.activeBasmalah}
                    dimmed={false}
                    segments={() => appStore.basmalahSegments()}
                    onClick={() => void appStore.playAyah(1)}
                  />
                </div>
              ) : null}

              {topPad > 0 ? (
                <div
                  className="ayah-mount-pad"
                  style={{ height: topPad }}
                  aria-hidden
                />
              ) : null}

              {visibleItems.map((item) => {
                if (item.kind === 'pageBreak') {
                  return (
                    <PageBreak
                      key={`page-${item.page}`}
                      page={item.page}
                      script={pageNumberScript}
                    />
                  )
                }
                const ayah = item.ayah
                const isFocusTarget = state.activeAyah === ayah.number
                // Karaoke word ownership is ink-only. Active-ayah *policy*
                // (Upcoming prep + recess veil) also includes fade-lead so the
                // next verse is faint before handoff — otherwise the first
                // word's wash runs under the lifting paper veil.
                // dimmed stays false: global recess is CSS on
                // `.scroll[data-reciting]`, so inactive ayahs do not reconcile
                // on play/pause.
                const inkOwner = inkAyah === ayah.number
                const policyActive = readerAyahInkPolicyActive(
                  ayah.number,
                  inkAyah,
                  leadAyah,
                )
                const aw =
                  inkOwner && state.activeWord?.ayah === ayah.number
                    ? state.activeWord
                    : null
                return (
                  <div
                    key={ayah.number}
                    className="reader-verse-rise"
                    style={verseRiseStyle}
                  >
                    <AyahBlock
                      ayah={ayah}
                      activeWord={aw}
                      isActiveAyah={policyActive}
                      dimmed={false}
                      focused={focusedAyah === ayah.number}
                      keepActiveWordInView={keepWordInView && isFocusTarget}
                      onKeepWordInView={onKeepWordInView}
                      readingMode={state.settings.readingMode}
                      verseNumberScript={state.settings.verseNumberScript}
                      showWordGloss={state.settings.showWordGloss}
                      showTransliteration={state.settings.showTransliteration}
                      showTranslation={state.settings.showTranslation}
                      bookmarked={
                        bookmarkedAyahs.has(ayah.number) ||
                        bookmarkNoteTipAyah === ayah.number
                      }
                      bookmarkSide={bookmarkTipSide}
                      bookmarkChromeAlpha={1}
                      bookmarkInteractive={
                        !recitingActive && bookmarkNoteTipAyah !== ayah.number
                      }
                      speed={state.settings.playbackSpeed}
                      fontScale={state.settings.fontScale}
                      onPlayWord={onPlayWord}
                      onToggleBookmark={onToggleBookmark}
                      onHoldWord={onHoldWord}
                      searchQuery={
                        deferredQuery != null && matchAyahSet.has(ayah.number)
                          ? deferredQuery
                          : null
                      }
                      flashWordPosition={
                        flashTarget?.ayah === ayah.number
                          ? flashTarget.wordPosition
                          : null
                      }
                    />
                  </div>
                )
              })}

              {bottomPad > 0 ? (
                <div
                  className="ayah-mount-pad"
                  style={{ height: bottomPad }}
                  aria-hidden
                />
              ) : null}

              {nextSurah != null && mountRange.hi >= content.surah.ayahCount ? (
                <NextChapterFooter
                  surah={nextSurah}
                  themeMode={state.settings.themeMode}
                  enabled={!chapterAdvancing}
                  headerMorph={headerMorph}
                  onOpen={() => {
                    void advanceToNextChapter(nextSurah.id)
                  }}
                />
              ) : null}
            </div>
          </div>

          {showReturn &&
          recitingActive &&
          playbackFocusTarget(state.activeAyah, state.activeBasmalah) != null ? (
            <ReturnToAyahButton
              pointUp={returnPointUp}
              onClick={() => {
                followWasEnabled.current = false
                appStore.setFollowEnabled(true)
                setShowReturn(false)
              }}
            />
          ) : null}

          <div className="player-bar">
            <button
              type="button"
              className="reciter-btn"
              data-receded={receded}
              aria-label={`Reciter ${reciterName}. Open settings`}
              onClick={() => appStore.setSheet('settings')}
            >
              {reciterName}
            </button>
            <div className="player-transport">
              <button
                type="button"
                className="ctrl"
                data-receded={receded}
                aria-label={repeatMode === 'off' ? 'Repeat off' : `Repeat ${repeatMode}`}
                onClick={() =>
                  appStore.setRepeat(repeatMode === 'ayah' ? 'off' : 'ayah')
                }
              >
                {repeatMode === 'ayah' ? <IconRepeatOne /> : <IconRepeat />}
              </button>
              <button
                type="button"
                className="ctrl"
                aria-label="Fast backward"
                onClick={() => void appStore.fastBackward()}
              >
                <IconFastRewind />
              </button>
              <button
                type="button"
                className="play"
                aria-label={
                  state.player.isBuffering && !state.player.isPlaying
                    ? 'Buffering'
                    : state.player.isPlaying
                      ? 'Pause'
                      : 'Play'
                }
                aria-keyshortcuts="Space"
                aria-busy={
                  (state.player.isBuffering && !state.player.isPlaying) || undefined
                }
                onClick={togglePlayback}
              >
                {state.player.isBuffering && !state.player.isPlaying ? (
                  <IconBuffering />
                ) : state.player.isPlaying ? (
                  <IconPause />
                ) : (
                  <IconPlay />
                )}
              </button>
              <button
                type="button"
                className="ctrl"
                aria-label="Fast forward"
                onClick={() => void appStore.fastForward()}
              >
                <IconFastForward />
              </button>
              <button
                type="button"
                className="ctrl speed"
                data-receded={receded}
                aria-label={`Speed ${state.settings.playbackSpeed}×`}
                onClick={() => {
                  const speeds = [0.75, 1, 1.25, 1.5]
                  const i = speeds.indexOf(state.settings.playbackSpeed)
                  const next = speeds[(i + 1) % speeds.length]!
                  appStore.updateSettings({ playbackSpeed: next })
                }}
              >
                {state.settings.playbackSpeed}×
              </button>
            </div>
          </div>
        </div>
      </div>

      {state.player.error ? <p className="muted-error">{state.player.error}</p> : null}

      {ayahRailTipVisible || ayahRailTipRendered ? (
        <AyahRailTip
          visible={ayahRailTipVisible}
          railSide={side}
          targetCenterY={
            Number.isFinite(ayahRailTipCenterY)
              ? ayahRailTipCenterY
              : sheetSize.height / 2
          }
          surfaceWidth={sheetSize.width}
          surfaceHeight={sheetSize.height}
          onDismiss={dismissAyahRailTip}
          onRenderedChange={setAyahRailTipRendered}
        />
      ) : null}

      {bookmarkNoteTipVisible || bookmarkNoteTipRendered ? (
        <BookmarkNoteTip
          visible={bookmarkNoteTipVisible}
          ribbonSide={bookmarkTipSide}
          targetCenterY={
            Number.isFinite(bookmarkNoteTipCenterY)
              ? bookmarkNoteTipCenterY
              : sheetSize.height / 2
          }
          surfaceWidth={sheetSize.width}
          surfaceHeight={sheetSize.height}
          onDismiss={dismissBookmarkNoteTip}
          onRenderedChange={(rendered) => {
            setBookmarkNoteTipRendered(rendered)
            if (!rendered && !bookmarkNoteTipOpen) {
              setBookmarkNoteTipAyah(0)
              setBookmarkNoteTipCenterY(Number.NaN)
            }
          }}
        />
      ) : null}

      {/* Ink bleed lives on this sheet — not a full-viewport layer over the deck. */}
      <RootViewer />
    </div>
  )
}
