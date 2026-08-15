package com.beautifulquran.tarjilab

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What a finger is doing on the waveform scope. */
enum class TarjiLabTool { LISTEN, HOLD, SHAPE }

/** Loop playback rate for the hold preview. */
enum class TarjiPreviewSpeed(val factor: Float, val mark: String) {
    FULL(1f, "1×"),
    HALF(0.5f, "½"),
    QUARTER(0.25f, "¼"),
}

/** Which span the preview loops: the gold hold, or the whole captured word. */
enum class TarjiPreviewScope { HOLD, WORD }

/** Loop window for [scope]. A missing hold falls back to the capture. */
fun previewLoopWindow(
    scope: TarjiPreviewScope,
    hold: TarjiHoldWindow?,
    captureMs: Float,
): TarjiHoldWindow {
    val whole = TarjiHoldWindow(0f, captureMs.coerceAtLeast(0f))
    return when (scope) {
        TarjiPreviewScope.WORD -> whole
        TarjiPreviewScope.HOLD -> hold?.takeIf { it.endMs > it.startMs } ?: whole
    }
}

/** Visible slice of the capture. [spanMs] is never wider than the file. */
data class TarjiViewWindow(val startMs: Float, val endMs: Float) {
    val spanMs: Float get() = (endMs - startMs).coerceAtLeast(0f)

    companion object {
        const val MIN_SPAN_MS = 80f
        fun fit(captureMs: Float) = TarjiViewWindow(0f, captureMs.coerceAtLeast(0f))
    }
}

fun viewMs(x: Float, width: Float, view: TarjiViewWindow): Float {
    if (width <= 0f || view.spanMs <= 0f) return view.startMs
    return (view.startMs + x / width * view.spanMs).coerceIn(view.startMs, view.endMs)
}

fun viewX(ms: Float, width: Float, view: TarjiViewWindow): Float {
    if (view.spanMs <= 0f) return 0f
    return (ms - view.startMs) / view.spanMs * width
}

fun zoomView(
    view: TarjiViewWindow,
    captureMs: Float,
    focusMs: Float,
    scale: Float,
    minSpanMs: Float = TarjiViewWindow.MIN_SPAN_MS,
): TarjiViewWindow {
    if (captureMs <= 0f) return view
    val span = (view.spanMs * scale).coerceIn(minSpanMs, captureMs.coerceAtLeast(minSpanMs))
    val t = if (view.spanMs <= 0f) 0.5f
    else ((focusMs - view.startMs) / view.spanMs).coerceIn(0f, 1f)
    val start = (focusMs - t * span).coerceIn(0f, (captureMs - span).coerceAtLeast(0f))
    return TarjiViewWindow(start, start + span)
}

/** Sample index range visible in [view]. */
fun pcmSlice(view: TarjiViewWindow, durationMs: Float, pcmSize: Int): IntRange {
    if (pcmSize <= 0 || durationMs <= 0f) return 0 until 0
    val start = (view.startMs / durationMs * pcmSize).toInt().coerceIn(0, pcmSize - 1)
    val end = (view.endMs / durationMs * pcmSize).toInt().coerceIn(start + 1, pcmSize)
    return start until end
}

fun panView(view: TarjiViewWindow, captureMs: Float, deltaMs: Float): TarjiViewWindow {
    val span = view.spanMs
    if (span <= 0f || captureMs <= 0f) return view
    val start = (view.startMs + deltaMs).coerceIn(0f, (captureMs - span).coerceAtLeast(0f))
    return TarjiViewWindow(start, start + span)
}

/** The hold is alive (vibrato) unless it has been stamped still. */
fun holdLifeAlive(kind: TarjiExpectationKind): Boolean =
    kind != TarjiExpectationKind.NO_SHIMMER

/**
 * The hold's own voice, as a 0..1 height through the band.
 * Still is a dead midline. Vibrato is a living wave.
 */
fun holdLifeY(kind: TarjiExpectationKind, t: Float, holdMs: Float): Float {
    if (!holdLifeAlive(kind)) return 0.5f
    val cycles = (holdMs / 1_000f * 5f).coerceIn(2f, 8f)
    return 0.5f - 0.22f * sin((t.coerceIn(0f, 1f) * cycles * 2.0 * PI).toFloat())
}

/**
 * Content playhead after [elapsedWallMs] at [speed], wrapped to the loop.
 * Hardware [android.media.AudioTrack.getPlaybackHeadPosition] races the ear
 * when speed ≠ 1 — this is the clock that matches what you hear.
 */
fun loopPlayheadMs(
    anchorMs: Float,
    elapsedWallMs: Float,
    speed: Float,
    loopStartMs: Float,
    loopEndMs: Float,
): Float {
    val len = (loopEndMs - loopStartMs).coerceAtLeast(1f)
    var t = (anchorMs + elapsedWallMs * speed - loopStartMs) % len
    if (t < 0f) t += len
    return loopStartMs + t
}

