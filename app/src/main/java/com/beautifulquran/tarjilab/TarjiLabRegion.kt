package com.beautifulquran.tarjilab

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a finger is doing on the waveform scope. */
enum class TarjiLabTool { LISTEN, HOLD, SHAPE }

/** Which part of the hold window a press landed on. */
enum class TarjiCanvasHit { START, END, BODY }

/**
 * The listener's selected hold on a capture — start and stop of the
 * climactic note, independent of any sine the detector might fit.
 */
data class TarjiHoldWindow(
    val startMs: Float,
    val endMs: Float,
) {
    val durationMs: Float get() = (endMs - startMs).coerceAtLeast(0f)

    fun moveStart(ms: Float, captureMs: Float): TarjiHoldWindow {
        val start = ms.coerceIn(0f, (endMs - MIN_HOLD_MS).coerceAtLeast(0f))
        return copy(startMs = start)
    }

    fun moveEnd(ms: Float, captureMs: Float): TarjiHoldWindow {
        val end = ms.coerceIn((startMs + MIN_HOLD_MS).coerceAtMost(captureMs), captureMs)
        return copy(endMs = end)
    }

    fun translate(deltaMs: Float, captureMs: Float): TarjiHoldWindow {
        val width = durationMs.coerceAtLeast(MIN_HOLD_MS)
        val start = (startMs + deltaMs).coerceIn(0f, (captureMs - width).coerceAtLeast(0f))
        return TarjiHoldWindow(start, (start + width).coerceAtMost(captureMs))
    }

    companion object {
        const val MIN_HOLD_MS = 80f

        fun of(startMs: Float, endMs: Float, captureMs: Float): TarjiHoldWindow {
            val lo = min(startMs, endMs).coerceIn(0f, captureMs)
            val hi = max(startMs, endMs).coerceIn(0f, captureMs)
            return if (hi - lo >= MIN_HOLD_MS) {
                TarjiHoldWindow(lo, hi)
            } else {
                val start = lo.coerceIn(0f, (captureMs - MIN_HOLD_MS).coerceAtLeast(0f))
                TarjiHoldWindow(start, (start + MIN_HOLD_MS).coerceAtMost(captureMs))
            }
        }
    }
}

/** First window: the detector's span if it heard a hold, else the whole capture. */
fun seedHoldWindow(
    captureMs: Float,
    detectorStartMs: Float?,
    detectorEndMs: Float?,
): TarjiHoldWindow {
    if (captureMs <= 0f) return TarjiHoldWindow(0f, 0f)
    val start = detectorStartMs
    val end = detectorEndMs
    return if (start != null && end != null && end > start) {
        TarjiHoldWindow.of(start, end, captureMs)
    } else {
        TarjiHoldWindow(0f, captureMs)
    }
}

/**
 * Hit-test the hold handles. [handleSlopPx] is the finger target around
 * each edge; the body is the interior, used to slide the whole window.
 */
fun hitHoldWindow(
    x: Float,
    width: Float,
    window: TarjiHoldWindow,
    captureMs: Float,
    handleSlopPx: Float,
): TarjiCanvasHit? {
    if (width <= 0f || captureMs <= 0f) return null
    val startX = window.startMs / captureMs * width
    val endX = window.endMs / captureMs * width
    val toStart = abs(x - startX)
    val toEnd = abs(x - endX)
    if (toStart <= handleSlopPx && toStart <= toEnd) return TarjiCanvasHit.START
    if (toEnd <= handleSlopPx) return TarjiCanvasHit.END
    if (x in min(startX, endX)..max(startX, endX)) return TarjiCanvasHit.BODY
    return null
}

/** Map a canvas x to capture milliseconds. */
fun canvasMs(x: Float, width: Float, captureMs: Float): Float {
    if (width <= 0f) return 0f
    return (x / width * captureMs).coerceIn(0f, captureMs)
}

/**
 * Paint one hop of a hand-shaped envelope from a finger position.
 * [value] is 0 at the bottom of the canvas and 1 at the top.
 * Empty [current] seeds a hop-aligned zero array.
 */
fun paintEnvelope(
    current: List<Float>,
    hopCount: Int,
    captureMs: Float,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
): List<Float> {
    if (hopCount <= 0 || width <= 0f || height <= 0f || captureMs <= 0f) return current
    val hops = if (current.size == hopCount) current.toMutableList()
    else MutableList(hopCount) { i -> current.getOrElse(i) { 0f } }
    val hop = ((x / width) * hopCount).toInt().coerceIn(0, hopCount - 1)
    val value = (1f - y / height).coerceIn(0f, 1f)
    hops[hop] = value
    if (hop > 0) hops[hop - 1] = (hops[hop - 1] + value) * 0.5f
    if (hop < hopCount - 1) hops[hop + 1] = (hops[hop + 1] + value) * 0.5f
    return hops
}

/** Seed a drawable envelope from the detector's 80 ms RMS, normalized 0..1. */
fun envelopeFromTrace(trace: TarjiLabTrace): List<Float> {
    var peak = 0f
    for (v in trace.envRms) if (v > peak) peak = v
    if (peak <= 1e-6f) return List(trace.hopCount) { 0f }
    return List(trace.hopCount) { i -> (trace.envRms[i] / peak).coerceIn(0f, 1f) }
}

/** Loop start/end frames for a hold window on a hop-aligned capture. */
fun loopFrames(
    window: TarjiHoldWindow,
    captureMs: Float,
    hopCount: Int,
    hopSamples: Int,
): IntRange {
    val total = (hopCount * hopSamples).coerceAtLeast(1)
    if (captureMs <= 0f) return 0 until total
    val start = (window.startMs / captureMs * total).roundToInt().coerceIn(0, total - 1)
    val end = (window.endMs / captureMs * total).roundToInt().coerceIn(start + 1, total)
    return start until end
}
