package com.beautifulquran.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerseSeamFadeTest {

    @Test
    fun `the end of a file ramps to silence and the body stays full level`() {
        val fade = VerseSeamFade(channelCount = 1, sampleRate = 1_000)
        val frames = fade.fadeFrames + 10
        val body = fade.push(pcm16(frames, 8_000))
        val tail = fade.endOfStream()

        assertEquals(10, body.size / 2)
        assertEquals(8_000, sampleAt(body, 0))
        assertEquals(8_000, sampleAt(body, 9))
        assertEquals(fade.fadeFrames, tail.size / 2)
        assertEquals(8_000, sampleAt(tail, 0))
        assertEquals(0, sampleAt(tail, fade.fadeFrames - 1))
        assertTrue(sampleAt(tail, fade.fadeFrames / 2) in 1..7_999)
    }

    @Test
    fun `a seek drops the held tail instead of playing it`() {
        val fade = VerseSeamFade(channelCount = 1, sampleRate = 1_000)
        fade.push(pcm16(fade.fadeFrames, 4_000))
        fade.clear()
        assertEquals(0, fade.endOfStream().size)
    }

    @Test
    fun `stereo frames fade together`() {
        val fade = VerseSeamFade(channelCount = 2, sampleRate = 1_000)
        fade.push(pcm16(fade.fadeFrames, 1_000, channels = 2))
        val tail = fade.endOfStream()
        assertEquals(0, sampleAt(tail, fade.fadeFrames - 1, channels = 2, channel = 0))
        assertEquals(0, sampleAt(tail, fade.fadeFrames - 1, channels = 2, channel = 1))
        assertEquals(1_000, sampleAt(tail, 0, channels = 2, channel = 1))
    }
}

internal fun pcm16(frames: Int, value: Int, channels: Int = 1): ByteArray {
    val out = ByteArray(frames * channels * 2)
    var offset = 0
    repeat(frames * channels) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = ((value shr 8) and 0xff).toByte()
        offset += 2
    }
    return out
}

internal fun sampleAt(pcm: ByteArray, frame: Int, channels: Int = 1, channel: Int = 0): Int {
    val offset = (frame * channels + channel) * 2
    val sample = (pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)
    return sample.toShort().toInt()
}
