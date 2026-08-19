package com.beautifulquran.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.Job
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
 * The rule has always claimed that it marks a place in the book. This lets the
 * reader take hold of that claim. A mushaf has no other wayfinding on a leaf —
 * the ayah rail and the bottom bar are both off in mushaf mode — so without
 * this the only way to page 400 is four hundred swipes.
 *
 * What the dial counts is the *verse*, not the leaf. Counting leaves made the
 * hand's finest possible ask a whole page, which is not a thing anyone wants
 * to arrive at: the label above the thumb has always named a chapter and a
 * verse, and it should be able to mean it. A leaf is worked out at the last
 * moment, when the finger lifts and the pager is told where to go.
 *
 * What the finger buys per dp of travel depends on how fast it is travelling.
 * Slow, and a dp is a fourteenth of a verse; fast, and it is thirty verses —
 * so one sweep of the thumb crosses the whole book and a hand that then slows
 * to a stop settles onto single verses without ever lifting. That is the whole
 * trick of a good dial: the granularity is a readout of the hand's own speed,
 * and the reader changes it by moving differently rather than by choosing a
 * mode.
 *
 * Which leaves the thumb somewhere to be. A gain that is not 1:1 cannot both
 * keep the thumb under the finger and keep it at the leaf's own seat on the
 * rule: the two diverge by construction. It is the *finger* the thumb belongs
 * to. A marker that lags the hand reads as a control that is not listening,
 * and no amount of correctness in what it points at buys that back. So while
 * the hand is down the thumb is simply under it, and the comb slides beneath
 * — which is what a magnifier does, and what the reader is looking at anyway:
 * the leaf is named by the comb and by the label above it, not by where the
 * thumb has got to along a 604-leaf line. On release the comb closes and the
 * thumb glides to the leaf's true seat in the same motion, and the rule is a
 * place-marker again.
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

/** Verses per dp at the slow end: fourteen dp of travel buys one verse. */
internal const val MUSHAF_DIAL_FINE_GAIN = 1f / 14f

/**
 * Verses per dp at the fast end. A 332 dp stroke — a phone's width less the
 * leaf's margins — crosses about ten thousand verses at this gain, against the
 * six thousand the book holds. The margin is the point: a hand sweeping to the
 * left edge must *arrive* at the end of the book rather than stop somewhere in
 * juzʾ 25 and need a second stroke.
 */
internal const val MUSHAF_DIAL_COARSE_GAIN = 30f

/** Under this speed (dp/s) the dial is at its finest. */
internal const val MUSHAF_DIAL_SLOW_DP_S = 40f

/** Over this speed (dp/s) the dial is at its coarsest. */
internal const val MUSHAF_DIAL_FAST_DP_S = 900f

/**
 * How fast the estimate follows a hand that is speeding up, in seconds.
 *
 * A dial reads speed continuously, so this is an EMA over the hand's own
 * travel rather than a [androidx.compose.ui.input.pointer.util.VelocityTracker],
 * which is built for the single number a fling needs at release. Speeding up
 * is answered almost at once: a reader who throws the thumb left wants the
 * book to be crossed on that stroke, and a lag here is a stroke that runs out
 * of screen before it reaches the far end.
 */
internal const val MUSHAF_DIAL_SPEED_RISE_TAU_S = 0.05f

/**
 * How fast the estimate follows a hand that is slowing down, in seconds.
 *
 * Slower than the rise on purpose. A hand crossing the book is not smooth —
 * it stalls for a frame or two mid-stroke — and answering each of those with a
 * zoom to verse level would make the comb pump under the finger. Settling has
 * to be something the reader *does*, held for a moment, not something a rough
 * stroke does by accident.
 */
internal const val MUSHAF_DIAL_SPEED_FALL_TAU_S = 0.18f

