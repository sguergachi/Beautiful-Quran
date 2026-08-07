/**
 * Visual word-ink policy — port of Android `ui/reader/InkEngine.kt`.
 * Pure decision functions; no DOM.
 */
import type { ActiveWord, Segment } from '../../data/models'
import { MAX_FEATHER, basmalahWashProgress } from '../../domain/BasmalahWash'

export enum InkState {
  Plain = 'Plain',
  Upcoming = 'Upcoming',
  Active = 'Active',
  Recited = 'Recited',
}

export interface InkWord {
  state: InkState
  repeat: boolean
}

export interface InkTuning {
  upcomingAlpha: number
  inkFadeMs: number
  ayahMarkFadeMs: number
  recessMs: number
  minSweepMs: number
  maxSweepMs: number
  repeatSweepMs: number
  repeatFadeOutMs: number
  /** Dissolve of the white-gold first-gloss glint (see [glinting]) back to
   * plain recited ink once the voice moves on to the next word. */
  glintFadeMs: number
  washFeather: number
  sweepEaseX1: number
  sweepEaseY1: number
  sweepEaseX2: number
  sweepEaseY2: number
}

export const DEFAULT_TUNING: InkTuning = {
  upcomingAlpha: 0.2661,
  inkFadeMs: 400,
  ayahMarkFadeMs: 400,
  recessMs: 400,
  minSweepMs: 140,
  maxSweepMs: 8_000,
  repeatSweepMs: 450,
  repeatFadeOutMs: 900,
  glintFadeMs: 1_000,
  washFeather: 1.6,
  sweepEaseX1: 0.3,
  sweepEaseY1: 0.24,
  sweepEaseX2: 0.7,
  sweepEaseY2: 0.78,
}

let tuning: InkTuning = { ...DEFAULT_TUNING }

export function getTuning(): InkTuning {
  return tuning
}

export function setTuning(next: Partial<InkTuning> | InkTuning): void {
  tuning = { ...tuning, ...next }
}

export function resetTuning(): void {
  tuning = { ...DEFAULT_TUNING }
  highlightLeadMs = DEFAULT_HIGHLIGHT_LEAD_MS
}

export function inkAlpha(state: InkState): number {
  return state === InkState.Upcoming ? tuning.upcomingAlpha : 1
}

export function wordState(
  position: number,
  activeWord: ActiveWord | null | undefined,
  isActiveAyah: boolean,
  dimmed: boolean,
): InkState {
  if (!isActiveAyah) return dimmed ? InkState.Upcoming : InkState.Plain
  if (!activeWord) return InkState.Upcoming
  if (position === activeWord.wordPosition) return InkState.Active
  if (position < activeWord.wordPosition) return InkState.Recited
  if (position <= activeWord.highWater) return InkState.Recited
  return InkState.Upcoming
}

export function inRepeatChain(
  position: number,
  activeWord: ActiveWord | null | undefined,
): boolean {
  if (!activeWord) return false
  return activeWord.isRepeat &&
    position >= activeWord.repeatStart &&
    position <= activeWord.wordPosition
}

export function word(
  position: number,
  activeWord: ActiveWord | null | undefined,
  isActiveAyah: boolean,
  dimmed: boolean,
): InkWord {
  return {
    state: wordState(position, activeWord, isActiveAyah, dimmed),
    repeat: isActiveAyah && inRepeatChain(position, activeWord),
  }
}

/** Port of Android `InkEngine.DEFAULT_HIGHLIGHT_LEAD_MS`. */
export const DEFAULT_HIGHLIGHT_LEAD_MS = 114

/** Word-ink lead (ms). Live-tunable on Android; web keeps the shipped default. */
let highlightLeadMs = DEFAULT_HIGHLIGHT_LEAD_MS

export function getHighlightLeadMs(): number {
  return highlightLeadMs
}

export function setHighlightLeadMs(ms: number): void {
  highlightLeadMs = Math.max(0, Math.trunc(ms))
}

/**
 * Effective min letter-sweep duration. Short holds scale up to this so the
 * wash still breathes; highlight lead already starts word ink early, so that
 * lead is spent on a longer soft reveal. Port of Android `minSweepFloorMs`.
 */
export function minSweepFloorMs(): number {
  return Math.min(
    tuning.maxSweepMs,
    Math.max(1, tuning.minSweepMs + Math.max(0, highlightLeadMs)),
  )
}

export function sweepMs(
  activeWord: ActiveWord | null | undefined,
  playbackSpeed: number,
): number | null {
  if (!activeWord) return null
  // Kotlin `toInt()` truncates; use the same boundary semantics on web.
  const raw = Math.max(0, Math.trunc(activeWord.durationMs / playbackSpeed))
  // Floor at minSweep + lead so short holds / wasl still breathe. Incomplete
  // washes finish after handoff (WordUnit / HafsWord) rather than snapping.
  const floor = minSweepFloorMs()
  if (raw <= 0) return floor
  return Math.min(tuning.maxSweepMs, Math.max(floor, raw))
}

