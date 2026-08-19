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
import androidx.compose.ui.util.lerp
import android.view.HapticFeedbackConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/*
 * The page dial: the hairline under the leaf, made drivable.
 *
 * The rule has always claimed that it marks a place in the book. This lets the
 * reader take hold of that claim. A mushaf has no other wayfinding on a leaf —
 * the ayah rail and the bottom bar are both off in mushaf mode — so without
 * this the only way to page 400 is four hundred swipes.
 *
 * There are exactly two things anyone ever wants from it. A *chapter*, which
 * is most of the book away and wants one stroke. And *a leaf inside the
 * chapter they have found*, which wants a fingertip. So the dial has two
 * tiers, and — this is the part it took three tries to get right — the tier is
 * not a reading of how fast the hand happens to be moving. Speed decided the
 * granularity once, and it meant the reader had to keep moving fast to stay at
 * chapters and could not slow down to look without the ground changing under
 * them. The tier is now something they *do*: they hold still, and it opens.
 *
 * Chapter tier — the default, at any speed. The whole book is laid across the
 * measure, one leaf to one leaf, so a stroke of the rule end to end is a
 * stroke from al-Fātiḥa to an-Nās, and the comb of chapter openings slides
 * under the thumb exactly as far as the hand moves. The label names the
 * chapter; the haptics tick chapters; a release lands on the chapter's first
 * leaf. Nothing about that changes with speed.
 *
 * Page tier — held open. Stay still for a quarter of a second and the rule
 * gives a click and *swells*: the chapter's own span of hairline stretches out
 * until it is the whole measure, a rounded trough with the chapter's leaves
 * standing in it. Inside the trough the mapping is absolute and it is the
 * chapter's, not the book's: the right end is the chapter's first leaf, the
 * left end its last, and the thumb goes wherever the finger is between them.
 * That is the difference in one line — in the chapter tier the reader moves
 * the comb, and in the page tier they move *within* it.
 *
 * The passage between the two is a true magnification, so nothing jumps. Each
 * leaf's mark flies from where it stands on the book's scale out to where it
 * stands in the trough, and the leaf under the thumb at the moment of the
 * click is the leaf under the thumb when the trough has finished opening — an
 * offset carried at entry and decayed away underneath the animation buys that.
 * Coming back out is the same sentence read backwards.
 *
 * Three things close it: letting go, moving off again at a pace (the reader
 * has finished picking and is travelling), and pressing up against either end
 * of the measure, which is what running out of chapter feels like from inside
 * the trough. All three collapse the trough back into the hairline, which is
 * how the reader is told the ground has changed back.
 *
 * Which leaves the thumb somewhere to be. In the chapter tier a gain that is
 * not 1:1 cannot both keep the thumb under the finger and keep it at the
 * leaf's own seat on the rule; it is the *finger* the thumb belongs to. A
 * marker that lags the hand reads as a control that is not listening, and no
 * amount of correctness in what it points at buys that back. So while the hand
 * is down the thumb is simply under it, and the seat mark — the same mark,
 * smaller — holds the leaf's true place on the rule so the thumb's ride home
 * on release has somewhere to go.
 *
 * Nothing fills, and nothing is gold: the rule is furniture. All drawing
 * happens in one Canvas at draw-phase only, as on the ayah rail.
 */

/**
 * How still a hand has to be, in dp/s, to count as holding.
 *
 * Not zero: a thumb resting on glass drifts a point or two a second, and a
 * dial that demanded a dead stop would be a dial that never opened for the
 * people whose hands shake.
 */
internal const val MUSHAF_DIAL_HOLD_DP_S = 16f

/**
 * How long it has to stay that still, in seconds, before the trough opens.
 *
 * Long enough that the pause at the end of an ordinary stroke does not open
 * it by accident, short enough that "hold on the chapter you want" is one
 * gesture rather than a wait.
 */
internal const val MUSHAF_DIAL_HOLD_S = 0.26f

