package com.beautifulquran.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How long the end of a verse file ramps to silence.
 * The files end on a non-zero sample and the next one starts at 0, so the
 * join is a step. A few milliseconds of ramp removes that click and leaves
 * the breath. A seek clears the hold, so a hizb landing keeps its attack.
 */
internal const val SEAM_FADE_MS = 8L

/**
 * How much of a verse's ending is kept to bridge the next file's opening
 * digital silence. Some packs (Ash-Shuraym, Al-Dosari) are cut from a live
 * mosque recording: every file ends on full-level room tone and the next one
 * opens with ~30-40 ms of exact zeros (encoder priming). Played back to back
 * the ambience drops out at every seam, which reads as an abrupt pause.
 */
internal const val SEAM_BRIDGE_SOURCE_MS = 60L

/** The bridge fades out over this much of the next verse's own opening. */
internal const val SEAM_BRIDGE_FADE_MS = 12L

/**
 * Digital silence longer than this is the recording's own pause, not encoder
 * priming; the bridge starts fading out here instead of filling all of it.
 */
internal const val SEAM_BRIDGE_MAX_FILL_MS = 80L

/** Tails quieter than this (RMS, 16-bit) already end in silence: nothing to bridge. */
internal const val SEAM_BRIDGE_MIN_RMS = 64.0

/** |sample| at or below this counts as digital silence. */
private const val SILENT_SAMPLE = 1

/**
 * Holds the last [SEAM_FADE_MS] of PCM and releases it unchanged while the
 * file continues. [endOfStream] is the only path that ramps the hold to 0.
 *
 * When [bridge] is set (the previous verse ended into this one without a
 * seek) and this file opens with digital silence, that silence is filled with
 * the previous ending, mirrored, rising after the previous fade and falling
 * away under this file's first sound. Nothing is inserted or removed, so the
 * sample clock the ink follows is untouched.
 */
