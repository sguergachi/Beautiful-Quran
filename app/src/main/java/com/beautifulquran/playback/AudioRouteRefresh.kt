package com.beautifulquran.playback

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * Recreates the audio output when its actual routed device changes. A reused AudioTrack
 * can retain the previous route's latency; stop/prepare releases that clock
 * while preserving the playlist, position, and play/pause intent. The service
 * owns this collector, including while the reader is closed or playback paused.
 */
internal suspend fun refreshAudioOutputOnRouteChange(player: Player, deviceId: StateFlow<Int?>) {
    var previousId = deviceId.value
    deviceId.collect { currentId ->
        if (currentId == null || currentId == previousId) return@collect
        val changed = previousId != null
        previousId = currentId
        if (changed) {
            val state = player.playbackState
            if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                player.stop()
                player.prepare()
            }
        }
    }
}
