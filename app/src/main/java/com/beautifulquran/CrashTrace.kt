package com.beautifulquran

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The last crash, kept on disk so a report can carry its stack trace.
 *
 * A crash that restarts the app leaves nothing to look at: the reader is back
 * on the cover and the list is at its top, which reads as a reset rather than
 * a failure. This records what actually killed the process, once, and hands it
 * to the same share sheet the developer log export uses.
 */
internal object CrashTrace {
    private const val TAG = "BeautifulQuranCrash"

    private fun stored(context: Context) = File(File(context.filesDir, "crash"), "last-crash.txt")

    /** Installed first in [QuranApp.onCreate], so nothing that runs after it can escape. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                stored(app).apply { parentFile?.mkdirs() }.writeText(
                    "crash ${java.util.Date()} ${BuildConfig.VERSION_NAME} " +
                        "(${BuildConfig.VERSION_CODE}) ${BuildConfig.CAPTURE_SOURCE_REVISION} " +
                        "dirty=${BuildConfig.CAPTURE_SOURCE_DIRTY} " +
                        "thread=${thread.name} ${Build.MANUFACTURER} ${Build.MODEL} " +
                        "API ${Build.VERSION.SDK_INT}\n$trace",
                )
            }.onFailure { Log.e(TAG, "could not record the crash", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun hasTrace(context: Context): Boolean =
        stored(context).let { it.isFile && it.length() > 0L }

    /** Copies the trace beside the log exports, the one place the share provider serves. */
    fun share(context: Context) {
        val source = stored(context)
        if (!source.isFile) return
        try {
            val directory = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(directory, "bq-crash.txt").apply { writeText(source.readText()) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Beautiful Quran crash")
                clipData = ClipData.newRawUri("crash", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "Share crash").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "could not share the crash", error)
        }
    }
}
