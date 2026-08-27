package com.beautifulquran.ui.reader.focus

import com.beautifulquran.domain.BASMALAH_PLAYLIST_AYAH
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The reader's single source of truth for *where a verse is on the page* and
 * *what it takes to bring it into focus*.
 *
 * This object is deliberately pure — no Android, no Compose, no [androidx]
 * imports — so it can be unit-tested on the JVM the same way [com.beautifulquran
 * .domain.HighlightEngine] is. All the messy Compose wiring (reading
 * `LazyListState.layoutInfo`, animating the scroll) lives in
 * [ReaderFocusController]; every decision it makes routes through the functions
 * here.
 *
 * ## Coordinate system
 *
 * Every pixel value shares the `LazyColumn` main axis: a verse's [TargetGeometry
 * .topPx] is its offset from the viewport's content start, exactly as reported
 * by `LazyListItemInfo.offset`. So `topPx == 0` sits at the first readable line
 * under the top chrome (the reader reserves the app-bar/status strip through the
 * list's content padding, so offset 0 is already clear of it), a negative
 * `topPx` means the verse has scrolled up past that line, and `topPx >=
 * viewportHeightPx` means it sits below the fold.
 *
 * ## Chapter-top basmalah
 *
 * Surahs that open with a basmalah preface expose that calligraphy as its **own
 * LazyColumn item** above ayah 1 (not folded into the surah-header title block).
 * While the lead-in clip plays, the focus target is [CHAPTER_TOP_FOCUS_AYAH]
 * (playlist ayah 0). [playbackFocusTarget] resolves that sentinel; the
 * controller then homes / places / returns onto it through the same
 * [anchorOffsetPx] / [placement] path used for every verse — so return-to-verse
 * and lyric-follow behave identically for the basmalah.
 */
object FocusEngine {
    /**
     * The ayah-number key used to focus the chapter-opening basmalah.
     * Same sentinel as the playlist lead-in ([BASMALAH_PLAYLIST_AYAH]).
     */
    const val CHAPTER_TOP_FOCUS_AYAH: Int = BASMALAH_PLAYLIST_AYAH

    /**
     * Resolve what lyric-follow / return-to-verse should home onto from the
     * media item actually playing. This deliberately does not accept the
     * fade-led ink target: preparing the next ayah's ink must not move the
     * reader before its audio begins. Basmalah lead-in wins over a null verse.
     */
    fun playbackFocusTarget(playingAyah: Int?, activeBasmalah: Boolean): Int? =
        if (activeBasmalah) CHAPTER_TOP_FOCUS_AYAH else playingAyah

    /** True on the first word when repeat-aware timings restart a whole ayah. */
    fun startsFullAyahRepeat(wordPosition: Int, isRepeat: Boolean, repeatStart: Int): Boolean =
        isRepeat && wordPosition == 1 && repeatStart == 1

    /** True when [focusAyah] is the chapter-opening basmalah target. */
    fun isChapterTopFocusTarget(focusAyah: Int): Boolean =
        focusAyah == CHAPTER_TOP_FOCUS_AYAH

    /**
     * Breathing room above a verse that fits on screen, as a fraction of the
     * usable viewport. A fitting verse rests with its top this far down so its
     * opening line is unmistakably in full view rather than jammed under the
     * chrome.
     */
    private const val FIT_TOP_MARGIN_FRACTION = 0.10f

    /**
     * Breathing room above a verse taller than the viewport. Near-flush with the
     * top so as much of the verse as possible is visible, but not glued to the
     * very edge.
     */
    private const val TALL_TOP_MARGIN_FRACTION = 0.04f

    /**
     * How far a verse's top may drift from its anchor before it counts as "away"
     * (and the return-to-verse control appears), as a fraction of the usable
     * viewport. Keeps tiny scroll nudges from flickering the control on and off.
     */
    private const val IN_FOCUS_TOLERANCE_FRACTION = 0.18f

    // --- Hand-initiated jump planning ---
    // A jump from verse A to verse B should *feel* like a fast scroll that
    // decelerates onto the target. The engine owns the whole plan:
    //
    //   • Near  → animate the full path (direct interpolation).
    //   • Far   → teleport to a doorstep, then animate a *long* residual so the
    //             reader actually sees verses rush past. The residual length and
    //             duration both scale with jump distance, saturating at a full
    //             second of scrolling for a ~200-verse jump — never the whole
    //             surah, but never a one-viewport nudge either.
    //
    // LazyList cannot smoothly animate across hundreds of unmeasured items, so
    // the far-path teleport is also a correctness requirement — not just a
    // timing trick.

    /**
     * How many items beyond the visible window still count as "near" enough to
     * animate the full path without a doorstep teleport.
     */
    private const val NEAR_EXTRA_ITEMS = 2

    /**
     * Longest animated residual (far jumps saturate here), in items. Sized so
     * a max-distance jump rushes through a long stretch of verses in one
     * second — enough to read as "we scrolled a long way".
     */
    private const val ANIMATED_SPAN_MAX_ITEMS = 48

    /**
     * Jump distance (in items) at which residual length and duration saturate.
     * A jump of this many verses gets the full one-second scroll.
     */
    private const val JUMP_DISTANCE_SATURATE_ITEMS = 200

    /** Shortest decelerating scroll (a verse or two away). */
    private const val JUMP_MIN_MS = 280

    /** Longest decelerating scroll — a full second of visible travel. */
    private const val JUMP_MAX_MS = 1_000

    /**
     * Plan a hand-initiated jump from [fromIndex] to [toIndex].
     *
     * - **Near** ([JumpPlan.doorstepIndex] null): the controller animates
     *   straight from the current scroll position onto the target's anchor.
     * - **Far** (doorstep set): the controller snaps to that doorstep first,
     *   then animates a distance-scaled residual — longer and up to one second
     *   for a bigger jump — so the motion reads as rushing across the page.
     *
     * [JumpPlan.animatedItemSpan] is how many items that residual covers; the
     * controller turns it into pixels from the average laid-out item height.
     */
    fun planJump(
        fromIndex: Int,
        toIndex: Int,
        visibleItemCount: Int,
        totalItemCount: Int,
    ): JumpPlan {
        val delta = toIndex - fromIndex
        val distance = abs(delta)
        if (delta == 0) {
            return JumpPlan(
                doorstepIndex = null,
                animatedItemSpan = 0,
                durationMs = JUMP_MIN_MS,
            )
        }
        val visible = visibleItemCount.coerceAtLeast(1)
        val near = distance <= visible + NEAR_EXTRA_ITEMS
        if (near) {
            return JumpPlan(
                doorstepIndex = null,
                animatedItemSpan = distance,
                durationMs = jumpDurationMs(distance),
            )
        }
        // Residual length scales with how far we're jumping: a short hop still
        // shows ~one viewport of travel; a ~200-verse jump rushes through the
        // full [ANIMATED_SPAN_MAX_ITEMS] stretch over a full second.
        // Capped at the ceiling: an unusually tall viewport could otherwise
        // make the floor exceed it, and coerceIn throws on an empty range.
        val minSpan = visible.coerceAtMost(ANIMATED_SPAN_MAX_ITEMS)
        val t = (distance.toFloat() / JUMP_DISTANCE_SATURATE_ITEMS).coerceIn(0f, 1f)
        val desiredSpan = (minSpan + (ANIMATED_SPAN_MAX_ITEMS - minSpan) * t)
            .roundToInt()
            .coerceIn(minSpan, ANIMATED_SPAN_MAX_ITEMS)
        // Never ask to animate more than the real remaining distance.
        val animatedSpan = desiredSpan.coerceAtMost(distance)
        val rawDoorstep = if (delta > 0) toIndex - animatedSpan else toIndex + animatedSpan
        val doorstep = rawDoorstep.coerceIn(0, (totalItemCount - 1).coerceAtLeast(0))
        return JumpPlan(
            doorstepIndex = doorstep,
            animatedItemSpan = animatedSpan,
            durationMs = jumpDurationMs(distance),
        )
    }

    /**
     * Duration of the decelerating scroll, scaled by the jump's full distance
     * in items so a ~200-verse jump holds a full second while a nearby one
     * stays brief. Kept in [JUMP_MIN_MS]..[JUMP_MAX_MS].
     */
    fun jumpDurationMs(jumpDistanceItems: Int): Int {
        val t = (abs(jumpDistanceItems).toFloat() / JUMP_DISTANCE_SATURATE_ITEMS)
            .coerceIn(0f, 1f)
        return (JUMP_MIN_MS + (JUMP_MAX_MS - JUMP_MIN_MS) * t).roundToInt()
    }

    /**
     * How many pixels to scroll **this frame** of a continuous home-onto-target
     * animation. Given the live [remainingPx] to the landing and the eased
     * progress (`lastProgress` → [progress], both in 0..1), consumes a matching
     * fraction of whatever is left — so the motion decelerates smoothly and
     * always ends exactly on the target even when item heights were estimated.
     *
     * When [progress] reaches 1 the entire remaining distance is consumed.
     */
    fun homeScrollStep(remainingPx: Float, progress: Float, lastProgress: Float): Float {
        if (remainingPx == 0f) return 0f
        if (progress <= lastProgress) return 0f
        if (progress >= 1f) return remainingPx
        val denom = (1f - lastProgress).coerceAtLeast(1e-4f)
        return remainingPx * ((progress - lastProgress) / denom)
    }

    /**
     * A live-remeasured home step that may travel only in [direction].
     * Annotation focus uses this while the IME and field finish laying out:
     * geometry may settle past the requested landing, but that must stop the
     * glide rather than turn it into a visible correction in reverse.
     */
    fun oneDirectionHomeScrollStep(
        remainingPx: Float,
        progress: Float,
        lastProgress: Float,
        direction: Float,
    ): Float =
        if (remainingPx * direction <= 0f) {
            0f
        } else {
            homeScrollStep(remainingPx, progress, lastProgress)
        }

    /**
     * Whether [target] is far enough from the current window that a smooth glide
     * would stutter estimating unmeasured verse heights along the way, so the
     * controller should teleport to a doorstep first and glide the last stretch.
     *
     * Prefer [planJump] for hand-initiated jumps — this remains for the
     * non-preRoll recitation-follow path, which still uses a simple doorstep
     * before its continuous home-scroll. A next-verse hop across a page
     * divider (delta ≈ 2) must stay below this threshold so it animates.
     */
    fun shouldTeleport(targetIndexDelta: Int, visibleItemCount: Int): Boolean =
        abs(targetIndexDelta) > visibleItemCount + NEAR_EXTRA_ITEMS

    /**
     * Usable vertical space for reading: the viewport minus top/bottom guards.
     * The bottom guard is the reading band above the player bar / edge fade —
     * content that only "fits" into the raw viewport still parks its last lines
     * under that chrome if we ignore it.
     */
    private fun usable(viewportHeightPx: Int, topGuardPx: Int, bottomGuardPx: Int = 0): Int =
        (viewportHeightPx - topGuardPx - bottomGuardPx).coerceAtLeast(1)

    /**
     * The reading line: the fixed vertical position, measured from content start,
     * that answers "which verse is the reader looking at". Used for the scroll
     * read-out (rail marker) and as the resting anchor for a verse that fits.
     */
    fun readingLinePx(
        viewportHeightPx: Int,
        topGuardPx: Int = 0,
        bottomGuardPx: Int = 0,
    ): Int =
        topGuardPx +
            (usable(viewportHeightPx, topGuardPx, bottomGuardPx) * FIT_TOP_MARGIN_FRACTION)
                .roundToInt()

    /**
     * The desired top offset for a verse we are focusing — the heart of the
     * "adaptive" behaviour:
     *
     * - **Fits on screen** → rest it fully in view with [FIT_TOP_MARGIN_FRACTION]
     *   breathing room, but never so high that its bottom would enter the
     *   bottom guard (player-bar reading band) — so the whole verse stays clear
     *   of the fold/fade.
     * - **Taller than the usable page** → pin its top near the content start so
     *   the reader starts at line one; word-level following then scrolls through it.
     *
     * The chapter-opening basmalah uses this same path: it is a short list item
     * that fits, so it rests on the verse-style reading line.
     *
     * A [targetHeightPx] of 0 (height not yet measured) is treated as "tall" and
     * pinned to the top — the safe default that always shows the opening line.
     */
    fun anchorOffsetPx(
        viewportHeightPx: Int,
        topGuardPx: Int,
        targetHeightPx: Int,
        bottomGuardPx: Int = 0,
    ): Int {
        val usable = usable(viewportHeightPx, topGuardPx, bottomGuardPx)
        val fits = targetHeightPx in 1..usable
        return if (fits) {
            val restingTop = topGuardPx + usable * FIT_TOP_MARGIN_FRACTION
            // Keep the verse's bottom at or above the bottom guard.
            val highestFullyVisibleTop =
                (viewportHeightPx - bottomGuardPx - targetHeightPx).coerceAtLeast(topGuardPx)
            restingTop.roundToInt().coerceIn(topGuardPx, highestFullyVisibleTop)
        } else {
            (topGuardPx + usable * TALL_TOP_MARGIN_FRACTION).roundToInt()
        }
    }

    /**
     * Pixels to scroll (positive scrolls content up, matching
     * `LazyListState.animateScrollBy`) so [target]'s top lands on
     * [anchorOffsetPx]. Only meaningful when [TargetGeometry.isLaidOut].
     */
    fun glideDeltaPx(target: TargetGeometry, anchorOffsetPx: Int): Int =
        target.topPx - anchorOffsetPx

    /**
     * Secondary lyric constraint: how far to scroll so the active word sits
     * inside the comfortable reading band (viewport minus [topGuardPx] and the
     * top/bottom band margins). Positive scrolls content up — same sign as
     * [glideDeltaPx] / `LazyListState.scrollBy`.
     *
     * Prefer a **bottom-only** band ([bandTopMarginPx] = 0) while follow is on
     * for every active word: that lifts lines clear of the player-bar fold
     * without fighting the verse-level top anchor on short verses.
     */
    fun wordBandDeltaPx(
        wordTopPx: Float,
        wordBottomPx: Float,
        viewportHeightPx: Float,
        topGuardPx: Float,
        bandTopMarginPx: Float,
        bandBottomMarginPx: Float,
    ): Float {
        val bandTop = topGuardPx + bandTopMarginPx
        val bandBottom = viewportHeightPx - bandBottomMarginPx
        if (wordTopPx < bandTop) return wordTopPx - bandTop
        if (wordBottomPx > bandBottom) return wordBottomPx - bandBottom
        return 0f
    }

    /**
     * Scroll needed to park an annotation field directly above the on-screen
     * keyboard, with [keyboardPaddingPx] of paper between them. Anchoring the
     * field as low as safely possible leaves the most room for its verse above.
     */
    fun annotationFieldDeltaPx(
        fieldBottomPx: Float,
        viewportHeightPx: Float,
        keyboardOverlapPx: Float,
        keyboardPaddingPx: Float,
    ): Float = fieldBottomPx -
        (viewportHeightPx - keyboardOverlapPx.coerceAtLeast(0f) -
            keyboardPaddingPx.coerceAtLeast(0f)).coerceAtLeast(0f)

    /** Portion of the list actually covered by the IME. The list can end above
     * the window bottom (for example above the player bar), so the raw keyboard
     * inset must not be subtracted from its shorter viewport. */
    fun keyboardOverlapPx(
        listBottomInWindowPx: Float,
        windowHeightPx: Float,
        keyboardInsetPx: Float,
    ): Float = (listBottomInWindowPx -
        (windowHeightPx - keyboardInsetPx.coerceAtLeast(0f))).coerceAtLeast(0f)

    /**
     * Where [target] sits relative to where it *should* sit — the answer the
     * return-to-verse control and the rail need. When the verse is not currently
     * laid out, [TargetGeometry.isAboveWhenOffscreen] decides the direction.
     */
    fun placement(
        target: TargetGeometry,
        viewportHeightPx: Int,
        topGuardPx: Int,
        bottomGuardPx: Int = 0,
    ): FocusPlacement {
        if (!target.isLaidOut) {
            val zone = if (target.isAboveWhenOffscreen) FocusZone.ABOVE else FocusZone.BELOW
            return FocusPlacement(zone, distancePx = 0)
        }
        val anchor = anchorOffsetPx(viewportHeightPx, topGuardPx, target.heightPx, bottomGuardPx)
        val distance = target.topPx - anchor
        val bottom = target.topPx + target.heightPx
        val usable = usable(viewportHeightPx, topGuardPx, bottomGuardPx)
        val tolerance = (usable * IN_FOCUS_TOLERANCE_FRACTION).roundToInt()
        val fold = viewportHeightPx - bottomGuardPx

        val fitsFullyVisible = target.heightPx in 1..usable &&
            target.topPx >= topGuardPx &&
            bottom <= fold

        val zone = when {
            bottom <= topGuardPx -> FocusZone.ABOVE
            target.topPx >= fold -> FocusZone.BELOW
            fitsFullyVisible -> FocusZone.IN_FOCUS
            distance in -tolerance..tolerance -> FocusZone.IN_FOCUS
            distance < 0 -> FocusZone.ABOVE
            else -> FocusZone.BELOW
        }
        return FocusPlacement(zone, distance)
    }

    /**
     * The reader's continuous position through the surah, for the rail marker:
     * an ayah number plus fractional progress through the verse at the reading
     * line. Blends to the final verse as the surah's tail settles into view,
     * because the last verse can never scroll up to the reading line on its own.
     */
    fun readoutPosition(readout: ReadoutSnapshot): Float {
        val progress = if (readout.readingAyahHeightPx > 0) {
            ((readout.readingLinePx - readout.readingAyahTopPx).toFloat() /
                readout.readingAyahHeightPx).coerceIn(0f, 1f)
        } else {
            0f
        }
        val base = readout.readingAyah + progress
        if (readout.tailVisible && readout.tailHeightPx > 0) {
            val beyondFold = readout.tailBeyondFoldPx.toFloat()
            val settle = (1f - beyondFold / readout.tailHeightPx).coerceIn(0f, 1f)
            val tail = base + (readout.lastAyahNumber - base) * settle
            return tail.coerceAtMost(readout.lastAyahNumber.toFloat())
        }
        return base.coerceIn(1f, readout.lastAyahNumber.toFloat())
    }
}

/**
 * Pure plan for a hand-initiated verse jump. Produced by [FocusEngine.planJump];
 * executed by [ReaderFocusController.focus].
 *
 * @param doorstepIndex Item index to snap to before the decelerating scroll, or
 *   `null` when the full path from the current position should be animated.
 * @param animatedItemSpan How many items the decelerating scroll should cover
 *   (the residual after a far teleport, or the full near distance).
 * @param durationMs Length of the decelerating scroll leg.
 */
data class JumpPlan(
    val doorstepIndex: Int?,
    val animatedItemSpan: Int,
    val durationMs: Int,
)

/** Immutable geometry of the verse being focused, in the coordinate system
 *  documented on [FocusEngine]. */
data class TargetGeometry(
    /** Current top offset of the verse's item. Ignored when [isLaidOut] is false. */
    val topPx: Int,
    /** Measured height of the verse's item; 0 when not yet measured. */
    val heightPx: Int,
    /** True when the verse is currently measured (visible or adjacent). */
    val isLaidOut: Boolean,
    /** When [isLaidOut] is false: is the verse above the current window? */
    val isAboveWhenOffscreen: Boolean,
)

/** Where a verse sits relative to its ideal focus position. */
enum class FocusZone {
    /** Above the reading line (the reader scrolled down past it). */
    ABOVE,

    /** Below the reading line (the reader scrolled up above it). */
    BELOW,

    /** At or near its anchor — no correction needed. */
    IN_FOCUS,
}

/** The verse's placement plus how far (signed px) its top is from its anchor. */
data class FocusPlacement(
    val zone: FocusZone,
    val distancePx: Int,
) {
    /** True when a return-to-verse nudge is warranted. */
    val isAway: Boolean get() = zone != FocusZone.IN_FOCUS

    /** True when returning means scrolling up (the verse is above the view). */
    val pointUp: Boolean get() = zone == FocusZone.ABOVE
}

/** Everything [FocusEngine.readoutPosition] needs, snapshotted from the list. */
data class ReadoutSnapshot(
    /** 1-based number of the verse crossing the reading line. */
    val readingAyah: Int,
    /** That verse's top offset. */
    val readingAyahTopPx: Int,
    /** That verse's measured height. */
    val readingAyahHeightPx: Int,
    /** The reading line position (see [FocusEngine.readingLinePx]). */
    val readingLinePx: Int,
    /** True when the surah's final item is visible (tail-blend territory). */
    val tailVisible: Boolean,
    /** How far the tail extends beyond the fold; 0 once fully settled. */
    val tailBeyondFoldPx: Int,
    /** Height of the final item. */
    val tailHeightPx: Int,
    /** The last verse number in the surah. */
    val lastAyahNumber: Int,
)
