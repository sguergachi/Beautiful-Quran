package com.beautifulquran.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.beautifulquran.domain.OutputLatency
import com.beautifulquran.domain.OutputLatency.OutputKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches system audio outputs and exposes the [OutputLatency] preset that
 * raw PCM tarjīʿ tap needs on its path to the ear. Media3's presentation
 * clock already handles output delay; do not subtract this preset again
 * for word highlighting. App-lifetime: register once from [com.beautifulquran.QuranApp].
 *
 * Classification is coarse (connected BT device present → BT preset). Android
 * does not give a reliable end-to-end A2DP ear-delay for media playback; the
 * pure presets in [OutputLatency] are the product decision.
 */
class AudioOutputLatency(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private val _latencyMs = MutableStateFlow(measure())
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        audioManager?.registerAudioDeviceCallback(callback, /* handler = */ null)
    }

    private fun refresh() {
        _latencyMs.value = measure()
    }

    private fun measure(): Long {
        val am = audioManager ?: return OutputLatency.LOCAL_MS
        val kinds = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull { kindForType(it.type) }
            .toSet()
        return OutputLatency.latencyMs(kinds)
    }

    companion object {
        /** Map [AudioDeviceInfo] type ints onto pure [OutputKind]s. Unknown
         * types are ignored so they never invent a Bluetooth route. */
        internal fun kindForType(type: Int): OutputKind? = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID,
            -> OutputKind.BLUETOOTH_A2DP

            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> OutputKind.BLUETOOTH_LE

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            -> OutputKind.LOCAL

            else -> null
        }
    }
}
