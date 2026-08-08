package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * Pass-through audio processor that mirrors the player's PCM into
 * [VoiceEnergy] for tarjīʿ detection. The audio itself is forwarded
 * untouched — this is a tap, not a filter.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VoiceTapAudioProcessor : BaseAudioProcessor() {

    private var format: AudioProcessor.AudioFormat? = null

    /**
     * The sink this processor runs in. Its AudioTrack buffer is the gap
     * between the tap and the phone's speaker — read live so the shimmer
     * delay lands on the ear, not on the sink's input. Set by
     * [PlaybackService] right after the sink is built.
     */
    private var sink: AudioSink? = null

    fun attach(sink: AudioSink) {
        this.sink = sink
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // Anything else: sit out (audio flows past) rather than fail the
            // sink's processor chain.
            return AudioProcessor.AudioFormat.NOT_SET
        }
        format = inputAudioFormat
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val f = format
        if (f != null) {
            val voice = VoiceEnergy.active
            if (voice != null) {
                // The sink's track buffer is app-side wall latency the route
                // presets never see (typically 40–100 ms, much more on
                // emulators) — without it the shimmer rides ahead of the ear.
                val bufferUs = sink?.getAudioTrackBufferSizeUs() ?: 0L
                if (bufferUs > 0L) voice.sinkLatencyMs = bufferUs / 1000
                voice.onPcm16(
                    inputBuffer.asReadOnlyBuffer(),
                    f.channelCount,
                    f.sampleRate,
                )
            }
        }
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onReset() {
        format = null
        // The sink (re)configured: the tap's hop clock is re-anchored at the
        // refeed position, so the reader can measure the tap-to-playback-head
        // backlog against the media clock.
        VoiceEnergy.active?.resetTapSession()
    }
}
