package com.beautifulquran.domain

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Per-frame timings as the platform measured them (Android `FrameMetrics`),
 * bounded, for the log export. Durations are microseconds; [NOT_REPORTED]
 * marks a field this Android version does not give.
 *
 * Phases, in the order a frame goes through them:
 * - UI thread: [delayUs] (waiting to start: a busy main thread), [inputUs],
 *   [animationUs] (Compose recomposition runs here), [layoutUs], [drawUs]
 *   (recording the display lists).
 * - Render thread: [syncUs] (handing the frame over, uploading bitmaps),
 *   [commandUs] (issuing GPU work: shader compiles and glyph uploads land
 *   here), [swapUs].
 * - [gpuUs]: the GPU's own time, overlapping the next frame.
 */
class FrameRing(val capacity: Int) {
    private val startNanos = LongArray(capacity)
    private val fields = Array(FIELD_COUNT) { IntArray(capacity) }
    private val firstDraw = BooleanArray(capacity)
    var size = 0
        private set
    /** Frames that arrived after the ring filled, plus frames the platform itself skipped reporting. */
    var dropped = 0
        private set

    /** Keeps the first [capacity] frames: a window that starts at a known moment (launch, log start). */
    @Synchronized
    fun add(
        startNanos: Long,
        totalUs: Int,
        delayUs: Int,
        inputUs: Int,
        animationUs: Int,
        layoutUs: Int,
        drawUs: Int,
        syncUs: Int,
        commandUs: Int,
        swapUs: Int,
        gpuUs: Int,
        deadlineUs: Int,
        firstDraw: Boolean,
        platformDropped: Int = 0,
    ) {
        dropped += platformDropped
        if (size >= capacity) {
            dropped++
            return
        }
        val i = size++
        this.startNanos[i] = startNanos
        this.firstDraw[i] = firstDraw
        fields[0][i] = totalUs
        fields[1][i] = delayUs
        fields[2][i] = inputUs
        fields[3][i] = animationUs
        fields[4][i] = layoutUs
        fields[5][i] = drawUs
        fields[6][i] = syncUs
        fields[7][i] = commandUs
        fields[8][i] = swapUs
        fields[9][i] = gpuUs
        fields[10][i] = deadlineUs
    }

    @Synchronized
    fun snapshot(): FrameTimeline = FrameTimeline(
        List(size) { i ->
            FrameSample(
                startNanos = startNanos[i],
                totalUs = fields[0][i],
                delayUs = fields[1][i],
                inputUs = fields[2][i],
                animationUs = fields[3][i],
                layoutUs = fields[4][i],
                drawUs = fields[5][i],
                syncUs = fields[6][i],
                commandUs = fields[7][i],
                swapUs = fields[8][i],
                gpuUs = fields[9][i],
                deadlineUs = fields[10][i],
                firstDraw = firstDraw[i],
            )
        },
        dropped = dropped,
    )

    companion object {
        const val NOT_REPORTED = -1
        private const val FIELD_COUNT = 11
    }
}

data class FrameSample(
    val startNanos: Long,
    val totalUs: Int,
    val delayUs: Int,
    val inputUs: Int,
    val animationUs: Int,
    val layoutUs: Int,
    val drawUs: Int,
    val syncUs: Int,
    val commandUs: Int,
    val swapUs: Int,
    val gpuUs: Int,
    val deadlineUs: Int,
    val firstDraw: Boolean = false,
) {
    /** Main-thread share of the frame: everything before the render thread takes it. */
    val uiThreadUs: Int get() = delayUs + inputUs + animationUs + layoutUs + drawUs

    /** Render-thread share of the frame. */
    val renderThreadUs: Int get() = syncUs + commandUs + swapUs

    fun missed(fallbackDeadlineUs: Int): Boolean =
        totalUs > (if (deadlineUs > 0) deadlineUs else fallbackDeadlineUs)
}

/** A named moment on the same clock as the frames (`System.nanoTime`). */
data class TimedMark(val atNanos: Long, val label: String)

data class FrameTimeline(val frames: List<FrameSample>, val dropped: Int = 0) {

