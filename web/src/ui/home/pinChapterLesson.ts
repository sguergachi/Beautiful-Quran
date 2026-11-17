/** Android `PinChapterLesson.MIN_READING_MS`: more than a foreground minute. */
const MIN_READING_MS = 60 * 1000

/**
 * Port of Android `PinChapterLesson`: offers pinning after a reader visit that
 * reads a chapter of 80–114 for more than one foreground minute across that
 * range, and a return to Chapters. However the reader was reached (row,
 * Continue, search), a visit begins when a chapter of 80–114 is first read.
 * The caller supplies a monotonic clock and reports only visibility
 * boundaries; no timer or playback ticks are needed.
 */
export class PinChapterLesson {
  offeredChapter: number | null = null
  private chapter: number | null = null
  private enteredReader = false
  private readingSince: number | null = null
  private readMs = 0

  /** Hidden/background time pauses the clock; chapters below 80 reset it. */
  observe(chapterId: number, reading: boolean, onChapters: boolean, nowMs: number): void {
    if (this.chapter == null) {
      if (reading || chapterId !== this.offeredChapter) this.offeredChapter = null
      // A visit begins when a late chapter is first read, by any route.
      if (!reading || chapterId < 80 || chapterId > 114) return
      this.chapter = chapterId
    }
    if (chapterId < 1 || chapterId > 114) {
      this.clearVisit()
      return
    }
    this.pauseReading(nowMs)
    this.chapter = chapterId
    if (chapterId < 80) this.readMs = 0
    this.readingSince = reading && chapterId >= 80 ? nowMs : null
    if (reading) this.enteredReader = true
    // Opening starts while Chapters is still visible. Only an actual reader
    // visit can make its subsequent return a teaching moment.
    if (onChapters && this.enteredReader) {
      this.offeredChapter = this.readMs > MIN_READING_MS ? this.chapter : null
      this.clearVisit()
    }
  }

  /** Ends the current foreground interval without changing the visit. */
  pauseReading(nowMs: number): void {
    if (this.readingSince != null) this.readMs += Math.max(0, nowMs - this.readingSince)
    this.readingSince = null
  }

  dismiss(): void {
    this.offeredChapter = null
    this.clearVisit()
  }

  private clearVisit(): void {
    this.chapter = null
    this.enteredReader = false
    this.readingSince = null
    this.readMs = 0
  }
}
