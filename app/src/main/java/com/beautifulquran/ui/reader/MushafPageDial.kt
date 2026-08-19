package com.beautifulquran.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import android.view.HapticFeedbackConstants
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The page dial: the hairline under the leaf, made drivable.
 *
 * The rule has always claimed that it marks a place among 604 leaves. This
 * lets the reader take hold of that claim. A mushaf has no other wayfinding on
 * a leaf — the ayah rail and the bottom bar are both off in mushaf mode — so
 * without this the only way to page 400 is four hundred swipes.
 *
 * The dial is *relative*, not absolute: what the finger buys per dp of travel
 * depends on how fast it is travelling. Slow, and a dp is a fourteenth of a
 * leaf; fast, and it is four leaves. That is the whole trick of a good dial,
 * and it means the thumb necessarily drifts away from the finger — which is
 * correct, and is how every dial of this kind behaves.
 *
 * The magnification is not decoration. The rule reads as a hairline because
 * 604 leaves across a phone's width is half a pixel each; under the finger the
 * comb is drawn at whatever pitch the *current* gain buys, so what the reader
 * sees separating is literally what a dp is worth this instant. Move fast
 * enough and the ticks fall back under a pixel and the line closes up again.
 *
 * Nothing fills, and nothing is gold: the rule is furniture. All drawing
 * happens in one Canvas at draw-phase only, as on the ayah rail.
 */

/** Pages per dp at the slow end: fourteen dp of travel buys one leaf. */
internal const val MUSHAF_DIAL_FINE_GAIN = 1f / 14f

/**
 * Pages per dp at the fast end. A 332 dp stroke — a phone's width less the
 * leaf's margins — crosses about 1300 leaves at this gain, so the whole book
 * is inside one flick with room to spare.
 */
internal const val MUSHAF_DIAL_COARSE_GAIN = 4f

/** Under this speed (dp/s) the dial is at its finest. */
internal const val MUSHAF_DIAL_SLOW_DP_S = 40f

/** Over this speed (dp/s) the dial is at its coarsest. */
internal const val MUSHAF_DIAL_FAST_DP_S = 1400f

/**
 * Time constant of the speed estimate, in seconds.
 *
 * A dial reads speed continuously, so this is an EMA over the pointer's own
 * samples rather than a [androidx.compose.ui.input.pointer.util.VelocityTracker],
 * which is built for the single number a fling needs at release. Seventy
 * milliseconds is about four frames: long enough that one jittery sample does
 * not change the gain, short enough that slowing down is felt immediately.
 */
internal const val MUSHAF_DIAL_SPEED_TAU_S = 0.07f

/** Below this pitch a comb is a smear, so the rule is drawn alone. */
internal const val MUSHAF_DIAL_MIN_PITCH_DP = 0.75f

/** How sharply the comb falls away from the thumb. See [mushafDialLensEnvelope]. */
internal const val MUSHAF_DIAL_LENS_SHAPE = 1.25f

/** The tactile rhythm the dial keeps, whatever the gain: a tick every ~4 dp. */
internal const val MUSHAF_DIAL_HAPTIC_PITCH_DP = 4f

/** Smooth Hermite ramp from 0 at [edge0] to 1 at [edge1]. */
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Pages per dp at this finger [speedDpPerSec].
 *
 * Interpolated geometrically, not linearly: gain spans nearly two orders of
 * magnitude, and a linear ramp between the ends spends almost all of its
 * travel in the coarse half — the dial would feel as though it had two
 * settings with a cliff between them. In log space each equal step of speed
 * multiplies the gain by the same factor, which is what makes the change read
 * as one continuous surface under the hand.
 */
internal fun mushafDialGain(speedDpPerSec: Float): Float {
    val t = smoothstep(MUSHAF_DIAL_SLOW_DP_S, MUSHAF_DIAL_FAST_DP_S, abs(speedDpPerSec))
    return MUSHAF_DIAL_FINE_GAIN *
        (MUSHAF_DIAL_COARSE_GAIN / MUSHAF_DIAL_FINE_GAIN).toDouble().pow(t.toDouble()).toFloat()
}

/**
 * The running speed estimate after a sample [instantDpPerSec] arriving
 * [dtSeconds] after the last one. Frame-rate independent: the weight given to
 * the new sample is set by how long it has been, not by how many samples.
 */