internal class VerseSeamFade(
    private val channelCount: Int,
    sampleRate: Int,
    bridge: ShortArray? = null,
) {
    init {
        require(channelCount > 0)
        require(sampleRate > 0)
    }

    private val frameBytes = channelCount * 2
    val fadeFrames: Int = framesFor(sampleRate, SEAM_FADE_MS)
    private val holdBytes = fadeFrames * frameBytes

    private var buffered = ByteArray(0)
    private var size = 0

    // The unfaded last SEAM_BRIDGE_SOURCE_MS of this file, for the next one.
    private val historyBytes = framesFor(sampleRate, SEAM_BRIDGE_SOURCE_MS) * frameBytes
    private val history = ByteArray(historyBytes)
    private var historySize = 0

    // Bridging into this file's opening (null once done or not wanted).
    private var bridgeSource: ShortArray? = bridge?.takeIf { it.size >= channelCount }
    private val bridgeRiseFrames = fadeFrames
    private val bridgeFallFrames = framesFor(sampleRate, SEAM_BRIDGE_FADE_MS)
    private val bridgeMaxFill = framesFor(sampleRate, SEAM_BRIDGE_MAX_FILL_MS)
    private var bridgeFrame = 0
    /** Frame index (from the file start) where the bridge begins to fall; -1 = still silent. */
    private var bridgeFallStart = -1

    /** PCM that is no longer inside the end window, still at full level. */
    fun push(pcm: ByteArray): ByteArray {
        if (pcm.isEmpty()) return ByteArray(0)
        if (bridgeSource != null) applyBridge(pcm)
        remember(pcm)
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
        historySize = 0
        bridgeSource = null
    }

    /**
     * This file's unfaded ending, interleaved, if it is loud enough that
     * dropping straight into the next file's silence would be heard.
     */
    fun bridgeSource(): ShortArray? {
        val frames = historySize / frameBytes
        if (frames == 0) return null
        val samples = ShortArray(frames * channelCount)
        var sumSquares = 0.0
        for (i in samples.indices) {
            val s = readSample(history, i * 2)
            samples[i] = s.toShort()
            sumSquares += s.toDouble() * s
        }
        return samples.takeIf { sqrt(sumSquares / samples.size) >= SEAM_BRIDGE_MIN_RMS }
    }

    private fun remember(pcm: ByteArray) {
        if (pcm.size >= historyBytes) {
            System.arraycopy(pcm, pcm.size - historyBytes, history, 0, historyBytes)
            historySize = historyBytes
            return
        }
        val overflow = (historySize + pcm.size - historyBytes).coerceAtLeast(0)
        if (overflow > 0) {
            System.arraycopy(history, overflow, history, 0, historySize - overflow)
            historySize -= overflow
        }
        System.arraycopy(pcm, 0, history, historySize, pcm.size)
        historySize += pcm.size
    }

    /** Adds the mirrored previous ending into this file's opening, in place. */
    private fun applyBridge(pcm: ByteArray) {
        val source = bridgeSource ?: return
        val sourceFrames = source.size / channelCount
        val frames = pcm.size / frameBytes
        // A file that opens on sound needs no bridge (and a hizb landing keeps
        // its attack): only a leading run of digital silence is filled.
        if (bridgeFrame == 0 && bridgeFallStart < 0 && frames > 0 && !silentFrame(pcm, 0)) {
            bridgeSource = null
            return
        }
        for (frame in 0 until frames) {
            if (bridgeFallStart < 0 && (!silentFrame(pcm, frame) || bridgeFrame >= bridgeMaxFill)) {
                bridgeFallStart = bridgeFrame
            }
            val rise = if (bridgeFrame < bridgeRiseFrames) {
                sin((bridgeFrame + 1).toDouble() / bridgeRiseFrames * (PI / 2.0))
            } else {
                1.0
            }
            val fall = if (bridgeFallStart < 0) {
                1.0
            } else {
                val progress = (bridgeFrame - bridgeFallStart + 1).toDouble() / bridgeFallFrames
                if (progress >= 1.0) 0.0 else cos(progress * (PI / 2.0))
            }
            val gain = rise * fall
            if (gain <= 0.0) {
                bridgeSource = null
                return
            }
            // Mirror (ping-pong) back from the last frame so the fill keeps
            // the ending's own colour without a loop seam.
            val period = 2 * sourceFrames
            val p = bridgeFrame % period
            val srcFrame = if (p < sourceFrames) sourceFrames - 1 - p else p - sourceFrames
            for (channel in 0 until channelCount) {
                val offset = frame * frameBytes + channel * 2
                val mixed = readSample(pcm, offset) +
                    source[srcFrame * channelCount + channel] * gain
                writeSample(pcm, offset, mixed.toInt().coerceIn(-32768, 32767))
            }
            bridgeFrame++
        }
    }

    private fun silentFrame(pcm: ByteArray, frame: Int): Boolean {
        for (channel in 0 until channelCount) {
            val s = readSample(pcm, frame * frameBytes + channel * 2)
            if (s > SILENT_SAMPLE || s < -SILENT_SAMPLE) return false
        }
        return true
    }

    private fun ensure(needed: Int) {
        if (buffered.size >= needed) return
        var cap = if (buffered.isEmpty()) holdBytes.coerceAtLeast(needed) else buffered.size
        while (cap < needed) cap *= 2
        buffered = buffered.copyOf(cap)
    }
}

private fun framesFor(sampleRate: Int, ms: Long): Int =
    ((sampleRate * ms) / 1_000L).toInt().coerceAtLeast(1)

private fun readSample(pcm: ByteArray, offset: Int): Int =
    ((pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()

private fun writeSample(pcm: ByteArray, offset: Int, value: Int) {
    pcm[offset] = (value and 0xff).toByte()
    pcm[offset + 1] = ((value shr 8) and 0xff).toByte()
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
            val faded = (readSample(pcm, offset) * gain).toInt().coerceIn(-32768, 32767)
            writeSample(pcm, offset, faded)
            offset += 2
        }
    }
}
