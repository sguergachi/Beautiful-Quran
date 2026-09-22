/** One Hafs word placed on a printed Madinah line. */
export interface MushafToken {
  surahId: number
  ayah: number
  position: number
  /** Unicode Hafs. The page font's private-use glyphs need the QCF face. */
  arabic: string
}

export interface MushafLine {
  number: number
  tokens: MushafToken[]
}

export interface MushafPage {
  page: number
  lines: MushafLine[]
}

export const MUSHAF_PAGE_COUNT = 604
export const MUSHAF_LINES_PER_PAGE = 15

export interface MushafWordPlacement {
  surahId: number
  ayah: number
  position: number
  line: number
  arabic: string
}

/**
 * Group a page's words onto its printed lines. Empty lines stay in the
 * list so the 15-line grid does not collapse.
 */
export function buildMushafPage(page: number, words: readonly MushafWordPlacement[]): MushafPage {
  const byLine = new Map<number, MushafToken[]>()
  const sorted = words.slice().sort(
    (a, b) => a.line - b.line || a.surahId - b.surahId || a.ayah - b.ayah || a.position - b.position,
  )
  for (const word of sorted) {
    if (word.line < 1 || word.line > MUSHAF_LINES_PER_PAGE) continue
    const tokens = byLine.get(word.line) ?? []
    tokens.push({
      surahId: word.surahId,
      ayah: word.ayah,
      position: word.position,
      arabic: word.arabic,
    })
    byLine.set(word.line, tokens)
  }
  const lines: MushafLine[] = []
  for (let number = 1; number <= MUSHAF_LINES_PER_PAGE; number++) {
    lines.push({ number, tokens: byLine.get(number) ?? [] })
  }
  return { page, lines }
}

/** Ayahs on a page, in reading order, for the English leaf of that same page. */
export function pageAyahs(words: readonly MushafWordPlacement[]): { surahId: number; ayah: number }[] {
  const seen = new Set<string>()
  const out: { surahId: number; ayah: number }[] = []
  const sorted = words.slice().sort(
    (a, b) => a.line - b.line || a.surahId - b.surahId || a.ayah - b.ayah || a.position - b.position,
  )
  for (const word of sorted) {
    const key = `${word.surahId}:${word.ayah}`
    if (seen.has(key)) continue
    seen.add(key)
    out.push({ surahId: word.surahId, ayah: word.ayah })
  }
  return out
}
