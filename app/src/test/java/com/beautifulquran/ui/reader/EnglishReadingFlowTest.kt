package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.AudibleSilence
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.EnglishPhrase
import com.beautifulquran.domain.EnglishWordSpan
import com.beautifulquran.domain.EnglishWordAlignment
import org.junit.Assert.*
import org.junit.Test

class EnglishReadingFlowTest {
    private fun aligned(vararg owners: Int): EnglishAlignment {
        val spans = owners.mapIndexed { i, owner ->
            EnglishWordSpan(i.toFloat() / owners.size, (i + 1f) / owners.size, owner)
        }
        return EnglishAlignment(FloatArray(owners.max()), spans,
            spans.map { EnglishPhrase(it.from, it.to, it.position) })
    }

    @Test
    fun `connected speech keeps one positive rate across ordinary Arabic band boundaries`() {
        val plan = englishPresentationPlan(aligned(1, 2, 3),
            listOf(Segment(1, 0, 200), Segment(2, 200, 1600), Segment(3, 1600, 2400)))
        assertNotNull(plan.readingFlow)
        for (at in listOf(100L, 190L, 200L, 500L, 1590L, 1600L, 2000L)) {
            assertEquals(10f / 2400, englishWashThrough(plan, at + 10)!! - englishWashThrough(plan, at)!!, .000001f)
        }
        assertEquals(800L, plan.windows.getValue(1).endMs)
        assertEquals(1600L, plan.windows.getValue(2).endMs)
        assertEquals(1f, englishWashThrough(plan, 2400)!!, 0f)
        // Punctuation can make a printed token cross an owner partition.
        assertEquals(EnglishWashWindow(600, 1200), englishWordExposure(plan, .25f, .5f))
    }

    @Test
    fun `swallowed owners contribute speech rather than fake pauses`() {
        val plan = englishPresentationPlan(aligned(1, 4),
            (1..4).map { Segment(it, (it - 1) * 1000L, it * 1000L) })
        assertEquals(.375f, englishWashThrough(plan, 1500)!!, .000001f)
        assertEquals(.625f, englishWashThrough(plan, 2500)!!, .000001f)
    }

    @Test
    fun `quiet strictly inside an inversion freezes its fractional edge without snapping`() {
        val alignment = aligned(2, 1, 3).copy(phrases = listOf(EnglishPhrase(0f, 2f / 3, 1, 2),
            EnglishPhrase(2f / 3, 1f, 3)))
        val plan = englishPresentationPlan(alignment,
            listOf(Segment(1, 0, 500), Segment(2, 500, 1500), Segment(3, 1500, 2000)),
            silence = listOf(AudibleSilence(600, 1100)))
        val held = englishWashThrough(plan, 600)!!
        assertEquals(.4f, held, .000001f)
        for (at in listOf(600L, 700L, 1000L, 1100L)) assertEquals(held, englishWashThrough(plan, at)!!, 0f)
        assertEquals(10f / 1500, englishWashThrough(plan, 1110)!! - held, .000001f)
        val clock = plan.readingFlow!!.clock
        assertEquals(600L, clock.mediaAt(held))
        assertEquals(1100L, clock.mediaAt(held, afterGap = true))
    }

    @Test
    fun `all source overlaps union once and clipped overlapping quiet subtracts once`() {
        val plan = englishPresentationPlan(aligned(1, 2, 3),
            listOf(Segment(1, 100, 800), Segment(2, 600, 1500), Segment(3, 1500, 2000)),
            200, listOf(AudibleSilence(-100, 300), AudibleSilence(500, 800), AudibleSilence(700, 1000),
                AudibleSilence(1800, 3000)))
        val clock = plan.readingFlow!!.clock
        assertEquals(listOf(300L..500L, 1000L..1800L), clock.spoken)
        assertEquals(.2f, englishWashThrough(plan, 900)!!, .000001f)
        assertEquals(1f, englishWashThrough(plan, 1900)!!, 0f)
    }

    @Test
    fun `voice gate inverse and fragment follow share the same reading edge`() {
        val plan = englishPresentationPlan(aligned(1, 2),
            listOf(Segment(1, 0, 400), Segment(2, 400, 1000)), 200)
        assertEquals(0f, englishWashThrough(plan, 199)!!, 0f)
        assertEquals(.5f, englishWashThrough(plan, 600)!!, .000001f)
        assertEquals(600L, plan.windows.getValue(1).endMs)
        for (span in plan.spans) {
            val window = plan.windows.getValue(span.position)
            for (at in listOf(window.startMs, (window.startMs + window.endMs) / 2, window.endMs)) {
                val rendered = span.from + (span.to - span.from) * window.progress(at)
                assertEquals(englishWashThrough(plan, at)!!, rendered, .000001f)
            }
        }
    }

