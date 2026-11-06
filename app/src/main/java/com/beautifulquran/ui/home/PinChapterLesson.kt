package com.beautifulquran.ui.home

/**
 * Offers pinning after a reader visit that reads chapter 80–114 for more than
 * one foreground minute across that range, and a return to Chapters. However
 * the reader was reached (list row, Continue, search), a visit begins when a
 * chapter of 80–114 is first read. The caller supplies a monotonic clock and
 * reports only visibility/lifecycle boundaries; no timer or playback ticks are
 * needed.
 */
internal class PinChapterLesson {
    var offeredChapter: Int? = null
        private set
    private var chapter: Int? = null
    private var enteredReader = false
    private var readingSince: Long? = null
    private var readMs = 0L

    /** Whole seconds read in the visit that just ended, until [takeEndedVisitSeconds]. */
    private var endedVisitSeconds: Long? = null
    fun takeEndedVisitSeconds(): Long? = endedVisitSeconds.also { endedVisitSeconds = null }

    /** Hidden/background time pauses the clock; chapters below 80 reset it. */
    fun observe(chapterId: Int, reading: Boolean, onChapters: Boolean, nowMs: Long) {
        if (chapter == null) {
            if (reading || chapterId != offeredChapter) offeredChapter = null
            // A visit begins when a late chapter is first read, by any route.
            if (!reading || chapterId !in 80..114) return
            chapter = chapterId
        }
        if (chapterId !in 1..114) {
            clearVisit()
            return
        }
        pauseReading(nowMs)
        chapter = chapterId
        if (chapterId < 80) readMs = 0L
        readingSince = if (reading && chapterId >= 80) nowMs else null
        if (reading) enteredReader = true
        // Opening starts while Chapters is still visible. Only an actual
        // reader visit can make its subsequent return a teaching moment.
        if (onChapters && enteredReader) {
            offeredChapter = chapter.takeIf { readMs > MIN_READING_MS }
            endedVisitSeconds = readMs / 1000
            clearVisit()
        }
    }

    /** Ends the current foreground interval without changing the visit's identity. */
    fun pauseReading(nowMs: Long) {
        readingSince?.let { readMs += (nowMs - it).coerceAtLeast(0L) }
        readingSince = null
    }

    fun dismiss() {
        offeredChapter = null
        clearVisit()
    }

    private fun clearVisit() {
        chapter = null
        enteredReader = false
        readingSince = null
        readMs = 0L
    }

    private companion object {
        const val MIN_READING_MS = 60 * 1000L
    }
}
