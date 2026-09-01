package com.beautifulquran.ui.reader

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.absorbPointerEvents
import com.beautifulquran.ui.theme.quietClickable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/*
 * A verse's own bookmark ribbon — ink that belongs to the ayah block, not a
 * floating overlay. Lives in the block's outer margin (opposite the ayah
 * selector). Idle: just the swallowtail tip of the ribbon, soft and quiet.
 * Parked place: a full green ribbon from the previous reading session.
 * Saved: the ruby strip down the block, stopping short of the next verse's tip.
 * Tap the margin to mark / unmark.
 *
 * Unfurl is a gravity drop with a traveling cloth wave, a soft overshoot, and
 * a settling flutter. Retract gathers the strip back into the tip.
 */

/** Wide enough to sit in the ayah block's 28.dp outer margin and stay tappable. */
internal val BookmarkStripWidth = 44.dp

internal const val BookmarkEdgeInsetDp = 8f    // from the block's outer edge
private const val RIBBON_WIDTH_DP = 11f
internal const val BookmarkTopInsetDp = 24f    // align the tip with the verse's first ink line
private const val NUB_LENGTH_DP = 14f   // just the swallowtail tip peeking out
private const val TOP_FOLD_DP = 3.5f    // soft fold over the page edge, matching web
private const val BOTTOM_GAP_DP = 48f   // leave air above the next verse's tip
private const val NOTCH_DP = 5.5f
private const val WAVE_AMP_DP = 4.5f    // cloth sway while unfurling
private const val SETTLE_AMP_DP = 3.2f  // final flutter amplitude
private const val NUB_STROKE_DP = 1.25f // idle outline: affordance, not a mark
private const val RIBBON_GAP_DP = 3f     // two physical ribbons, never one overpainted strip
private const val VERSE_PLACE_EDGE_SHIFT_DP = 4f
private const val PLACE_RIBBON_WIDTH_RATIO = 0.72f // passive marker, quieter than tappable ruby
private const val OVERSHOOT = 0.06f     // tip past the resting length, then spring back
private const val SOLID_ALPHA = 0.92f
private const val IDLE_NUB_ALPHA = 0.4f // quiet affordance when just the tail is showing

/** Reader reserves green's screen-edge lane; Home keeps its original inset. */
internal fun bookmarkRibbonInsetDp(
    reservePlaceLane: Boolean,
    edgeInsetDp: Float,
    ribbonWidthDp: Float,
): Float = edgeInsetDp +
    if (reservePlaceLane) placeRibbonWidthDp(ribbonWidthDp) + RIBBON_GAP_DP else 0f

internal fun placeRibbonInsetDp(reservePlaceLane: Boolean, edgeInsetDp: Float): Float =
    (edgeInsetDp - if (reservePlaceLane) VERSE_PLACE_EDGE_SHIFT_DP else 0f).coerceAtLeast(0f)

internal fun placeRibbonWidthDp(ribbonWidthDp: Float): Float =
    ribbonWidthDp * PLACE_RIBBON_WIDTH_RATIO

internal fun placeRibbonTapGuardWidthDp(placeMarked: Boolean, ribbonWidthDp: Float): Float =
    if (placeMarked) placeRibbonWidthDp(ribbonWidthDp) else 0f

/** A completion only consumes the animation generation it actually presented. */
internal fun remainingUnfurlSignal(current: Int, consumed: Int): Int =
    if (current == consumed) 0 else current

/** Gravity spill: slow peel, then accelerates, eases as length runs out. */
private val UnfurlEasing = CubicBezierEasing(0.45f, 0.02f, 0.22f, 1f)

/** Gathering roll-up: starts quick, then softens into the tip. */
private val RetractEasing = CubicBezierEasing(0.55f, 0.05f, 0.35f, 1f)

/**
 * The bookmark ribbon drawn inside a single [AyahBlock]. [side] is the edge
 * opposite the ayah selector. [chromeAlpha] / [interactive] follow the same
 * chrome rules as the selector (hidden / inert while reciting).
 */
