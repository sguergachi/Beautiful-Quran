import { describe, expect, it } from 'vitest'
import { spreadLayers } from '../bookSpread'
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

  it('lets Settings and Bookmarks cover Chapters without parking the reader', () => {
    expect(spreadLayers(true, SETTINGS_LAYER, true)).toEqual({ home: SETTINGS_LAYER, reader: READER_LAYER })
    expect(spreadLayers(true, BOOKMARKS_LAYER, true)).toEqual({ home: BOOKMARKS_LAYER, reader: READER_LAYER })
  })

  it('gives Chapters its real layer when facing leaves fill the spread', () => {
    expect(spreadLayers(true, READER_LAYER, true, true)).toEqual({ home: READER_LAYER, reader: READER_LAYER })
    expect(spreadLayers(true, COVER_LAYER, true, true)).toEqual({ home: COVER_LAYER, reader: READER_LAYER })
  })

  it('has no reader page before a chapter is opened', () => {
    // Layer 1 is Settings here, so Chapters is genuinely underneath it.
    expect(spreadLayers(true, READER_LAYER, false)).toEqual({ home: READER_LAYER, reader: READER_LAYER })
  })
})
