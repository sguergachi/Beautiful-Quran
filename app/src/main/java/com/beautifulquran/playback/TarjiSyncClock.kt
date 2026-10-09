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

/** A real sink presentation timestamp, published on the audio thread. */
internal data class TarjiSinkPosition(val rendererMs: Double, val wallNanos: Long, val playing: Boolean = true) {
    fun at(wallNanos: Long, speed: Float, playing: Boolean): Double = rendererMs +
        // Media3 can sleep for half a large output buffer between sink reads.
        if (playing && this.playing) ((wallNanos - this.wallNanos) / 1e6).coerceAtLeast(0.0) * speed else 0.0
}

/** Carry between position ticks, never through a missing/stalled clock. */
private fun elapsedMs(fromNanos: Long, toNanos: Long): Double =
    ((toNanos - fromNanos) / 1e6).coerceIn(0.0, 100.0)

/** Stop stale detector output, but let already queued audio reach the ear. */
internal fun hasAudiblePcm(feedAgeMs: Long, queuedContentMs: Double, playing: Boolean): Boolean =
    playing && (feedAgeMs in 0 until 350L || queuedContentMs > 0.0)

/**
 * Maps source PCM to the player's media-item clock using two presentation
 * clocks sampled at the same instant. Buffer capacity and decoded bursts
 * never establish the origin. Both clocks already include Bluetooth and
 * Sonic; seeks and gapless item changes simply supply their new mapping.
 * Main-thread only.
 */
internal class TarjiEarClock {
    private var contentMs = Double.NaN
    private var positionMs = 0L
    private var positionWallNanos = 0L
    private var speed = 1f
    private var playing = false
    private var hasPosition = false

    val isAnchored: Boolean get() = contentMs.isFinite()
    val isPlaying: Boolean get() = playing

    fun reset() {
        contentMs = Double.NaN
        hasPosition = false
    }

    fun onPosition(
        positionMs: Long,
        wallNanos: Long,
        contentAtHeadMs: Double,
        speed: Float,
        playing: Boolean,
    ) {
        contentMs = contentAtHeadMs
        this.positionMs = positionMs
        this.positionWallNanos = wallNanos
        this.speed = speed
        this.playing = playing
        hasPosition = true
    }

    /** Audible media position, held exactly while paused/buffering. */
    fun positionAt(wallNanos: Long): Double = if (!hasPosition) Double.NaN else
        positionMs + if (playing) elapsedMs(positionWallNanos, wallNanos) * speed else 0.0

    fun contentAtEarMs(wallNanos: Long): Double = contentMs +
        if (playing) elapsedMs(positionWallNanos, wallNanos) * speed else 0.0

    /** Source timestamp of an event; output trims never change word ownership. */
    fun mediaMsOfContent(contentMs: Double): Double = contentMs + (positionMs - this.contentMs)

