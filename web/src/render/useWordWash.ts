import { useLayoutEffect, useRef, type RefObject } from 'react'
import type { ActiveWord, Word } from '../data/models'
import {
  connection,
  connectionProgress,
  waslPrefixCompletion,
  waslPrefixStart,
  tajweedCurve,
} from '../domain/tajweedPacing'
import { cubicBezierEase } from '../ui/theme/Fade'
import {
  getTuning,
  InkState,
  pacing,
  type InkWord,
} from '../ui/reader/InkEngine'
import { tarjiSignal } from '../playback/tarjiSignal'
import {
  applyMask,
  cachedPaperCoverMask,
  cachedWashMask,
  clearPaperCover,
} from './inkWash'
import { useWordMotionRegistry } from './WordMotionContext'

export interface WordMotion {
  key: string
  timing?: ActiveWord | null
  isAyahFinal: boolean
  previousArabic?: string
  nextArabic?: string
}
/** Shared Arabic/English wash lifecycle. Active→Recited keeps the residual;
 * seek/recess/plain cancel it. All entry tuning is latched until completion.
 */
export function useWordWash({
  word,
  ink,
  activation,
  durationMs,
  english,
  target,
  motion,
  secondary,
  onActive,
}: {
  word: Word
  ink: InkWord
  activation: number
  durationMs: number | null
  english: boolean
  target: RefObject<HTMLSpanElement | null>
  motion?: WordMotion
  secondary?: (progress: number | null) => void
  onActive: () => void
}) {
  const registry = useWordMotionRegistry()
  const washing = useRef(false)
  const stop = useRef<(() => void) | null>(null)
  const prev = useRef<{ state: InkState; activation: number } | null>(null)
  const state = useRef(ink)
  state.current = ink
  const key = motion?.key ?? String(word.position)
  const group = key.slice(0, key.lastIndexOf(':') + 1)
  const nextKey = `${group}${word.position + 1}`,
    priorKey = `${group}${word.position - 1}`
  const latched = useRef({
    eligible: false,
    wordStartMs: -1,
    washProgress: (): number => 0,
  })

  useLayoutEffect(() => {
    const el = target.current
    if (!el) return
    const paint = (progress: number, feather: number) => {
      if (state.current.state !== InkState.Upcoming || state.current.repeat)
        return
      if (english) return // Meaning text follows its own strictly ordered wash.
      el.style.transition = 'none'
      el.style.opacity = '1'
      applyMask(
        el,
        cachedPaperCoverMask(
          progress,
          getTuning().upcomingAlpha,
          true,
          feather,
        ),
      )
    }
    registry.targets.set(key, { paint })
    return () => {
      registry.targets.delete(key)
      registry.prefixes.delete(key)
      registry.progress.delete(key)
    }
  }, [registry, key, target, english])

  useLayoutEffect(() => {
    const el = target.current
    if (!el) return
    const previous = prev.current
    prev.current = { state: ink.state, activation }
    const cancel = () => {
      stop.current?.()
      stop.current = null
      washing.current = false
    }
    const clear = () => {
      if (english) {
        applyMask(el, 'none')
        el.style.removeProperty('opacity')
      } else clearPaperCover(el)
    }
    if (ink.repeat && ink.state === InkState.Active) {
      latched.current = {
        eligible:
          tajweedCurve(word.arabic, 1, {
            isAyahFinal: motion?.isAyahFinal ?? false,
          })?.hasStrongHold ?? false,
        wordStartMs: motion?.timing?.startMs ?? tarjiSignal.positionMs,
        washProgress: () => 1,
      }
    }
    if (
      ink.repeat ||
      ink.state === InkState.Upcoming ||
      ink.state === InkState.Plain
    ) {
      cancel()
      clear()
      registry.progress.delete(key)
      if (ink.state === InkState.Upcoming && !english) {
        const t = getTuning()
        el.style.transition = `opacity ${t.recessMs}ms cubic-bezier(0.4, 0, 0.2, 1)`
        el.style.opacity = String(1 - t.upcomingAlpha)
      }
      secondary?.(null)
      return
    }
    if (ink.state === InkState.Recited) {
      if (!washing.current) {
        clear()
        secondary?.(null)
      }
      return
    }
    if (
      previous?.state === InkState.Active &&
      previous.activation === activation
    )
      return
    cancel()
    onActive()
    registry.enter(activation)
    const tuning = { ...getTuning() },
      duration = Math.max(1, durationMs ?? tuning.repeatSweepMs)
    const from = !english ? (registry.prefixes.get(key) ?? 0) : 0
    const curve =
      !english && motion?.timing
        ? pacing(
            word.arabic,
            motion.timing,
            motion.isAyahFinal,
            motion.previousArabic,
            from > 0 ? tuning.washFeather : undefined,
          )
        : null
    const eligible =
      tajweedCurve(word.arabic, 1, {
        isAyahFinal: motion?.isAyahFinal ?? false,
      })?.hasStrongHold ?? false
    latched.current = {
      eligible,
      wordStartMs: motion?.timing?.startMs ?? tarjiSignal.positionMs,
      washProgress: () => registry.progress.get(key) ?? 1,
    }
    // A wasl entry retains the donor's feather; changing it at handoff shifts
    // the already-visible prefix. The new sweep continues from that edge.
    registry.prefixes.delete(key)
    const feather =
      from > 0
        ? tuning.washFeather
        : curve
          ? tuning.pacedFeather
          : tuning.washFeather
    const connected =
      !english &&
      tuning.tajweedPacing &&
      tuning.holdConnect &&
      motion?.nextArabic
        ? connection(word.arabic, motion.nextArabic)
        : null
    const start = waslPrefixStart(duration, tuning.waslPrefixMs)
    const completion = waslPrefixCompletion(
      duration,
      tuning.waslPrefixMs,
      tuning.waslHandoff,
    )
    const prefixEnd = connected
      ? (connected.prefixFraction +
          Math.min(connected.prefixFraction + 0.25, 0.55)) /
        (1 + tuning.washFeather)
      : 0
    let elapsed = 0,
      last = performance.now(),
      frame = 0,
      cancelled = false
    const source = tarjiSignal.sourceKey
    let lastMedia = tarjiSignal.positionMs
    washing.current = true
    registry.progress.set(key, 0)
    const paint = (progress: number) => {
      if (english) {
        el.style.removeProperty('opacity')
        applyMask(
          el,
          cachedWashMask(progress, tuning.upcomingAlpha, false, feather),
        )
      } else {
        el.style.transition = 'none'
        el.style.opacity = '1'
        applyMask(
          el,
          cachedPaperCoverMask(progress, tuning.upcomingAlpha, true, feather),
        )
      }
      secondary?.(progress)
    }
    paint(from)
    const tick = (now: number) => {
      if (cancelled) return
      const delta = Math.max(0, now - last)
      last = now
      const currentMedia = tarjiSignal.positionMs
      const running = !source || tarjiSignal.isPlaying
      // English wrapped words wait for the previous residual to finish.
      const prior = english ? registry.progress.get(priorKey) : undefined
      if (running && (prior == null || prior >= 1)) {
        if (
          source &&
          source === tarjiSignal.sourceKey &&
          state.current.state === InkState.Active
        ) {
          // A frozen/buffering media clock must freeze ink too.
          elapsed += Math.max(0, currentMedia - lastMedia) / tarjiSignal.speed
        } else elapsed += delta
      }
      lastMedia = currentMedia
      const clock = Math.min(1, elapsed / duration)
      const shaped = curve
        ? curve.at(clock)
        : cubicBezierEase(
            clock,
            tuning.sweepEaseX1,
            tuning.sweepEaseY1,
            tuning.sweepEaseX2,
            tuning.sweepEaseY2,
          )
      const progress = from + (1 - from) * shaped
      paint(progress)
      registry.progress.set(key, progress)
      if (connected && state.current.state === InkState.Active) {
        registry.prefix(
          nextKey,
          prefixEnd * connectionProgress(clock, start, completion),
          tuning.washFeather,
        )
      }
      if (clock >= 1) {
        washing.current = false
        stop.current = null
        clear()
        secondary?.(1)
      } else frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    stop.current = () => {
      cancelled = true
      cancelAnimationFrame(frame)
    }
    // Entry-only: mid-word tuning/duration changes must never rewind the edge.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ink.state, ink.repeat, activation, english, key, registry])

  useLayoutEffect(
    () => () => {
      stop.current?.()
      stop.current = null
      washing.current = false
      prev.current = null
    },
    [],
  )
  return { washing, latched }
}
