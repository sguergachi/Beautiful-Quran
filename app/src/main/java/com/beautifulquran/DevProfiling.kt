package com.beautifulquran

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Debug
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.os.Trace
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.os.BufferFillPolicy
import androidx.core.os.SystemTraceRequestBuilder
import androidx.core.content.FileProvider
import androidx.core.os.requestProfiling
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Local performance capture.
 *
 * Two halves with different reach:
 *
 * - **By hand, in every build.** Developer → "Record performance profile"
 *   ([recordSystemTrace]) watches ten seconds of use and hands the result to
 *   the share sheet: a text report (frame times, main-thread stack samples,
 *   named marks) and, where the platform will record one, a system trace.
 *   Nothing runs until that row is tapped and nothing leaves the phone unless
 *   the share sheet sends it.
 * - **On its own, debug builds only.** The cold-start ceremony trace and the
 *   Android 17 cold-start / fully-drawn triggers ([install]). A release build
 *   registers none of this.
 *
 * Manual and auto traces use Jetpack [SystemTraceRequestBuilder] (API 35+).
 */
object DevProfiling {

    private const val Tag = "BeautifulQuranProfile"
    private const val ManualTraceDurationMs = 10_000
    private const val CeremonyTraceDurationMs = 20_000
    private const val TraceBufferKb = 32_768

    /** The tag whose result belongs to the capture being shared. */
    private const val ManualTag = "manual-system-trace"

    /** ART sampling fallback: 8MB of buffer at 1kHz covers ten seconds. */
    private const val MethodTraceBufferBytes = 8 * 1024 * 1024
    private const val MethodTraceIntervalUs = 1_000

    /** How long the share waits on the platform to hand the trace back. */
    private const val TraceWaitSeconds = 30L

    /** Main-thread stack sampling period, and how much of each stack is kept. */
    private const val StackSampleMs = 5L
    private const val StackDepth = 14

    private val methodTraceRunning = AtomicBoolean(false)
    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()
    private val ceremonyStop = AtomicReference<CancellationSignal?>(null)
    private val capture = AtomicReference<Capture?>(null)

    /**
     * One ten-second window. The text half is ready the moment the window
     * closes; the trace arrives from the platform some seconds later, so the
     * share waits on [traceDone] (bounded) and then sends whatever exists.
     */
    private class Capture(val dir: File, val stamp: Long) {
        val tag = "$ManualTag-$stamp"
        val frames = FrameWatch()
        val stacks = StackSampler(Looper.getMainLooper().thread)
        val traceDone = CountDownLatch(1)
        @Volatile var traceFile: File? = null
        @Volatile var traceNote: String = "not requested"
    }

    /**
     * Named marks collected during a capture window: every path that can
     * blank or fade the screen emits one, and they are written into the
     * report - the mark sequence around a reported flash reads as a sentence
     * naming the path that caused it.
     */
    private val markLock = Any()
    private val markLines = ArrayList<String>(256)
    @PublishedApi
    internal val captureStart = AtomicReference<Long?>(null)

    private fun recordMark(label: String) {
        val at = System.currentTimeMillis()
        synchronized(markLock) {
            val start = captureStart.get()
            if (start != null) {
                markLines += String.format("%+.3fs  %s", (at - start) / 1000.0, label)
                if (markLines.size > 2000) markLines.removeAt(0)
            }
        }
    }

    private fun startMarkCapture() {
        synchronized(markLock) {
            markLines.clear()
            captureStart.set(System.currentTimeMillis())
        }
    }

    private fun stopMarkCapture(): List<String> {
        captureStart.set(null)
        return synchronized(markLock) { markLines.toList() }
    }

    /**
     * Application context for the share step. The profiling callback arrives
     * on a pool thread long after the tap, with no activity in hand, so the
     * one thing it needs is kept here rather than plumbed through the request.
     */
    private val appContext = AtomicReference<Context?>(null)

    fun install(application: Application) {
        appContext.set(application)
        // Everything below records without being asked. Debug builds only.
        if (!BuildConfig.DEBUG) return
        when {
            Build.VERSION.SDK_INT >= 37 -> {
                Api37.install(application)
                Api35.startCeremonyTrace(application)
            }
            Build.VERSION.SDK_INT >= 35 -> {
                Log.i(Tag, "ProfilingManager SystemTraceRequestBuilder available (API 35+)")
                Api35.startCeremonyTrace(application)
            }
        }
    }

    fun reportFullyDrawn(activity: Activity) {
        mark("reportFullyDrawn")
        ceremonyStop.getAndSet(null)?.cancel()
        activity.reportFullyDrawn()
    }

