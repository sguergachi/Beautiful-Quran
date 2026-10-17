import type { Bookmark } from '../data/settings'

export interface BookmarkEdit {
  verse: Bookmark
  present: boolean
  revision: number
}

/** Saved verses and the desired membership outbox are one durable record. */
export interface BookmarkJournal {
  collectionId: string
  saved: Bookmark[]
  pending: BookmarkEdit[]
  revision: number
  deleting: boolean
}

export const emptyJournal = (): BookmarkJournal => ({
  collectionId: '', saved: [], pending: [], revision: 0, deleting: false,
})

export const verseKey = (verse: Bookmark): string => verse.surahId + ':' + verse.ayah
export const sameVerse = (a: Bookmark, b: Bookmark): boolean => verseKey(a) === verseKey(b)

function membership(verses: Bookmark[], verse: Bookmark, present: boolean): Bookmark[] {
  return [...verses.filter((v) => !sameVerse(v, verse)), ...(present ? [verse] : [])]
    .sort((a, b) => a.surahId - b.surahId || a.ayah - b.ayah)
}

export function editJournal(journal: BookmarkJournal, verse: Bookmark, present: boolean): BookmarkJournal {
  const revision = journal.revision + 1
  return {
    ...journal, revision,
    saved: membership(journal.saved, verse, present),
    pending: [...journal.pending.filter((edit) => !sameVerse(edit.verse, verse)),
      { verse, present, revision }],
  }
}

/** Remote deletion cannot erase an offline edit; a stale acknowledgement cannot erase a newer edit. */
export function mergeRemote(journal: BookmarkJournal, remote: Bookmark[]): BookmarkJournal {
  return { ...journal, saved: journal.pending.reduce(
    (saved, edit) => membership(saved, edit.verse, edit.present),
    [...new Map(remote.map((verse) => [verseKey(verse), verse])).values()]
      .sort((a, b) => a.surahId - b.surahId || a.ayah - b.ayah),
  ) }
}

export function acknowledge(journal: BookmarkJournal, edit: BookmarkEdit): BookmarkJournal {
  return { ...journal, pending: journal.pending.filter((pending) =>
    !(sameVerse(pending.verse, edit.verse) && pending.revision === edit.revision &&
      pending.present === edit.present)) }
}

/** Reject a damaged local record before making it an upload source. */
export function validJournal(value: BookmarkJournal): boolean {
  const validVerse = (verse: Bookmark) => Number.isInteger(verse?.surahId) &&
    verse.surahId >= 1 && verse.surahId <= 114 && Number.isInteger(verse.ayah) &&
    verse.ayah >= 1 && verse.ayah <= 286
  return value != null && typeof value.collectionId === 'string' &&
    Number.isSafeInteger(value.revision) && value.revision >= 0 &&
    typeof value.deleting === 'boolean' && Array.isArray(value.saved) &&
    value.saved.every(validVerse) && Array.isArray(value.pending) &&
    value.pending.every((edit) => validVerse(edit?.verse) && typeof edit.present === 'boolean' &&
      Number.isSafeInteger(edit.revision) && edit.revision > 0 && edit.revision <= value.revision) &&
    new Set(value.pending.map((edit) => verseKey(edit.verse))).size === value.pending.length
}