/**
 * How far past either end of the book the dial may be dragged, in dp.
 *
 * Measured on screen rather than in verses, because the rubber has to read the
 * same whatever the gain: a fixed number of verses of slack would be invisible
 * at a coarse gain and half a screen of travel at a fine one.
 */
internal const val MUSHAF_DIAL_SLACK_DP = 18f

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
 * Verses per dp at this finger [speedDpPerSec].
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
    val instant = abs(instantDpPerSec)
    val tau =
        if (instant > previous) MUSHAF_DIAL_SPEED_RISE_TAU_S else MUSHAF_DIAL_SPEED_FALL_TAU_S
    // 1 - e^(-dt/tau), not dt/(dt+tau). The rational form is the cheap
    // approximation and it is *not* frame-rate independent: two 16ms samples
    // move the estimate 7% further than one 32ms sample of the same speed, so
    // the gain would depend on how often the pointer happened to report.
    val alpha = 1f - exp(-dtSeconds / tau)
    return previous + (instant - previous) * alpha
}

/**
 * How far past an end the dial may be dragged, in verses, at this [gain].
 *
 * See [MUSHAF_DIAL_SLACK_DP]: this is that screen distance converted into the
 * units the dial actually holds, so the rubber gives the same on the eye at
 * both ends of the gain range.
 */
internal fun mushafDialSlack(gain: Float): Float = MUSHAF_DIAL_SLACK_DP * gain

/** Screen distance one verse occupies at this [gain] — the dial's own ruler. */
internal fun mushafDialPitchDp(gain: Float): Float = 1f / gain.coerceAtLeast(1e-4f)

/** Whether single verses can still be told apart at this [pitchDp]. */
internal fun mushafDialTicksVisible(pitchDp: Float): Boolean =
    pitchDp >= MUSHAF_DIAL_MIN_PITCH_DP

/**
 * The pitch the comb is actually drawn at: [rulePitchDp] when it is shut and
 * [gainPitchDp] when it is fully [open].
 *
 * Shut means the rule's own pitch — the whole book laid across the measure —
 * and not zero. That is what makes the close an honest zoom rather than a
 * fade: at the end of it every tooth stands exactly where the rule would have
 * drawn it, so the comb collapses *into the line* instead of over it.
 *
 * Interpolated geometrically. The two pitches are a factor of twenty-five
 * apart at the fine end, and a straight interpolation spends the whole first
 * half of the motion still looking shut.
 */
internal fun mushafDialZoomedPitchDp(
    rulePitchDp: Float,
    gainPitchDp: Float,
    open: Float,
): Float {
    val shut = rulePitchDp.coerceAtLeast(1e-3f)
    val wide = gainPitchDp.coerceAtLeast(1e-3f)
    return shut * (wide / shut).pow(open.coerceIn(0f, 1f))
}

/**
 * How strongly the comb is drawn as its pitch closes on the line, 1 while the
 * teeth are apart and 0 once they are not.
 *
 * [mushafDialTicksVisible] is a cliff, and a cliff is fine while the pitch is
 * a consequence of the hand's speed — the reader is moving fast and the comb
 * is meant to be gone. It is not fine while the comb is collapsing under its
 * own animation: the teeth would blink out an instant before they met. So the
 * last stretch before the floor is a fade, and the teeth go out exactly as
 * they merge.
 */
internal fun mushafDialCombStrength(pitchDp: Float): Float {
    val lo = MUSHAF_DIAL_MIN_PITCH_DP
    val hi = lo * 2.4f
    return ((pitchDp - lo) / (hi - lo)).coerceIn(0f, 1f)
}

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
 * Verses between haptic ticks at this [pitchDp]. The stride widens as the
 * comb tightens so the hand feels the same cadence — about one tick per
 * [MUSHAF_DIAL_HAPTIC_PITCH_DP] of travel — whether it is crossing single
 * verses or whole chapters. Ticking every verse at coarse gain would be a buzz.
 */
