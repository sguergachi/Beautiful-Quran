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

/**
 * A canonical word that shares a printed glyph with an earlier word has
 * `qcf_page` 0. It belongs on the owner's line, which [qcf_span_end] names.
 */
export function inheritSpannedPlacement<T extends {
  surah_id: number
  ayah_number: number
  position: number
  qcf_page: number
  qcf_line: number
  qcf_span_end: number
}>(rows: readonly T[]): T[] {
  const byVerse = new Map<string, T[]>()
  for (const row of rows) {
    const key = `${row.surah_id}:${row.ayah_number}`
    const list = byVerse.get(key) ?? []
    list.push(row)
    byVerse.set(key, list)
  }
  return rows.map((row) => {
    if (row.qcf_page >= 1 && row.qcf_line >= 1) return row
    const owner = byVerse.get(`${row.surah_id}:${row.ayah_number}`)?.find((other) =>
      other.qcf_page >= 1 &&
      other.qcf_line >= 1 &&
      other.position <= row.position &&
      row.position <= other.qcf_span_end,
    )
    return owner ? { ...row, qcf_page: owner.qcf_page, qcf_line: owner.qcf_line } : row
  })
}

/** The ornament belongs on the ayah's last word, never on the last word of a line. */
export function mushafTokenEndsAyah(position: number, lastPosition: number): boolean {
  return lastPosition > 0 && position === lastPosition
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