/**
 * The pace, in dp/s, at which a hand in the trough is plainly travelling
 * again, and the trough gets out of its way.
 *
 * Well above anything fine work produces — picking a leaf out of a chapter is
 * a few dp at a time — and below a real stroke, so the reader never has to
 * flick to escape.
 */
internal const val MUSHAF_DIAL_FLEE_DP_S = 240f

/** How close to an end of the measure counts as pressed up against it, in dp. */
internal const val MUSHAF_DIAL_EDGE_DP = 12f

/** How long pressed against an end before the trough gives way, in seconds. */
internal const val MUSHAF_DIAL_EDGE_S = 0.12f

/**
 * How fast the estimate follows a hand that is speeding up, in seconds.
 *
 * A dial reads speed continuously, so this is an EMA over the hand's own
 * travel rather than a [androidx.compose.ui.input.pointer.util.VelocityTracker],
 * which is built for the single number a fling needs at release. Speeding up
 * is answered almost at once: the reader who sets off again out of the trough
 * wants the book back under their hand on that stroke, not after it.
 */
internal const val MUSHAF_DIAL_SPEED_RISE_TAU_S = 0.05f

/**
 * How fast the estimate follows a hand that is slowing down, in seconds.
 *
 * Slower than the rise, but not by much. It only has to outlast the jitter of
 * a hand coming to rest — the quarter second of [MUSHAF_DIAL_HOLD_S] is what
 * actually decides that a stop was meant, and a long fall here would only be
 * added to that wait.
 */
internal const val MUSHAF_DIAL_SPEED_FALL_TAU_S = 0.06f

/**
 * How far past either end of the book the dial may be dragged, in dp.
 *
 * Measured on screen rather than in leaves so the rubber reads the same
 * whatever the scale it is stretching against.
 */
internal const val MUSHAF_DIAL_SLACK_DP = 18f

/** The tactile rhythm the dial keeps: no tick closer than this to the last. */
internal const val MUSHAF_DIAL_HAPTIC_PITCH_DP = 4f

/** Nor closer in time than this, in seconds — under it a tick is a buzz. */
internal const val MUSHAF_DIAL_HAPTIC_MIN_S = 0.045f

/** How fast the entry offset is paid off, in seconds. See [mushafDialTroughPage]. */
internal const val MUSHAF_DIAL_TROUGH_SETTLE_TAU_S = 0.11f

/**
 * Leaves per dp in the chapter tier, on a rule with [trackDp] of usable
 * measure holding [pageCount] leaves.
 *
 * The whole book across the whole measure, and so exactly the rule's own
 * scale: the comb of chapters travels with the finger point for point, and
 * "drag to the left edge" and "arrive at an-Nās" are the same gesture on any
 * width of phone. Derived rather than declared, because that property is a
 * relation between the book and the screen and not a number.
 */
internal fun mushafDialBookGain(pageCount: Int, trackDp: Float): Float {
    if (trackDp <= 0f) return 1f
    return (pageCount - 1).coerceAtLeast(1) / trackDp
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
    // the reading would depend on how often the digitiser happened to report.
    val alpha = 1f - exp(-dtSeconds / tau)
    return previous + (instant - previous) * alpha
}

/**
 * How far past an end the dial may be dragged, in leaves, at this [gain].
 *
 * See [MUSHAF_DIAL_SLACK_DP]: that screen distance converted into the units
 * the dial actually holds.
 */
internal fun mushafDialSlack(gain: Float): Float = MUSHAF_DIAL_SLACK_DP * gain

/**
 * Whether a hand this still, for this long, has asked for the trough.
 *
 * Both conditions, because either alone is wrong: a fast hand accumulates no
 * stillness, and an instant of stillness is what the top of every stroke
 * looks like.
 */
internal fun mushafDialShouldOpen(speedDpPerSec: Float, heldSeconds: Float): Boolean =
    abs(speedDpPerSec) < MUSHAF_DIAL_HOLD_DP_S && heldSeconds >= MUSHAF_DIAL_HOLD_S