internal fun mushafDialHapticStride(pitchDp: Float): Int {
    if (pitchDp >= MUSHAF_DIAL_HAPTIC_PITCH_DP) return 1
    return ceil(MUSHAF_DIAL_HAPTIC_PITCH_DP / pitchDp.coerceAtLeast(1e-3f)).toInt().coerceAtLeast(1)
}

/**
 * Where a verse sits along the rule, 0 at the left edge and 1 at the right.
 * Verse 1 is at the right and verse [verseCount] at the left: the book's own
 * direction of travel.
 */
internal fun mushafDialFraction(verse: Float, verseCount: Int): Float {
    if (verseCount <= 1) return 0f
    return ((verse - 1f) / (verseCount - 1f)).coerceIn(0f, 1f)
}

/**
 * Where the mark for [verse] stands when the comb is open, in px from the left
 * of the rule, given the thumb at [thumbXPx] over verse [at].
 *
 * The comb is set at the *gain's* pitch, not at the rule's own scale. That is
 * the entire readout: a verse is worth [pitchPx] of hand right now, and the
 * comb says so at actual size — which is why it opens as the drag slows and
 * closes back into the line as it speeds up. Drawing these at their rule
 * positions instead gives a hatch of fixed pitch that never magnifies anything.
 *
 * Verse numbers grow leftward, as the book does.
 */
internal fun mushafDialTickX(thumbXPx: Float, verse: Int, at: Float, pitchPx: Float): Float =
    thumbXPx - (verse - at) * pitchPx

/**
 * Where the thumb may stand when it is following a finger at [xPx], on a rule
 * [widthPx] wide holding [insetPx] back at each end.
 *
 * The same two ends [mushafDialTrackX] respects, for the same reason: the
 * thumb tracks the hand, and a hand can reach into the system's back-gesture
 * strip even though a verse's seat never maps there.
 */
internal fun mushafDialClampToTrack(xPx: Float, widthPx: Float, insetPx: Float): Float {
    val inset = insetPx.coerceIn(0f, widthPx / 2f)
    return xPx.coerceIn(inset, (widthPx - inset).coerceAtLeast(inset))
}

/**
 * Where along the rule a [fraction] of the book sits, in px, given the rule is
 * [widthPx] wide and holds [insetPx] of paper back at each end.
 *
 * The inset is not a margin for looks. Both ends of this rule lie inside the
 * system's own back-gesture strip, and a thumb parked there is a thumb the OS
 * takes the press for — the first verse and the last would be the two you could
 * not grab. So the thumb's travel stops short of the paper's edge while the
 * line itself still runs the full measure.
 */
internal fun mushafDialTrackX(fraction: Float, widthPx: Float, insetPx: Float): Float {
    val inset = insetPx.coerceIn(0f, widthPx / 2f)
    return inset + (widthPx - 2f * inset) * fraction.coerceIn(0f, 1f)
}

