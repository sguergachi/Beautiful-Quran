import { describe, expect, it } from 'vitest'
import { parseVerseHash, verseLink } from './verseRoute'

const count = (id: number) => ({ 1: 7, 2: 286, 114: 6 }[id])

describe('verse addresses', () => {
  it('opens an exact verse, including the end of the Quran', () => {
    expect(parseVerseHash('#2:255', count)).toEqual({ surahId: 2, ayah: 255 })
    expect(parseVerseHash('#114:6', count)).toEqual({ surahId: 114, ayah: 6 })
  })

  it('rejects unknown chapters, invalid verses and other routes', () => {
    for (const hash of ['#0:1', '#115:1', '#1:8', '#2:0', '#2:-1', '#2:1.5', '#lab', '#2:255/extra', '']) {
      expect(parseVerseHash(hash, count)).toBeNull()
    }
  })

  it('keeps the deployment base and replaces an older address', () => {
    expect(verseLink('https://example.org/Beautiful-Quran/app/#1:1', { surahId: 2, ayah: 255 }))
      .toBe('https://example.org/Beautiful-Quran/app/#2:255')
  })
})
