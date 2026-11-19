import { describe, expect, it } from 'vitest'
import {
  showsEnglishLeafChrome,
  showsPreviewAnnotation,
  showsScrollChrome,
  showsVerseNumberChrome,
} from './customizePolicy'

describe('Customize chrome (Android CustomizePolicy)', () => {
  it('keeps size dial, toggles and ayah selector to the Scroll layout', () => {
    expect(showsScrollChrome('scroll')).toBe(true)
    expect(showsScrollChrome('mushaf')).toBe(false)
  })

  it('previews a verse note only on a Scroll page with notes on', () => {
    expect(showsPreviewAnnotation('scroll', true)).toBe(true)
    expect(showsPreviewAnnotation('scroll', false)).toBe(false)
    expect(showsPreviewAnnotation('mushaf', true)).toBe(false)
  })

  it('offers verse-mark script on every scroll view and the English leaf', () => {
    expect(showsVerseNumberChrome('scroll', 'arabic_only')).toBe(true)
    expect(showsVerseNumberChrome('mushaf', 'english_only')).toBe(true)
    expect(showsVerseNumberChrome('mushaf', 'arabic_only')).toBe(false)
  })

  it('asks which English only on the English leaf', () => {
    expect(showsEnglishLeafChrome('mushaf', 'english_only')).toBe(true)
    expect(showsEnglishLeafChrome('mushaf', 'arabic_only')).toBe(false)
    expect(showsEnglishLeafChrome('scroll', 'english_only')).toBe(false)
  })
})
