import { describe, expect, it } from 'vitest'
import {
  composeShareText,
  onLeaveReaderSheet,
  onMarkTap,
  onVerseTap,
  shareFooterCopy,
  toggleGatheredAyah,
} from './gather'

const ref = (surahId: number, ayah: number) => ({ surahId, ayah })

describe('gather selection', () => {
  it('adds in tap order and drops on a second tap', () => {
    const once = toggleGatheredAyah([], ref(2, 255))
    const twice = toggleGatheredAyah(once, ref(112, 1))
    expect(twice).toEqual([ref(2, 255), ref(112, 1)])
    expect(toggleGatheredAyah(twice, ref(2, 255))).toEqual([ref(112, 1)])
  })

  it('stops at twenty verses', () => {
    const full = Array.from({ length: 20 }, (_, i) => ref(1, i + 1))
    expect(toggleGatheredAyah(full, ref(2, 1))).toEqual(full)
  })
})

describe('gather entry', () => {
  it('enters from the mark and only toggles the body once gathering', () => {
    expect(onMarkTap(false, ref(2, 255))).toEqual({ type: 'enter', ref: ref(2, 255) })
    expect(onVerseTap(false, ref(2, 255))).toEqual({ type: 'none' })
    expect(onVerseTap(true, ref(2, 255))).toEqual({ type: 'toggle', ref: ref(2, 255) })
    expect(onLeaveReaderSheet(true)).toEqual({ type: 'exit' })
    expect(onLeaveReaderSheet(false)).toEqual({ type: 'none' })
  })
})

describe('share text', () => {
  it('writes arabic, translation, and a quiet reference', () => {
    const text = composeShareText([
      {
        arabic: 'اللَّهُ',
        translation: 'Allah',
        surahNameTransliteration: 'al-Baqarah',
        surahId: 2,
        ayah: 255,
      },
    ])
    expect(text).toBe("اللَّهُ\n\nAllah\n\nal-Baqarah 2:255")
  })

  it('cites one chapter as a range and two chapters by both ends', () => {
    expect(
      shareFooterCopy([
        { surahId: 2, ayah: 255, surahName: 'al-Baqarah' },
        { surahId: 2, ayah: 256, surahName: 'al-Baqarah' },
      ]),
    ).toEqual({ chapter: 'al-Baqarah', verses: '2:255–256' })
    expect(
      shareFooterCopy([
        { surahId: 2, ayah: 255, surahName: 'al-Baqarah' },
        { surahId: 112, ayah: 1, surahName: 'al-Ikhlas' },
      ]).verses,
    ).toBe('2:255 · 112:1')
  })
})
