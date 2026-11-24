package com.beautifulquran.baselineprofile

import android.os.SystemClock
import android.util.Log
import android.widget.EditText
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.waitForStable
import java.util.regex.Pattern

internal const val PackageName = "com.beautifulquran"
internal const val UiTimeoutMs = 15_000L
internal const val RecitationTimeoutMs = 20_000L
internal const val BenchmarkLog = "BeautifulQuranBenchmark"
internal const val AlafasyName = "Mishary Rashid Alafasy"
internal const val OfflineFixtureArg = "offlineRecitationFixture"
internal const val IterationsArg = "iterations"

/**
 * Iteration count for a measured or collected journey: [default] unless the run
 * passes `-Pandroid.testInstrumentationRunnerArguments.iterations=N` — a quick
 * end-to-end check of the harness (N = 1) without editing the benchmarks.
 */
internal fun iterations(default: Int): Int =
    InstrumentationRegistry.getArguments().getString(IterationsArg)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: default

/**
 * A fresh install holds the cover until its first timing (and, for Mushaf, page)
 * fill lands over the network; the board says so in its own description.
 */
private const val CoverReadyTimeoutMs = 180_000L
private const val CoverReadyDescription = "The Noble Quran — touch to open"

/** The board in any state: ready, or naming the first fill it is waiting on. */
private val CoverAnyState: BySelector =
    By.desc(Pattern.compile("(?s).*(Quran|recitation timings).*"))

/** A Mushaf leaf can still be filling its glyph fonts the first time a chapter opens. */
private const val MushafLeafTimeoutMs = 60_000L

/** Mirrors `MushafPageDialTag` in the app (surfaced via `testTagsAsResourceId`). */
private const val MushafPageDialTag = "mushafPageDial"

/**
 * The paper stack keeps neighbouring sheets composed, and a turning sheet's rows
 * are already in the accessibility tree while it slides. A tap aimed at a row
 * still in motion lands where the row *was*; wait for its bounds to hold first.
 */
private const val SettleTimeoutMs = 5_000L
private const val SettleIntervalMs = 300L

/** Section labels are set in small caps, so match them whatever their case. */
private fun sectionLabel(name: String): BySelector =
    By.text(Pattern.compile(Pattern.quote(name), Pattern.CASE_INSENSITIVE))

private val SettingsGate = sectionLabel("Reciter")
private val CustomizeGate = sectionLabel("Layout")

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

/**
 * Opens the cover once it is ready to open. Until its first content fill lands
 * the board describes the download instead, and a tap behind the system splash
 * is lost, so wait for the ready description rather than tapping into it. The
 * ceremony also opens on its own, so a cover that is already gone is fine.
 */
internal fun UiDevice.skipCover() {
    val ready = By.desc(CoverReadyDescription)
    val deadline = SystemClock.elapsedRealtime() + CoverReadyTimeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        val cover = findObject(ready)
        if (cover != null) {
            cover.click()
            break
        }
        if (!hasObject(CoverAnyState) && hasObject(By.desc("Open settings"))) break
        SystemClock.sleep(250)
    }
    check(wait(Until.gone(CoverAnyState), UiTimeoutMs)) {
        "Cover did not open: ${findObject(CoverAnyState)?.contentDescription}"
    }
    waitForChapters()
}

internal fun UiDevice.waitForChapters() {
    waitForSettled(By.text("Al-Fatihah"), "Chapter list")
}

internal fun UiDevice.waitForScrollReader() {
    waitFor(By.desc("Back"), "Scroll reader")
}

internal fun UiDevice.waitForMushafReader() {
    waitFor(By.desc("Chapters"), "Mushaf reader")
    waitFor(By.descStartsWith("Mushaf page "), "Mushaf page", MushafLeafTimeoutMs)
}

internal fun UiDevice.waitForSettings() {
    waitForSettled(SettingsGate, "Settings")
}

internal fun UiDevice.openSettings() {
    waitForSettled(By.desc("Open settings"), "Settings control").click()
    waitForSettings()
}

/**
 * Settings → Mishary Rashid Alafasy, Customize → [layout] + Arabic, back via the
 * Reciter section (Customize's own title is also "Customize", so Reciter is the
 * Settings gate). Each choice is confirmed by its selected state, not assumed.
 */
