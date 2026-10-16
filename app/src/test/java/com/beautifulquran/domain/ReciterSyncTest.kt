package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class ReciterSyncTest {
    @Test
    fun `Yasser highlight runs 200 ms ahead`() {
        assertEquals(200L, ReciterSync.highlightAdvanceMs(9))
    }

    @Test
    fun `Alafasy keeps the presentation clock and ordered highlights at every reader speed`() {
        val segments = listOf(
            Segment(position = 1, startMs = 210, endMs = 490),
            Segment(position = 2, startMs = 490, endMs = 1_340),
            Segment(position = 3, startMs = 1_340, endMs = 2_240),
        )
        for (speed in listOf(0.75f, 1f, 1.25f, 1.5f)) {
            val lead = ReciterSync.highlightAdvanceMs(1)
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
            // Output type cannot introduce an unmeasured lead over the heard voice.
            assertEquals(1_100L, OutputLatency.heardMs(1_100, latencyMs = 0))
            assertEquals(1_100L, OutputLatency.highlightMs(1_100, 0, lead, 210))
        }
    }

    @Test
    fun `other reciters keep their source clock`() {
        for (reciterId in listOf(1, 2, 3, 4, 5, 6, 7, 14, 16, 17, 18, 19)) {
            assertEquals(0L, ReciterSync.highlightAdvanceMs(reciterId))
        }
    }
}
