/**
 * Live "Back to …" store: the pure [createBackToPlaceStore] wired to the
 * page. Lives outside the reader (like Android's MainActivity owner) so the
 * capsule survives leaving the reader for Chapters and coming back.
 */
import { useSyncExternalStore } from 'react'
import { appStore } from '../../store/appStore'
import {
  createBackToPlaceStore,
  type BackToPlaceTarget,
} from './backToPlace'

/** Keys that move the reader by hand (scroll / turn a leaf). */
const MOVE_KEYS = new Set([
  'ArrowUp',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'PageUp',
  'PageDown',
  'Home',
  'End',
  ' ',
])

function listenForUserMoves(onMove: () => void): () => void {
  if (typeof window === 'undefined') return () => {}
  const options: AddEventListenerOptions = { capture: true, passive: true }
  const onKey = (event: KeyboardEvent) => {
    if (MOVE_KEYS.has(event.key)) onMove()
  }
  // A move across the paper stack counts too — Android arms on any
  // stackPosition change past the settle.
  const baseline = appStore.getSnapshot().sheet
  const unsubscribe = appStore.subscribe(() => {
    if (appStore.getSnapshot().sheet !== baseline) onMove()
  })
  window.addEventListener('wheel', onMove, options)
  window.addEventListener('touchmove', onMove, options)
  window.addEventListener('keydown', onKey, options)
  return () => {
    unsubscribe()
    window.removeEventListener('wheel', onMove, options)
    window.removeEventListener('touchmove', onMove, options)
    window.removeEventListener('keydown', onKey, options)
  }
}

export const backToPlace = createBackToPlaceStore({
  now: () => Date.now(),
  setTimer: (fn, ms) => setTimeout(fn, ms),
  clearTimer: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
  listenForUserMoves,
})

/** The place a concordance jump left, or null. */
export function useBackToPlaceTarget(): BackToPlaceTarget | null {
  return useSyncExternalStore(
    backToPlace.subscribe,
    () => backToPlace.getSnapshot().target,
    () => null,
  )
}

/**
 * True while the "Back to …" capsule is showing. The reader hides its
 * return-to-ayah roundel then so the two never compete (Android
 * `showReturnToAyah = … && !rootReturnVisible`).
 */
export function useBackToPlaceVisible(): boolean {
  return useBackToPlaceTarget() != null
}

/** Android `returnFromConcordanceJump`: back to the verse the hold began on. */
export function returnToPlace(): void {
  const target = backToPlace.takeTarget()
  if (!target) return
  appStore.openSurah(target.surahId, target.ayah)
}
