package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Puts [VerseSeamFade] on the playback PCM. Float output sits the processor
 * out; the verse files are 16-bit.
 *
 * The next verse is configured while this file's last few milliseconds are
 * still held. [onConfigure] must not build a new fade: that runs before
 * [onQueueEndOfStream], and the tail would be thrown away instead of ramped.
 * The fade for the new file is built in [onFlush], after the ended file has
 * been drained.
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
        val format = inputAudioFormat
        seam = if (isActive && format.encoding == C.ENCODING_PCM_16BIT) {
            VerseSeamFade(format.channelCount, format.sampleRate)
        } else {
            null
        }
    }

    override fun onReset() {
        seam = null
    }
}
