package com.beautifulquran.playback

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Exact content duration represented by one decimated analysis hop. */
internal fun analysisHopContentMs(
    sourceSampleRate: Int,
    decimation: Int,
    hopSamples: Int,
): Double = hopSamples * decimation * 1_000.0 / sourceSampleRate

/** Map a tap-content timestamp onto the media-item clock at the playback head. */
internal fun mapTapContentToMediaMs(
    playbackPositionMs: Long,
    tapContentMs: Double,
    eventStartContentMs: Double,
    backlogContentMs: Double,
): Long = (
    playbackPositionMs.toDouble() +
        backlogContentMs +
        eventStartContentMs -
        tapContentMs
    ).roundToLong()

/** Content-time delay of Sonic's resampler away from unity playback speed. */
internal fun sonicContentLatencyMs(speed: Float): Float =
    if (abs(speed - 1f) > 0.001f) Tarji.SONIC_LATENCY_MS else 0f

/**
 * Stable tap-to-playback-head clock for one sink session.
 *
 * The sink capacity supplies the initial absolute delay; thereafter the tap
 * and playback-head content clocks measure only queue growth or drain. This
 * avoids pretending a late UI poll happened at the beginning of the session.
 */
internal data class TarjiBacklogAnchor(
    val tapContentMs: Double,
    val playbackContentMs: Long,
    val backlogContentMs: Double,
    val speed: Float,
) {
    fun estimate(tapContentMs: Double, playbackContentMs: Long): Double =
        (
            backlogContentMs +
                (tapContentMs - this.tapContentMs) -
                (playbackContentMs - this.playbackContentMs)
            ).coerceIn(0.0, MAX_BACKLOG_CONTENT_MS)

    companion object {
        /** Wait until the tap has supplied one sink buffer before anchoring it. */
        fun isReady(
            tapContentMs: Double,
            sinkLatencyMs: Long,
            speed: Float,
        ): Boolean = sinkLatencyMs > 0L && tapContentMs >= sinkContentMs(sinkLatencyMs, speed)

        fun capture(
            tapContentMs: Double,
            playbackContentMs: Long,
            sinkLatencyMs: Long,
            speed: Float,
        ): TarjiBacklogAnchor {
            val safeTapMs = tapContentMs.coerceAtLeast(0.0)
            val sinkContentMs = sinkContentMs(sinkLatencyMs, speed)
            val initialMs = if (sinkContentMs > 0f) {
                minOf(safeTapMs, sinkContentMs)
            } else {
                safeTapMs
            }
            return TarjiBacklogAnchor(
                tapContentMs = safeTapMs,
                playbackContentMs = playbackContentMs,
                backlogContentMs = initialMs.coerceAtMost(MAX_BACKLOG_CONTENT_MS),
                speed = speed,
            )
        }

        private fun sinkContentMs(sinkLatencyMs: Long, speed: Float): Double =
            sinkLatencyMs.coerceAtLeast(0L) * speed.coerceAtLeast(0f).toDouble()

        private const val MAX_BACKLOG_CONTENT_MS = 400.0
    }
}

/**
 * Where the listener's ear is in the tap session's content, at any instant.
 *
 * The tap is fed in bursts — on a phone, ~150 ms of PCM every ~150 ms — so
 * "the newest hop minus the backlog" only moves when a burst lands. Read that
 * way, a 4–6 Hz reverberation reaches the screen as a sample-and-hold at
 * about one and a half samples per cycle: no visible swing, and up to a burst
 * out of step with the voice. The playback head does move smoothly, and the
 * two clocks differ by a constant for the life of a sink session:
 *
 *     content at the ear = playback position − [offsetMs]
 *
 * so the offset is fixed once (the same full-sink baseline
 * [TarjiBacklogAnchor] uses) and every frame reads the detector's history at
 * the playback head itself. A stall holds the read-out with the audio; a
 * gapless handoff to the next ayah, where the position restarts but the PCM
 * runs on, carries the offset across.
 *
 * Main-thread only: fed by the reader's position tick, read by the frame loop.
 */
