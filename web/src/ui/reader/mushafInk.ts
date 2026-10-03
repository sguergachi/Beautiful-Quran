import type { ActiveWord } from '../../data/models'
import type { MushafToken } from '../../domain/mushafPage'
import { InkEngine, InkState, type InkWord } from './InkEngine'

/**
 * What the voice is doing, for the leaf's ink. The same projection the
 * scroll reader feeds its verses (ReaderScreen: inkAyah / leadAyah).
 */
export interface MushafInk {
  /** The chapter on the player is this one, and it is playing or joining. */
  reciting: boolean
  /** Owns the karaoke wash: the active word's verse, else the playing item. */
  inkAyah: number | null
  /** The fade-lead verse: softens under paper before the item hands off. */
  leadAyah: number | null
  activeWord: ActiveWord | null
  /**
   * The voiced verse has already had a word. With no active word it is then
   * in its audio tail, read through, not still waiting for its first word.
   * Android `playingAyahHasWord`.
   */
  inkAyahRead: boolean
  speed: number
}

export const MUSHAF_INK_IDLE: MushafInk = {
  reciting: false,
  inkAyah: null,
  leadAyah: null,
  activeWord: null,
  inkAyahRead: false,
  speed: 1,
}

const PLAIN: InkWord = { state: InkState.Plain, repeat: false }
const UPCOMING: InkWord = { state: InkState.Upcoming, repeat: false }
/** A word in hand on a page that is only a picture: inked, with no wash. */
export const MUSHAF_STILL_INK: InkWord = { state: InkState.Recited, repeat: false }

/**
 * One word's ink on a leaf that carries the voice. Port of Android
 * `mushafInkPackKind`: the voiced verse runs the karaoke wash, every verse
 * still to come waits under Upcoming paper, and what has been read keeps
 * full ink. A leaf the reader has browsed to, away from the voice, is
 * [live] = false and stays plain.
 *
 * The clip of a verse runs on a little after its last word. The voice has
 * no word then, exactly as before the first one, but the verse is read:
 * it keeps its ink through the tail instead of dropping back under paper
 * for a moment before the next verse takes over.
 */
export function mushafTokenInk(
  token: Pick<MushafToken, 'surahId' | 'ayah' | 'position'>,
  surahId: number,
  ink: MushafInk,
  live: boolean,
): InkWord {
  if (!live || !ink.reciting) return PLAIN
  if (token.surahId !== surahId) return token.surahId > surahId ? UPCOMING : PLAIN
  if (token.ayah === ink.inkAyah || token.ayah === ink.leadAyah) {
    const word = token.ayah === ink.inkAyah && ink.activeWord?.ayah === token.ayah ? ink.activeWord : null
    if (!word && token.ayah === ink.inkAyah && ink.inkAyahRead) return MUSHAF_STILL_INK
    return InkEngine.word(token.position, word, true, false)
  }
  const frontier = Math.max(ink.inkAyah ?? 0, ink.leadAyah ?? 0)
  return token.ayah > frontier ? UPCOMING : PLAIN
}