    @Test
    fun `repeat and source overlap fallbacks retain their original clocks`() {
        val repeat = aligned(1, 2, 3)
        val timings = listOf(1, 2, 1, 2, 3).mapIndexed { i, owner -> Segment(owner, i * 1000L, (i + 1) * 1000L) }
        val anchor = englishPhrasePlan(repeat, timings)
        val plan = englishPresentationPlan(repeat, timings)
        assertEquals(anchor.windows, plan.windows)
        assertEquals("source_repeat", plan.readingFlowFallback)
        val overlap = aligned(1, 2, 3).copy(phrases = listOf(EnglishPhrase(0f, .6f, 1, 2), EnglishPhrase(.6f, 1f, 2)))
        val forward = listOf(Segment(1, 0, 400), Segment(2, 400, 800), Segment(3, 800, 1200))
        assertEquals(englishPhrasePlan(overlap, forward).windows, englishPresentationPlan(overlap, forward).windows)
        assertEquals("source_overlap", englishPresentationPlan(overlap, forward).readingFlowFallback)
    }

    @Test
    fun `zero audible time has an explicit fallback and never installs a zero duration reading clock`() {
        val plan = englishPresentationPlan(aligned(1, 2),
            listOf(Segment(1, 0, 400), Segment(2, 400, 1000)), silence = listOf(AudibleSilence(0, 1000)))
        assertNull(plan.readingFlow)
        assertEquals("zero_audible_time", plan.readingFlowFallback)
        assertEquals(.5f, plan.windows.getValue(1).progress(200), 0f)
    }

    @Test
    fun `real Hani pause inside a contiguous timing row freezes the shared plan`() {
        val row = javaClass.getResourceAsStream("/english-glorification-owners.tsv")!!.bufferedReader()
            .use { it.readLines().first { row -> row.startsWith("64:1\t") } }.split('\t')
        val alignment = EnglishWordAlignment.align(row[1], row[2].split('|'), row[4].split('|'))!!
        val starts = listOf(1020, 2140, 3150, 3450, 3660, 5090, 5750, 5950, 6770, 7150, 8060, 8590,
            9730, 10370, 10870, 11570, 12890, 14625)
        val segments = starts.zipWithNext().mapIndexed { i, (start, end) -> Segment(i + 1, start.toLong(), end.toLong()) }
        val plan = englishPresentationPlan(alignment, segments, 1020, listOf(AudibleSilence(9442, 9813)))
        val held = englishWashThrough(plan, 9442)!!
        assertEquals(EnglishReadingExtent(row[1]).printed((9442f - 1020) / (14625 - 1020 - 371)), held, .000001f)
        for (at in 9442L..9813L step 17) assertEquals(held, englishWashThrough(plan, at)!!, .000001f)
        assertEquals(held, englishWashThrough(plan, 9813)!!, .000001f)
        assertTrue(englishWashThrough(plan, 9814)!! > held)
        assertEquals(9442L, plan.readingFlow!!.mediaAt(held))
        assertEquals(9813L, plan.readingFlow.mediaAt(held, afterGap = true))
    }

    @Test
    fun `non binary pause ratios choose earliest finish and latest start through projection`() {
        for ((before, resume, end) in listOf(Triple(30647L, 33107L, 53017L), Triple(52524L, 53848L, 83843L))) {
            val clock = EnglishWashWindow(0, end, listOf(0L..before, resume..end))
            val held = clock.progress(before)
            assertEquals(before, clock.mediaAt(held))
            assertEquals(resume, clock.mediaAt(held, afterGap = true))
            val projected = EnglishReadingWindow(EnglishReadingFlow(clock), .1f, .9f)
            val local = projected.progress(before)
            assertEquals(before, projected.mediaAt(local, afterGap = false))
            assertEquals(resume, projected.mediaAt(local, afterGap = true))
        }
        val random = kotlin.random.Random(878)
        repeat(10000) {
            val before = random.nextLong(1, 200000)
            val resume = before + random.nextLong(1, 200000)
            val end = resume + random.nextLong(1, 200000)
            val clock = EnglishWashWindow(0, end, listOf(0L..before, resume..end))
            val held = clock.progress(before)
            assertEquals(before, clock.mediaAt(held))
            assertEquals(resume, clock.mediaAt(held, afterGap = true))
        }
    }

    @Test
    fun `a gate past a reading window retains finite completed progress`() {
        val plan = englishPresentationPlan(aligned(1, 2, 3),
            listOf(Segment(1, 0, 200), Segment(2, 200, 1600), Segment(3, 1600, 2400)))
        val first = plan.windows.getValue(1)
        assertEquals(1f, first.progress(1000, 1000), 0f)
        assertEquals(800L, first.mediaAt(.5f, voiceStartMs = 1000))
    }

    @Test
    fun `rounded inverse endpoint near quiet never gates the fractional runtime edge`() {
        val spans = listOf(EnglishWordSpan(0f, .39999f, 1), EnglishWordSpan(.39999f, 1f, 2))
        val alignment = EnglishAlignment(FloatArray(2), spans,
            spans.map { EnglishPhrase(it.from, it.to, it.position) })
        val plan = englishPresentationPlan(alignment, listOf(Segment(1, 0, 500), Segment(2, 500, 2000)),
            silence = listOf(AudibleSilence(600, 1100)))
        assertEquals(1100L, plan.windows.getValue(2).startMs)
        for (at in listOf(600L, 800L, 1099L, 1100L)) {
            assertEquals(.4f, englishWashThrough(plan, at)!!, .0000001f)
        }
    }
}