internal fun mushafDialSpeed(previous: Float, instantDpPerSec: Float, dtSeconds: Float): Float {
    if (dtSeconds <= 0f) return previous
    // 1 - e^(-dt/tau), not dt/(dt+tau). The rational form is the cheap
    // approximation and it is *not* frame-rate independent: two 16ms samples
    // move the estimate 7% further than one 32ms sample of the same speed, so
    // the gain would depend on how often the pointer happened to report.
    val alpha = 1f - exp(-dtSeconds / MUSHAF_DIAL_SPEED_TAU_S)
    return previous + (abs(instantDpPerSec) - previous) * alpha
}

/** Screen distance one leaf occupies at this [gain] — the dial's own ruler. */
internal fun mushafDialPitchDp(gain: Float): Float = 1f / gain.coerceAtLeast(1e-4f)

/** Whether individual leaves can still be told apart at this [pitchDp]. */
internal fun mushafDialTicksVisible(pitchDp: Float): Boolean =
    pitchDp >= MUSHAF_DIAL_MIN_PITCH_DP

/**
 * How far the comb stands up [distanceDp] from the thumb, 1 under it and 0 at
 * the lens edge. A cosine, so the magnified stretch dissolves into the rule
 * with no seam either side — a hard-edged window would read as a box.
 *
 * The shoulder is deliberately shallow ([MUSHAF_DIAL_LENS_SHAPE] under 2, where
 * a plain raised cosine would sit). Squared, the fourth tooth out stood a
 * hundredth of its full height and the comb read as three marks and a hairline;
 * the rule is meant to look like the row of fine markers it is, so the teeth
 * stay legible most of the way out and only the last of them fades.
 */
internal fun mushafDialLensEnvelope(distanceDp: Float, lensDp: Float): Float {
    if (lensDp <= 0f) return 0f
    val t = (abs(distanceDp) / lensDp).coerceIn(0f, 1f)
    val c = cos(t * (Math.PI / 2.0)).toFloat().coerceAtLeast(0f)
    return c.toDouble().pow(MUSHAF_DIAL_LENS_SHAPE.toDouble()).toFloat()
}

/**
 * Leaves between haptic ticks at this [pitchDp]. The stride widens as the
 * comb tightens so the hand feels the same cadence — about one tick per
 * [MUSHAF_DIAL_HAPTIC_PITCH_DP] of travel — whether it is crossing single
 * leaves or whole juzʾ. Ticking every leaf at coarse gain would be a buzz.
 */
internal fun mushafDialHapticStride(pitchDp: Float): Int {
    if (pitchDp >= MUSHAF_DIAL_HAPTIC_PITCH_DP) return 1
    return ceil(MUSHAF_DIAL_HAPTIC_PITCH_DP / pitchDp.coerceAtLeast(1e-3f)).toInt().coerceAtLeast(1)
}

/**
 * Where a leaf sits along the rule, 0 at the left edge and 1 at the right.
 * Leaf 1 is at the right and leaf [pageCount] at the left: the book's own
 * direction of travel.
 */
internal fun mushafDialFraction(page: Float, pageCount: Int): Float {
    if (pageCount <= 1) return 0f
    return ((page - 1f) / (pageCount - 1f)).coerceIn(0f, 1f)
}

/**
 * Where the mark for [page] stands when the comb is open, in px from the left
 * of the rule, given the thumb at [thumbXPx] over leaf [at].
 *
 * The comb is set at the *gain's* pitch, not at the rule's own scale. That is
 * the entire readout: a leaf is worth [pitchPx] of hand right now, and the comb
 * says so at actual size — which is why it opens as the drag slows and closes
 * back into the line as it speeds up. Drawing these at their rule positions
 * instead gives a hatch of fixed pitch that never magnifies anything.
 *
 * Leaf numbers grow leftward, as the book does.
 */
internal fun mushafDialTickX(thumbXPx: Float, page: Int, at: Float, pitchPx: Float): Float =
    thumbXPx - (page - at) * pitchPx

/**
 * Where along the rule a [fraction] of the book sits, in px, given the rule is
 * [widthPx] wide and holds [insetPx] of paper back at each end.
 *
 * The inset is not a margin for looks. Both ends of this rule lie inside the
 * system's own back-gesture strip, and a thumb parked there is a thumb the OS
 * takes the press for — the first leaf and the last would be the two you could
 * not grab. So the thumb's travel stops short of the paper's edge while the
 * line itself still runs the full measure.
 */
