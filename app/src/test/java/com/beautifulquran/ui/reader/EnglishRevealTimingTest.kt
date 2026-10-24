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
    fun `word finishes one hundred milliseconds sooner then holds until next onset`() {
        val window = mediaWashWindow(verse, first, englishRevealDurationMs(1000, 140), 1f)!!
        val next = mediaWashWindow(verse, first.copy(wordPosition = 2, startMs = 2000),
            englishRevealDurationMs(1000, 140), 1f)!!
        assertEquals(1000L, window.startMs)
        assertEquals(900L, window.durationMs)
        for (at in 1900L..1999L step 11L) {
            assertEquals(1f, window.progress(frame(at)), 0f)
            assertEquals(0f, next.progress(frame(at)), 0f)
            assertEquals(1, frame(at).active?.position)
        }
        assertEquals(2, frame(2000).active?.position)
        assertEquals(0f, next.progress(frame(2000)), 0f)
    }

    @Test
    fun `soft edge minimum and short repeat dwell never become opacity pops or stretched repeats`() {
        assertEquals(140, englishRevealDurationMs(140, 140))
        assertEquals(140, englishRevealDurationMs(220, 140))
        assertEquals(80, englishRevealDurationMs(80, 140))
        assertNull(englishRevealDurationMs(null, 140))
    }

    @Test
    fun `one hundred wall milliseconds is converted to media time exactly once at entry speed`() {
        for (speed in listOf(0.5f, 1f, 1.5f, 2f)) {
            val wall = (1000 / speed).toInt()
            val old = mediaWashWindow(verse, first, wall, speed)!!
            val faster = mediaWashWindow(verse, first, englishRevealDurationMs(wall, 140), speed)!!
            assertEquals(old.startMs, faster.startMs)
            assertEquals((100 * speed).toLong(), old.durationMs - faster.durationMs)
        }
    }

    @Test
    fun `paused early finish remains complete and a tap still waits for confirmation`() {
        val window = mediaWashWindow(verse, first, englishRevealDurationMs(1000, 140), 1f)!!
        val tap = HighlightWash.transition(HighlightWashState(), true, false, 1, window, provisional = true)
        assertEquals(0f, HighlightWash.progress(tap, frame(1900)), 0f)
        val confirmed = HighlightWash.transition(tap, true, false, 1, window)
        assertEquals(1f, HighlightWash.progress(confirmed, frame(1900, playing = false)), 0f)
    }
}
