package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import org.junit.Assert.*
import org.junit.Test

class HighlightWashTest {
    private val item = HighlightItem(24, 1, 7)
    private val timing = HighlightTimingContext(1,
        HighlightEngine.PreparedTimings.prepare(listOf(Segment(1, 0, 1000), Segment(2, 1000, 2000))), 0)
    private val window = HighlightWashWindow(24, 1, 0, 0, 1000)
    private fun sample(position: Long, at: Long, playing: Boolean = true, speed: Float = 1f) =
        HighlightPlaybackSample(item, position, at * 1_000_000, playing, speed = speed)

    private fun frame(position: Long) = HighlightSync.frame(
        HighlightSync.sample(null, sample(position, 0), timing), 0)

    @Test
    fun `late entry uses elapsed media progress rather than restarting at zero`() {
        val wash = HighlightWash.transition(HighlightWashState(), true, false, 0, window)
        assertEquals(0.4f, HighlightWash.progress(wash, frame(400)), 0f)
    }

    @Test
    fun `retuning mid word cannot replace its captured window`() {
        val wash = HighlightWash.transition(HighlightWashState(), true, false, 0, window)
        assertSame(wash, HighlightWash.transition(wash, true, false, 0, window.copy(durationMs = 500)))
    }

    @Test
    fun `ordinary ink does not restart for a consecutive same word re say`() {
        val wash = HighlightWash.transition(HighlightWashState(), true, false, 0, window)
        assertSame(wash, HighlightWash.transition(wash, true, false, 0, window.copy(startMs = 1000)))
    }

    @Test
    fun `actual performance rearms even when rapid same-word taps pin the UI activation`() {
        val first = HighlightWash.transition(HighlightWashState(), true, false, 7, window)
        val second = HighlightWash.transition(first, true, false, 7, window.copy(inkGeneration = 2))
        assertEquals(2L, second.window!!.inkGeneration)
        assertEquals(0.4f, HighlightWash.progress(second, frame(400).copy(inkGeneration = 2)), 0f)
    }

    @Test
    fun `reciter identity isolates windows even with the same verse and UI activation`() {
        val first = HighlightWash.transition(HighlightWashState(), true, false, 7, window.copy(reciterId = 1))
        assertFalse(first.window!!.matches(frame(400)))
        val next = HighlightWash.transition(first, true, false, 7, window.copy(reciterId = 7))
        assertEquals(0.4f, HighlightWash.progress(next, frame(400)), 0f)
    }

    @Test
    fun `orange ink does restart for each consecutive same word occurrence`() {
        val first = HighlightWash.transition(HighlightWashState(), true, false, 0, window, repeatOccurrence = true)
        val next = HighlightWash.transition(first, true, false, 0, window.copy(startMs = 1000), repeatOccurrence = true)
        assertEquals(0f, HighlightWash.progress(next, frame(1000)), 0f)
        assertEquals(0.5f, HighlightWash.progress(next, frame(1500)), 0f)
    }

    @Test
    fun `handoff retains the independent residual and an upcoming target abandons it`() {
        val active = HighlightWash.transition(HighlightWashState(), true, false, 0, window)
        val residual = HighlightWash.transition(active, false, true, 0, null)
        assertEquals(0.9f, HighlightWash.progress(residual, frame(900)), 0f)
        assertEquals(1f, HighlightWash.progress(residual, frame(1100)), 0f)
        val cleared = HighlightWash.transition(residual, false, false, 0, null)
        assertNull(cleared.window)
    }

    @Test
    fun `tap remains provisional until confirmation with the actual player generation`() {
        val provisional = HighlightWash.transition(HighlightWashState(), true, false, 7,
            window.copy(inkGeneration = 1), provisional = true)
        assertEquals(0f, HighlightWash.progress(provisional, frame(400)), 0f)
        val confirmed = HighlightWash.transition(provisional, true, false, 7,
            window.copy(inkGeneration = 2), provisional = false)
        val current = frame(400).copy(inkGeneration = 2)
        assertEquals(0.4f, HighlightWash.progress(confirmed, current), 0f)
    }

    @Test
    fun `a later tap completes an abandoned provisional word in its recited prefix`() {
        val provisional = HighlightWash.transition(HighlightWashState(), true, false, 1,
            window.copy(inkGeneration = 1), provisional = true)
        val recited = HighlightWash.transition(provisional, active = false, retain = true,
            activation = 0, window = window.copy(inkGeneration = 2), provisional = true)
        assertNull(recited.window)
        assertEquals(1f, HighlightWash.progress(recited, frame(0)), 0f)
    }

    @Test
    fun `real pause speed and correction events drive the same motion lifecycle`() {
        val wash = HighlightWash.transition(HighlightWashState(), true, false, 0, window)
        var sync = HighlightSync.sample(null, sample(0, 0), timing)
        sync = HighlightSync.sample(sync, sample(400, 400, playing = false), timing)
        assertEquals(0.4f, HighlightWash.progress(wash, HighlightSync.frame(sync, 900_000_000)), 0f)
        sync = HighlightSync.sample(sync, sample(400, 900, speed = 2f), timing)
        assertEquals(0.6f, HighlightWash.progress(wash, HighlightSync.frame(sync, 1_000_000_000)), 0f)
        sync = HighlightSync.sample(sync, sample(500, 1000, speed = 2f), timing)
        assertEquals(0.6f, HighlightWash.progress(wash, HighlightSync.frame(sync, 1_000_000_000)), 0f)
    }

    @Test
    fun `short repeat chain has no queued animation debt`() {
        var previous = HighlightWashState()
        repeat(12) { i ->
            val start = i * 80L
            previous = HighlightWash.transition(previous, true, true, 0,
                window.copy(startMs = start, durationMs = 80), repeatOccurrence = true)
            assertEquals(0.5f, HighlightWash.progress(previous, frame(start + 40)), 0f)
        }
    }

    @Test
    fun `a frame for another item or seek cannot move an old window`() {
        val wash = HighlightWash.transition(HighlightWashState(), true, false, 0, window)
        assertEquals(1f, HighlightWash.progress(wash, frame(400).copy(item = item.copy(ayah = 2))), 0f)
        assertEquals(1f, HighlightWash.progress(wash, frame(400).copy(inkGeneration = 1)), 0f)
    }
}
