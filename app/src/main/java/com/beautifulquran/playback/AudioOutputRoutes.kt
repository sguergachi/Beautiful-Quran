package com.beautifulquran.playback

import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Actual media routing, including switches between devices that remain connected. */
class AudioOutputRoutes {
    private val handler = Handler(Looper.getMainLooper())
    private val current = MutableStateFlow<Int?>(null)
    val deviceId = current.asStateFlow()
    private var track: AudioTrack? = null
    private val listener = AudioRouting.OnRoutingChangedListener { routing ->
        if (routing === track) refresh(routing)
    }

    /** Watch only the player's current track; stale callbacks cannot change the route. */
    fun attach(audioTrack: AudioTrack) {
        handler.post {
            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) return@post
            track?.removeOnRoutingChangedListener(listener)
            track = audioTrack
            audioTrack.addOnRoutingChangedListener(listener, handler)
            refresh(audioTrack)
        }
    }

    fun detach() {
        handler.post {
            track?.removeOnRoutingChangedListener(listener)
            track = null
        }
    }

    private fun refresh(routing: AudioRouting) {
        routing.routedDevice?.let { current.value = it.id }
    }
}
