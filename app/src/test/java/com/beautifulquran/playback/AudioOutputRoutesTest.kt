package com.beautifulquran.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputRoutesTest {
    @Test
    fun `classic and LE Bluetooth routes enable the visual trim`() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )) assertTrue(isBluetoothAudioOutput(type))
    }

    @Test
    fun `speaker wired USB and unknown routes keep their clock even with Bluetooth connected`() {
        assertFalse(isBluetoothAudioOutput(null))
        for (type in listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_UNKNOWN,
        )) assertFalse(isBluetoothAudioOutput(type))
    }
}
