import type { BrushCircleStyle } from '../ui/kit/brushMark'
import { BRUSH_CIRCLE_STYLE_IDS } from '../ui/kit/brushMark'

export type ThemeMode = 'system' | 'light' | 'dark' | 'royal_green'
export type ReadingMode = 'arabic_english' | 'english_only' | 'arabic_only'
/** Android `ReadingLayout`. A printed leaf, or the scrolling chapter. */
export type ReadingLayout = 'scroll' | 'mushaf'
export type PagePresentation = 'facing' | 'single'
export type VerseNumberScript = 'arabic' | 'english'
export type PageNumberScript = 'both' | 'arabic' | 'english'
export type AyahSelectorSide = 'left' | 'right'
/** Android `EnglishLeafText`: what the English Mushaf leaf prints. */
export type EnglishLeafText = 'translation' | 'gloss'

/** Percent. The print elongates letters to fill a line; this is the web's stand-in. */
export type MushafGlyphWiden = 0 | 4 | 6 | 8
export const MUSHAF_GLYPH_WIDENS: MushafGlyphWiden[] = [0, 4, 6, 8]

/** Seconds of silence after each verse (Settings → Playback). Android parity. */
export const VERSE_GAP_CHOICES = [0, 1, 2, 3, 5]
export const MAX_VERSE_GAP_SECONDS = 5
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
  /** Voices kept on Settings; Android starts with its original seven. */
  favoriteReciterIds: number[]
  /** Chapters held at the top of Chapters, on the green band (Android parity). */
  pinnedSurahIds: number[]
  fontScale: number
  readingMode: ReadingMode
  readingLayout: ReadingLayout
  /** Facing pages on larger screens, or one enlarged page. */
  pagePresentation: PagePresentation
  verseNumberScript: VerseNumberScript
  pageNumberScript: PageNumberScript
  showWordGloss: boolean
  showTransliteration: boolean
  showTranslation: boolean
  /** Android `englishLeafText`: the English Mushaf leaf's text source. */
  englishLeafText: EnglishLeafText
  /**
   * Android `annotationsEnabled`: verse notes and their entry gesture.
   * Off hides them; stored writing is never deleted.
   */
  annotationsEnabled: boolean
  /** Paper page-turn and cover-open sound effects, on until the reader turns them off. */
  soundEffectsEnabled: boolean
  themeMode: ThemeMode
  ayahSelectorSide: AyahSelectorSide
  /** Continue Listening — last verse actually recited (not mere open/scroll).
   * The only persisted place, as on Android: reading never moves it. */
  lastSurah: number
  lastAyah: number
  playbackSpeed: number
  /** Silence after each verse before the next begins, in seconds. 0 is none. */
  verseGapSeconds: number
  /** Repeating a whole chapter opens with the basmalah only the first time round. */
  skipRepeatedBasmalah: boolean
  /** Reveals developer tools (e.g. the Ornaments Lab). Off by default. */
  developerMode: boolean
  /**
   * Android `educationGuidesEnabled`: developer-only gate for the contextual
   * feature lessons, off until their visual language is approved for readers.
   * Enabling rearms every lesson.
   */
  educationGuidesEnabled: boolean
  /**
   * Use Gapless-5 (HTML5 + Web Audio) for verse joins. On by default; the
   * developer toggle can fall back to dual-`<audio>` handoff for A/B.
   */
  gapless5Playback: boolean
  /**
   * Mushaf: the most a loose line's letters may be widened, in percent,
   * before space is added between its words. 0 turns widening off.
   */
  mushafGlyphWiden: MushafGlyphWiden
  /** Developer-only: ink-brush circle style around selected enums. */
  brushCircleStyle: BrushCircleStyle
}

const DEFAULTS: Settings = {
  reciterId: 1,
  favoriteReciterIds: [1, 2, 3, 4, 5, 6, 7],
  pinnedSurahIds: [],
  fontScale: 1,
  readingMode: 'arabic_english',
  readingLayout: 'scroll',
  pagePresentation: 'facing',
  verseNumberScript: 'arabic',
  pageNumberScript: 'both',
  showWordGloss: true,
  showTransliteration: false,
  showTranslation: false,
  englishLeafText: 'translation',
  annotationsEnabled: true,
  soundEffectsEnabled: true,
  themeMode: 'system',
  ayahSelectorSide: 'left',
  lastSurah: 0,
  lastAyah: 1,
  playbackSpeed: 1,
  verseGapSeconds: 0,
  skipRepeatedBasmalah: true,
  developerMode: false,
  educationGuidesEnabled: false,
  gapless5Playback: true,
  mushafGlyphWiden: 6,
  brushCircleStyle: 'baseline',
}

function clampBrushStyle(value: unknown): BrushCircleStyle {
  return typeof value === 'string' &&
    (BRUSH_CIRCLE_STYLE_IDS as string[]).includes(value)
    ? (value as BrushCircleStyle)
    : DEFAULTS.brushCircleStyle
}

