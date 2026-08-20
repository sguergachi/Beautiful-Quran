import type { BrushCircleStyle } from '../ui/kit/brushMark'
import { BRUSH_CIRCLE_STYLE_IDS } from '../ui/kit/brushMark'

export type ThemeMode = 'system' | 'light' | 'dark' | 'royal_green'
export type ReadingMode = 'arabic_english' | 'english_only' | 'arabic_only'
export type ReadingLayout = 'scroll' | 'mushaf'
export type VerseNumberScript = 'arabic' | 'english'
export type PageNumberScript = 'both' | 'arabic' | 'english'
export type AyahSelectorSide = 'left' | 'right'
export type HomeBookmarkStyle = 'top_bound' | 'saved_passages'

export const HOME_BOOKMARK_STYLES: HomeBookmarkStyle[] = [
  'top_bound',
  'saved_passages',
]
export const READING_LAYOUTS: ReadingLayout[] = ['scroll', 'mushaf']
export const VERSE_NUMBER_SCRIPTS: VerseNumberScript[] = ['arabic', 'english']
export const PAGE_NUMBER_SCRIPTS: PageNumberScript[] = ['both', 'arabic', 'english']
export const READING_MODES: ReadingMode[] = [
  'arabic_english',
  'english_only',
  'arabic_only',
]
export type { BrushCircleStyle }

/** Match Android SettingsScreen: 0.8f..1.6f with 8 intervals (9 snap points). */
export const FONT_SCALE_MIN = 0.8
export const FONT_SCALE_MAX = 1.6
export const FONT_SCALE_STEPS = 8
export const FONT_SCALE_STEP = (FONT_SCALE_MAX - FONT_SCALE_MIN) / FONT_SCALE_STEPS

/** Snap [scale] to the nearest stop, then move [deltaStops] (±1 for the A glyphs). */
export function nudgeFontScale(scale: number, deltaStops: number): number {
  const current = Math.round((scale - FONT_SCALE_MIN) / FONT_SCALE_STEP)
  const next = Math.min(FONT_SCALE_STEPS, Math.max(0, current + deltaStops))
  return FONT_SCALE_MIN + next * FONT_SCALE_STEP
}

export interface Settings {
  reciterId: number
  fontScale: number
  readingMode: ReadingMode
  readingLayout: ReadingLayout
  verseNumberScript: VerseNumberScript
  pageNumberScript: PageNumberScript
  showWordGloss: boolean
  showTransliteration: boolean
  showTranslation: boolean
  themeMode: ThemeMode
  ayahSelectorSide: AyahSelectorSide
  /** Continue Listening — last verse actually recited (not mere open/scroll). */
  lastSurah: number
  lastAyah: number
  playbackSpeed: number
  /** Reveals developer tools (e.g. the Ornaments Lab). Off by default. */
  developerMode: boolean
  /**
   * Developer-only gate for contextual feature lessons. Off until their
   * visual language is approved for readers. Enabling rearms every lesson.
   */
  educationGuidesEnabled: boolean
  /**
   * Use Gapless-5 (HTML5 + Web Audio) for verse joins. On by default; the
   * developer toggle can fall back to dual-`<audio>` handoff for A/B.
   */
  gapless5Playback: boolean
  /** Developer-selectable Chapters bookmark treatment. */
  homeBookmarkStyle: HomeBookmarkStyle
  /** Developer-only: ink-brush circle style around selected enums. */
  brushCircleStyle: BrushCircleStyle
}

const DEFAULTS: Settings = {
  reciterId: 1,
  fontScale: 1,
  readingMode: 'arabic_english',
  readingLayout: 'scroll',
  verseNumberScript: 'arabic',
  pageNumberScript: 'both',
  showWordGloss: true,
  showTransliteration: false,
  showTranslation: false,
  themeMode: 'system',
  ayahSelectorSide: 'left',
  lastSurah: 0,
  lastAyah: 1,
  playbackSpeed: 1,
  developerMode: false,
  educationGuidesEnabled: false,
  gapless5Playback: true,
  homeBookmarkStyle: 'top_bound',
  brushCircleStyle: 'baseline',
}

