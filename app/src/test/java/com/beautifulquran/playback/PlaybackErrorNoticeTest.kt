package com.beautifulquran.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackErrorNoticeTest {
    private val networkFailure = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    private val message = "No connection · press play to try again"

    @Test
    fun `a paused stream failing in the background stays quiet on return`() {
        val afterFailure = playbackErrorNotice(null, false, false, networkFailure)
        assertNull(afterFailure)
        assertNull(playbackErrorNotice(afterFailure, false, false))
        // Resuming must not replay a failure that happened while paused.
        assertNull(playbackErrorNotice(afterFailure, true, false))
    }

    @Test
    fun `a failed first play attempt is visible before any audio has played`() {
        assertEquals(message, playbackErrorNotice(null, true, false, networkFailure))
    }

    @Test
    fun `an interrupted recitation retains its notice through later player events`() {
        val afterFailure = playbackErrorNotice(null, true, false, networkFailure)
        assertEquals(message, playbackErrorNotice(afterFailure, true, false))
    }

    @Test
    fun `pausing retires a failure and later resume does not resurrect it`() {
        val paused = playbackErrorNotice(message, false, false)
        assertNull(paused)
        assertNull(playbackErrorNotice(paused, true, false))
    }

    @Test
    fun `pausing and a delayed error in the same batch stays quiet`() {
        assertNull(playbackErrorNotice(message, false, false, networkFailure))
    }

    @Test
    fun `recovery clears the notice`() {
        assertNull(playbackErrorNotice(message, true, true))
    }

    @Test
    fun `dismissal lasts until a new play failure`() {
        assertNull(playbackErrorNotice(null, true, false))
        assertEquals(message, playbackErrorNotice(null, true, false, networkFailure))
    }
}