internal fun mushafDialTrackX(fraction: Float, widthPx: Float, insetPx: Float): Float {
    val inset = insetPx.coerceIn(0f, widthPx / 2f)
    return inset + (widthPx - 2f * inset) * fraction.coerceIn(0f, 1f)
}

/** What the dial writes over the thumb: the leaf's chapter and its first verse. */
internal data class MushafDialLabel(val chapter: String, val ayah: Int)

/** The rule's own band. Ticks stand up inside it; the rule sits near its foot. */
private val MushafDialSlot = 13.dp
/** The rule's line, measured from the top of the band — where it has always sat. */
private val MushafDialRuleY = 11.5.dp
private val MushafDialThumbHeight = 3.dp
/** What the thumb grows to under a hand: enough to read as held, no more. */
private val MushafDialThumbHeldHeight = 4.5.dp
private const val MushafDialThumbAspect = 3.2f
private const val MushafDialRuleWeightPx = 1f
/** The line thickens with the thumb, so the whole rule reads as taken up. */
private const val MushafDialRuleHeldWeightPx = 2f
/** Paper held back at each end, clear of the system's back-gesture strip. */
private val MushafDialEdgeInset = 14.dp
/** How far a tick under the thumb stands off the rule. */
private val MushafDialTick = 9.dp
/** Half-width of the magnified stretch. */
private val MushafDialLens = 64.dp
/** The grab strip: a tap target's worth of paper hung around the rule. */
private val MushafDialTouch = 40.dp
/** How far that strip reaches up into the leaf's tail, clear of the last line. */
private val MushafDialTouchLift = 14.dp
/** Paper between the top of the comb and the foot of the label. */
private val MushafDialHudAir = 2.dp

/**
 * The hairline under the leaf: a rule that separates the page from the
 * transport, carrying a single thumb where the reader has got to in the book —
 * and taking a drag along it as a scrub through the 604.
 *
 * A ribbon marks a place; it does not colour in the pages behind it. So the
 * rule stays one weight end to end and the thumb alone moves along it — it
 * reads the *page*, not the playback, so it answers while leaves are turned as
 * well as while they are recited. Furniture, so ink: gold on the leaf means
 * illumination.
 *
 * At rest this is exactly the line it has always been. The comb exists only
 * under a finger, and it is that same line resolved rather than a control
 * grown on top of it.
 */
