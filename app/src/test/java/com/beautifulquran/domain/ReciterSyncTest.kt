package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class ReciterSyncTest {
    @Test
    fun `Yasser highlight runs 200 ms ahead`() {
        assertEquals(200L, ReciterSync.highlightAdvanceMs(9))
        assertEquals(200L, ReciterSync.highlightAdvanceMs(9, bluetoothOutput = true, playbackSpeed = 1.5f))
    }

    @Test
    fun `Alafasy Bluetooth ink advances 200 wall milliseconds at every reader speed`() {
        for (speed in listOf(0.75f, 1f, 1.25f, 1.5f)) {
            assertEquals(
                (200 * speed).toLong(),
                ReciterSync.highlightAdvanceMs(1, bluetoothOutput = true, playbackSpeed = speed),
            )
            assertEquals(0L, ReciterSync.highlightAdvanceMs(1, bluetoothOutput = false, playbackSpeed = speed))
        }
    }

    @Test
    fun `Alafasy Bluetooth correction preserves opening silence and short first words`() {
        val segments = listOf(
            Segment(position = 1, startMs = 210, endMs = 490),
            Segment(position = 2, startMs = 490, endMs = 1_340),
            Segment(position = 3, startMs = 1_340, endMs = 2_240),
        )
        for (speed in listOf(0.75f, 1f, 1.25f, 1.5f)) {
            val lead = ReciterSync.highlightAdvanceMs(1, bluetoothOutput = true, playbackSpeed = speed)
            val clock = HighlightClock()
            val visits = mutableListOf<Int>()
            for (tick in 0..100) {
                val mediaMs = (tick * 33 * speed).toLong()
                val query = OutputLatency.highlightMs(mediaMs, latencyMs = 0, leadMs = lead, leadNotBeforeMs = 210)
                val word = HighlightEngine.activeWord(segments, clock.sample("ayah", query))
                if (mediaMs < 210) assertEquals(null, word)
                if (word != null && visits.lastOrNull() != word) visits += word
            }
            assertEquals(listOf(1, 2, 3), visits)
            // Only word ink advances. The presentation/heard clock stays put.
            assertEquals(1_100L, OutputLatency.heardMs(1_100, latencyMs = 0))
            assertEquals(1_100L + lead, OutputLatency.highlightMs(1_100, 0, lead, 210))
        }
    }

    @Test
    fun `other reciters keep their source clock`() {
        assertEquals(0L, ReciterSync.highlightAdvanceMs(1))
        assertEquals(0L, ReciterSync.highlightAdvanceMs(19))
        assertEquals(0L, ReciterSync.highlightAdvanceMs(19, bluetoothOutput = true))
    }
}
