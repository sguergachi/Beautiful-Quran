package com.beautifulquran.ui.home

/**
 * Offers pinning after a hand-scrolled opening of chapter 80–114, more than
 * three foreground minutes across that range, and a return to Chapters. The caller
 * supplies a monotonic clock and reports only visibility/lifecycle boundaries;
 * no timer or playback ticks are needed. A new list opening replaces the visit.
 */
internal class PinChapterLesson {
    var offeredChapter: Int? = null
        private set
    private var chapter: Int? = null
    private var enteredReader = false
    private var readingSince: Long? = null
    private var readMs = 0L

    fun openFromList(chapterId: Int, scrolledDown: Boolean) {
        clearVisit()
        offeredChapter = null
        chapter = chapterId.takeIf { scrolledDown && it in 80..114 }
    }

    /** Hidden/background time pauses the clock; chapters below 80 reset it. */
    fun observe(chapterId: Int, reading: Boolean, onChapters: Boolean, nowMs: Long) {
        if (chapter == null) {
            if (reading || chapterId != offeredChapter) offeredChapter = null
            return
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
        const val MIN_READING_MS = 3 * 60 * 1000L
    }
}
