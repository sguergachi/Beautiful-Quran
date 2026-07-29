/** Basmalah constants — mirrors Android `domain/Basmalah.kt`. */

export const BASMALAH_UTHMANI = 'بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ'

export const SURAH_WITHOUT_BASMALAH = 9

const SURAH_BASMALAH_IS_AYAH_ONE = 1

/** Playlist sentinel for the recited basmalah lead-in (media ayah 0). */
export const BASMALAH_PLAYLIST_AYAH = 0

/** Al-Fatihah — source of the lead-in audio clip and its word timings. */
export const SURAH_FATIHA = 1

export function surahOpensWithBasmalahPreface(surahId: number): boolean {
  return surahId !== SURAH_BASMALAH_IS_AYAH_ONE && surahId !== SURAH_WITHOUT_BASMALAH
}
