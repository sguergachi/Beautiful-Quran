package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Pass-through audio processor that mirrors the player's PCM into
 * [VoiceEnergy] for tarjīʿ detection. The audio itself is forwarded
 * untouched — this is a tap, not a filter.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VoiceTapAudioProcessor : BaseAudioProcessor() {

    private var format: AudioProcessor.AudioFormat? = null

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
            VoiceEnergy.active?.onPcm16(
                inputBuffer.asReadOnlyBuffer(),
                f.channelCount,
                f.sampleRate,
            )
        }
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onReset() {
        format = null
    }
}
