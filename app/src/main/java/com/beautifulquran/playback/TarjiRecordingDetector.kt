package com.beautifulquran.playback

import kotlin.math.abs
import kotlin.math.atanh
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * What a whole verse sets the bar by: its typical loudness and pitch, which
 * "lifted" is measured against, and the score of the last moment its
 * allowance keeps ([TarjiDramaWeights.maxPerVerse]).
 */
class TarjiVoiceReference internal constructor(
    internal val typicalRms: Float,
    internal val typicalF0: Float,
    internal val allowanceScore: Float,
)

/** Immutable decisions on the decoded source clock. Live phase is deliberately absent. */
internal class TarjiRecordingResult(
    val gain: FloatArray,
    val rate: FloatArray,
    val amplitude: BooleanArray,
    val eventStart: IntArray,
    val rms: FloatArray,
    val acousticOnset: IntArray,
    val hopMs: Double,
    val volumeFloor: Float,
    val calibrated: Boolean,
    /** The bar this analysis set, for judging part of the same verse alike. */
    val reference: TarjiVoiceReference? = null,
) {
    fun sample(mediaMs: Double, out: TarjiEarSample): Boolean {
        out.gain = 0f
        out.reverberating = false
        out.rateHz = 0f
        out.eventStartHop = -1
        out.eventStartMediaMs = Long.MIN_VALUE
        if (!mediaMs.isFinite() || mediaMs < 0.0 || mediaMs >= gain.size * hopMs || gain.isEmpty()) return false
        val position = (mediaMs / hopMs - 1.0).coerceAtLeast(0.0)
        val a = position.toInt().coerceAtMost(gain.lastIndex)
        val b = min(a + 1, gain.lastIndex)
        val fraction = (position - a).toFloat()
        val mode = if (fraction < 0.5f) a else b
        out.gain = gain[a] + fraction * (gain[b] - gain[a])
        out.rateHz = rate[mode]
        out.reverberating = eventStart[mode] >= 0
        out.eventStartHop = eventStart[mode]
        out.eventStartMediaMs = if (eventStart[mode] >= 0) (eventStart[mode] * hopMs).toLong() else Long.MIN_VALUE
        return true
    }
}