internal class TarjiEarClock {
    private var offsetMs = Double.NaN
    private var positionMs = 0L
    private var positionWallNanos = 0L
    private var speed = 1f

    val isAnchored: Boolean get() = !offsetMs.isNaN()

    fun reset() {
        offsetMs = Double.NaN
    }

    fun onPosition(
        positionMs: Long,
        wallNanos: Long,
        tapContentMs: Double,
        sinkLatencyMs: Long,
        speed: Float,
    ) {
        if (isAnchored && abs(speed - this.speed) > 0.001f) reset()
        if (isAnchored && positionMs < this.positionMs - HANDOFF_BACK_MS) {
            // The next ayah's clock restarts while the sink plays on.
            offsetMs += positionMs - positionAt(wallNanos)
        } else if (!isAnchored &&
            TarjiBacklogAnchor.isReady(tapContentMs, sinkLatencyMs, speed)
        ) {
            val anchor = TarjiBacklogAnchor.capture(tapContentMs, positionMs, sinkLatencyMs, speed)
            offsetMs = positionMs + anchor.backlogContentMs - anchor.tapContentMs
        }
        this.positionMs = positionMs
        this.positionWallNanos = wallNanos
        this.speed = speed
    }

    /** Playback position at [wallNanos], carried forward from the last tick. */
    fun positionAt(wallNanos: Long): Double {
        val sinceMs = ((wallNanos - positionWallNanos) / 1e6).coerceIn(0.0, MAX_CARRY_MS)
        return positionMs + sinceMs * speed
    }

    /** Tap-session content time at the ear, or NaN before the sink has filled. */
    fun contentAtEarMs(wallNanos: Long): Double = positionAt(wallNanos) - offsetMs

    /** Media-item position at which content [contentMs] is heard. */
    fun mediaMsOfContent(contentMs: Double): Double = contentMs + offsetMs

    private companion object {
        /** Ticks arrive every ~33 ms while playing; a paused clock must not run on. */
        const val MAX_CARRY_MS = 100.0
        /** A backward step without a sink flush is an item handoff, not jitter. */
        const val HANDOFF_BACK_MS = 200L
    }
}

/**
 * The visible pulse: the voice's own flutter around its local level, for any
 * hop of a per-hop series (20 ms RMS for intensity, folded pitch for vibrato).
 *
 * The detector's tremolo answers a different question — is there a periodic
 * reverberation on this hold — from a causal 1.3 s window, and what it leaves
 * after removing that window's trend is mostly the hold's slow swell: one
 * flare a half-second long where the ear hears five ripples. The light has a
 * luxury the detector does not. The tap runs a sink buffer ahead of the
 * speaker, so by the time a hop reaches the ear the hops after it are already
 * in hand, and the level around it can be taken from both sides:
 *
 *     pulse(h) = (series(h) − mean over one pulse period centred on h)
 *                ÷ the flutter's own recent amplitude
 *
 * A mean over exactly one period holds none of the pulse, so the swell drops
 * out and the ripple stays, in phase — a centred window has no lag to correct.
 * The result is ~−1..1 whatever the ripple's depth, floored so a steady note
 * does not have its noise blown up into a flicker.
 */
internal object TarjiEarPulse {

    /** One pulse period in hops, held to what the tap's lead can cover. */
    fun periodHops(rateHz: Float, hopMs: Double): Int =
        if (rateHz > 0f && hopMs > 0.0) {
            (1000.0 / (rateHz * hopMs)).roundToLong().toInt().coerceIn(MIN_PERIOD_HOPS, MAX_PERIOD_HOPS)
        } else {
            DEFAULT_PERIOD_HOPS
        }

    /** Hops after [hop] the pulse at [hop] reads when they exist. */
    fun lookaheadHops(periodHops: Int): Int = periodHops / 2 + 1

