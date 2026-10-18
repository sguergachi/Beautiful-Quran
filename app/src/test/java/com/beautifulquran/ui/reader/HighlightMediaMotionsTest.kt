package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.*
import org.junit.Assert.*
import org.junit.Test

class HighlightMediaMotionsTest {
    private val timing = HighlightTimingContext(1,
        HighlightEngine.PreparedTimings.prepare(listOf(Segment(1, 0, 2000))), 0)
    private fun input(position: Long, at: Long, playing: Boolean = true, buffering: Boolean = false, speed: Float = 1f) =
        HighlightPlaybackSample(HighlightItem(24, 1, 7), position, at * 1_000_000, playing, buffering, speed)

    @Test
    fun `production draw timeline freezes on pause and buffer then follows speed across display frames`() {
        val timeline = HighlightTimeline()
        var sync = HighlightSync.sample(null, input(0, 0), timing)
        timeline.update(sync)
        val window = HighlightWashWindow(24, 1, 0, 0, 1000)
        val wash = HighlightWash.transition(HighlightWashState(), true, false, 0, window)
        fun progress() = HighlightWash.progress(wash, timeline.frame())
        timeline.tick(400_000_000)
        val beforePause = progress()
        assertTrue(beforePause > 0f && beforePause < 1f)
        sync = HighlightSync.sample(sync, input(400, 400, playing = false), timing)
        timeline.update(sync)
        timeline.tick(900_000_000)
        assertEquals(beforePause, progress(), 0f)
        sync = HighlightSync.sample(sync, input(400, 900, speed = 2f), timing)
        timeline.update(sync)
        timeline.tick(1_000_000_000)
        assertTrue(progress() > beforePause)
        sync = HighlightSync.sample(sync, input(600, 1000, buffering = true, speed = 2f), timing)
        timeline.update(sync)
        val buffered = progress()
        timeline.tick(1_400_000_000)
        assertEquals(buffered, progress(), 0f)
        for (hz in listOf(60, 90, 120)) {
            val resumed = HighlightSync.sample(sync, input(600, 1400, speed = 2f), timing)
            timeline.update(resumed)
            var prior = progress()
            repeat(hz / 10) { frame ->
                timeline.tick(1_400_000_000L + (frame + 1L) * 1_000_000_000L / hz)
                assertTrue(progress() > prior)
                prior = progress()
            }
        }
    }
}
