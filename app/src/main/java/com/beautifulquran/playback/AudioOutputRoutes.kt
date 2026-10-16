package com.beautifulquran.playback

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Includes classic and LE media routes; local/wired/unknown routes stay untrimmed. */
internal fun isBluetoothAudioOutput(deviceType: Int?): Boolean = when (deviceType) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
    else -> false
}

/** Actual media routing, including switches between devices that remain connected. */
class AudioOutputRoutes {
    private val handler = Handler(Looper.getMainLooper())
    private val current = MutableStateFlow<Int?>(null)
    val deviceId = current.asStateFlow()
    private val bluetooth = MutableStateFlow(false)
    /** The player's routed output, independent of devices that are merely connected. */
    val isBluetooth = bluetooth.asStateFlow()
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
            bluetooth.value = false
            audioTrack.addOnRoutingChangedListener(listener, handler)
            refresh(audioTrack)
        }
    }

    fun detach() {
        handler.post {
            track?.removeOnRoutingChangedListener(listener)
            track = null
            bluetooth.value = false
        }
    }

    private fun refresh(routing: AudioRouting) {
        val device = routing.routedDevice
        bluetooth.value = isBluetoothAudioOutput(device?.type)
        device?.let { current.value = it.id }
    }
}
