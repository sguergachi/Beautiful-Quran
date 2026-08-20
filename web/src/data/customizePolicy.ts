import type {
  ReadingMode,
  Settings,
  VerseNumberScript,
} from './settings'

export function applyReadingMode(mode: ReadingMode): Partial<Settings> {
  return { readingMode: mode }
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