    /** Read the light and graph cursor at one corrected source instant. */
    fun sampleAtEar(
        wallNanos: Long, track: TarjiEarTrack, hopMs: Double, out: TarjiEarSample,
        trimWallMs: Double, phaseLeadMs: Float, displayLeadMs: Float, readHistory: Boolean,
        generation: Int = -1, rawPulseRateHz: Float = Float.NaN, rawUsesAmplitude: Boolean = true,
    ): Boolean {
        out.clear()
        val trimMs = trimWallMs * speed
        val displayMs = if (playing) displayLeadMs.coerceAtLeast(0f) * speed else 0f
        val content = contentAtEarMs(wallNanos) - trimMs + displayMs + phaseLeadMs.coerceAtLeast(0f)
        val read = readHistory && isAnchored && if (rawPulseRateHz.isNaN()) {
            track.read(content, hopMs, out, generation)
        } else {
            track.readPulse(content, hopMs, out, rawPulseRateHz, rawUsesAmplitude)
        }
        val mediaMs = positionAt(wallNanos) - trimMs + displayMs
        if (mediaMs.isFinite()) out.mediaMs = mediaMs.roundToLong()
        if (read && out.eventStartHop >= 0) {
            out.eventStartMediaMs = mediaMsOfContent(out.eventStartHop * hopMs).roundToLong()
        }
        return read
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
    /** The pulse's rate at the ear, 0 before one is measured. */
    var rateHz = 0f
    /** The media-item position this sample was read at; [Long.MIN_VALUE] if unknown. */
    var mediaMs = Long.MIN_VALUE

    fun clear() {
        rateHz = 0f
        mediaMs = Long.MIN_VALUE
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
    private val generation = IntArray(HOPS)

    /** Centre of the first hop returned by [copyRecentRms], or -1 if empty. */
    var recentRmsStartHop = -1
        private set

    @Volatile
    var published = 0
        private set

    fun clear() {
        published = 0
        recentRmsStartHop = -1
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
        generation: Int = 0,
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
        this.generation[slot] = generation
        published = hop + 1
    }

    /**
     * The signal at [contentMs] of session content. Gain and event identity
     * are the detector's decisions as of each hop's end; the pulse is the
     * voice itself at that instant (see [TarjiEarPulse]). Clamped to what is
     * held; false while nothing is.
     */
    fun read(contentMs: Double, hopMs: Double, out: TarjiEarSample, generation: Int = -1): Boolean {
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
        val currentA = generation < 0 || this.generation[a] == generation
        val currentB = generation < 0 || this.generation[b] == generation
        val gainA = if (currentA) gain[a] else 0f
        val gainB = if (currentB) gain[b] else 0f
        out.gain = gainA + fraction * (gainB - gainA)
        // Same edge rule as the hop mirrors: the event switches where the
        // interpolated read-out crosses the midpoint between two hops.
        val heldBefore = currentA && eventStartHop[a] >= 0
        val heldAfter = currentB && eventStartHop[b] >= 0
        val held = if (heldBefore == heldAfter) heldBefore
            else if (heldAfter) fraction >= 0.5f else fraction < 0.5f
        out.reverberating = held
        out.eventStartHop = if (!held) -1 else if (heldBefore) eventStartHop[a] else eventStartHop[b]

        // The pulse lives at hop centres. One channel and one period for the
        // pair being interpolated, taken from the hop the event is read at.
        val mode = if (heldBefore || !heldAfter) a else b
        out.rateHz = if (generation < 0 || this.generation[mode] == generation) rateHz[mode] else 0f
        val period = TarjiEarPulse.periodHops(rateHz[mode], hopMs)
        out.tremolo = pulseAt(contentMs, hopMs, oldest, newest, period, usesAmplitude[mode], pitchLead[mode])
        return true
    }

    /** Read only the measured voice; offline decisions never replace the tap's phase. */
    fun readPulse(contentMs: Double, hopMs: Double, out: TarjiEarSample, rateHz: Float, usesAmplitude: Boolean): Boolean {
        out.clear()
        val count = published
        if (count == 0 || hopMs <= 0.0) return false
        val oldest = maxOf(0, count - HOPS + 1)
        val newest = count - 1
        val slot = (contentMs / hopMs - 1.0).coerceIn(oldest.toDouble(), newest.toDouble()).toInt() % HOPS
        out.rateHz = rateHz
        out.tremolo = pulseAt(contentMs, hopMs, oldest, newest,
            TarjiEarPulse.periodHops(rateHz, hopMs), usesAmplitude, pitchLead[slot])
        return true
    }

    /** Most-recent contiguous voice samples, ordered oldest to newest. Main thread. */
    fun copyRecentRms(destination: FloatArray): Int {
        val count = published
        val size = minOf(destination.size, count, HOPS - 1)
        recentRmsStartHop = if (size > 0) count - size else -1
        for (i in 0 until size) destination[i] = hopRms[(count - size + i) % HOPS]
        return size
    }

    private fun pulseAt(
        contentMs: Double, hopMs: Double, oldest: Int, newest: Int,
        period: Int, amplitude: Boolean, pitchLeadHops: Float,
    ): Float {
        val centre = contentMs / hopMs - 0.5 + if (amplitude) 0f else pitchLeadHops
        val at = centre.coerceIn(oldest.toDouble(), newest.toDouble())
        val first = at.toInt()
        val second = minOf(first + 1, newest)
        val firstPulse = pulse(first, oldest, newest, period, amplitude)
        val secondPulse = if (second == first) firstPulse
            else pulse(second, oldest, newest, period, amplitude)
        return firstPulse + (at - first).toFloat() * (secondPulse - firstPulse)
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