    fun recordSystemTrace(context: Context) {
        val app = context.applicationContext
        appContext.compareAndSet(null, app)
        val dir = File(app.cacheDir, "share").apply { mkdirs() }
        val window = Capture(dir, System.currentTimeMillis())
        if (!capture.compareAndSet(null, window)) {
            toast(context, "A performance profile is already recording")
            return
        }
        // Ten seconds of recording with nothing on screen reads as a dead
        // button, and the whole point is to use the app while it records.
        toast(context, "Recording ${ManualTraceDurationMs / 1000}s — use the app now")
        startMarkCapture()
        window.frames.start()
        Thread(window.stacks, "BQStackWatch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
        if (Build.VERSION.SDK_INT >= 35) {
            window.traceNote = "requested"
            Api35.recordSystemTrace(app, window.tag, ManualTraceDurationMs)
        } else {
            Log.w(Tag, "SystemTraceRequestBuilder requires API 35+ — sampling instead")
            recordMethodTrace(window)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            window.frames.stop()
            window.stacks.stop()
            val marks = stopMarkCapture()
            // Off the main thread: it writes files and may wait on the trace.
            Thread({ finishCapture(app, window, marks) }, "BQProfileShare").start()
        }, ManualTraceDurationMs.toLong())
    }

    private fun finishCapture(context: Context, window: Capture, marks: List<String>) {
        val files = ArrayList<File>(2)
        if (!window.traceDone.await(TraceWaitSeconds, TimeUnit.SECONDS)) {
            window.traceNote = "timed out; sharing the report alone"
            Log.w(Tag, "Trace did not come back in ${TraceWaitSeconds}s")
        }
        try {
            val report = File(window.dir, "bq-profile-${window.stamp}.txt")
            report.writeText(buildReport(context, window, marks))
            files += report
        } catch (error: java.io.IOException) {
            Log.e(Tag, "Unable to write the profile report", error)
        }
        window.traceFile?.takeIf { it.isFile && it.length() > 0L }?.let { files += it }
        capture.set(null)
        shareFiles(context, files)
    }

    private fun buildReport(context: Context, window: Capture, marks: List<String>): String =
        buildString {
            append("Beautiful Quran performance profile\n")
            append("build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ")
            append(if (BuildConfig.DEBUG) "debug" else "release")
            append('\n')
            append("device ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}\n")
            @Suppress("DEPRECATION")
            val refresh = context.getSystemService(android.view.WindowManager::class.java)
                ?.defaultDisplay?.refreshRate
            append("display refresh ${refresh?.let { String.format("%.0f", it) } ?: "?"} Hz\n")
            append("system trace: ${window.traceNote}\n")
            append("\n== frame callbacks (interval ms @ ms since start) ==\n")
            append(window.frames.summary())
            append("\n\n== marks ==\n")
            append(marks.size).append(" marks\n")
            marks.forEach { append(it).append('\n') }
            append("\n== main thread, sampled every ${StackSampleMs}ms (idle samples dropped) ==\n")
            append(window.stacks.summary())
        }

    /**
     * Samples the main thread's stack during the capture window. Whatever
     * blocks a swipe appears as a run of identical stacks at the hitch's
     * timestamp — the one thing a sampling profile of methods cannot name.
     * A release build's names are obfuscated; retrace them with that build's
     * `mapping.txt`.
     */
    private class StackSampler(private val mainThread: Thread) : Runnable {
        @Volatile private var running = true
        private val samples = ArrayList<String>(2400)
        private val startNanos = System.nanoTime()
        private var idle = 0

        override fun run() {
            while (running) {
                try {
                    val at = (System.nanoTime() - startNanos) / 1_000_000L
                    val stack = mainThread.stackTrace
                    // Parked in the message queue: the main thread has nothing
                    // to do, which is the healthy case and most of the samples.
                    if (stack.firstOrNull()?.methodName == "nativePollOnce") {
                        idle++
                    } else {
                        val frames = stack.take(StackDepth).joinToString(" <- ") { frame ->
                            frame.className.substringAfterLast('.') + "." + frame.methodName
                        }
                        synchronized(samples) { samples += "$at ms: $frames" }
                    }
                } catch (_: RuntimeException) {
                }
                Thread.sleep(StackSampleMs)
            }
        }

        fun stop() {
            running = false
        }

        fun summary(): String {
            val busy = synchronized(samples) { samples.toList() }
            return "${busy.size} busy samples, $idle idle\n" + busy.joinToString("\n")
        }
    }

    /**
     * Frame-time watch over the capture window: the sampling trace shows
     * where CPU went but not *when frames dropped*, and the reported lag is
     * a hitch at the start of a swipe — exactly what a per-frame delta log
     * catches that a sampler cannot.
     */
    private class FrameWatch : Choreographer.FrameCallback {
        private val choreographer = Choreographer.getInstance()
        private val deltasMicros = LongArray(2400)
        private val offsetsMicros = LongArray(deltasMicros.size)
        private var lastNanos = 0L
        private var startNanos = 0L
        private var worstMicros = 0L
        private var counted = 0

