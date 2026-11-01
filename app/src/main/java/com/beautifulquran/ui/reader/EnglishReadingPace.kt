package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.EnglishWordSpan

/** A compatible literal match's printed end and requested first-pass finish. */
internal data class EnglishWordFinish(
    val word: EnglishWordSpan,
    val desiredMs: Long,
    val deadlineMs: Long,
    val sourceEndMs: Long,
    val voicedEndMs: Long,
    val leadReason: String,
)

/**
 * A monotone cubic joins word finishes without easing to rest at each word.
 * Its input is cumulative audible time, so quiet still freezes the same edge.
 */
internal class EnglishReadingPace(
    private val time: DoubleArray,
    private val ink: DoubleArray,
    val finishes: List<EnglishWordFinish>,
    val rejected: Map<String, Int>,
    private val protected: List<Pair<Double, Double>>,
) {
    val deferredMatches: Int get() = rejected.values.sum()
    private val slope = DoubleArray(time.size).also { out ->
        val secants = DoubleArray(time.lastIndex) { (ink[it + 1] - ink[it]) / (time[it + 1] - time[it]) }
        out[0] = secants.first()
        out[out.lastIndex] = secants.last()
        for (at in 1 until out.lastIndex) {
            val before = secants[at - 1]
            val after = secants[at]
            val left = time[at] - time[at - 1]
            val right = time[at + 1] - time[at]
            val a = 2 * right + left
            val b = right + 2 * left
            out[at] = if (before <= 0 || after <= 0) 0.0 else (a + b) / (a / before + b / after)
        }
        for (at in out.indices) {
            if (protected.any { ink[at] in it.first..it.second }) out[at] = 1.0
        }
    }

    /** Exact derivative extrema relative to the prior unit-rate reading clock. */
    val velocityBounds: Pair<Double, Double> = run {
        var minimum = Double.POSITIVE_INFINITY
        var maximum = 0.0
        for (at in 0 until time.lastIndex) {
            val secant = (ink[at + 1] - ink[at]) / (time[at + 1] - time[at])
            val a = 3 * (slope[at] + slope[at + 1] - 2 * secant)
            val b = 2 * (3 * secant - 2 * slope[at] - slope[at + 1])
            val vertex = if (a != 0.0) (-b / (2 * a)).coerceIn(0.0, 1.0) else 0.0
            val extremum = (a * vertex + b) * vertex + slope[at]
            minimum = minOf(minimum, minOf(slope[at], slope[at + 1], extremum))
            maximum = maxOf(maximum, maxOf(slope[at], slope[at + 1], extremum))
        }
        minimum to maximum
    }

    fun hasPositiveVelocity(): Boolean = velocityBounds.first > 0 && velocityBounds.second.isFinite()

    fun through(audible: Float): Float {
        val value = audible.coerceIn(0f, 1f).toDouble()
        var low = 0
        var high = time.lastIndex
        while (low + 1 < high) {
            val mid = (low + high) ushr 1
            if (time[mid] <= value) low = mid else high = mid
        }
        val width = time[high] - time[low]
        val p = (value - time[low]) / width
        val p2 = p * p
        val p3 = p2 * p
        return ((2 * p3 - 3 * p2 + 1) * ink[low] + (p3 - 2 * p2 + p) * width * slope[low] +
            (-2 * p3 + 3 * p2) * ink[high] + (p3 - p2) * width * slope[high]).toFloat().coerceIn(0f, 1f)
    }

    /** Cached exposure/follow inversion; playback never runs a second live clock. */
    fun audible(printed: Float): Float {
        val target = printed.coerceIn(0f, 1f)
        ink.indices.firstOrNull { ink[it].toFloat() == target }?.let { return time[it].toFloat() }
        var low = 0f
        var high = 1f
        repeat(32) {
            val mid = (low + high) / 2
            if (through(mid) < target) low = mid else high = mid
        }
        return (low + high) / 2
    }
}

