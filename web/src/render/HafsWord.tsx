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
import { InkState, getTuning, startRevealed, type InkWord } from '../ui/reader/InkEngine'
import {
  applyMask,
  clearPaperCover,
  glintEnabled,
  runGlintFadeOut,
  runGlintWashIn,
  runPaperCoverWash,
  runRepeatFadeOut,
  runRepeatWashIn,
  runSearchHitDoubleWash,
} from './inkWash'
import { SearchHitFlash } from '../ui/reader/SearchHitFlash'
import { useWordInteraction } from './useWordInteraction'

interface Props {
  word: Word
  ink: InkWord
  sweepMs: number | null
  /** Seek-generation so replaying this Active word restarts the wash. */
  activation?: number
  /** When true, pulse the orange search-hit flash on this Arabic word. */
  searchFlash?: boolean
  rootRef?: MutableRefObject<HTMLElement | null>
  onPlay: () => void
  onHold: () => void
  onContextMenu?: (e: MouseEvent) => void
}

export function HafsWord({
  word,
  ink,
  sweepMs: activeSweepMs,
  activation = 0,
  searchFlash = false,
  rootRef: externalRootRef,
  onPlay,
  onHold,
  onContextMenu,
}: Props) {
  const localRootRef = useRef<HTMLSpanElement>(null)
  const coverRef = useRef<HTMLSpanElement>(null)
  const overlayRef = useRef<HTMLSpanElement>(null)
  const glintHaloRef = useRef<HTMLSpanElement>(null)
  const flashRef = useRef<HTMLSpanElement>(null)
  // null until first layout effect so mount-as-Active still runs the wash.
  const prevState = useRef<InkState | null>(null)
  const prevActivation = useRef(activation)
  const revealedOnEntry = useRef(false)
  const prevRepeat = useRef(false)
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
  if (ink.repeat && glintEnabled() && !glintMounted) setGlintMounted(true)
  if (searchFlash && !flashMounted) setFlashMounted(true)

  /**
   * In-flight first-pass wash. Residual short-hold washes finish after
   * Active→Recited (Android rememberLetterSweep); cancel only on new Active
   * entry, recess, repeat, or unmount.
   */
  const washStopRef = useRef<(() => void) | null>(null)
  const washingRef = useRef(false)
  const stopWash = () => {
    washStopRef.current?.()
    washStopRef.current = null
    washingRef.current = false
  }

  /*
   * Paper-cover bloom on Active entry (Android shapedWordBloom / InkReveal).
   * useLayoutEffect so progress-0 lands before paint. The wash is a
   * smootherstep directional mask — soft faded edge is required fidelity.
   * Leaving Active mid-wash does not snap-clear: residual finishes after handoff.
   */
  useLayoutEffect(() => {
    const cover = coverRef.current
    if (!cover) return
    const prev = prevState.current
    const enteredActive = ink.state === InkState.Active && prev !== InkState.Active
    const reactivated =
      ink.state === InkState.Active &&
      prev === InkState.Active &&
      activation !== prevActivation.current
    if (enteredActive || reactivated) {
      // Always re-run the wash on Active entry / seek-reactivation.
      revealedOnEntry.current = startRevealed(prev ?? InkState.Plain, ink.state)
    } else if (ink.state !== InkState.Active) {
      revealedOnEntry.current = false
    }
    prevState.current = ink.state
    prevActivation.current = activation

    const t = getTuning()
    const resting = t.upcomingAlpha
    const ease = {
      x1: t.sweepEaseX1,
      y1: t.sweepEaseY1,
      x2: t.sweepEaseX2,
      y2: t.sweepEaseY2,
    }

    if (ink.state === InkState.Upcoming) {
      stopWash()
      clearPaperCover(cover)
      cover.style.transition = `opacity ${t.recessMs}ms cubic-bezier(0.4, 0, 0.2, 1)`
      cover.style.opacity = String(1 - resting)
      return
    }

    if (ink.repeat) {
      stopWash()
      clearPaperCover(cover)
      return
    }

    if (ink.state !== InkState.Active) {
      // Recited / Plain: let residual short-hold wash finish; idle only clear.
      if (!washingRef.current) clearPaperCover(cover)
      return
    }

    if (revealedOnEntry.current) {
      stopWash()
      clearPaperCover(cover)
      return
    }

    if (!enteredActive && !reactivated) return

    // The same branch that starts the first-pass wash mounts the glint twin
    // (Nightfall): the white-gold sheen rides this wash, then dries after it.
    if (glintEnabled()) setGlintMounted(true)

    stopWash()
    const duration = activeSweepMs ?? t.repeatSweepMs
    washingRef.current = true
    washStopRef.current = runPaperCoverWash(cover, true, duration, ease, resting, undefined, () => {
      washingRef.current = false
      washStopRef.current = null
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ink.state, ink.repeat, activation])

  // Unmount only — residual washes must survive Active→Recited handoff.
  // Reset prevState so React StrictMode's remount restarts an Active wash
  // (cleanup cancels the first pass; without reset, remount sees prev=Active
  // and skips enteredActive).
  useLayoutEffect(
    () => () => {
      stopWash()
      prevState.current = null
    },
    [],
  )

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
    const glinting =
      ink.state === InkState.Active && (ink.repeat || !revealedOnEntry.current)
    const was = prevGlint.current
    const wasRepeat = prevGlintRepeat.current
    prevGlint.current = glinting
    prevGlintRepeat.current = ink.repeat

    if (glinting && !was) {
      glintReplacedByRepeat.current = false
      const duration = activeSweepMs ?? getTuning().repeatSweepMs
      return runGlintWashIn(null, halo, true, duration)
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
      return runGlintWashIn(null, halo, true, duration)
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

  useLayoutEffect(() => {
    if (!repeatMounted) {
      prevRepeat.current = ink.repeat
      return
    }
    const overlay = overlayRef.current
    if (!overlay) return
    const was = prevRepeat.current
    const enteredRepeat = ink.repeat && !was
    const leftRepeat = !ink.repeat && was
    prevRepeat.current = ink.repeat

    if (enteredRepeat) {
      const duration = activeSweepMs ?? getTuning().repeatSweepMs
      return runRepeatWashIn(overlay, true, duration)
    }
    // Same-word seek while still in the chain: re-run the orange wash.
    if (ink.repeat && was && ink.state === InkState.Active) {
      const duration = activeSweepMs ?? getTuning().repeatSweepMs
      return runRepeatWashIn(overlay, true, duration)
    }
    if (leftRepeat) {
      return runRepeatFadeOut(overlay, () => setRepeatMounted(false))
    }
    if (ink.repeat) {
      overlay.style.opacity = '1'
      applyMask(overlay, 'none')
    } else {
      overlay.style.opacity = '0'
      applyMask(overlay, 'none')
      setRepeatMounted(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ink.repeat, repeatMounted, activation, ink.state])

  // Search-hit pulse: mount overlay only while flashing.
  useLayoutEffect(() => {
    if (!flashMounted) return
    if (!searchFlash) {
      setFlashMounted(false)
      return
    }
    if (!flashRef.current) return
    return runSearchHitDoubleWash(
      flashRef.current,
      true,
      SearchHitFlash.PULSES,
      () => setFlashMounted(false),
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
      style={{ ['--upcoming-cover' as string]: String(upcomingCover) }}
      {...interaction}
      onContextMenu={onContextMenu}
      role="button"
      tabIndex={0}
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
              className="hafs-repeat-overlay hafs-glyph"
              aria-hidden="true"
              style={{ opacity: 0 }}
            >
              {word.arabic}
            </span>
          ) : null}
        </span>
        <span ref={coverRef} className="ink-paper-cover" aria-hidden="true" />
      </span>
      {' '}
    </span>
  )
}
