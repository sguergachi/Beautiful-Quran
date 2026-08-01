package com.beautifulquran

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import android.os.Trace
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.os.BufferFillPolicy
import androidx.core.os.SystemTraceRequestBuilder
import androidx.core.os.requestProfiling
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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

    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()
    private val ceremonyStop = AtomicReference<CancellationSignal?>(null)

    fun install(application: Application) {
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
        if (Build.VERSION.SDK_INT < 35) {
            Log.w(Tag, "SystemTraceRequestBuilder requires API 35+")
            return
        }
        Api35.recordSystemTrace(context.applicationContext, "manual-system-trace", ManualTraceDurationMs)
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
                requestProfiling(
                    context,
                    request,
                    callbackExecutor,
                    resultListener,
                )
            } catch (error: RuntimeException) {
                Log.e(Tag, "Unable to start system trace ($tag)", error)
                if (ceremonyStop.get() === stopSignal) ceremonyStop.set(null)
                return
            }
            Log.i(Tag, "Recording ${durationMs}ms system trace tag=$tag")
        }
    }

    @RequiresApi(37)
    private object Api37 {
        fun install(application: Application) {
            val manager = application.getSystemService(android.os.ProfilingManager::class.java)
            manager.registerForAllProfilingResults(callbackExecutor, resultListener)
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
    }

    private val resultListener = Consumer<ProfilingResult> { result ->
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
