/** Visual clock calibration for a reciter whose source-aligned ink lands late. */
export const YASSER_AL_DOSARI_ID = 9
export const YASSER_ADVANCE_MS = 200
export const TAWFEEK_AS_SAWAIGH_ID = 20
export const TAWFEEK_ADVANCE_MS = 100

/** Extra word-highlight lead; timing rows and non-word playback stay untouched. */
export function highlightAdvanceMs(reciterId: number): number {
  if (reciterId === YASSER_AL_DOSARI_ID) return YASSER_ADVANCE_MS
  if (reciterId === TAWFEEK_AS_SAWAIGH_ID) return TAWFEEK_ADVANCE_MS
  return 0
}

export const ReciterSync = {
  YASSER_AL_DOSARI_ID,
  YASSER_ADVANCE_MS,
  TAWFEEK_AS_SAWAIGH_ID,
  TAWFEEK_ADVANCE_MS,
  highlightAdvanceMs,
}