const KEY = 'beautiful-quran-settings'

/** The web's retired default; not one of Android's stops. */
const RETIRED_DEFAULT_FONT_SCALE = 0.95

function clampFontScale(value: unknown): number {
  const raw = typeof value === 'number' && Number.isFinite(value) ? value : DEFAULTS.fontScale
  // Every reader was saved at the old 0.95 default whether or not they touched
  // the dial; it is not a stop, so it lands on Android's 1.0 default.
  const n = Math.abs(raw - RETIRED_DEFAULT_FONT_SCALE) < 1e-6 ? DEFAULTS.fontScale : raw
  return Math.min(FONT_SCALE_MAX, Math.max(FONT_SCALE_MIN, n))
}

/** Whole seconds, 0..[MAX_VERSE_GAP_SECONDS]; anything else is no gap. */
function clampVerseGap(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return DEFAULTS.verseGapSeconds
  return Math.min(MAX_VERSE_GAP_SECONDS, Math.max(0, Math.round(value)))
}

/** A stored id list, unique positive integers only; a broken list falls back. */
function cleanIds(value: unknown, fallback: number[]): number[] {
  return Array.isArray(value)
    ? [...new Set(value.filter((id) => Number.isInteger(id) && id > 0))]
    : [...fallback]
}

/**
 * Choose a voice. A chosen voice is always a favourite, so Settings' short
 * list never hides the reciter that is actually playing (Android parity).
 */
export function reciterChoice(
  settings: Pick<Settings, 'favoriteReciterIds'>,
  reciterId: number,
): Pick<Settings, 'reciterId' | 'favoriteReciterIds'> {
  const favorites = settings.favoriteReciterIds
  return {
    reciterId,
    favoriteReciterIds: favorites.includes(reciterId) ? favorites : [...favorites, reciterId],
  }
}

export function normalizeSettings(partial: Partial<Settings> = {}): Settings {
  // Drop the retired separate reading place: Android persists only the
  // last-heard verse, so a stored lastRead* must not survive a load.
  const clean = { ...partial } as Record<string, unknown>
  delete clean.lastReadSurah
  delete clean.lastReadAyah
  // Retired developer A/B (Android removed `colorSystem` too).
  delete clean.colorSystem
  // Android retired its Chapters bookmark experiment (homeBookmarkStyle):
  // the top-bound ribbon is the one entry, so the stored choice goes too.
  delete clean.homeBookmarkStyle
  const developerMode = Boolean(partial.developerMode ?? DEFAULTS.developerMode)
  return {
    ...DEFAULTS,
    ...(clean as Partial<Settings>),
    favoriteReciterIds: cleanIds(partial.favoriteReciterIds, DEFAULTS.favoriteReciterIds),
    pinnedSurahIds: cleanIds(partial.pinnedSurahIds, DEFAULTS.pinnedSurahIds),
    fontScale: clampFontScale(partial.fontScale ?? DEFAULTS.fontScale),
    verseGapSeconds: clampVerseGap(partial.verseGapSeconds),
    skipRepeatedBasmalah: partial.skipRepeatedBasmalah !== false,
    pagePresentation: partial.pagePresentation === 'single' ? 'single' : 'facing',
    developerMode,
    englishLeafText: partial.englishLeafText === 'gloss' ? 'gloss' : 'translation',
    annotationsEnabled: Boolean(partial.annotationsEnabled ?? DEFAULTS.annotationsEnabled),
    soundEffectsEnabled: partial.soundEffectsEnabled !== false,
    // A developer toggle. The web used to default it on and saved that for
    // every reader, so a stored `true` only counts while developer mode is on.
    educationGuidesEnabled:
      developerMode &&
      Boolean(partial.educationGuidesEnabled ?? DEFAULTS.educationGuidesEnabled),
    gapless5Playback: Boolean(
      partial.gapless5Playback ?? DEFAULTS.gapless5Playback,
    ),
    mushafGlyphWiden: MUSHAF_GLYPH_WIDENS.includes(partial.mushafGlyphWiden as MushafGlyphWiden)
      ? (partial.mushafGlyphWiden as MushafGlyphWiden)
      : DEFAULTS.mushafGlyphWiden,
    brushCircleStyle: clampBrushStyle(
      partial.brushCircleStyle ?? DEFAULTS.brushCircleStyle,
    ),
    readingMode: READING_MODES.includes(partial.readingMode as ReadingMode)
      ? (partial.readingMode as ReadingMode)
      : DEFAULTS.readingMode,
    readingLayout: partial.readingLayout === 'mushaf' ? 'mushaf' : 'scroll',
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
    if (!raw) return normalizeSettings()
    return normalizeSettings(JSON.parse(raw) as Partial<Settings>)
  } catch {
    return normalizeSettings()
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
