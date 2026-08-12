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
 * Ornate ayah brackets follow the surrounding line's writing direction.
 * Characters are glued with WORD JOINER so the mark never wraps mid-unit
 * (mirrors Android `formatAyahNumberMark`).
 */
export function formatAyahNumberMark(n: number, useArabicIndicDigits: boolean): string {
  const digits = formatReaderDigits(n, useArabicIndicDigits)
  const raw = useArabicIndicDigits ? `﴿${digits}﴾` : `﴾${digits}﴿`
  return [...raw].join('\u2060')
}
