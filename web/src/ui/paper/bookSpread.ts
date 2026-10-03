import { useSyncExternalStore } from 'react'
import { BOOKMARKS_LAYER, COVER_LAYER, READER_LAYER, type StackLayer } from './stack'

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

/** Visibility follows the facing page, rather than the sheet covering the verso. */
export function readerVisible(spread: boolean, stack: StackLayer, hasReader: boolean): boolean {
  return hasReader && (spread || stack === READER_LAYER)
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

/** The leaf in the air during a page turn: across both pages, over the sheets. */
const turningLeaf = createSlot()
export const setTurningLeafSlot = turningLeaf.set
export const useTurningLeafSlot = turningLeaf.use
