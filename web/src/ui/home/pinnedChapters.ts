/**
 * Pinned chapters (Android `HomeScreen.kt`: `pinnedBand`, `holdToPin`,
 * `holdOutcome`). Pinned chapters lead the list on a green band,
 * in Quranic order, and move there: the list below leaves them out, so no
 * chapter shows twice. Search owns the sheet while active; the band stands down.
 */

/**
 * Hold-to-pin (Android `holdToPin`): the hold is the confirmation. The row
 * starts to tint after [HOLD_START_MS], deepens evenly over [HOLD_FILL_MS],
 * and the pin toggles when it is full.
 */
export const HOLD_START_MS = 150
export const HOLD_FILL_MS = 550
/** Lifted sooner than this, a press is the ordinary click that opens it. */
export const HOLD_TAP_MAX_MS = 300

/** What lifting the pointer [heldMs] into a hold means, before it is full. */
export function holdOutcome(heldMs: number): 'tap' | 'cancel' {
  return heldMs < HOLD_TAP_MAX_MS ? 'tap' : 'cancel'
}

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
