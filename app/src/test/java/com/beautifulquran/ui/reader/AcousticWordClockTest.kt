package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticWordClockTest {

    @Test
    fun `anchor extrapolation follows media phase and playback speed`() {
        val anchor = AcousticClockAnchor(
            ayah = 1,
            wordPosition = 1,
            epoch = 1,
            mediaPositionMs = 300,
            realtimeNanos = 1_000_000_000,
            playbackSpeed = 2f,
            startMs = 100,
            holdEndMs = 500,
        )

        assertEquals(0.5f, anchor.progressAt(1_000_000_000), 0f)
        assertEquals(1f, anchor.progressAt(1_100_000_000), 0f)
    }

    @Test
    fun `anchor phase is raw segment progress for momentum pressure`() {
        val anchor = AcousticClockAnchor(
            ayah = 1,
            wordPosition = 1,
            epoch = 1,
            mediaPositionMs = 300,
            realtimeNanos = 0,
            playbackSpeed = 0f,
            startMs = 100,
            holdEndMs = 500,
        )
        // 200ms into a 400ms segment → 0.5 (no pre-roll stretch).
        assertEquals(0.5f, anchor.progressAt(0), 0f)
        assertEquals(0f, anchor.copy(mediaPositionMs = 100).progressAt(0), 0f)
        assertEquals(1f, anchor.copy(mediaPositionMs = 500).progressAt(0), 0f)
    }

    @Test
    fun `paused anchor freezes and a degenerate hold settles`() {
        val paused = AcousticClockAnchor(
            ayah = 1,
            wordPosition = 1,
            epoch = 1,
            mediaPositionMs = 300,
            realtimeNanos = 0,
            playbackSpeed = 0f,
            startMs = 100,
            holdEndMs = 500,
        )
        assertEquals(0.5f, paused.progressAt(500_000_000), 0f)
        assertEquals(1f, paused.copy(holdEndMs = 100).progressAt(0), 0f)
    }

    @Test
    fun `torn clock delivery never empties the visible word`() {
        val anchor = AcousticClockAnchor(
            ayah = 1,
            wordPosition = 2,
            epoch = 2,
            mediaPositionMs = 100,
            realtimeNanos = 0,
            playbackSpeed = 1f,
            startMs = 100,
            holdEndMs = 500,
        )

        assertEquals(1f, acousticProgressFrame(0.8f, 1, anchor, 0), 0f)
        assertEquals(0f, acousticProgressFrame(0.8f, 3, anchor, 0), 0f)
        assertEquals(0.8f, acousticProgressFrame(0.8f, 2, anchor, 0), 0f)
    }
}
