/**
 * Paper-stack layers — mirrors Android MainActivity COVER / AYAH / SETTINGS.
 *
 * -1 Bookmarks (over home) · 0 Chapters · 1 Reader · 2 Settings
 * When no surah is open, Settings occupies layer 1.
 */
export const BOOKMARKS_LAYER = -1
export const COVER_LAYER = 0
export const READER_LAYER = 1
export const SETTINGS_LAYER = 2

export type StackLayer = -1 | 0 | 1 | 2

export type SheetId = 'bookmarks' | 'home' | 'reader' | 'settings'

/**
 * Whether the reader owns layer 1.
 *
 * True while a surah is loaded or an explicitly restored reader state owns
 * layer 1. The sheet check keeps Settings from claiming a transient reader
 * state (`sheetAtLayer(1, false)` → `'settings'`).
 */
export function hasReaderOpen(
  content: unknown | null,
  sheet: SheetId,
): boolean {
  return content != null || sheet === 'reader'
}

/**
 * Phone scroll reader: one chapter bar stays above the cover and the reader.
 * Mushaf, gather, bookmarks, and settings keep the bar on their own sheet.
 * The cover shows it only while a verse is loaded.
 */
export function showPinnedChapterBar(input: {
  hasReader: boolean
  mushaf: boolean
  gathering: boolean
  stackLayer: number
  coverSession: boolean
}): boolean {
  if (!input.hasReader || input.mushaf || input.gathering) return false
  if (input.stackLayer < COVER_LAYER || input.stackLayer > READER_LAYER) return false
  if (input.stackLayer === COVER_LAYER && !input.coverSession) return false
  return true
}

export function settingsLayerFor(hasReader: boolean): StackLayer {
  return hasReader ? SETTINGS_LAYER : READER_LAYER
}

export function sheetAtLayer(
  layer: StackLayer,
  hasReader: boolean,
): SheetId {
  if (layer < COVER_LAYER) return 'bookmarks'
  if (layer === COVER_LAYER) return 'home'
  if (layer === READER_LAYER) return hasReader ? 'reader' : 'settings'
  return 'settings'
}

/** Depth of a sheet beneath the current stack top (0 = topmost visible). */
export function sheetDepth(
  sheetLayer: number,
  stackLayer: number,
): number {
  return Math.max(0, stackLayer - sheetLayer)
}
