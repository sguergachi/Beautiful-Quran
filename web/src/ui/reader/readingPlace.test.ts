import { describe, expect, it } from 'vitest'
import { scrollReadingAyah } from './readingPlace'

describe('scroll reading place', () => {
  it('keeps a linked verse when a short chapter settles nearer its tail', () => {
    expect(scrollReadingAyah(2, 4, false, false)).toBe(2)
    expect(scrollReadingAyah(6, 7, false, false)).toBe(6)
  })
  it('remembers manual scrolling and recitation after opening', () => {
    expect(scrollReadingAyah(2, 4, true, false)).toBe(4)
    expect(scrollReadingAyah(2, 4, false, true)).toBe(4)
  })
})
