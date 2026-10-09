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
                "No connection · press play to try again",
                playbackErrorMessage(it),
            )
        }
    }

    @Test
    fun missingFileSaysTheVerseIsUnavailable() {
        assertEquals(
            "This verse isn't available right now",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(
            "This verse isn't available right now",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
    }

    @Test
    fun damagedAudioAndOutputFailuresStayDistinct() {
        assertEquals(
            "This verse couldn't be played",
            playbackErrorMessage(PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
        assertEquals(
            "This verse couldn't be played",
            playbackErrorMessage(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertEquals(
            "Audio output stopped · press play again",
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
