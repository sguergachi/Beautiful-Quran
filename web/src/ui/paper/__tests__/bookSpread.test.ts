import { describe, expect, it } from 'vitest'
import { bookRightShare, bookTurnDirection, bookmarkSwipeDestination, closesBook, facingPage, turnMs, leavesReader, readerVisible, spreadLayers } from '../bookSpread'
import { BOOKMARKS_LAYER, COVER_LAYER, READER_LAYER, SETTINGS_LAYER } from '../stack'

describe('spreadLayers', () => {
  it('leaves the phone deck alone', () => {
    expect(spreadLayers(false, READER_LAYER, true)).toEqual({ home: READER_LAYER, reader: READER_LAYER })
    expect(spreadLayers(false, COVER_LAYER, true)).toEqual({ home: COVER_LAYER, reader: COVER_LAYER })
  })

  it('keeps both pages on top while a chapter is open', () => {
    expect(spreadLayers(true, READER_LAYER, true)).toEqual({ home: COVER_LAYER, reader: READER_LAYER })
    expect(spreadLayers(true, COVER_LAYER, true)).toEqual({ home: COVER_LAYER, reader: READER_LAYER })
  })

  it('lays Bookmarks over Chapters and Settings over the chapter', () => {
    expect(spreadLayers(true, BOOKMARKS_LAYER, true)).toEqual({ home: BOOKMARKS_LAYER, reader: READER_LAYER })
    // Chapters stays in reach beside Settings; the covered chapter is no longer on top.
    expect(spreadLayers(true, SETTINGS_LAYER, true)).toEqual({ home: COVER_LAYER, reader: SETTINGS_LAYER })
  })

  it('hides Chapters under facing leaves while Settings is open', () => {
    expect(spreadLayers(true, SETTINGS_LAYER, true, true)).toEqual({ home: SETTINGS_LAYER, reader: SETTINGS_LAYER })
  })

  it('gives Chapters its real layer when facing leaves fill the spread', () => {
    expect(spreadLayers(true, READER_LAYER, true, true)).toEqual({ home: READER_LAYER, reader: READER_LAYER })
    expect(spreadLayers(true, COVER_LAYER, true, true)).toEqual({ home: COVER_LAYER, reader: READER_LAYER })
  })

  it('keeps Chapters on top beside Settings before a chapter is opened', () => {
    // Layer 1 is Settings here, laid over the title page on the right.
    expect(spreadLayers(true, READER_LAYER, false)).toEqual({ home: COVER_LAYER, reader: READER_LAYER })
  })
})

describe('visible reader navigation', () => {
  it('keeps gather on the desktop reader beside Chapters and Bookmarks', () => {
    for (const layer of [COVER_LAYER, BOOKMARKS_LAYER, READER_LAYER] as const) {
      expect(readerVisible(true, layer, true)).toBe(true)
      expect(leavesReader(true, READER_LAYER, layer, true)).toBe(false)
    }
  })

  it('exits gather when Settings is laid over the chapter', () => {
    expect(readerVisible(true, SETTINGS_LAYER, true)).toBe(false)
    expect(leavesReader(true, READER_LAYER, SETTINGS_LAYER, true)).toBe(true)
    expect(leavesReader(true, COVER_LAYER, SETTINGS_LAYER, true)).toBe(true)
  })

  it('exits gather when a phone parks the reader', () => {
    expect(leavesReader(false, READER_LAYER, SETTINGS_LAYER, true)).toBe(true)
    expect(leavesReader(false, READER_LAYER, COVER_LAYER, true)).toBe(true)
    expect(readerVisible(false, COVER_LAYER, true)).toBe(false)
    expect(readerVisible(true, READER_LAYER, false)).toBe(false)
  })
})

