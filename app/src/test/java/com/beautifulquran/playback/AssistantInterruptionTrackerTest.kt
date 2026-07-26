package com.beautifulquran.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantInterruptionTrackerTest {

    @Test
    fun `resumes when Assistant starts after focus loss and then stops`() {
        val tracker = AssistantInterruptionTracker()

        tracker.onFocusLost()
        tracker.onAssistantPlaybackChanged(true)
        assertFalse(tracker.canResume)

        tracker.onAssistantPlaybackChanged(false)
        assertTrue(tracker.takeResume())
        assertFalse(tracker.takeResume())
    }

    @Test
    fun `resumes when Assistant was already audible before focus loss`() {
        val tracker = AssistantInterruptionTracker()

        tracker.onAssistantPlaybackChanged(true)
        tracker.onFocusLost()
        tracker.onAssistantPlaybackChanged(false)

        assertTrue(tracker.takeResume())
    }

    @Test
    fun `does not resume an unrelated or expired focus loss`() {
        val tracker = AssistantInterruptionTracker()

        tracker.onFocusLost()
        tracker.expireUnconfirmedFocusLoss()
        tracker.onAssistantPlaybackChanged(true)
        tracker.onAssistantPlaybackChanged(false)

        assertFalse(tracker.takeResume())
    }

    @Test
    fun `explicit cancellation keeps playback paused`() {
        val tracker = AssistantInterruptionTracker()

        tracker.onFocusLost()
        tracker.onAssistantPlaybackChanged(true)
        tracker.cancel()
        tracker.onAssistantPlaybackChanged(false)

        assertFalse(tracker.takeResume())
    }
}
