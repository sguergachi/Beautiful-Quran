import { BASMALAH_PLAYLIST_AYAH } from '../domain/Basmalah'

/**
 * The pause between verses (Settings → Playback): a moment of silence after
 * each verse before the next begins — Android `VerseGap.kt`.
 *
 * The player stops at the verse's audible end and starts the next clip after
 * the chosen silence. Nothing is spliced into the audio, so the files, their
 * clocks and the word timings stay exactly as they are. To the reader the
 * silence is still recitation: play intent holds throughout, the way a
 * reciter's own pause does.
 */

/**
 * Whether the verse that has just ended is followed by the silence. Only a
 * chapter's verses take it: not the basmalah that opens a chapter, and not
 * the end of the queue (nothing follows it) unless a repeat carries on.
 */
export function verseGapFollows(
  endedAyah: number | undefined,
  continues: boolean,
): boolean {
  if (endedAyah == null || endedAyah === BASMALAH_PLAYLIST_AYAH) return false
  return continues
}
