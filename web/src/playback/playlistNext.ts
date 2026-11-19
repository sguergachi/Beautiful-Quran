import { BASMALAH_PLAYLIST_AYAH } from '../domain/Basmalah'

/**
 * Pure playlist advance — shared by PlayerController and unit tests.
 * Keeps verse-join / wrap behaviour free of DOM Audio.
 */

export type RepeatMode = 'off' | 'ayah' | 'surah' | 'range'

export interface RepeatRange {
  first: number
  last: number
}

/**
 * Next playlist index after [index], honouring surah/range wrap.
 * When [forStandby] and ayah-repeat are set, returns null so the standby
 * element stays empty (HTMLAudioElement.loop owns the join).
 */
export function peekPlaylistNextIndex(
  playlistAyahs: number[],
  index: number,
  opts: {
    repeatMode: RepeatMode
    repeatRange: RepeatRange | null
    forStandby?: boolean
    /** Surah repeat wraps past the basmalah lead-in, already heard. */
    skipLeadIn?: boolean
  },
): number | null {
  if (opts.repeatMode === 'ayah' && opts.forStandby) return null

  const range = opts.repeatRange
  const cur = playlistAyahs[index]
  if (range && cur != null && cur >= range.last) {
    const firstIdx = playlistAyahs.findIndex((a) => a === range.first)
    return Math.max(0, firstIdx)
  }

  const next = index + 1
  if (next >= playlistAyahs.length) {
    if (opts.repeatMode === 'surah') {
      return opts.skipLeadIn && playlistAyahs[0] === BASMALAH_PLAYLIST_AYAH && playlistAyahs.length > 1 ? 1 : 0
    }
    return null
  }
  return next
}
