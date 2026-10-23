/**
 * Pinned chapters (Android `HomeScreen.kt`: `pinnedBand`, `pinFill`,
 * `pinFillSpan`, `pinAction`). Pinned chapters lead the list on a green band,
 * in Quranic order, and move there: the list below leaves them out, so no
 * chapter shows twice. Search owns the sheet while active; the band stands down.
 */

/** Press-and-hold time before a row asks to pin or unpin. */
export const PIN_HOLD_MS = 500

export interface ChapterLists<T extends { id: number }> {
  pinned: T[]
  listed: T[]
}

export function pinnedChapterLists<T extends { id: number }>(
  surahs: T[],
  pinnedIds: readonly number[],
  searching: boolean,
): ChapterLists<T> {
  if (searching || pinnedIds.length === 0) return { pinned: [], listed: surahs }
  const ids = new Set(pinnedIds)
  return {
    pinned: surahs.filter((s) => ids.has(s.id)),
    listed: surahs.filter((s) => !ids.has(s.id)),
  }
}

/** Key of a chapter row: its pinned copy on the band, or its in-order row. */
export function chapterRowKey(surahId: number, pinned: boolean): string {
  return pinned ? `pinned-${surahId}` : `surah-${surahId}`
}

/** Where the fill grows from: the pointer, clamped to the row; the middle
 * when no pointer is known (the keyboard asked). */
export function pinFillOrigin(pointerX: number, width: number): number {
  return Number.isNaN(pointerX) ? width / 2 : Math.min(width, Math.max(0, pointerX))
}

/**
 * The filled span at [progress]: a solid, square-cut slice out from the
 * pointer that reaches both edges together. The row draws it as `scaleX`
 * about [pinFillOrigin], which is exactly this span.
 */
export function pinFillSpan(pointerX: number, width: number, progress: number): [number, number] {
  const origin = pinFillOrigin(pointerX, width)
  const p = Math.min(1, Math.max(0, progress))
  return [origin * (1 - p), origin + (width - origin) * p]
}

/** The verb press-and-hold offers beside Cancel. */
export function pinAction(pinned: boolean): 'Pin' | 'Unpin' {
  return pinned ? 'Unpin' : 'Pin'
}