@Composable
internal fun MushafPageDial(
    /** The leaf in view, 1-based. Read as a lambda so a page turn does not
     * recompose the reader that hosts this. */
    leafPage: () -> Int,
    pageCount: Int,
    /** Leaves that open a juzʾ: the comb's landmarks. */
    majorPages: Set<Int>,
    pageLabel: (Int) -> MushafDialLabel?,
    onSeekPage: (Int) -> Unit,
    /** Raised while a hand is on the rule. The leaf's folio steps aside for
     * the label, which is naming a page the folio has not reached yet. */
    onScrubbing: (Boolean) -> Unit,
    /** True while the reciter has the leaf: the marker steps back. */
    reciting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pages = pageCount.coerceAtLeast(1)
    val settled = leafPage().coerceIn(1, pages)
    val settledState = rememberUpdatedState(settled)
    val labelOf = rememberUpdatedState(pageLabel)
    val seek = rememberUpdatedState(onSeekPage)
    val reportScrub = rememberUpdatedState(onScrubbing)

    var scrubbing by remember { mutableStateOf(false) }
    val dialPage = remember { mutableFloatStateOf(settled.toFloat()) }
    val dialPitchDp = remember { mutableFloatStateOf(mushafDialPitchDp(MUSHAF_DIAL_FINE_GAIN)) }
    val expand = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    var hudWidthPx by remember { mutableIntStateOf(0) }
    var hudHeightPx by remember { mutableIntStateOf(0) }

    // A ribbon is for finding your place, not for watching. While the page is
    // being recited it fades almost out, and comes back when the reading does —
    // but a hand on the rule brings it straight back, because on a leaf this is
    // the only wayfinding there is and a control you cannot see is no control.
    val thumbInk by animateFloatAsState(
        targetValue = if (reciting && !scrubbing) 0.06f else 0.62f,
        animationSpec = tween(InkEngine.tuning.recessMs, easing = FastOutSlowInEasing),
        label = "mushafThumb",
    )
    val resting by animateFloatAsState(
        targetValue = settled.toFloat(),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "mushafProgress",
    )
    // Rounded, so the label recomposes once per leaf crossed rather than once
    // per frame; its x follows the finger in the layout phase instead.
    // Keyed on the count: the catalog arrives after the first composition, and
    // an unkeyed derivation captured a one-leaf book and clamped the label to
    // page 1 for the rest of the session.
    val hudPage by remember(pages) {
        derivedStateOf { dialPage.floatValue.roundToInt().coerceIn(1, pages) }
    }
    val hud = if (scrubbing) labelOf.value(hudPage) else null

    Box(
        modifier
            .fillMaxWidth()
            .height(MushafDialSlot)
            .onSizeChanged { widthPx = it.width },
    ) {
        Canvas(Modifier.fillMaxWidth().height(MushafDialSlot)) {
            val ruleY = MushafDialRuleY.toPx()
            val at = if (scrubbing) dialPage.floatValue else resting
            val lift = expand.value
            // The line thickens under the hand along its whole length: the
            // reader has taken hold of the rule, not of a knob on it.
            val rule = MushafDialRuleWeightPx +
                (MushafDialRuleHeldWeightPx - MushafDialRuleWeightPx) * lift
            drawRoundRect(
                color = ink.copy(alpha = 0.10f + 0.06f * lift),
                topLeft = Offset(0f, ruleY - rule / 2f),
                size = Size(size.width, rule),
                cornerRadius = CornerRadius(rule, rule),
            )
            val inset = MushafDialEdgeInset.toPx()
            val thumbX = mushafDialTrackX(1f - mushafDialFraction(at, pages), size.width, inset)
            // The comb: one mark per leaf, at the pitch the current gain buys.
            // Standing them up is what shows the reader the granularity — and
            // when the pitch falls under a pixel there is nothing honest to
            // show, so the rule simply stands alone and reads as speed.
            val pitchDp = dialPitchDp.floatValue
            if (lift > 0.004f && mushafDialTicksVisible(pitchDp)) {
                val lensDp = MushafDialLens.value
                val span = lensDp / pitchDp
                val lo = ceil(at - span).toInt().coerceAtLeast(1)
                val hi = floor(at + span).toInt().coerceAtMost(pages)
                val tickMax = MushafDialTick.toPx()
                val headroom = ruleY - 1.5.dp.toPx()
                val pitchPx = pitchDp * density
                for (page in lo..hi) {
                    val x = mushafDialTickX(thumbX, page, at, pitchPx)
                    if (x < -rule || x > size.width + rule) continue
                    val env = mushafDialLensEnvelope((x - thumbX) / density, lensDp)
                    if (env <= 0.004f) continue
                    val major = page in majorPages
                    val length = (tickMax * env * lift * if (major) 1.4f else 1f)
                        .coerceAtMost(headroom)
                    if (length <= 0.4f) continue
                    drawRoundRect(
                        color = ink.copy(
                            alpha = ((if (major) 0.16f else 0.10f) + 0.34f * env) * lift,
                        ),
                        topLeft = Offset(x - rule / 2f, ruleY - length),
                        size = Size(rule, length),
                        cornerRadius = CornerRadius(rule, rule),
                    )
                }
            }
            // The thumb: where in the book this leaf sits. A little thicker than
            // the rule and rounded, so it reads as a marker laid on the line
            // rather than a control fixed to it.
            val thumbH = MushafDialThumbHeight.toPx() +
                (MushafDialThumbHeldHeight - MushafDialThumbHeight).toPx() * lift
            val thumbW = thumbH * MushafDialThumbAspect
            val left = (thumbX - thumbW / 2f).coerceIn(0f, size.width - thumbW)
            drawRoundRect(
                color = ink.copy(alpha = thumbInk),
                topLeft = Offset(left, ruleY - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(thumbH, thumbH),
            )
        }
        if (hud != null) {
            // Type alone, no capsule: on this paper anything with a ground
            // behind it is a card, and the leaf does not carry cards. It takes
            // the transport's own small hand, because it stands on the
            // transport's paper rather than on the leaf.
            Text(
                text = "${hud.chapter}  ${hud.ayah}",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.08.em),
                color = ink.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged {
                        hudWidthPx = it.width
                        hudHeightPx = it.height
                    }
                    .offset {
                        val at = dialPage.floatValue
                        val x = mushafDialTrackX(
                            1f - mushafDialFraction(at, pages),
                            widthPx.toFloat(),
                            MushafDialEdgeInset.toPx(),
                        )
                        val left = (x - hudWidthPx / 2f)
                            .coerceIn(0f, (widthPx - hudWidthPx).coerceAtLeast(0).toFloat())
                        IntOffset(
                            left.roundToInt(),
                            -(hudHeightPx + MushafDialHudAir.toPx() + MushafDialTick.toPx())
                                .roundToInt(),
                        )
                    }
                    .graphicsLayer { alpha = expand.value },
            )
        }
        // The grab strip. It hangs around the rule rather than replacing it:
        // the rule keeps the band it has always had, and the paper the finger
        // needs is borrowed from the leaf's tail above and the air below. It
        // runs the whole measure, so the scrub is picked up anywhere along the
        // line and not only where the thumb happens to be sitting.
        //
        // Held back from the system: the strip reaches both screen edges, and
        // without this a press near either one is read as the back gesture and
        // the reader leaves the book instead of scrubbing it.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .requiredHeight(MushafDialTouch)
                .offset(y = -MushafDialTouchLift)
                .systemGestureExclusion()
                .pointerInput(pages) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (pages <= 1) return@awaitEachGesture
                        down.consume()
                        var lastX = down.position.x
                        var lastTime = down.uptimeMillis
                        var speed = 0f
                        var raw = settledState.value.toFloat()
                        var moved = false
                        var lastHaptic = settledState.value
                        dialPage.floatValue = raw
                        dialPitchDp.floatValue = mushafDialPitchDp(MUSHAF_DIAL_FINE_GAIN)
                        scrubbing = true
                        reportScrub.value(true)
                        scope.launch { expand.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 340f)) }
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val dxPx = change.position.x - lastX
                            val dt = ((change.uptimeMillis - lastTime).coerceAtLeast(0L)) / 1000f
                            lastX = change.position.x
                            lastTime = change.uptimeMillis
                            if (dxPx == 0f) continue
                            moved = true
                            val dxDp = dxPx / density
                            speed = mushafDialSpeed(speed, if (dt > 0f) dxDp / dt else 0f, dt)
                            val gain = mushafDialGain(speed)
                            dialPitchDp.floatValue = mushafDialPitchDp(gain)
                            // Accumulate raw and band once: re-banding an
                            // already-banded value compounds the curve.
                            raw -= dxDp * gain
                            dialPage.floatValue =
                                rubberBandDialPosition(raw, 1f, pages.toFloat())
                            val landed = dialPage.floatValue.roundToInt().coerceIn(1, pages)
                            if (landed != lastHaptic) {
                                val stride = mushafDialHapticStride(dialPitchDp.floatValue)
                                val crossedMajor = crossedMajorPage(lastHaptic, landed, majorPages)
                                if (crossedMajor) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    lastHaptic = landed
                                } else if (abs(landed - lastHaptic) >= stride) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    lastHaptic = landed
                                }
                            }
                        }
                        val landed = dialPage.floatValue.roundToInt().coerceIn(1, pages)
                        // The thumb marks a place; it is not a flywheel. A
                        // release lands where the hand left it — no decay, no
                        // overshoot to read past.
                        scope.launch {
                            expand.animateTo(0f, spring(dampingRatio = 1f, stiffness = 200f))
                        }
                        if (moved && landed != settledState.value) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            seek.value(landed)
                        }
                        dialPage.floatValue = landed.toFloat()
                        scrubbing = false
                        reportScrub.value(false)
                    }
                },
        )
    }
}

/** True when the run from [from] to [to] steps over a leaf that opens a juzʾ. */
private fun crossedMajorPage(from: Int, to: Int, majors: Set<Int>): Boolean {
    if (from == to) return false
    val lo = minOf(from, to) + 1
    val hi = maxOf(from, to)
    if (hi - lo > 64) return majors.any { it in lo..hi }
    for (page in lo..hi) if (page in majors) return true
    return false
}
