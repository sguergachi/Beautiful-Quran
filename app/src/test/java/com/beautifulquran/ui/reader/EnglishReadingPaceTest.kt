package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.AudibleSilence
import com.beautifulquran.domain.EnglishWordAlignment
import org.junit.Assert.*
import org.junit.Test

class EnglishReadingPaceTest {
    private val text = "Mercy guides people."
    private val alignment = EnglishWordAlignment.align(text, listOf("Mercy", "guides", "people"))!!
    private val segments = listOf(Segment(1, 0, 1000), Segment(2, 1000, 1600), Segment(3, 1600, 3000))

    @Test
    fun `literal matches finish one hundred milliseconds before their spoken ends`() {
        val plan = englishPresentationPlan(alignment, segments)
        assertEquals(2, plan.readingFlow!!.pace!!.finishes.size)
        for ((word, expected) in alignment.timingWords.zip(listOf(900L, 1500L, 3000L))) {
            val exposure = englishWordExposure(plan, word.from, word.to)!!
            assertEquals(expected, exposure.endMs)
            val range = (word.from * text.length).toInt() until (word.to * text.length).toInt()
            assertEquals(1f, englishPaperInkWordProgress(englishWashThrough(plan, expected)!! * text.length,
                range.first, range.last + 1), .000001f)
        }
    }

    @Test
    fun `interior word deadlines keep positive continuous velocity without stopping`() {
        val plan = englishPresentationPlan(alignment, segments)
        val flow = plan.readingFlow!!
        for (at in listOf(900L, 1500L)) {
            val before = flow.ink(flow.through(at)) - flow.ink(flow.through(at - 1))
            val after = flow.ink(flow.through(at + 1)) - flow.ink(flow.through(at))
            assertTrue(before > 0f)
            assertTrue(after > 0f)
            assertEquals(before, after, .000005f)
        }
        var previous = 0f
        for (at in 0L..3000L) {
            val edge = englishWashThrough(plan, at)!!
            assertTrue("retreat at $at", edge >= previous)
            previous = edge
        }
    }

    @Test
    fun `quiet still holds the fractional edge and inverse chooses both pause endpoints`() {
        val plan = englishPresentationPlan(alignment, segments, silence = listOf(AudibleSilence(1100, 1300)))
        val flow = plan.readingFlow!!
        val held = flow.through(1100)
        for (at in 1100L..1300L) assertEquals(held, flow.through(at), 0f)
        assertEquals(1100L, flow.mediaAt(held))
        assertEquals(1300L, flow.mediaAt(held, afterGap = true))
        assertTrue(flow.through(1301) > held)
        for (span in plan.spans) {
            val window = plan.windows.getValue(span.position)
            val at = (window.startMs + window.endMs) / 2
            assertEquals(flow.through(at), span.from + (span.to - span.from) * window.progress(at), .000001f)
        }
    }

    @Test
    fun `reordered literal words stay a readable phrase rather than acquiring crossed deadlines`() {
        val reordered = EnglishWordAlignment.align("painful punishment follows mercy", listOf("punishment", "painful", "follows", "mercy"))!!
        val plan = englishPresentationPlan(reordered, listOf(Segment(1, 0, 500), Segment(2, 500, 1500),
            Segment(3, 1500, 2300), Segment(4, 2300, 3000)))
        assertEquals(listOf(3), plan.readingFlow!!.pace!!.finishes.map { it.word.position })
        assertEquals(2, plan.readingFlow.pace.rejected["reordered_phrase"])
        val flow = plan.readingFlow
        for (at in 0L..1400L step 7) {
            val linear = flow.clock.progress(at)
            if (flow.extent!!.printed(linear) >= reordered.presentationSpans.first().to) break
            assertEquals(linear, flow.ink(flow.through(at)), .000001f)
        }
    }

    @Test
    fun `short words keep positive exposure and late gates never acquire spent deadlines`() {
        val short = listOf(Segment(1, 0, 40), Segment(2, 40, 100), Segment(3, 100, 300))
        val plan = englishPresentationPlan(alignment, short)
        assertEquals(listOf(40L, 100L), plan.readingFlow!!.pace!!.finishes.map { it.deadlineMs })
        val oneLetter = EnglishWordAlignment.align("X guides people.", listOf("X", "guides", "people"))!!
        val gated = englishPresentationPlan(oneLetter, segments, 950)
        assertEquals(1000L, gated.readingFlow!!.pace!!.finishes.first().deadlineMs)
        assertEquals("voice_gate_relaxed", gated.readingFlow.pace.finishes.first().leadReason)
        assertEquals(0f, englishWashThrough(gated, 949)!!, 0f)
        // A gate beyond an entire existing source window retains the old fallback.
        assertEquals("source_window", englishPresentationPlan(alignment, segments, 1200).readingFlowFallback)
    }

    @Test
    fun `only literal verified provenance sets a deadline while cards remain locked`() {
        val mixed = EnglishWordAlignment.align("Mercy mystery [people]", listOf("Mercy", "guides", "people"))!!
        assertEquals(listOf("Mercy"), mixed.timingWords.map {
            mixed.readingText!!.substring((it.from * mixed.readingText.length).toInt(), (it.to * mixed.readingText.length).toInt())
        })
        val card = englishPresentationPlan(alignment.copy(phrases = null), segments)
        assertNull(card.readingFlow)
        assertEquals(englishWashWindows(alignment.washSpans.map { it.position }, segments), card.windows)
        val joined = EnglishWordAlignment.align("un-Mercy guides people", listOf("Mercy", "guides", "people"))!!
        assertFalse(joined.timingWords.any { it.position == 1 })
    }

