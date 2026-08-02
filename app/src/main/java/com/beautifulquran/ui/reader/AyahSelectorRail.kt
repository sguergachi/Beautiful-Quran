package com.beautifulquran.ui.reader

import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.ui.theme.LocalQuranAccents
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The ayah selector rail: a collapsed stack of bars flush with the screen edge
 * that blooms into a scrub wheel under the finger. Selection commits after a
 * grace countdown; all drawing happens in one Canvas at draw-phase only.
 */
internal fun rubberBandDialPosition(value: Float, min: Float, max: Float): Float {
    if (value in min..max) return value
    return if (value < min) {
        min - (min - value) * 0.32f
    } else {
        max + (value - max) * 0.32f
    }
}

internal fun symbolicAyahBarCount(ayahCount: Int): Int {
    return ceil(sqrt(ayahCount.toFloat())).roundToInt().coerceIn(4, 18)
}

/**
 * Vertical span (dp) of the collapsed dash stack drawn by [AyahSelectorRail].
 * Matches the Canvas layout: bar height 1.5dp, spacing 72/count clamped 4–8.
 */
internal fun collapsedStackSpanDp(ayahCount: Int): Float {
    val count = symbolicAyahBarCount(ayahCount)
    val barH = 1.5f
    val spacing = (72f / count).coerceIn(4f, 8f)
    return (count - 1) * (barH + spacing) + barH
}

/**
 * Collapsed touch-target height (dp): the visual stack plus a little pad, with
 * a 48dp floor so short surahs stay easy to hit. Keeps the hit zone on the
 * vertical center of the edge — not the full screen height.
 */
internal fun collapsedRailHitHeightDp(ayahCount: Int, padDp: Float = 24f): Float {
    return max(48f, collapsedStackSpanDp(ayahCount) + padDp)
}

/**
 * Ayah number → mushaf page number for ayahs that open a new page: the first
 * ayah whose page differs from the previous ayah's. The expanded wheel marks
 * these with a faded diamond and page number. Ayahs with no page data (0) are
 * skipped without breaking the boundary chain.
 */
internal fun pageStartByAyah(ayahs: List<Ayah>): Map<Int, Int> = buildMap {
    var previousPage = 0
    for (ayah in ayahs) {
        if (ayah.page <= 0) continue
        if (ayah.page != previousPage) put(ayah.number, ayah.page)
        previousPage = ayah.page
    }
}

private suspend fun settleDialWheel(
    start: Float,
    velocity: Float,
    ayahCount: Int,
    setPosition: (Float) -> Unit,
): Int {
    val min = 1f
    val max = ayahCount.toFloat()
    val anim = Animatable(start.coerceIn(min, max))
    // Bounds stop the decay the moment it reaches either end — no invisible
    // tail running past the range while the wheel sits frozen at the clamp.
    anim.updateBounds(lowerBound = min, upperBound = max)

    val flung = abs(velocity) > 0.06f
    if (flung) {
        anim.animateDecay(
            initialVelocity = velocity,
            animationSpec = exponentialDecay(frictionMultiplier = 1.85f),
        ) {
            setPosition(value)
        }
    }

    val target = anim.value.roundToInt().coerceIn(1, ayahCount)
    anim.animateTo(
        targetValue = target.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        initialVelocity = if (flung) 0f else velocity,
    ) {
        setPosition(value)
    }
    setPosition(target.toFloat())
    return target
}