        fun start() {
            startNanos = System.nanoTime()
            choreographer.postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (lastNanos != 0L) {
                val deltaMicros = (frameTimeNanos - lastNanos) / 1_000L
                if (counted < deltasMicros.size) {
                    deltasMicros[counted] = deltaMicros
                    offsetsMicros[counted] = (frameTimeNanos - startNanos) / 1_000L
                    counted++
                }
                if (deltaMicros > worstMicros) worstMicros = deltaMicros
            }
            lastNanos = frameTimeNanos
            choreographer.postFrameCallback(this)
        }

        fun stop() {
            choreographer.removeFrameCallback(this)
        }

        fun summary(): String = buildString {
            val sorted = deltasMicros.copyOf(counted).apply { sort() }
            val median = if (counted > 0) sorted[counted / 2] else 0L
            // Against the display's own cadence rather than a fixed 16.7ms: a
            // 120Hz panel drops a frame at 16ms and a 60Hz one does not.
            val late = (0 until counted).count { deltasMicros[it] > median * 3 / 2 }
            append("frames=").append(counted)
            append(" medianMs=").append(String.format("%.1f", median / 1000.0))
            append(" late=").append(late)
            append(" worstMs=").append(String.format("%.1f", worstMicros / 1000.0))
            append('\n')
            repeat(counted) { index ->
                if (index > 0) append(' ')
                append(
                    String.format(
                        "%.1f@%.0f",
                        deltasMicros[index] / 1000.0,
                        offsetsMicros[index] / 1000.0,
                    ),
                )
            }
        }
    }

    /**
     * ART sampling profile, used when the platform will not record a system
     * trace.
     *
     * On API 30–34, ART sampling supplies a process-local trace alongside
     * the text report. API 35+ requests the system trace instead; platform
     * errors are recorded in the report and do not prevent sharing it.
     */
    private fun recordMethodTrace(window: Capture) {
        if (!methodTraceRunning.compareAndSet(false, true)) {
            Log.i(Tag, "Method trace already running")
            window.traceDone.countDown()
            return
        }
        val file = File(window.dir, "bq-method-${window.stamp}.trace")
        try {
            Debug.startMethodTracingSampling(
                file.absolutePath,
                MethodTraceBufferBytes,
                MethodTraceIntervalUs,
            )
        } catch (error: RuntimeException) {
            methodTraceRunning.set(false)
            Log.e(Tag, "Unable to start method trace", error)
            window.traceNote = "method trace failed to start: ${error.message}"
            window.traceDone.countDown()
            return
        }
        window.traceNote = "ART method sampling (no system trace on this device)"
        Log.i(Tag, "Sampling method trace -> ${file.absolutePath}")
        Handler(Looper.getMainLooper()).postDelayed(
            {
                try {
                    Debug.stopMethodTracing()
                } catch (error: RuntimeException) {
                    Log.e(Tag, "Unable to stop method trace", error)
                }
                methodTraceRunning.set(false)
                window.traceFile = file
                window.traceDone.countDown()
            },
            ManualTraceDurationMs.toLong(),
        )
    }

    /**
     * A finished system trace for the open capture.
     *
     * ProfilingManager writes into the app's own storage, where nothing else
     * can read it, so the file is copied under the cache root the manifest's
     * FileProvider publishes and shared from there.
     */
    private fun onManualTrace(path: String?) {
        val window = capture.get() ?: return
        val source = path?.let(::File)
        if (source == null || !source.isFile) {
            Log.w(Tag, "Profile file missing: $path")
            window.traceNote = "finished, but the file was missing"
        } else {
            val copy = File(window.dir, "bq-trace-${window.stamp}.perfetto-trace")
            try {
                source.copyTo(copy, overwrite = true)
                window.traceFile = copy
                window.traceNote = "attached (${copy.name})"
            } catch (error: java.io.IOException) {
                Log.e(Tag, "Unable to stage profile for sharing", error)
                window.traceNote = "finished, but could not be copied: ${error.message}"
            }
        }
        window.traceDone.countDown()
    }

    private fun onManualTraceFailed(reason: String) {
        val window = capture.get() ?: return
        window.traceNote = "failed: $reason"
        window.traceDone.countDown()
    }