@Composable
internal fun VerseBookmarkRibbon(
    bookmarked: Boolean,
    /** True for the place parked at the end of the previous reading session. */
    placeMarked: Boolean,
    side: AyahSelectorSide,
    chromeAlpha: () -> Float,
    interactive: Boolean,
    onToggle: () -> Boolean,
    /** False on Chapters, where this component draws only the green place cloth. */
    bookmarkTipVisible: Boolean = true,
    /** Optional secondary action for an exposed saved ribbon. */
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** False when the ribbon is navigation or asks before changing state. */
    animateOnTap: Boolean = true,
    /** Non-zero changes replay a saved ruby bookmark's physical unfurl. */
    unfurlSignal: Int = 0,
    /** Non-zero changes replay the passive green place marker's unfurl. */
    placeUnfurlSignal: Int = 0,
    /** Acknowledges a completed green unfurl so remounting cannot replay it. */
    onPlaceUnfurlConsumed: (Int) -> Unit = {},
    /** Reader-only paired lanes; Home ribbons retain their original geometry. */
    reservePlaceLane: Boolean = false,
    edgeInset: Dp = BookmarkEdgeInsetDp.dp,
    ribbonWidth: Dp = RIBBON_WIDTH_DP.dp,
    topInset: Dp = BookmarkTopInsetDp.dp,
    bottomGap: Dp = BOTTOM_GAP_DP.dp,
) {
    val mirrored = side == AyahSelectorSide.RIGHT
    val ruby = LocalQuranAccents.current.bookmarkRibbon
    val currentPlaceGreen = MaterialTheme.colorScheme.primary
    // Match the monochrome play/pause icon, not the green interactive accent.
    val playbackInk = MaterialTheme.colorScheme.onSurfaceVariant
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // 0 = retracted tip, 1 = full ribbon. Driven by mark/unmark.
    val unfurl = remember { Animatable(if (bookmarked) 1f else 0f) }
    val placeUnfurl = remember { Animatable(if (placeMarked) 1f else 0f) }
    // Cloth wave / settle flutter (signed; springs to 0).
    val sway = remember { Animatable(0f) }
    var animating by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var stripSize by remember { mutableStateOf(IntSize.Zero) }
    val latestOnToggle by rememberUpdatedState(onToggle)
    val latestOnLongClick by rememberUpdatedState(onLongClick)
    val latestChrome by rememberUpdatedState(chromeAlpha)
    val latestOnPlaceUnfurlConsumed by rememberUpdatedState(onPlaceUnfurlConsumed)

    // External bookmark changes snap without animation — only the tap path
    // below runs the unfurl.
    LaunchedEffect(bookmarked) {
        if (animating) return@LaunchedEffect
        if (bookmarked && unfurl.value < 0.999f) {
            unfurl.snapTo(1f)
            sway.snapTo(0f)
        } else if (!bookmarked && unfurl.value > 0.001f) {
            unfurl.snapTo(0f)
            sway.snapTo(0f)
        }
    }
    LaunchedEffect(placeMarked, placeUnfurlSignal) {
        if (placeMarked && placeUnfurlSignal <= 0 && placeUnfurl.value < 0.999f) {
            placeUnfurl.snapTo(1f)
        } else if (!placeMarked && placeUnfurl.value > 0.001f) {
            placeUnfurl.snapTo(0f)
        }
    }

    fun playUnfurl(blockHeightPx: Float) {
        job?.cancel()
        animating = true
        job = scope.launch {
            sway.snapTo(0f)
            unfurl.snapTo(0f)
            val durationMs = (280f + blockHeightPx * 0.55f).coerceIn(420f, 1400f).toInt()
            launch {
                unfurl.animateTo(1f + OVERSHOOT, tween(durationMs, easing = UnfurlEasing))
                unfurl.animateTo(
                    1f,
                    spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                )
            }
            // One soft flutter after the tip lands — underdamped, then still.
            delay((durationMs * 0.78f).toLong())
            sway.snapTo(1f)
            sway.animateTo(
                0f,
                spring(dampingRatio = 0.32f, stiffness = 220f),
            )
            animating = false
        }
    }

    fun playRetract(blockHeightPx: Float) {
        job?.cancel()
        animating = true
        job = scope.launch {
            val durationMs = (220f + blockHeightPx * 0.4f).coerceIn(320f, 900f).toInt()
            launch {
                unfurl.animateTo(0f, tween(durationMs, easing = RetractEasing))
            }
            sway.snapTo(0.35f)
            sway.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 280f))
            animating = false
        }
    }

    LaunchedEffect(unfurlSignal) {
        if (unfurlSignal <= 0 || !bookmarked) return@LaunchedEffect
        // Let the returning Home sheet finish its first measure so duration
        // and cloth travel use the ribbon's real full-page height.
        delay(16)
        val height = stripSize.height.toFloat().coerceAtLeast(1f)
        playUnfurl(height)
    }

    LaunchedEffect(placeUnfurlSignal, placeMarked) {
        if (placeUnfurlSignal <= 0 || !placeMarked) return@LaunchedEffect
        placeUnfurl.snapTo(0f)
        delay(16)
        val height = stripSize.height.toFloat().coerceAtLeast(1f)
        val durationMs = (280f + height * 0.55f).coerceIn(420f, 1400f).toInt()
        placeUnfurl.animateTo(1f + OVERSHOOT, tween(durationMs, easing = UnfurlEasing))
        placeUnfurl.animateTo(
            1f,
            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        )
        latestOnPlaceUnfurlConsumed(placeUnfurlSignal)
    }

    // Ruby owns the wide gutter target; the passive green cloth places a child
    // guard over only its own pixels below so those touches never reach ruby.
    val tapModifier = if (interactive) {
        Modifier.quietClickable(
            role = Role.Button,
            onLongClick = onLongClick?.let {
                {
                    if (latestChrome() >= 0.1f) latestOnLongClick?.invoke()
                }
            },
            onClick = {
                if (latestChrome() >= 0.1f) {
                    if (!animateOnTap) {
                        job?.cancel()
                        animating = false
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            unfurl.snapTo(1f)
                            sway.snapTo(0f)
                        }
                        latestOnToggle()
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    } else {
                        job?.cancel()
                        animating = true
                        val nowMarked = latestOnToggle()
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        val h = stripSize.height.toFloat().coerceAtLeast(1f)
                        if (nowMarked) playUnfurl(h) else playRetract(h)
                    }
                }
            },
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .width(BookmarkStripWidth)
            .onSizeChanged { stripSize = it }
            .then(tapModifier),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Read chrome in the draw scope so LazyColumn items invalidate
            // every fade frame (parent State updates must be observed here).
            val chrome = chromeAlpha().coerceIn(0f, 1f)
            if (chrome <= 0.001f) return@Canvas

            val h = size.height
            if (h <= 0f) return@Canvas
            val edgeInsetPx = edgeInset.toPx()
            val ribbonW = ribbonWidth.toPx()
            val topInsetPx = topInset.toPx()
            val nubLen = NUB_LENGTH_DP.dp.toPx()
            val topFold = TOP_FOLD_DP.dp.toPx()
            val bottomGapPx = bottomGap.toPx()
            val notch = NOTCH_DP.dp.toPx()
            val waveAmp = WAVE_AMP_DP.dp.toPx()
            val settleAmp = SETTLE_AMP_DP.dp.toPx()
            val nubStroke = NUB_STROKE_DP.dp.toPx()
            // Resting full length stops short of the block bottom so consecutive
            // saved ribbons (and the next verse's idle tip) never kiss.
            val retractedTipY = topInsetPx + nubLen
            val fullLen = (h - bottomGapPx).coerceAtLeast(retractedTipY)

            fun ax(logicalX: Float): Float =
                if (mirrored) size.width - logicalX else logicalX

            val progress = unfurl.value.coerceAtLeast(0f)
            val tipY = if (progress <= 0.001f) {
                retractedTipY
            } else {
                val travel = (fullLen - retractedTipY).coerceAtLeast(1f)
                (retractedTipY + travel * progress).coerceAtMost(fullLen * 1.08f)
            }
            val showingRibbon = progress > 0.02f || bookmarked
            val alpha = chrome * when {
                showingRibbon && progress > 0.5f -> SOLID_ALPHA
                showingRibbon -> SOLID_ALPHA * (0.55f + 0.45f * progress.coerceIn(0f, 1f))
                // The inactive outline uses the monochrome playback ink, faded
                // to a quiet affordance. Ruby remains exclusive to saved bookmarks.
                else -> IDLE_NUB_ALPHA
            }

            // Cloth wave while unfurling; settle flutter once the tip lands.
            val wavePhase = progress * PI.toFloat() * 2.4f
            val liveWave = if (animating && progress in 0.05f..0.98f) {
                (1f - progress) * 0.85f + 0.15f
            } else {
                0f
            }
            val settle = sway.value

            fun lateral(y: Float): Float {
                val t = (y / h.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val tipWeight = t * t
                val cloth = sin(wavePhase - t * PI.toFloat() * 3.2f) * waveAmp * liveWave * tipWeight
                val flutter = sin(t * PI.toFloat() * 2.5f + settle * 1.2f) *
                    settleAmp * settle * tipWeight
                return cloth + flutter
            }

            // Always a swallowtail tip — idle "nub" is just that tip, short and
            // faded; a saved mark is the same shape grown to the block bottom.
            fun ribbonPath(
                bottom: Float,
                clothMotion: Boolean,
                inset: Float = edgeInsetPx,
                width: Float = ribbonW,
            ) = Path().apply {
                val top = topInsetPx + topFold
                val bot = bottom.coerceAtLeast(topInsetPx + nubLen * 0.6f)
                val span = (bot - top).coerceAtLeast(1f)
                val notchDepth = minOf(notch, span * 0.45f)
                val steps = (span / 3f).toInt().coerceIn(6, 64)
                fun motion(y: Float) = if (clothMotion) lateral(y) else 0f
                val outer = inset
                val inner = inset + width
                val center = inset + width / 2f
                val outerTop = ax(outer + motion(top))
                val innerTop = ax(inner + motion(top))
                moveTo(outerTop, top)
                quadraticTo(outerTop, top - topFold, ax(center), top - topFold)
                quadraticTo(innerTop, top - topFold, innerTop, top)
                for (i in 1..steps) {
                    val y = top + span * (i / steps.toFloat())
                    lineTo(ax(inner + motion(y)), y)
                }
                lineTo(ax(center + motion(bot - notchDepth)), bot - notchDepth)
                for (i in steps downTo 0) {
                    val y = top + span * (i / steps.toFloat())
                    lineTo(ax(outer + motion(y)), y)
                }
                close()
            }
            val bookmarkInset = bookmarkRibbonInsetDp(
                reservePlaceLane = reservePlaceLane,
                edgeInsetDp = edgeInset.value,
                ribbonWidthDp = ribbonWidth.value,
            ).dp.toPx()
            val path = ribbonPath(tipY, clothMotion = true, inset = bookmarkInset)

            val placeProgress = placeUnfurl.value.coerceAtLeast(0f)
            if (placeMarked && placeProgress > 0.001f) {
                val placeWidth = placeRibbonWidthDp(ribbonWidth.value).dp.toPx()
                val fill = Brush.verticalGradient(
                    0f to currentPlaceGreen,
                    0.55f to currentPlaceGreen,
                    1f to currentPlaceGreen.copy(alpha = 0.82f),
                    startY = topInsetPx,
                    endY = fullLen.coerceAtLeast(1f),
                )
                val placeTipY = (
                    retractedTipY +
                        (fullLen - retractedTipY) * placeProgress
                    ).coerceAtMost(fullLen * 1.08f)
                drawPath(
                    path = ribbonPath(
                        bottom = placeTipY,
                        clothMotion = false,
                        inset = placeRibbonInsetDp(
                            reservePlaceLane = reservePlaceLane,
                            edgeInsetDp = edgeInset.value,
                        ).dp.toPx(),
                        width = placeWidth,
                    ),
                    brush = fill,
                    alpha = chrome * SOLID_ALPHA,
                )
            }

            if (showingRibbon) {
                val fill = Brush.verticalGradient(
                    0f to ruby,
                    0.55f to ruby,
                    1f to ruby.copy(alpha = 0.82f),
                    startY = topInsetPx,
                    endY = tipY.coerceAtLeast(1f),
                )
                drawPath(path, fill, alpha = alpha)
            } else if (bookmarkTipVisible) {
                // An unmarked verse gets an empty ribbon silhouette. Ruby fill
                // is reserved for the reader's saved marks.
                drawPath(
                    path = path,
                    color = playbackInk,
                    alpha = alpha,
                    style = Stroke(
                        width = nubStroke,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
        val placeTapGuardWidth = placeRibbonTapGuardWidthDp(
            placeMarked = placeMarked,
            ribbonWidthDp = ribbonWidth.value,
        ).dp
        if (interactive && placeTapGuardWidth > 0.dp) {
            val placeInset = placeRibbonInsetDp(
                reservePlaceLane = reservePlaceLane,
                edgeInsetDp = edgeInset.value,
            ).dp
            Box(
                Modifier
                    .align(if (mirrored) Alignment.TopEnd else Alignment.TopStart)
                    .offset(x = if (mirrored) -placeInset else placeInset)
                    .width(placeTapGuardWidth)
                    .fillMaxHeight()
                    .absorbPointerEvents(),
            )
        }
    }
}
