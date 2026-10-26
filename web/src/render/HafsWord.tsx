import { useRepeatWash } from './useRepeatWash'
import { useWordWash, type WordMotion } from './useWordWash'
/**
 * Arabic-only word: full-ink glyph + paper-cover bloom.
 *
 * Mirrors Android `ResponsiveHafsAyah` / `shapedWordBloom`: glyphs stay opaque
 * full ink; Upcoming/recess use a paper cover; Active peels that cover with
 * the smootherstep directional mask (soft faded edge — required fidelity).
 */
import {
  useLayoutEffect,
  useRef,
  useState,
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
  glintEnabled,
  runGlintFadeOut,
  runGlintWashIn,
  runSearchHitWash,
} from './inkWash'
import { SearchHitFlash } from '../ui/reader/SearchHitFlash'
import { useWordInteraction } from './useWordInteraction'

interface Props {
  word: Word
  ink: InkWord
  sweepMs: number | null
  motion?: WordMotion
  /** Seek-generation so replaying this Active word restarts the wash. */
  activation?: number
  /** When true, breathe the orange search-hit flash on this Arabic word. */
  searchFlash?: boolean
  /** Temporarily recess this non-target word while a search flash is active. */
  searchRecessed?: boolean
  rootRef?: MutableRefObject<HTMLElement | null>
  onPlay: () => void
  onHold: () => void
  onContextMenu?: (e: MouseEvent) => void
  tabIndex?: number
}

export function HafsWord({
  word,
  ink,
  sweepMs: activeSweepMs,
  motion,
  activation = 0,
  searchFlash = false,
  searchRecessed = false,
  rootRef: externalRootRef,
  onPlay,
  onHold,
  onContextMenu,
  tabIndex = -1,
}: Props) {
  const localRootRef = useRef<HTMLSpanElement>(null)
  const coverRef = useRef<HTMLSpanElement>(null)
  const overlayRef = useRef<HTMLSpanElement>(null)
  const glintHaloRef = useRef<HTMLSpanElement>(null)
  const flashRef = useRef<HTMLSpanElement>(null)
  const prevGlint = useRef(false)
  const prevGlintRepeat = useRef(ink.repeat)
  const glintReplacedByRepeat = useRef(false)
  /** Orange/glint/search overlays only while needed — not for every idle word. */
  const [repeatMounted, setRepeatMounted] = useState(ink.repeat)
  const [glintMounted, setGlintMounted] = useState(false)
  const [flashMounted, setFlashMounted] = useState(searchFlash)
  const tuning = getTuning()
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
    false,
    motion,
    repeatMounted,
    overlayRef,
    () => setRepeatMounted(false),
  )

  const { latched: motionRef } = useWordWash({
    word,
    ink,
    activation,
    durationMs: activeSweepMs,
    motion,
    english: false,
    target: coverRef,
    onActive: () => {
      if (glintEnabled()) setGlintMounted(true)
    },
  })

  // Fresh-ink glint (Nightfall/Royal Green, InkEngine.glinting): transparent-fill
  // halo rises with the paper-cover wash. Never add a second filled Hafs glyph:
  // Chromium can rasterize its overhanging terminal differently from the base.
  useLayoutEffect(() => {
    if (!glintMounted) {
      prevGlint.current = false
      return
    }
    const halo = glintHaloRef.current
    if (!halo) return
    const glinting = inkGlinting(ink.state)
    const was = prevGlint.current
    const wasRepeat = prevGlintRepeat.current
    prevGlint.current = glinting
    prevGlintRepeat.current = ink.repeat

    if (glinting && !was) {
      glintReplacedByRepeat.current = false
      const duration = activeSweepMs ?? getTuning().repeatSweepMs
      return runGlintWashIn(null, halo, true, duration, {
        ...motionRef.current,
        repeat: ink.repeat,
        washProgress: ink.repeat
          ? () => repeatProgress.current
          : motionRef.current.washProgress,
      })
    }
    // Preserve the formed halo at a same-word repeat boundary, then let the
    // incoming orange wash replace it on the same clock.
    if (glinting && was && ink.repeat && !wasRepeat) {
      glintReplacedByRepeat.current = true
      const duration = activeSweepMs ?? getTuning().repeatSweepMs
      return runGlintFadeOut(null, halo, undefined, duration)
    }
    // Seek-reactivation while still glinting: re-run the wash-in.
    if (glinting && was && ink.state === InkState.Active) {
      const duration = activeSweepMs ?? getTuning().repeatSweepMs
      return runGlintWashIn(null, halo, true, duration, {
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
      return runGlintFadeOut(null, halo, () => setGlintMounted(false))
    }
    if (!glinting) {
      halo.style.opacity = '0'
      setGlintMounted(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ink.state, ink.repeat, glintMounted, activation])

  // Search-hit breath: mount overlay only while flashing.
  useLayoutEffect(() => {
    if (!flashMounted) return
    if (!searchFlash) {
      setFlashMounted(false)
      return
    }
    if (!flashRef.current) return
    return runSearchHitWash(flashRef.current, SearchHitFlash, () =>
      setFlashMounted(false),
    )
  }, [searchFlash, flashMounted])

  return (
    <span
      ref={(node) => {
        localRootRef.current = node
        if (externalRootRef) externalRootRef.current = node
      }}
      className="hafs-word"
      data-state={ink.state}
      data-search-recessed={searchRecessed || undefined}
      style={{ ['--upcoming-cover' as string]: String(upcomingCover) }}
      {...interaction}
      onContextMenu={onContextMenu}
      role="button"
      tabIndex={tabIndex}
      data-reader-word="rtl"
      lang="ar"
      translate="no"
    >
      <span className="hafs-shell">
        <span className="word-ink-slot">
          <span className="hafs-glyph">{word.arabic}</span>
          {glintMounted ? (
            <span
              ref={glintHaloRef}
              className="word-glint-halo arabic-glint-surface"
              aria-hidden="true"
              style={{ opacity: 0 }}
            >
              <span className="hafs-glyph">{word.arabic}</span>
            </span>
          ) : null}
          {repeatMounted ? (
            <span
              ref={overlayRef}
              className="hafs-repeat-overlay hafs-glyph"
              aria-hidden="true"
              style={{ opacity: 0 }}
            >
              {word.arabic}
            </span>
          ) : null}
          {flashMounted ? (
            <span
              ref={flashRef}
              className="hafs-repeat-overlay search-hit-breath hafs-glyph"
              aria-hidden="true"
              style={{ opacity: 0 }}
            >
              {word.arabic}
            </span>
          ) : null}
        </span>
        <span ref={coverRef} className="ink-paper-cover" aria-hidden="true" />
      </span>{' '}
    </span>
  )
}
