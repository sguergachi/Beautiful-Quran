package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.EnglishWordSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class PaperInkEnglishWindowsTest {
    private val text = "painful punishment awaits"
    private val ranges = Regex("\\S+").findAll(text).map { it.range }.toList()
    private val segments = listOf(Segment(1, 100, 500), Segment(2, 500, 900), Segment(3, 900, 1500))

    @Test fun reorderedMeaningIncludesEarlierWordsAndKeepsContinuousClocks() {
        val alignment = EnglishAlignment(floatArrayOf(), listOf(
            EnglishWordSpan(0f, 7f / text.length, 2),
            EnglishWordSpan(7f / text.length, 18f / text.length, 1),
            EnglishWordSpan(18f / text.length, 1f, 3),
        ))
        val windows = paperInkEnglishWindows(ranges, text.length, alignment, segments)
        assertEquals(100L, windows.first().startMs)
        assertEquals(1500L, windows.last().endMs)
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertEquals(900L, windows[1].endMs)
    }

    @Test fun missingGlossesUseContinuousVerseTimeAndFinishBeforeRepeat() {
        val windows = paperInkEnglishWindows(ranges, text.length, null,
            segments + Segment(1, 1500, 2000))
        assertEquals(100L, windows.first().startMs)
        assertEquals(1500L, windows.last().endMs)
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }
}
