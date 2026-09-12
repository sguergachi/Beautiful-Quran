package com.beautifulquran.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val PackageName = "com.beautifulquran"
internal const val UiTimeoutMs = 15_000L
internal const val RecitationTimeoutMs = 20_000L
internal const val BenchmarkLog = "BeautifulQuranBenchmark"
internal const val AlafasyName = "Mishary Rashid Alafasy"
internal const val OfflineFixtureArg = "offlineRecitationFixture"

internal fun requireOfflineRecitationFixture() {
    val value = InstrumentationRegistry.getArguments().getString(OfflineFixtureArg)
    check(value.equals("true", ignoreCase = true) || value == "1") {
        "Recitation needs a pre-cached Alafasy fixture and network off. " +
            "Prepare Al-Fatihah and 2:282 by playing them once while online " +
            "(do not Download all), then pass " +
            "-Pandroid.testInstrumentationRunnerArguments.$OfflineFixtureArg=true. " +
            "See docs/PROFILING.md."
    }
}

internal fun MacrobenchmarkScope.launchPastCover() {
    killProcess()
    pressHome()
    startActivityAndWait()
    device.skipCover()
}

internal fun UiDevice.skipCover() {
    click(displayWidth / 2, displayHeight / 2)
    waitForChapters()
}

internal fun UiDevice.waitForChapters() {
    waitFor(By.text("Al-Fatihah"), "Chapter list")
}

internal fun UiDevice.waitForScrollReader() {
    waitFor(By.desc("Back"), "Scroll reader")
}

internal fun UiDevice.waitForMushafReader() {
    waitFor(By.desc("Chapters"), "Mushaf reader")
    waitFor(By.descStartsWith("Mushaf page "), "Mushaf page")
}

internal fun UiDevice.waitForSettings() {
    waitFor(By.text("Reciter"), "Settings")
}

/**
 * Settings → Mishary Rashid Alafasy, Customize → [layout] + Arabic, back via Reciter
 * (Customize's own title is also "Customize", so Reciter is the Settings gate).
 */
internal fun UiDevice.chooseReaderSettings(layout: String) {
    waitFor(By.desc("Open settings"), "Settings control")
    findObject(By.desc("Open settings")).click()
    waitForSettings()
    waitFor(By.text(AlafasyName), "Alafasy row")
    findObject(By.text(AlafasyName)).click()
    findObject(By.text("Customize")).click()
    waitFor(By.text("Layout"), "Customize")
    waitFor(By.text(layout), "Layout $layout").click()
    // View is the first "Arabic" on Customize; verse/page scripts repeat the word later.
    waitFor(By.text("Arabic"), "Arabic view")
    val arabic = findObjects(By.text("Arabic")).firstOrNull()
        ?: error("Arabic view missing")
    arabic.click()
    pressBack()
    waitForSettings()
    check(wait(Until.gone(By.text("Layout")), UiTimeoutMs)) {
        "Customize still showing after back"
    }
    pressBack()
    waitForChapters()
}

internal fun UiDevice.openChapter(name: String) {
    waitFor(By.text(name), "Chapter $name").click()
}

internal fun UiDevice.search(query: String) {
    val field = waitFor(By.desc("Search"), "Search field")
    field.click()
    field.text = query
}

internal fun UiDevice.waitForSearchResults() {
    waitFor(By.textContains("In the Quran"), "Search results")
}

/** Pause is the playing control, so it is a valid ready check for recitation. */
internal fun UiDevice.playUntilStarted() {
    waitFor(By.desc("Play"), "Play").click()
    waitFor(By.desc("Pause"), "Playing recitation (offline Alafasy fixture)", RecitationTimeoutMs)
}

internal fun UiDevice.mushafPageNumber(): Int {
    // Adjacent leaves are also composed; read the leaf under the viewport centre.
    val node = findObjects(By.descStartsWith("Mushaf page "))
        .firstOrNull { it.visibleBounds.contains(displayWidth / 2, displayHeight / 2) }
        ?: error("Visible mushaf page description missing")
    val desc = node.contentDescription.orEmpty()
    return Regex("""^Mushaf page (\d+),""")
        .find(desc)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?: error("Unexpected mushaf description: $desc")
}

internal fun UiDevice.waitUntilMushafPageChanges(from: Int): Int {
    val deadline = SystemClock.elapsedRealtime() + UiTimeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        val page = runCatching { mushafPageNumber() }.getOrNull()
        if (page != null && page != from) return page
        SystemClock.sleep(50)
    }
    error("Mushaf page did not leave $from")
}

internal fun UiDevice.waitForMushafPage(page: Int) {
    val deadline = SystemClock.elapsedRealtime() + UiTimeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (runCatching { mushafPageNumber() }.getOrNull() == page) return
        SystemClock.sleep(50)
    }
    error("Mushaf page $page did not reach the viewport centre")
}

/** RTL pager: a leftward swipe, sized from the display, turns toward later pages. */
internal fun UiDevice.turnMushafTowardLaterPages() {
    swipeAcross(fromX = 0.80f, toX = 0.20f, y = displayHeight / 2)
    waitForIdle()
}

internal fun UiDevice.swipeMushafDialTowardLaterPages() {
    val play = findObject(By.desc("Play")) ?: error("Play missing — cannot aim the dial")
    val dialY = play.visibleBounds.top - play.visibleBounds.height() * 3 / 2
    swipeAcross(fromX = 0.80f, toX = 0.20f, y = dialY, steps = 30)
    waitForIdle()
}

internal fun UiDevice.returnToMushafPage(page: Int) {
    val desc = "Return to page $page"
    waitFor(By.desc(desc), desc).click()
    waitForMushafPage(page)
}

internal fun UiDevice.idleBriefly(ms: Long = 500) {
    SystemClock.sleep(ms)
}

internal fun logBenchmark(message: String) {
    Log.i(BenchmarkLog, message)
}

private fun UiDevice.swipeAcross(fromX: Float, toX: Float, y: Int, steps: Int = 40) {
    val w = displayWidth
    swipe((w * fromX).toInt(), y, (w * toX).toInt(), y, steps)
}

private fun UiDevice.waitFor(
    selector: BySelector,
    what: String,
    timeoutMs: Long = UiTimeoutMs,
) = checkNotNull(wait(Until.findObject(selector), timeoutMs)) {
    "$what did not become ready"
}
