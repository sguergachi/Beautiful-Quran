/**
 * Gather and text share — Android `share/AyahRef.kt`, `ShareUx.kt`,
 * `VerseTextComposer.kt`, and `shareFooterCopy`.
 */

export interface AyahRef {
  surahId: number
  ayah: number
}

export const SHARE_SELECTION_MAX = 20

export function sameAyah(a: AyahRef, b: AyahRef): boolean {
  return a.surahId === b.surahId && a.ayah === b.ayah
}

/** Toggle in tap order. A second tap drops the verse; the rest renumber. */
export function toggleGatheredAyah(
  selection: readonly AyahRef[],
  ref: AyahRef,
  max = SHARE_SELECTION_MAX,
): AyahRef[] {
  const index = selection.findIndex((item) => sameAyah(item, ref))
  if (index >= 0) return selection.filter((_, i) => i !== index)
  if (selection.length >= max) return selection.slice()
  return selection.concat(ref)
}

/** 1-based ordinals keyed by `surah:ayah`. */
export function gatherOrdinals(selection: readonly AyahRef[]): Map<string, number> {
  const out = new Map<string, number>()
  selection.forEach((ref, i) => out.set(`${ref.surahId}:${ref.ayah}`, i + 1))
  return out
}

export function ayahKey(surahId: number, ayah: number): string {
  return `${surahId}:${ayah}`
}

export type ShareUxAction =
  | { type: 'enter'; ref: AyahRef }
  | { type: 'toggle'; ref: AyahRef }
  | { type: 'exit' }
  | { type: 'none' }

export function onMarkTap(gathering: boolean, ref: AyahRef): ShareUxAction {
  return gathering ? { type: 'toggle', ref } : { type: 'enter', ref }
}

/** Idle verse body does not enter gather. Once gathering, a tap anywhere toggles. */
export function onVerseTap(gathering: boolean, ref: AyahRef): ShareUxAction {
  return gathering ? { type: 'toggle', ref } : { type: 'none' }
}

export function onLeaveReaderSheet(gathering: boolean): ShareUxAction {
  return gathering ? { type: 'exit' } : { type: 'none' }
}

export interface ShareVerse {
  arabic: string
  translation: string
  surahNameTransliteration: string
  surahId: number
  ayah: number
}

export function shareReference(verse: ShareVerse): string {
  const name = verse.surahNameTransliteration.trim() || `Surah ${verse.surahId}`
  return `${name} ${verse.surahId}:${verse.ayah}`
}

/** Arabic, optional translation, then a reference footer. Verses separated by a blank line. */
export function composeShareText(verses: readonly ShareVerse[], includeTranslation = true): string {
  if (verses.length === 0) return ''
  return verses
    .map((verse) => {
      const parts = [verse.arabic.trim()]
      if (includeTranslation && verse.translation.trim()) parts.push(verse.translation.trim())
      parts.push(shareReference(verse))
      return parts.join('\n\n')
    })
    .join('\n\n')
}

export interface ShareFooterCopy {
  chapter: string
  verses: string
}

export function shareFooterCopy(
  verses: readonly { surahId: number; ayah: number; surahName: string }[],
): ShareFooterCopy {
  if (verses.length === 0) return { chapter: '', verses: '' }
  const first = verses[0]!
  const last = verses[verses.length - 1]!
  const name = (line: { surahId: number; surahName: string }) =>
    line.surahName.trim() || `Surah ${line.surahId}`
  const chapter =
    first.surahId === last.surahId ? name(first) : `${name(first)} · ${name(last)}`
  const verseLine =
    first.surahId === last.surahId
      ? first.ayah === last.ayah
        ? `${first.surahId}:${first.ayah}`
        : `${first.surahId}:${first.ayah}–${last.ayah}`
      : `${first.surahId}:${first.ayah} · ${last.surahId}:${last.ayah}`
  return { chapter, verses: verseLine }
}
