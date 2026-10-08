package com.beautifulquran.playback

import androidx.media3.common.PlaybackException

/**
 * The line a reader sees when recitation stops on an error. Media3's code
 * names (`ERROR_CODE_IO_UNSPECIFIED`…) are for the log, never the page: each
 * code family maps to what happened and what to do next, in plain words.
 */
internal fun playbackErrorMessage(errorCode: Int): String = when (errorCode) {
    // A dropped or switching connection (Wi-Fi ↔ mobile, a tunnel) surfaces
    // as a failed, timed-out or unspecified read of the stream.
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT,
    -> "Couldn't reach the recitation. Check your connection and press play."
    in IO_ERRORS -> "This verse's recitation isn't available right now."
    in PARSING_ERRORS, in DECODING_ERRORS ->
        "This recitation couldn't be played. Press play to try again."
    in AUDIO_OUTPUT_ERRORS -> "Your audio output stopped responding. Press play to try again."
    else -> "The recitation stopped unexpectedly. Press play to try again."
}

// Media3 groups its codes by thousands: 2xxx input/output, 3xxx parsing,
// 4xxx decoding, 5xxx audio output.
private val IO_ERRORS = 2000..2999
private val PARSING_ERRORS = 3000..3999
private val DECODING_ERRORS = 4000..4999
private val AUDIO_OUTPUT_ERRORS = 5000..5999