/** Two-sided cycles with strict seeds, conservative recording noise calibration and forward admission. */
internal object TarjiRecordingDetector {
    /**
     * [verse] judges drama against a whole verse when [frames] are only
     * part of one (the Tarjīʿ Lab's capture). Without it, [frames] are the
     * verse, and the result carries the reference they make.
     */
    fun analyze(
        frames: List<TarjiFrame>,
        knobs: Tarji,
        verse: TarjiVoiceReference? = null,
        wanted: () -> Boolean = { true },
    ): TarjiRecordingResult {
        val n = frames.size
        val hopMs = frames.firstOrNull()?.hopMs ?: 20.0
        val rms = FloatArray(n) { frames[it].hopRms }
        val am = FloatArray(n)
        val fm = FloatArray(n)
        val amWeight = FloatArray(n)
        val fmWeight = FloatArray(n)
        var hold = -1
        var reference = 0f
        for (i in frames.indices) {
            if (i and 63 == 0 && !wanted()) throw java.util.concurrent.CancellationException("retuned")
            val f = frames[i]
            if (hold != f.holdStartHop) { hold = f.holdStartHop; reference = 0f }
            val before = if (i > 0 && frames[i - 1].holdStartHop == hold) rms[i - 1] else rms[i]
            val after = if (i < n - 1 && frames[i + 1].holdStartHop == hold) rms[i + 1] else rms[i]
            am[i] = ln(max(1e-5f, (before + 2f * rms[i] + after) * 0.25f))
            amWeight[i] = if (f.voiced) 1f else 0f
            if (f.f0Valid) {
                if (reference == 0f) reference = f.f0Hz
                fm[i] = (1200.0 * ln(f.f0Hz / reference) / ln(2.0)).toFloat()
                fmWeight[i] = f.pitchQuality
            }
        }
        val rawPitch = fm.copyOf()
        for (i in frames.indices) {
            if (i and 63 == 0 && !wanted()) throw java.util.concurrent.CancellationException("retuned")
            if (fmWeight[i] <= 0f) continue
            var sum = 2f * rawPitch[i]
            var weight = 2
            for (j in intArrayOf(i - 1, i + 1)) {
                if (j in frames.indices && fmWeight[j] > 0f && frames[j].holdStartHop == frames[i].holdStartHop) {
                    sum += rawPitch[j]
                    weight++
                }
            }
            fm[i] = sum / weight
        }
        val quiet = rms.sortedArray()
        val p20 = quiet.getOrElse(n / 5) { 0f }
        val noise = frames.filter { it.hopRms < p20 && it.holdClarity < 0.5f }.map { it.hopRms }.sorted()
        val median = noise.getOrElse(noise.size / 2) { 0f }
        val deviations = noise.map { abs(it - median) }.sorted()
        val mad = deviations.getOrElse(deviations.size / 2) { 0f }
        val calibrated = noise.size >= 25 && mad > 0f
        val floor = max(0.006f, max(knobs.minVolume, if (calibrated) median + 6f * mad else 0f))
        val amRegion = Region(n)
        val fmRegion = Region(n)
        var from = 0
        while (from < n) {
            if (!frames[from].voiced || frames[from].holdMs <= 0f) { from++; continue }
            var until = from + 1
            while (until < n && frames[until].voiced &&
                frames[until].holdStartHop == frames[from].holdStartHop) until++
            if (!wanted()) throw java.util.concurrent.CancellationException("retuned")
            regions(am, amWeight, from, until, hopMs, knobs,
                atanh(knobs.minTremoloDepth.coerceIn(0.035f, 0.95f)), amRegion, amplitude = true)
            regions(fm, fmWeight, from, until, hopMs, knobs, 10f, fmRegion, amplitude = false)
            from = until
        }
        val stage = TarjiEventStage()
        val amEvidence = TarjiEvidence()
        val fmEvidence = TarjiEvidence()
        val gain = FloatArray(n)
        val rate = FloatArray(n)
        val amplitude = BooleanArray(n)
        val starts = IntArray(n) { -1 }
        val acoustic = IntArray(n) { -1 }
        for (i in frames.indices) {
            if (i and 63 == 0 && !wanted()) throw java.util.concurrent.CancellationException("retuned")
            amRegion.at(i, amEvidence)
            fmRegion.at(i, fmEvidence)
            stage.next(frames[i], knobs, amEvidence, fmEvidence, floor)
            val d = stage.decision
            gain[i] = d.gain
            rate[i] = d.rateHz
            amplitude[i] = d.usesAmplitude
            starts[i] = d.eventStartHop
            acoustic[i] = if (d.usesAmplitude) amRegion.onset[i] else fmRegion.onset[i]
        }
        val made = keepDramatic(frames, knobs.minDrama, knobs.dramaWeights, gain, starts, acoustic, hopMs, verse)
        return TarjiRecordingResult(gain, rate, amplitude, starts, rms, acoustic, hopMs, floor, calibrated, made)
    }

