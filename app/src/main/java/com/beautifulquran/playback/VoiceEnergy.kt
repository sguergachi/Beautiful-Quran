package com.beautifulquran.playback

import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

    /** Start of the delayed acoustic event on the media-item clock. */
    @Volatile
    var eventStartMediaMs = NO_EVENT_MS
        private set

    /** Diagnostics for the Ink Lab readout: current hold length and the
     * measured oscillation rate (Hz). Undelayed — these are for humans. */
    @Volatile
    var holdMs = 0f
        private set

    @Volatile
    var rateHz = 0f
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

    @Volatile
    private var lastFeedMs = 0L

    /**
     * Output route latency (Bluetooth etc.), pushed by the reader — the same
     * estimate the highlight clock subtracts. The tap hears
     * the voice before the listener does, so the reported signal is delayed
     * by exactly this to land on what reaches the ear now.
     */
    @Volatile
    var outputLatencyMs = 0L

    /** The audio sink's own AudioTrack buffer (wall ms), read live by the tap
     * so the shimmer is delayed past the app's internal output buffer too —
     * the dominant missing term on real devices and emulators alike. Used
     * until the reader's measured backlog ([measuredBacklogContentMs]) lands. */
    @Volatile
    var sinkLatencyMs = 0L

    /**
     * Measured tap-to-playback-head backlog (content ms), computed by the
     * reader from this probe's hop clock against `positionMs` (the same
     * clock the highlight uses) and pushed back here — the shimmer delay
     * rides it once available, so the pulse is in lockstep with the word
     * ink on any device without guessing.
     */
    @Volatile
    var measuredBacklogContentMs = -1.0

    /** Identity timestamp for the current sink session. */
    @Volatile
    var sessionStartWall = 0L

    /** Exact source-content time processed since this tap session started. */
    @Volatile
    var sessionContentMs = 0.0
        private set

    /** Called when the sink flushes or reconfigures. A discontinuity starts a
     * fresh acoustic event: no prior hold, phase, or partial hop may leak into
     * the new media position. */
    fun resetTapSession() {
        tarji.reset()
        reverberating = false
        eventStartContentMs = -1.0
        eventStartMediaMs = NO_EVENT_MS
        tremolo = 0f
        tremoloGain = 0f
        holdMs = 0f
        rateHz = 0f
        lastFeedMs = 0L
        measuredBacklogContentMs = -1.0
        sessionContentMs = 0.0
        decimSum = 0f
        decimCount = 0
        hopFill = 0
        sessionStartWall = SystemClock.elapsedRealtime()
    }

    /** Total tap-to-ear delay (wall ms) as currently applied — diagnostics. */
    @Volatile
    var earDelayTotalMs = 0L

    /** Current playback speed, pushed by [PlayerController] — the delay is in
     * wall time, the history in content hops, so the speed scales it. */
    @Volatile
    var playbackSpeed = 1f

    // Decimation state (44.1/48 kHz stereo PCM → 8 kHz mono).
    private var decimSum = 0f
    private var decimCount = 0
    private var decimStep = 6
    private var sourceSampleRate = 0
    private var analysisHop = FloatArray(Tarji.HOP_SAMPLES)
    private var hopContentDurationMs = Tarji.HOP_MS.toDouble()
    private var hopFill = 0
    @Volatile
    private var eventStartContentMs = -1.0

    /**
     * Feed 16-bit PCM straight from the audio sink. Only reads [buffer]'s
     * content (position untouched); the tap passes it through afterwards.
     * Called on the audio thread.
     */
    fun onPcm16(buffer: ByteBuffer, channels: Int, sampleRate: Int) {
        if (channels <= 0 || sampleRate <= 0) return
        val nextDecimStep = maxOf(1, sampleRate / Tarji.SAMPLE_RATE)
        // One hop stays near HOP_MS of content at the decimated rate:
        // floor-decimation gives 8820 Hz for 44.1 kHz (176.4 samples).
        val hopSamples = ((sampleRate / nextDecimStep) * (Tarji.HOP_MS / 1000f))
            .roundToInt()
            .coerceAtLeast(1)
        if (sampleRate != sourceSampleRate || analysisHop.size != hopSamples) {
            sourceSampleRate = sampleRate
            decimStep = nextDecimStep
            decimSum = 0f
            decimCount = 0
            hopFill = 0
            analysisHop = FloatArray(hopSamples)
            hopContentDurationMs = analysisHopContentMs(sampleRate, decimStep, hopSamples)
            tarji.hopSamples = hopSamples
        }
        // PCM16 is little-endian regardless of the buffer's own order flag —
        // byte-swapped reads keep their energy but lose all periodicity.
        val buf = buffer.asReadOnlyBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 2 * channels) {
            // Envelope + pitch need one channel only; skip the rest.
            decimSum += buf.short / 32768f
            buf.position(buf.position() + 2 * (channels - 1))
            if (++decimCount >= decimStep) {
                analysisHop[hopFill++] = decimSum / decimStep
                decimSum = 0f
                decimCount = 0
                if (hopFill == analysisHop.size) analyzeHop()
            }
        }
    }

    /** Analyze and publish one ~20 ms content hop. The old 2,048-sample
     * batch exposed only ~4 detector values per second, undersampling the very
     * 5–10 Hz vocal pulse the renderer was meant to follow. */
    private fun analyzeHop() {
        val speed = playbackSpeed
        val sonicMs =
            if (kotlin.math.abs(speed - 1f) > 0.001f) Tarji.SONIC_LATENCY_MS else 0f
        val measuredContentMs = measuredBacklogContentMs.takeIf { it >= 0.0 }
        tarji.delayHops = Tarji.earDelayHops(
            routeMs = outputLatencyMs,
            sinkMs = sinkLatencyMs,
            speed = speed,
            sonicContentMs = sonicMs + earDelayMs,
            measuredSinkContentMs = measuredContentMs,
        )
        val safeSpeed = speed.coerceAtLeast(0.01f)
        val sinkWallMs = measuredContentMs?.div(safeSpeed) ?: sinkLatencyMs.toDouble()
        earDelayTotalMs = (
            outputLatencyMs + sinkWallMs + (sonicMs + earDelayMs) / safeSpeed
            ).roundToLong()
        // Ink Lab detector knobs (pushed from InkEngine.tuning).
        tarji.maxTremoloHz = maxTremoloHz
        tarji.minTremoloHz = minTremoloHz
        tarji.holdMinMs = holdMinMs
        tarji.minTremoloDepth = minTremoloDepth
        tarji.minPeriodicity = minPeriodicity
        tarji.maxPitchDrift = maxPitchDrift
        tarji.attackMs = attackMs
        tarji.releaseMs = releaseMs
        tarji.onSamples8k(analysisHop)
        sessionContentMs += hopContentDurationMs
        hopFill = 0
        reverberating = tarji.syncReverberating
        eventStartContentMs = tarji.syncEventStartHop
            .takeIf { it >= 0 }
            ?.toDouble()
            ?.times(hopContentDurationMs)
            ?: -1.0
        tremolo = tarji.syncTremolo
        tremoloGain = tarji.syncTremoloGain
        holdMs = tarji.holdMs
        rateHz = tarji.lastRateHz
        lastFeedMs = SystemClock.elapsedRealtime()
    }

    /** Rebase the delayed event start onto the current media-item clock. */
    fun updatePlaybackPosition(playbackPositionMs: Long) {
        val start = eventStartContentMs
        if (start < 0.0) {
            eventStartMediaMs = NO_EVENT_MS
            return
        }
        val backlog = measuredBacklogContentMs.takeIf { it >= 0.0 }
            ?: sinkLatencyMs.toDouble() * playbackSpeed.coerceAtLeast(0f)
        eventStartMediaMs = mapTapContentToMediaMs(
            playbackPositionMs = playbackPositionMs,
            tapContentMs = sessionContentMs,
            eventStartContentMs = start,
            backlogContentMs = backlog,
        )
    }

    /** Total content hops processed since the tap session started. */
    val hopCount: Int
        get() = tarji.hopCount

    /** Detection goes silent when the PCM stops (pause, track change). */
    val isLive: Boolean
        get() = SystemClock.elapsedRealtime() - lastFeedMs < LIVE_WINDOW_MS

    /** The gain a renderer should apply: collapses promptly when audio stops. */
    val shimmerGain: Float
        get() = if (isLive) tremoloGain else 0f

    fun release() {
        tarji.reset()
        reverberating = false
        eventStartContentMs = -1.0
        eventStartMediaMs = NO_EVENT_MS
        tremolo = 0f
        tremoloGain = 0f
        holdMs = 0f
        rateHz = 0f
        lastFeedMs = 0L
        sinkLatencyMs = 0L
        measuredBacklogContentMs = -1.0
        sessionContentMs = 0.0
        earDelayTotalMs = 0L
        decimSum = 0f
        decimCount = 0
        hopFill = 0
    }

    companion object {
        const val NO_EVENT_MS = Long.MIN_VALUE
        private const val LIVE_WINDOW_MS = 350L

        /**
         * The live probe owned by [PlayerController]. Draw-phase glint reads
         * this without composition so voice energy never forces a recompose.
         */
        @Volatile
        var active: VoiceEnergy? = null

        /** Detector knobs pushed from `InkEngine.tuning` (Ink Lab Tarjīʿ). */
        @Volatile var maxTremoloHz: Float = Tarji.MAX_TREMOLO_HZ
        @Volatile var minTremoloHz: Float = Tarji.MIN_TREMOLO_HZ
        @Volatile var holdMinMs: Float = Tarji.HOLD_MIN_MS.toFloat()
        @Volatile var minTremoloDepth: Float = Tarji.MIN_TREMOLO_DEPTH
        @Volatile var minPeriodicity: Float = Tarji.MIN_PERIODICITY
        @Volatile var maxPitchDrift: Float = Tarji.MAX_PITCH_DRIFT
        @Volatile var attackMs: Float = Tarji.ATTACK_MS
        @Volatile var releaseMs: Float = Tarji.RELEASE_MS
        /** Ink Lab: extra ear delay on top of the measured terms (ms). */
        @Volatile var earDelayMs: Float = 0f
    }
}
