/** Visual clock calibration for a reciter whose source-aligned ink lands late. */
export const YASSER_AL_DOSARI_ID = 9
export const YASSER_ADVANCE_MS = 200

/** Extra word-highlight lead; timing rows and non-word playback stay untouched. */
export function highlightAdvanceMs(reciterId: number): number {
  return reciterId === YASSER_AL_DOSARI_ID ? YASSER_ADVANCE_MS : 0
}

export const ReciterSync = {
  YASSER_AL_DOSARI_ID,
  YASSER_ADVANCE_MS,
  highlightAdvanceMs,
}