    /**
     * Darkens every event less dramatic than [minDrama] ([TarjiDrama]), judged
     * against this recording's own voice: the whole verse is in hand, so
     * "lifted" means above how this reciter sounds in this verse.
     */
    private fun keepDramatic(frames: List<TarjiFrame>, minDrama: Float, weights: TarjiDramaWeights, gain: FloatArray,
                             starts: IntArray, acoustic: IntArray, hopMs: Double,
                             reference: TarjiVoiceReference?): TarjiVoiceReference {
        val voicedRms = frames.filter { it.voiced }.map { it.hopRms }.sorted()
        val f0 = frames.filter { it.f0Valid }.map { it.f0Hz }.sorted()
        val typicalRms = reference?.typicalRms ?: voicedRms.getOrElse(voicedRms.size / 2) { 0f }
        val typicalF0 = reference?.typicalF0 ?: f0.getOrElse(f0.size / 2) { 0f }
        if (minDrama <= 0f) return TarjiVoiceReference(typicalRms, typicalF0, Float.NEGATIVE_INFINITY)
        val events = LinkedHashMap<Int, MutableList<Int>>()
        for (i in starts.indices) if (starts[i] >= 0) events.getOrPut(starts[i]) { ArrayList() } += i
        val scored = ArrayList<Pair<List<Int>, Float>>(events.size)
        for ((_, hops) in events) {
            var rmsSum = 0.0
            var hold = 0f
            val pitches = ArrayList<Float>()
            for (i in hops) {
                rmsSum += frames[i].hopRms
                hold = max(hold, frames[i].holdMs)
                if (frames[i].f0Valid) pitches += frames[i].f0Hz
            }
            pitches.sort()
            val drama = TarjiDrama.score(
                eventMs = hops.size * hopMs,
                holdMs = hold.toDouble(),
                loudness = if (typicalRms > 0f) (rmsSum / hops.size / typicalRms).toFloat() else 1f,
                pitchRatio = if (typicalF0 > 0f && pitches.isNotEmpty()) pitches[pitches.size / 2] / typicalF0 else 1f,
                weights = weights,
            )
            val strong = hops.maxOf { gain[it] } >= weights.minPeak
            scored += hops to if (strong) drama else -1f
        }
        // The most dramatic first, up to the verse's allowance; the rest stay
        // dark. Part of a verse is held to the bar its whole verse set.
        val ranked = scored.filter { it.second >= minDrama }.sortedByDescending { it.second }
        val bar = reference?.allowanceScore
            ?: ranked.getOrNull(weights.maxPerVerse - 1)?.second ?: Float.NEGATIVE_INFINITY
        val kept = ranked.filter { it.second >= bar }.take(weights.maxPerVerse).map { it.first }.toSet()
        for ((hops, _) in scored) {
            if (hops in kept) continue
            for (i in hops) { gain[i] = 0f; starts[i] = -1; acoustic[i] = -1 }
        }
        return TarjiVoiceReference(typicalRms, typicalF0, bar)
    }

    private class Region(n: Int) {
        val accepted = BooleanArray(n)
        val rate = FloatArray(n)
        val depth = FloatArray(n)
        val balance = FloatArray(n) { 1f }
        val onset = IntArray(n) { -1 }
        fun at(i: Int, out: TarjiEvidence) {
            out.clear()
            out.open = accepted[i]
            out.keep = accepted[i]
            out.rateHz = rate[i]
            out.depth = depth[i]
            out.levelBalance = balance[i]
            out.score = if (accepted[i]) 1f else 0f
        }
    }

