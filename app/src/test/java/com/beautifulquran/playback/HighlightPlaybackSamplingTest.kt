package com.beautifulquran.playback

import com.beautifulquran.domain.HighlightItem
import org.junit.Assert.*
import org.junit.Test

class HighlightPlaybackSamplingTest {
    private val item = NowPlaying(24, 33, 7)
    private val state = PlayerUiState(
        isConnected = true, nowPlaying = item, isPlaying = true, speed = 1.5f,
        positionEvents = PlaybackPositionEvents(clockId = 9, inkId = 4),
    )

    private fun capture(
        before: PlayerUiState = state, after: PlayerUiState = before,
        liveBefore: NowPlaying? = item, liveAfter: NowPlaying? = liveBefore, position: Long = 100,
    ) = coherentHighlightSample(item, before, after, liveBefore, liveAfter, position, 1_000_000)

    @Test
    fun `one packet carries position transport speed and both revisions`() {
        val sample = capture()!!
        assertEquals(HighlightItem(24, 33, 7), sample.item)
        assertEquals(100L, sample.positionMs)
        assertEquals(1_000_000L, sample.sampledAtNanos)
        assertEquals(1.5f, sample.speed, 0f)
        assertEquals(9L, sample.clockRevision)
        assertEquals(4L, sample.inkGeneration)
        assertTrue(sample.advances)
    }

    @Test
    fun `handoff before or during position read is skipped without emitting a false word`() {
        val next = item.copy(ayah = 34)
        assertNull(capture(liveBefore = next))
        assertNull(capture(liveAfter = next))
        assertNull(capture(before = state.copy(nowPlaying = next)))
    }

    @Test
    fun `a same verse reciter or chapter switch is also a handoff`() {
        assertNull(capture(liveAfter = item.copy(reciterId = 1)))
        assertNull(capture(liveAfter = item.copy(surahId = 25)))
    }

    @Test
    fun `seek pause speed and buffer changes spanning the read cannot mix generations`() {
        for (changed in listOf(
            state.copy(positionEvents = state.positionEvents.copy(clockId = 10, inkId = 5)),
            state.copy(isPlaying = false), state.copy(isBuffering = true), state.copy(speed = 2f),
        )) assertNull(capture(after = changed))
    }

    @Test
    fun `unavailable controller and negative position have no coherent sample`() {
        assertNull(capture(liveBefore = null))
        assertNull(capture(position = -1))
    }

    @Test
    fun `paused and buffering samples do not project ongoing audio`() {
        assertFalse(capture(before = state.copy(isPlaying = false))!!.advances)
        assertFalse(capture(before = state.copy(isBuffering = true))!!.advances)
    }
}