/** Reordered phrases and the established terminal residual cannot become literal word deadlines. */
internal fun englishReadingPace(
    alignment: EnglishAlignment,
    segments: List<Segment>,
    clock: EnglishWashWindow,
    extent: EnglishReadingExtent?,
    /** Audible progress at a media time, through the same tempo the flow reads. */
    progressAt: (Long) -> Float = clock::progress,
): EnglishReadingPace? {
    if (alignment.timingWords.isEmpty()) return null
    val finishes = ArrayList<EnglishWordFinish>()
    val rejected = linkedMapOf<String, Int>()
    fun reject(reason: String) { rejected[reason] = (rejected[reason] ?: 0) + 1 }
    fun distance(point: Float) = (extent?.ink(point) ?: point).toDouble()
    val grouped = alignment.timingWords.groupBy { it.position }
    val units = grouped.mapValues { it.value.last() }
    val ambiguous = grouped.filterValues { words ->
        val literals = words.map { word ->
            alignment.readingText.orEmpty().let { text ->
                text.substring(kotlin.math.round(word.from * text.length).toInt(), kotlin.math.round(word.to * text.length).toInt())
                    .filter { it.isLetter() }.lowercase()
            }
        }
        literals.distinct().size != literals.size
    }.keys
    val terminal = alignment.presentationSpans.last()
    val residualTail = alignment.spans.last().position != segments.last().position
    // The reviewed reading clock stays exact throughout inversions and the
    // entire carried terminal residual, not just its final punctuation.
    val rendered = Regex("\\S+").findAll(alignment.readingText.orEmpty()).map { it.range }.toList()
    val protected = ArrayList<Pair<Double, Double>>()
    alignment.presentationSpans.filter {
        it.lastPosition > it.position || (residualTail && it == terminal)
    }.map { phrase ->
        val unitsHere = rendered.filter {
            (it.last + 1f) / alignment.readingText!!.length > phrase.from + .0000001f &&
                it.first.toFloat() / alignment.readingText.length < phrase.to - .0000001f
        }
        val from = unitsHere.firstOrNull()?.let { it.first.toFloat() / alignment.readingText!!.length } ?: phrase.from
        val to = unitsHere.lastOrNull()?.let { (it.last + 1f) / alignment.readingText!!.length } ?: phrase.to
        distance(from) to distance(to)
    }.sortedBy { it.first }.forEach { range ->
        val previous = protected.lastOrNull()
        if (previous != null && range.first <= previous.second) {
            protected[protected.lastIndex] = previous.first to maxOf(previous.second, range.second)
        } else protected += range
    }
    val fixed = (listOf(0.0, 1.0) + protected.flatMap { listOf(it.first, it.second) }).distinct().sorted()
    fun knots() = (fixed.map { it to it } + finishes.map {
        progressAt(it.deadlineMs).toDouble() to distance(it.word.to)
    }).sortedBy { it.second }
    fun fitted(): EnglishReadingPace {
        val points = knots()
        return EnglishReadingPace(points.map { it.first }.toDoubleArray(), points.map { it.second }.toDoubleArray(),
            finishes.toList(), rejected.toMap(), protected)
    }
    for (word in alignment.timingWords) {
        if (word.position in ambiguous) { reject("literal_ambiguity"); continue }
        if (word != units[word.position]) { reject("same_source_unit"); continue }
        val phrase = alignment.presentationSpans.firstOrNull { word.from < it.to } ?: continue
        if (phrase.position != phrase.lastPosition || phrase.position != word.position) {
            reject("reordered_phrase"); continue
        }
        if ((residualTail && phrase == terminal) || word.to >= 1f) { reject("terminal_policy"); continue }
        val source = segments.firstOrNull { it.position == word.position }
        if (source == null || source.endMs <= source.startMs) { reject("source_window"); continue }
        val voicedEnd = clock.spoken.orEmpty().lastOrNull {
            it.first < source.endMs && it.last > source.startMs
        }?.let { minOf(it.last, source.endMs) }
        if (voicedEnd == null || voicedEnd <= maxOf(source.startMs, clock.startMs)) { reject("voice_gate"); continue }
        // A 100 media-ms saving at 1x is preferred; it never creates a spent
        // onset or a duplicate audible coordinate during quiet/overlap.
        val desired = source.endMs - 100L
        val idealVoiced = desired > maxOf(source.startMs, clock.startMs) && clock.spoken.orEmpty().any {
            desired > it.first && desired <= it.last
        }
        val end = distance(word.to)
        val points = knots()
        val before = points.lastOrNull { it.second < end }
        val after = points.firstOrNull { it.second >= end }
        if (before == null || after == null || after.second == end || protected.any { end in it.first..it.second }) {
            reject("protected_range"); continue
        }
        fun compatible(at: Long) = progressAt(at).toDouble() > before.first &&
            progressAt(at).toDouble() < after.first
        var accepted = false
        var reason = "crossing_target"
        for (deadline in listOfNotNull(desired.takeIf { idealVoiced }, voicedEnd).distinct()) {
            if (!compatible(deadline)) { reason = "crossing_target"; continue }
            val leadReason = when {
                deadline == desired -> "lead_100"
                !idealVoiced && desired <= maxOf(source.startMs, clock.startMs) -> "voice_gate_relaxed"
                !idealVoiced -> "quiet_relaxed"
                reason == "nonpositive_velocity" || reason == "pace_range" -> "velocity_relaxed"
                else -> "ordering_relaxed"
            }
            finishes += EnglishWordFinish(word, desired, deadline, source.endMs, voicedEnd, leadReason)
            val candidate = fitted()
            if (candidate.hasPositiveVelocity() && candidate.velocityBounds.first >= .25 && candidate.velocityBounds.second <= 4.0) {
                accepted = true
                break
            }
            finishes.removeAt(finishes.lastIndex)
            // Exact endpoints cannot borrow a millisecond burst or an almost
            // stationary interval from the current reading pace. This broad
            // fourfold bound is admission policy, not a readability score.
            reason = if (candidate.hasPositiveVelocity()) "pace_range" else "nonpositive_velocity"
        }
        if (!accepted) reject(reason)
    }
    return fitted()
}
