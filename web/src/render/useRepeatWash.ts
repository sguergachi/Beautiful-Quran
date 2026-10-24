import { useLayoutEffect, useRef, type RefObject } from 'react'
import {
  getTuning,
  InkState,
  minSweepFloorMs,
  pacing,
  type InkWord,
} from '../ui/reader/InkEngine'
import type { Word } from '../data/models'
import type { WordMotion } from './useWordWash'
import { tarjiSignal } from '../playback/tarjiSignal'
import { highlightTimeline, mediaWashProgress } from '../playback/highlightTimeline'
import { cubicBezierEase } from '../ui/theme/Fade'
import { applyMask, cachedWashMask, runRepeatFadeOut } from './inkWash'
import { englishRevealDurationMs } from '../ui/reader/EnglishRevealTiming'

/** Android rememberRepeatWash: measured dwell, concurrent residuals, history
 * already complete on seek. A preceding orange wash never delays live speech.
 */
export function useRepeatWash(
  word: Word,
  ink: InkWord,
  activation: number,
  english: boolean,
  motion: WordMotion | undefined,
  mounted: boolean,
  overlay: RefObject<HTMLSpanElement | null>,
  unmount: () => void,
) {
  const previous = useRef({ repeat: false, activation: 0, occurrence: -1 })
  const live = useRef(ink)
  live.current = ink
  const stop = useRef<(() => void) | null>(null)
  const fading = useRef<(() => void) | null>(null)
  const running = useRef(false)
  const progress = useRef(1)
  const dry = () => {
    const el = overlay.current
    if (!el || live.current.repeat) return
    fading.current?.()
    fading.current = runRepeatFadeOut(el, () => {
      fading.current = null
      if (!live.current.repeat) unmount()
    })
  }
  useLayoutEffect(() => {
    if (!mounted || !overlay.current) return
    const el = overlay.current,
      prev = previous.current
    const reveal =
      ink.repeat &&
      (!prev.repeat || (activation !== 0 && activation !== prev.activation) ||
        ink.state === InkState.Active && motion?.timing?.startMs !== prev.occurrence)
    const release = !ink.repeat && prev.repeat
    previous.current = { repeat: ink.repeat, activation, occurrence: motion?.timing?.startMs ?? -1 }
    if (release) {
      if (!running.current) dry()
      return
    }
    if (!reveal) return
    stop.current?.()
    fading.current?.()
    fading.current = null
    el.style.opacity = '1'
    if (ink.state !== InkState.Active) {
      // Seeking into an already-spoken chain exposes its history immediately.
      progress.current = 1
      applyMask(el, 'none')
      running.current = false
      stop.current = null
      return
    }
    const t = { ...getTuning() }
    const ordinaryDuration = Math.max(
      1,
      motion?.timing
        ? motion.timing.durationMs / tarjiSignal.speed
        : t.repeatSweepMs,
    )
    const duration = english ? englishRevealDurationMs(ordinaryDuration, minSweepFloorMs()) : ordinaryDuration
    const curve =
      !english && motion?.timing
        ? pacing(
            word.arabic,
            motion.timing,
            motion.isAyahFinal,
            motion.previousArabic,
          )
        : null
    const feather = curve ? t.pacedFeather : t.washFeather
    const mediaWindow = motion?.timing?.startMs != null ? highlightTimeline.window(motion.timing.startMs, duration) : null
    const source = tarjiSignal.sourceKey
    let elapsed = 0,
      last = performance.now(),
      lastMedia = tarjiSignal.positionMs,
      frame = 0,
      cancelled = false
    running.current = true
    progress.current = 0
    applyMask(el, cachedWashMask(0, 0, !english, feather))
    const tick = (now: number) => {
      if (cancelled) return
      const delta = Math.max(0, now - last)
      last = now
      const media = tarjiSignal.positionMs
      if (!source || tarjiSignal.isPlaying) {
        elapsed +=
          source &&
          source === tarjiSignal.sourceKey &&
          live.current.state === InkState.Active
            ? Math.max(0, media - lastMedia) / tarjiSignal.speed
            : delta
      }
      lastMedia = media
      const clock = mediaWindow ? mediaWashProgress(mediaWindow, highlightTimeline.frame(now)) : Math.min(1, elapsed / duration)
      const p = curve
        ? curve.at(clock)
        : cubicBezierEase(
            clock,
            t.sweepEaseX1,
            t.sweepEaseY1,
            t.sweepEaseX2,
            t.sweepEaseY2,
          )
      progress.current = p
      applyMask(el, cachedWashMask(p, 0, !english, feather))
      if (clock >= 1) {
        running.current = false
        stop.current = null
        dry()
      } else frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    stop.current = () => {
      cancelled = true
      cancelAnimationFrame(frame)
      running.current = false
    }
    // Capture tuning/duration at Reveal only; Active→Recited keeps this clock.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ink.repeat, ink.state, activation, motion?.timing?.startMs, mounted, english])
  useLayoutEffect(
    () => () => {
      stop.current?.()
      fading.current?.()
      previous.current = { repeat: false, activation: 0, occurrence: -1 }
    },
    [],
  )
  return progress
}
