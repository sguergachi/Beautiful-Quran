import { describe, expect, it } from 'vitest'
import {
  HOLD_TAP_MAX_MS,
  chapterRowKey,
  holdOutcome,
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

describe('hold to pin', () => {
  it('opens on a quick release and changes nothing on a longer one', () => {
    expect(holdOutcome(80)).toBe('tap')
    expect(holdOutcome(HOLD_TAP_MAX_MS - 1)).toBe('tap')
    expect(holdOutcome(HOLD_TAP_MAX_MS)).toBe('cancel')
    expect(holdOutcome(650)).toBe('cancel')
  })
})
