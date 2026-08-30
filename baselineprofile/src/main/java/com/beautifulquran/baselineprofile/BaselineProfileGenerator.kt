package com.beautifulquran.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal const val PackageName = "com.beautifulquran"
private const val UiTimeoutMs = 15_000L

/**
 * Produces the release Baseline Profile and its startup-only DEX layout subset.
 *
 * Keep the startup rule narrow. Reader navigation and scrolling belong in the
 * general profile so their larger code surface does not crowd startup classes
 * out of the primary DEX.
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
        device.pressHome()
        startActivityAndWait()

        // The cover is intentionally skippable. Entering immediately keeps
        // profile generation deterministic while still profiling the cover in
        // the dedicated startup journey above.
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        check(device.wait(Until.hasObject(By.text("Al-Fatihah")), UiTimeoutMs)) {
            "Chapter list did not become ready"
        }

        val chapterList = device.findObject(By.scrollable(true))
        chapterList?.fling(Direction.DOWN)
        chapterList?.fling(Direction.UP)

        device.findObject(By.text("Al-Fatihah")).click()
        // Default reading layout is the mushaf pager, not the scroll sheet.
        check(
            device.wait(Until.hasObject(By.desc("Chapters")), UiTimeoutMs) ||
                device.wait(Until.hasObject(By.text("The Opening")), UiTimeoutMs),
        ) {
            "Reader did not become ready"
        }
        SystemClock.sleep(500)

        val midX = device.displayWidth / 2
        val midY = device.displayHeight / 2
        // RTL reverse pager: a leftward swipe turns toward later pages.
        device.swipe(midX + 400, midY, midX - 400, midY, 40)
        device.waitForIdle()
        device.swipe(midX - 400, midY, midX + 400, midY, 40)
        device.waitForIdle()

        // Exercise the reader's other primary navigation path too. Derive the
        // hairline from the transport's measured control rather than assuming
        // one device height or density, then use its return roundel once.
        val playBounds = checkNotNull(device.findObject(By.desc("Play"))).visibleBounds
        val dialY = playBounds.top - playBounds.height() * 3 / 2
        device.swipe(device.displayWidth * 3 / 4, dialY, device.displayWidth / 4, dialY, 30)
        device.waitForIdle()
        device.click(device.displayWidth - playBounds.height(), dialY)
        device.waitForIdle()

        val reader = device.findObject(By.scrollable(true))
        reader?.fling(Direction.DOWN)
        reader?.fling(Direction.UP)

        device.pressBack()
        check(device.wait(Until.hasObject(By.desc("Open settings")), UiTimeoutMs)) {
            "Cover did not return"
        }
        device.findObject(By.desc("Open settings")).click()
        check(device.wait(Until.hasObject(By.text("Reciter")), UiTimeoutMs)) {
            "Settings did not become ready"
        }
    }
}