fun nextHoldLife(kind: TarjiExpectationKind): TarjiExpectationKind =
    if (kind == TarjiExpectationKind.NO_SHIMMER) {
        TarjiExpectationKind.PULSES
    } else {
        TarjiExpectationKind.NO_SHIMMER
    }

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
    view: TarjiViewWindow = TarjiViewWindow.fit(captureMs),
): TarjiCanvasHit? {
    if (width <= 0f || view.spanMs <= 0f) return null
    val startX = viewX(window.startMs, width, view)
    val endX = viewX(window.endMs, width, view)
    val toStart = abs(x - startX)
    val toEnd = abs(x - endX)
    if (toStart <= handleSlopPx && toStart <= toEnd) return TarjiCanvasHit.START
    if (toEnd <= handleSlopPx) return TarjiCanvasHit.END
    if (x in min(startX, endX)..max(startX, endX)) return TarjiCanvasHit.BODY
    return null
}

/** Map a canvas x to capture milliseconds. */
fun canvasMs(
    x: Float,
    width: Float,
    captureMs: Float,
    view: TarjiViewWindow = TarjiViewWindow.fit(captureMs),
): Float = viewMs(x, width, view)

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
    view: TarjiViewWindow = TarjiViewWindow.fit(captureMs),
): List<Float> {
    if (hopCount <= 0 || width <= 0f || height <= 0f || captureMs <= 0f) return current
    val hops = if (current.size == hopCount) current.toMutableList()
    else MutableList(hopCount) { i -> current.getOrElse(i) { 0f } }
    val hop = ((viewMs(x, width, view) / captureMs) * hopCount)
        .toInt()
        .coerceIn(0, hopCount - 1)
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

/**
 * One Hold-mode move. A press *outside* the current band does not commit
 * until the finger actually travels — a tap must not collapse the hold
 * to [TarjiHoldWindow.MIN_HOLD_MS].
 */
fun holdDrag(
    hit: TarjiCanvasHit?,
    originMs: Float,
    lastMs: Float,
    atMs: Float,
    current: TarjiHoldWindow,
    captureMs: Float,
): TarjiHoldWindow = when (hit) {
    TarjiCanvasHit.START -> current.moveStart(atMs, captureMs)
    TarjiCanvasHit.END -> current.moveEnd(atMs, captureMs)
    TarjiCanvasHit.BODY -> current.translate(atMs - lastMs, captureMs)
    null -> TarjiHoldWindow.of(originMs, atMs, captureMs)
}

/** Playhead rides the handle being dragged; Play still starts at the hold. */
fun playheadForHoldDrag(hit: TarjiCanvasHit?, window: TarjiHoldWindow): Float = when (hit) {
    TarjiCanvasHit.END -> (window.endMs - 1f).coerceAtLeast(window.startMs)
    else -> window.startMs
}

/** After a hold edit, Play starts at the new window — never the old loop. */
fun playheadAfterHoldEdit(window: TarjiHoldWindow): Float = window.startMs

/** Where Play should start: keep an in-hold scrub, else the hold's start. */
fun playheadForPlay(playheadMs: Float, window: TarjiHoldWindow?): Float {
    if (window == null) return playheadMs.coerceAtLeast(0f)
    return if (isInsideHold(playheadMs, window)) playheadMs else window.startMs
}

/** True when [ms] sits inside the loopable hold (end is exclusive). */
fun isInsideHold(ms: Float, window: TarjiHoldWindow): Boolean =
    ms >= window.startMs && ms < window.endMs

/**
 * What the lab word wears at [ms]. Still is dead. A hand-shaped envelope
 * owns the glow so Shape is visible; otherwise the detector.
 */
data class LabWordGlow(
    val holding: Boolean,
    val tremolo: Float,
    val gain: Float,
)

fun labWordGlow(
    kind: TarjiExpectationKind,
    envelope: List<Float>,
    trace: TarjiLabTrace?,
    ms: Float,
    hopDurationMs: Float,
): LabWordGlow {
    if (kind == TarjiExpectationKind.NO_SHIMMER) return LabWordGlow(false, 0f, 0f)
    if (envelope.isNotEmpty() && hopDurationMs > 0f) {
        val hop = (ms / hopDurationMs).toInt().coerceIn(0, envelope.lastIndex)
        val amp = envelope[hop].coerceIn(0f, 1f)
        return LabWordGlow(
            holding = amp > 0.04f,
            tremolo = amp * 2f - 1f,
            gain = 1f,
        )
    }
    if (trace == null) return LabWordGlow(false, 0f, 0f)
    val point = tracePointAt(trace, ms)
    return LabWordGlow(point.reverberating, point.tremolo, point.gain)
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
