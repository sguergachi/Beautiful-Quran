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
import { secondaryAlpha, TRANSLITERATION_COLOR_ALPHA } from '../ui/reader/WordHighlight'
import {
  applyMask,
  clearPaperCover,
  glintEnabled,
  runGlintFadeOut,
  runGlintWashIn,
  runLetterWash,
  runPaperCoverWash,
  hasRepeatWashProgress,
  runRepeatFadeOutAsync,
  runRepeatResidualAsync,
  runRepeatWashInAsync,
  runSearchHitDoubleWash,
  type CancellablePromise,
} from './inkWash'
import { SearchHitFlash } from '../ui/reader/SearchHitFlash'
import { useWordInteraction } from './useWordInteraction'
import { useRepeatWashGate } from './RepeatWashContext'
import { isRepeatSeekReactivation } from './repeatWashGate'

interface Props {
  word: Word
  /** Display-only English token; interaction and data identity stay on [word]. */
  englishText?: string
  ink: InkWord
  sweepMs: number | null
  /** Seek-generation so replaying this Active word restarts the wash. */
  activation?: number
  showGloss: boolean
  showTransliteration: boolean
  englishMode?: boolean
  searchHit?: boolean
  /** When true, pulse the orange search-hit flash on Arabic + gloss. */
  searchFlash?: boolean
  /** Optional external ref so the ayah can keep the active word in view. */
  rootRef?: MutableRefObject<HTMLElement | null>
  onPlay: () => void
  onHold: () => void
  onContextMenu?: (e: MouseEvent) => void
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
    if (translit) translit.style.opacity = String(TRANSLITERATION_COLOR_ALPHA * alpha)
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
    if (translit) translit.style.opacity = String(TRANSLITERATION_COLOR_ALPHA * floor)
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
  activation = 0,
  showGloss,
  showTransliteration,
  englishMode = false,
  searchHit = false,
  searchFlash = false,
  rootRef: externalRootRef,
  onPlay,
  onHold,
  onContextMenu,
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
  // null until the first layout effect so a word that mounts already Active
  // still runs its first-pass wash (same idea as prevRepeat starting false).
  const prevState = useRef<InkState | null>(null)
  const prevActivation = useRef(activation)
  // false so a word that mounts already in the chain still washes orange in
  // (Android Animatable starts at progress 0 when repeat is true on first compose).
  const prevRepeat = useRef(false)
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
  if (ink.repeat && glintEnabled() && !glintMounted) setGlintMounted(true)
  if (searchFlash && !flashMounted) setFlashMounted(true)

  /**
   * In-flight first-pass wash. Not returned as the effect cleanup on every
   * dep change — short holds must finish after Active→Recited handoff (same
   * as Android rememberLetterSweep). Cancel only on new Active entry, recess,
   * repeat takeover, or unmount.
   */
  const washStopRef = useRef<(() => void) | null>(null)
  const washingRef = useRef(false)
  const stopWash = () => {
    washStopRef.current?.()
    washStopRef.current = null
    washingRef.current = false
  }

