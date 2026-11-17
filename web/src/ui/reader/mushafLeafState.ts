import { useSyncExternalStore } from 'react'
import { appStore } from '../../store/appStore'
import type { DialLabel } from './mushafDial'

/**
 * What the leaf tells the chrome under it — the page dial and the transport
 * (Android hands these to `MushafReadingSheet` as lambdas). The leaf owns its
 * page; the dial reads it here and asks the leaf to turn through [seek].
 */
export interface MushafLeafSnapshot {
  ready: boolean
  /** The leaf in view, 1-based, on whatever the rule counts. */
  page: number
  pageCount: number
  /** A mushaf is bound on the right; a book of the translation on the left. */
  rightToLeft: boolean
  /** The leaf each chapter opens on, 1..114 in order. */
  chapterPages: readonly number[]
  /** True while the voice is reciting this chapter: the thumb steps back. */
  reciting: boolean
}

export interface MushafPlayTarget {
  surahId: number
  ayah: number
  /** A word to start from; absent plays the verse whole (its basmalah included). */
  position?: number
}

interface LeafActions {
  seek: (page: number, surahId?: number) => void
  label: (page: number, surahId?: number) => DialLabel | null
  playTarget: () => MushafPlayTarget | null
}

const EMPTY: MushafLeafSnapshot = {
  ready: false, page: 1, pageCount: 604, rightToLeft: true, chapterPages: [], reciting: false,
}

let snapshot = EMPTY
let actions: LeafActions | null = null
const listeners = new Set<() => void>()

export const mushafLeaf = {
  get: () => snapshot,
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  },
  publish(next: MushafLeafSnapshot) {
    if (
      next.ready === snapshot.ready && next.page === snapshot.page && next.pageCount === snapshot.pageCount &&
      next.rightToLeft === snapshot.rightToLeft && next.chapterPages === snapshot.chapterPages &&
      next.reciting === snapshot.reciting
    ) return
    snapshot = next
    for (const listener of listeners) listener()
  },
  /** The leaf registers what the chrome may ask of it; returns the release. */
  register(next: LeafActions) {
    actions = next
    return () => {
      if (actions !== next) return
      actions = null
      mushafLeaf.publish(EMPTY)
    }
  },
  seek(page: number, surahId?: number) {
    actions?.seek(page, surahId)
  },
  label(page: number, surahId?: number): DialLabel | null {
    return actions?.label(page, surahId) ?? null
  },
  playTarget(): MushafPlayTarget | null {
    return actions?.playTarget() ?? null
  },
}

export function useMushafLeaf(): MushafLeafSnapshot {
  return useSyncExternalStore(mushafLeaf.subscribe, mushafLeaf.get, mushafLeaf.get)
}

/**
 * Recite [target]: from its word, or from the verse; loading its chapter
 * first when another is open — Android `viewModel.load(startPlaybackAtAyah,
 * startPlaybackAtWord, keepContent = true)`.
 */
export function playMushafTarget(target: MushafPlayTarget) {
  const loaded = appStore.getSnapshot().content?.surah.id
  if (target.surahId !== loaded) {
    void appStore.openSurahAndPlay(target.surahId, target.ayah, target.position)
  } else if (target.position != null && target.position > 1) {
    void appStore.playFromWord(target.ayah, target.position)
  } else {
    // A leaf that opens its chapter carries the basmalah line: recite it.
    void appStore.playAyah(target.ayah, target.ayah === 1)
  }
}

/**
 * Play on a Mushaf leaf recites the leaf in view, from its first word — also
 * when the leaf belongs to a chapter the reader only turned to. A paused
 * verse the leaf carries resumes instead. Returns false to let the caller
 * resume (Android `mushafPlayTarget` → null).
 */
export function playMushafLeaf(): boolean {
  const target = mushafLeaf.playTarget()
  if (!target) return false
  appStore.setFollowEnabled(true)
  playMushafTarget(target)
  return true
}
