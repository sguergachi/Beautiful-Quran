import { describe, expect, it } from 'vitest'
import { clampAyah, dialIndexAt } from './searchDialsMath'

describe('search dials', () => {
  it('selects the row nearest the centre slot, within range', () => {
    expect(dialIndexAt(0, 42, 114)).toBe(0)
    expect(dialIndexAt(20, 42, 114)).toBe(0)
    expect(dialIndexAt(22, 42, 114)).toBe(1)
    expect(dialIndexAt(99999, 42, 114)).toBe(113)
    expect(dialIndexAt(-30, 42, 7)).toBe(0)
  })

  it('clamps the ayah to the centred surah', () => {
    expect(clampAyah(255, 7)).toBe(7)
    expect(clampAyah(3, 7)).toBe(3)
    expect(clampAyah(0, 7)).toBe(1)
  })
})
