package com.beautifulquran.playback

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * Recreates the audio output when its route preset changes. A reused AudioTrack
 * can retain the previous route's latency; stop/prepare releases that clock
 * while preserving the playlist, position, and play/pause intent. The service
 * owns this collector, including while the reader is closed or playback paused.
 */
internal suspend fun refreshAudioOutputOnRouteChange(player: Player, latencyMs: StateFlow<Long>) {
    var previousMs = latencyMs.value
    latencyMs.collect { currentMs ->
        if (currentMs != previousMs) {
            previousMs = currentMs
            val state = player.playbackState
            if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                player.stop()
                player.prepare()
            }
        }
    }
}
