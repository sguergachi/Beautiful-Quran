package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Pass-through audio processor that mirrors the player's PCM into
 * [VoiceEnergy] for tarjīʿ detection. The audio itself is forwarded
 * untouched — this is a tap, not a filter.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VoiceTapAudioProcessor : BaseAudioProcessor() {

    private var format: AudioProcessor.AudioFormat? = null

    /** The sink's capacity is retained only as a diagnostic. */
    private var sink: AudioSink? = null
    private var presentationTimeUs: Long? = null

    /** Preserve source PTS through internal processor flushes and buffer retries. */
    fun attach(sink: AudioSink): AudioSink {
        this.sink = sink
        return object : ForwardingAudioSink(sink) {
            private var playing = false

            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                this@VoiceTapAudioProcessor.presentationTimeUs = presentationTimeUs
                VoiceEnergy.active?.anchorSource(presentationTimeUs / 1000.0)
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }

            override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                val positionUs = super.getCurrentPositionUs(sourceEnded)
                if (positionUs != AudioSink.CURRENT_POSITION_NOT_SET) {
                    VoiceEnergy.active?.updateSinkPosition(positionUs / 1000.0, System.nanoTime(), playing)
                }
                return positionUs
            }

            override fun play() {
                super.play()
                playing = true
                getCurrentPositionUs(false)
            }

            override fun pause() {
                super.pause()
                playing = false
                getCurrentPositionUs(false)
            }

            override fun flush() {
                presentationTimeUs = null
                VoiceEnergy.active?.resetTapSession()
                super.flush()
            }
        }
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // Anything else: sit out (audio flows past) rather than fail the
            // sink's processor chain.
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val f = format
        if (f != null) {
            val voice = VoiceEnergy.active
            if (voice != null) {
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

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Internal speed/gapless flushes can leave old audio queued in the
        // same track. Preserve its history; actual sink flushes reset above.
        if (format != inputAudioFormat) {
            VoiceEnergy.active?.resetTapSession(presentationTimeUs?.div(1000.0) ?: Double.NaN)
        }
        format = inputAudioFormat
    }

    override fun onReset() {
        format = null
        presentationTimeUs = null
        VoiceEnergy.active?.resetTapSession()
    }
}
