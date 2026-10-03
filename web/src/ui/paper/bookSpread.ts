import { useSyncExternalStore } from 'react'
import { BOOKMARKS_LAYER, COVER_LAYER, READER_LAYER, SETTINGS_LAYER, type StackLayer } from './stack'

/**
 * Desktop lays the paper stack open as a book, and every sheet has one
 * place in it: Chapters is the left page (Bookmarks is laid over it), and
 * the right page is whatever was opened from it — the title page, the
 * chapter being read, or Settings laid over that.
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
 * are both on top: Chapters owns the verso until Bookmarks is laid over it,
 * and an open chapter owns the recto until Settings is laid over it.
 */
export function spreadLayers(
  spread: boolean,
  stack: StackLayer,
  hasReader: boolean,
  leaves = false,
): { home: StackLayer; reader: StackLayer } {
  if (!spread) return { home: stack, reader: stack }
  // Mushaf layout fills both pages with facing leaves, so Chapters keeps
  // its real layer there: laid over the verso at layer 0, gone above it.
  // Otherwise nothing above Chapters' own layer covers the left page.
  const home = !leaves && stack > COVER_LAYER ? COVER_LAYER : stack
  if (!hasReader) return { home, reader: stack }
  return {
    home,
    // Under Settings the chapter is a covered page: it gives up the keys.
    reader: stack === SETTINGS_LAYER ? SETTINGS_LAYER : READER_LAYER,
  }
}

/** In a spread the chapter is in sight beside Chapters and Bookmarks; Settings covers it. */
export function readerVisible(spread: boolean, stack: StackLayer, hasReader: boolean): boolean {
  return hasReader && (stack === READER_LAYER || (spread && stack < READER_LAYER))
}

export function leavesReader(
  spread: boolean,
  from: StackLayer,
  to: StackLayer,
  hasReader: boolean,
): boolean {
  return readerVisible(spread, from, hasReader) && !readerVisible(spread, to, hasReader)
}

/**
 * Facing mushaf leaves: Chapters is the left-most page of the open book and
 * Settings the right-most, each lying under the leaf on its side. Going to
 * Chapters lifts the verso leaf and swings it right ('on'); going to
 * Settings lifts the recto leaf and swings it left ('back'). Leaving either
 * lays that leaf back down over it. Bookmarks is a sheet laid over Chapters,
 * not a page of the book, so it turns nothing.
 */
export function bookTurnDirection(from: StackLayer, to: StackLayer): 'on' | 'back' | null {
  const start = Math.max(COVER_LAYER, from)
  const end = Math.max(COVER_LAYER, to)
  if (start === end) return null
  return end > start ? 'back' : 'on'
}

/**
 * Chapters is the first page, so the sweep that turns a leaf back (a finger
 * moving left) closes the book from it. Bookmarks owns the other direction.
 */
export function closesBook(layer: StackLayer, dx: number): boolean {
  return layer === COVER_LAYER && dx < 0
}

/** A swipe belongs to the sheet on which its pointer went down. */
export function bookmarkSwipeDestination(layer: StackLayer, dx: number, hasBookmarks: boolean): StackLayer | null {
  if (layer === COVER_LAYER && dx > 0 && hasBookmarks) return BOOKMARKS_LAYER
  if (layer === BOOKMARKS_LAYER && dx < 0) return COVER_LAYER
  return null
}

/** A DOM node `BookSpread` owns and the reader draws into through a portal. */
function createSlot() {
  let node: HTMLElement | null = null
  const listeners = new Set<() => void>()
  const set = (el: HTMLElement | null) => {
    if (node === el) return
    node = el
    for (const listener of listeners) listener()
  }
  const use = (): HTMLElement | null =>
    useSyncExternalStore(
      (onChange) => {
        listeners.add(onChange)
        return () => listeners.delete(onChange)
      },
      () => node,
      () => null,
    )
  return { set, use }
}

/** The facing (left) leaf, under the verso sheets. */
const versoLeaf = createSlot()
export const setVersoLeafSlot = versoLeaf.set
export const useVersoLeafSlot = versoLeaf.use

/** A loose sheet laid over one page of the spread (the root viewer). */
const looseSheet = createSlot()
export const setLooseSheetSlot = looseSheet.set
export const useLooseSheetSlot = looseSheet.use

/** A loose sheet is laid on the page facing the one it was called from. */
export function facingPage(origin: 'recto' | 'verso'): 'recto' | 'verso' {
  return origin === 'recto' ? 'verso' : 'recto'
}

/** The leaf in the air during a page turn: across both pages, over the sheets. */
const turningLeaf = createSlot()
export const setTurningLeafSlot = turningLeaf.set
export const useTurningLeafSlot = turningLeaf.use