internal fun UiDevice.chooseReaderSettings(layout: String) {
    openSettings()
    chooseAndConfirm(waitForChoice(AlafasyName), AlafasyName)
    waitForSettled(By.text("Customize"), "Customize row").click()
    waitForSettled(CustomizeGate, "Customize")
    chooseAndConfirm(waitForChoice(layout), "Layout $layout")
    chooseAndConfirm(arabicViewChoice(), "Arabic view")
    pressBack()
    waitForSettings()
    check(wait(Until.gone(CustomizeGate), UiTimeoutMs)) {
        "Customize still showing after back"
    }
    pressBack()
    waitForChapters()
}

/**
 * The "Arabic" under Customize's View section. Verse and page numbers offer an
 * "Arabic" script further down, and View itself sits below the fold, so scroll
 * until a choice set directly under the View label is on screen.
 */
private fun UiDevice.arabicViewChoice(): UiObject2 {
    val viewLabel = sectionLabel("View")
    val page = waitFor(By.scrollable(true).hasDescendant(CustomizeGate), "Customize scroll")
    repeat(8) {
        val label = findObject(viewLabel)
        if (label != null) {
            val below = label.visibleBounds.bottom
            val arabic = findObjects(By.checkable(true).text("Arabic"))
                .filter { it.visibleBounds.top >= below }
                .minByOrNull { it.visibleBounds.top }
            if (arabic != null && arabic.visibleBounds.height() > 0) {
                arabic.waitForStable(SettleTimeoutMs, SettleIntervalMs, requireStableScreenshot = false)
                return arabic
            }
        }
        if (!page.scroll(Direction.DOWN, 0.5f)) return@repeat
    }
    error("Arabic view choice not found under View")
}

/**
 * A selectable choice named [name]. The selected state lives on the clickable
 * node: a choice word is that node itself, while a reciter row is a clickable
 * row holding its name as a child text.
 */
private fun UiDevice.waitForChoice(name: String): UiObject2 {
    val itself = By.checkable(true).text(name)
    val holder = By.checkable(true).hasChild(By.text(name))
    val deadline = SystemClock.elapsedRealtime() + UiTimeoutMs
    while (true) {
        val found = findObject(itself) ?: findObject(holder)
        if (found != null) {
            found.waitForStable(SettleTimeoutMs, SettleIntervalMs, requireStableScreenshot = false)
            return found
        }
        check(SystemClock.elapsedRealtime() < deadline) { "Choice $name did not become ready" }
        SystemClock.sleep(50)
    }
}

/** Taps a choice and waits until it reports itself selected. */
private fun UiDevice.chooseAndConfirm(choice: UiObject2, what: String) {
    if (!choice.isChosen()) choice.click()
    val deadline = SystemClock.elapsedRealtime() + UiTimeoutMs
    while (!choice.isChosen()) {
        check(SystemClock.elapsedRealtime() < deadline) { "$what was not selected" }
        SystemClock.sleep(50)
    }
    waitForIdle()
}

/**
 * Compose reports `Modifier.semantics { selected = … }` as checkable + checked
 * on every role except Tab, where it is `isSelected`; accept either.
 */
private fun UiObject2.isChosen(): Boolean = isChecked || isSelected

internal fun UiDevice.openChapter(name: String) {
    clickSettled(By.text(name), "Chapter $name")
}

/**
 * The Home search field. Its "Search" label is a contentDescription on a field
 * that merges its descendants, so Compose reports the label as a child node of
 * the EditText rather than on it; only the EditText takes text. Accept either
 * shape so the journey keeps working if Compose moves the label onto the field.
 */
internal fun UiDevice.searchField(): UiObject2 {
    val labelled = By.clazz(EditText::class.java).desc("Search")
    val parentOfLabel = By.clazz(EditText::class.java).hasChild(By.desc("Search"))
    check(wait(Until.hasObject(By.desc("Search")), UiTimeoutMs)) { "Search field did not become ready" }
    return findObject(labelled) ?: findObject(parentOfLabel) ?: error("Search field is not editable")
}

internal fun UiDevice.search(query: String) {
    searchField().click()
    // Focus lifts the field as the masthead recedes; let it land first.
    searchField().waitForStable(SettleTimeoutMs, SettleIntervalMs, requireStableScreenshot = false)
    setSearchText(query)
}