function clampBrushStyle(value: unknown): BrushCircleStyle {
  return typeof value === 'string' &&
    (BRUSH_CIRCLE_STYLE_IDS as string[]).includes(value)
    ? (value as BrushCircleStyle)
    : DEFAULTS.brushCircleStyle
}

const KEY = 'beautiful-quran-settings'

function clampFontScale(value: unknown): number {
  const n = typeof value === 'number' && Number.isFinite(value) ? value : DEFAULTS.fontScale
  return Math.min(FONT_SCALE_MAX, Math.max(FONT_SCALE_MIN, n))
}

export function normalizeSettings(partial: Partial<Settings> = {}): Settings {
  return {
    ...DEFAULTS,
    ...partial,
    fontScale: clampFontScale(partial.fontScale ?? DEFAULTS.fontScale),
    developerMode: Boolean(partial.developerMode ?? DEFAULTS.developerMode),
    educationGuidesEnabled: Boolean(
      partial.educationGuidesEnabled ?? DEFAULTS.educationGuidesEnabled,
    ),
    gapless5Playback: Boolean(
      partial.gapless5Playback ?? DEFAULTS.gapless5Playback,
    ),
    homeBookmarkStyle: HOME_BOOKMARK_STYLES.includes(
      partial.homeBookmarkStyle as HomeBookmarkStyle,
    )
      ? (partial.homeBookmarkStyle as HomeBookmarkStyle)
      : DEFAULTS.homeBookmarkStyle,
    brushCircleStyle: clampBrushStyle(
      partial.brushCircleStyle ?? DEFAULTS.brushCircleStyle,
    ),
    readingMode: READING_MODES.includes(partial.readingMode as ReadingMode)
      ? (partial.readingMode as ReadingMode)
      : DEFAULTS.readingMode,
    readingLayout: READING_LAYOUTS.includes(partial.readingLayout as ReadingLayout)
      ? (partial.readingLayout as ReadingLayout)
      : DEFAULTS.readingLayout,
    verseNumberScript: VERSE_NUMBER_SCRIPTS.includes(
      partial.verseNumberScript as VerseNumberScript,
    )
      ? (partial.verseNumberScript as VerseNumberScript)
      : DEFAULTS.verseNumberScript,
    pageNumberScript: PAGE_NUMBER_SCRIPTS.includes(
      partial.pageNumberScript as PageNumberScript,
    )
      ? (partial.pageNumberScript as PageNumberScript)
      : DEFAULTS.pageNumberScript,
  }
}

export function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return { ...DEFAULTS }
    return normalizeSettings(JSON.parse(raw) as Partial<Settings>)
  } catch {
    return { ...DEFAULTS }
  }
}

export function saveSettings(settings: Settings): void {
  localStorage.setItem(KEY, JSON.stringify(normalizeSettings(settings)))
}

export interface Bookmark {
  surahId: number
  ayah: number
}

const BOOKMARK_KEY = 'beautiful-quran-bookmarks'

export function loadBookmarks(): Bookmark[] {
  try {
    const raw = localStorage.getItem(BOOKMARK_KEY)
    if (!raw) return []
    return JSON.parse(raw) as Bookmark[]
  } catch {
    return []
  }
}

export function saveBookmarks(bookmarks: Bookmark[]): void {
  localStorage.setItem(BOOKMARK_KEY, JSON.stringify(bookmarks))
}

export function toggleBookmark(bookmarks: Bookmark[], surahId: number, ayah: number): Bookmark[] {
  const exists = bookmarks.some((b) => b.surahId === surahId && b.ayah === ayah)
  if (exists) return bookmarks.filter((b) => !(b.surahId === surahId && b.ayah === ayah))
  return [...bookmarks, { surahId, ayah }]
}
