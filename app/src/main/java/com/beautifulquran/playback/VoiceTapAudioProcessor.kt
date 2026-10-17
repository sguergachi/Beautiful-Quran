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
        // Only signed/unsigned integer and float PCM can be read by the tap;
        // pass it through untouched in all cases so audio is never altered.
        // The capture conversion below normalises the received samples to the
        // PCM16 shape VoiceEnergy already understands.
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
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
                feedVoiceEnergy(voice, inputBuffer.asReadOnlyBuffer(), f)
            }
        }
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    /** Mirror the sink's PCM into [voice], normalising any supported PCM
     * encoding to the PCM16 shape the detector expects. Only the first channel
     * is used, matching VoiceEnergy's existing (left-channel) behaviour. */
    private fun feedVoiceEnergy(
        voice: VoiceEnergy,
        buffer: ByteBuffer,
        format: AudioProcessor.AudioFormat,
    ) {
        val ch = format.channelCount.coerceAtLeast(1)
        val sr = format.sampleRate
        if (sr <= 0) return
        val bytesPerFrame = maxOf(1, format.bytesPerFrame)
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) return
        val little = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        when (format.encoding) {
            C.ENCODING_PCM_16BIT -> voice.onPcm16(little, ch, sr)
            else -> {
                val out = java.nio.ByteBuffer.allocate(frames * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until frames) {
                    val base = i * bytesPerFrame
                    val sample = when (format.encoding) {
                        C.ENCODING_PCM_8BIT -> {
                            (little.get(base).toInt() and 0xFF) - 128
                        }
                        C.ENCODING_PCM_24BIT -> {
                            val b0 = little.get(base).toInt() and 0xFF
                            val b1 = little.get(base + 1).toInt() and 0xFF
                            val b2 = little.get(base + 2).toInt()
                            val raw = (b2 shl 16) or (b1 shl 8) or b0
                            val signed = if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
                            (signed.toDouble() / 8388608.0 * 32767.0).toInt()
                        }
                        C.ENCODING_PCM_32BIT -> {
                            little.getInt(base) shr 16
                        }
                        C.ENCODING_PCM_FLOAT -> {
                            (little.getFloat(base) * 32767f).toInt()
                        }
                        else -> 0
                    }.coerceIn(-32768, 32767).toShort()
                    out.putShort(sample)
                }
                out.flip()
                voice.onPcm16(out, 1, sr)
            }
        }
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
