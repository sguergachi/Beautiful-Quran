import { describe, expect, it } from 'vitest'
import { pausedReadingPlaceRibbonAyah } from './readingPlaceRibbon'

const base = {
  renderedSurahId: 2,
  mediaSurahId: 2,
  mediaAyah: 5,
  isPlaying: false,
  isBuffering: false,
  playedHere: true,
}

describe('pausedReadingPlaceRibbonAyah', () => {
  it('parks on the paused verse of a chapter played here', () => {
    expect(pausedReadingPlaceRibbonAyah(base)).toBe(5)
  })

  it('never while playing, buffering, elsewhere, or unplayed', () => {
    expect(pausedReadingPlaceRibbonAyah({ ...base, isPlaying: true })).toBeNull()
    expect(pausedReadingPlaceRibbonAyah({ ...base, isBuffering: true })).toBeNull()
    expect(pausedReadingPlaceRibbonAyah({ ...base, mediaSurahId: 3 })).toBeNull()
    expect(pausedReadingPlaceRibbonAyah({ ...base, playedHere: false })).toBeNull()
    expect(pausedReadingPlaceRibbonAyah({ ...base, mediaAyah: 0 })).toBeNull()
  })
})
