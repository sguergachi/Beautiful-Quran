import { describe, expect, it } from 'vitest'
import { peekPlaylistNextIndex } from '../playlistNext'

describe('peekPlaylistNextIndex', () => {
  const ayahs = [1, 2, 3, 4, 5]

  it('advances to the following ayah', () => {
    expect(
      peekPlaylistNextIndex(ayahs, 1, { repeatMode: 'off', repeatRange: null }),
    ).toBe(2)
  })

  it('stops at end of surah when repeat is off', () => {
    expect(
      peekPlaylistNextIndex(ayahs, 4, { repeatMode: 'off', repeatRange: null }),
    ).toBeNull()
  })

  it('wraps to 0 on surah repeat', () => {
    expect(
      peekPlaylistNextIndex(ayahs, 4, { repeatMode: 'surah', repeatRange: null }),
    ).toBe(0)
  })

  it('wraps past the heard basmalah when told to', () => {
    const withLeadIn = [0, 1, 2, 3]
    const repeat = { repeatMode: 'surah', repeatRange: null } as const
    expect(peekPlaylistNextIndex(withLeadIn, 3, repeat)).toBe(0)
    expect(peekPlaylistNextIndex(withLeadIn, 3, { ...repeat, skipLeadIn: true })).toBe(1)
    expect(peekPlaylistNextIndex(ayahs, 4, { ...repeat, skipLeadIn: true })).toBe(0)
  })

  it('wraps range repeat to the first ayah', () => {
    expect(
      peekPlaylistNextIndex(ayahs, 2, {
        repeatMode: 'range',
        repeatRange: { first: 2, last: 3 },
      }),
    ).toBe(1) // ayah 2 is index 1
  })

  it('does not prepare standby while ayah-repeating', () => {
    expect(
      peekPlaylistNextIndex(ayahs, 1, {
        repeatMode: 'ayah',
        repeatRange: null,
        forStandby: true,
      }),
    ).toBeNull()
  })

  it('still advances on manual next during ayah-repeat', () => {
    expect(
      peekPlaylistNextIndex(ayahs, 1, {
        repeatMode: 'ayah',
        repeatRange: null,
        forStandby: false,
      }),
    ).toBe(2)
  })
})
