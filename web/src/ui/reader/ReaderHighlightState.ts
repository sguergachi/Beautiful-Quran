import type { ActiveWord } from '../../data/models'
import type { PreparedTimings } from '../../domain/HighlightEngine'
import { BASMALAH_PLAYLIST_AYAH } from '../../domain/Basmalah'

export const FADE_LEAD_MS = 500

export interface ReaderHighlightInput {
  ayah: number
  /**
   * Heard media position (latency-corrected, no word lead). Drives ayah fade
   * lead so verse handoff stays with the voice.
   */
  positionMs: number
  /**
   * Karaoke query time (heard + ramped highlight lead). Drives active word.
   * Defaults to [positionMs] when omitted (tests / idle paths).
   */
  highlightPositionMs?: number
  durationMs: number
  isPlaying: boolean
  ayahCount: number
  repeatRange: { first: number; last: number } | null
  /** Seek-generation so replaying the same Active word restarts ink. */
  activation?: number
}

export interface ReaderHighlightState {
  activeWord: ActiveWord | null
  activeAyah: number | null
  activeBasmalah: boolean
}

/**
 * Verse that owns karaoke ink. Focus may lead into the next verse before the
 * media item changes, so it must never be used as the ink owner.
 */
export function readerInkAyah(
  activeWord: ActiveWord | null | undefined,
  nowPlayingAyah: number | null | undefined,
): number | null {
  return activeWord?.ayah ?? nowPlayingAyah ?? null
}

/**
 * Whether [ayah] should run active-ayah ink policy: Upcoming prep on unread
 * words and recess-veil lift.
 *
 * The fade-lead focus target ([leadAyah]) is included so the *next* verse
 * softens before the audio item hands off. Without that, handoff flips the
 * veil and the first word's wash in the same frame — the directional ink
 * runs under a lifting paper cover and reads as "first word never animates".
 * Karaoke ownership stays with [inkAyah] / the active word alone.
 */
export function readerAyahInkPolicyActive(
  ayah: number,
  inkAyah: number | null | undefined,
  leadAyah: number | null | undefined,
): boolean {
  return inkAyah === ayah || leadAyah === ayah
}

/** How long buffering must hold before the page settles into the wait. */
export const STREAM_WAIT_SETTLE_MS = 500

/**
 * Waiting on the stream download: the spinner is up and nothing is spoken
 * yet, so the whole page — the due verse and the opening basmalah included
 * — waits under Upcoming paper, not the reciter's verse alone. A recited
 * word anchor (mid-chapter rebuffer, ink frozen on the last word) un-waits
 * the page, exactly as Android's [readerWaitingOnStream] gate does.
 */
export function readerWaitsOnStream(
  isBuffering: boolean,
  activeWord: ActiveWord | null | undefined,
): boolean {
  return isBuffering && activeWord == null
}

/**
 * Pure playback-to-reader projection shared by every store tick. Keeping this
 * out of AppStore makes fade-lead and full ActiveWord identity unit-testable.
 */
export function readerHighlightState(
  input: ReaderHighlightInput,
  prepared: PreparedTimings | undefined,
): ReaderHighlightState {
  const activeBasmalah = input.ayah === BASMALAH_PLAYLIST_AYAH
  let activeAyah: number | null = activeBasmalah ? null : input.ayah
  if (
    !activeBasmalah &&
    activeAyah != null &&
    input.isPlaying &&
    input.durationMs > 0 &&
    activeAyah < input.ayahCount
  ) {
    const remaining = input.durationMs - input.positionMs
    const stopsAtRepeatEnd = input.repeatRange != null && activeAyah >= input.repeatRange.last
    if (remaining >= 0 && remaining <= FADE_LEAD_MS && !stopsAtRepeatEnd) {
      activeAyah += 1
    }
  }

  let activeWord: ActiveWord | null = null
  if (!activeBasmalah && input.ayah > 0) {
    const queryMs = input.highlightPositionMs ?? input.positionMs
    const info = prepared?.activeInfo(queryMs)
    if (info) {
      activeWord = {
        ayah: input.ayah,
        wordPosition: info.position,
        durationMs: Math.max(0, info.holdEndMs - info.startMs),
        startMs: info.startMs,
        spokenMs: Math.max(0, info.endMs - info.startMs),
        isRepeat: info.isRepeat,
        highWater: info.highWater,
        repeatStart: info.repeatStart,
        activation: input.activation ?? 0,
      }
    }
  }
  return { activeWord, activeAyah, activeBasmalah }
}

/** Full visible identity; unlike the old abbreviated key, no metadata is lost. */
export function readerHighlightKey(state: ReaderHighlightState): string {
  const word = state.activeWord
  return [
    state.activeAyah ?? '-',
    state.activeBasmalah,
    word?.ayah ?? '-',
    word?.wordPosition ?? '-',
    word?.durationMs ?? '-',
    word?.startMs ?? '-',
    word?.spokenMs ?? '-',
    word?.isRepeat ?? '-',
    word?.highWater ?? '-',
    word?.repeatStart ?? '-',
    word?.activation ?? 0,
  ].join(':')
}
