/**
 * Visual word-ink policy — port of Android `ui/reader/InkEngine.kt`.
 * Pure decision functions; no DOM.
 */
import type { ActiveWord } from '../../data/models'

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
  washFeather: 0.5,
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
 * Whether a word entering Active should start already fully revealed.
 * Always false: every Active entry re-runs the ink wash — including when the
 * listener taps a recited word to play it again. Sampling jitter is filtered
 * by HighlightClock so accidental bounce no longer needs this suppression.
 * Port of Android `InkEngine.startRevealed`.
 */
export function startRevealed(_previous: InkState, _current: InkState): boolean {
  return false
}

/**
 * Whether the word should wear the fresh-ink glint: the subtle white-gold
 * sheen a genuinely new word carries while its ink is still wet, dissolving
 * back to plain recited ink over [InkTuning.glintFadeMs] once the voice moves
 * on. Themes opt in via the `--glint` accent (Nightfall and Royal Green —
 * see `glintEnabled` in render/inkWash); this predicate is the *word* half of
 * the gate. Active repeat words glint over their orange wash too.
 * [wasStartRevealed] is retained for API parity; wash always restarts on Active
 * entry, so ordinary seeks also glint as a fresh play of the word.
 * Port of Android `InkEngine.glinting`.
 */
export function glinting(
  state: InkState,
  repeat: boolean,
  wasStartRevealed: boolean,
): boolean {
  return state === InkState.Active && (repeat || !wasStartRevealed)
}

export function prefaceState(isActive: boolean, dimmed: boolean): InkState {
  if (isActive) return InkState.Active
  if (dimmed) return InkState.Upcoming
  return InkState.Plain
}

/**
 * How far the basmalah calligraphy wash has traveled (0..1) across the SVG.
 * Driven by the lead-in clip's playback clock — settles at
 * [PREFACE_WASH_SETTLE_FRACTION] so the feathered edge finishes before audio ends.
 * Port of Android `InkEngine.prefaceWashProgress`.
 */
export function prefaceWashProgress(positionMs: number, durationMs: number): number {
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
): number {
  return Math.max(previousProgress, prefaceWashProgress(positionMs, durationMs))
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
  startRevealed,
  glinting,
  prefaceState,
  prefaceWashProgress,
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
