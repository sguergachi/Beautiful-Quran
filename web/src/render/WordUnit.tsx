import { useRepeatWash } from './useRepeatWash'
import { useWordWash, type WordMotion } from './useWordWash'
import {
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type MouseEvent,
  type MutableRefObject,
} from 'react'
import type { Word } from '../data/models'
import {
  InkState,
  getTuning,
  glinting as inkGlinting,
  type InkWord,
} from '../ui/reader/InkEngine'
import {
  secondaryAlpha,
  TRANSLITERATION_COLOR_ALPHA,
} from '../ui/reader/WordHighlight'
import {
  applyMask,
  glintEnabled,
  runGlintFadeOut,
  runGlintWashIn,
  runSearchHitWash,
} from './inkWash'
import { SearchHitFlash } from '../ui/reader/SearchHitFlash'
import { useWordInteraction } from './useWordInteraction'

interface Props {
  word: Word
  /** Display-only English token; interaction and data identity stay on [word]. */
  englishText?: string
  ink: InkWord
  sweepMs: number | null
  motion?: WordMotion
  /** Seek-generation so replaying this Active word restarts the wash. */
  activation?: number
  showGloss: boolean
  showTransliteration: boolean
  englishMode?: boolean
  searchHit?: boolean
  /** When true, breathe the orange search-hit flash on Arabic + gloss. */
  searchFlash?: boolean
  /** Temporarily recess this non-target word while a search flash is active. */
  searchRecessed?: boolean
  /** Optional external ref so the ayah can keep the active word in view. */
  rootRef?: MutableRefObject<HTMLElement | null>
  onPlay: () => void
  onHold: () => void
  onContextMenu?: (e: MouseEvent) => void
  tabIndex?: number
}

/**
 * Paint secondary gloss/translit alpha.
 *
 * Arabic glyphs no longer dim via parent opacity (that made overlapping Hafs
 * marks look dirty). Gloss/translit own their Upcoming floor here. During
 * Active they track the sweep via `secondaryAlpha` (Android
 * WordHighlight.secondaryAlpha) — never popping to full the instant Active starts.
 *
 * English lyric mode still uses parent `.word-ink` opacity for Upcoming, so
 * children must not compound that dim when `englishMode` is true.
 */
function paintSecondary(
  gloss: HTMLElement | null,
  translit: HTMLElement | null,
  ink: { state: InkState; repeat: boolean },
  sweepProgress: number | null,
  englishMode: boolean,
) {
  if (ink.state === InkState.Active && !ink.repeat && sweepProgress != null) {
    const alpha = secondaryAlpha(ink.state, ink.repeat, sweepProgress)
    if (gloss) gloss.style.opacity = String(alpha)
    if (translit)
      translit.style.opacity = String(TRANSLITERATION_COLOR_ALPHA * alpha)
    return
  }

  if (englishMode) {
    // Inherit parent lyric ink (Upcoming dim / Recited full). Translit keeps its
    // 0.55 color strength; gloss stays at 1 relative to the parent.
    if (gloss) gloss.style.opacity = ''
    if (translit) translit.style.opacity = String(TRANSLITERATION_COLOR_ALPHA)
    return
  }

  // Arabic path: parent is full opacity; secondary lines carry Upcoming themselves.
  // Plain/Recited clear inline opacity so the ayah recess veil can sit on top.
  if (ink.state === InkState.Upcoming) {
    const floor = getTuning().upcomingAlpha
    if (gloss) gloss.style.opacity = String(floor)
    if (translit)
      translit.style.opacity = String(TRANSLITERATION_COLOR_ALPHA * floor)
    return
  }
  if (gloss) gloss.style.removeProperty('opacity')
  if (translit) translit.style.opacity = String(TRANSLITERATION_COLOR_ALPHA)
}

