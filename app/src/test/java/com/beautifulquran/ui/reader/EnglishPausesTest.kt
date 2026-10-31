package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.AudibleSilence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class EnglishPausesTest {
    /** 8 kHz voice-like tone with a quiet stretch (reverb level) between [quietFrom, quietTo) ms. */
    private fun pcm(totalMs: Int, quietFrom: Int, quietTo: Int): FloatArray = FloatArray(totalMs * 8) { i ->
        val ms = i / 8
        val level = if (ms in quietFrom until quietTo) 0.01f else 0.5f
        level * sin(i * 0.3f)
    }

    @Test
    fun `a breath inside a word is found from the audio`() {
        val found = EnglishPauses.detect(pcm(3000, 1200, 1600), 160, 20.0)
        assertEquals(1, found.size)
        assertEquals(1200.0, found[0].startMs.toDouble(), 40.0)
        assertEquals(1600.0, found[0].endMs.toDouble(), 40.0)
        // A dip shorter than a pause is speech.
        assertTrue(EnglishPauses.detect(pcm(3000, 1200, 1300), 160, 20.0).isEmpty())
    }

    // Word 1 is timed 0..1500 but the reciter stops at 1000: the timings never say so.
    private val segments = listOf(Segment(1, 0, 1500), Segment(2, 1500, 2500))
    private val pause = listOf(AudibleSilence(1000, 1500))

    private fun clock(lead: Float) = requireNotNull(EnglishScrollClock.of(intArrayOf(10, 10), intArrayOf(1, 2), { null },
        segments, 100f, pause, lead) { null })

    @Test
    fun `Scroll English lands as the voice stops and holds the whole pause`() {
        val held = clock(0f)
        assertEquals(1f, held.progress(0, 1000)!!, 0.01f)
        for (t in 1000L..1500L step 20) assertEquals(0f, held.progress(1, t)!!, 0f)
        assertTrue(held.progress(1, 1600)!! > 0f)
        // Without the heard pause the gloss was still moving at 1200.
        val blind = requireNotNull(EnglishScrollClock.of(intArrayOf(10, 10), intArrayOf(1, 2), { null },
            segments, 100f) { null })
        assertTrue(blind.progress(0, 1200)!! < 0.95f)
    }

    @Test
    fun `with a lead the next word still leaves with the voice`() {
        val lead = 200L
        val led = clock(lead.toFloat())
        // Ink time runs `lead` ahead of media time: it lands early ...
        assertEquals(1f, led.progress(0, 1000)!!, 0.01f)
        // ... but holds until the voice is back at media 1500 (ink 1700).
        for (t in 1000L..1500L + lead step 20) assertEquals(0f, led.progress(1, t)!!, 0f)
        assertTrue(led.progress(1, 1500 + lead + 60)!! > 0f)
        assertEquals(1f, led.progress(1, 2500)!!, 0.01f)
    }

    @Test
    fun `Mushaf reading clock holds a heard pause until the voice returns`() {
        val text = "aaaa bbbb cccc dddd"
        val alignment = requireNotNull(com.beautifulquran.domain.EnglishWordAlignment.align(text,
            listOf("aaaa", "bbbb", "cccc", "dddd"), emptyList()))
        val timed = listOf(Segment(1, 0, 1000), Segment(2, 1000, 2000), Segment(3, 2000, 3000), Segment(4, 3000, 4000))
        val plan = englishPresentationPlan(alignment, timed, silence = listOf(AudibleSilence(1600, 2000)),
            smoothMs = 100f, leadMs = 200f)
        val flow = plan.readingFlow ?: return
        val stop = flow.through(1600)
        for (t in 1600L..2200L step 20) assertEquals(stop, flow.through(t), 1e-4f)
        assertTrue(flow.through(2300) > stop)
    }

    @Test
    fun `pauses found after a verse starts still reach the words to come`() {
        val url = "test://late-${System.nanoTime()}"
        // Playback started before detection finished: Mushaf's set is locked empty.
        EnglishPauses.pin(1, 3, 7, 1L, url, positionMs = 500, voiceStartMs = 0)
        assertTrue(EnglishPauses.pinnedFor(3, 7).isEmpty())
        // Detection lands: only pauses after the next word (and the margin) are adopted, for Scroll.
        val field = EnglishPauses::class.java.getDeclaredField("ready").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ready = field.get(EnglishPauses) as MutableMap<String, EnglishPauses.Loudness>
        // 20 ms hops of loud voice with deep quiet at 700–900 and 16760–17560 ms.
        val db = FloatArray(1000) { h -> if (h * 20 in 700 until 900 || h * 20 in 16760 until 17560) -30f else -3f }
        synchronized(ready) { ready[url] = EnglishPauses.Loudness(20.0, db) }
        EnglishPauses.pin(1, 3, 7, 1L, url, positionMs = 600, voiceStartMs = 0, nextWordMs = 1000)
        assertEquals(listOf(AudibleSilence(16760, 17560)), EnglishPauses.aheadFor(3, 7))
        assertTrue(EnglishPauses.pinnedFor(3, 7).isEmpty())
    }

    @Test
    fun `a shallow quiet counts only next to a marked stop`() {
        // A −15 dB, 400 ms quiet around 53135 ms, and the same at an unmarked 25000 ms.
        val db = FloatArray(3000) { h ->
            val ms = h * 20
            if (ms in 53140 until 53540 || ms in 25000 until 25400) -15f else -3f
        }
        val loudness = EnglishPauses.Loudness(20.0, db)
        assertTrue(loudness.pauses.isEmpty())
        val marked = loudness.pausesWith(listOf(53135L))
        assertEquals(1, marked.size)
        assertEquals(53140.0, marked[0].startMs.toDouble(), 20.0)
        assertTrue(EnglishPauses.marksStop("رَبِّنَاۗ"))
        assertTrue(EnglishPauses.marksStop("مُتَشَٰبِهَٰتٞۖ"))
        assertTrue(!EnglishPauses.marksStop("رَبِّنَا"))
    }

    @Test
    fun `a word timed just before the silence does not take a sliver of ink`() {
        // 3:7: "wa ma" is timed from 53135 but the reciter is silent 53160..53560.
        val timed = listOf(Segment(1, 51875, 53135), Segment(2, 53135, 54135))
        val clock = requireNotNull(EnglishScrollClock.of(intArrayOf(8, 7), intArrayOf(1, 2), { null }, timed, 100f,
            listOf(AudibleSilence(53160, 53560)), 200f) { null })
        for (t in 53160L..53740L step 20) assertEquals(0f, clock.progress(1, t)!!, 0f)
        assertTrue(clock.progress(1, 53900)!! > 0.2f)
        assertEquals(1f, clock.progress(0, 53160)!!, 0.01f)
    }
}

