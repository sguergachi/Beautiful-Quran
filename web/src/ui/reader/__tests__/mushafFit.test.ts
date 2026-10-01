import { describe, expect, it } from 'vitest'
import { mushafFit } from '../mushafFit'

describe('incoming page fit', () => {
  it('waits for both incoming leaves, including the hidden recto', () => {
    expect(mushafFit(3, true, { 1: 1, 2: 1, 4: 0.9 })).toBeUndefined()
    expect(mushafFit(3, true, { 1: 1, 2: 1, 3: 0.7, 4: 0.9 })).toBe(0.7)
  })

  it('uses the incoming page on a phone and the tighter incoming verso on a spread', () => {
    expect(mushafFit(3, false, { 3: 0.8 })).toBe(0.8)
    expect(mushafFit(3, true, { 3: 0.8, 4: 0.6 })).toBe(0.6)
  })
})
