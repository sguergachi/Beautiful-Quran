import { describe, expect, it } from 'vitest'
import { PinChapterLesson } from './pinChapterLesson'

describe('pin chapter lesson', () => {
  it('offers the chapter after more than a minute of reading, on return to Chapters', () => {
    const lesson = new PinChapterLesson()
    lesson.observe(112, true, false, 0)
    lesson.pauseReading(61_000)
    lesson.observe(112, false, true, 61_000)
    expect(lesson.offeredChapter).toBe(112)
  })

  it('a minute or less offers nothing', () => {
    const lesson = new PinChapterLesson()
    lesson.observe(112, true, false, 0)
    lesson.observe(112, false, true, 60_000)
    expect(lesson.offeredChapter).toBeNull()
  })

  it('early chapters never start a visit, and hidden time does not count', () => {
    const early = new PinChapterLesson()
    early.observe(2, true, false, 0)
    early.observe(2, false, true, 120_000)
    expect(early.offeredChapter).toBeNull()

    const hidden = new PinChapterLesson()
    hidden.observe(100, true, false, 0)
    hidden.observe(100, false, false, 30_000) // backgrounded
    hidden.observe(100, true, false, 600_000)
    hidden.observe(100, false, true, 631_000)
    expect(hidden.offeredChapter).toBe(100)
  })

  it('dismiss forgets the offer', () => {
    const lesson = new PinChapterLesson()
    lesson.observe(112, true, false, 0)
    lesson.observe(112, false, true, 90_000)
    lesson.dismiss()
    expect(lesson.offeredChapter).toBeNull()
  })
})
