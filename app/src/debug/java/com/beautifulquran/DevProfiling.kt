package com.beautifulquran

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.Debug
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.os.BufferFillPolicy
import androidx.core.os.SystemTraceRequestBuilder
import androidx.core.content.FileProvider
import androidx.core.os.requestProfiling
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Debug-only ProfilingManager surface.
 *
 * Manual and auto cold-start traces use Jetpack [SystemTraceRequestBuilder]
 * (API 35+). On Android 17 (API 37+) the platform cold-start / fully-drawn
 * triggers are also registered; [reportFullyDrawn] ends both the auto trace
 * and the platform COLD_START window.
 */
object DevProfiling {

    private const val Tag = "BeautifulQuranProfile"
    private const val ManualTraceDurationMs = 10_000
    private const val CeremonyTraceDurationMs = 20_000
    private const val TraceBufferKb = 32_768

    /** The tag whose result should be offered to the share sheet. */
    private const val ManualTag = "manual-system-trace"

    /** ART sampling fallback: 8MB of buffer at 1kHz covers ten seconds. */
    private const val MethodTraceBufferBytes = 8 * 1024 * 1024
    private const val MethodTraceIntervalUs = 1_000

    private val methodTraceRunning = AtomicBoolean(false)
    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()
    private val ceremonyStop = AtomicReference<CancellationSignal?>(null)

    /**
     * Application context for the share step. The profiling callback arrives
     * on a pool thread long after the tap, with no activity in hand, so the
     * one thing it needs is kept here rather than plumbed through the request.
     */
    private val appContext = AtomicReference<Context?>(null)

    fun install(application: Application) {
        appContext.set(application)
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
        appContext.compareAndSet(null, context.applicationContext)
        // Ten seconds of recording with nothing on screen reads as a dead
        // button, and the whole point is to use the app while it records.
        toast(context, "Recording ${ManualTraceDurationMs / 1000}s — use the app now")
        if (Build.VERSION.SDK_INT < 35) {
            Log.w(Tag, "SystemTraceRequestBuilder requires API 35+ — sampling instead")
            recordMethodTrace()
            return
        }
        Api35.recordSystemTrace(context.applicationContext, ManualTag, ManualTraceDurationMs)
    }

    /**
     * ART sampling profile, used when the platform will not record a system
     * trace.
     *
     * ProfilingManager needs a working perfetto on the device and answers
     * ERROR_FAILED_EXECUTING where there is none — every emulator tried here.
     * A button that silently does nothing in that case is worse than useless
     * when the whole point is to send a profile back, so the ART sampler
     * stands in: coarser than a system trace (this process only, no frame or
     * scheduling tracks) but it always produces a file, and Android Studio and
     * Perfetto both open a .trace.
     */
    private fun recordMethodTrace() {
        val context = appContext.get() ?: return
        if (!methodTraceRunning.compareAndSet(false, true)) {
            Log.i(Tag, "Method trace already running")
            return
        }
        val file = File(
            File(context.cacheDir, "share").apply { mkdirs() },
            "bq-method-${System.currentTimeMillis()}.trace",
        )
        try {
            Debug.startMethodTracingSampling(
                file.absolutePath,
                MethodTraceBufferBytes,
                MethodTraceIntervalUs,
            )
        } catch (error: RuntimeException) {
            methodTraceRunning.set(false)
            Log.e(Tag, "Unable to start method trace", error)
            toast(context, "Could not start a profile on this device")
            return
        }
        Log.i(Tag, "Sampling method trace -> ${file.absolutePath}")
        Handler(Looper.getMainLooper()).postDelayed(
            {
                try {
                    Debug.stopMethodTracing()
                } catch (error: RuntimeException) {
                    Log.e(Tag, "Unable to stop method trace", error)
                }
                methodTraceRunning.set(false)
                shareFile(file, "method-trace")
            },
            ManualTraceDurationMs.toLong(),
        )
    }

    /**
     * Hands a finished trace to the share sheet.
     *
     * ProfilingManager writes into the app's own storage, where nothing else
     * can read it, so the file is copied under the cache root the manifest's
     * FileProvider publishes and shared from there. Perfetto traces open at
     * ui.perfetto.dev, so the MIME type is left generic rather than claiming
     * a format no receiver knows.
     */
    private fun shareProfile(path: String, tag: String) {
        val context = appContext.get() ?: return
        val source = File(path)
        if (!source.isFile) {
            Log.w(Tag, "Profile file missing: $path")
            return
        }
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val copy = File(shareDir, "$tag-${source.name}")
        try {
            source.copyTo(copy, overwrite = true)
        } catch (error: java.io.IOException) {
            Log.e(Tag, "Unable to stage profile for sharing", error)
            return
        }
        shareFile(copy, tag)
    }

    /** Offers a file that already sits under the shareable cache root. */
    private fun shareFile(file: File, tag: String) {
        val context = appContext.get() ?: return
        if (!file.isFile || file.length() == 0L) {
            Log.w(Tag, "Nothing to share for $tag: ${file.absolutePath}")
            toast(context, "Profile came back empty")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Beautiful Quran profile — $tag")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Send performance profile")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        Log.i(Tag, "Sharing profile ${file.absolutePath} (${file.length()} bytes)")
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    /** Wall-clock milestone for logcat; also emits an instant atrace counter. */
    fun mark(label: String) {
        Log.i(Tag, label)
        Trace.setCounter("BQ:$label", 1)
        Trace.setCounter("BQ:$label", 0)
    }

    inline fun <T> trace(label: String, block: () -> T): T {
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
                if (result.tag == ManualTag) {
                    result.resultFilePath?.let { shareProfile(it, result.tag ?: ManualTag) }
                }
            } else {
                Log.e(
                    Tag,
                    "Profiling failed: code=${result.errorCode}, message=${result.errorMessage}",
                )
                if (result.tag == ManualTag) recordMethodTrace()
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
                if (result.tag == ManualTag) {
                    result.resultFilePath?.let { shareProfile(it, result.tag ?: ManualTag) }
                }
            } else {
                Log.e(
                    Tag,
                    "Profiling failed: code=${result.errorCode}, message=${result.errorMessage}",
                )
                if (result.tag == ManualTag) recordMethodTrace()
            }
        }
    }
}
