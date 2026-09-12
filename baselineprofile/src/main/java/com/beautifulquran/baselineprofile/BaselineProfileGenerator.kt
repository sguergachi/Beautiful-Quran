package com.beautifulquran.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Produces the release Baseline Profile and its startup-only DEX layout subset.
 *
 * Keep the startup rule narrow. Reader navigation and scrolling belong in the
 * general profile so their larger code surface does not crowd startup classes
 * out of the primary DEX.
 *
 * Recitation in [readerAndPaperNavigation] needs the same offline Alafasy
 * fixture as [ReaderBenchmark] — pass [OfflineFixtureArg]. Do not regenerate
 * the committed seed without a physical-device comparison.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PackageName,
        includeInStartupProfile = true,
    ) {
        device.pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    @Test
    fun readerAndPaperNavigation() = rule.collect(
        packageName = PackageName,
        includeInStartupProfile = false,
    ) {
        requireOfflineRecitationFixture()
        startActivityAndWait()
        device.skipCover()

        val chapterList = device.findObject(By.scrollable(true))
        chapterList?.fling(Direction.DOWN)
        chapterList?.fling(Direction.UP)

        device.chooseReaderSettings("Mushaf")
        device.openChapter("Al-Fatihah")
        device.waitForMushafReader()
        device.playUntilStarted()
        device.idleBriefly(1_500)
        device.findObject(By.desc("Pause"))?.click()
        device.idleBriefly()

        val opening = device.mushafPageNumber()
        device.turnMushafTowardLaterPages()
        device.waitUntilMushafPageChanges(opening)
        val beforeDial = device.mushafPageNumber()
        device.swipeMushafDialTowardLaterPages()
        device.waitUntilMushafPageChanges(beforeDial)
        device.returnToMushafPage(beforeDial)

        val reader = device.findObject(By.scrollable(true))
        reader?.fling(Direction.DOWN)
        reader?.fling(Direction.UP)

        device.pressBack()
        device.waitForChapters()
        device.findObject(By.desc("Open settings")).click()
        device.waitForSettings()
    }
}
