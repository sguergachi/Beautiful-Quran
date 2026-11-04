package com.beautifulquran.ui.home

/**
 * Offers pinning after a hand-scrolled chapter 80–114 has been read for more
 * than three foreground minutes and the reader returns to Chapters. The caller
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

    /** Hidden sheets, background time and other chapters never add reading time. */
    fun observe(chapterId: Int, reading: Boolean, onChapters: Boolean, nowMs: Long) {
        if (chapter == null) {
            if (reading || chapterId != offeredChapter) offeredChapter = null
            return
        }
        if (chapterId != chapter) {
            clearVisit()
            return
        }
        pauseReading(nowMs)
        readingSince = if (reading) nowMs else null
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