  /*
   * Active-entry wash — Android fidelity is law.
   *
   * Arabic: paper-cover bloom with smootherstep directional mask
   * (`shapedWordBloom`). Glyphs stay full opaque ink; the soft faded edge
   * is required — never a hard cut or whole-word opacity.
   *
   * English: directional `letterFadeIn` mask on the glyph (same soft edge).
   *
   * useLayoutEffect so progress-0 lands before paint — otherwise the word
   * flashes full ink for a frame then snaps faint when the wash arrives.
   *
   * While repeating, the base layer stays untouched — orange carries the motion.
   * Leaving Active mid-wash does not snap-clear: residual finishes after handoff.
   */
  useLayoutEffect(() => {
    const prev = prevState.current
    const enteredActive = ink.state === InkState.Active && prev !== InkState.Active
    const reactivated =
      ink.state === InkState.Active &&
      prev === InkState.Active &&
      activation !== prevActivation.current
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

    // ── Arabic: paper cover + directional smootherstep mask ──────────────
    if (!englishMode) {
      const cover = coverRef.current
      if (!cover) return

      if (ink.state === InkState.Upcoming) {
        stopWash()
        clearPaperCover(cover)
        cover.style.transition = `opacity ${t.recessMs}ms cubic-bezier(0.4, 0, 0.2, 1)`
        cover.style.opacity = String(1 - resting)
        paintSecondary(glossRef.current, translitRef.current, ink, null, false)
        return
      }

      if (ink.repeat) {
        stopWash()
        clearPaperCover(cover)
        paintSecondary(glossRef.current, translitRef.current, ink, null, false)
        return
      }

      if (ink.state !== InkState.Active) {
        // Recited / Plain: let a residual short-hold wash finish; idle only clear.
        if (!washingRef.current) {
          clearPaperCover(cover)
          paintSecondary(glossRef.current, translitRef.current, ink, null, false)
        }
        return
      }

      if (!enteredActive && !reactivated) return

      // The same branch that starts the first-pass wash mounts the glint twin
      // (Nightfall): the white-gold sheen rides this wash, drying after it.
      if (glintEnabled()) setGlintMounted(true)

      stopWash()
      const duration = activeSweepMs ?? t.repeatSweepMs
      paintSecondary(glossRef.current, translitRef.current, ink, 0, false)
      washingRef.current = true
      washStopRef.current = runPaperCoverWash(
        cover,
        true,
        duration,
        ease,
        resting,
        (_p, eased) => {
          paintSecondary(glossRef.current, translitRef.current, ink, eased, false)
        },
        () => {
          washingRef.current = false
          washStopRef.current = null
          paintSecondary(glossRef.current, translitRef.current, ink, 1, false)
        },
      )
      return
    }

    // ── English: directional letterFadeIn mask ───────────────────────────
    const el = baseRef.current
    if (!el) return

    if (ink.repeat) {
      stopWash()
      applyMask(el, 'none')
      el.style.removeProperty('opacity')
      paintSecondary(glossRef.current, translitRef.current, ink, null, true)
      return
    }

    if (ink.state !== InkState.Active) {
      if (!washingRef.current) {
        applyMask(el, 'none')
        el.style.removeProperty('opacity')
        paintSecondary(glossRef.current, translitRef.current, ink, null, true)
      }
      return
    }

    if (!enteredActive && !reactivated) return

    // Same glint mount as the Arabic branch — English lyric ink glints too.
    if (glintEnabled()) setGlintMounted(true)

    stopWash()
    const duration = activeSweepMs ?? t.repeatSweepMs
    paintSecondary(glossRef.current, translitRef.current, ink, 0, true)
    washingRef.current = true
    washStopRef.current = runLetterWash(
      el,
      false,
      duration,
      ease,
      resting,
      (_p, eased) => {
        paintSecondary(glossRef.current, translitRef.current, ink, eased, true)
      },
      () => {
        washingRef.current = false
        washStopRef.current = null
        paintSecondary(glossRef.current, translitRef.current, ink, 1, true)
      },
    )
    // speed/duration captured at Active entry only — mid-word setting changes
    // must not cancel and restart the sweep (that is itself a flicker).
    // activation restarts when the same word is re-sought.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ink.state, ink.repeat, englishMode, activation])

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
      return runGlintWashIn(overlay, halo, !englishMode, duration)
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
      return runGlintWashIn(overlay, halo, !englishMode, duration)
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
    paintSecondary(
      glossRef.current,
      translitRef.current,
      ink,
      ink.state === InkState.Active && !ink.repeat ? 1 : null,
      englishMode,
    )
  }, [ink.state, ink.repeat, showGloss, showTransliteration, word.translation, word.transliteration, englishMode])

  const repeatGate = useRepeatWashGate()
  const repeatLive = useRef(ink.repeat)
  repeatLive.current = ink.repeat
  /** In-flight leave fade — must cancel on re-entry or opacity races to 0. */
  const fadeCancelRef = useRef<(() => void) | null>(null)
  const cancelLeaveFade = () => {
    fadeCancelRef.current?.()
    fadeCancelRef.current = null
  }
  // Orange repeat overlay — sequential residual finish (Android law):
  // • Duration is always repeatSweepMs (never the audio sliver).
  // • Per-ayah gate: word N finishes its feather before N+1 starts.
  // • Active handoff does not cancel an in-flight wash (deps omit activation).
  // • Never snap incomplete → full; release finishes residual then dissolves.
  // • Never-started members no-op on residual (no post-chain orange bloom).
  // • Re-entry cancels a stale leave fade so opacity is not raced to 0.
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
    const rtl = !englishMode
    const duration = getTuning().repeatSweepMs