    /**
     * Hands the capture to the share sheet as one send. Perfetto traces open
     * at ui.perfetto.dev, so the MIME type is left generic rather than
     * claiming a format no receiver knows.
     */
    private fun shareFiles(context: Context, files: List<File>) {
        val ready = files.filter { it.isFile && it.length() > 0L }
        if (ready.isEmpty()) {
            Log.w(Tag, "Nothing to share")
            toast(context, "Profile came back empty")
            return
        }
        val uris = ArrayList<Uri>(
            ready.map { FileProvider.getUriForFile(context, "${context.packageName}.share", it) },
        )
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "Beautiful Quran performance profile")
            // The chooser grants read access from the clip, not from the extra.
            clipData = ClipData.newRawUri("profile", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Send performance profile")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        Log.i(Tag, "Sharing ${ready.joinToString { "${it.name} (${it.length()} bytes)" }}")
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Wall-clock milestone for logcat, the system trace, and an open
     * capture's report. A release build with no capture open does nothing:
     * it logs and records only once the developer has asked it to.
     */
    fun mark(label: String) {
        if (!BuildConfig.DEBUG && captureStart.get() == null) return
        Log.i(Tag, label)
        recordMark(label)
        Trace.setCounter("BQ:$label", 1)
        Trace.setCounter("BQ:$label", 0)
    }

    inline fun <T> trace(label: String, block: () -> T): T {
        if (!BuildConfig.DEBUG && captureStart.get() == null) return block()
        Trace.beginSection(label)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    @RequiresApi(35)
    private object Api35 {
        fun startCeremonyTrace(context: Context) {
            val stop = CancellationSignal()
            if (!ceremonyStop.compareAndSet(null, stop)) {
                stop.cancel()
                return
            }
            recordSystemTrace(context, "cold-start-ceremony", CeremonyTraceDurationMs, stop)
            Log.i(Tag, "Auto ceremony system trace started (stops at reportFullyDrawn)")
        }

        fun recordSystemTrace(
            context: Context,
            tag: String,
            durationMs: Int,
            stopSignal: CancellationSignal = CancellationSignal(),
        ) {
            val request = SystemTraceRequestBuilder()
                .setCancellationSignal(stopSignal)
                .setTag(tag)
                .setDurationMs(durationMs)
                .setBufferFillPolicy(BufferFillPolicy.RING_BUFFER)
                .setBufferSizeKb(TraceBufferKb)
                .build()
            try {
                requestProfiling(context, request, callbackExecutor, listener)
            } catch (error: RuntimeException) {
                Log.e(Tag, "Unable to start system trace ($tag)", error)
                if (ceremonyStop.get() === stopSignal) ceremonyStop.set(null)
                if (tag == capture.get()?.tag) onManualTraceFailed("could not start: ${error.message}")
                return
            }
            Log.i(Tag, "Recording ${durationMs}ms system trace tag=$tag")
        }

        /** API 35's [ProfilingResult] has no trigger type — the shared listener
         * must not reference it or the result callback kills the app. */
        private val listener = Consumer<ProfilingResult> { result ->
            if (result.errorCode == ProfilingResult.ERROR_NONE) {
                Log.i(Tag, "Profile ready: tag=${result.tag}, file=${result.resultFilePath}")
                // Only what the developer asked for by hand: the cold-start
                // ceremony fires on every launch and a share sheet on every
                // launch would be unusable.
                if (result.tag == capture.get()?.tag) onManualTrace(result.resultFilePath)
            } else {
                Log.e(
                    Tag,
                    "Profiling failed: code=${result.errorCode}, message=${result.errorMessage}",
                )
                if (result.tag == capture.get()?.tag) {
                    onManualTraceFailed("code ${result.errorCode} ${result.errorMessage.orEmpty()}")
                }
            }
        }
    }

    @RequiresApi(37)
    private object Api37 {
        fun install(application: Application) {
            val manager = application.getSystemService(android.os.ProfilingManager::class.java)
            manager.registerForAllProfilingResults(callbackExecutor, listener)
            manager.addProfilingTriggers(
                listOf(
                    ProfilingTrigger.TRIGGER_TYPE_COLD_START,
                    ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN,
                ).map { type ->
                    ProfilingTrigger.Builder(type)
                        .setRateLimitingPeriodHours(1)
                        .build()
                },
            )
            Log.i(Tag, "Android 17 ProfilingManager triggers registered (cold start + fully drawn)")
        }

        /** API 37 adds [ProfilingResult.triggerType]; safe to log here. */
        private val listener = Consumer<ProfilingResult> { result ->
            if (result.errorCode == ProfilingResult.ERROR_NONE) {
                Log.i(
                    Tag,
                    "Profile ready: type=${result.triggerType}, tag=${result.tag}, " +
                        "file=${result.resultFilePath}",
                )
            } else {
                Log.e(
                    Tag,
                    "Profiling failed: code=${result.errorCode}, message=${result.errorMessage}",
                )
            }
        }
    }
}