    private fun regions(source: FloatArray, quality: FloatArray, from: Int, until: Int, hopMs: Double,
                        knobs: Tarji, floor: Float, out: Region, amplitude: Boolean) {
        val n = until - from
        if (n < 12) return
        val values = source.copyOfRange(from, until)
        val weights = quality.copyOfRange(from, until)
        val residual = FloatArray(n)
        val periods = FloatArray(n) { (1000.0 / (knobs.minTremoloHz.coerceAtLeast(1.5f) * hopMs)).toFloat() }
        fun centre() {
            for (i in 0 until n) {
                val radius = min(min(i, n - 1 - i), (periods[i] * 0.5f).roundToInt())
                var sum = 0.0
                var weight = 0.0
                for (j in i - radius..i + radius) { sum += values[j] * weights[j]; weight += weights[j] }
                residual[i] = if (weight > 0.0) (values[i] - sum / weight).toFloat() else 0f
            }
        }
        val extrema = TarjiExtrema(n)
        centre()
        extrema.find(residual, weights, n, 0.7f * floor)
        for (i in 0 until extrema.count - 2) {
            if (extrema.segment[i] != extrema.segment[i + 2]) continue
            val period = extrema.time[i + 2] - extrema.time[i]
            if (period <= 0f) continue
            if (tarjiCycleRate(period, hopMs, knobs.minTremoloHz, knobs.maxTremoloHz) == null) continue
            for (j in ceil(extrema.time[i].toDouble()).toInt()..extrema.time[i + 2].toInt()) periods[j] = period
        }
        centre()
        extrema.find(residual, weights, n, 0.7f * floor)
        val cycles = max(0, extrema.count - 2)
        val strong = BooleanArray(cycles)
        val weak = BooleanArray(cycles)
        val cycleRate = FloatArray(cycles)
        val cycleDepth = FloatArray(cycles)
        val cycleBalance = FloatArray(cycles) { 1f }
        for (i in 0 until cycles) {
            if (extrema.segment[i] != extrema.segment[i + 2]) continue
            val period = extrema.time[i + 2] - extrema.time[i]
            if (period <= 0f) continue
            val rate = tarjiCycleRate(period, hopMs, knobs.minTremoloHz, knobs.maxTremoloHz) ?: continue
            val peer = if (i + 4 < extrema.count && extrema.segment[i] == extrema.segment[i + 4]) i + 2
                else if (i >= 2 && extrema.segment[i] == extrema.segment[i - 2]) i - 2 else continue
            val peerPeriod = extrema.time[peer + 2] - extrema.time[peer]
            val jitter = abs(period - peerPeriod) / max(period, peerPeriod)
            val depth = min(abs(extrema.value[i + 1] - extrema.value[i]),
                abs(extrema.value[i + 2] - extrema.value[i + 1])) * 0.5f
            val first = ceil(extrema.time[i].toDouble()).toInt()
            val last = extrema.time[i + 2].toInt()
            var valid = 0
            for (j in first..last) if (weights[j] > 0f) valid++
            val coverage = valid.toFloat() / max(1, last - first + 1)
            val fraction = (extrema.time[i + 1] - extrema.time[i]) / period
            if (fraction !in 0.2f..0.8f) continue
            if (amplitude) {
                val middle = (first + last) / 2
                var a = 0.0; var b = 0.0
                for (j in first..middle) a += values[j]
                for (j in middle + 1..last) b += values[j]
                cycleBalance[i] = exp(-abs(a / (middle - first + 1) - b / max(1, last - middle))).toFloat()
            }
            strong[i] = jitter <= 0.2f && depth >= floor && coverage >= 0.85f && cycleBalance[i] >= 0.35f
            weak[i] = jitter <= 0.35f && depth >= 0.7f * floor && coverage >= 0.7f && cycleBalance[i] >= 0.35f
            cycleRate[i] = rate
            cycleDepth[i] = depth
        }
        for (seed in 0 until cycles - 2) {
            if (!strong[seed] || !strong[seed + 2] || extrema.segment[seed] != extrema.segment[seed + 4]) continue
            var first = seed
            var last = seed + 2
            while (first > 0 && weak[first - 1] && extrema.segment[first - 1] == extrema.segment[seed]) first--
            while (last + 1 < cycles && weak[last + 1] && extrema.segment[last + 3] == extrema.segment[seed]) last++
            val onset = from + ceil(extrema.time[first].toDouble()).toInt()
            for (cycle in first..last) {
                val a = ceil(extrema.time[cycle].toDouble()).toInt()
                val b = extrema.time[cycle + 2].toInt()
                for (j in a..b) {
                    if (weights[j] <= 0f) continue
                    val index = from + j
                    out.accepted[index] = true
                    out.rate[index] = cycleRate[cycle]
                    out.depth[index] = if (amplitude) tanh(cycleDepth[cycle]) else cycleDepth[cycle]
                    out.balance[index] = cycleBalance[cycle]
                    out.onset[index] = onset
                }
            }
        }
    }
}

/**
 * How dramatic one reverberation is, 0..1: the moments a reciter holds a note
 * and lifts his voice into it, against the ripples of an ordinary syllable.
 * Every term is a ramp between two values of this reciter's own voice
 * ([TarjiDramaWeights]):
 *
 *  - **Sustain**: how long the pulse itself lasts.
 *  - **Hold**: how long the note it rides is held.
 *  - **Lift**: how far the voice is raised above the verse's typical
 *    loudness or pitch, whichever is more. A pitch reading more than six
 *    semitones off is an octave error of the tracker, not a lift, and is
 *    ignored.
 */