    /**
     * Pulse at [hop] of [series], a ring of [size] slots holding hops
     * [oldest]..[newest] (a plain array is a ring that never wraps).
     * [floor] is the smallest flutter, as a fraction of the level, that is
     * shown at full swing.
     */
    fun at(
        series: FloatArray,
        size: Int,
        hop: Int,
        oldest: Int,
        newest: Int,
        periodHops: Int,
        floor: Float,
    ): Float {
        if (newest < oldest) return 0f
        val half = periodHops / 2
        var sumSq = 0f
        var level = 0f
        var flutter = 0f
        for (j in hop - periodHops..hop) {
            // Centred mean over one period; an even period halves its ends.
            var mean = 0f
            for (k in j - half..j + half) {
                val weight = if (periodHops % 2 == 0 && (k == j - half || k == j + half)) 0.5f else 1f
                mean += weight * series[k.coerceIn(oldest, newest) % size]
            }
            mean /= if (periodHops % 2 == 0) periodHops.toFloat() else (2 * half + 1).toFloat()
            // A 1-2-1 blur takes the pitch-period beat out of a 20 ms RMS.
            val value = (series[(j - 1).coerceIn(oldest, newest) % size] +
                2f * series[j.coerceIn(oldest, newest) % size] +
                series[(j + 1).coerceIn(oldest, newest) % size]) * 0.25f
            val residual = value - mean
            sumSq += residual * residual
            if (j == hop) {
                level = mean
                flutter = residual
            }
        }
        val amplitude = sqrt(2f * sumSq / (periodHops + 1))
        val scale = maxOf(amplitude, floor * abs(level), 1e-6f)
        return (flutter / scale).coerceIn(-1.5f, 1.5f)
    }

    /**
     * The pulse at the centre of every hop from [from] on — the offline twin
     * of [TarjiEarTrack.read], for the Tarjīʿ Lab's replay. Hops before
     * [from] (a capture's lead-in) only supply the level behind the first.
     */
    fun series(
        hopRms: FloatArray,
        pitch: FloatArray,
        pitchLeadHops: FloatArray,
        rateHz: FloatArray,
        usesAmplitude: BooleanArray,
        hopMs: Double,
        from: Int,
    ): FloatArray {
        val newest = hopRms.size - 1
        return FloatArray(hopRms.size - from) { i ->
            val hop = from + i
            val period = periodHops(rateHz[hop], hopMs)
            if (usesAmplitude[hop]) {
                at(hopRms, hopRms.size, hop, 0, newest, period, AMPLITUDE_FLOOR)
            } else {
                val centre = (hop + pitchLeadHops[hop]).coerceIn(0f, newest.toFloat())
                val first = centre.toInt()
                val second = minOf(first + 1, newest)
                val firstPulse = at(pitch, pitch.size, first, 0, newest, period, PITCH_FLOOR)
                val secondPulse = at(pitch, pitch.size, second, 0, newest, period, PITCH_FLOOR)
                firstPulse + (centre - first) * (secondPulse - firstPulse)
            }
        }
    }

    /** Smallest intensity flutter shown at full swing (fraction of level). */
    const val AMPLITUDE_FLOOR = 0.03f
    /** Smallest pitch flutter shown at full swing — about five cents. */
    const val PITCH_FLOOR = 0.003f

    // 8 Hz .. 3 Hz at 20 ms hops. A slower pulse keeps the 3 Hz window: its
    // level then holds a little of the pulse, which costs depth, not phase.
    private const val MIN_PERIOD_HOPS = 6
    private const val MAX_PERIOD_HOPS = 16
    private const val DEFAULT_PERIOD_HOPS = 10
}

/** One frame's tarjīʿ signal as it reaches the ear. Reused by its caller. */
class TarjiEarSample {
    var tremolo = 0f
    var gain = 0f
    var reverberating = false
    /** Analysis hop at which the heard event was acquired, or -1. */
    var eventStartHop = -1
    /** That hop on the media-item clock; [VoiceEnergy.NO_EVENT_MS] if unknown. */
    var eventStartMediaMs = Long.MIN_VALUE

    fun clear() {
        tremolo = 0f
        gain = 0f
        reverberating = false
        eventStartHop = -1
        eventStartMediaMs = Long.MIN_VALUE
    }
}

/**
 * The detector's recent output and the voice it was measured on, one slot per
 * analysis hop, written by the audio thread and read by the frame loop at any
 * content time.
 *
 * Slots are written before [published], so a reader that loads the count
 * first sees complete hops; the ring is several times the deepest backlog,
 * so the slots being rewritten are never the ones being read.
 */
