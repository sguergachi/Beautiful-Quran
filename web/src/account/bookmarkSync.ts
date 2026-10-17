import type { Bookmark } from '../data/settings'
import { acknowledge, editJournal, mergeRemote, sameVerse, type BookmarkEdit, type BookmarkJournal } from './bookmarkJournal'

export interface BookmarkRemote {
  collection(): Promise<string>
  bookmarks(id: string): Promise<Bookmark[]>
  write(id: string, edit: BookmarkEdit): Promise<void>
}

/** Neither an accepted mutation nor a partial snapshot clears an unconfirmed local edit. */
export async function syncBookmarks(
  api: BookmarkRemote,
  read: () => BookmarkJournal,
  save: (journal: BookmarkJournal) => void,
): Promise<void> {
  if (!read().collectionId) {
    const collectionId = await api.collection()
    save({ ...read(), collectionId })
  }
  const id = read().collectionId
  const remote = await api.bookmarks(id)
  let journal = mergeRemote(read(), remote)
  if (journal.deleting) {
    const verses = [...new Map([...remote, ...journal.saved].map((v) => [v.surahId + ':' + v.ayah, v])).values()]
    for (const verse of verses) journal = editJournal(journal, verse, false)
  }
  save(journal)
  const edits = journal.pending
  for (const edit of edits) {
    if (!read().pending.some((pending) =>
      sameVerse(pending.verse, edit.verse) && pending.revision === edit.revision)) continue
    if (remote.some((v) => sameVerse(v, edit.verse)) !== edit.present) await api.write(id, edit)
  }
  const verified = await api.bookmarks(id)
  journal = read()
  for (const edit of edits) {
    if (verified.some((v) => sameVerse(v, edit.verse)) === edit.present)
      journal = acknowledge(journal, edit)
  }
  journal = mergeRemote(journal, verified)
  save({ ...journal, deleting: journal.deleting && (verified.length > 0 || journal.pending.length > 0) })
}