describe('bookmark swipe ownership', () => {
  it('uses the starting Chapters effective layer even when the real stack is Reader', () => {
    const effective = spreadLayers(true, READER_LAYER, true).home
    expect(bookmarkSwipeDestination(effective, 80, true)).toBe(BOOKMARKS_LAYER)
    expect(bookmarkSwipeDestination(READER_LAYER, 80, true)).toBeNull()
    expect(bookmarkSwipeDestination(effective, 80, false)).toBeNull()
  })

  it('returns from Bookmarks only in the closing direction', () => {
    expect(bookmarkSwipeDestination(BOOKMARKS_LAYER, -80, true)).toBe(COVER_LAYER)
    expect(bookmarkSwipeDestination(BOOKMARKS_LAYER, 80, true)).toBeNull()
  })
})

describe('sheet turns on facing leaves', () => {
  it('lifts the verso leaf to uncover Chapters, the left-most page', () => {
    expect(bookTurnDirection(READER_LAYER, COVER_LAYER)).toBe('on')
    expect(bookTurnDirection(SETTINGS_LAYER, COVER_LAYER)).toBe('on')
  })

  it('lifts the recto leaf to uncover Settings, the right-most page', () => {
    expect(bookTurnDirection(READER_LAYER, SETTINGS_LAYER)).toBe('back')
    expect(bookTurnDirection(COVER_LAYER, SETTINGS_LAYER)).toBe('back')
  })

  it('lays the same leaf back down on the way out', () => {
    expect(bookTurnDirection(COVER_LAYER, READER_LAYER)).toBe('back')
    expect(bookTurnDirection(SETTINGS_LAYER, READER_LAYER)).toBe('on')
  })

  it('turns nothing for Bookmarks, a sheet laid over Chapters', () => {
    expect(bookTurnDirection(READER_LAYER, READER_LAYER)).toBeNull()
    expect(bookTurnDirection(COVER_LAYER, BOOKMARKS_LAYER)).toBeNull()
    expect(bookTurnDirection(BOOKMARKS_LAYER, COVER_LAYER)).toBeNull()
    expect(bookTurnDirection(BOOKMARKS_LAYER, READER_LAYER)).toBe('back')
  })
})

describe('closing the book', () => {
  it('takes the backward sweep on Chapters only', () => {
    expect(closesBook(COVER_LAYER, -80)).toBe(true)
    expect(closesBook(COVER_LAYER, 80)).toBe(false)
    expect(closesBook(READER_LAYER, -80)).toBe(false)
    expect(closesBook(BOOKMARKS_LAYER, -80)).toBe(false)
  })
})

describe('a loose sheet', () => {
  it('lies on the page facing the word, never over it', () => {
    expect(facingPage('recto')).toBe('verso')
    expect(facingPage('verso')).toBe('recto')
  })
})

describe('the two piles of the page block', () => {
  it('puts the whole block on the left at the first page and on the right at the last', () => {
    expect(bookRightShare(true, READER_LAYER, 1)).toBe(0)
    expect(bookRightShare(true, READER_LAYER, 604)).toBe(1)
    expect(bookRightShare(true, READER_LAYER, 302)).toBeCloseTo(0.499, 3)
    // A scrolling chapter counts by the page it begins on.
    expect(bookRightShare(false, READER_LAYER, 604)).toBe(1)
  })

  it('turns the whole left pile over for Chapters and the whole right pile for Settings', () => {
    expect(bookRightShare(true, COVER_LAYER, 300)).toBe(1)
    expect(bookRightShare(true, BOOKMARKS_LAYER, 300)).toBe(1)
    expect(bookRightShare(true, SETTINGS_LAYER, 300)).toBe(0)
    // Beside a scrolling chapter Chapters and Settings are laid over a page; nothing turns.
    expect(bookRightShare(false, COVER_LAYER, 300)).toBeCloseTo(299 / 603, 6)
    expect(bookRightShare(false, SETTINGS_LAYER, 300)).toBeCloseTo(299 / 603, 6)
  })

  it('stands open at Chapters before a chapter is chosen', () => {
    expect(bookRightShare(false, COVER_LAYER, null)).toBe(1)
  })

  it('takes longer to turn a pile than a leaf', () => {
    expect(turnMs(0)).toBe(760)
    expect(turnMs(1)).toBe(1140)
    expect(turnMs(0.5)).toBeGreaterThan(turnMs(0.1))
  })
})
