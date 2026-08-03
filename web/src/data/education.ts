/**
 * One-shot contextual feature lessons — Android EducationMoment parity.
 *
 * Dismissal is stored separately from the guides master gate so Replay can
 * rearm lessons without rewriting every Settings field.
 */

export type EducationMoment = 'bookmark_note' | 'ayah_rail'

const DISMISS_KEYS: Record<EducationMoment, string> = {
  bookmark_note: 'beautiful-quran-education-bookmark-note-v1',
  ayah_rail: 'beautiful-quran-education-ayah-rail-v1',
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

/** Whether a settled chapter opening should teach its live ayah rail. */
export function shouldShowAyahRailTip(opts: {
  developerMode: boolean
  educationGuidesEnabled: boolean
}): boolean {
  return (
    opts.developerMode &&
    opts.educationGuidesEnabled &&
    !isEducationDismissed('ayah_rail')
  )
}

/**
 * Whether the first bookmark add should teach the note gesture.
 *
 * Android also requires annotationsEnabled; web annotations are still pending,
 * so the tip is offered whenever guides are gated on — same discoverability
 * surface once notes land on the ribbon.
 */
export function shouldShowBookmarkNoteTip(opts: {
  developerMode: boolean
  educationGuidesEnabled: boolean
  nowBookmarked: boolean
}): boolean {
  return (
    opts.nowBookmarked &&
    opts.developerMode &&
    opts.educationGuidesEnabled &&
    !isEducationDismissed('bookmark_note')
  )
}
