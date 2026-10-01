import { describe, expect, it } from 'vitest'
import { readerOpensOnTitle } from '../readerOpening'

describe('reader opening intent', () => {
  it('reserves the title for an ordinary chapter open at verse one', () => {
    expect(readerOpensOnTitle(1, 'chapter')).toBe(true)
    expect(readerOpensOnTitle(2, 'chapter')).toBe(false)
  })

  it('puts Continue, bookmark and search landings on the reading line even at verse one', () => {
    expect(readerOpensOnTitle(1, 'reading')).toBe(false)
    expect(readerOpensOnTitle(2, 'reading')).toBe(false)
  })
})