/** What the dial writes over the thumb: the verse's chapter and its number. */
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
/** The seat mark's share of the thumb: plainly the same mark, smaller. */
private const val MushafDialSeatWidth = 0.55f
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
    /** The verse in view, 1-based. Read as a lambda so a page turn does not
     * recompose the reader that hosts this. */
    verseAt: () -> Int,
    verseCount: Int,
    /** Verses that open a chapter: the comb's coarse tier. */
    majorVerses: Set<Int>,
    verseLabel: (Int) -> MushafDialLabel?,
    onSeekVerse: (Int) -> Unit,
    /** Raised while a hand is on the rule. The leaf's folio steps aside for
     * the label, which is naming a verse the folio has not reached yet. */
    onScrubbing: (Boolean) -> Unit,
    /** True while the reciter has the leaf: the marker steps back. */
    reciting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val verses = verseCount.coerceAtLeast(1)
    val settled = verseAt().coerceIn(1, verses)
    val settledState = rememberUpdatedState(settled)
    val labelOf = rememberUpdatedState(verseLabel)
    val seek = rememberUpdatedState(onSeekVerse)
    val reportScrub = rememberUpdatedState(onScrubbing)

    var scrubbing by remember { mutableStateOf(false) }
    val dialVerse = remember { mutableFloatStateOf(settled.toFloat()) }
    val dialPitchDp = remember { mutableFloatStateOf(mushafDialPitchDp(MUSHAF_DIAL_FINE_GAIN)) }
    val expand = remember { Animatable(0f) }
    // Where the thumb is drawn, in px along the rule, whenever the hand owns
    // it: the finger's own x during a drag, then the glide home afterwards.
    val handX = remember { mutableFloatStateOf(0f) }
    var handed by remember { mutableStateOf(false) }
    // How far the lens is open, 0 at the rule's own pitch and 1 at the gain's.
    // It is what makes the close read as a zoom out rather than a marker
    // wandering off: the comb collapses into the line it magnified.
    val zoom = remember { Animatable(0f) }
    var glide by remember { mutableStateOf<Job?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }
    var hudWidthPx by remember { mutableIntStateOf(0) }
    // The chapter openings in order, so the coarse tier can be drawn by
    // walking a hundred-odd marks rather than by asking a set about every one
    // of two thousand verses inside the lens.
    val chapterMarks = remember(majorVerses) { majorVerses.toIntArray().sortedArray() }
    // Verses per chapter on average: what the coarse tier's own pitch is, and
    // so whether it has anything left to say.
    val chapterSpan = remember(chapterMarks, verses) {
        if (chapterMarks.isEmpty()) verses.toFloat() else verses.toFloat() / chapterMarks.size
    }
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
    // Driven rather than declared, because a scrub's release has to put this
    // exactly on the leaf it landed on. Left as a tween toward the pager's
    // page, a several-hundred-leaf animation would still be running underneath
    // when the thumb finished gliding home, and the marker would set off again
    // on its own the instant the glide handed back.
    val resting = remember { Animatable(settled.toFloat()) }
    LaunchedEffect(settled) {
        if (resting.value != settled.toFloat()) {
            resting.animateTo(settled.toFloat(), tween(320, easing = FastOutSlowInEasing))
        }
    }
    // Rounded, so the label recomposes once per verse crossed rather than once
    // per frame; its x follows the finger in the layout phase instead.
    // Keyed on the count: the catalog arrives after the first composition, and
    // an unkeyed derivation captured a one-verse book and clamped the label to
    // verse 1 for the rest of the session.
    val hudVerse by remember(verses) {
        derivedStateOf { dialVerse.floatValue.roundToInt().coerceIn(1, verses) }
    }
    // Whether a verse number is worth printing. At a coarse gain the figure
    // turns over by hundreds between frames and is unreadable noise — worse,
    // it invites the reader to aim with it. The label drops to the chapter
    // alone, which at that speed is exactly what they are steering by, and the
    // number comes back as the hand settles. Read off the gain's own pitch
    // rather than the zoom's, so the label does not flicker while the lens is
    // animating open or shut.
    val hudVerseLegible by remember {
        derivedStateOf { mushafDialTicksVisible(dialPitchDp.floatValue) }
    }
    // Kept through the glide home: the label riding the thumb down onto the
    // rule is half of what says the lens is closing, not the leaf changing.
    val hud = if (scrubbing || handed) labelOf.value(hudVerse) else null

    Box(
        modifier
            .fillMaxWidth()
            .height(MushafDialSlot)
            .onSizeChanged { widthPx = it.width },
    ) {
        Canvas(Modifier.fillMaxWidth().height(MushafDialSlot)) {
            val ruleY = MushafDialRuleY.toPx()
            val at = if (scrubbing || handed) dialVerse.floatValue else resting.value
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
            val seatX = mushafDialTrackX(1f - mushafDialFraction(at, verses), size.width, inset)
            // Under the hand while there is one, at the leaf's seat when there
            // is not. See the note at the head of this file for why the thumb
            // follows the finger rather than the page.
            val thumbX = if (scrubbing || handed) handX.floatValue else seatX
            // The rule's own pitch: the whole book across the measure. The comb is
            // interpolated between that and the gain's pitch, so an open lens
            // and a shut one are the same drawing at two magnifications.
            val rulePitchDp = ((size.width - 2f * inset) / density / verses)
                .coerceAtLeast(0.01f)
            val pitchDp =
                mushafDialZoomedPitchDp(rulePitchDp, dialPitchDp.floatValue, zoom.value)
            val comb = lift * mushafDialCombStrength(pitchDp)
            val lensDp = MushafDialLens.value
            val span = lensDp / pitchDp
            val lo = ceil(at - span).toInt().coerceAtLeast(1)
            val hi = floor(at + span).toInt().coerceAtMost(verses)
            val tickMax = MushafDialTick.toPx()
            val headroom = ruleY - 1.5.dp.toPx()
            val pitchPx = pitchDp * density
            // The fine tier: one mark per verse, at the pitch the current gain
            // buys. When the pitch falls under a pixel there is nothing honest
            // to show and these simply go, which is itself the readout — the
            // hand is moving too fast for a verse to mean anything.
            if (comb > 0.004f && mushafDialTicksVisible(pitchDp)) {
                for (verse in lo..hi) {
                    val x = mushafDialTickX(thumbX, verse, at, pitchPx)
                    if (x < -rule || x > size.width + rule) continue
                    val env = mushafDialLensEnvelope((x - thumbX) / density, lensDp)
                    if (env <= 0.004f) continue
                    val length = (tickMax * env * comb).coerceAtMost(headroom)
                    if (length <= 0.4f) continue
                    drawRoundRect(
                        color = ink.copy(alpha = (0.10f + 0.34f * env) * comb),
                        topLeft = Offset(x - rule / 2f, ruleY - length),
                        size = Size(rule, length),
                        cornerRadius = CornerRadius(rule, rule),
                    )
                }
            }
            // The coarse tier: chapter openings, drawn on their own pitch and
            // so still standing long after the verses have closed up. This is
            // what a fast hand is actually steering by — the comb never goes
            // blank, it changes what it is a comb of, and the reader crosses
            // the book by chapters and then slows down into verses without the
            // readout ever having been taken away.
            val chapterComb = lift * mushafDialCombStrength(pitchDp * chapterSpan)
            if (chapterComb > 0.004f) {
                val minGapPx = MUSHAF_DIAL_MIN_PITCH_DP * density
                var previousX = Float.MAX_VALUE
                for (mark in chapterMarks) {
                    if (mark < lo) continue
                    if (mark > hi) break
                    val x = mushafDialTickX(thumbX, mark, at, pitchPx)
                    if (x < -rule || x > size.width + rule) continue
                    // Marks run leftward as the ordinal grows. The last chapters
                    // are three verses long, so at a coarse pitch several of
                    // them land on one pixel; drop the ones that would only
                    // thicken their neighbour.
                    if (previousX - x < minGapPx) continue
                    previousX = x
                    val env = mushafDialLensEnvelope((x - thumbX) / density, lensDp)
                    if (env <= 0.004f) continue
                    val length = (tickMax * 1.4f * env * chapterComb).coerceAtMost(headroom)
                    if (length <= 0.4f) continue
                    drawRoundRect(
                        color = ink.copy(alpha = (0.16f + 0.34f * env) * chapterComb),
                        topLeft = Offset(x - rule / 2f, ruleY - length),
                        size = Size(rule, length),
                        cornerRadius = CornerRadius(rule, rule),
                    )
                }
            }
            // The thumb: where in the book this verse sits. A little thicker than
            // the rule and rounded, so it reads as a marker laid on the line
            // rather than a control fixed to it.
            val thumbH = MushafDialThumbHeight.toPx() +
                (MushafDialThumbHeldHeight - MushafDialThumbHeight).toPx() * lift
            val thumbW = thumbH * MushafDialThumbAspect
            // The seat: this verse's place in the book, kept in view for the
            // whole scrub. Without it the thumb's return has no destination
            // and reads as the marker wandering off on its own; with it the
            // thumb is plainly going home to a mark that was always there.
            // It dissolves as the thumb arrives, being the same mark.
            if (lift > 0.004f) {
                val apart = (abs(thumbX - seatX) / thumbW).coerceIn(0f, 1f)
                val seatAlpha = 0.22f * lift * apart
                if (seatAlpha > 0.004f) {
                    val seatH = MushafDialThumbHeight.toPx()
                    val seatW = seatH * MushafDialThumbAspect * MushafDialSeatWidth
                    drawRoundRect(
                        color = ink.copy(alpha = seatAlpha),
                        topLeft = Offset(
                            (seatX - seatW / 2f).coerceIn(0f, size.width - seatW),
                            ruleY - seatH / 2f,
                        ),
                        size = Size(seatW, seatH),
                        cornerRadius = CornerRadius(seatH, seatH),
                    )
                }
            }
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
                text = if (hudVerseLegible) "${hud.chapter}  ${hud.ayah}" else hud.chapter,
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
                        // Over the thumb, which is over the finger.
                        val left = (handX.floatValue - hudWidthPx / 2f)
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
                .pointerInput(verses) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (verses <= 1) return@awaitEachGesture
                        down.consume()
                        // A glide home may still be running from the last
                        // scrub; the hand takes the thumb back off it.
                        glide?.cancel()
                        handed = false
                        val insetPx = MushafDialEdgeInset.toPx()
                        val widthPxNow = size.width.toFloat()
                        var lastX = down.position.x
                        // What the hand has travelled since the meter last
                        // looked, in dp. The pointer loop fills it; the frame
                        // loop drains it.
                        var pendingDp = 0f
                        var speed = 0f
                        var raw = settledState.value.toFloat()
                        var moved = false
                        var lastHaptic = settledState.value
                        dialVerse.floatValue = raw
                        // The thumb goes to the finger on contact, before any
                        // movement: the reader has taken hold of the rule
                        // here, and the mark belongs where the hand is.
                        handX.floatValue =
                            mushafDialClampToTrack(down.position.x, widthPxNow, insetPx)
                        dialPitchDp.floatValue = mushafDialPitchDp(MUSHAF_DIAL_FINE_GAIN)
                        scrubbing = true
                        reportScrub.value(true)
                        scope.launch { expand.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 340f)) }
                        // The lens opens out of the line rather than arriving
                        // already open: the reader watches the hairline resolve
                        // into its own markers, which is the one moment they
                        // learn what the comb is a magnification of.
                        scope.launch { zoom.animateTo(1f, MushafDialZoomIn) }
                        // The speed meter runs on the frame clock, not on
                        // pointer events.
                        //
                        // A finger that stops moving stops reporting. Read off
                        // the events alone, the estimate simply freezes at
                        // whatever the last motion was — so a hand that swept
                        // the book and then held still to pick a verse would
                        // sit there at chapter granularity forever, waiting for
                        // a sample that is never coming. Ticking every frame,
                        // stillness is a real measurement of zero and the lens
                        // opens under a held thumb, which is the whole of what
                        // "slow down to get finer" has to mean.
                        //
                        // It also owns the advance: one gain per frame applied
                        // to that frame's travel, rather than a gain per
                        // pointer sample, so the mapping does not change with
                        // how chattily the digitiser happens to report.
                        val meter = scope.launch {
                            var last = withFrameNanos { it }
                            while (true) {
                                val now = withFrameNanos { it }
                                val dt = ((now - last) / 1_000_000_000f).coerceIn(1f / 240f, 0.1f)
                                last = now
                                val dxDp = pendingDp
                                pendingDp = 0f
                                speed = mushafDialSpeed(speed, dxDp / dt, dt)
                                val gain = mushafDialGain(speed)
                                dialPitchDp.floatValue = mushafDialPitchDp(gain)
                                if (dxDp == 0f) continue
                                // Accumulate raw and band once: re-banding an
                                // already-banded value compounds the curve.
                                val slack = mushafDialSlack(gain)
                                raw = (raw - dxDp * gain)
                                    .coerceIn(1f - slack, verses + slack)
                                dialVerse.floatValue =
                                    rubberBandDialPosition(raw, 1f, verses.toFloat())
                                val landed = dialVerse.floatValue.roundToInt().coerceIn(1, verses)
                                if (landed != lastHaptic) {
                                    val stride = mushafDialHapticStride(dialPitchDp.floatValue)
                                    val crossedMajor =
                                        crossedMajorVerse(lastHaptic, landed, majorVerses)
                                    if (crossedMajor) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        lastHaptic = landed
                                    } else if (abs(landed - lastHaptic) >= stride) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        lastHaptic = landed
                                    }
                                }
                            }
                        }
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val dxPx = change.position.x - lastX
                            lastX = change.position.x
                            handX.floatValue =
                                mushafDialClampToTrack(change.position.x, widthPxNow, insetPx)
                            if (dxPx == 0f) continue
                            moved = true
                            pendingDp += dxPx / density
                        }
                        meter.cancel()
                        val landed = dialVerse.floatValue.roundToInt().coerceIn(1, verses)
                        // The thumb marks a place; it is not a flywheel. A
                        // release lands where the hand left it — no decay, no
                        // overshoot to read past.
                        // The lift relaxes more slowly than the lens closes,
                        // so the teeth are still standing while they converge
                        // and it is the merge that puts them out, not a fade.
                        scope.launch {
                            expand.animateTo(0f, spring(dampingRatio = 1f, stiffness = 150f))
                        }
                        if (moved && landed != settledState.value) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            seek.value(landed)
                        }
                        dialVerse.floatValue = landed.toFloat()
                        // Hand the thumb back to the rule. It walks from where
                        // the finger left it to the landed verse's own seat
                        // while the comb closes over it, one motion — cutting
                        // it there instead would read as the marker jumping.
                        val seat = mushafDialTrackX(
                            1f - mushafDialFraction(landed.toFloat(), verses),
                            widthPxNow,
                            insetPx,
                        )
                        handed = true
                        scrubbing = false
                        reportScrub.value(false)
                        glide = scope.launch {
                            resting.snapTo(landed.toFloat())
                            // One motion: the teeth rush together onto their
                            // true rule positions while the thumb rides the
                            // shrinking comb down onto the seat. Same spec on
                            // both, so they arrive together.
                            launch { zoom.animateTo(0f, MushafDialZoomOut) }
                            animate(
                                initialValue = handX.floatValue,
                                targetValue = seat,
                                animationSpec = MushafDialZoomOut,
                            ) { value, _ -> handX.floatValue = value }
                            handed = false
                        }
                    }
                },
        )
    }
}

/** The lens opening under a new hand. */
private val MushafDialZoomIn = spring<Float>(dampingRatio = 0.9f, stiffness = 340f)

/** The lens closing on release, and the thumb's ride home on the same spec. */
private val MushafDialZoomOut = spring<Float>(dampingRatio = 1f, stiffness = 220f)

/** True when the run from [from] to [to] steps over a verse that opens a chapter. */
private fun crossedMajorVerse(from: Int, to: Int, majors: Set<Int>): Boolean {
    if (from == to) return false
    val lo = minOf(from, to) + 1
    val hi = maxOf(from, to)
    if (hi - lo > 64) return majors.any { it in lo..hi }
    for (verse in lo..hi) if (verse in majors) return true
    return false
}
