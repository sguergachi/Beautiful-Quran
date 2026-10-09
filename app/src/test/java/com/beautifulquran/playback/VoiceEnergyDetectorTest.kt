package com.beautifulquran.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceEnergyDetectorTest {
    @Before fun useCurrent() { VoiceEnergy.setDetectorMode(TarjiDetectorMode.Current) }
    @After fun restoreCurrent() { VoiceEnergy.setDetectorMode(TarjiDetectorMode.Current) }

    private fun pcm(seconds: Double, from: Double = 0.0): ByteBuffer =
        ByteBuffer.allocate((seconds * 8_000).toInt() * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(capacity() / 2) { i ->
                val time = from + i / 8_000.0
                val voice = 0.3 * (1 + 0.25 * sin(2 * Math.PI * 5 * time)) * sin(2 * Math.PI * 130 * time)
                putShort((32767 * voice).toInt().toShort())
            }
            flip()
        }

    private fun anchoredVoice(seconds: Double = 3.0): VoiceEnergy = VoiceEnergy { 2_000L }.apply {
        resetTapSession(1_000_000.0)
        onPcm16(pcm(seconds), 1, 8_000)
        updateSinkPosition(1_002_500.0, 0L, true)
        updatePlaybackPosition(14_800L, 0L, true)
    }

    @Test
    fun `repeated selector writes keep generation but actual mode changes advance it`() {
        val generation = VoiceEnergy.detectorGeneration
        VoiceEnergy.setDetectorMode(TarjiDetectorMode.Current)
        assertEquals(generation, VoiceEnergy.detectorGeneration)
        VoiceEnergy.setDetectorMode(TarjiDetectorMode.Cycles)
        assertEquals(TarjiDetectorMode.Cycles, VoiceEnergy.detectorMode)
        assertEquals(generation + 1, VoiceEnergy.detectorGeneration)
    }

    @Test
    fun `recording switch suppresses queued decisions while raw history capture and clock survive`() {
        val voice = anchoredVoice()
        val before = TarjiEarSample()
        assertTrue(voice.sampleAtEar(0L, before, displayLeadMs = 0f))
        assertTrue(before.gain > 0.1f)
        val rms = FloatArray(128)
        val size = voice.copyRecentRms(rms)
        val start = voice.recentRmsStartMediaMs
        val session = voice.sessionGeneration
        val hops = voice.hopCount
        val content = voice.sessionContentMs
        voice.armCapture()
        VoiceEnergy.setDetectorMode(TarjiDetectorMode.Recording)
        val selected = TarjiEarSample()
        assertTrue(voice.sampleAtEar(0L, selected, displayLeadMs = 0f))
        assertEquals(0f, selected.gain, 0f)
        assertFalse(selected.reverberating)
        assertEquals(before.mediaMs, selected.mediaMs)
        assertEquals(before.tremolo, selected.tremolo, 0f)
        assertEquals(15_300L, voice.detectorSwitchMediaMs)
        val raw = TarjiEarSample()
        assertTrue(voice.sampleRawPulseAtEar(0L, raw, before.rateHz, true, displayLeadMs = 0f))
        assertEquals(before.tremolo, raw.tremolo, 0f)
        assertEquals(before.mediaMs, raw.mediaMs)
        val after = FloatArray(128)
        assertEquals(size, voice.copyRecentRms(after))
        assertArrayEquals(rms, after, 0f)
        assertEquals(start, voice.recentRmsStartMediaMs, 0.0)
        assertEquals(session, voice.sessionGeneration)
        assertEquals(hops, voice.hopCount)
        assertEquals(content, voice.sessionContentMs, 0.0)

        voice.onPcm16(pcm(0.4, 3.0), 1, 8_000)
        assertEquals(hops + 20, voice.hopCount)
        assertEquals(content + 400, voice.sessionContentMs, 1e-6)
        assertEquals(session, voice.sessionGeneration)
        assertEquals(15_300L, voice.detectorSwitchMediaMs)
        assertEquals(0f, voice.tremoloGain, 0f)
        assertEquals(20, voice.disarmCapture()!!.hopContentMs.size)

        voice.resetTapSession(2_000_000.0)
        assertTrue(voice.sessionGeneration > session)
        assertEquals(0, voice.copyRecentRms(after))
        assertTrue(voice.recentRmsStartMediaMs.isNaN())
        assertEquals(VoiceEnergy.NO_EVENT_MS, voice.detectorSwitchMediaMs)
        voice.onPcm16(pcm(1.0), 1, 8_000)
        voice.updateSinkPosition(2_000_500.0, 0L, true)
        voice.updatePlaybackPosition(17_830L, 0L, true)
        assertTrue(voice.sampleRawPulseAtEar(0L, raw, 5f, true, displayLeadMs = 0f))
        assertEquals(17_830L, raw.mediaMs)
        assertEquals(0L, voice.detectorSwitchMediaMs)
    }

    @Test
    fun `all modes retain identical tap voice samples and raw heard phase`() {
        var referenceRms: FloatArray? = null
        var referencePulse = 0f
        for (mode in TarjiDetectorMode.entries) {
            VoiceEnergy.setDetectorMode(mode)
            val voice = anchoredVoice()
            val rms = FloatArray(96)
            assertEquals(rms.size, voice.copyRecentRms(rms))
            val raw = TarjiEarSample()
            assertTrue(voice.sampleRawPulseAtEar(0L, raw, 5f, true, displayLeadMs = 0f))
            assertEquals(14_800L, raw.mediaMs)
            assertEquals(13_390.0, voice.recentRmsStartMediaMs, 1e-6)
            if (referenceRms == null) { referenceRms = rms; referencePulse = raw.tremolo }
            else { assertArrayEquals(referenceRms, rms, 0f); assertEquals(referencePulse, raw.tremolo, 0f) }
        }
    }

    @Test
    fun `current mirrors stay exact against the unchanged baseline DSP`() {
        val voice = VoiceEnergy { 2_000L }
        val baseline = Tarji()
        val hop = FloatArray(160)
        val bytes = pcm(3.0)
        repeat(150) {
            val data = bytes.slice().order(ByteOrder.LITTLE_ENDIAN).apply { limit(320) }
            for (i in hop.indices) hop[i] = data.getShort(i * 2) / 32768f
            baseline.onSamples8k(hop)
            voice.onPcm16(data, 1, 8_000)
            bytes.position(bytes.position() + 320)
            assertEquals(baseline.syncTremoloGain, voice.tremoloGain, 0f)
            assertEquals(baseline.syncTremolo, voice.tremolo, 0f)
            assertEquals(baseline.syncReverberating, voice.reverberating)
            assertEquals(baseline.lastRateHz, voice.rateHz, 0f)
            assertEquals(baseline.hopCount, voice.hopCount)
        }
    }
}
