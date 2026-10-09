package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceTapAudioProcessorTest {
    private val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)

    @Test
    fun `first buffer PTS anchors an oversized burst without a reader tick`() = withTap { tap, sink, voice ->
        sink.output.handleBuffer(note(), 1_017_330_000L, 1)
        sink.presentedUs = 1_017_830_000L
        sink.output.getCurrentPositionUs(false)
        // The Lab owns this tick even when no reader chapter is loaded.
        voice.updatePlaybackPosition(17_830L, wallNanos = 0L, playing = true)
        assertEquals(17_330.0, voice.mediaMsOfContent(0.0), 1e-6)
        assertEquals(500.0, voice.measuredBacklogContentMs, 1e-6)
        assertEquals(1000.0, voice.sessionContentMs, 1e-6)
        assertFalse(tap.getOutput().hasRemaining())
    }

    @Test
    fun `reusable gapless and speed flushes retain the still audible history`() = withTap { _, sink, voice ->
        sink.output.handleBuffer(note(), 1_000_000_000L, 1)
        sink.presentedUs = 1_000_500_000L
        sink.output.getCurrentPositionUs(false)
        voice.updatePlaybackPosition(500L, wallNanos = 0L, playing = true)
        val before = TarjiEarSample()
        assertTrue(voice.sampleAtEar(0L, before, displayLeadMs = 0f))
        val session = voice.sessionStartWall

        sink.flushProcessors = true
        sink.output.handleBuffer(note(), 1_001_000_000L, 1)
        sink.output.getCurrentPositionUs(false)
        voice.updatePlaybackPosition(500L, wallNanos = 0L, playing = true)
        val after = TarjiEarSample()
        assertTrue(voice.sampleAtEar(0L, after, displayLeadMs = 0f))
        assertEquals(session, voice.sessionStartWall)
        assertEquals(2000.0, voice.sessionContentMs, 1e-6)
        assertEquals(before.tremolo, after.tremolo, 1e-6f)
        assertEquals(before.gain, after.gain, 1e-6f)
        assertEquals(before.eventStartMediaMs, after.eventStartMediaMs)
    }

    @Test
    fun `a real sink flush clears old PCM and anchors the seek destination`() = withTap { _, sink, voice ->
        sink.output.handleBuffer(note(), 1_000_000_000L, 1)
        val previousSession = voice.sessionStartWall
        sink.output.flush()
        assertTrue(voice.sessionStartWall > previousSession)
        assertEquals(0, voice.hopCount)
        assertEquals(0.0, voice.sessionContentMs, 0.0)

        sink.output.handleBuffer(note(), 1_012_270_000L, 1)
        sink.presentedUs = 1_012_770_000L
        sink.output.getCurrentPositionUs(false)
        voice.updatePlaybackPosition(12_770L, wallNanos = 0L, playing = true)
        assertEquals(12_270.0, voice.mediaMsOfContent(0.0), 1e-6)
        assertEquals(1000.0, voice.sessionContentMs, 1e-6)
        voice.updatePlaybackPosition(12_770L, wallNanos = 0L, playing = false)
        assertFalse(voice.sampleAtEar(0L, TarjiEarSample()))
    }

    @Test
    fun `controller resume waits for sink play before reading the pulse`() = withTap { _, sink, voice ->
        sink.output.handleBuffer(note(), 1_000_000_000L, 1)
        sink.presentedUs = 1_000_500_000L
        sink.output.pause()
        voice.updatePlaybackPosition(500L, wallNanos = 0L, playing = false)
        voice.updatePlaybackPosition(500L, wallNanos = 0L, playing = true)
        assertFalse(voice.isPlaying)
        assertFalse(voice.sampleAtEar(0L, TarjiEarSample()))
        sink.output.play()
        voice.updatePlaybackPosition(500L, wallNanos = 0L, playing = true)
        assertTrue(voice.sampleAtEar(0L, TarjiEarSample(), displayLeadMs = 0f))
        assertEquals(0.0, voice.mediaMsOfContent(0.0), 1e-6)
    }

    private fun withTap(check: (VoiceTapAudioProcessor, Sink, VoiceEnergy) -> Unit) {
        val voice = VoiceEnergy { 2000L }
        val tap = VoiceTapAudioProcessor()
        val previous = VoiceEnergy.active
        VoiceEnergy.active = voice
        try {
            tap.configure(format)
            val sink = Sink(tap)
            sink.output.play()
            check(tap, sink, voice)
        } finally {
            VoiceEnergy.active = previous
        }
    }

    /** Exercises the real wrapper and processor with Media3's flush ordering. */
    private class Sink(private val tap: VoiceTapAudioProcessor) {
        var presentedUs = AudioSink.CURRENT_POSITION_NOT_SET
        var flushProcessors = true
        val output = tap.attach(Proxy.newProxyInstance(
            AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "handleBuffer" -> {
                    if (flushProcessors) {
                        tap.flush(AudioProcessor.StreamMetadata.DEFAULT)
                        flushProcessors = false
                    }
                    tap.queueInput(args!![0] as ByteBuffer)
                    tap.getOutput().let { it.position(it.limit()) }
                    true
                }
                "getCurrentPositionUs" -> presentedUs
                "getAudioTrackBufferSizeUs" -> 250_000L
                "flush" -> { flushProcessors = true; null }
                "play", "pause" -> null
                else -> error("Unexpected sink call: ${method.name}")
            }
        } as AudioSink)
    }

    private fun note(): ByteBuffer = ByteBuffer.allocateDirect(48_000 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (sample in 0 until 48_000) {
            val t = sample / 48_000.0
            val level = 0.2 * (1 + 0.2 * kotlin.math.sin(2 * Math.PI * 5 * t))
            putShort((32767 * level * kotlin.math.sin(2 * Math.PI * 130 * t)).toInt().toShort())
        }
        flip()
    }
}