export function WordUnit({
  word,
  englishText,
  ink,
  sweepMs: activeSweepMs,
  motion,
  activation = 0,
  showGloss,
  showTransliteration,
  englishMode = false,
  searchHit = false,
  searchFlash = false,
  searchRecessed = false,
  rootRef: externalRootRef,
  onPlay,
  onHold,
  onContextMenu,
  tabIndex = -1,
}: Props) {
  const localRootRef = useRef<HTMLSpanElement>(null)
  /** Base glyph layer — Active wash targets this (English) or the paper cover (Arabic). */
  const baseRef = useRef<HTMLSpanElement>(null)
  const coverRef = useRef<HTMLSpanElement>(null)
  const overlayRef = useRef<HTMLSpanElement>(null)
  const glintRef = useRef<HTMLSpanElement>(null)
  const glintHaloRef = useRef<HTMLSpanElement>(null)
  const flashRef = useRef<HTMLSpanElement>(null)
  const glossFlashRef = useRef<HTMLSpanElement>(null)
  const glossRef = useRef<HTMLSpanElement>(null)
  const translitRef = useRef<HTMLSpanElement>(null)
  const prevGlint = useRef(false)
  const prevGlintRepeat = useRef(ink.repeat)
  const glintReplacedByRepeat = useRef(false)
  /** Mount orange/glint/search overlays only while needed — not for every idle word. */
  const [repeatMounted, setRepeatMounted] = useState(ink.repeat)
  const [glintMounted, setGlintMounted] = useState(false)
  const [flashMounted, setFlashMounted] = useState(searchFlash)
  const tuning = getTuning()
  const baseClass = englishMode ? 'word-gloss' : 'word-arabic'
  const upcomingCover = 1 - tuning.upcomingAlpha
  const interaction = useWordInteraction(onPlay, onHold)

  if (ink.repeat && !repeatMounted) setRepeatMounted(true)
  if (
    ink.repeat &&
    ink.state === InkState.Active &&
    glintEnabled() &&
    !glintMounted
  )
    setGlintMounted(true)
  if (searchFlash && !flashMounted) setFlashMounted(true)

  const repeatProgress = useRepeatWash(
    word,
    ink,
    activation,
    englishMode,
    motion,
    repeatMounted,
    overlayRef,
    () => setRepeatMounted(false),
  )

  const { washing: washingRef, latched: motionRef } = useWordWash({
    word,
    ink,
    activation,
    durationMs: activeSweepMs,
    motion,
    english: englishMode,
    target: englishMode ? baseRef : coverRef,
    secondary: (progress) =>
      paintSecondary(
        glossRef.current,
        translitRef.current,
        ink,
        progress,
        englishMode,
      ),
    onActive: () => {
      if (glintEnabled()) setGlintMounted(true)
    },
  })

  // Fresh-ink glint (Nightfall/Royal Green, InkEngine.glinting): halo rises with
  // the base wash; English also gets a masked tint twin. Arabic keeps one
  // authoritative filled glyph so Hafs overhang cannot rasterize differently.
  useLayoutEffect(() => {
    if (!glintMounted) {
      prevGlint.current = false
      return
    }
    const overlay = englishMode ? glintRef.current : null
    const halo = glintHaloRef.current
    if (!halo || (englishMode && !overlay)) return
    const glinting = inkGlinting(ink.state)
    const was = prevGlint.current
    const wasRepeat = prevGlintRepeat.current
    prevGlint.current = glinting
    prevGlintRepeat.current = ink.repeat

    if (glinting && !was) {
      glintReplacedByRepeat.current = false
      const duration = activeSweepMs ?? getTuning().repeatSweepMs
      return runGlintWashIn(overlay, halo, !englishMode, duration, {
        ...motionRef.current,
        repeat: ink.repeat,
        washProgress: ink.repeat
          ? () => repeatProgress.current
          : motionRef.current.washProgress,
      })
    }
    // A same-word repeat replaces the already-formed gold glint with orange;
    // fade it on the repeat wash clock instead of resetting it to zero.
    if (glinting && was && ink.repeat && !wasRepeat) {
      glintReplacedByRepeat.current = true
      const duration = activeSweepMs ?? getTuning().repeatSweepMs
      return runGlintFadeOut(overlay, halo, undefined, duration)
    }
    if (glinting && was && ink.state === InkState.Active) {
      const duration = activeSweepMs ?? getTuning().repeatSweepMs
      return runGlintWashIn(overlay, halo, !englishMode, duration, {
        ...motionRef.current,
        repeat: ink.repeat,
        washProgress: ink.repeat
          ? () => repeatProgress.current
          : motionRef.current.washProgress,
      })
    }
    if (!glinting && was) {
      if (glintReplacedByRepeat.current) {
        setGlintMounted(false)
        return
      }
      return runGlintFadeOut(overlay, halo, () => setGlintMounted(false))
    }
    if (!glinting) {
      if (overlay) overlay.style.opacity = '0'
      halo.style.opacity = '0'
      if (overlay) applyMask(overlay, 'none')
      setGlintMounted(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ink.state, ink.repeat, glintMounted, englishMode, activation])

  // Keep secondary alpha in sync when gloss/translit mount or ink leaves Active
  // without a wash restart (e.g. settings toggle mid-verse).
  useLayoutEffect(() => {
    if (ink.state === InkState.Active && !ink.repeat) {
      // Wash rAF owns secondary while sweeping; only seed resting/full here.
      return
    }
    if (washingRef.current) return
    paintSecondary(
      glossRef.current,
      translitRef.current,
      ink,
      ink.state === InkState.Active && !ink.repeat ? 1 : null,
      englishMode,
    )
  }, [
    ink.state,
    ink.repeat,
    showGloss,
    showTransliteration,
    word.translation,
    word.transliteration,
    englishMode,
  ])

  // Search-hit breath: mount the twin overlay only while flashing.
  useLayoutEffect(() => {
    if (!flashMounted) return
    if (!searchFlash) {
      setFlashMounted(false)
      return
    }
    const cancels: Array<() => void> = []
    let pending = 0
    const doneOne = () => {
      pending--
      if (pending <= 0) setFlashMounted(false)
    }
    if (flashRef.current) {
      pending++
      cancels.push(runSearchHitWash(flashRef.current, SearchHitFlash, doneOne))
    }
    if (glossFlashRef.current) {
      pending++
      cancels.push(
        runSearchHitWash(glossFlashRef.current, SearchHitFlash, doneOne),
      )
    }
    if (pending === 0) setFlashMounted(false)
    return () => cancels.forEach((c) => c())
  }, [searchFlash, englishMode, flashMounted])

  const rtl = !englishMode
  const style: CSSProperties = englishMode
    ? { ['--upcoming-alpha' as string]: String(tuning.upcomingAlpha) }
    : { ['--upcoming-cover' as string]: String(upcomingCover) }

  const label = englishMode
    ? englishText || word.translation || word.arabic
    : word.arabic

  return (
    <span
      ref={(node) => {
        localRootRef.current = node
        if (externalRootRef) externalRootRef.current = node
      }}
      className={
        englishMode ? 'word-unit word-ink' : 'word-unit word-arabic-ink'
      }
      data-state={ink.state}
      data-search-recessed={searchRecessed || undefined}
      style={style}
      {...interaction}
      onContextMenu={onContextMenu}
      role="button"
      tabIndex={tabIndex}
      data-reader-word={rtl ? 'rtl' : 'ltr'}
    >
      <span
        className="word-stack"
        dir={rtl ? 'rtl' : 'ltr'}
        lang={rtl ? 'ar' : undefined}
        translate={rtl ? 'no' : undefined}
      >
        {/* Base + orange overlays share one tight slot so abspos ink sits on
            the same border box as the glyphs (top:0 on the stack was half-
            leading above the inline baseline). */}
        <span className="word-ink-slot">
          <span
            ref={baseRef}
            className={baseClass}
            data-search-hit={englishMode && searchHit ? 'true' : undefined}
          >
            {label}
          </span>
          {glintMounted ? (
            <span
              ref={glintHaloRef}
              className={`word-glint-halo${englishMode ? '' : ' arabic-glint-surface'}`}
              aria-hidden="true"
              style={{ opacity: 0 }}
            >
              <span className={baseClass}>{label}</span>
            </span>
          ) : null}
          {repeatMounted ? (
            <span
              ref={overlayRef}
              className={`word-repeat-overlay ${baseClass}`}
              aria-hidden="true"
              style={{ opacity: 0 }}
            >
              {label}
            </span>
          ) : null}
          {glintMounted && englishMode ? (
            <span
              ref={glintRef}
              className="word-glint-overlay"
              aria-hidden="true"
              style={{ opacity: 0 }}
            >
              <span className={baseClass}>{label}</span>
            </span>
          ) : null}
          {flashMounted ? (
            <span
              ref={flashRef}
              className={`word-repeat-overlay search-hit-breath ${baseClass}`}
              aria-hidden="true"
              style={{ opacity: 0 }}
            >
              {label}
            </span>
          ) : null}
        </span>
        {/* Arabic only: opaque full-ink glyphs + paper cover (never glyph alpha). */}
        {!englishMode ? (
          <span ref={coverRef} className="ink-paper-cover" aria-hidden="true" />
        ) : null}
      </span>
      {/* Gloss/translit are siblings of the glyph stack (not nested under the
          wash mask). Arabic path: they own Upcoming dim. English path: parent
          `.word-ink` owns it. During Active both track secondaryAlpha. */}
      {!englishMode && showGloss && word.translation ? (
        <span className="word-gloss-flash">
          <span
            ref={glossRef}
            className="word-gloss"
            data-search-hit={searchHit ? 'true' : undefined}
          >
            {word.translation}
          </span>
          {flashMounted ? (
            <span
              ref={glossFlashRef}
              className="word-repeat-overlay search-hit-breath word-gloss"
              aria-hidden="true"
              style={{ opacity: 0 }}
            >
              {word.translation}
            </span>
          ) : null}
        </span>
      ) : null}
      {showTransliteration && word.transliteration ? (
        <span ref={translitRef} className="word-translit">
          {word.transliteration}
        </span>
      ) : null}
    </span>
  )
}
