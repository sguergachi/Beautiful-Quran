package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.EnglishWordSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkEnglishWindowsTest {
    private val text = "painful punishment awaits"
    private val ranges = Regex("\\S+").findAll(text).map { it.range }.toList()
    private val segments = listOf(Segment(1, 100, 500), Segment(2, 500, 900), Segment(3, 900, 1500))

    @Test fun englishLeafDoesNotPreviewTheNextWordAcrossItsBoundary() {
        val start = ranges[1].first
        val end = ranges[1].last + 1
        for (edge in listOf(0f, start - 1f, start - .01f)) {
            assertEquals(-1f, englishPaperInkWordProgress(edge, start, end), 0f)
        }
        assertEquals(0f, englishPaperInkWordProgress(start.toFloat(), start, end), 0f)
        assertEquals(.5f, englishPaperInkWordProgress((start + end) / 2f, start, end), 0f)
        assertEquals(1f, englishPaperInkWordProgress(end + 1f, start, end), 0f)
        // Rewinding before the word restores unread ink, rather than a preview.
        assertEquals(-1f, englishPaperInkWordProgress(start - .01f, start, end), 0f)
    }

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

    @Test
    fun `connected words overlap by half a letter and a pause keeps the boundary`() {
        // "the heavens": the previous word ends at 3, the next starts at 4.
        val lead = EnglishWordLead(previousEnd = 3, lead = ENGLISH_WORD_OVERLAP_CHARS)
        assertEquals(-1f, englishPaperInkWordProgress(2.4f, 4, 11, lead), 0f)
        assertEquals(0f, englishPaperInkWordProgress(2.5f, 4, 11, lead), 0f)
        val atEnd = englishPaperInkWordProgress(3f, 4, 11, lead)
        assertTrue(atEnd > 0f && atEnd < 0.1f)
        assertEquals(atEnd, englishPaperInkWordProgress(3.6f, 4, 11, lead), 0f)
        assertEquals(1f, englishPaperInkWordProgress(11f, 4, 11, lead), 0f)
        var previous = -1f
        for (i in 0..300) {
            val p = englishPaperInkWordProgress(2f + i * 0.03f, 4, 11, lead)
            assertTrue(p >= previous)
            previous = p
        }
        // Without a lead (a pause before it) the word still waits for the edge.
        assertEquals(-1f, englishPaperInkWordProgress(3.9f, 4, 11, null), 0f)
        assertEquals(englishPaperInkWordProgress(6f, 4, 11), englishPaperInkWordProgress(6f, 4, 11, null), 0f)
    }
}