    @Test
    fun `one expanded source unit uses its final word and quiet targets relax to a voiced end`() {
        val shared = EnglishWordAlignment.align(text, listOf("Mercy guides", "people"))!!
        val unit = englishPresentationPlan(shared, listOf(Segment(1, 0, 1000), Segment(2, 1000, 3000)))
        assertEquals(1, unit.readingFlow!!.pace!!.finishes.size)
        assertEquals(900L, unit.readingFlow.pace.finishes.single().deadlineMs)
        assertEquals(1, unit.readingFlow.pace.rejected["same_source_unit"])
        val quiet = englishPresentationPlan(alignment, segments, silence = listOf(AudibleSilence(850, 950)))
        val finish = quiet.readingFlow!!.pace!!.finishes.first()
        assertEquals(900L, finish.desiredMs)
        assertEquals(1000L, finish.deadlineMs)
        assertEquals("quiet_relaxed", finish.leadReason)
    }

    @Test
    fun `overlapping ends and identical audible targets never become epsilon separated deadlines`() {
        val overlap = englishPresentationPlan(alignment, listOf(Segment(1, 0, 1000),
            Segment(2, 500, 900), Segment(3, 900, 3000)))
        assertEquals(listOf(1), overlap.readingFlow!!.pace!!.finishes.map { it.word.position })
        assertEquals(1, overlap.readingFlow.pace.rejected["crossing_target"])
        val quiet = englishPresentationPlan(alignment, listOf(Segment(1, 0, 1000),
            Segment(2, 600, 1100), Segment(3, 1100, 3000)), silence = listOf(AudibleSilence(800, 1200)))
        assertEquals(listOf(1), quiet.readingFlow!!.pace!!.finishes.map { it.word.position })
        assertEquals(800L, quiet.readingFlow.pace.finishes.single().deadlineMs)
        assertEquals(1, quiet.readingFlow.pace.rejected["crossing_target"])
    }

    @Test
    fun `no admitted matches is identity on the current audible and blank clock`() {
        val reordered = EnglishWordAlignment.align("painful punishment", listOf("punishment", "painful"))!!
        val source = listOf(Segment(1, 0, 500), Segment(2, 500, 3000))
        val quiet = listOf(AudibleSilence(800, 1200))
        val current = englishPresentationPlan(reordered, source, silence = quiet)
        val baseline = englishPresentationPlan(reordered.copy(timingWords = emptyList()), source, silence = quiet)
        assertTrue(current.readingFlow!!.pace!!.finishes.isEmpty())
        for (at in 0L..3000L step 7) {
            assertEquals(englishWashThrough(baseline, at)!!, englishWashThrough(current, at)!!, .000001f)
        }
    }

    @Test
    fun `real terminal residual keeps every word core and punctuation on the existing clock`() {
        val row = javaClass.getResourceAsStream("/english-quran-ai-opening-owners.tsv")!!.bufferedReader()
            .use { it.readLines().first { row -> row.startsWith("24:2\t") } }.split('\t')
        val aligned = EnglishWordAlignment.align(row[1], row[2].split('|'), row[4].split('|'), row[5].split('|'))!!
        val source = (1..26).map { Segment(it, (it - 1) * 1000L, it * 1000L) }
        val plan = englishPresentationPlan(aligned, source)
        val baseline = englishPresentationPlan(aligned.copy(timingWords = emptyList()), source)
        val tail = aligned.presentationSpans.last().from
        for (at in 0L..26000L step 17) {
            val old = englishWashThrough(baseline, at)!!
            if (old >= tail) assertEquals(old, englishWashThrough(plan, at)!!, .000001f)
        }
        val punishment = row[1].indexOf("punishment").toFloat() / row[1].length
        assertEquals(englishWordExposure(baseline, punishment, 1f), englishWordExposure(plan, punishment, 1f))
        assertEquals(26000L, plan.windows.values.last().endMs)
    }

    @Test
    fun `paced quiet round trips and monotone edges survive varied source ratios`() {
        val random = kotlin.random.Random(878)
        repeat(200) {
            val first = random.nextLong(400, 5000)
            val second = first + random.nextLong(400, 5000)
            val end = second + random.nextLong(400, 5000)
            val pause = random.nextLong(10, end - 300)
            val resume = minOf(pause + random.nextLong(50, 300), end - 1)
            val plan = englishPresentationPlan(alignment, listOf(Segment(1, 0, first), Segment(2, first, second),
                Segment(3, second, end)), silence = listOf(AudibleSilence(pause, resume)))
            val flow = plan.readingFlow!!
            assertTrue(flow.pace!!.hasPositiveVelocity())
            val held = flow.through(pause)
            assertEquals(held, flow.through(resume), 0f)
            assertEquals(pause.toDouble(), flow.mediaAt(held).toDouble(), 1.0)
            assertEquals(resume.toDouble(), flow.mediaAt(held, afterGap = true).toDouble(), 1.0)
            var previous = 0f
            for (step in 0..100) {
                val edge = flow.through(end * step / 100)
                assertTrue(edge >= previous)
                previous = edge
            }
        }
    }
}
