/**
 * Android `ReadingPlaceRibbonPolicy`: the green reading-place ribbon moves to
 * the verse the reader deliberately paused on — only after this chapter was
 * actually played here, never mid-buffer.
 */
export function pausedReadingPlaceRibbonAyah(opts: {
  renderedSurahId: number
  mediaSurahId: number | null | undefined
  mediaAyah: number | null | undefined
  isPlaying: boolean
  isBuffering: boolean
  playedHere: boolean
}): number | null {
  const ayah = opts.mediaAyah
  if (ayah == null) return null
  return opts.mediaSurahId === opts.renderedSurahId &&
    ayah >= 1 &&
    !opts.isPlaying &&
    !opts.isBuffering &&
    opts.playedHere
    ? ayah
    : null
}

/** Ignore the player's brief non-playing dip between repeat items. */
export const PLACE_RIBBON_PAUSE_HOLD_MS = 350
