package com.beautifulquran.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Waiting on the stream download: the whole page waits under Upcoming paper,
 * the reciter's due verse included — but only while nothing has been spoken.
 */
class StreamWaitPolicyTest {

    private fun seed(ayah: Int = 9) = ActiveWord(
        ayah = ayah,
        wordPosition = 3,
        durationMs = 600,
        fromTap = true,
    )

    private fun spoken(ayah: Int = 9) = seed(ayah).copy(fromTap = false)

    @Test
    fun `the spinner wait holds the page while the voice has not spoken`() {
        assertTrue(readerWaitingOnStream(isBuffering = true, activeWord = null))
        assertTrue(readerWaitingOnStream(isBuffering = true, activeWord = seed()))
    }

    @Test
    fun `a recited word anchor un-waits the page`() {
        assertFalse(readerWaitingOnStream(isBuffering = true, activeWord = spoken()))
    }

    @Test
    fun `no buffering means no wait`() {
        assertFalse(readerWaitingOnStream(isBuffering = false, activeWord = null))
        assertFalse(readerWaitingOnStream(isBuffering = false, activeWord = spoken()))
        assertFalse(readerWaitingOnStream(isBuffering = false, activeWord = seed()))
    }
}
