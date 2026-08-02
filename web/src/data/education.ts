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

export function isEducationDismissed(moment: EducationMoment): boolean {
  try {
    return localStorage.getItem(DISMISS_KEYS[moment]) === '1'
  } catch {
    return false
  }
}

export function dismissEducation(moment: EducationMoment): void {
  try {
    localStorage.setItem(DISMISS_KEYS[moment], '1')
  } catch {
    /* private mode */
  }
}

/** Clears every dismiss flag so lessons can fire on their next eligible moment. */
export function rearmEducation(): void {
  try {
    for (const key of Object.values(DISMISS_KEYS)) {
      localStorage.removeItem(key)
    }
  } catch {
    /* private mode */
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
