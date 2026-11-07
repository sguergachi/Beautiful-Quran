package com.beautifulquran

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.beautifulquran.ui.reader.InkEngine
import com.beautifulquran.data.Settings
import com.beautifulquran.domain.FrameTimeline
import com.beautifulquran.domain.TimedMark
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The developer row stays usable across reader navigation and activity recreation. */
internal data class LogExportState(
    val recording: Boolean = false,
    val preparing: Boolean = false,
    val hasLog: Boolean = false,
    val error: String? = null,
)

/** Explicit, bounded local sync recording; only the system share sheet can send its file. */
internal object LogExport {
    private const val MAX_DURATION_MS = 180_000L
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LogExportState())
    val state = mutableState.asStateFlow()
    private var stopTask: Runnable? = null
    private var metadata: Map<String, String> = emptyMap()
    private var startedAtNanos = 0L
    private var latest: File? = null
    private var settingsWatch: Job? = null

    /** Called on the main thread by the developer row: start, or stop and share. */
    fun toggle(context: Context) {
        if (state.value.preparing) return
        if (state.value.recording) {
            stop(context.applicationContext, "user")
            return
        }
        if (!DevProfiling.startLogCapture()) {
            mutableState.value = state.value.copy(error = "Another recording is already running.")
            return
        }
        val app = context.applicationContext
        startedAtNanos = System.nanoTime()
        metadata = contextMetadata(app).mapKeys { "start ${it.key}" } + linkedMapOf(
            "build" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "source" to "${BuildConfig.CAPTURE_SOURCE_REVISION} dirty=${BuildConfig.CAPTURE_SOURCE_DIRTY}",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}",
            "startedAtEpochMs" to System.currentTimeMillis().toString(),
            "startedAtNanos" to startedAtNanos.toString(),
            "limitMs" to MAX_DURATION_MS.toString(),
            "processAgeAtStartMs" to ((startedAtNanos - FrameLog.processStartNanos) / 1_000_000).toString(),
            "refreshHz" to FrameLog.refreshHz.toString(),
        )
        mutableState.value = LogExportState(recording = true, hasLog = latest != null)
        FrameLog.startRecording()
        DevProfiling.recordEvent("log-start")
        settingsWatch = scope.launch {
            (app as QuranApp).settings.settings.map(::readerSettings).distinctUntilChanged().collect {
                DevProfiling.recordEvent("reader-settings $it")
            }
        }
        stopTask = Runnable { stop(app, "three-minute limit") }.also { main.postDelayed(it, MAX_DURATION_MS) }
    }

    private fun stop(context: Context, reason: String) {
        if (!state.value.recording) return
        stopTask?.let(main::removeCallbacks)
        stopTask = null
        settingsWatch?.cancel()
        settingsWatch = null
        DevProfiling.recordEvent("log-stop reason=$reason")
        val snapshot = DevProfiling.stopLogCapture()
        val recordedFrames = FrameLog.stopRecording()
        val launchFrames = FrameLog.launchTimeline()
        val launchEvents = FrameLog.launchEvents()
        val logStartNanos = startedAtNanos
        val details = metadata + contextMetadata(context).mapKeys { "end ${it.key}" } + linkedMapOf(
            "durationMs" to ((System.nanoTime() - startedAtNanos) / 1_000_000).toString(),
            "stopReason" to reason,
        )
        mutableState.value = state.value.copy(recording = false, preparing = true)
        worker.execute {
            try {
                val directory = File(context.cacheDir, "share").apply { mkdirs() }
                val file = File(directory, "bq-log-${System.currentTimeMillis()}.txt")
                file.writeText(snapshot.report(details, performance(recordedFrames, launchFrames, launchEvents, snapshot.marks, logStartNanos)))
                directory.listFiles().orEmpty()
                    .filter { it.name.startsWith("bq-log-") && it.extension == "txt" }
                    .sortedByDescending { it.lastModified() }.drop(3).forEach { it.delete() }
                main.post {
                    latest = file
                    mutableState.value = LogExportState(hasLog = true)
                    shareLatest(context)
                }
            } catch (error: Exception) {
                Log.e("BeautifulQuranLog", "Could not write log export", error)
                main.post {
                    mutableState.value = LogExportState(
                        hasLog = latest != null, error = "Couldn’t prepare the log. Try recording again.",
                    )
                }
            }
        }
    }

    /**
     * Frame timings: the recording window, then the process's launch window
     * (its first minute), which holds a cold launch and first chapter open
     * even when Log export was only pressed afterwards.
     */
    private fun performance(
        recorded: FrameTimeline?,
        launch: FrameTimeline,
        launchEvents: List<TimedMark>,
        recordedMarks: List<String>,
        logStartNanos: Long,
    ): List<String> {
        val hz = FrameLog.refreshHz
        val lines = ArrayList<String>()
        if (recorded != null) {
            lines += recorded.report(
                title = "during this log (s since log start)",
                originNanos = logStartNanos,
                refreshHz = hz,
                marks = recordedMarks.mapNotNull(FrameTimeline::parseMark),
            )
            lines += ""
        }
        lines += launch.report(
            title = "launch: the first ${FrameLog.LAUNCH_WINDOW_MS / 1000} s of this process " +
                "(s since process start; log started at +${"%.3f".format(java.util.Locale.ROOT, (logStartNanos - FrameLog.processStartNanos) / 1e9)}s)",
            originNanos = FrameLog.processStartNanos,
            refreshHz = hz,
            marks = launchEvents,
        )
        return lines
    }

    /** Keep the last export available if the chooser was dismissed or a receiver failed. */
    fun shareLatest(context: Context) {
        val file = latest?.takeIf { it.isFile && it.length() > 0L }
        if (file == null) {
            mutableState.value = state.value.copy(hasLog = false, error = "Log expired. Record a new log.")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Beautiful Quran log export")
                clipData = ClipData.newRawUri("log export", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share log export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            mutableState.value = state.value.copy(error = null)
        } catch (error: RuntimeException) {
            Log.e("BeautifulQuranLog", "Could not share saved log", error)
            mutableState.value = state.value.copy(error = "Log saved. Try sharing again.")
        }
    }

    /** Selected reader and presentation settings only; no bookmarks, notes or credentials. */
    private fun contextMetadata(context: Context): Map<String, String> {
        val app = context as QuranApp
        val settings = app.settings.settings.value
        val playback = app.player.state.value
        val route = app.outputRoutes.deviceId.value
        return linkedMapOf(
            "reader" to readerSettings(settings),
            "playback" to "item=${playback.nowPlaying} selectedReciter=${settings.reciterId} " +
                "playing=${playback.isPlaying} buffering=${playback.isBuffering} speed=${playback.speed} " +
                "positionIntent=${playback.positionIntentId} revisions=${playback.positionEvents}",
            "output" to "deviceId=$route type=${routeType(context, route)}",
            "ink" to "lagWallMs=${InkEngine.outputLatencyOverrideMs} leadMs=${InkEngine.highlightLeadMs} " +
                "scrollLeadMs=${InkEngine.scrollLeadMs} fadeLeadMs=${InkEngine.fadeLeadMs} ${InkEngine.tuning}",
            "systemFontScale" to context.resources.configuration.fontScale.toString(),
            "viewport" to "${context.resources.configuration.screenWidthDp}x${context.resources.configuration.screenHeightDp}dp " +
                "densityDpi=${context.resources.displayMetrics.densityDpi}",
        )
    }

    private fun readerSettings(settings: Settings): String =
        "mode=${settings.readingMode} layout=${settings.readingLayout} fontScale=${settings.fontScale} " +
            "theme=${settings.themeMode} english=${settings.englishLeafText} reciter=${settings.reciterId} " +
            "typeface=${settings.activeBookTypeface} arabicFace=${settings.activeScrollArabicFace}"

    /** Device type only; a Bluetooth device's personal name is not included. */
    fun routeType(context: Context, deviceId: Int?): Int? = context.getSystemService(AudioManager::class.java)
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { it.id == deviceId }?.type
}