/**
 * Whether a hand in the trough has asked to be out of it: moving off at a
 * pace, or pressed up against an end of the measure long enough to mean it.
 *
 * The second is the one that matters. The trough's ends are the chapter's
 * ends, so a finger jammed against the edge is a reader who has run out of
 * chapter — and what they want next is the book back, not more of a chapter
 * that has stopped.
 */
internal fun mushafDialShouldClose(speedDpPerSec: Float, edgeSeconds: Float): Boolean =
    abs(speedDpPerSec) > MUSHAF_DIAL_FLEE_DP_S || edgeSeconds >= MUSHAF_DIAL_EDGE_S

/**
 * Whether a haptic tick is due, given the hand has travelled [travelDp] and
 * [sinceSeconds] have passed since the last one.
 *
 * A crossing is not enough on its own. In the chapter tier the openings are
 * about three dp apart on the book's scale, so a real stroke crosses a
 * hundred of them in a third of a second; ticking each would be a buzz, and a
 * buzz carries no information about anything.
 */
internal fun mushafDialHapticDue(travelDp: Float, sinceSeconds: Float): Boolean =
    abs(travelDp) >= MUSHAF_DIAL_HAPTIC_PITCH_DP && sinceSeconds >= MUSHAF_DIAL_HAPTIC_MIN_S

/**
 * Where a page sits along the rule, 0 at the left edge and 1 at the right.
 * Leaf 1 is at the right and leaf [pageCount] at the left: the book's own
 * direction of travel.
 */
internal fun mushafDialFraction(page: Float, pageCount: Int): Float {
    if (pageCount <= 1) return 0f
    return ((page - 1f) / (pageCount - 1f)).coerceIn(0f, 1f)
}

/**
 * The leaf the trough hands back for a finger at [fraction] of the measure
 * (0 at the left edge, 1 at the right), given the chapter's [run].
 *
 * Absolute, and the chapter's rather than the book's: the right end is the
 * chapter's first leaf and the left end its last. This is what makes the
 * trough a trough — the reader is no longer pushing a comb along, they are
 * picking a place inside one that has been laid out for them, and both ends
 * of the measure mean something they can aim at.
 */
internal fun mushafDialTroughPage(fraction: Float, run: IntRange): Float {
    val t = 1f - fraction.coerceIn(0f, 1f)
    return run.first + t * (run.last - run.first)
}

/**
 * Where the mark for [page] stands, in px from the left of the rule, given the
 * thumb at [thumbXPx] over leaf [at] and [pitchPx] of screen per leaf.
 *
 * The comb is pinned to the thumb rather than to the paper. In the chapter
 * tier that is the whole readout: the hand pushes the book's own scale along,
 * one point of screen to one point, so what the reader sees moving under their
 * thumb is exactly what they are spending.
 *
 * Leaf numbers grow leftward, as the book does.
 */
internal fun mushafDialTickX(thumbXPx: Float, page: Int, at: Float, pitchPx: Float): Float =
    thumbXPx - (page - at) * pitchPx

/**
 * Where the thumb may stand when it is following a finger at [xPx], on a rule
 * [widthPx] wide holding [insetPx] back at each end.
 *
 * The same two ends [mushafDialTrackX] respects, for the same reason: the
 * thumb tracks the hand, and a hand can reach into the system's back-gesture
 * strip even though a leaf's seat never maps there.
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
 * takes the press for — the first leaf and the last would be the two you could
 * not grab. So the thumb's travel stops short of the paper's edge while the
 * line itself still runs the full measure.
 */
internal fun mushafDialTrackX(fraction: Float, widthPx: Float, insetPx: Float): Float {
    val inset = insetPx.coerceIn(0f, widthPx / 2f)
    return inset + (widthPx - 2f * inset) * fraction.coerceIn(0f, 1f)
}

/**
 * How far along the measure a finger at [xPx] is: 0 at the left end, 1 at the
 * right. The inverse of [mushafDialTrackX], and what the trough reads.
 */
internal fun mushafDialTrackFraction(xPx: Float, widthPx: Float, insetPx: Float): Float {
    val inset = insetPx.coerceIn(0f, widthPx / 2f)
    val track = widthPx - 2f * inset
    if (track <= 0f) return 0f
    return ((xPx - inset) / track).coerceIn(0f, 1f)
}

