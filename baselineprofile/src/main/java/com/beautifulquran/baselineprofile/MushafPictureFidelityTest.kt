package com.beautifulquran.baselineprofile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * A Madinah leaf's turn picture against the live leaf it stands in for.
 *
 * Turns slide pictures rasterised in software (MushafTurnSnapshot.kt), and
 * every leaf is shown live again when it comes to rest, so the two must be the
 * same page: a picture that differs is seen to change as the leaf lands. The
 * benchmark build's probe (MushafPictureProbe) makes the resting leaf draw
 * from its picture; each leaf is screenshotted live, from its picture as the
 * window rasterises it, and from one rasterised in Display P3, and the
 * pictures must match the live leaf to within anti-aliasing rounding.
 *
 * Page 2 carries the title band and basmalah, which are drawn live over the
 * picture; page 3 is an ordinary leaf of words.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MushafPictureFidelityTest {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun turnPicturesMatchTheLiveLeaf() = rule.measureRepeated(
        packageName = PackageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 1,
        setupBlock = {
            launchPastCover()
            device.chooseReaderSettings("Mushaf")
            device.openChapter("Al-Baqarah")
            device.waitForMushafReader()
            device.waitForMushafLeafAtRest(device.mushafPageNumber())
        },
    ) {
        val out = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val failures = mutableListOf<String>()
        repeat(2) { leaf ->
            val page = device.mushafPageNumber()
            device.clearPictureProbe()
            val live = device.shot(File(out, "p${page}_live.png"))
            for (flag in listOf("picture-probe", "picture-probe-p3")) {
                val before = device.picturesShown()
                device.setPictureProbe(flag)
                val pictured = device.shot(File(out, "p${page}_$flag.png"))
                val drawn = device.picturesShown() > before
                device.clearPictureProbe()
                if (!drawn) {
                    failures += "page $page $flag: the leaf never drew its picture"
                    continue
                }
                val diff = maxChannelDiff(live, pictured)
                if (diff > MaxDiff) failures += "page $page $flag: differs by $diff/255"
            }
            if (leaf == 0) {
                device.turnMushafTowardLaterPages()
                device.waitUntilMushafPageChanges(page)
                device.waitForMushafLeafAtRest(page + 1)
            }
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    private fun UiDevice.setPictureProbe(flag: String) {
        executeShellCommand("touch $ProbeDir/$flag")
    }

    /** How many times a forced leaf has drawn its picture, as the app last reported. */
    private fun UiDevice.picturesShown(): Int =
        executeShellCommand("cat $ProbeDir/picture-probe-shown").trim().toIntOrNull() ?: 0

    private fun UiDevice.clearPictureProbe() {
        executeShellCommand("rm -f $ProbeDir/picture-probe $ProbeDir/picture-probe-p3")
    }

    /** Lets the probe's poll, a picture's capture and its raster land. */
    private fun UiDevice.shot(file: File): Bitmap {
        idleBriefly(SettleMs)
        check(takeScreenshot(file)) { "Screenshot $file failed" }
        return checkNotNull(BitmapFactory.decodeFile(file.path)) { "Unreadable $file" }
    }

    /** The largest per-channel difference below the status bar, whose clock moves. */
    private fun maxChannelDiff(a: Bitmap, b: Bitmap): Int {
        check(a.width == b.width && a.height == b.height) { "Screenshots differ in size" }
        val top = (a.height * StatusBarShare).toInt()
        val rowA = IntArray(a.width)
        val rowB = IntArray(a.width)
        var worst = 0
        for (y in top until a.height) {
            a.getPixels(rowA, 0, a.width, 0, y, a.width, 1)
            b.getPixels(rowB, 0, b.width, 0, y, b.width, 1)
            for (x in 0 until a.width) {
                val p = rowA[x]
                val q = rowB[x]
                if (p == q) continue
                worst = maxOf(
                    worst,
                    abs((p shr 16 and 0xff) - (q shr 16 and 0xff)),
                    abs((p shr 8 and 0xff) - (q shr 8 and 0xff)),
                    abs((p and 0xff) - (q and 0xff)),
                )
            }
        }
        return worst
    }

    private companion object {
        const val ProbeDir = "/sdcard/Android/data/$PackageName/files"

        /** Anti-aliasing rounding: measured at 1/255 (sRGB) and 2/255 (P3). */
        const val MaxDiff = 3
        const val SettleMs = 2_500L
        const val StatusBarShare = 0.05f
    }
}
