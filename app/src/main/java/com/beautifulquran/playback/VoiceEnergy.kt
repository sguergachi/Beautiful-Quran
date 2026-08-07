package com.beautifulquran.playback

import android.os.SystemClock
import java.nio.ByteBuffer

/**
 * Live voice analysis of the reciter, feeding the glint's tarjīʿ shimmer.
 *
 * PCM is tapped inside the app's own audio pipeline ([VoiceTapAudioProcessor]
 * on the player's audio sink) — no `RECORD_AUDIO` permission, no Visualizer
 * (which would also be dead on emulators and several Bluetooth routes), and
 * the envelope arrives in wall-clock order at whatever speed is playing, so
 * the detected reverberation is the very one the listener hears.
 *
 * Buffers arrive on ExoPlayer's audio thread; the glint draw path reads only
 * the volatile mirrors — no locks, no composition.
 */
class VoiceEnergy {

    private val tarji = Tarji()

    /** True while a held note carries a detected reverberation (tarjīʿ). */
    @Volatile
    var reverberating = false
        private set

    /** The reverberation oscillation, zero-centred ~−1..1, in phase with the
     * voice. Ride this for the synced shimmer. */
    @Volatile
    var tremolo = 0f
        private set

    /** Attack/release envelope of the detection (0..1) — ramp the effect by
     * this so the shimmer never pops at detection edges. */
    @Volatile
    var tremoloGain = 0f
        private set

    /** True while a voiced single note is being held (with or without
     * reverberation) — the still-gold sine floor only breathes then. */
    @Volatile
    var holdingNote = false
        private set

    @Volatile
    private var lastFeedMs = 0L

    /**
     * Output route latency (sink buffer + Bluetooth etc.), pushed by the
     * reader — the same estimate the highlight clock subtracts. The tap hears
     * the voice before the listener does, so the reported signal is delayed
     * by exactly this to land on what reaches the ear now.
     */
    @Volatile
    var outputLatencyMs = 0L

    /** Current playback speed, pushed by [PlayerController] — the delay is in
     * wall time, the history in content hops, so the speed scales it. */
    @Volatile
    var playbackSpeed = 1f

    // Decimation state (44.1/48 kHz stereo PCM → 8 kHz mono).
    private var decimSum = 0f
    private var decimCount = 0
    private var decimStep = 6
    private val chunk = FloatArray(2048)
    private var chunkFill = 0

    /**
     * Feed 16-bit PCM straight from the audio sink. Only reads [buffer]'s
     * content (position untouched); the tap passes it through afterwards.
     * Called on the audio thread.
     */
    fun onPcm16(buffer: ByteBuffer, channels: Int, sampleRate: Int) {
        if (channels <= 0 || sampleRate <= 0) return
        decimStep = maxOf(1, sampleRate / Tarji.SAMPLE_RATE)
        // PCM16 is little-endian regardless of the buffer's own order flag —
        // byte-swapped reads keep their energy but lose all periodicity.
        val buf = buffer.asReadOnlyBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 2 * channels) {
            // Envelope + pitch need one channel only; skip the rest.
            decimSum += buf.short / 32768f
            buf.position(buf.position() + 2 * (channels - 1))
            if (++decimCount >= decimStep) {
                chunk[chunkFill++] = decimSum / decimStep
                decimSum = 0f
                decimCount = 0
                if (chunkFill >= chunk.size) flushChunk()
            }
        }
    }

    private fun flushChunk() {
        if (chunkFill == 0) return
        tarji.delayHops =
            (outputLatencyMs * playbackSpeed / Tarji.HOP_MS).toInt().coerceIn(0, 63)
        tarji.onSamples8k(chunk, chunkFill)
        chunkFill = 0
        reverberating = tarji.syncReverberating
        tremolo = tarji.syncTremolo
        tremoloGain = tarji.syncTremoloGain
        holdingNote = tarji.syncHoldingNote
        lastFeedMs = SystemClock.elapsedRealtime()
    }

    /** Detection goes silent when the PCM stops (pause, track change). */
    val isLive: Boolean
        get() = SystemClock.elapsedRealtime() - lastFeedMs < LIVE_WINDOW_MS

    /** The gain a renderer should apply: collapses promptly when audio stops. */
    val shimmerGain: Float
        get() = if (isLive) tremoloGain else 0f

    fun release() {
        tarji.reset()
        reverberating = false
        tremolo = 0f
        tremoloGain = 0f
        holdingNote = false
        lastFeedMs = 0L
        decimSum = 0f
        decimCount = 0
        chunkFill = 0
    }

    companion object {
        private const val LIVE_WINDOW_MS = 350L

        /**
         * The live probe owned by [PlayerController]. Draw-phase glint reads
         * this without composition so voice energy never forces a recompose.
         */
        @Volatile
        var active: VoiceEnergy? = null
    }
}
