/** Map Western digits to Arabic-Indic (٠–٩). */
export function toArabicIndic(n: number): string {
  return String(n).replace(/\d/g, (d) => '٠١٢٣٤٥٦٧٨٩'[Number(d)]!)
}

/**
 * Verse / page digit form for the reader. English-only uses Western digits;
 * Arabic and gloss modes use Arabic-Indic — matching Android `AyahNumberMark`.
 */
export function formatReaderDigits(n: number, useArabicIndicDigits: boolean): string {
  return useArabicIndicDigits ? toArabicIndic(n) : String(n)
}

/**
 * Ornate ayah brackets. U+FD3E/U+FD3F are Bidi_Mirrored.
 * Arabic stays `﴿N﴾` in RTL. English is isolated LTR and emits `﴾N﴿` so
 * mirroring paints `﴿N﴾` (cups face the digits, like (1) not )1().
 * Characters are glued with WORD JOINER so the mark never wraps mid-unit
 * (mirrors Android `formatAyahNumberMark`).
 */
export function formatAyahNumberMark(n: number, useArabicIndicDigits: boolean): string {
  const digits = formatReaderDigits(n, useArabicIndicDigits)
  const raw = useArabicIndicDigits ? `﴿${digits}﴾` : `\u2066﴾${digits}﴿\u2069`
  return [...raw].join('\u2060')
}

export type PageFolioLayout = {
  leading: string
  trailing: string | null
  centered: boolean
}

/** Which folio figures a page break paints, matching Android `pageFolioLayout`. */
export function pageFolioLayout(
  page: number,
  script: 'both' | 'arabic' | 'english',
): PageFolioLayout {
  const western = String(page)
  const arabic = toArabicIndic(page)
  if (script === 'both') return { leading: western, trailing: arabic, centered: false }
  if (script === 'english') return { leading: western, trailing: null, centered: true }
  return { leading: arabic, trailing: null, centered: true }
}
