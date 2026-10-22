package com.beautifulquran.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Waiting on the stream download: the whole page waits under Upcoming paper —
 * but only while this reciting performance has spoken nothing.
 */
class StreamWaitPolicyTest {

    @Test
    fun `the spinner wait holds the page while the performance is mute`() {
        assertTrue(readerWaitingOnStream(isBuffering = true, spokenInPerformance = false))
    }

    @Test
    fun `a word spoken inside the performance un-waits the page`() {
        // Mid-chapter rebuffer: the wash froze on the last heard word; the
        // page keeps what the ink left, no dim.
        assertFalse(readerWaitingOnStream(isBuffering = true, spokenInPerformance = true))
    }

    @Test
    fun `no buffering means no wait`() {
        assertFalse(readerWaitingOnStream(isBuffering = false, spokenInPerformance = true))
        assertFalse(readerWaitingOnStream(isBuffering = false, spokenInPerformance = false))
    }
}
