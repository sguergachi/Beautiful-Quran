import { useSyncExternalStore } from 'react'
import { COVER_LAYER, READER_LAYER, type StackLayer } from './stack'

/**
 * Desktop lays the paper stack open as a book: Chapters (and whatever sheet
 * covers it — Bookmarks, Settings) on the verso, the Reader on the recto.
 * Phones and narrow windows keep the one-sheet-at-a-time deck.
 */
export const BOOK_SPREAD_QUERY = '(min-width: 1100px) and (min-height: 600px)'

function subscribe(onChange: () => void): () => void {
  const query = window.matchMedia(BOOK_SPREAD_QUERY)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}

export function useBookSpread(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(BOOK_SPREAD_QUERY).matches,
    () => false,
  )
}

/**
 * The layer each page believes the stack is on. In a spread the two pages
 * are both on top: an open chapter always owns the recto, and Chapters owns
 * the verso until Bookmarks or Settings is laid over it.
 */
export function spreadLayers(
  spread: boolean,
  stack: StackLayer,
  hasReader: boolean,
  leaves = false,
): { home: StackLayer; reader: StackLayer } {
  if (!spread || !hasReader) return { home: stack, reader: stack }
  return {
    // Mushaf layout fills both pages with facing leaves, so Chapters keeps
    // its real layer there: laid over the verso at layer 0, gone at layer 1.
    home: !leaves && stack === READER_LAYER ? COVER_LAYER : stack,
    reader: READER_LAYER,
  }
}

/**
 * Where the reader hangs the facing (left) leaf. The element lives under
 * the verso sheets in `BookSpread`; the reader owns what is drawn in it.
 */
let versoLeafSlot: HTMLElement | null = null
const slotListeners = new Set<() => void>()

export function setVersoLeafSlot(el: HTMLElement | null) {
  if (versoLeafSlot === el) return
  versoLeafSlot = el
  for (const listener of slotListeners) listener()
}

export function useVersoLeafSlot(): HTMLElement | null {
  return useSyncExternalStore(
    (onChange) => {
      slotListeners.add(onChange)
      return () => slotListeners.delete(onChange)
    },
    () => versoLeafSlot,
    () => null,
  )
}
