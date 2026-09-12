package com.beautifulquran.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device reader journeys. No emulator number is release evidence.
 * Recitation needs [OfflineFixtureArg] plus a pre-cached Alafasy fixture;
 * network must be switched off before running. Physical runs are still pending.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ReaderBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun longAyahRecitation() = rule.measureRepeated(
        packageName = PackageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            requireOfflineRecitationFixture()
            launchPastCover()
            device.chooseReaderSettings("Scroll")
            device.search("2:282")
            device.openChapter("Al-Baqarah")
            device.waitForScrollReader()
            device.playUntilStarted()
        },
    ) {
        check(device.hasObject(By.desc("Pause"))) {
            "Setup must leave recitation playing before frame capture"
        }
        device.idleBriefly(2_000)
    }

    @Test
    fun mushafPageTurn() = rule.measureRepeated(
        packageName = PackageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            launchPastCover()
            device.chooseReaderSettings("Mushaf")
            device.openChapter("Al-Fatihah")
            device.waitForMushafReader()
        },
    ) {
        val from = device.mushafPageNumber()
        device.turnMushafTowardLaterPages()
        val to = device.waitUntilMushafPageChanges(from)
        logBenchmark("mushafPageTurn $from -> $to")
    }

    @Test
    fun distantDialJump() = rule.measureRepeated(
        packageName = PackageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            launchPastCover()
            device.chooseReaderSettings("Mushaf")
            device.openChapter("Al-Fatihah")
            device.waitForMushafReader()
        },
    ) {
        val from = device.mushafPageNumber()
        device.swipeMushafDialTowardLaterPages()
        val to = device.waitUntilMushafPageChanges(from)
        check(kotlin.math.abs(to - from) >= 10) { "Dial only moved $from -> $to; expected a distant jump" }
        logBenchmark("distantDialJump $from -> $to")
    }

    @Test
    fun coldSearch() = rule.measureRepeated(
        packageName = PackageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            launchPastCover()
            device.chooseReaderSettings("Scroll")
            // Drop the process-lifetime word index, then reopen past the cover
            // so measured frames are search, not the entrance ceremony.
            killProcess()
            pressHome()
            startActivityAndWait()
            device.skipCover()
            device.search("")
            device.waitForIdle()
        },
    ) {
        val field = checkNotNull(device.findObject(By.desc("Search")))
        val started = SystemClock.elapsedRealtime()
        field.text = "peace"
        device.waitForSearchResults()
        logBenchmark(
            "coldSearch query-to-first-results ${SystemClock.elapsedRealtime() - started}ms " +
                "(auxiliary log, not StartupTimingMetric)",
        )
    }
}
