import type {
  EnglishLeafText,
  ReadingLayout,
  ReadingMode,
  Settings,
  VerseNumberScript,
} from './settings'
import type { WordSearchSources } from '../domain/WordSearch'

export const MUSHAF_VIEW_MODES: ReadingMode[] = ['arabic_only', 'english_only']

/** Navigation preferences cannot change a kept leaf's ink or paper. */
export function readingAppearanceKey(settings: Settings): string {
  return JSON.stringify({ ...settings, lastSurah: 0, lastAyah: 0 })
}

/** Bilingual has no printed leaf, so entering mushaf from it lands on Arabic. */
export function applyReadingLayout(
  settings: Settings,
  layout: ReadingLayout,
): Partial<Settings> {
  if (layout === 'mushaf' && !MUSHAF_VIEW_MODES.includes(settings.readingMode)) {
    return { readingLayout: layout, readingMode: 'arabic_only' }
  }
  return { readingLayout: layout }
}

export function applyReadingMode(mode: ReadingMode): Partial<Settings> {
  return { readingMode: mode }
}

/** Which English the English Mushaf leaf may be set from (Android ENGLISH_LEAF_TEXTS). */
export const ENGLISH_LEAF_TEXTS: EnglishLeafText[] = ['translation', 'gloss']

/** Printed mushaf has no annotation margin, no ayah rail and no size dial. */
export function showsScrollChrome(layout: ReadingLayout): boolean {
  return layout === 'scroll'
}

/** The faded leaf only shows a sample ḥāshiya on a scroll page with notes on. */
export function showsPreviewAnnotation(layout: ReadingLayout, annotationsEnabled: boolean): boolean {
  return showsScrollChrome(layout) && annotationsEnabled
}

/**
 * Verse-mark script is offered wherever Western digits are a real option:
 * every scroll view, and the English leaf. The Arabic leaf's marks are drawn
 * by the page face and cannot be restyled.
 */
export function showsVerseNumberChrome(layout: ReadingLayout, mode: ReadingMode): boolean {
  return showsScrollChrome(layout) || mode === 'english_only'
}

/** Only the English leaf asks which English to set (Android CustomizeScreen). */
export function showsEnglishLeafChrome(layout: ReadingLayout, mode: ReadingMode): boolean {
  return layout === 'mushaf' && mode === 'english_only'
}

/** Word gloss lives under Arabic tiles — only the bilingual scroll view. */
export function showsWordGlossChrome(mode: ReadingMode): boolean {
  return mode === 'arabic_english'
}

export function showsPreviewWordGloss(
  mode: ReadingMode,
  showWordGloss: boolean,
): boolean {
  return showsWordGlossChrome(mode) && showWordGloss
}

/** Web is the Scroll reader, so its search always uses the timed English gloss. */
export function wordSearchSources(): WordSearchSources {
  return { arabic: false, wordGloss: true, transliteration: false, verseTranslation: false }
}

export function themeLabel(mode: Settings['themeMode']): string {
  switch (mode) {
    case 'system':
      return 'System'
    case 'light':
      return 'Paper'
    case 'dark':
      return 'Nightfall'
    case 'royal_green':
      return 'Royal green'
  }
}

export function customizeSummary(settings: Settings): string {
  const theme = themeLabel(settings.themeMode)
  if (settings.readingLayout === 'mushaf') {
    const hand = settings.readingMode === 'english_only' ? 'English' : 'Arabic'
    return `Mushaf · ${hand} · ${theme}`
  }
  const view =
    settings.readingMode === 'english_only'
      ? 'English'
      : settings.readingMode === 'arabic_only'
        ? 'Arabic'
        : 'Arabic & English'
  const verse =
    settings.verseNumberScript === 'arabic'
      ? 'Arabic verse marks'
      : 'English verse marks'
  return `${view} · ${verse} · ${theme}`
}

export function usesArabicIndicVerseMarks(script: VerseNumberScript): boolean {
  return script === 'arabic'
}
