package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.HighlightEngine
import com.beautifulquran.domain.HighlightItem
import com.beautifulquran.domain.HighlightPlaybackSample
import com.beautifulquran.domain.HighlightSync
import com.beautifulquran.domain.HighlightTimingContext
import com.beautifulquran.domain.HighlightWash
import com.beautifulquran.domain.HighlightWashState
import org.junit.Assert.*
import org.junit.Test

class EnglishRevealTimingTest {
    private val verse = Ayah(64, 11, "", "", words = emptyList())
    private val first = ActiveWord(11, 1, startMs = 1000, durationMs = 1000, activation = 1)
    private val timing = HighlightTimingContext(1, HighlightEngine.PreparedTimings.prepare(listOf(
        Segment(1, 1000, 1700), Segment(2, 2000, 3000),
    )), 1000)

    private fun frame(position: Long, playing: Boolean = true) = HighlightSync.frame(
        HighlightSync.sample(null, HighlightPlaybackSample(
            HighlightItem(64, 11, 1), position, 0, playing, inkGeneration = 1,
        ), timing), 0,
    )

    @Test
    fun `connected speech flows to the next onset without stopping`() {
        // A 10 ms segment gap is connected speech: the sweep spans the whole hold.
        val joined = first.copy(spokenMs = 990)
        val window = mediaWashWindow(verse, joined, englishRevealDurationMs(1000, joined.spokenMs), 1f)!!
        val next = mediaWashWindow(verse, first.copy(wordPosition = 2, startMs = 2000),
            englishRevealDurationMs(1000), 1f)!!
        assertEquals(1000L, window.startMs)
        assertEquals(1000L, window.durationMs)
        var previous = 0f
        for (at in 1000L..1999L step 11L) {
            val progress = window.progress(frame(at))
            assertTrue("still moving at $at", progress < 1f && progress >= previous)
            previous = progress
        }
        assertEquals(0f, next.progress(frame(2000)), 0f)
    }

    @Test
    fun `pause threshold separates connected speech from a held silence`() {
        assertEquals(1000, englishRevealDurationMs(1000, 1000 - ENGLISH_PAUSE_MS + 1))
        assertEquals((1000 - ENGLISH_PAUSE_MS).toInt(), englishRevealDurationMs(1000, 1000 - ENGLISH_PAUSE_MS))
        // At 2x the same media gap is half the wall time; the threshold is media.
        assertEquals(500, englishRevealDurationMs(500, 1000 - ENGLISH_PAUSE_MS + 1, 2f))
        assertEquals(425, englishRevealDurationMs(500, 1000 - ENGLISH_PAUSE_MS, 2f))
    }

    @Test
    fun `floored sweep and short repeat dwell keep their existing duration`() {
        assertEquals(140, englishRevealDurationMs(140))
        assertEquals(220, englishRevealDurationMs(220))
        assertEquals(80, englishRevealDurationMs(80))
        assertNull(englishRevealDurationMs(null))
    }

    @Test
    fun `paused finish remains complete and a tap still waits for confirmation`() {
        val window = mediaWashWindow(verse, first, englishRevealDurationMs(1000, 700), 1f)!!
        val tap = HighlightWash.transition(HighlightWashState(), true, false, 1, window, provisional = true)
        assertEquals(0f, HighlightWash.progress(tap, frame(1900)), 0f)
        val confirmed = HighlightWash.transition(tap, true, false, 1, window)
        assertEquals(1f, HighlightWash.progress(confirmed, frame(1900, playing = false)), 0f)
    }

    @Test
    fun `voiced end finishes the edge before the entire pause not just its final hundred milliseconds`() {
        val spoken = first.copy(spokenMs = 700)
        val window = mediaWashWindow(verse, spoken, englishRevealDurationMs(
            InkEngine.sweepMs(spoken, 1f), spoken.spokenMs, 1f,
        ), 1f)!!
        val next = mediaWashWindow(verse, first.copy(wordPosition = 2, startMs = 2000), 900, 1f)!!
        assertEquals(700L, window.durationMs)
        assertTrue(window.progress(frame(1350)) in 0.01f..0.99f)
        for (at in 1700L..1999L step 11L) {
            assertEquals(1f, window.progress(frame(at)), 0f)
            assertEquals(0f, next.progress(frame(at)), 0f)
            assertEquals(1, frame(at).active?.position)
        }
        assertEquals(1f, window.progress(frame(1750, playing = false)), 0f)
    }

    @Test
    fun `short voiced word keeps a sweep but its floor cannot overrun the spoken end`() {
        val spoken = first.copy(durationMs = 300, spokenMs = 80)
        val window = mediaWashWindow(verse, spoken, englishRevealDurationMs(
            InkEngine.sweepMs(spoken, 1f), spoken.spokenMs, 1f,
        ), 1f)!!
        assertEquals(80L, window.durationMs)
        assertEquals(0f, window.progress(frame(1000)), 0f)
        val eased = InkEngine.sweepEasing.transform(window.progress(frame(1040)))
        assertTrue(eased > 0f && eased < 1f)
        assertEquals(1f, window.progress(frame(1080)), 0f)
    }

    @Test
    fun `repeat ends at its own voiced boundary without moving its occurrence start`() {
        val repeat = first.copy(startMs = 2000, durationMs = 400, spokenMs = 60, isRepeat = true, activation = 2)
        val window = mediaWashWindow(verse, repeat, englishRevealDurationMs(
            InkEngine.repeatDwellMs(repeat, 1f), repeat.spokenMs, 1f,
        ), 1f)!!
        assertEquals(2000L, window.startMs)
        assertEquals(60L, window.durationMs)
        assertEquals(0f, window.progress(frame(2000)), 0f)
        assertEquals(0.5f, window.progress(frame(2030)), 0f)
        assertEquals(1f, window.progress(frame(2060)), 0f)
        assertEquals(1f, window.progress(frame(2399)), 0f)
    }

    @Test
    fun `voiced deadline survives speed conversion even below one wall millisecond`() {
        for (speed in listOf(0.5f, 1f, 1.5f, 2f)) {
            for (voiced in listOf(1L, 79L, 140L, 700L, 1000L)) {
                val spoken = first.copy(spokenMs = voiced)
                val duration = englishRevealDurationMs(
                    InkEngine.sweepMs(spoken, speed), voiced, speed,
                )
                val window = mediaWashWindow(verse, spoken, duration, speed)!!
                assertEquals(first.startMs, window.startMs)
                assertTrue("$voiced ms at $speed x: ${window.durationMs}", window.durationMs in 1L..voiced)
                assertEquals(1f, window.progress(frame(first.startMs + voiced)), 0f)
            }
        }
    }

    @Test
    fun `missing voiced metadata keeps the full hold`() {
        assertEquals(1000, englishRevealDurationMs(1000, spokenMs = null))
        assertEquals(1000, englishRevealDurationMs(1000, spokenMs = 0))
        assertEquals(1000, englishRevealDurationMs(1000, spokenMs = 1000))
        assertNull(englishRevealDurationMs(null, spokenMs = 700))
    }
}
