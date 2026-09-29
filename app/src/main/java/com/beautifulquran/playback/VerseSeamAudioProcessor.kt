package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Puts [VerseSeamFade] on the playback PCM. Float output sits the processor
 * out; the verse files are 16-bit.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VerseSeamAudioProcessor : BaseAudioProcessor() {

    private var seam: VerseSeamFade? = null

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        seam = VerseSeamFade(inputAudioFormat.channelCount, inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fade = seam ?: return
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val pcm = ByteArray(remaining)
        inputBuffer.get(pcm)
        val ready = fade.push(pcm)
        if (ready.isEmpty()) return
        replaceOutputBuffer(ready.size).put(ready).flip()
    }

    override fun onQueueEndOfStream() {
        val tail = seam?.endOfStream() ?: return
        if (tail.isEmpty()) return
        replaceOutputBuffer(tail.size).put(tail).flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        seam?.clear()
    }

    override fun onReset() {
        seam = null
    }
}
