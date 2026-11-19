/**
 * Which leaf the voice is on, and when to turn to the next one — Android
 * `MushafPager` follow. Pure: the page map is passed in.
 */
import { BASMALAH_PLAYLIST_AYAH } from '../../domain/Basmalah'

export interface PageWord {
  surah_id: number
  ayah_number: number
  position: number
}

export interface PageMap {
  pageOfAyah: (surahId: number, ayah: number) => number | null
  pageWords: (page: number) => readonly PageWord[] | null
}

/**
 * How far ahead of the voice the leaf turns. A page turned when the next
 * leaf's first word is spoken is always late; a person turning for someone
 * else starts inside the last word.
 */
export const MUSHAF_TURN_LEAD_MS = 500
/** The turn playback makes for the reader: paced, eased at both ends. */
export const MUSHAF_FOLLOW_TURN_MS = 620

/** From the moment the leaf's last word begins: its dwell at [speed], less the lead. */
export function mushafTurnLeadDelayMs(durationMs: number, speed: number): number {
  return Math.max(0, Math.round(durationMs / Math.max(0.1, speed)) - MUSHAF_TURN_LEAD_MS)
}

/** The leaf a word stands on: a verse the book carried over stands on two. */
export function mushafWordPage(map: PageMap, surahId: number, ayah: number, position: number): number | null {
  const first = map.pageOfAyah(surahId, ayah)
  if (first == null) return null
  for (const page of [first, first + 1]) {
    const rows = map.pageWords(page)
    if (rows?.some((row) => row.surah_id === surahId && row.ayah_number === ayah && row.position === position)) {
      return page
    }
  }
  return first
}

/**
 * The leaf the reciter is on, or null when the voice is not reciting this
 * chapter. By word when there is one, so a verse carried over turns the
 * leaf mid-verse instead of at the next verse.
 */
export function mushafVoicePage(
  map: PageMap,
  surahId: number,
  voice: {
    reciting: boolean
    inkAyah: number | null
    activeWord: { ayah: number; wordPosition: number } | null
    activeAyah: number | null
  },
): number | null {
  if (!voice.reciting) return null
  if (voice.inkAyah === BASMALAH_PLAYLIST_AYAH) return map.pageOfAyah(surahId, 1)
  const word = voice.activeWord
  if (word && word.ayah > 0 && (voice.inkAyah == null || word.ayah === voice.inkAyah)) {
    return mushafWordPage(map, surahId, word.ayah, word.wordPosition)
  }
  const ayah = voice.inkAyah ?? voice.activeAyah
  return ayah != null && ayah > 0 ? map.pageOfAyah(surahId, ayah) : null
}

/**
 * Whether the word being recited is the last on its leaf with the chapter
 * running on overleaf: the turn then starts inside that word.
 */
export function mushafLeadsTurn(
  map: PageMap,
  page: number,
  pageCount: number,
  surahId: number,
  word: { ayah: number; wordPosition: number },
): boolean {
  if (page >= pageCount) return false
  const rows = map.pageWords(page)
  const tail = rows?.[rows.length - 1]
  if (!tail || tail.surah_id !== surahId || tail.ayah_number !== word.ayah || tail.position !== word.wordPosition) {
    return false
  }
  const next = map.pageWords(page + 1)
  return next?.some((row) => row.surah_id === surahId) ?? false
}

/** The juzʾ (1..30) a verse belongs to — Android `juzOf`. */
const JUZ_STARTS: readonly (readonly [number, number])[] = [
  [1, 1], [2, 142], [2, 253], [3, 93], [4, 24], [4, 148], [5, 82], [6, 111], [7, 88], [8, 41],
  [9, 93], [11, 6], [12, 53], [15, 1], [17, 1], [18, 75], [21, 1], [23, 1], [25, 21], [27, 56],
  [29, 46], [33, 31], [36, 28], [39, 32], [41, 47], [46, 1], [51, 31], [58, 1], [67, 1], [78, 1],
]

export function juzOf(surahId: number, ayah: number): number {
  let juz = 1
  JUZ_STARTS.some(([s, a], index) => {
    if (s > surahId || (s === surahId && a > ayah)) return true
    juz = index + 1
    return false
  })
  return juz
}
