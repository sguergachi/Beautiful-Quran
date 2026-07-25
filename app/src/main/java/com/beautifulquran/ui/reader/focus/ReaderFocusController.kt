package com.beautifulquran.ui.reader.focus

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * The Compose-facing half of the focus engine: it holds the [LazyListState] and
 * the verse↔item lookups, and is the **single writer** to that scroll state.
 * Every programmatic scroll in the reader — jumping from the selector, following
 * the recitation (including the chapter-opening basmalah), returning to the
 * playing target, the initial "continue listening" settle — goes through
 * [focus], so nothing fights over the list.
 *
 * All the actual maths lives in the pure [FocusEngine]; this class only reads
 * `layoutInfo`, snapshots it, and hands it over.
 *
 * Created via [rememberReaderFocusController]; its per-composition inputs (the
 * lookups, the surah length, the top guard) are refreshed there each frame.
 */
class ReaderFocusController internal constructor(
    val listState: LazyListState,
) {
    /**
     * Focus-target key → index in the LazyColumn item list.
     * Ayah numbers are 1-based; [FocusEngine.CHAPTER_TOP_FOCUS_AYAH] (0) maps
     * to the dedicated basmalah list item on preface chapters.
     */
    internal var itemIndexOfAyah: Map<Int, Int> = emptyMap()

    /**
     * LazyColumn item index → ayah number (1-based), for ayah items only.
     * The basmalah item is intentionally omitted so the rail readout stays in
     * 1..N.
     */
    internal var ayahNumberByItemIndex: Map<Int, Int> = emptyMap()

    /** Highest verse number in the surah — the top of the rail's range. */
    internal var lastAyahNumber: Int = 1

    /**
     * Pixels of top chrome not already covered by the list's content padding.
     * Currently 0 — the app-bar/status strip is reserved through content
     * padding, so offset 0 is already clear of it — but kept as a knob for
     * future immersive layouts.
     */
    internal var topGuardPx: Int = 0

    /**
     * Pixels reserved at the bottom of the list viewport for the soft edge
     * fade / reading band above the player bar. Tall-verse detection and
     * word-band follow both treat this strip as outside the usable page.
     */
    internal var bottomGuardPx: Int = 0

    /**
     * Serializes every programmatic scroll. Selector jumps and recitation-follow
     * can fire from sibling `LaunchedEffect`s in the same frame; without this,
     * the second `focus()` cancels the first mid-slide via `MutatorMutex` and
     * the jump reads as a pop.
     */
    private val focusMutex = Mutex()

    /**
     * The reader's continuous position through the surah (ayah number + fraction),
     * for the rail marker. Derived from `layoutInfo`, so it recomputes only when
     * read and only while scrolling.
     */
    val focusedPosition: State<Float> = derivedStateOf { computeReadoutPosition() }

    /** The whole-number verse the reader is currently looking at. */
    val focusedAyah: State<Int> = derivedStateOf {
        focusedPosition.value.toInt().coerceIn(1, lastAyahNumber.coerceAtLeast(1))
    }

    /**
     * Where [ayahNumber] sits relative to its ideal focus position. Reads
     * `layoutInfo`, so call inside a `derivedStateOf` to stay reactive. Returns
     * an in-focus placement for a null/unknown verse (nothing to correct).
     * Pass [FocusEngine.CHAPTER_TOP_FOCUS_AYAH] for the basmalah list item.
     */
    fun placementOf(ayahNumber: Int?): FocusPlacement {
        val itemIndex = ayahNumber?.let { itemIndexOfAyah[it] }
            ?: return FocusPlacement(FocusZone.IN_FOCUS, 0)
        val info = listState.layoutInfo
        val viewportHeight = info.viewportSize.height
        if (viewportHeight <= 0) return FocusPlacement(FocusZone.IN_FOCUS, 0)
        val visible = info.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        val geometry = if (visible != null) {
            TargetGeometry(
                topPx = visible.offset,
                heightPx = visible.size,
                isLaidOut = true,
                isAboveWhenOffscreen = false,
            )
        } else {
            TargetGeometry(
                topPx = 0,
                heightPx = 0,
                isLaidOut = false,
                isAboveWhenOffscreen = itemIndex < listState.firstVisibleItemIndex,
            )
        }
        return FocusEngine.placement(geometry, viewportHeight, topGuardPx, bottomGuardPx)
    }

    /** True when [ayahNumber] is currently laid out taller than the *usable*
     *  viewport (full height minus top/bottom guards). Used by the return-to-
     *  verse path and diagnostics; word follow itself always runs and no-ops
     *  when the active word is already in band. */
    fun exceedsViewport(ayahNumber: Int?): Boolean {
        val itemIndex = ayahNumber?.let { itemIndexOfAyah[it] } ?: return false
        val info = listState.layoutInfo
        val viewportHeight = info.viewportSize.height
        if (viewportHeight <= 0) return false
        val visible = info.visibleItemsInfo.firstOrNull { it.index == itemIndex } ?: return false
        val usable = (viewportHeight - topGuardPx - bottomGuardPx).coerceAtLeast(1)
        return visible.size > usable
    }

    /** Whether [ayahNumber] currently has live geometry in the list viewport. */
    fun isLaidOut(ayahNumber: Int): Boolean {
        val itemIndex = itemIndexOfAyah[ayahNumber] ?: return false
        return listState.layoutInfo.visibleItemsInfo.any { it.index == itemIndex }
    }

    /**
     * Secondary lyric constraint: scroll so the active word sits inside the
     * comfortable reading band. Serialized with [focus] so verse glides and
     * word follow never fight.
     *
     * [measureInViewport] is invoked **inside** the lock (and again after each
     * glide) so bounds stay live: top/bottom are distances from the LazyColumn
     * content-start edge (0 = top of the list viewport). Return null when the
     * word is not currently laid out.
     *
     * Pass [bandTopMarginPx] = 0 for bottom-only correction — lifts words clear
     * of the player-bar fold without fighting the verse-level top anchor.
     * Returns true once real word bounds were measured, even if no scroll was
     * needed; one-shot restore callers use that to consume their request safely.
     */
    suspend fun keepWordInView(
        bandTopMarginPx: Float,
        bandBottomMarginPx: Float,
        measureInViewport: () -> Pair<Float, Float>?,
    ): Boolean =
        keepBoundsInView(measureInViewport) { bounds, viewportHeight ->
            FocusEngine.wordBandDeltaPx(
                wordTopPx = bounds.first,
                wordBottomPx = bounds.second,
                viewportHeightPx = viewportHeight.toFloat(),
                topGuardPx = topGuardPx.toFloat(),
                bandTopMarginPx = bandTopMarginPx,
                bandBottomMarginPx = bandBottomMarginPx,
            )
        }

    /** Parks the active note field above the keyboard through the same
     * serialized focus path used by verse and word following. */
    suspend fun keepAnnotationInView(
        keyboardOverlapPx: Float,
        keyboardPaddingPx: Float,
        measureInViewport: () -> Pair<Float, Float>?,
    ) {
        keepBoundsInView(measureInViewport) { bounds, viewportHeight ->
            FocusEngine.annotationFieldDeltaPx(
                fieldBottomPx = bounds.second,
                viewportHeightPx = viewportHeight.toFloat(),
                keyboardOverlapPx = keyboardOverlapPx,
                keyboardPaddingPx = keyboardPaddingPx,
            )
        }
    }

    /** Two measured passes absorb layout settling after the first glide. */
    private suspend fun keepBoundsInView(
        measureInViewport: () -> Pair<Float, Float>?,
        deltaPx: (Pair<Float, Float>, Int) -> Float,
    ): Boolean =
        focusMutex.withLock {
            var measured = false
            repeat(2) { pass ->
                val viewportHeight = listState.layoutInfo.viewportSize.height
                if (viewportHeight <= 0) return@withLock measured
                val bounds = measureInViewport() ?: return@withLock measured
                measured = true
                val delta = deltaPx(bounds, viewportHeight)
                if (abs(delta) < 0.5f) return@withLock true
                listState.animateScrollBy(
                    delta,
                    animationSpec = tween(
                        durationMillis = if (pass == 0) DETAIL_GLIDE_MS else DETAIL_GLIDE_MS / 2,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            measured
        }

    /**
     * Bring [ayahNumber] into focus — the one entry point for every programmatic
     * scroll. Waits for a real viewport before measuring (killing the
     * open-before-layout race), then lands the target on its adaptive anchor.
     * [animate] false snaps instantly (used for the initial settle).
     *
     * Pass [FocusEngine.CHAPTER_TOP_FOCUS_AYAH] (0) to home onto the basmalah
     * list item above ayah 1 — same glide / placement path as any verse.
     *
     * [preRoll] is for jumps the reader initiates by hand (selector, search,
     * return-to-verse). The pure [FocusEngine.planJump] decides the whole
     * trajectory:
     * - **Near** — one continuous home-scroll from here onto the verse.
     * - **Far** — teleport to a doorstep, then one continuous home-scroll across
     *   a distance-scaled residual (up to ~48 items / one full second) that
     *   re-aims at the live remaining distance every frame — smooth all the way
     *   until it lands exactly, with no rush-then-settle handoff stutter.
     *
     * Recitation-follow leaves [preRoll] off so lyric tracking stays a gentle
     * glide — still via the same continuous [animateHomeOnto] path (not a
     * one-shot measure), so the next verse across a page divider never pops
     * into place when it was not yet laid out. See [FocusEngine.planJump].
     */
    suspend fun focus(ayahNumber: Int, animate: Boolean, preRoll: Boolean = false) {
        focusMutex.withLock {
            focusLocked(ayahNumber, animate, preRoll)
        }
    }

    private suspend fun focusLocked(ayahNumber: Int, animate: Boolean, preRoll: Boolean) {
        val itemIndex = itemIndexOfAyah[ayahNumber] ?: return
        // Never measure against a zero viewport — wait for the first real layout.
        val viewportHeight = snapshotFlow { listState.layoutInfo.viewportSize.height }
            .first { it > 0 }

        val info = listState.layoutInfo
        val fromIndex = listState.firstVisibleItemIndex
        val visibleCount = info.visibleItemsInfo.size.coerceAtLeast(1)
        val totalCount = info.totalItemsCount.coerceAtLeast(1)

        if (preRoll) {
            val plan = FocusEngine.planJump(
                fromIndex = fromIndex,
                toIndex = itemIndex,
                visibleItemCount = visibleCount,
                totalItemCount = totalCount,
            )
            plan.doorstepIndex?.let { doorstep ->
                // Far: cover the bulk instantly so the home-scroll only has the
                // distance-scaled residual to show as travel.
                listState.scrollToItem(doorstep)
            }
            // One continuous decelerating home — no second settle phase.
            animateHomeOnto(itemIndex, viewportHeight, plan.durationMs)
            return
        }

        // Recitation-follow / non-preRoll: soft glide, with a simple doorstep
        // when the target is far beyond the measured window.
        if (FocusEngine.shouldTeleport(
                targetIndexDelta = itemIndex - fromIndex,
                visibleItemCount = visibleCount,
            )
        ) {
            val doorstep = if (itemIndex > fromIndex) {
                itemIndex - visibleCount
            } else {
                itemIndex + visibleCount
            }
            listState.scrollToItem(
                doorstep.coerceIn(0, (totalCount - 1).coerceAtLeast(0)),
            )
        }

        if (!animate) {
            // Instant settle (continue-listening open): force the item into
            // layout when needed, then snap by the exact remaining pixels.
            val delta = measureGlideDelta(itemIndex, viewportHeight) ?: return
            if (delta != 0) listState.scrollBy(delta.toFloat())
            return
        }

        // Continuous re-aim — same path as the hand-jump residual. Critical
        // across a mushaf page break: the next ayah often sits just past a
        // PageDivider and is not yet laid out, so a one-shot measure would
        // scrollToItem (a pop) before gliding. Estimating + re-aiming each
        // frame keeps the motion a smooth decelerating scroll onto the verse.
        animateHomeOnto(itemIndex, viewportHeight, GLIDE_MS)
    }

    /**
     * Continuously home onto [itemIndex]'s adaptive anchor over [durationMs].
     *
     * Runs as a single [LazyListState.scroll] so the decelerating motion holds
     * the MutatorMutex the whole way — no rush-then-settle handoff. Each frame
     * re-reads the live remaining distance (measured when the verse is laid
     * out, estimated from average item height when not) and scrolls the
     * FastOutSlowIn fraction of whatever is left — see
     * [FocusEngine.homeScrollStep]. Uneven ayah heights never produce an
     * undershoot that then gets corrected in a chunky second phase.
     */
    private suspend fun animateHomeOnto(
        itemIndex: Int,
        viewportHeight: Int,
        durationMs: Int,
    ) {
        if (abs(remainingPxToAnchor(itemIndex, viewportHeight)) < 0.5f) return

        listState.scroll {
            var lastProgress = 0f
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs.coerceAtLeast(1),
                    easing = FastOutSlowInEasing,
                ),
            ) { value, _ ->
                val remaining = remainingPxToAnchor(itemIndex, viewportHeight)
                val step = FocusEngine.homeScrollStep(remaining, value, lastProgress)
                lastProgress = value
                if (abs(step) >= 0.5f) {
                    scrollBy(step)
                }
            }
            // Consume any sub-pixel leftover from clamping — invisible as a jump.
            val leftover = remainingPxToAnchor(itemIndex, viewportHeight)
            if (abs(leftover) >= 0.5f) {
                scrollBy(leftover)
            }
        }
    }

    /**
     * Signed pixels still needed to put [itemIndex] on its adaptive anchor.
     * Uses real geometry when laid out; otherwise estimates from the average
     * laid-out item height (updated as new verses enter during the home-scroll).
     */
    private fun remainingPxToAnchor(itemIndex: Int, viewportHeight: Int): Float {
        val visible = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == itemIndex }
        if (visible != null) {
            val anchor = FocusEngine.anchorOffsetPx(
                viewportHeight, topGuardPx, visible.size, bottomGuardPx,
            )
            return FocusEngine.glideDeltaPx(
                TargetGeometry(
                    visible.offset,
                    visible.size,
                    isLaidOut = true,
                    isAboveWhenOffscreen = false,
                ),
                anchor,
            ).toFloat()
        }
        val avg = averageLaidOutItemHeightPx().coerceAtLeast(1)
        val indexDelta = itemIndex - listState.firstVisibleItemIndex
        // Approximate the target's top as if every intervening item were `avg`
        // tall, accounting for the partial scroll of the first visible item.
        val approxTop = indexDelta * avg - listState.firstVisibleItemScrollOffset
        val approxAnchor = FocusEngine.anchorOffsetPx(
            viewportHeight, topGuardPx, avg, bottomGuardPx,
        )
        return (approxTop - approxAnchor).toFloat()
    }

    /** Mean height of currently laid-out items — used when the target is not
     *  yet measured. Recomputed each frame so the estimate improves as verses
     *  rush past during a far home-scroll. */
    private fun averageLaidOutItemHeightPx(): Int {
        val items = listState.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return 0
        return items.sumOf { it.size } / items.size
    }

    /**
     * Pixels to scroll so [itemIndex] lands on its adaptive anchor. Forces the
     * item into layout when it is not yet measured — used only by the instant
     * (`animate = false`) settle path, where a snap is intentional.
     */
    private suspend fun measureGlideDelta(itemIndex: Int, viewportHeight: Int): Int? {
        var target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        if (target == null) {
            listState.scrollToItem(itemIndex)
            target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        }
        val geometry = target?.let {
            TargetGeometry(it.offset, it.size, isLaidOut = true, isAboveWhenOffscreen = false)
        } ?: return null
        val anchor = FocusEngine.anchorOffsetPx(
            viewportHeight, topGuardPx, geometry.heightPx, bottomGuardPx,
        )
        return FocusEngine.glideDeltaPx(geometry, anchor)
    }

    private fun computeReadoutPosition(): Float {
        val info = listState.layoutInfo
        val viewportHeight = info.viewportSize.height
        val last = lastAyahNumber.coerceAtLeast(1)
        if (viewportHeight <= 0 || info.visibleItemsInfo.isEmpty()) return 1f

        val readingLine = FocusEngine.readingLinePx(viewportHeight, topGuardPx, bottomGuardPx)
        val ayahItems = info.visibleItemsInfo.filter { ayahNumberByItemIndex.containsKey(it.index) }
        if (ayahItems.isEmpty()) return 1f
        // The verse crossing the reading line — not merely the first one peeking
        // over the top edge, so a 1px sliver never reads as a whole verse behind.
        val reading = ayahItems.lastOrNull { it.offset <= readingLine } ?: ayahItems.first()
        val readingAyah = ayahNumberByItemIndex[reading.index] ?: return 1f

        val lastItem = info.visibleItemsInfo.last()
        val tailVisible = lastItem.index == info.totalItemsCount - 1
        val snapshot = ReadoutSnapshot(
            readingAyah = readingAyah,
            readingAyahTopPx = reading.offset,
            readingAyahHeightPx = reading.size,
            readingLinePx = readingLine,
            tailVisible = tailVisible,
            tailBeyondFoldPx = if (tailVisible) {
                (lastItem.offset + lastItem.size - info.viewportEndOffset).coerceAtLeast(0)
            } else {
                0
            },
            tailHeightPx = lastItem.size,
            lastAyahNumber = last,
        )
        return FocusEngine.readoutPosition(snapshot)
    }

    private companion object {
        /** Soft recitation-follow / layout-reflow glide duration. Hand-initiated
         *  jumps use [FocusEngine.planJump]'s duration instead. */
        const val GLIDE_MS: Int = 700

        /** Detail-follow duration — short so line changes feel instant but
         *  still ease (matches the web port's word glide). */
        const val DETAIL_GLIDE_MS: Int = 300
    }
}

/**
 * Remembers a [ReaderFocusController] bound to [listState] and refreshes its
 * per-composition inputs. The lookups are plain fields (not snapshot state):
 * they only change when the surah content changes, at which point the whole
 * screen recomposes and the derived states re-read them anyway.
 */
@Composable
fun rememberReaderFocusController(
    listState: LazyListState,
    itemIndexOfAyah: Map<Int, Int>,
    ayahNumberByItemIndex: Map<Int, Int>,
    lastAyahNumber: Int,
    topGuardPx: Int = 0,
    bottomGuardPx: Int = 0,
): ReaderFocusController {
    val controller = remember(listState) { ReaderFocusController(listState) }
    controller.itemIndexOfAyah = itemIndexOfAyah
    controller.ayahNumberByItemIndex = ayahNumberByItemIndex
    controller.lastAyahNumber = lastAyahNumber
    controller.topGuardPx = topGuardPx
    controller.bottomGuardPx = bottomGuardPx
    return controller
}
