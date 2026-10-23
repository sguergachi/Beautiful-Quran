import { describe, expect, it } from 'vitest'
import {
  chapterRowKey,
  pinAction,
  pinFillOrigin,
  pinFillSpan,
  pinnedChapterLists,
} from './pinnedChapters'

const surahs = [1, 2, 18, 36, 67, 114].map((id) => ({ id }))

describe('pinned chapter lists', () => {
  it('moves pinned chapters onto the band in Quranic order, out of the list', () => {
    const { pinned, listed } = pinnedChapterLists(surahs, [67, 18], false)
    expect(pinned.map((s) => s.id)).toEqual([18, 67])
    expect(listed.map((s) => s.id)).toEqual([1, 2, 36, 114])
  })

  it('stands the band down while searching, and when nothing is pinned', () => {
    expect(pinnedChapterLists(surahs, [18], true)).toEqual({ pinned: [], listed: surahs })
    expect(pinnedChapterLists(surahs, [], false)).toEqual({ pinned: [], listed: surahs })
  })

  it('ignores a pinned id with no chapter', () => {
    const { pinned, listed } = pinnedChapterLists(surahs, [999], false)
    expect(pinned).toEqual([])
    expect(listed).toEqual(surahs)
  })

  it('keys the pinned copy apart from the in-order row', () => {
    expect(chapterRowKey(18, true)).toBe('pinned-18')
    expect(chapterRowKey(18, false)).toBe('surah-18')
  })
})

describe('pin fill', () => {
  it('grows out from the pointer and reaches both edges together', () => {
    expect(pinFillSpan(100, 400, 0)).toEqual([100, 100])
    expect(pinFillSpan(100, 400, 0.5)).toEqual([50, 250])
    expect(pinFillSpan(100, 400, 1)).toEqual([0, 400])
  })

  it('starts in the middle without a pointer, and clamps one off the row', () => {
    expect(pinFillOrigin(NaN, 400)).toBe(200)
    expect(pinFillOrigin(-20, 400)).toBe(0)
    expect(pinFillOrigin(480, 400)).toBe(400)
    expect(pinFillSpan(480, 400, 2)).toEqual([0, 400])
  })

  it('offers the verb for the row it is on', () => {
    expect(pinAction(false)).toBe('Pin')
    expect(pinAction(true)).toBe('Unpin')
  })
})
