import { describe, expect, it } from 'vitest'
import { mushafFlashPositions, mushafFlashRole } from '../mushafSearchFlash'

describe('mushafFlashPositions', () => {
  it('lights the found words of the verse', () => {
    expect(mushafFlashPositions({ wordPosition: 2, wordPositions: [2, 3] }, [1, 2, 3, 4])).toEqual([2, 3])
    expect(mushafFlashPositions({ wordPosition: 4, wordPositions: [] }, [1, 2, 3, 4])).toEqual([4])
  })
  it('lights the whole verse for a translation-only hit', () => {
    expect(mushafFlashPositions({ wordPosition: 0, wordPositions: [] }, [1, 2, 3])).toEqual([1, 2, 3])
    expect(mushafFlashPositions({ wordPosition: 9, wordPositions: [9] }, [1, 2])).toEqual([1, 2])
  })
})

describe('mushafFlashRole', () => {
  const flash = { surahId: 2, ayah: 255, positions: new Set([3]), focusActive: true }
  it('breathes the hit and recedes the rest while focused', () => {
    expect(mushafFlashRole(flash, 2, 255, 3)).toBe('hit')
    expect(mushafFlashRole(flash, 2, 255, 4)).toBe('recessed')
    expect(mushafFlashRole(flash, 2, 256, 3)).toBe('recessed')
    expect(mushafFlashRole({ ...flash, focusActive: false }, 2, 256, 3)).toBeNull()
    expect(mushafFlashRole(null, 2, 255, 3)).toBeNull()
  })
})