/** Sets the query and checks that it reached the field. */
internal fun UiDevice.setSearchText(query: String) {
    val field = searchField()
    field.text = query
    check(field.wait(Until.textEquals(query), UiTimeoutMs) == true || query.isEmpty()) {
        "Search field did not take \"$query\""
    }
}

internal fun UiDevice.waitForSearchResults() {
    waitFor(By.textContains("In the Quran"), "Search results")
}

internal fun UiDevice.waitForSearchResultsGone() {
    check(wait(Until.gone(By.textContains("In the Quran")), UiTimeoutMs)) { "Search results did not clear" }
}

/** The list the results are laid out in, once they are in. */
internal fun UiDevice.searchResultsList(): UiObject2 =
    waitFor(By.scrollable(true).hasDescendant(By.textContains("In the Quran")), "Search results list")

/** Pause is the playing control, so it is a valid ready check for recitation. */
internal fun UiDevice.playUntilStarted() {
    waitForSettled(By.desc("Play"), "Play").click()
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

/**
 * Waits until leaf [page] has stopped moving, then a reader's beat. The page
 * number under the viewport centre changes halfway through a turn; a swipe
 * begun there lands on a leaf still flinging, and the pager snaps to whichever
 * page is nearer, which is not a turn.
 */
internal fun UiDevice.waitForMushafLeafAtRest(page: Int) {
    waitForSettled(By.descStartsWith("Mushaf page $page,"), "Mushaf page $page at rest")
    idleBriefly(MushafReadingPauseMs)
}

/** RTL pager: a rightward swipe, sized from the display, turns toward later pages. */
internal fun UiDevice.turnMushafTowardLaterPages() {
    swipeAcross(fromX = 0.20f, toX = 0.80f, y = displayHeight / 2)
    waitForIdle()
}

/** A quick rightward flick, not waited out: the next one lands mid-turn. */
internal fun UiDevice.flickMushafTowardLaterPages() {
    swipeAcross(fromX = 0.25f, toX = 0.75f, y = displayHeight / 2, steps = 12)
    SystemClock.sleep(RapidFlipGapMs)
}

private const val RapidFlipGapMs = 180L

/**
 * A leftward stroke along the dial's own grab strip (21 dp tall), aimed at its
 * centre line. The strip's bounds come from its test tag, not from the
 * transport row, which moves with playback and layout.
 */
internal fun UiDevice.swipeMushafDialTowardLaterPages() {
    val dial = waitForSettled(By.res(MushafPageDialTag), "Mushaf page dial")
    swipeAcross(fromX = 0.80f, toX = 0.20f, y = dial.visibleBounds.centerY(), steps = 30)
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

private const val MushafReadingPauseMs = 1_000L

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
): UiObject2 = checkNotNull(wait(Until.findObject(selector), timeoutMs)) {
    "$what did not become ready"
}

/** [waitFor], then holds until the object stops moving (a sheet has finished turning). */
private fun UiDevice.waitForSettled(
    selector: BySelector,
    what: String,
    timeoutMs: Long = UiTimeoutMs,
): UiObject2 = settled(selector, what, timeoutMs) { it }

/** [waitForSettled] and click, as one step that survives the node being replaced. */
private fun UiDevice.clickSettled(selector: BySelector, what: String) {
    settled(selector, what, UiTimeoutMs) { it.click() }
}

/**
 * Finds [selector], waits for it to stop moving, then runs [use] on it. A list
 * that re-renders (search results arriving, a sheet's rows recomposing) swaps
 * the node for a new one; that is a real change in the UI, so look it up again
 * rather than fail, until [timeoutMs].
 */
private fun <T> UiDevice.settled(
    selector: BySelector,
    what: String,
    timeoutMs: Long,
    use: (UiObject2) -> T,
): T {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (true) {
        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        val found = waitFor(selector, what, remaining)
        try {
            val stable = found.waitForStable(SettleTimeoutMs, SettleIntervalMs, requireStableScreenshot = false)
            check(!stable.isTimeout) { "$what kept moving" }
            return use(found)
        } catch (replaced: StaleObjectException) {
            if (SystemClock.elapsedRealtime() >= deadline) throw replaced
        }
    }
}
