import type {
  ReadingLayout,
  ReadingMode,
  Settings,
  VerseNumberScript,
} from './settings'

/** Mushaf is a printed Arabic page — never English, never bilingual. */
export function applyReadingLayout(
  settings: Settings,
  layout: ReadingLayout,
): Partial<Settings> {
  if (layout === 'mushaf') {
    return { readingLayout: layout, readingMode: 'arabic_only' }
  }
  return { readingLayout: layout }
}

/** View-mode changes while mushaf is on are ignored so the leaf stays Arabic. */
export function applyReadingMode(
  settings: Settings,
  mode: ReadingMode,
): Partial<Settings> {
  if (settings.readingLayout === 'mushaf' && mode !== 'arabic_only') {
    return {}
  }
  return { readingMode: mode }
}

/** Printed mushaf has no annotation margin and no ayah rail. */
export function showsScrollChrome(layout: ReadingLayout): boolean {
  return layout === 'scroll'
}

/** Collapsed ayah rail lives on the scroll leaf — never on a printed page. */
export function showsPreviewAyahRail(layout: ReadingLayout): boolean {
  return showsScrollChrome(layout)
}

/** Word gloss lives under Arabic tiles — only the bilingual scroll view. */
export function showsWordGlossChrome(
  layout: ReadingLayout,
  mode: ReadingMode,
): boolean {
  return showsScrollChrome(layout) && mode === 'arabic_english'
}

export function showsPreviewWordGloss(
  layout: ReadingLayout,
  mode: ReadingMode,
  showWordGloss: boolean,
): boolean {
  return showsWordGlossChrome(layout, mode) && showWordGloss
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
  if (settings.readingLayout === 'mushaf') return `Mushaf · ${theme}`
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
