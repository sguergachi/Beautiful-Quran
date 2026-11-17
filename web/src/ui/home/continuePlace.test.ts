import { describe, expect, it } from 'vitest'
import { chapterOpenAyah, rowPlacement, shouldDismissSearchOnScroll } from './continuePlace'

describe('place row placement', () => {
  const view = [100, 800] as const
  it('counts a row fully between the field and the foot as visible', () => {
    expect(rowPlacement(100, 172, ...view)).toBe('visible')
    expect(rowPlacement(99.8, 172, ...view)).toBe('visible')
  })
  it('a row cut by either edge is partial, not visible', () => {
    expect(rowPlacement(80, 152, ...view)).toBe('partial')
    expect(rowPlacement(760, 832, ...view)).toBe('partial')
  })
  it('knows which way a row left', () => {
    expect(rowPlacement(10, 90, ...view)).toBe('above')
    expect(rowPlacement(820, 892, ...view)).toBe('below')
  })
})

describe('chapter row opening ayah', () => {
  it('prefers the typed reference, then the last-heard ayah of its own row', () => {
    expect(chapterOpenAyah({ surahId: 2, ayahTarget: 255, lastSurah: 2, lastAyah: 7 })).toBe(255)
    expect(chapterOpenAyah({ surahId: 2, ayahTarget: null, lastSurah: 2, lastAyah: 7 })).toBe(7)
    expect(chapterOpenAyah({ surahId: 3, ayahTarget: null, lastSurah: 2, lastAyah: 7 })).toBeNull()
    expect(chapterOpenAyah({ surahId: 2, ayahTarget: null, lastSurah: 2, lastAyah: 0 })).toBeNull()
  })
})

describe('search scroll dismissal', () => {
  it('dismisses only past the threshold', () => {
    expect(shouldDismissSearchOnScroll(24)).toBe(false)
    expect(shouldDismissSearchOnScroll(25)).toBe(true)
  })
})
