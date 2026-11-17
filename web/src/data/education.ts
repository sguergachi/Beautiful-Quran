/**
 * One-shot contextual feature lessons — Android EducationMoment parity.
 *
 * Dismissal is stored separately from the guides master gate so Replay can
 * rearm lessons without rewriting every Settings field.
 */

export type EducationMoment = 'bookmark_note' | 'ayah_rail' | 'word_hold' | 'pin_chapter'

const DISMISS_KEYS: Record<EducationMoment, string> = {
  word_hold: 'beautiful-quran-education-word-hold-v1',
  bookmark_note: 'beautiful-quran-education-bookmark-note-v1',
  ayah_rail: 'beautiful-quran-education-ayah-rail-v1',
  pin_chapter: 'beautiful-quran-education-pin-chapter-v1',
}

/** Fallback when localStorage is missing (Vitest node) or blocked. */
const memory = new Map<string, string>()

function storageGet(key: string): string | null {
  try {
    if (typeof localStorage !== 'undefined') return localStorage.getItem(key)
  } catch {
    /* private mode */
  }
  return memory.get(key) ?? null
}

function storageSet(key: string, value: string): void {
  memory.set(key, value)
  try {
    if (typeof localStorage !== 'undefined') localStorage.setItem(key, value)
  } catch {
    /* private mode */
  }
}

function storageRemove(key: string): void {
  memory.delete(key)
  try {
    if (typeof localStorage !== 'undefined') localStorage.removeItem(key)
  } catch {
    /* private mode */
  }
}

export function isEducationDismissed(moment: EducationMoment): boolean {
  return storageGet(DISMISS_KEYS[moment]) === '1'
}

export function dismissEducation(moment: EducationMoment): void {
  storageSet(DISMISS_KEYS[moment], '1')
}

/** Clears every dismiss flag so lessons can fire on their next eligible moment. */
export function rearmEducation(): void {
  for (const key of Object.values(DISMISS_KEYS)) {
    storageRemove(key)
  }
}

/**
 * Whether a settled chapter opening should teach its live ayah rail.
 * Lessons are developer-gated, as Android `ReaderViewModel.shouldShowAyahRailTip`.
 */
export function shouldShowAyahRailTip(opts: {
  educationGuidesEnabled: boolean
  developerMode: boolean
}): boolean {
  return (
    opts.developerMode &&
    opts.educationGuidesEnabled &&
    !isEducationDismissed('ayah_rail')
  )
}

/**
 * On a fresh bookmark, teach that holding the saved ribbon writes a note —
 * Android `toggleBookmark().showNoteTip`.
 */
export function shouldShowBookmarkTip(opts: {
  educationGuidesEnabled: boolean
  developerMode: boolean
  annotationsEnabled: boolean
  nowBookmarked: boolean
}): boolean {
  return (
    opts.nowBookmarked &&
    opts.developerMode &&
    opts.educationGuidesEnabled &&
    opts.annotationsEnabled &&
    !isEducationDismissed('bookmark_note')
  )
}
