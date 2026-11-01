package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.AudibleSilence
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.EnglishPhrase
import com.beautifulquran.domain.EnglishWordSpan
import com.beautifulquran.domain.EnglishLeafVerse
import org.junit.Assert.*
import org.junit.Test

class EnglishReadingExtentTest {
    private fun aligned(text: String): EnglishAlignment {
        val spans = listOf(EnglishWordSpan(0f, .25f, 1), EnglishWordSpan(.25f, .625f, 2),
            EnglishWordSpan(.625f, 1f, 3))
        return EnglishAlignment(FloatArray(3), spans,
            spans.map { EnglishPhrase(it.from, it.to, it.position) }, text)
    }
    private val timings = listOf(Segment(1, 0, 200), Segment(2, 200, 400), Segment(3, 400, 600))

    @Test
    fun `word space has zero travel time with no anticipation of the next word`() {
        val plan = englishPresentationPlan(aligned("We go on"), timings)
        val previous = englishWordExposure(plan, 0f, .25f)!!
        val next = englishWordExposure(plan, .375f, .625f)!!
        assertEquals(200L, previous.endMs)
        assertEquals(previous.endMs, next.startMs)
        val before = englishWashThrough(plan, 199)!! * 8
        val boundary = englishWashThrough(plan, 200)!! * 8
        assertEquals(-1f, englishPaperInkWordProgress(before, 3, 5), 0f)
        assertEquals(0f, englishPaperInkWordProgress(boundary, 3, 5), .000001f)
        assertEquals(1f, englishPaperInkWordProgress(boundary, 0, 2), 0f)
        assertTrue(englishPaperInkWordProgress(englishWashThrough(plan, 201)!! * 8, 3, 5) > 0)
        assertEquals(600L, plan.windows.getValue(3).endMs)
    }

    @Test
    fun `ordinary and Unicode blanks disappear from reading distance without changing text`() {
        for (text in listOf("We go on", "We\u202Fgo\non")) {
            val extent = EnglishReadingExtent(text)
            assertEquals(.375f, extent.printed(1f / 3), .000001f)
            assertEquals(1f / 3, extent.ink(.25f), .000001f)
            assertEquals(1f / 3, extent.ink(.375f), .000001f)
            for (at in 0..1000) {
                val through = at / 1000f
                assertEquals(through, extent.ink(extent.printed(through)), .000001f)
            }
        }
    }

    @Test
    fun `audio quiet retains its duration and exact prefix with the compressed blank map`() {
        val plan = englishPresentationPlan(aligned("We go on"), timings,
            silence = listOf(AudibleSilence(50, 150)))
        val held = englishWashThrough(plan, 50)!!
        for (at in 50L..150L) assertEquals(held, englishWashThrough(plan, at)!!, 0f)
        assertEquals(50L, plan.readingFlow!!.mediaAt(held))
        assertEquals(150L, plan.readingFlow.mediaAt(held, afterGap = true))
        assertEquals(1f, englishWashThrough(plan, 600)!!, 0f)
    }

    @Test
    fun `word cards keep their legacy blank space timing despite presentation metadata`() {
        val alignment = aligned("We go on").copy(phrases = null)
        val plan = englishPresentationPlan(alignment, timings)
        assertNull(plan.readingFlow)
        assertEquals(englishWashWindows(alignment.washSpans.map { it.position }, timings), plan.windows)
    }

    @Test
    fun `extent composed with real fragment projection starts next word unread and carries at zero`() {
        val source = "clear evidence"
        val extent = EnglishReadingExtent(source)
        val boundary = extent.printed(5f / 13)
        val verse = EnglishLeafVerse(24, 1, source, source = source)
        val printed = verse.fragmentWashProgress(boundary) * source.length
        assertEquals(6f, printed, .000001f)
        assertEquals(0f, englishPaperInkWordProgress(printed, 6, source.length), .000001f)
        val carried = EnglishLeafVerse(24, 1, "evidence", from = 5, textFrom = 6,
            to = source.length, verseLength = source.length, source = source)
        assertEquals(0f, carried.fragmentWashProgress(boundary), .000001f)
        assertEquals(0f, carried.fragmentWashProgress(extent.printed(5f / 13 - .0001f)), 0f)
        assertTrue(carried.fragmentWashProgress(extent.printed(5f / 13 + .0001f)) > 0f)
    }

    @Test
    fun `normalized source spacing conservatively retains continuous legacy fragment projection`() {
        val source = "clear   evidence"
        val alignment = aligned(source)
        val plan = englishPresentationPlan(alignment, timings)
        assertNull(plan.readingFlow!!.extent)
        assertEquals("normalized_spacing", plan.readingFlow.extentFallback)
        val verse = EnglishLeafVerse(24, 1, "clear evidence", to = source.length,
            verseLength = source.length, source = source)
        for (at in 0L..600L) {
            assertEquals(verse.fragmentWashProgress(at / 600f),
                verse.fragmentWashProgress(englishWashThrough(plan, at)!!), .000001f)
        }
    }
}