internal object TarjiDrama {
    fun score(
        eventMs: Double,
        holdMs: Double,
        loudness: Float,
        pitchRatio: Float,
        weights: TarjiDramaWeights = TarjiDramaWeights.GENERIC,
    ): Float {
        val w = weights
        val sustain = ramp(eventMs.toFloat(), w.sustainFromMs, w.sustainToMs)
        val hold = ramp(holdMs.toFloat(), w.holdFromMs, w.holdToMs)
        val loudDb = if (loudness > 0f) 20f * log10(loudness) else -60f
        val semitones = if (pitchRatio > 0f) 12f * log2(pitchRatio) else 0f
        val pitchTrusted = abs(semitones) <= w.pitchLimitSt && loudDb >= w.pitchNeedsLoudDb
        val pitchLift = if (pitchTrusted) ramp(semitones, w.pitchFromSt, w.pitchToSt) else 0f
        val loudLift = ramp(loudDb, w.loudFromDb, w.loudToDb)
        val lift = if (w.pitchShare > 0f) (loudLift + w.pitchShare * pitchLift).coerceIn(0f, 1f)
            else max(loudLift, pitchLift)
        return if (w.heldAndLifted) {
            // Both or neither: a long flat note is not drama, nor is a loud blip.
            (1f - w.hold) * kotlin.math.sqrt(sustain * lift) + w.hold * hold
        } else {
            w.sustain * sustain + w.hold * hold + w.lift * lift
        }
    }

    private fun ramp(x: Float, from: Float, to: Float): Float = ((x - from) / (to - from)).coerceIn(0f, 1f)
}

/**
 * The ranges and weights behind [TarjiDrama] for one reciter. [GENERIC] was
 * set from 26 recordings by 13 reciters; a reciter with his own was fitted to
 * his whole recitation (docs/tarji-detection/hani-drama.md).
 */
internal data class TarjiDramaWeights(
    val sustainFromMs: Float, val sustainToMs: Float,
    val holdFromMs: Float, val holdToMs: Float,
    val loudFromDb: Float, val loudToDb: Float,
    val pitchFromSt: Float, val pitchToSt: Float,
    val sustain: Float, val hold: Float, val lift: Float,
    /** Score as held-and-lifted (geometric) plus [hold], rather than a weighted sum. */
    val heldAndLifted: Boolean = false,
    /** A pitch reading further off than this is a tracker error, not a lift. */
    val pitchLimitSt: Float = 6f,
    /** Pitch counts only when the voice is at least this much louder too. */
    val pitchNeedsLoudDb: Float = Float.NEGATIVE_INFINITY,
    /** 0: lift is loudness or pitch, whichever is more. Above 0: loudness plus this share of pitch. */
    val pitchShare: Float = 0f,
    /** Events whose detector gain never reaches this are not considered. */
    val minPeak: Float = 0f,
    /** At most this many events per verse, the most dramatic first. */
    val maxPerVerse: Int = Int.MAX_VALUE,
) {
    companion object {
        val GENERIC = TarjiDramaWeights(
            sustainFromMs = 600f, sustainToMs = 1_500f,
            holdFromMs = 1_200f, holdToMs = 3_000f,
            loudFromDb = -2f, loudToDb = 3f,
            pitchFromSt = -1f, pitchToSt = 3f,
            sustain = 0.4f, hold = 0.35f, lift = 0.25f,
        )

        /**
         * Hani Ar-Rifai (reciter 7), fitted to all 6,236 of his verses (31,019
         * detected events) and reviewed independently
         * (docs/tarji-detection/hani-drama.md). Held AND lifted: a pulse of
         * 0.4 s → 1 s, with his voice 1 → 5 dB above the verse's. Pitch is
         * supporting evidence only — within ±4 st and with the voice also
         * louder — because his falling verse cadences read as phantom +5 st
         * lifts. "Hold" is dropped: for him it measures the reciting tone, not
         * a held vowel. At most two moments a verse, of real strength.
         */
        val HANI = TarjiDramaWeights(
            sustainFromMs = 400f, sustainToMs = 1_000f,
            holdFromMs = 0f, holdToMs = 1f,
            loudFromDb = 1f, loudToDb = 5f,
            pitchFromSt = 1f, pitchToSt = 3f,
            sustain = 0f, hold = 0f, lift = 0f,
            heldAndLifted = true,
            pitchLimitSt = 4f,
            pitchNeedsLoudDb = 0.5f,
            pitchShare = 0.5f,
            minPeak = 0.3f,
            maxPerVerse = 2,
        )

        fun forReciter(reciterId: Int): TarjiDramaWeights = if (reciterId == 7) HANI else GENERIC
    }
}
