import { describe, expect, it } from 'vitest'
import { stackDragPosition, stackDragRange, stackSwipeDestination, type StackSwipe } from '../stackSwipe'

describe('stackDragRange', () => {
  const drag = { hasReader: true, hasBookmarks: false, settingsOrigin: 1 as const, mushafReaderOnTop: false }
  it('moves one layer either way', () => {
    expect(stackDragRange({ ...drag, layer: 1 })).toEqual({ start: 1, lo: 0, hi: 2 })
    expect(stackDragRange({ ...drag, layer: 0 })).toEqual({ start: 0, lo: 0, hi: 1 })
    expect(stackDragRange({ ...drag, layer: 0, hasBookmarks: true })).toEqual({ start: 0, lo: -1, hi: 1 })
  })
  it('lets the Mushaf turn its own leaves', () => {
    expect(stackDragRange({ ...drag, layer: 1, mushafReaderOnTop: true })).toBeNull()
    expect(stackDragRange({ ...drag, layer: 0, mushafReaderOnTop: true })).toEqual({ start: 0, lo: 0, hi: 1 })
  })
  it('turns Chapters back over Settings opened from Chapters', () => {
    expect(stackDragRange({ ...drag, layer: 2, settingsOrigin: 0 })).toEqual({ start: 1, lo: 0, hi: 2 })
    expect(stackDragRange({ ...drag, layer: 2, settingsOrigin: 1 })).toEqual({ start: 2, lo: 1, hi: 2 })
    expect(stackDragRange({ ...drag, layer: 1, hasReader: false, settingsOrigin: 0 })).toEqual({ start: 1, lo: 0, hi: 1 })
  })
  it('follows the finger within the range', () => {
    const range = { start: 1, lo: 0, hi: 2 }
    expect(stackDragPosition(range, -206, 412)).toBe(1.5)
    expect(stackDragPosition(range, 900, 412)).toBe(0)
    expect(stackDragPosition(range, -900, 412)).toBe(2)
  })
})

const base: StackSwipe = {
  layer: 0, dx: -120, dy: 4, ms: 400, width: 400, hasReader: true, hasBookmarks: true, mushafReaderOnTop: false,
}

describe('stackSwipeDestination', () => {
  it('turns Chapters forward on a leftward stroke, never closing the book', () => {
    expect(stackSwipeDestination(base)).toBe(1)
    expect(stackSwipeDestination({ ...base, hasReader: false })).toBe(1)
    expect(stackSwipeDestination({ ...base, layer: 1 })).toBe(2)
    expect(stackSwipeDestination({ ...base, layer: 2 })).toBe(null)
  })

  it('turns back on a rightward stroke, to Bookmarks only from Chapters', () => {
    expect(stackSwipeDestination({ ...base, dx: 120 })).toBe(-1)
    expect(stackSwipeDestination({ ...base, dx: 120, hasBookmarks: false })).toBe(null)
    expect(stackSwipeDestination({ ...base, layer: 1, dx: 120 })).toBe('back')
    expect(stackSwipeDestination({ ...base, layer: -1, dx: -120 })).toBe(0)
  })

  it('commits past 15% of the width or on a 0.30 widths/s fling', () => {
    expect(stackSwipeDestination({ ...base, dx: -50, ms: 1000 })).toBe(null)
    expect(stackSwipeDestination({ ...base, dx: -50, ms: 100 })).toBe(1)
    expect(stackSwipeDestination({ ...base, dx: -61 })).toBe(1)
  })

  it('leaves vertical strokes and the Mushaf reader alone', () => {
    expect(stackSwipeDestination({ ...base, dy: 140 })).toBe(null)
    expect(stackSwipeDestination({ ...base, layer: 1, dx: 120, mushafReaderOnTop: true })).toBe(null)
    expect(stackSwipeDestination({ ...base, layer: 2, dx: 120, mushafReaderOnTop: true })).toBe('back')
  })
})
