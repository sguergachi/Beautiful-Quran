package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer

class VerseSeamAudioProcessorTest {

    @Test
    fun `configuring the next verse still fades this file's tail`() {
        val processor = VerseSeamAudioProcessor()
        val format = AudioProcessor.AudioFormat(
            /* sampleRate = */ 1_000,
            /* channelCount = */ 1,
            /* encoding = */ C.ENCODING_PCM_16BIT,
        )
        processor.configure(format)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val inputFrames = 18
        processor.queueInput(ByteBuffer.wrap(pcm16(inputFrames, 8_000)))
        val body = copy(processor.getOutput())

        // The sink configures the next file before it ends this one.
        processor.configure(format)
        processor.queueEndOfStream()
        val tail = copy(processor.getOutput())
        assertFalse(processor.getOutput().hasRemaining())
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        assertEquals(10, body.size / 2)
        assertEquals(8_000, sampleAt(body, 0))
        assertEquals(8, tail.size / 2)
        assertEquals(8_000, sampleAt(tail, 0))
        assertEquals(0, sampleAt(tail, 7))
        assertEquals(inputFrames * 2, body.size + tail.size)
    }

    @Test
    fun `a flush before the file ends drops the held tail`() {
        val processor = VerseSeamAudioProcessor()
        val format = AudioProcessor.AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(ByteBuffer.wrap(pcm16(8, 4_000)))
        assertFalse(processor.getOutput().hasRemaining())

        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueEndOfStream()
        assertFalse(processor.getOutput().hasRemaining())
    }
}

private fun copy(buffer: ByteBuffer): ByteArray {
    val dup = buffer.duplicate()
    val out = ByteArray(dup.remaining())
    dup.get(out)
    return out
}
