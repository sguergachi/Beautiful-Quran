import { foldEnglish } from '../../domain/EnglishTypography'
import type { Ayah, Surah } from '../../data/models'
import { normalizeArabicForSearch } from '../../domain/WordSearch'

export type BookmarkedVerse = { surah: Surah; ayah: Ayah }
export type BookmarkSection = { surah: Surah; verses: BookmarkedVerse[] }

export const BOOKMARK_SECTION_PREVIEW_LIMIT = 5

export function isBookmarkSectionCollapsed(
  surahId: number,
  collapsedSurahs: Set<number>,
  searching: boolean,
): boolean {
  return !searching && collapsedSurahs.has(surahId)
}

export function visibleBookmarkVerses(
  verses: BookmarkedVerse[],
  expanded: boolean,
  searching: boolean,
): BookmarkedVerse[] {
  return expanded || searching
    ? verses
    : verses.slice(0, BOOKMARK_SECTION_PREVIEW_LIMIT)
}

export function hiddenBookmarkCount(
  verses: BookmarkedVerse[],
  expanded: boolean,
  searching: boolean,
): number {
  return expanded || searching
    ? 0
    : Math.max(0, verses.length - BOOKMARK_SECTION_PREVIEW_LIMIT)
}

export function bookmarkDisclosureLabel(
  hiddenCount: number,
  expanded: boolean,
): string {
  if (expanded) return 'Show fewer bookmarks'
  return hiddenCount === 1
    ? 'Show 1 more bookmark'
    : `Show ${hiddenCount} more bookmarks`
}

export function filterBookmarkSections(
  verses: BookmarkedVerse[],
  query: string,
): BookmarkSection[] {
  const needle = query.trim()
  const lowerNeedle = needle.toLocaleLowerCase()
  const arabicNeedle = normalizeArabicForSearch(needle)
  const matches = needle
    ? verses.filter(({ surah, ayah }) =>
        foldEnglish(surah.nameTransliteration).toLocaleLowerCase().includes(lowerNeedle) ||
        surah.nameTranslation.toLocaleLowerCase().includes(lowerNeedle) ||
        surah.nameArabic.includes(needle) ||
        String(surah.id) === needle ||
        foldEnglish(ayah.translation).toLocaleLowerCase().includes(lowerNeedle) ||
        (arabicNeedle.length > 0 &&
          normalizeArabicForSearch(ayah.text).includes(arabicNeedle)) ||
        `${surah.id}:${ayah.number}` === needle,
      )
    : verses

  const grouped = new Map<number, BookmarkSection>()
  for (const verse of matches) {
    const section = grouped.get(verse.surah.id)
    if (section) section.verses.push(verse)
    else grouped.set(verse.surah.id, { surah: verse.surah, verses: [verse] })
  }
  return [...grouped.values()]
}
