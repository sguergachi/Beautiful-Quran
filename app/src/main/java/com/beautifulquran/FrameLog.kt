package com.beautifulquran

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.FrameMetrics
import android.view.Window
import com.beautifulquran.domain.FrameRing
import com.beautifulquran.domain.FrameTimeline
import com.beautifulquran.domain.TimedMark
import java.lang.ref.WeakReference

/**
 * Frame timings for the log export, measured by the platform per frame.
 *
 * Two windows:
 * - **Launch**: the first [LAUNCH_WINDOW_MS] of every process, frames and
 *   navigation events, recorded without being asked. A cold launch's first
 *   chapter open is over before anyone can reach Settings to press Log
 *   export, so the export carries this window whenever the process is the
 *   one that launched it. After the window the listener is removed.
 * - **Recording**: every frame while a log export records.
 *
 * Nothing leaves the phone; the export's share sheet is the only way out.
 * The cost while listening is the platform copying a few numbers per frame
 * to a background thread.
 */
internal object FrameLog {
    const val LAUNCH_WINDOW_MS = 60_000L
    private const val LAUNCH_FRAMES = 60 * 144
    private const val LAUNCH_MARKS = 600
    // Three minutes at 144 Hz.
    private const val RECORDING_FRAMES = 180 * 144

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy {
        Handler(HandlerThread("bq-frame-log").apply { start() }.looper)
    }

    /** Process start on the frames' clock (`System.nanoTime` is CLOCK_MONOTONIC, as is uptime). */
    val processStartNanos: Long = Process.getStartUptimeMillis() * 1_000_000L

    private val launch = FrameRing(LAUNCH_FRAMES)
    private val launchMarks = ArrayList<TimedMark>()
    @Volatile private var recording: FrameRing? = null
    @Volatile var refreshHz: Float = 60f
        private set

    private var window: WeakReference<Window>? = null
    private var listening: Window? = null

    fun inLaunchWindow(nowNanos: Long = System.nanoTime()): Boolean =
        nowNanos - processStartNanos < LAUNCH_WINDOW_MS * 1_000_000L

    fun launchMark(label: String, atNanos: Long = System.nanoTime()) {
        if (!inLaunchWindow(atNanos)) return
        synchronized(launchMarks) {
            if (launchMarks.size < LAUNCH_MARKS) launchMarks += TimedMark(atNanos, label)
        }
    }

    /** Main thread, from the activity's onCreate. */
    fun attach(window: Window) {
        this.window = WeakReference(window)
        @Suppress("DEPRECATION")
        refreshHz = window.windowManager.defaultDisplay.refreshRate
        updateListener()
        if (inLaunchWindow()) {
            // Stop listening once launch is over, unless a recording needs it.
            val left = LAUNCH_WINDOW_MS - (System.nanoTime() - processStartNanos) / 1_000_000L
            main.postDelayed(::updateListener, left.coerceAtLeast(0L) + 50L)
        }
    }

    /** Main thread, from the activity's onDestroy. */
    fun detach(window: Window) {
        if (listening === window) {
            window.removeOnFrameMetricsAvailableListener(listener)
            listening = null
        }
        if (this.window?.get() === window) this.window = null
    }

    fun startRecording() {
        recording = FrameRing(RECORDING_FRAMES)
        main.post(::updateListener)
    }

    fun stopRecording(): FrameTimeline? {
        val ring = recording ?: return null
        recording = null
        main.post(::updateListener)
        return ring.snapshot()
    }

    fun launchTimeline(): FrameTimeline = launch.snapshot()

    fun launchEvents(): List<TimedMark> = synchronized(launchMarks) { launchMarks.toList() }

    private fun updateListener() {
        val wanted = window?.get()?.takeIf { recording != null || inLaunchWindow() }
        if (listening === wanted) return
        listening?.removeOnFrameMetricsAvailableListener(listener)
        listening = null
        if (wanted != null) {
            wanted.addOnFrameMetricsAvailableListener(listener, worker)
            listening = wanted
        }
    }

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        val start = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        val rec = recording
        val inLaunch = inLaunchWindow(start)
        if (rec == null && !inLaunch) return@OnFrameMetricsAvailableListener
        fun us(id: Int): Int = (metrics.getMetric(id) / 1_000L).toInt()
        val total = us(FrameMetrics.TOTAL_DURATION)
        val delay = us(FrameMetrics.UNKNOWN_DELAY_DURATION)
        val input = us(FrameMetrics.INPUT_HANDLING_DURATION)
        val animation = us(FrameMetrics.ANIMATION_DURATION)
        val layout = us(FrameMetrics.LAYOUT_MEASURE_DURATION)
        val draw = us(FrameMetrics.DRAW_DURATION)
        val sync = us(FrameMetrics.SYNC_DURATION)
        val command = us(FrameMetrics.COMMAND_ISSUE_DURATION)
        val swap = us(FrameMetrics.SWAP_BUFFERS_DURATION)
        val gpu = if (Build.VERSION.SDK_INT >= 31) us(FrameMetrics.GPU_DURATION) else FrameRing.NOT_REPORTED
        val deadline = if (Build.VERSION.SDK_INT >= 31) us(FrameMetrics.DEADLINE) else FrameRing.NOT_REPORTED
        val first = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
        // A recording that begins inside the launch window feeds both.
        for (ring in listOfNotNull(rec, launch.takeIf { inLaunch })) {
            ring.add(
                start, total, delay, input, animation, layout, draw, sync, command, swap, gpu, deadline, first,
                platformDropped = dropped,
            )
        }
    }
}