/**
 * What the dial writes over the thumb: the leaf's chapter, and the run of
 * verses the leaf holds.
 *
 * The range is only printed once the trough is open and a single leaf is a
 * thing the hand can aim at — see [mushafDialLabelText].
 */
internal data class MushafDialLabel(val chapter: String, val fromAyah: Int, val toAyah: Int)

/**
 * The label over the thumb, at the granularity the dial is actually working at.
 *
 * Chapter tier, the chapter alone. The reader crossing the book is steering by
 * chapters, and a verse range that turns over between every pair of frames is
 * not something anyone reads — worse, it invites them to aim with it. In the
 * trough the leaf is the target, so the label says which verses are on it.
 */
internal fun mushafDialLabelText(label: MushafDialLabel, zoomed: Boolean): String {
    if (!zoomed) return label.chapter
    val verses =
        if (label.toAyah <= label.fromAyah) "${label.fromAyah}"
        else "${label.fromAyah}–${label.toAyah}"
    return "${label.chapter}  $verses"
}

/**
 * The run of leaves belonging to the chapter that holds leaf [at], given the
 * sorted [marks] that open each chapter and a book of [pageCount] leaves.
 *
 * The cell the trough is a magnification *of*: what the chapter tier lands on,
 * what the bracket draws, and what the two ends of the open trough mean.
 */
internal fun mushafDialChapterRun(marks: IntArray, at: Int, pageCount: Int): IntRange {
    if (marks.isEmpty()) return 1..pageCount.coerceAtLeast(1)
    var start = marks.first()
    var end = pageCount.coerceAtLeast(1)
    for ((index, mark) in marks.withIndex()) {
        if (mark > at) {
            end = mark - 1
            break
        }
        start = mark
        if (index == marks.lastIndex) end = pageCount.coerceAtLeast(1)
    }
    // Chapters share a leaf constantly — one ends and the next opens on the
    // same page — so a run may be a single leaf wide, and must never be empty.
    return start..end.coerceAtLeast(start)
}

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
/** The seat mark's share of the thumb: plainly the same mark, smaller. */
private const val MushafDialSeatWidth = 0.55f
/** A chapter opening in the chapter tier. */
private val MushafDialChapterTick = 5.dp
/** A leaf in the open trough: taller, because now it is the thing being aimed at. */
private val MushafDialPageTick = 7.dp
/** The grab strip: a tap target's worth of paper hung around the rule. */
private val MushafDialTouch = 40.dp
/** How far that strip reaches up into the leaf's tail, clear of the last line. */
private val MushafDialTouchLift = 14.dp
/** Paper between the top of the comb and the foot of the label. */
private val MushafDialHudAir = 2.dp
/** The chapter's span on the book's scale, before it stretches out. */
private val MushafDialBracket = 1.5.dp
/** What that span swells to once it is the whole measure: the trough. */
private val MushafDialTrough = 4.dp
/** The bracket never draws shorter than this, or a one-leaf chapter is a dot. */
private val MushafDialBracketMin = 8.dp

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
 * At rest this is exactly the line it has always been. The comb and the trough
 * exist only under a finger, and they are that same line resolved rather than
 * a control grown on top of it.
 */
