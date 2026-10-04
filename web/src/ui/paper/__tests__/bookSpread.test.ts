import { describe, expect, it } from 'vitest'
import { CHAPTERS_PLACE, bookRightShare, bookmarkSwipeDestination, closesBook, facingPage, turnMs, leavesReader, readerVisible, spreadLayers, bookPiles, bookRest, forgetBookRest, restBook } from '../bookSpread'
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
    expect(bookRightShare(1)).toBe(0)
    expect(bookRightShare(604)).toBe(1)
    expect(bookRightShare(302)).toBeCloseTo(0.499, 3)
  })

  it('is at its start before a chapter is chosen, and while Chapters shows', () => {
    expect(bookRightShare(null)).toBe(0)
    expect(bookRightShare(CHAPTERS_PLACE)).toBe(0)
  })

  it('turns, from Chapters, exactly the leaves that come before the chapter chosen', () => {
    const turned = (page: number) => Math.abs(bookRightShare(page) - bookRightShare(CHAPTERS_PLACE))
    // Al-Baqarah begins on the first spread: nothing to turn.
    expect(turned(1)).toBe(0)
    // Al-Mumtahanah, on page 549: nine tenths of the book.
    expect(turned(549)).toBeCloseTo(548 / 603)
    // The last spread: every leaf.
    expect(turned(603)).toBeCloseTo(602 / 603)
    // And going back to Chapters turns the same leaves home.
    expect(bookRightShare(CHAPTERS_PLACE) - bookRightShare(549)).toBeCloseTo(-548 / 603)
  })

  it('takes longer to turn a pile than a leaf', () => {
    expect(turnMs(0)).toBe(760)
    expect(turnMs(1)).toBe(1140)
    expect(turnMs(0.5)).toBeGreaterThan(turnMs(0.1))
  })
})

describe('the piles while leaves are in the air', () => {
  it('share the whole block between them at rest', () => {
    expect(bookPiles(0.25, null)).toEqual({ right: 0.25, left: 0.75 })
  })

  it('take the lifted leaves off their pile at once and add them to the other only on landing', () => {
    // Going to Chapters from three quarters of the way in: the leaves read are turned back.
    const toChapters = bookPiles(0, { from: 0.75, to: 0 })
    expect(toChapters.right).toBe(0)
    // The left pile still has only what lay on it: the rest is in the air.
    expect(toChapters.left).toBeCloseTo(0.25)
    // Choosing that chapter again, they leave the left pile and have not reached the right.
    const back = bookPiles(0.75, { from: 0, to: 0.75 })
    expect(back.right).toBe(0)
    expect(back.left).toBeCloseTo(0.25)
  })

  it('never holds more than the block', () => {
    for (const [from, to] of [[0, 1], [1, 0], [0.3, 0.31], [0.9, 0.2]]) {
      const piles = bookPiles(to, { from, to })
      expect(piles.left + piles.right).toBeLessThanOrEqual(1 + 1e-9)
      expect(piles.left + piles.right + Math.abs(to - from)).toBeCloseTo(1)
    }
  })
})

describe('where the book rests', () => {
  it('keeps what it is told and forgets nothing else', () => {
    forgetBookRest()
    expect(bookRest()).toBeNull()
    restBook({ layer: READER_LAYER })
    restBook({ place: 12 })
    expect(bookRest()).toEqual({ layer: READER_LAYER, place: 12 })
    restBook({ layer: COVER_LAYER })
    expect(bookRest()).toEqual({ layer: COVER_LAYER, place: 12 })
    forgetBookRest()
  })
})
