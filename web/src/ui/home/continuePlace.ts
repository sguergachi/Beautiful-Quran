/**
 * Where the place row stands against the list's open paper (below the
 * fixed search field, above the sheet's foot): fully in view, cut by an
 * edge, or gone above / below. 'none' when no row carries the place;
 * 'unmeasured' before the list has laid out any rows (Android holds the pill
 * back then, or it would pop and fly to a row that was in view all along).
 */
export type RowPlacement = 'visible' | 'partial' | 'above' | 'below' | 'none' | 'unmeasured'

export function rowPlacement(
  rowTop: number,
  rowBottom: number,
  viewTop: number,
  viewBottom: number,
): RowPlacement {
  // Sub-pixel layout must not keep a resting row from counting as in view.
  const slack = 0.5
  if (rowBottom <= viewTop + slack) return 'above'
  if (rowTop >= viewBottom - slack) return 'below'
  if (rowTop >= viewTop - slack && rowBottom <= viewBottom + slack) return 'visible'
  return 'partial'
}

/** Android `HomeScreen` row tap: a typed `surah:ayah` wins, then the
 * last-heard ayah when this is the place row, else the chapter opening. */
export function chapterOpenAyah(opts: {
  surahId: number
  ayahTarget: number | null
  lastSurah: number
  lastAyah: number
}): number | null {
  if (opts.ayahTarget != null) return opts.ayahTarget
  if (opts.surahId === opts.lastSurah && opts.lastAyah > 0) return opts.lastAyah
  return null
}

/** Android `shouldDismissSearchOnScroll`: a deliberate list scroll past this
 * far under the focused field puts the keyboard away (results stay). */
export const DISMISS_SCROLL_THRESHOLD_PX = 24

export function shouldDismissSearchOnScroll(scrollTop: number): boolean {
  return scrollTop > DISMISS_SCROLL_THRESHOLD_PX
}
