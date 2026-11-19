package com.beautifulquran.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    @Test
    fun `room tone bridges the next file's opening silence without moving a sample`() {
        // Ash-Shuraym: a verse ends on full room tone, the next opens on
        // encoder-priming zeros. The seam must not drop the ambience out.
        val rate = 1_000
        val ended = VerseSeamFade(channelCount = 1, sampleRate = rate)
        ended.push(noise(200, 1_200))
        ended.endOfStream()
        val bridge = assertNotNullAndGet(ended.bridgeSource())

        val zeros = 40
        val next = VerseSeamFade(channelCount = 1, sampleRate = rate, bridge = bridge)
        val input = pcm16(zeros, 0) + noise(100, 1_000, seed = 7)
        val out = next.push(input.copyOf()) + next.endOfStream()

        assertEquals("no frames added or removed", input.size, out.size)
        // Rises after the previous file's fade, then holds the room tone.
        assertTrue(abs(sampleAt(out, 0)) < 400)
        val held = (fadeFramesAt(rate)..<zeros).map { abs(sampleAt(out, it)) }
        assertTrue("silence filled", held.average() > 400)
        // Gone under the new file's sound once the fall is over.
        val settled = zeros + SEAM_BRIDGE_FADE_MS.toInt() + 1
        for (frame in settled until settled + 40) {
            assertEquals(sampleAt(input, frame), sampleAt(out, frame))
        }
    }

    @Test
    fun `a file that opens on sound is played exactly as recorded`() {
        val ended = VerseSeamFade(channelCount = 1, sampleRate = 1_000)
        ended.push(noise(200, 1_200))
        val bridge = ended.bridgeSource()
        val next = VerseSeamFade(channelCount = 1, sampleRate = 1_000, bridge = bridge)
        val input = noise(100, 900, seed = 3)
        val out = next.push(input.copyOf()) + next.endOfStream()
        val body = out.size - next.fadeFrames * 2
        assertArrayEquals(input.copyOf(body), out.copyOf(body))
    }

    @Test
    fun `an ending that already fades to silence leaves nothing to bridge`() {
        val ended = VerseSeamFade(channelCount = 1, sampleRate = 1_000)
        ended.push(noise(200, 1_200) + pcm16(SEAM_BRIDGE_SOURCE_MS.toInt(), 0))
        assertNull(ended.bridgeSource())
    }

    @Test
    fun `an ending that went silent before the file ended leaves nothing to bridge`() {
        // Loud earlier in the window, but the last frames are already digital
        // silence: mirroring would play silence, then that sound, then silence.
        val ended = VerseSeamFade(channelCount = 1, sampleRate = 1_000)
        ended.push(noise(200, 1_200) + pcm16(SEAM_BRIDGE_SOURCE_MS.toInt() / 2, 0))
        assertNull(ended.bridgeSource())
    }

    @Test
    fun `a bridge from another format is not used`() {
        val ended = VerseSeamFade(channelCount = 1, sampleRate = 1_000)
        ended.push(noise(200, 1_200))
        val bridge = assertNotNullAndGet(ended.bridgeSource())
        for (next in listOf(
            VerseSeamFade(channelCount = 2, sampleRate = 1_000, bridge = bridge),
            VerseSeamFade(channelCount = 1, sampleRate = 2_000, bridge = bridge),
        )) {
            val out = next.push(pcm16(100, 0, channels = 2))
            assertTrue(out.all { it.toInt() == 0 })
        }
    }

    @Test
    fun `a recorded pause is bridged only briefly, then left silent`() {
        val rate = 1_000
        val ended = VerseSeamFade(channelCount = 1, sampleRate = rate)
        ended.push(noise(200, 1_200))
        val next = VerseSeamFade(channelCount = 1, sampleRate = rate, bridge = ended.bridgeSource())
        val out = next.push(pcm16(1_000, 0))
        val after = SEAM_BRIDGE_MAX_FILL_MS.toInt() + SEAM_BRIDGE_FADE_MS.toInt() + 1
        for (frame in after until out.size / 2) assertEquals(0, sampleAt(out, frame))
        assertTrue(abs(sampleAt(out, SEAM_BRIDGE_MAX_FILL_MS.toInt() / 2)) > 0)
    }

    @Test
    fun `stereo bridges keep their channels`() {
        val ended = VerseSeamFade(channelCount = 2, sampleRate = 1_000)
        ended.push(stereo(200, left = 2_000, right = -2_000))
        val next = VerseSeamFade(channelCount = 2, sampleRate = 1_000, bridge = ended.bridgeSource())
        val out = next.push(pcm16(40, 0, channels = 2) + stereo(60, left = 10, right = 10))
        assertTrue(sampleAt(out, 20, channels = 2, channel = 0) > 1_000)
        assertTrue(sampleAt(out, 20, channels = 2, channel = 1) < -1_000)
    }

    private fun fadeFramesAt(rate: Int) = ((rate * SEAM_FADE_MS) / 1_000L).toInt()

    private fun assertNotNullAndGet(bridge: SeamBridge?): SeamBridge {
        assertNotNull(bridge)
        return bridge!!
    }
}

/** Deterministic room-tone-like noise, never digitally silent. */
internal fun noise(frames: Int, amplitude: Int, seed: Int = 1): ByteArray {
    val random = java.util.Random(seed.toLong())
    val out = ByteArray(frames * 2)
    for (frame in 0 until frames) {
        var value = ((random.nextDouble() * 2 - 1) * amplitude).toInt()
        if (value in -2..2) value = 3
        out[frame * 2] = (value and 0xff).toByte()
        out[frame * 2 + 1] = ((value shr 8) and 0xff).toByte()
    }
    return out
}

private fun stereo(frames: Int, left: Int, right: Int): ByteArray {
    val out = ByteArray(frames * 4)
    for (frame in 0 until frames) {
        out[frame * 4] = (left and 0xff).toByte()
        out[frame * 4 + 1] = ((left shr 8) and 0xff).toByte()
        out[frame * 4 + 2] = (right and 0xff).toByte()
        out[frame * 4 + 3] = ((right shr 8) and 0xff).toByte()
    }
    return out
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