    const pos = word.position
    if (enteredRepeat) {
      prevRepeat.current = true
      cancelLeaveFade()
      // Only abandons *queued* work still waiting on the gate — once the
      // wash body starts it always runs to progress 1.
      let stillQueued = true
      void repeatGate.run(pos, async () => {
        if (!stillQueued || !overlayRef.current) return
        if (!repeatLive.current) return
        stillQueued = false
        await runRepeatWashInAsync(overlayRef.current, rtl, duration)
      })
      return () => {
        // Drop only if we never left the queue. In-flight wash keeps going.
        if (stillQueued) {
          stillQueued = false
          if (repeatLive.current) prevRepeat.current = false
        }
      }
    }
    if (leftRepeat) {
      prevRepeat.current = false
      // Residual under the gate; fade outside so N-word clear isn't serial.
      void (async () => {
        await repeatGate.run(pos, async () => {
          const el = overlayRef.current
          if (!el) return
          await runRepeatResidualAsync(el, rtl)
        })
        if (repeatLive.current) return
        const el = overlayRef.current
        if (!el) {
          setRepeatMounted(false)
          return
        }
        // Never washed → nothing to dissolve.
        if (!hasRepeatWashProgress(el)) {
          setRepeatMounted(false)
          return
        }
        const fade: CancellablePromise = runRepeatFadeOutAsync(el)
        fadeCancelRef.current = () => fade.cancel()
        await fade
        fadeCancelRef.current = null
        if (!repeatLive.current) setRepeatMounted(false)
      })()
      return
    }
    // Still in chain — hold settled orange; do not snap.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ink.repeat, englishMode, repeatMounted])

  // Seek-reactivation of the *active* word: queue another full orange wash.
  const prevSeekActive = useRef(false)
  const prevSeekActivation = useRef(activation)
  useLayoutEffect(() => {
    const active = ink.repeat && ink.state === InkState.Active
    const reactivated = isRepeatSeekReactivation(
      prevSeekActive.current,
      prevSeekActivation.current,
      active,
      activation,
    )
    prevSeekActive.current = active
    prevSeekActivation.current = activation
    if (!reactivated) return
    cancelLeaveFade()
    if (!overlayRef.current || !repeatMounted) return
    const rtl = !englishMode
    const duration = getTuning().repeatSweepMs
    void repeatGate.run(word.position, async () => {
      if (!overlayRef.current || !repeatLive.current) return
      await runRepeatWashInAsync(overlayRef.current, rtl, duration)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activation, ink.repeat, ink.state, repeatMounted, englishMode])

  // Search-hit pulse: mount the twin overlay only while flashing.
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
      cancels.push(
        runSearchHitDoubleWash(
          flashRef.current,
          !englishMode,
          SearchHitFlash.PULSES,
          doneOne,
        ),
      )
    }
    if (glossFlashRef.current) {
      pending++
      cancels.push(
        runSearchHitDoubleWash(
          glossFlashRef.current,
          false,
          SearchHitFlash.PULSES,
          doneOne,
        ),
      )
    }
    if (pending === 0) setFlashMounted(false)
    return () => cancels.forEach((c) => c())
  }, [searchFlash, englishMode, flashMounted])

  const rtl = !englishMode
  const style: CSSProperties = englishMode
    ? { ['--upcoming-alpha' as string]: String(tuning.upcomingAlpha) }
    : { ['--upcoming-cover' as string]: String(upcomingCover) }

  const label = englishMode ? englishText || word.translation || word.arabic : word.arabic

  return (
    <span
      ref={(node) => {
        localRootRef.current = node
        if (externalRootRef) externalRootRef.current = node
      }}
      className={englishMode ? 'word-unit word-ink' : 'word-unit word-arabic-ink'}
      data-state={ink.state}
      style={style}
      {...interaction}
      onContextMenu={onContextMenu}
      role="button"
      tabIndex={0}
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
              className={`word-repeat-overlay ${baseClass}`}
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
              className="word-repeat-overlay word-gloss"
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
