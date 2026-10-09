import { describe, expect, it } from 'vitest'
import { visibleSheets } from '../sheetFocus'
import { bookSpreadEnabled } from '../bookSpread'

describe('visible paper and keyboard ownership', () => {
  it('excludes covered phone paper, with and without an open chapter', () => {
    expect(visibleSheets(false, false, 1, true)).toEqual(['reader'])
    expect(visibleSheets(false, false, 2, true)).toEqual(['settings'])
    expect(visibleSheets(false, false, 1, false)).toEqual(['settings'])
  })

  it('keeps exposed facing pages usable', () => {
    expect(visibleSheets(true, false, 1, true)).toEqual(['home', 'reader'])
    expect(visibleSheets(true, false, 2, true)).toEqual(['home', 'settings'])
    expect(visibleSheets(true, false, -1, true)).toEqual(['reader', 'bookmarks'])
  })

  it('covers both Mushaf leaves with Settings, and keeps Chapters off an open verso', () => {
    expect(visibleSheets(true, true, 1, true)).toEqual(['reader'])
    expect(visibleSheets(true, true, 2, true)).toEqual(['settings'])
  })

  it('enlarges a single page while preserving facing pages by default', () => {
    expect(bookSpreadEnabled(true, 'facing')).toBe(true)
    expect(bookSpreadEnabled(true, 'single')).toBe(false)
    expect(bookSpreadEnabled(false, 'facing')).toBe(false)
  })
})
