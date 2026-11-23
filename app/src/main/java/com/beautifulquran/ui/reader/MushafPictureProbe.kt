package com.beautifulquran.ui.reader

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import com.beautifulquran.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Makes the resting Madinah leaf draw from its turn picture, so
 * MushafPictureFidelityTest can compare the picture with the live leaf it
 * stands in for. Only the benchmark build carries it
 * ([BuildConfig.MUSHAF_PICTURE_PROBE]); the test drives it by creating a flag
 * file in the app's external files directory, `picture-probe` for a picture
 * rasterised as the window is, `picture-probe-p3` for one rasterised in
 * Display P3 whatever the window. It answers in `picture-probe-shown` with
 * how many times a forced leaf has drawn its picture, so the test knows the
 * screenshot it compares really is one.
 */
internal object MushafPictureProbe {
    const val Off = 0
    const val Window = 1
    const val P3 = 2

    val mode = mutableIntStateOf(Off)

    /** Picture draws while forced; UI thread only. */
    private var shown = 0

    /** Called by a leaf drawing its picture. */
    fun drewPicture() {
        if (forcesPicture) shown++
    }

    /** Whether a leaf carrying live ink is pictured anyway. */
    val forcesPicture: Boolean get() = BuildConfig.MUSHAF_PICTURE_PROBE && mode.intValue != Off

    /** Whether pictures are rasterised in P3 whatever the window. */
    val forcesP3: Boolean get() = BuildConfig.MUSHAF_PICTURE_PROBE && mode.intValue == P3

    @Composable
    fun Watch() {
        if (!BuildConfig.MUSHAF_PICTURE_PROBE) return
        val context = LocalContext.current
        LaunchedEffect(context) {
            var reported = -1
            while (true) {
                val count = shown
                val next = withContext(Dispatchers.IO) {
                    if (count != reported) report(context, count)
                    read(context)
                }
                reported = count
                if (next != mode.intValue) mode.intValue = next
                delay(PollMs)
            }
        }
    }

    private fun read(context: Context): Int {
        val dir = context.getExternalFilesDir(null) ?: return Off
        return when {
            File(dir, "picture-probe-p3").exists() -> P3
            File(dir, "picture-probe").exists() -> Window
            else -> Off
        }
    }

    private fun report(context: Context, count: Int) {
        val dir = context.getExternalFilesDir(null) ?: return
        File(dir, "picture-probe-shown").writeText(count.toString())
    }

    private const val PollMs = 250L
}