/**
 * Whether the word should wear the fresh-ink glint. Being Active *is* the
 * whole word-side gate — every Active entry re-runs the wash (including
 * seeks / repeats), and the sheen rides that wash. Themes opt in via the
 * `--glint` accent (see `glintEnabled` in render/inkWash).
 * Port of Android `InkEngine.glinting`.
 */
export function glinting(state: InkState): boolean {
  return state === InkState.Active
}

/** Peak-to-peak half-amplitude of [glintResonance] (fraction of glint alpha). */
export const GLINT_RESONANCE_AMP = 0.08

/**
 * Soft resonance rate in Hz — mid vocal-vibrato range so a multi-second
 * waqf park reads as a living hold rather than a static spotlight.
 * Port of Android `InkEngine.GLINT_RESONANCE_HZ`.
 */
export const GLINT_RESONANCE_HZ = 5.5

/**
 * Soft shimmer of the wet-ink glint while a long hold is sustained.
 * Multiplies glint opacity; returns 1 when not holding. Stylistic rate, not
 * measured pitch. Port of Android `InkEngine.glintResonance`.
 */
export function glintResonance(
  holding: boolean,
  phaseSec: number,
  amplitude = GLINT_RESONANCE_AMP,
  hz = GLINT_RESONANCE_HZ,
): number {
  if (!holding || amplitude <= 0 || hz <= 0) return 1
  return 1 + amplitude * Math.sin(2 * Math.PI * hz * phaseSec)
}

export function prefaceState(isActive: boolean, dimmed: boolean): InkState {
  if (isActive) return InkState.Active
  if (dimmed) return InkState.Upcoming
  return InkState.Plain
}

/**
 * How far the basmalah calligraphy wash has traveled (0..1) across the SVG.
 *
 * With the lead-in clip's word [segments] (Al-Fatihah 1:1, always the same
 * file) the wash is locked to the voice: each word owns the band of artwork its
 * glyphs cover — see `domain/BasmalahWash`. That is the shipped path; the ramp
 * below is the fallback for timings that are missing, still loading, or not the
 * plain four words. The fallback settles at [PREFACE_WASH_SETTLE_FRACTION] so
 * the feathered edge finishes before audio ends.
 * Port of Android `InkEngine.prefaceWashProgress`.
 */
export function prefaceWashProgress(
  positionMs: number,
  durationMs: number,
  segments?: Segment[] | null,
): number {
  const paced = basmalahWashProgress(positionMs, segments, durationMs)
  if (paced != null) return paced
  return prefaceRampProgress(positionMs, durationMs)
}

/**
 * Feather of the calligraphy wash: the tuned `washFeather`, capped by what this
 * four-word-wide artwork can carry without inking the closing word before the
 * reciter reaches it. Port of Android `InkEngine.prefaceFeather`.
 */
export function prefaceFeather(): number {
  return Math.min(tuning.washFeather, MAX_FEATHER)
}

/** Plain clip-clock ramp — the no-timings fallback of [prefaceWashProgress]. */
export function prefaceRampProgress(positionMs: number, durationMs: number): number {
  if (durationMs <= 0) return 0
  if (positionMs <= 0) return 0
  const settleAt = Math.max(1, Math.trunc(durationMs * PREFACE_WASH_SETTLE_FRACTION))
  if (positionMs >= settleAt) return 1
  return Math.min(1, Math.max(0, positionMs / settleAt))
}

/**
 * Advance the calligraphy wash without allowing a playlist clock reset to
 * uncover ink that has already settled.
 */
export function advancePrefaceWashProgress(
  previousProgress: number,
  positionMs: number,
  durationMs: number,
  segments?: Segment[] | null,
): number {
  return Math.max(previousProgress, prefaceWashProgress(positionMs, durationMs, segments))
}

/** Fraction of the lead-in clip at which the SVG wash must be fully settled. */
export const PREFACE_WASH_SETTLE_FRACTION = 0.88

export const InkEngine = {
  State: InkState,
  get tuning() {
    return getTuning()
  },
  set tuning(v: InkTuning) {
    setTuning(v)
  },
  wordState,
  inRepeatChain,
  word,
  minSweepFloorMs,
  sweepMs,
  glinting,
  prefaceState,
  prefaceWashProgress,
  prefaceRampProgress,
  prefaceFeather,
  advancePrefaceWashProgress,
  PREFACE_WASH_SETTLE_FRACTION,
  DEFAULT_HIGHLIGHT_LEAD_MS,
  getHighlightLeadMs,
  setHighlightLeadMs,
  inkAlpha,
  resetTuning,
  setTuning,
  getTuning,
}
