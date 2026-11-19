import { describe, expect, it } from 'vitest'
import { nextRepeatMode, nextSpeed, speedLabel } from './floatingPlaybackPolicy'

describe('cover transport cycling', () => {
  it('cycles repeat off, one, all, off; a range falls back to off', () => {
    expect(nextRepeatMode('off')).toBe('ayah')
    expect(nextRepeatMode('ayah')).toBe('surah')
    expect(nextRepeatMode('surah')).toBe('off')
    expect(nextRepeatMode('range')).toBe('off')
  })

  it('cycles the four speeds and wraps; an unknown speed starts over', () => {
    expect(nextSpeed(0.75)).toBe(1)
    expect(nextSpeed(1)).toBe(1.25)
    expect(nextSpeed(1.25)).toBe(1.5)
    expect(nextSpeed(1.5)).toBe(0.75)
    expect(nextSpeed(2)).toBe(0.75)
  })

  it('labels whole speeds without a decimal', () => {
    expect(speedLabel(1)).toBe('1×')
    expect(speedLabel(0.75)).toBe('0.75×')
  })
})
