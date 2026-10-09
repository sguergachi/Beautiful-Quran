package com.beautifulquran.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackErrorMessageTest {

    @Test
    fun droppedConnectionAsksForTheNetwork() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
        ).forEach {
            assertEquals(
                "No connection · tap Play",
                playbackErrorMessage(it),
            )
        }
    }

    @Test
    fun missingFileSaysTheVerseIsUnavailable() {
        assertEquals(
            "Verse unavailable",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(
            "Verse unavailable",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
    }

    @Test
    fun damagedAudioAndOutputFailuresStayDistinct() {
        assertEquals(
            "Cannot play this verse",
            playbackErrorMessage(PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
        assertEquals(
            "Cannot play this verse",
            playbackErrorMessage(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertEquals(
            "Audio stopped · tap Play",
            playbackErrorMessage(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED),
        )
    }

    @Test
    fun noMessageLeaksAMedia3CodeName() {
        (0..7000).forEach { code ->
            val message = playbackErrorMessage(code)
            assertFalse(message, "ERROR_CODE" in message || code.toString() in message)
        }
    }
}
