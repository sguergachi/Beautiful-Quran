package com.beautifulquran.playback

import kotlin.math.PI
import kotlin.math.cos

/**
 * How long the end of a verse file ramps to silence.
 * The files end on a non-zero sample and the next one starts at 0, so the
 * join is a step. A few milliseconds of ramp removes that click and leaves
 * the breath. A seek clears the hold, so a hizb landing keeps its attack.
 */
internal const val SEAM_FADE_MS = 8L

/**
 * Holds the last [SEAM_FADE_MS] of PCM and releases it unchanged while the
 * file continues. [endOfStream] is the only path that ramps the hold to 0.
 */
internal class VerseSeamFade(
    channelCount: Int,
    sampleRate: Int,
) {
    init {
        require(channelCount > 0)
        require(sampleRate > 0)
    }

    private val frameBytes = channelCount * 2
    val fadeFrames: Int = ((sampleRate * SEAM_FADE_MS) / 1_000L).toInt().coerceAtLeast(1)
    private val holdBytes = fadeFrames * frameBytes

    private var buffered = ByteArray(0)
    private var size = 0

    /** PCM that is no longer inside the end window, still at full level. */
    fun push(pcm: ByteArray): ByteArray {
        if (pcm.isEmpty()) return ByteArray(0)
        ensure(size + pcm.size)
        System.arraycopy(pcm, 0, buffered, size, pcm.size)
        size += pcm.size
        val aligned = size - size % frameBytes
        val frames = aligned / frameBytes
        if (frames <= fadeFrames) return ByteArray(0)
        val emit = (frames - fadeFrames) * frameBytes
        val out = buffered.copyOfRange(0, emit)
        val keep = size - emit
        System.arraycopy(buffered, emit, buffered, 0, keep)
        size = keep
        return out
    }

    /** Ramp whatever is still held. A seek uses [clear] instead. */
    fun endOfStream(): ByteArray {
        val aligned = size - size % frameBytes
        if (aligned <= 0) {
            size = 0
            return ByteArray(0)
        }
        val tail = buffered.copyOf(aligned)
        size = 0
        fadeOutPcm16(tail, frameBytes / 2)
        return tail
    }

    fun clear() {
        size = 0
    }

    private fun ensure(needed: Int) {
        if (buffered.size >= needed) return
        var cap = if (buffered.isEmpty()) holdBytes.coerceAtLeast(needed) else buffered.size
        while (cap < needed) cap *= 2
        buffered = buffered.copyOf(cap)
    }
}

/** Equal-power ramp to silence. The first frame stays at full level, the last is 0. */
internal fun fadeOutPcm16(pcm: ByteArray, channelCount: Int) {
    val frameBytes = channelCount * 2
    val frames = pcm.size / frameBytes
    if (frames <= 0) return
    var offset = 0
    for (frame in 0 until frames) {
        val progress = if (frames == 1) 1f else frame.toFloat() / (frames - 1)
        val gain = cos(progress * (PI / 2.0)).toFloat()
        for (channel in 0 until channelCount) {
            val sample = (pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            val faded = (signed * gain).toInt().coerceIn(-32768, 32767)
            pcm[offset] = (faded and 0xff).toByte()
            pcm[offset + 1] = ((faded shr 8) and 0xff).toByte()
            offset += 2
        }
    }
}