@Composable
internal fun AyahSelectorRail(
    ayahCount: Int,
    side: AyahSelectorSide,
    currentAyah: State<Int>,
    currentPosition: State<Float>,
    /** Ayah numbers bookmarked in this surah — ruby ticks on the collapsed stack. */
    bookmarkedAyahs: Set<Int> = emptySet(),
    /** Ayah number → mushaf page where a new page opens — a faded diamond and
     *  page number trailing the tick's label on the expanded wheel. */
    pageStarts: Map<Int, Int> = emptyMap(),
    chromeAlpha: () -> Float,
    /** Plain boolean for the touch-target gate, so composition never reads the
     * animated [chromeAlpha] (which would recompose the rail on every fade
     * frame). The gesture handler still consults [chromeAlpha] at touch time. */
    interactive: Boolean,
    onJumpToAyah: (Int) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    /** Root-space center of the collapsed stack for contextual education. */
    onCollapsedCenterY: (Float) -> Unit = {},
    dismissRequests: Int,
    modifier: Modifier = Modifier,
) {
    val mirrored = side == AyahSelectorSide.RIGHT
    var expanded by remember { mutableStateOf(false) }
    // Owned Animatable rather than animateFloatAsState so the commit sequence
    // can await the collapse and keep the wheel anchored on the chosen ayah
    // the whole way out.
    val expansion = remember { Animatable(0f) }
    var lastHapticAyah by remember(ayahCount) {
        mutableIntStateOf(currentAyah.value.coerceIn(1, ayahCount))
    }
    var dialPosition by remember(ayahCount) {
        mutableFloatStateOf(currentPosition.value.coerceIn(1f, ayahCount.toFloat()))
    }
    // Runs settle + grace countdown + commit after a release; cancelled by the next touch.
    var releaseJob by remember { mutableStateOf<Job?>(null) }
    // The ayah a pending release settled on, so a tap-away can commit exactly
    // that even mid-grace instead of re-reading a still-animating dialPosition.
    var pendingCommitAyah by remember { mutableStateOf<Int?>(null) }
    val commitProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val latestOnJumpToAyah by rememberUpdatedState(onJumpToAyah)
    val latestOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val latestOnCollapsedCenterY by rememberUpdatedState(onCollapsedCenterY)

    LaunchedEffect(expanded) {
        latestOnExpandedChange(expanded)
    }

    // Applies the chosen ayah: jumps the reader if it differs from where it
    // already sits, then collapses the wheel anchored on that ayah. Shared by
    // the grace-timeout path and the tap-away path so both actually commit.
    suspend fun commitSelection(target: Int) {
        pendingCommitAyah = null
        if (target != currentAyah.value.coerceIn(1, ayahCount)) {
            latestOnJumpToAyah(target)
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        expanded = false
        // Collapse stays anchored on the committed ayah — dialPosition is not
        // touched, so the wheel fades out where the reader left it instead of
        // snapping back to the pre-jump scroll position.
        expansion.animateTo(0f, spring(dampingRatio = 1f, stiffness = 200f))
        commitProgress.snapTo(0f)
        releaseJob = null
    }

    LaunchedEffect(dismissRequests) {
        if (dismissRequests == 0 || !expanded) return@LaunchedEffect
        // Tapping away commits the current selection rather than discarding it:
        // the reader has already scrubbed to an ayah, so dismissing the wheel
        // means "take me there", not "cancel". A wheel opened without scrubbing
        // resolves to the current ayah, so the jump is a harmless no-op.
        releaseJob?.cancel()
        releaseJob = null
        val target = pendingCommitAyah ?: dialPosition.roundToInt().coerceIn(1, ayahCount)
        commitProgress.snapTo(0f)
        commitSelection(target)
    }

    fun scheduleReleaseCommit(start: Float, velocity: Float) {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            commitProgress.snapTo(0f)
            val target = settleDialWheel(
                start = start,
                velocity = velocity,
                ayahCount = ayahCount,
                setPosition = { dialPosition = it },
            )
            lastHapticAyah = target
            pendingCommitAyah = target
            // Grace window: the gold underline drains for 1.5s; touching the
            // rail again cancels this job and hands the wheel back for edits.
            commitProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1_500, easing = LinearEasing),
            )
            commitSelection(target)
        }
    }

    LaunchedEffect(ayahCount) {
        // Mirror the reading position only while the wheel is fully hidden;
        // syncing any earlier yanks a still-visible wheel to a stale ayah.
        snapshotFlow { currentAyah.value to currentPosition.value }
            .collect { (ayah, position) ->
                if (!expanded && releaseJob == null && !expansion.isRunning) {
                    lastHapticAyah = ayah.coerceIn(1, ayahCount)
                    dialPosition = position.coerceIn(1f, ayahCount.toFloat())
                }
            }
    }
    LaunchedEffect(expanded, ayahCount) {
        if (!expanded) return@LaunchedEffect
        snapshotFlow { dialPosition.roundToInt().coerceIn(1, ayahCount) }
            .collect { ayah ->
                if (ayah != lastHapticAyah) {
                    val majorTick = ayah == 1 || ayah == ayahCount || ayah % 5 == 0
                    view.performHapticFeedback(
                        if (majorTick) {
                            HapticFeedbackConstants.CONTEXT_CLICK
                        } else {
                            HapticFeedbackConstants.CLOCK_TICK
                        },
                    )
                    lastHapticAyah = ayah
                }
            }
    }
    val accents = LocalQuranAccents.current
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            // Fixed drawing width: the old expand/collapse width animation
            // re-laid the rail out every frame. Touch handling is split into
            // a child below so the collapsed hit target is only the visible
            // dash stack (vertically centered), not the full edge strip —
            // letting ayah text above/below and beside the stack receive taps.
            .width(92.dp)
            .graphicsLayer { alpha = chromeAlpha() },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    latestOnCollapsedCenterY(
                        coordinates.positionInRoot().y + coordinates.size.height / 2f,
                    )
                },
        ) {
            val expand = expansion.value
            // The right-side rail is the left layout mirrored across the rail's
            // own width: bar rects flip so their hidden cap lands under the far
            // edge, and numbers hang off the inner end instead.
            val railWidth = size.width
            fun rectLeft(x: Float, width: Float) = if (mirrored) railWidth - x - width else x
            fun textAnchor(x: Float) = if (mirrored) railWidth - x else x
            // Always anchored on dialPosition: while hidden it mirrors the
            // reading position (synced above), while visible it is the finger
            // — including the rubber-banded overshoot past either end.
            val selectedPosition = dialPosition
            val selectedAyah = selectedPosition.roundToInt().coerceIn(1, ayahCount)
            val collapsedX = 0f
            val centerY = size.height * 0.5f
            val collapsedAlpha = 1f - expand
            val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = if (mirrored) Paint.Align.RIGHT else Paint.Align.LEFT
                textSize = 9.sp.toPx()
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
            val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = if (mirrored) Paint.Align.RIGHT else Paint.Align.LEFT
                textSize = 7.5.sp.toPx()
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }

            // Collapsed-stack metrics live outside the pass because the
            // expanded wheel's focal point blooms out of the dark bar.
            val collapsedBarsCount = symbolicAyahBarCount(ayahCount)
            val collapsedBarHeight = 1.5.dp.toPx()
            val collapsedSpacing = (72.dp.toPx() / collapsedBarsCount)
                .coerceIn(4.dp.toPx(), 8.dp.toPx())
            val collapsedStep = collapsedBarHeight + collapsedSpacing
            val readProgress = if (ayahCount > 1) {
                ((currentPosition.value - 1f) / (ayahCount - 1).toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val collapsedActivePosition = readProgress * (collapsedBarsCount - 1)

            if (collapsedAlpha > 0.01f) {
                // Symbolic summary of the surah: bar count grows with ayah
                // count (sqrt-mapped), and the glow slides through the stack
                // as the reader moves through the surah. Bookmarks recolor the
                // nearest existing bar rather than adding a second mark.
                val collapsedBarWidth = 10.dp.toPx()
                val collapsedCorner = CornerRadius(collapsedBarHeight, collapsedBarHeight)
                val halfSpan = ((collapsedBarsCount - 1) / 2f).coerceAtLeast(1f)
                val bookmarkedBarIndices = if (bookmarkedAyahs.isEmpty()) {
                    emptySet()
                } else {
                    buildSet {
                        for (ayah in bookmarkedAyahs) {
                            if (ayah !in 1..ayahCount) continue
                            val progress = if (ayahCount > 1) {
                                ((ayah - 1f) / (ayahCount - 1).toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            add(
                                (progress * (collapsedBarsCount - 1))
                                    .roundToInt()
                                    .coerceIn(0, collapsedBarsCount - 1),
                            )
                        }
                    }
                }

                for (index in 0 until collapsedBarsCount) {
                    val relative = index - (collapsedBarsCount - 1) / 2f
                    val y = centerY + relative * collapsedStep
                    // Staggered exit: outer bars slip off the edge first as
                    // the wheel opens, and return last on close.
                    val exit = (collapsedAlpha * 1.35f - (abs(relative) / halfSpan) * 0.35f)
                        .coerceIn(0f, 1f)
                    if (exit <= 0.01f) continue
                    // Continuous focus instead of one hard active index, so
                    // the highlight glides between bars during scroll.
                    val focus = (1f - abs(index - collapsedActivePosition)).coerceIn(0f, 1f)
                    // Left cap hidden behind the screen edge so the bars read
                    // as flush at x = 0 despite the rounded corners.
                    val collapsedBarW = collapsedBarWidth * (0.7f + 0.45f * focus) + collapsedBarHeight
                    val inkAlpha = (0.18f + 0.72f * focus) * exit
                    val barColor = if (index in bookmarkedBarIndices) {
                        accents.bookmarkRibbon.copy(alpha = (0.55f + 0.35f * focus) * exit)
                    } else {
                        onSurface.copy(alpha = inkAlpha)
                    }
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(
                            rectLeft(
                                collapsedX - collapsedBarHeight - (1f - exit) * 4.dp.toPx(),
                                collapsedBarW,
                            ),
                            y - collapsedBarHeight / 2f,
                        ),
                        size = Size(collapsedBarW, collapsedBarHeight),
                        cornerRadius = collapsedCorner,
                    )
                }
            }

            if (expand > 0.01f) {
                // Flush with the screen edge, matching the collapsed stack.
                val wheelX = 0f
                val tickSpacing = 14.dp.toPx()
                val focusRadius = (ceil(size.height / tickSpacing / 2f).toInt() + 2)
                    .coerceAtLeast(8)
                // The focal point rides the rail like a scrollbar thumb: its
                // height mirrors the dark bar's relative position in the
                // surah. On open it blooms out of the dark bar itself, then
                // glides to (and drags along) that proportional position.
                val anchorMargin = 72.dp.toPx()
                val anchorTravel = (size.height - 2f * anchorMargin).coerceAtLeast(0f)
                val dialFraction = if (ayahCount > 1) {
                    ((selectedPosition - 1f) / (ayahCount - 1f)).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
                val collapsedActiveY = centerY +
                    (collapsedActivePosition - (collapsedBarsCount - 1) / 2f) * collapsedStep
                val restingAnchorY = anchorMargin + dialFraction * anchorTravel
                val anchorY = collapsedActiveY + (restingAnchorY - collapsedActiveY) * expand
                val first = (selectedPosition - anchorY / tickSpacing).toInt()
                    .coerceAtLeast(1)
                val last = (selectedPosition + (size.height - anchorY) / tickSpacing + 1f).toInt()
                    .coerceAtMost(ayahCount)
                val verticalFade = 82.dp.toPx()
                val minBarLength = 8.dp.toPx()
                val maxBarLength = 44.dp.toPx()
                val minBarThickness = 2.dp.toPx()
                val maxBarThickness = 4.dp.toPx()
                val holdProgress = commitProgress.value

                for (ayah in first..last) {
                    val offset = ayah - selectedPosition
                    val y = anchorY + offset * tickSpacing
                    val distance = (abs(offset) / focusRadius).coerceIn(0f, 1f)
                    val focus = 1f - distance
                    val major = ayah == 1 || ayah == ayahCount || ayah % 5 == 0
                    // Bloom outward from the focal tick on open, retract on close.
                    val arrival = ((expand - distance * 0.3f) / 0.7f).coerceIn(0f, 1f)
                    if (arrival <= 0.01f) continue
                    val edgeFade = (min(y, size.height - y) / verticalFade).coerceIn(0f, 1f)
                    // Ticks scrolling in from either end grow out of the edge
                    // rather than popping in at full length; the focal tick is
                    // exempt so it stays tallest even near the rail's ends.
                    val grow = arrival * (0.35f + 0.65f * maxOf(edgeFade, focus * focus))
                    val length = (
                        minBarLength +
                            (maxBarLength - minBarLength) * focus * focus +
                            if (major) 6.dp.toPx() else 0f
                        ) * grow
                    val tickThickness = minBarThickness + (maxBarThickness - minBarThickness) * focus
                    val tickCorner = CornerRadius(tickThickness, tickThickness)
                    val alpha = (0.1f + 0.62f * focus) * arrival * edgeFade
                    val isSelected = ayah == selectedAyah
                    val isBookmarked = ayah in bookmarkedAyahs
                    // Start behind the screen edge so the rounded left cap is
                    // hidden and the visible end sits truly flush at x = 0.
                    val tickFullWidth = length + tickThickness
                    // Gold = dial focus; ruby = saved verse; ink = ordinary tick.
                    // Focus wins when a bookmarked ayah is under the finger.
                    drawRoundRect(
                        color = when {
                            isSelected -> accents.gold.copy(alpha = 0.96f * arrival)
                            isBookmarked -> accents.bookmarkRibbon.copy(alpha = (0.55f + 0.35f * focus) * arrival * edgeFade)
                            else -> onSurface.copy(alpha = alpha)
                        },
                        topLeft = Offset(
                            rectLeft(wheelX - tickThickness, tickFullWidth),
                            y - tickThickness / 2f,
                        ),
                        size = Size(tickFullWidth, tickThickness),
                        cornerRadius = tickCorner,
                    )
                    if (isSelected && holdProgress > 0f) {
                        // Grace countdown: the selected yellow bar fills from
                        // the edge cap before the ayah jump commits, tinted with
                        // the same theme onSurface ink as the collapsed
                        // highlight (light grey on dark, dark grey on paper).
                        val holdWidth = tickFullWidth * holdProgress
                        drawRoundRect(
                            color = onSurface.copy(alpha = 0.82f * arrival),
                            topLeft = Offset(
                                rectLeft(wheelX - tickThickness, holdWidth),
                                y - tickThickness / 2f,
                            ),
                            size = Size(holdWidth, tickThickness),
                            cornerRadius = tickCorner,
                        )
                    }
                    numberPaint.color = when {
                        isSelected -> accents.gold.copy(alpha = 0.95f * arrival).toArgb()
                        isBookmarked -> accents.bookmarkRibbon
                            .copy(alpha = (0.4f + 0.5f * focus) * arrival * edgeFade)
                            .toArgb()
                        else -> onSurface
                            .copy(alpha = (0.18f + 0.46f * focus) * arrival * edgeFade)
                            .toArgb()
                    }
                    numberPaint.textSize = if (isSelected) 11.sp.toPx() else 8.5.sp.toPx()
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            ayah.toString(),
                            textAnchor(wheelX + length + 6.dp.toPx()),
                            y + numberPaint.textSize * 0.34f,
                            numberPaint,
                        )
                    }
                    // Mushaf page boundary: a tiny diamond and the page number
                    // trail the ayah number in the faintest ink — optional
                    // wayfinding metadata, quiet beside the tick's own label.
                    // Distances stay in from-edge units so mirroring just works.
                    val pageStart = pageStarts[ayah]
                    if (pageStart != null) {
                        val pageInk = onSurface.copy(
                            alpha = (0.14f + 0.2f * focus) * arrival * edgeFade,
                        )
                        val numberWidth = numberPaint.measureText(ayah.toString())
                        val markHalf = 2.dp.toPx()
                        val markCenter = wheelX + length + 6.dp.toPx() +
                            numberWidth + 6.dp.toPx() + markHalf
                        withTransform({
                            rotate(45f, Offset(textAnchor(markCenter), y))
                        }) {
                            drawRoundRect(
                                color = pageInk,
                                topLeft = Offset(textAnchor(markCenter) - markHalf, y - markHalf),
                                size = Size(markHalf * 2f, markHalf * 2f),
                                cornerRadius = CornerRadius(markHalf / 2f),
                            )
                        }
                        pagePaint.color = pageInk.toArgb()
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(
                                pageStart.toString(),
                                textAnchor(markCenter + markHalf + 3.dp.toPx()),
                                y + numberPaint.textSize * 0.34f,
                                pagePaint,
                            )
                        }
                    }
                }
            }
        }

        if (expanded || interactive) {
            // Collapsed: hit only the visual dash stack (vertically centered on
            // the edge). Expanded: full height so the scrub wheel stays under
            // the finger after the bloom.
            val collapsedHitHeight = collapsedRailHitHeightDp(ayahCount).dp
            Box(
                Modifier
                    .align(if (mirrored) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft)
                    .then(
                        if (expanded) Modifier.fillMaxHeight() else Modifier.height(collapsedHitHeight),
                    )
                    .width(if (expanded) 92.dp else 44.dp)
                    .pointerInput(ayahCount) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Invisible chrome (recitation follow mode) must not
                            // hijack page touches into a ghost selector.
                            if (chromeAlpha() < 0.1f) return@awaitEachGesture
                            val tickSpacingPx = 14.dp.toPx()
                            val velocityTracker = VelocityTracker()
                            var dragged = false
                            releaseJob?.cancel()
                            releaseJob = null
                            pendingCommitAyah = null
                            scope.launch { commitProgress.snapTo(0f) }
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            if (!expanded) {
                                lastHapticAyah = currentAyah.value.coerceIn(1, ayahCount)
                                dialPosition = currentPosition.value.coerceIn(1f, ayahCount.toFloat())
                                expanded = true
                            }
                            scope.launch {
                                expansion.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 340f))
                            }
                            down.consume()

                            // Band the accumulated finger position once per frame;
                            // re-banding an already-banded value compounds the curve
                            // and made overscroll feel erratic.
                            var rawPosition = dialPosition
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val deltaAyah = -change.positionChange().y / tickSpacingPx
                                if (abs(deltaAyah) > 0.001f) {
                                    dragged = true
                                    rawPosition += deltaAyah
                                    dialPosition = rubberBandDialPosition(
                                        rawPosition,
                                        1f,
                                        ayahCount.toFloat(),
                                    )
                                }
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                            // Every release schedules the settle + grace countdown, so
                            // an opened wheel always resolves instead of lingering.
                            // A no-move tap settles back onto the current ayah and the
                            // commit becomes a no-op.
                            val velocityAyah = if (dragged) {
                                -velocityTracker.calculateVelocity().y / tickSpacingPx
                            } else {
                                0f
                            }
                            scheduleReleaseCommit(
                                start = dialPosition.coerceIn(1f, ayahCount.toFloat()),
                                velocity = velocityAyah,
                            )
                        }
                    },
            )
        }
    }
}