internal class TarjiEarTrack {
    private val hopRms = FloatArray(HOPS)
    private val pitch = FloatArray(HOPS)
    private val pitchLead = FloatArray(HOPS)
    private val rateHz = FloatArray(HOPS)
    private val usesAmplitude = BooleanArray(HOPS)
    private val gain = FloatArray(HOPS)
    private val eventStartHop = IntArray(HOPS)

    @Volatile
    var published = 0
        private set

    fun clear() {
        published = 0
    }

    /** Record zero-based [hop]; [eventStartHop] is -1 unless reverberating. */
    fun publish(
        hop: Int,
        hopRms: Float,
        pitchHz: Float,
        pitchLeadHops: Float,
        rateHz: Float,
        usesAmplitude: Boolean,
        gain: Float,
        eventStartHop: Int,
    ) {
        if (hop < 0) return
        val slot = hop % HOPS
        this.hopRms[slot] = hopRms
        this.pitch[slot] = pitchHz
        this.pitchLead[slot] = pitchLeadHops
        this.rateHz[slot] = rateHz
        this.usesAmplitude[slot] = usesAmplitude
        this.gain[slot] = gain
        this.eventStartHop[slot] = eventStartHop
        published = hop + 1
    }

    /**
     * The signal at [contentMs] of session content. Gain and event identity
     * are the detector's decisions as of each hop's end; the pulse is the
     * voice itself at that instant (see [TarjiEarPulse]). Clamped to what is
     * held; false while nothing is.
     */
    fun read(contentMs: Double, hopMs: Double, out: TarjiEarSample): Boolean {
        out.clear()
        val count = published
        if (count == 0 || hopMs <= 0.0) return false
        val oldest = maxOf(0, count - HOPS + 1)
        val newest = count - 1
        val position = (contentMs / hopMs - 1.0).coerceIn(oldest.toDouble(), newest.toDouble())
        val before = position.toInt()
        val after = minOf(before + 1, newest)
        val fraction = (position - before).toFloat()
        val a = before % HOPS
        val b = after % HOPS
        out.gain = gain[a] + fraction * (gain[b] - gain[a])
        // Same edge rule as the hop mirrors: the event switches where the
        // interpolated read-out crosses the midpoint between two hops.
        val heldBefore = eventStartHop[a] >= 0
        val heldAfter = eventStartHop[b] >= 0
        val held = if (heldBefore == heldAfter) heldBefore
            else if (heldAfter) fraction >= 0.5f else fraction < 0.5f
        out.reverberating = held
        out.eventStartHop = if (!held) -1 else if (heldBefore) eventStartHop[a] else eventStartHop[b]

        // The pulse lives at hop centres. One channel and one period for the
        // pair being interpolated, taken from the hop the event is read at.
        val mode = if (heldBefore || !heldAfter) a else b
        val period = TarjiEarPulse.periodHops(rateHz[mode], hopMs)
        val centre = contentMs / hopMs - 0.5 + if (usesAmplitude[mode]) 0f else pitchLead[mode]
        val at = centre.coerceIn(oldest.toDouble(), newest.toDouble())
        val first = at.toInt()
        val second = minOf(first + 1, newest)
        val firstPulse = pulse(first, oldest, newest, period, usesAmplitude[mode])
        val secondPulse = if (second == first) firstPulse
            else pulse(second, oldest, newest, period, usesAmplitude[mode])
        out.tremolo = firstPulse + (at - first).toFloat() * (secondPulse - firstPulse)
        return true
    }

    private fun pulse(hop: Int, oldest: Int, newest: Int, period: Int, amplitude: Boolean): Float =
        if (amplitude) {
            TarjiEarPulse.at(hopRms, HOPS, hop, oldest, newest, period, TarjiEarPulse.AMPLITUDE_FLOOR)
        } else {
            TarjiEarPulse.at(pitch, HOPS, hop, oldest, newest, period, TarjiEarPulse.PITCH_FLOOR)
        }

    private companion object {
        /** 2.5 s of 20 ms hops. */
        const val HOPS = 128
    }
}
