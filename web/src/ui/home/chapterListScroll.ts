/** Where the chapter list was left (Android `chapterListAnchor`), so a
 * reload opens on the chapter last under the thumb. Per device, in px. */
const KEY = 'beautiful-quran-chapter-list-scroll'

export function readChapterListScroll(): number {
  try {
    const value = Number(localStorage.getItem(KEY))
    return Number.isFinite(value) && value > 0 ? value : 0
  } catch {
    return 0
  }
}

export function writeChapterListScroll(scrollTop: number): void {
  try {
    localStorage.setItem(KEY, String(Math.max(0, Math.round(scrollTop))))
  } catch {
    // Private mode / blocked storage: the list simply opens at the top.
  }
}
