package com.beautifulquran.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEnergyTest {

    @Test
    fun `rms is zero for silence`() {
        val silence = ByteArray(64) { 128.toByte() }
        assertEquals(0f, VoiceEnergy.rms(silence), 1e-5f)
    }

    @Test
    fun `rms rises with waveform energy`() {
        val soft = ByteArray(64) { i -> if (i % 2 == 0) 140.toByte() else 116.toByte() }
        val loud = ByteArray(64) { i -> if (i % 2 == 0) 200.toByte() else 56.toByte() }
        assertTrue(VoiceEnergy.rms(loud) > VoiceEnergy.rms(soft))
        assertTrue(VoiceEnergy.rms(soft) > 0f)
    }
}
