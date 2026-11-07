package com.beautifulquran.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HizbOpeningTimelineTest {
    private fun recording(): Timeline = SinglePeriodTimeline(
        10_000_000L, true, false, false, null, MediaItem.EMPTY,
    )

    @Test
    fun `automatic entry starts before the voice without rebasing the file clock`() {
        val original = recording()
        val timeline = HizbOpeningTimeline(original, 3_933L) { true }
        val window = timeline.getWindow(0, Timeline.Window())
        val period = timeline.getPeriod(0, Timeline.Period())

        assertEquals(3_933_000L, window.defaultPositionUs)
        assertEquals(0L, window.positionInFirstPeriodUs)
        assertEquals(0L, period.positionInWindowUs)
        assertEquals(10_000_000L, window.durationUs)
        assertEquals(10_000_000L, period.durationUs)
        assertEquals(original.getUidOfPeriod(0), timeline.getUidOfPeriod(0))
        // A repeat enters here too, without a seek loop.
        assertEquals(3_933_000L, timeline.getWindow(0, window).defaultPositionUs)
    }

    @Test
    fun `the lab can restore the full opening on an already prepared timeline`() {
        var listening = true
        val timeline = HizbOpeningTimeline(recording(), 5_250L) { listening }
        val window = Timeline.Window()
        assertEquals(5_250_000L, timeline.getWindow(0, window).defaultPositionUs)
        listening = false
        assertEquals(0L, timeline.getWindow(0, window).defaultPositionUs)
        listening = true
        assertEquals(5_250_000L, timeline.getWindow(0, window).defaultPositionUs)
    }

    @Test
    fun `an unprepared item already enters at the opening`() {
        // Without this the player starts at 0 and Media3 moves it to the
        // opening on preparation without a discontinuity (Hani 4:148 snap).
        val unprepared = unpreparedProgressiveTimeline(MediaItem.EMPTY)
        val timeline = HizbOpeningTimeline(unprepared, 3_933L) { true }
        val window = timeline.getWindow(0, Timeline.Window(), /* defaultPositionProjectionUs= */ 1L)
        assertEquals(3_933_000L, window.defaultPositionUs)
        assertTrue(window.isPlaceholder)
        // The period is the one ProgressiveMediaSource reports once prepared.
        assertEquals(recording().getUidOfPeriod(0), timeline.getUidOfPeriod(0))
    }

    @Test
    fun `the lab's unprepared entry stays at zero`() {
        val timeline = HizbOpeningTimeline(unpreparedProgressiveTimeline(MediaItem.EMPTY), 3_933L) { false }
        assertEquals(0L, timeline.getWindow(0, Timeline.Window(), /* defaultPositionProjectionUs= */ 1L).defaultPositionUs)
    }
}
