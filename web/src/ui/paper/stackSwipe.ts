import { BOOKMARKS_LAYER, COVER_LAYER, READER_LAYER, settingsLayerFor, type StackLayer } from './stack'

/**
 * Phone sheet turns by a horizontal swipe — Android `paperStackDrag`
 * (MainActivity). A finger moving left turns the top sheet away to the
 * next one (Chapters → Reader → Settings); moving right turns back
 * (Settings → Reader → Chapters → Bookmarks). One sheet per gesture.
 * Chapters never closes the book on a phone: the cover is shown once per
 * cold start.
 */
export const STACK_SWIPE_COMMIT = 0.15
export const STACK_SWIPE_FLING = 0.3
/** The axis is decided with a slight bias toward reading's vertical scroll. */
export const STACK_SWIPE_AXIS_BIAS = 1.1

export interface StackSwipe {
  layer: StackLayer
  dx: number
  dy: number
  /** Milliseconds the stroke took. */
  ms: number
  width: number
  hasReader: boolean
  hasBookmarks: boolean
  /** The Mushaf reader turns its own leaves; the stack leaves it alone. */
  mushafReaderOnTop: boolean
}

export interface StackDrag {
  /** The layer the stack rests on when the stroke begins. */
  layer: StackLayer
  hasReader: boolean
  hasBookmarks: boolean
  /** The layer Settings was opened over (it returns there). */
  settingsOrigin: StackLayer
  mushafReaderOnTop: boolean
}

/**
 * The span a held stroke may move the stack through — one layer either
 * way (Android `paperStackDrag` onDrag), or null when the stroke cannot
 * hold a sheet (the Mushaf turns its own leaves). Settings opened straight
 * over Chapters starts one layer above Chapters, so its stroke turns
 * Chapters back in rather than the hidden reader between them.
 */
export function stackDragRange(drag: StackDrag): { start: number; lo: number; hi: number } | null {
  if (drag.mushafReaderOnTop && drag.layer === READER_LAYER) return null
  const top = settingsLayerFor(drag.hasReader)
  const start = drag.layer === top && drag.layer > COVER_LAYER
    ? Math.min(drag.layer, Math.max(drag.settingsOrigin, COVER_LAYER) + 1)
    : drag.layer
  const min = drag.hasBookmarks ? BOOKMARKS_LAYER : COVER_LAYER
  const lo = Math.max(start - 1, min)
  const hi = Math.min(start + 1, top)
  return lo < hi ? { start, lo, hi } : null
}

/** Where the held sheets stand for a finger `dx` pixels along the stroke. */
export function stackDragPosition(range: { start: number; lo: number; hi: number }, dx: number, width: number): number {
  if (width <= 0) return range.start
  return Math.min(range.hi, Math.max(range.lo, range.start - dx / width))
}

/** Where a released stroke turns the stack, or null when it turns nothing. */
export function stackSwipeDestination(swipe: StackSwipe): StackLayer | 'back' | null {
  const { layer, dx, dy, ms, width } = swipe
  if (width <= 0 || Math.abs(dx) <= Math.abs(dy) * STACK_SWIPE_AXIS_BIAS) return null
  if (swipe.mushafReaderOnTop && layer === READER_LAYER) return null
  const share = Math.abs(dx) / width
  const speed = ms > 0 ? share / (ms / 1000) : 0
  if (share < STACK_SWIPE_COMMIT && !(speed >= STACK_SWIPE_FLING && share >= 0.04)) return null
  if (dx < 0) {
    if (layer === BOOKMARKS_LAYER) return COVER_LAYER
    const top = settingsLayerFor(swipe.hasReader)
    return layer < top ? ((layer + 1) as StackLayer) : null
  }
  if (layer === COVER_LAYER) return swipe.hasBookmarks ? BOOKMARKS_LAYER : null
  if (layer === BOOKMARKS_LAYER) return null
  return 'back'
}
