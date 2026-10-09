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

export type MushafHeadFigures = {
  /** Left figure: Western digits, or the single script's own figure. */
  left: string
  /** lang tag — the Arabic-Indic figure is set in Hafs, untracked. */
  leftLang: 'ar' | null
  /** The chapter, centred; at the right with one script. */
  center: string
  /** Right figure: Arabic-Indic, only with both scripts. */
  right: string | null
}

/**
 * Running-head figures: Western at the left, the chapter centred and
 * Arabic-Indic at the right; one script puts its figure at the left and the
 * chapter at the right. Matching Android `MushafRunningHead`.
 */
export function mushafHeadFigures(
  page: number,
  script: 'both' | 'arabic' | 'english',
  chapter: string,
): MushafHeadFigures {
  const folio = pageFolioLayout(page, script)
  if (folio.trailing == null) {
    return {
      left: folio.leading,
      leftLang: script === 'arabic' ? 'ar' : null,
      center: chapter,
      right: null,
    }
  }
  return {
    left: String(page),
    leftLang: null,
    center: chapter,
    right: folio.trailing,
  }
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
