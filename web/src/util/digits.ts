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

/** Ornate ayah brackets follow the surrounding line's writing direction. */
export function formatAyahNumberMark(n: number, useArabicIndicDigits: boolean): string {
  const digits = formatReaderDigits(n, useArabicIndicDigits)
  return useArabicIndicDigits ? `﴿${digits}﴾` : `﴾${digits}﴿`
}