    /**
     * Readable report: a summary, the slowest frames with the event that came
     * just before each, then every frame as one line for offline analysis.
     * Times are seconds since [originNanos] (process start, or log start).
     */
    fun report(
        title: String,
        originNanos: Long,
        refreshHz: Float,
        marks: List<TimedMark> = emptyList(),
        slowest: Int = 25,
    ): List<String> {
        val out = ArrayList<String>(frames.size + 64)
        out += "== frame timing: $title =="
        val periodUs = if (refreshHz > 0f) (1_000_000f / refreshHz).roundToLong().toInt() else 16_667
        if (frames.isEmpty()) {
            out += "no frames recorded (dropped=$dropped)"
            return out
        }
        val totals = frames.map { it.totalUs }.sorted()
        val missed = frames.filter { it.missed(periodUs) }
        out += "refresh: ${fmt("%.1f", refreshHz)} Hz (frame budget ${ms(periodUs)} ms; " +
            "a frame misses when its total exceeds the platform deadline, else the budget)"
        out += "frames=${frames.size} dropped=$dropped missed=${missed.size} " +
            "(${fmt("%.1f", 100.0 * missed.size / frames.size)}%) " +
            "over50ms=${frames.count { it.totalUs > 50_000 }} over100ms=${frames.count { it.totalUs > 100_000 }}"
        out += "total ms: p50=${ms(pct(totals, 50))} p90=${ms(pct(totals, 90))} " +
            "p99=${ms(pct(totals, 99))} max=${ms(totals.last())}"
        out += "worst ui-thread ms=${ms(frames.maxOf { it.uiThreadUs })} " +
            "worst render-thread ms=${ms(frames.maxOf { it.renderThreadUs })} " +
            "worst gpu ms=${ms(frames.maxOf { it.gpuUs })}"
        val sortedMarks = marks.sortedBy { it.atNanos }
        val turns = turnWindows(sortedMarks)
        if (turns.isNotEmpty()) {
            out += "page turns (from the turn starting until ${TURN_TAIL_MS} ms after it settles, " +
                "when the new page's content usually lands):"
            turns.forEach { (start, endNanos) ->
                val inTurn = frames.filter { it.startNanos >= start.atNanos && it.startNanos <= endNanos }
                val worst = inTurn.maxByOrNull { it.totalUs }
                out += "  ${sec(start.atNanos - originNanos)} \"${start.label}\" " +
                    "${ms(((endNanos - start.atNanos) / 1_000).toInt())} ms: frames=${inTurn.size} " +
                    "missed=${inTurn.count { it.missed(periodUs) }}" + (worst?.let {
                        " worst=${ms(it.totalUs)} at ${sec(it.startNanos - originNanos)} " +
                            "(ui=${ms(it.uiThreadUs)} rt=${ms(it.renderThreadUs)} gpu=${ms(it.gpuUs)})"
                    } ?: "")
            }
        }
        out += "slowest frames (s since start; total; ui-thread [delay input anim layout draw]; " +
            "render-thread [sync cmd swap]; gpu; event just before):"
        frames.sortedByDescending { it.totalUs }.take(slowest).sortedBy { it.startNanos }.forEach { frame ->
            val before = sortedMarks.lastOrNull { it.atNanos <= frame.startNanos }
            val context = before?.let {
                " after \"${it.label}\" (+${ms(((frame.startNanos - it.atNanos) / 1_000).toInt())} ms)"
            } ?: ""
            out += "  ${sec(frame.startNanos - originNanos)} total=${ms(frame.totalUs)}" +
                " ui=${ms(frame.uiThreadUs)} [${ms(frame.delayUs)} ${ms(frame.inputUs)} " +
                "${ms(frame.animationUs)} ${ms(frame.layoutUs)} ${ms(frame.drawUs)}]" +
                " rt=${ms(frame.renderThreadUs)} [${ms(frame.syncUs)} ${ms(frame.commandUs)} ${ms(frame.swapUs)}]" +
                " gpu=${ms(frame.gpuUs)}" + (if (frame.firstDraw) " first-draw" else "") + context
        }
        if (sortedMarks.isNotEmpty()) {
            out += "events:"
            sortedMarks.forEach { out += "  ${sec(it.atNanos - originNanos)} ${it.label}" }
        }
        out += "frames (s,total,delay,input,anim,layout,draw,sync,cmd,swap,gpu,deadline; ms; -1 = not reported):"
        frames.forEach { f ->
            out += listOf(
                f.totalUs, f.delayUs, f.inputUs, f.animationUs, f.layoutUs, f.drawUs,
                f.syncUs, f.commandUs, f.swapUs, f.gpuUs, f.deadlineUs,
            ).joinToString(",", prefix = sec(f.startNanos - originNanos) + ",") { ms(it) }
        }
        return out
    }

    companion object {
        const val TURN_TAIL_MS = 400L

        /** Each page turn: a `stackSettleStart` to its `stackSettleEnd`, plus a tail. */
        private fun turnWindows(marks: List<TimedMark>): List<Pair<TimedMark, Long>> =
            marks.withIndex().filter { it.value.label.startsWith("stackSettleStart") }.map { (i, start) ->
                val end = marks.drop(i + 1).firstOrNull { it.label.startsWith("stackSettleEnd") }?.atNanos
                    ?: (start.atNanos + 1_000_000_000L)
                start to end + TURN_TAIL_MS * 1_000_000L
            }

        private fun pct(sorted: List<Int>, p: Int): Int =
            sorted[((sorted.size - 1) * p / 100.0).roundToLong().toInt()]

        private fun ms(us: Int): String = if (us < 0) "-1" else fmt("%.1f", us / 1000.0)

        private fun sec(nanos: Long): String = fmt("%+.3f", nanos / 1e9)

        private fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.ROOT, pattern, *args)

        private val MARK_LINE = Regex("""atNanos=(\d+)\s+(.*)""")

        /** Reads the `atNanos=` stamp back out of a recorded event line. */
        fun parseMark(line: String): TimedMark? =
            MARK_LINE.find(line)?.let { TimedMark(it.groupValues[1].toLong(), it.groupValues[2]) }
    }
}