@Composable
internal fun MushafPageDial(
    /** The page in view, 1-based. Read as a lambda so a page turn does not
     * recompose the reader that hosts this. */
    pageAt: () -> Int,
    pageCount: Int,
    /** Leaves that open a chapter: the chapter tier's comb, and the trough's ends. */
    chapterPages: Set<Int>,
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
    val settled = pageAt().coerceIn(1, pages)
    val settledState = rememberUpdatedState(settled)
    val labelOf = rememberUpdatedState(pageLabel)
    val seek = rememberUpdatedState(onSeekPage)
    val reportScrub = rememberUpdatedState(onScrubbing)

    var scrubbing by remember { mutableStateOf(false) }
    val dialPage = remember { mutableFloatStateOf(settled.toFloat()) }
    val expand = remember { Animatable(0f) }
    // Where the thumb is drawn, in px along the rule, whenever the hand owns
    // it: the finger's own x during a drag, then the glide home afterwards.
    val handX = remember { mutableFloatStateOf(0f) }
    var handed by remember { mutableStateOf(false) }
    // How far the trough is open: 0 is the chapter tier, where the chapter is
    // a short bracket on the book's scale, and 1 is that bracket stretched
    // across the whole measure with its leaves standing in it.
    val zoom = remember { Animatable(0f) }
    // The same thing as a fact rather than as a frame value, for the label and
    // for the pointer loop. Two recompositions per transition, not per frame.
    var trough by remember { mutableStateOf(false) }
    // Which chapter the trough is holding. Read in the draw phase, so it is
    // written before the animation starts and left alone until the next entry.
    var troughRun by remember { mutableStateOf(1..1) }
    var glide by remember { mutableStateOf<Job?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }
    var hudWidthPx by remember { mutableIntStateOf(0) }
    // The chapter openings in order, so the comb is drawn by walking a
    // hundred-odd marks rather than by asking a set about every leaf on screen.
    val chapterMarks = remember(chapterPages) { chapterPages.toIntArray().sortedArray() }
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
    // Rounded, so the label recomposes once per leaf crossed rather than once
    // per frame; its x follows the finger in the layout phase instead.
    // Keyed on the count: the catalog arrives after the first composition, and
    // an unkeyed derivation captured a one-page book and clamped the label to
    // page 1 for the rest of the session.
    val hudPage by remember(pages) {
        derivedStateOf { dialPage.floatValue.roundToInt().coerceIn(1, pages) }
    }
    // Kept through the glide home: the label riding the thumb down onto the
    // rule is half of what says the trough is closing, not the leaf changing.
    val hud = if (scrubbing || handed) labelOf.value(hudPage) else null

    Box(
        modifier
            .fillMaxWidth()
            .height(MushafDialSlot)
            .onSizeChanged { widthPx = it.width },
    ) {
        Canvas(Modifier.fillMaxWidth().height(MushafDialSlot)) {
            val ruleY = MushafDialRuleY.toPx()
            val at = if (scrubbing || handed) dialPage.floatValue else resting.value
            val lift = expand.value
            val open = zoom.value
            val inset = MushafDialEdgeInset.toPx()
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
            val seatX = mushafDialTrackX(1f - mushafDialFraction(at, pages), size.width, inset)
            // Under the hand while there is one, at the leaf's seat when there
            // is not. See the note at the head of this file for why the thumb
            // follows the finger rather than the page.
            val thumbX = if (scrubbing || handed) handX.floatValue else seatX
            // The book's own scale: the whole 604 across the measure. Both
            // tiers are drawn from it — the chapter tier stands on it, and the
            // trough is what one chapter's worth of it stretches into.
            val bookPitchPx = (size.width - 2f * inset) / (pages - 1).coerceAtLeast(1)
            val run = troughRun
            val runSpan = (run.last - run.first).coerceAtLeast(1)
            val headroom = ruleY - 1.5.dp.toPx()

            // The bracket, and what it becomes. In the chapter tier it is a
            // short thickened span of rule around the chapter the thumb is in,
            // drawn on the book's scale like everything else in that tier.
            // Hold still and it stretches out until it is the whole measure and
            // deep enough to have leaves standing in it: the trough.
            //
            // It is not a fill. It does not run from an end of the rule and it
            // does not grow with progress — at rest it is not there at all. It
            // is one cell of the comb, and then that cell magnified, which is
            // the only way to say "you are inside this chapter now" to a reader
            // whose own finger is covering the line.
            if (lift > 0.004f && chapterMarks.isNotEmpty()) {
                val fromX = mushafDialTickX(thumbX, run.first, at, bookPitchPx)
                val toX = mushafDialTickX(thumbX, run.last, at, bookPitchPx)
                val minW = MushafDialBracketMin.toPx()
                val centre = (fromX + toX) / 2f
                val half = maxOf(abs(fromX - toX), minW) / 2f
                val left = lerp(centre - half, inset, open)
                val right = lerp(centre + half, size.width - inset, open)
                val weight = lerp(MushafDialBracket.toPx(), MushafDialTrough.toPx(), open)
                drawRoundRect(
                    color = ink.copy(alpha = (0.13f + 0.07f * open) * lift),
                    topLeft = Offset(left, ruleY - weight / 2f),
                    size = Size((right - left).coerceAtLeast(weight), weight),
                    cornerRadius = CornerRadius(weight, weight),
                )
            }

            // The chapter tier's comb: one mark per chapter opening, pinned to
            // the thumb on the book's scale, so it slides exactly as far as the
            // hand does. This is what the reader steers by by default, at any
            // speed — it is not taken away for going fast or for going slow.
            val combInk = lift * (1f - open)
            if (combInk > 0.004f) {
                val tick = MushafDialChapterTick.toPx()
                var previousX = Float.MAX_VALUE
                for (mark in chapterMarks) {
                    val x = mushafDialTickX(thumbX, mark, at, bookPitchPx)
                    if (x < -rule || x > size.width + rule) continue
                    // Marks run leftward as the number grows. The short
                    // chapters at the back of the book share leaves, so
                    // several of these land on one pixel; drop the ones that
                    // would only thicken their neighbour.
                    if (previousX - x < rule * 1.5f) continue
                    previousX = x
                    val length = (tick * combInk).coerceAtMost(headroom)
                    if (length <= 0.4f) continue
                    drawRoundRect(
                        color = ink.copy(alpha = 0.34f * combInk),
                        topLeft = Offset(x - rule / 2f, ruleY - length),
                        size = Size(rule, length),
                        cornerRadius = CornerRadius(rule, rule),
                    )
                }
            }

            // The trough's own comb: the chapter's leaves. Each one flies from
            // where it stands on the book's scale out to where it stands in the
            // trough, so the opening is a magnification the eye can follow and
            // not a second drawing fading in over the first.
            if (open > 0.004f && lift > 0.004f) {
                val tick = MushafDialPageTick.toPx()
                val strength = lift * open
                for (page in run.first..run.last) {
                    val bookX = mushafDialTickX(thumbX, page, at, bookPitchPx)
                    val troughX = mushafDialTrackX(
                        1f - (page - run.first).toFloat() / runSpan,
                        size.width,
                        inset,
                    )
                    val x = lerp(bookX, troughX, open)
                    if (x < -rule || x > size.width + rule) continue
                    val length = (tick * strength).coerceAtMost(headroom)
                    if (length <= 0.4f) continue
                    drawRoundRect(
                        color = ink.copy(alpha = 0.30f * strength),
                        topLeft = Offset(x - rule / 2f, ruleY - length),
                        size = Size(rule, length),
                        cornerRadius = CornerRadius(rule, rule),
                    )
                }
            }

            // The seat: this leaf's place in the book, kept in view for the
            // whole scrub. Without it the thumb's return has no destination
            // and reads as the marker wandering off on its own; with it the
            // thumb is plainly going home to a mark that was always there.
            // It dissolves as the thumb arrives, being the same mark — and it
            // stands down inside the trough, where the measure has stopped
            // being the book and its seat would be a mark in another scale.
            if (lift > 0.004f && open < 0.996f) {
                val thumbWNow = (MushafDialThumbHeight.toPx() +
                    (MushafDialThumbHeldHeight - MushafDialThumbHeight).toPx() * lift) *
                    MushafDialThumbAspect
                val apart = (abs(thumbX - seatX) / thumbWNow).coerceIn(0f, 1f)
                val seatAlpha = 0.26f * lift * apart * (1f - open)
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
            // The thumb: where in the book this leaf sits. A little thicker
            // than the rule and rounded, so it reads as a marker laid on the
            // line rather than a control fixed to it.
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
                text = mushafDialLabelText(hud, trough),
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
                            -(hudHeightPx + MushafDialHudAir.toPx() + MushafDialPageTick.toPx())
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
                        // A glide home may still be running from the last
                        // scrub; the hand takes the thumb back off it.
                        glide?.cancel()
                        handed = false
                        val insetPx = MushafDialEdgeInset.toPx()
                        val widthPxNow = size.width.toFloat()
                        val trackDp = (widthPxNow - 2f * insetPx) / density
                        // The chapter tier's scale: the book across the
                        // measure, so the comb travels with the finger.
                        val bookGain = mushafDialBookGain(pages, trackDp)
                        val slack = mushafDialSlack(bookGain)
                        val edgePx = MUSHAF_DIAL_EDGE_DP * density
                        var lastX = down.position.x
                        // What the hand has travelled since the meter last
                        // looked, in dp. The pointer loop fills it; the frame
                        // loop drains it.
                        var pendingDp = 0f
                        var speed = 0f
                        var raw = settledState.value.toFloat()
                        var moved = false
                        var open = false
                        // Carried at the moment the trough opens and paid off
                        // underneath the animation: the difference between the
                        // leaf the reader was on and the leaf the trough's own
                        // absolute scale puts under their finger. Without it
                        // the click would move the book under a still hand.
                        var settleOffset = 0f
                        var heldS = 0f
                        var edgeS = 0f
                        // Set when the trough gives way at an end of the
                        // measure. The finger is still sitting there and still
                        // still, so without this the hold would re-open it on
                        // the very next frame.
                        var edgeShut = false
                        var travelDp = 0f
                        var sinceTickS = 0f
                        var lastChapter = mushafDialChapterRun(
                            chapterMarks,
                            raw.roundToInt().coerceIn(1, pages),
                            pages,
                        ).first
                        var lastPage = raw.roundToInt().coerceIn(1, pages)
                        dialPage.floatValue = raw
                        // The thumb goes to the finger on contact, before any
                        // movement: the reader has taken hold of the rule
                        // here, and the mark belongs where the hand is.
                        handX.floatValue =
                            mushafDialClampToTrack(down.position.x, widthPxNow, insetPx)
                        troughRun = mushafDialChapterRun(chapterMarks, lastPage, pages)
                        scrubbing = true
                        reportScrub.value(true)
                        scope.launch { expand.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 340f)) }
                        // The meter runs on the frame clock, not on pointer
                        // events.
                        //
                        // A finger that stops moving stops reporting. Read off
                        // the events alone, the estimate simply freezes at
                        // whatever the last motion was — and since holding
                        // still is the whole gesture that opens the trough,
                        // the dial would never open at all. Ticking every
                        // frame, stillness is a real measurement of zero.
                        //
                        // It also owns the advance: one scale per frame
                        // applied to that frame's travel, rather than one per
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
                                travelDp += abs(dxDp)
                                sinceTickS += dt
                                val handPx = handX.floatValue
                                if (open) {
                                    // Absolute, inside the chapter: the finger
                                    // names a place between the two ends.
                                    val fraction =
                                        mushafDialTrackFraction(handPx, widthPxNow, insetPx)
                                    val target = mushafDialTroughPage(fraction, troughRun)
                                    settleOffset *= exp(-dt / MUSHAF_DIAL_TROUGH_SETTLE_TAU_S)
                                    raw = (target + settleOffset).coerceIn(
                                        troughRun.first.toFloat(),
                                        troughRun.last.toFloat(),
                                    )
                                    dialPage.floatValue = raw
                                    val atEnd = handPx <= insetPx + edgePx ||
                                        handPx >= widthPxNow - insetPx - edgePx
                                    edgeS = if (atEnd) edgeS + dt else 0f
                                    if (mushafDialShouldClose(speed, edgeS)) {
                                        open = false
                                        trough = false
                                        edgeShut = edgeS >= MUSHAF_DIAL_EDGE_S
                                        edgeS = 0f
                                        heldS = 0f
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        scope.launch { zoom.animateTo(0f, MushafDialZoomOut) }
                                    }
                                    val landed = raw.roundToInt().coerceIn(1, pages)
                                    if (landed != lastPage &&
                                        mushafDialHapticDue(travelDp, sinceTickS)
                                    ) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        lastPage = landed
                                        travelDp = 0f
                                        sinceTickS = 0f
                                    }
                                    continue
                                }
                                // The chapter tier: push the book's own scale
                                // along, one point of screen to one point.
                                if (dxDp != 0f) {
                                    // Accumulate raw and band once: re-banding
                                    // an already-banded value compounds the
                                    // curve.
                                    raw = (raw - dxDp * bookGain)
                                        .coerceIn(1f - slack, pages + slack)
                                    dialPage.floatValue =
                                        rubberBandDialPosition(raw, 1f, pages.toFloat())
                                }
                                val landed = dialPage.floatValue.roundToInt().coerceIn(1, pages)
                                val chapter = mushafDialChapterRun(chapterMarks, landed, pages)
                                if (chapter.first != lastChapter) {
                                    if (mushafDialHapticDue(travelDp, sinceTickS)) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        travelDp = 0f
                                        sinceTickS = 0f
                                    }
                                    lastChapter = chapter.first
                                }
                                lastPage = landed
                                troughRun = chapter
                                // The hold. Both halves are needed: a fast
                                // hand banks no stillness, and an instant of
                                // stillness is what the top of every stroke
                                // looks like.
                                heldS = if (abs(speed) < MUSHAF_DIAL_HOLD_DP_S) heldS + dt else 0f
                                if (edgeShut) {
                                    val atEnd = handPx <= insetPx + 2f * edgePx ||
                                        handPx >= widthPxNow - insetPx - 2f * edgePx
                                    if (!atEnd) edgeShut = false
                                }
                                if (!edgeShut && mushafDialShouldOpen(speed, heldS)) {
                                    // Enter on the leaf the hand is actually
                                    // on, and let the trough's absolute scale
                                    // arrive underneath it.
                                    val fraction =
                                        mushafDialTrackFraction(handPx, widthPxNow, insetPx)
                                    settleOffset =
                                        raw - mushafDialTroughPage(fraction, chapter)
                                    open = true
                                    trough = true
                                    heldS = 0f
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    scope.launch { zoom.animateTo(1f, MushafDialZoomIn) }
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
                        // In the trough the reader has picked a leaf and means
                        // it. In the chapter tier they have picked a chapter,
                        // so the leaf is the one it opens on — that is what
                        // chapter granularity has to mean when the hand comes
                        // off, or the tier was a lie.
                        val here = dialPage.floatValue.roundToInt().coerceIn(1, pages)
                        val landed =
                            if (open) here
                            else mushafDialChapterRun(chapterMarks, here, pages).first
                        // The thumb marks a place; it is not a flywheel. A
                        // release lands where the hand left it — no decay, no
                        // overshoot to read past.
                        trough = false
                        scope.launch {
                            expand.animateTo(0f, spring(dampingRatio = 1f, stiffness = 150f))
                        }
                        if (moved && landed != settledState.value) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            seek.value(landed)
                        }
                        dialPage.floatValue = landed.toFloat()
                        // Hand the thumb back to the rule. It walks from where
                        // the finger left it to the landed leaf's own seat
                        // while the trough closes over it, one motion — cutting
                        // it there instead would read as the marker jumping.
                        val seat = mushafDialTrackX(
                            1f - mushafDialFraction(landed.toFloat(), pages),
                            widthPxNow,
                            insetPx,
                        )
                        handed = true
                        scrubbing = false
                        reportScrub.value(false)
                        glide = scope.launch {
                            resting.snapTo(landed.toFloat())
                            // One motion: the trough shuts back into the line
                            // while the thumb rides down onto the seat. Same
                            // spec on both, so they arrive together.
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

/** The trough swelling open under a held hand. */
private val MushafDialZoomIn = spring<Float>(dampingRatio = 0.9f, stiffness = 340f)

/** The trough shutting, and the thumb's ride home on the same spec. */
private val MushafDialZoomOut = spring<Float>(dampingRatio = 1f, stiffness = 220f)
