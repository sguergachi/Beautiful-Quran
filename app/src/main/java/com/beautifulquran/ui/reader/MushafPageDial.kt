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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
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
import com.beautifulquran.ui.theme.LocalQuranAccents
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
 * Page tier — held open. Stay still for an eighth of a second and the rule
 * gives a click and the bracket *stretches*: the chapter's own capsule opens
 * out until it is the whole measure, a rounded trough with the chapter's
 * leaves standing in it, receding to furniture ink as it goes — a marker
 * becoming a channel. Inside the trough the mapping is absolute and it is the
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
 * Three things close it, and speed is not one of them. A reader working
 * quickly inside a long chapter is doing the tier's own job, and taking the
 * tier away for it made the dial something you had to move gingerly in. The
 * three are all places, not paces.
 *
 * Letting go — the reader has chosen. Carrying the finger off either end of
 * the measure: the chapter's leaves stop short of the rule's ends and leave a
 * *run-out* of bare rule beyond the last of them at each side, and staying out
 * there is running out of chapter. Or coming off the line the finger pressed
 * on at all, up onto the leaf or down into the transport — the *stray*.
 *
 * The last two are weighted differently, because they are not the same kind of
 * boundary. The end of the measure is somewhere the reader legitimately aims:
 * the chapter's last leaf lives right against it, and a sweep down to that leaf
 * overshoots. So going past has to be *held* for a beat to count, and coming
 * back inside clears the count — the overshoot costs nothing. Across the rule
 * there is nothing to aim at, so the stray takes effect at once. Either way the
 * trough collapses back into the hairline, which is how the reader is told the
 * ground has changed back, and the closing stands until the hand *travels*
 * again. Not until it is back somewhere in particular — that was tried, and it
 * was wrong twice over. A thumb sweeping the rule pivots from the wrist and
 * ends a long stroke well off the line it started on, and the chapter tier lays
 * the whole book across the full measure while the run-out is measured against
 * the trough's shorter one, so the first and last forty leaves of the book
 * stand inside the run-out by construction. Guarding on position refused the
 * ordinary hold to most long strokes and to al-Fātiḥa. Guarding on travel asks
 * the right question: a hold that has not moved since the close is the same
 * gesture still going, and a hold after fresh steering is a new request.
 *
 * Which leaves one case: a hand that has not moved and wants back in anyway.
 * Holding still for a second and a half — many ordinary holds over — opens the
 * trough regardless, because a finger that has sat motionless that long is not
 * an accident. A control whose only answer to a held finger is silence reads as
 * broken. Either way of opening takes the line it finds as the line the trough
 * is held by, and forgives the run-out until the hand is back over the measure;
 * otherwise the trough would read its own closing law on the next frame and
 * shut what it had just opened.
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
 * This number has been walked in both directions on the glass. It came down
 * from a quarter second to an eighth, on the reasoning that a finger waiting
 * for a control to answer reads as a control thinking about it — and at an
 * eighth the dial answered the *pause* rather than the hold. A scrub is not
 * one continuous motion: a hand slows to read what it is passing, and every
 * one of those slowings arrived as an instruction. So it goes back up by a
 * full second, past a quarter and well past a stopwatch's idea of nothing.
 *
 * What that buys is that opening the trough is a thing the reader does on
 * purpose. Coming in is deliberate and cheap to leave — the run-out and the
 * stray are still what make an accidental open cost nothing — and the hand
 * that never meant to zoom in simply never does.
 *
 * The speed gate above still holds the other end: a hold is stillness *and*
 * time, so a slow drift never accumulates one no matter how long it lasts.
 */
internal const val MUSHAF_DIAL_HOLD_S = 2.12f

/**
 * How long a hand has to stay still, in seconds, to open the trough from
 * anywhere at all: the insistent hold.
 *
 * The ordinary hold asks where the finger is, because the guard that keeps the
 * trough shut after a reader has walked out of it is the same guard. That is
 * right for the common case and wrong for the reader who has walked out and
 * wants straight back in: they are sitting in the run-out, or off the line,
 * holding perfectly still, and nothing happens. A control whose only answer to
 * a held finger is silence reads as broken, wherever the finger is.
 *
 * So a hold long enough that it cannot be the tail of a stroke is taken as an
 * instruction rather than a position, and it overrides both places. It costs
 * a dozen ordinary holds — long enough that no scrub ever reaches it by
 * accident, short enough to be a pause and not a wait.
 *
 * Opening this way re-anchors the gesture: the line the finger is on becomes
 * the line it pressed on, and the run-out is forgiven until the hand is back
 * over the measure. Otherwise the reader would be handed the trough and have
 * it taken away again in the same fifth of a second, over and over.
 */
internal const val MUSHAF_DIAL_INSIST_S = 2.5f

/**
 * How much bare rule the trough leaves standing past its own last leaf at
 * each end: the run-out.
 *
 * The trough is left by carrying a finger off the end of it, and nothing
 * else. Speed used to do it too, which meant a reader working quickly inside
 * a chapter — the ordinary way to cross a long one — kept being handed back
 * the whole book they had just left. A tier you can fall out of by moving is
 * a tier you have to move gingerly in.
 *
 * So the leaves stop short of the rule's ends and this is the ground beyond
 * them. It has to be wide enough to be aimed at rather than arrived at by
 * accident, and narrow enough that the chapter still gets most of the
 * measure; at a phone's width it is about a thirteenth of the rule at each
 * end. It doubles as the readout: bare rule under the thumb, with the last
 * leaf behind it, is what running out of chapter looks like.
 */
internal val MushafDialRunOut = 26.dp

/**
 * How far off the line the finger pressed on it may drift, in dp, before the
 * trough takes that as leaving too.
 *
 * The run-out is the way out along the rule; this is the way out across it,
 * and it is the shorter one. A reader deep in a long chapter should not have
 * to walk to an end of the measure to be given the book back — lifting away
 * from the line is the same thought, said with the hand they already have on
 * the glass, and it does not cost them the place they were looking at on the
 * way past.
 *
 * Measured from the press, not from the last frame: what is being asked is
 * "have you left the line you took hold of", which is a displacement. A rate
 * would be a speed test again, and a slow deliberate lift away is exactly the
 * gesture that has to work. It sits outside the grab strip's own half-height,
 * so ordinary drift along a scrub — a thumb rolling as the arm extends — is
 * still on the line. It sat at 28 dp and was widened by half again: at that
 * width a long sweep was ending itself under a hand that was still working
 * the rule, and the cost of the two errors is not symmetric — a stray that
 * fires late costs one more frame of scrubbing, a stray that fires early
 * takes the instrument away mid-stroke.
 */
internal val MushafDialStray = 42.dp

/**
 * How long the finger has to stay out in the run-out, in seconds, before the
 * trough actually gives way.
 *
 * The run-out's width is room to aim; this is its resistance. A sweep down the
 * last few leaves of a chapter overshoots the end of the measure — that is
 * what aiming at an end looks like — and the reader who then comes back to the
 * leaf they wanted should find the trough where they left it, not the whole
 * book. So crossing the line starts a clock rather than firing, and coming
 * back inside stops and clears it.
 *
 * Short enough that leaving is one continuous motion and not a wait: by the
 * time a hand that means it has arrived and stopped, this is already spent.
 * It is no longer tied to the hold, which it once matched. Entering and
 * leaving looked like one beat when they were the same number, but they are
 * not the same act: entering should cost as close to nothing as the speed gate
 * allows, while leaving is precisely where a little stickiness is wanted. So
 * the hold has walked down and this has stayed where it was.
 *
 * The stray test does not take it — coming off the line is unambiguous, and
 * making the reader hold a lifted thumb in mid-drift would be resistance
 * against nothing.
 */
internal const val MUSHAF_DIAL_RUNOUT_S = 0.18f

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

/** The tactile rhythm the dial keeps: no tick closer than this to the last. */
internal const val MUSHAF_DIAL_HAPTIC_PITCH_DP = 4f

/** Nor closer in time than this, in seconds — under it a tick is a buzz. */
internal const val MUSHAF_DIAL_HAPTIC_MIN_S = 0.045f

/** How fast the entry offset is paid off, in seconds. See [mushafDialTroughPage]. */
internal const val MUSHAF_DIAL_TROUGH_SETTLE_TAU_S = 0.11f

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
 * Whether a hand this still, for this long, has asked for the trough.
 *
 * Both conditions, because either alone is wrong: a fast hand accumulates no
 * stillness, and an instant of stillness is what the top of every stroke
 * looks like.
 */
internal fun mushafDialShouldOpen(speedDpPerSec: Float, heldSeconds: Float): Boolean =
    abs(speedDpPerSec) < MUSHAF_DIAL_HOLD_DP_S && heldSeconds >= MUSHAF_DIAL_HOLD_S

/**
 * Whether a hand this still, for this much longer, has asked for the trough
 * from wherever it happens to be sitting.
 *
 * The same test as [mushafDialShouldOpen] with a longer clock, and the only
 * one that is not asked about the finger's place. A reader out in the run-out
 * or off the line has been told no by the guard; holding through this says
 * they meant it, and there is nothing else a held finger out there could mean.
 */
internal fun mushafDialInsists(speedDpPerSec: Float, heldSeconds: Float): Boolean =
    abs(speedDpPerSec) < MUSHAF_DIAL_HOLD_DP_S && heldSeconds >= MUSHAF_DIAL_INSIST_S

/**
 * Whether a finger at [xPx] has carried off the end of the trough's measure,
 * which runs from [troughInsetPx] to that far from the other end of a rule
 * [widthPx] wide.
 *
 * The whole close rule, and the whole re-open guard. The trough's ends are the
 * chapter's ends, so a finger that has gone past them is a reader who has run
 * out of chapter, and what they want next is the book back. Nothing about how
 * fast they got there enters into it: crossing the last leaf is a place, and a
 * place can be aimed at, held, and backed out of — which is the point of
 * giving it the width of the run-out rather than the edge of the glass.
 */
internal fun mushafDialPastTrough(xPx: Float, widthPx: Float, troughInsetPx: Float): Boolean {
    val inset = troughInsetPx.coerceIn(0f, widthPx / 2f)
    return xPx < inset || xPx > widthPx - inset
}

/**
 * Whether a finger at [yPx] has come off the line it pressed on at
 * [pressYPx], by more than [strayPx].
 *
 * The other half of the same rule [mushafDialPastTrough] states: the trough
 * lives in a place, and a hand that is not in that place is not in the
 * trough. Either direction, because the rule has a leaf above it and the
 * transport below, and reaching for either is the same "I am done here".
 *
 * It reads a displacement from the press rather than a movement between
 * frames on purpose. A rate would be the speed test this replaced wearing a
 * second hat, and would punish the slow deliberate lift away — which is the
 * gesture, not the accident.
 */
internal fun mushafDialStrayed(yPx: Float, pressYPx: Float, strayPx: Float): Boolean =
    abs(yPx - pressYPx) > strayPx

/**
 * The whole close law: whether a hand that has been in the run-out for
 * [runOutSeconds], and is or is not [strayed] off the line, has left.
 *
 * The two ways out are not weighted the same, and that asymmetry is the
 * point. Along the rule, the end of the measure is somewhere the reader
 * legitimately aims — the last leaf of the chapter lives right against it —
 * so going past has to be held to count, and a moment out there on the way to
 * that leaf costs nothing. Across the rule there is nothing to aim at: the
 * line is the instrument, and coming off it can only mean leaving.
 */
internal fun mushafDialShouldLeaveTrough(runOutSeconds: Float, strayed: Boolean): Boolean =
    strayed || runOutSeconds >= MUSHAF_DIAL_RUNOUT_S

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
 * What the dial writes over the thumb: the leaf's chapter, by number and by
 * name, and the run of verses the leaf holds.
 *
 * The range is only printed once the trough is open and a single leaf is a
 * thing the hand can aim at — see [mushafDialLabelFoot].
 */
internal data class MushafDialLabel(
    val number: Int,
    val chapter: String,
    val fromAyah: Int,
    val toAyah: Int,
)

/**
 * The line the label always writes, at the granularity the dial is working at.
 *
 * Chapter tier, the chapter and nothing else — but by its number as well as its
 * name, set the way the index sets them, because a reader crossing the book at
 * this speed is counting chapters and the number is the thing they are counting.
 *
 * In the trough the leaf is the target, so the head names the leaf: the chapter
 * it stands in, then its folio. The chapter stays at the front because the
 * trough is a magnification of one, and a folio alone would leave the reader
 * with nothing to check it against.
 */
internal fun mushafDialLabelHead(label: MushafDialLabel, zoomed: Boolean, page: Int): String =
    if (zoomed) "${label.chapter}  ·  pg. $page" else "${label.number}  ${label.chapter}"

/**
 * The line only the trough writes: the verses standing on the leaf.
 *
 * At chapter tier it is empty rather than absent. The label keeps both lines'
 * worth of paper at either tier, so opening the trough writes into the gap the
 * head was already sitting above instead of shoving the head upward — the type
 * over a thumb should not jump when the comb does.
 */
internal fun mushafDialLabelFoot(label: MushafDialLabel, zoomed: Boolean): String {
    if (!zoomed) return ""
    val verses =
        if (label.toAyah <= label.fromAyah) "${label.fromAyah}"
        else "${label.fromAyah}–${label.toAyah}"
    return "Ayah $verses"
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

internal fun mushafDialChapterIndex(marks: IntArray, at: Int, pageCount: Int): Int {
    if (marks.isEmpty()) return 0
    var lo = 0
    var hi = marks.lastIndex
    var found = 0
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (marks[mid] <= at) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/**
 * Fisheye for the chapter comb. True positions stay on the hairline; when
 * the comb is under the finger the neighbourhood is magnified so the
 * bunched marks at the back can be picked between. Farther marks stay
 * shorter and closer to their true place — the magnification is in the
 * lens, not in the book.
 */
internal const val MUSHAF_DIAL_LENS_SIGMA_DP = 24f
internal const val MUSHAF_DIAL_LENS_MAG = 3.2f
internal const val MUSHAF_DIAL_LENS_HEIGHT_GAIN = 2.4f

internal fun mushafDialLensFactor(distPx: Float, sigmaPx: Float, maxMag: Float): Float {
    if (sigmaPx <= 1f) return 1f
    // Gaussian falloff: 1 at infinity, maxMag at the centre.
    val x = distPx / sigmaPx
    return 1f + (maxMag - 1f) * exp(-0.5f * x * x)
}

internal fun mushafDialLensedX(trueX: Float, centerX: Float, sigmaPx: Float, maxMag: Float): Float {
    val d = trueX - centerX
    return centerX + d * mushafDialLensFactor(d, sigmaPx, maxMag)
}

/** Where a page sits on the chapter tier, 0 at leaf 1 and 1 at the far end. */
internal fun mushafDialChapterFraction(page: Float, marks: IntArray, pageCount: Int): Float {
    // True place in the book: leaf 1 at 0, 604 at 1. Magnification is a
    // lens at draw time, not a warped distribution.
    return mushafDialFraction(page, pageCount)
}

/** The inverse: the leaf under a finger at fraction [f] of the chapter tier. */
internal fun mushafDialChapterPage(f: Float, marks: IntArray, pageCount: Int): Float {
    // Inverse of the true distribution above; lens is visual.
    val clamped = f.coerceIn(0f, 1f)
    return 1f + clamped * (pageCount - 1f).coerceAtLeast(0f)
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
internal val MushafDialEdgeInset = 14.dp
/** The seat mark's share of the thumb: plainly the same mark, smaller. */
private const val MushafDialSeatWidth = 0.55f
/** A chapter opening in the chapter tier. */
private val MushafDialChapterTick = 5.dp
/** A leaf in the open trough: taller, because now it is the thing being aimed at. */
private val MushafDialPageTick = 7.dp
/**
 * Grab paper below the rule. The strip lives inside the dial's own bounds:
 * hung off them with requiredHeight+offset it was never hit-tested at all —
 * Compose reaches a child only through its parent's geometry — so the comb
 * has had no hold in it since the frameless rebuild. The rule keeps its
 * 13dp band above; the hand grabs from beneath it, where it already is.
 */
internal val MushafDialBelowGrab = 44.dp
/** Paper between the top of the comb and the foot of the label. */
private val MushafDialHudAir = 2.dp
/**
 * The bracket's weight, closed and open alike — the held thumb's own.
 *
 * One constant, because the bracket and the trough are one mark. It used to be
 * a hairline that fattened as it stretched, on the reasoning that the trough
 * had to be deep enough to stand leaves in; but at the chapter tier the
 * bracket *is* the marker, the only thing the reader has under their finger,
 * and a hairline is not a thing you hold. Made the seat mark's weight it reads
 * as the same mark it is, and stretching is then the whole of what opening
 * does to it. The character still changes at the click, in ink rather than in
 * depth: a solid marker becomes a channel with leaves standing in it.
 */
private val MushafDialBracket = MushafDialThumbHeldHeight
/**
 * The bracket never draws shorter than this, or a one-leaf chapter is a dot.
 *
 * The seat mark's own length: a short chapter's cell is that mark exactly, not
 * a stub of something else.
 */
private val MushafDialBracketMin = MushafDialThumbHeldHeight * MushafDialThumbAspect

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
    val accents = LocalQuranAccents.current
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
    // Orange pulse 500ms before the trough pops — 300ms flash + 200ms breather.
    val pulse = remember { Animatable(0f) }
    var hasPulsed by remember { mutableStateOf(false) }
    // Where the thumb is drawn, in px along the rule, whenever the hand owns
    // it: the finger's own x during a drag, then the glide home afterwards.
    val handX = remember { mutableFloatStateOf(0f) }
    var handed by remember { mutableStateOf(false) }
    // How far the trough is open: 0 is the chapter tier, where the chapter is
    // a short bracket on the book's scale, and 1 is that bracket stretched
    // across the whole measure with its leaves standing in it.
    val zoom = remember { Animatable(0f) }
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
    // being recited it steps back rather than vanishing — still findable under
    // a thumb, because this is the only wayfinding the leaf has and a control
    // you cannot see is no control. A hand on the rule brings it fully back.
    val thumbInk by animateFloatAsState(
        targetValue = if (reciting && !scrubbing) 0.22f else 0.62f,
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
            .height(MushafDialSlot + MushafDialBelowGrab)
            .onSizeChanged { widthPx = it.width },
    ) {
        Canvas(Modifier.fillMaxWidth().height(MushafDialSlot)) {
            val ruleY = MushafDialRuleY.toPx()
            val at = if (scrubbing || handed) dialPage.floatValue else resting.value
            val lift = expand.value
            val open = zoom.value
            val inset = MushafDialEdgeInset.toPx()
            // The trough's own measure is held further back still. What is
            // left standing beyond its last leaf at each end is the run-out:
            // the ground the reader crosses to hand the book back. The chapter
            // tier's comb does not use it — the book has to reach both ends of
            // the rule or the far left stops meaning the back of the book.
            val troughInset = inset + MushafDialRunOut.toPx()
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
            // The book's own scale: leaf 1 at the right end of the measure,
            // leaf 604 at the left, nailed to the rule. Both tiers are drawn
            // from it — the chapter comb stands on it, and the trough is what
            // one chapter's worth of it stretches into.
            //
            // Nailed, not carried under the thumb: a comb that travels with
            // the hand runs off the end of its own measure, and the reader is
            // left looking at bare rule beyond the last chapter with no way to
            // tell whether that is the end of the book or the end of the comb.
            // The book does not move. The reader moves along it.
            fun bookX(page: Float) =
                mushafDialTrackX(1f - mushafDialFraction(page, pages), size.width, inset)
            val run = troughRun
            val runSpan = (run.last - run.first).coerceAtLeast(1)
            val headroom = ruleY - 1.5.dp.toPx()

            // The bracket, and what it becomes. In the chapter tier it is a
            // short capsule of the seat mark's own weight and ink, sitting over
            // the chapter the finger is in, drawn on the book's true scale.
            // Hold still and it stretches out until it is the whole measure
            // with leaves standing in it, and recedes to furniture ink as it
            // goes: the trough.
            //
            // It is not a fill. It does not run from an end of the rule and it
            // does not grow with progress — at rest it is not there at all. It
            // is one chapter's run, and then that run magnified into the trough,
            // which is the only way to say "you are inside this chapter now" to
            // a reader whose own finger is covering the line.
            if (lift > 0.004f && chapterMarks.isNotEmpty()) {
                val fromX = bookX(run.first.toFloat())
                val toX = bookX(run.last.toFloat())
                val minW = MushafDialBracketMin.toPx()
                val centre = (fromX + toX) / 2f
                val half = maxOf(abs(fromX - toX), minW) / 2f
                val left = lerp(centre - half, troughInset, open)
                val right = lerp(centre + half, size.width - troughInset, open)
                val weight = MushafDialBracket.toPx()
                drawRoundRect(
                    // The seat mark's ink while it is a marker, falling back to
                    // furniture as it becomes a channel. Reversed from how it
                    // was, and the reversal is the point: the reader's hand is
                    // on the chapter, not on the measure, and the whole measure
                    // carrying a marker's weight would read as a fill.
                    color = ink.copy(alpha = lerp(thumbInk, 0.20f, open) * lift),
                    topLeft = Offset(left, ruleY - weight / 2f),
                    size = Size((right - left).coerceAtLeast(weight), weight),
                    cornerRadius = CornerRadius(weight, weight),
                )
            }

            // The chapter tier's comb: true hairline at rest. When under the
            // finger the neighbourhood is lensed — far right 1×, growing to
            // max by ~ch 25 then plateau — with extra tail boost. Syncs with
            // the thumb's true seat.
            val combInk = lift * (1f - open)
            if (combInk > 0.004f) {
                val tick = MushafDialChapterTick.toPx()
                val baseSigmaPx = MUSHAF_DIAL_LENS_SIGMA_DP.dp.toPx()
                val isLensed = scrubbing || handed
                val centerX = if (isLensed) handX.floatValue else seatX
                fun bookX(page: Float) =
                    mushafDialTrackX(1f - mushafDialFraction(page, pages), size.width, inset)
                val centreProgress = if (isLensed) {
                    val centreFrac = mushafDialTrackFraction(centerX, size.width, inset)
                    (1f - centreFrac).coerceIn(0f, 1f)
                } else 0f
                val plateauAt = 0.78f
                val effProgress = (centreProgress / plateauAt).coerceIn(0f, 1f)
                val leftPushPx = if (isLensed) 10.dp.toPx() * combInk * effProgress else 0f
                val sigmaPx = baseSigmaPx * (1f + 0.6f * effProgress)
                val progBaseMag = 1f + (MUSHAF_DIAL_LENS_MAG - 1f) * effProgress
                val progHeightMag = 1f + (MUSHAF_DIAL_LENS_HEIGHT_GAIN - 1f) * effProgress
                val epsilonPx = 1.8.dp.toPx()
                var previousX = Float.MAX_VALUE
                for ((idx, mark) in chapterMarks.withIndex()) {
                    var trueX = bookX(mark.toFloat())
                    // Spread co-located marks (gap 0) around their page.
                    var gStart = idx
                    while (gStart > 0 && chapterMarks[gStart - 1] == mark) gStart--
                    var gEnd = idx
                    while (gEnd + 1 < chapterMarks.size && chapterMarks[gEnd + 1] == mark) gEnd++
                    val gSize = gEnd - gStart + 1
                    if (gSize > 1) {
                        val posInGroup = idx - gStart
                        val offset = (posInGroup - (gSize - 1) / 2f) * epsilonPx
                        trueX += offset
                    }
                    // Extra tail boost from chapter 25, also progressive with
                    // effProgress so it fades in leftward. Tighter stronger for tail.
                    val isTailMark = idx >= 24
                    val gap = if (idx < chapterMarks.lastIndex) {
                        (chapterMarks[idx + 1] - mark).coerceIn(0, 20)
                    } else 1
                    val densityMag = if (isLensed) {
                        val extra = if (isTailMark) {
                            (1f - gap / 10f).coerceIn(0f, 1f) * 2.2f * effProgress
                        } else 0f
                        progBaseMag + extra
                    } else progBaseMag
                    val heightMagForMark = if (isLensed) {
                        val extraH = if (isTailMark) {
                            (1f - gap / 10f).coerceIn(0f, 1f) * 1.1f * effProgress
                        } else 0f
                        progHeightMag + extraH
                    } else progHeightMag
                    val x0 = if (isLensed) {
                        mushafDialLensedX(trueX, centerX, sigmaPx, densityMag)
                    } else trueX
                    val x = x0 + leftPushPx * (if (isLensed && isTailMark) {
                        val markFrac = mushafDialFraction(mark.toFloat(), pages)
                        (markFrac).coerceIn(0f, 1f)
                    } else 0f)
                    if (x < -rule || x > size.width + rule) continue
                    if (previousX - x < rule * 1.5f) continue
                    previousX = x
                    val dist = if (isLensed) abs(trueX - centerX) else 0f
                    val heightGain = if (isLensed) {
                        mushafDialLensFactor(dist, sigmaPx, heightMagForMark)
                    } else 1f
                    val length = (tick * combInk * heightGain).coerceAtMost(headroom)
                    if (length <= 0.4f) continue
                    drawRoundRect(
                        // Stronger than the trough's own leaves, which is the
                        // right way round: those are five dp tall and spread
                        // across a whole measure, these are hairlines at a
                        // hundred and fourteen to a screen. At furniture
                        // weight the comb read as a smudge on the rule rather
                        // than as marks a reader could count and aim between,
                        // which is the only thing it is for. Height shows the
                        // lens: closer is taller, denser tail is taller still.
                        color = ink.copy(alpha = 0.54f * combInk),
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
                    val seat = bookX(page.toFloat())
                    val troughX = mushafDialTrackX(
                        1f - (page - run.first).toFloat() / runSpan,
                        size.width,
                        troughInset,
                    )
                    val x = lerp(seat, troughX, open)
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

            // The marker, and when there is one at all.
            //
            // At rest it is the ribbon: this leaf's place among the 604, the
            // only thing on the rule. Under a hand at chapter granularity it
            // goes out entirely — what the reader has hold of there is the
            // *comb*, and the cell of it their finger is in is drawn as the
            // bracket. A marker riding the line beside all that is a second
            // answer to a question the comb has already answered, and it
            // turned the gesture into dragging a knob past some scenery.
            //
            // It comes back inside the trough, where it is the right answer:
            // the chapter has been magnified into a measure of its own, and a
            // seat mark standing in it is what the reader scrubs with. It
            // fades back in as the trough shuts, so the rule ends the gesture
            // holding a place again.
            val marker = maxOf(open, 1f - lift)
            if (thumbInk * marker > 0.004f) {
                val thumbH = MushafDialThumbHeight.toPx() +
                    (MushafDialThumbHeldHeight - MushafDialThumbHeight).toPx() * lift
                val thumbW = thumbH * MushafDialThumbAspect
                val left = (thumbX - thumbW / 2f).coerceIn(0f, size.width - thumbW)
                drawRoundRect(
                    color = ink.copy(alpha = thumbInk * marker),
                    topLeft = Offset(left, ruleY - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(thumbH, thumbH),
                )
            }
        }
        if (hud != null) {
            // Type alone, no capsule: on this paper anything with a ground
            // behind it is a card, and the leaf does not carry cards. It takes
            // the transport's own small hand, because it stands on the
            // transport's paper rather than on the leaf.
            val hudType = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.08.em)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // Measured out of the slot's reach. The dial's own band is
                    // 13 dp — a hairline's worth — and the label does not live
                    // in it; it stands on the leaf's bottom margin above. Left
                    // to the slot's constraints the second line has nowhere to
                    // be measured into and silently collapses to nothing.
                    //
                    // Height only. Freeing the width as well would take the
                    // rule's own width off the label, and a line that is never
                    // told how much paper it has cannot ellipsise: at a large
                    // font scale the head would run off the glass instead of
                    // ending in a full stop.
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged {
                        hudWidthPx = it.width
                        hudHeightPx = it.height
                    }
                    .offset {
                        // Over the thumb, which is over the finger.
                        val left = (handX.floatValue - hudWidthPx / 2f)
                            .coerceIn(0f, (widthPx - hudWidthPx).coerceAtLeast(0).toFloat())
                        // Clear of the tallest tick, and measured from the
                        // rule rather than from the top of the slot. The slot
                        // carries paper above the rule that the label was
                        // being lifted over as well, and a two-line label
                        // lifted that far lands on the leaf's last line of
                        // script. Held off the rule itself, it sits in the
                        // margin the folio stands in — which is the one thing
                        // on the leaf the label is already saying.
                        val foot = MushafDialRuleY.toPx() -
                            MushafDialPageTick.toPx() -
                            MushafDialHudAir.toPx()
                        IntOffset(left.roundToInt(), (foot - hudHeightPx).roundToInt())
                    }
                    .graphicsLayer { alpha = expand.value },
            ) {
                // The head is set twice, once as each tier reads the leaf, and
                // the two are cross-faded on the trough's own opening. The
                // tiers do not change their minds at an instant: the bracket
                // takes about a quarter second to stretch into the trough, and
                // wording that swapped on the first frame of that was
                // answering a question the rule had not finished asking.
                //
                // Stacked rather than sequenced, so the block stays as wide as
                // the wider of the two readings throughout and the centring
                // does not slide out from under the type as the words change.
                // Both alphas are read in the draw phase, so the whole
                // transition costs no recomposition at all.
                val hudPulse = pulse.value
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = mushafDialLabelHead(hud, zoomed = false, page = hudPage),
                        style = hudType,
                        color = androidx.compose.ui.graphics.lerp(ink, accents.repeatInk, hudPulse).copy(alpha = 0.72f + 0.18f * hudPulse),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = 1f - zoom.value },
                    )
                    Text(
                        text = mushafDialLabelHead(hud, zoomed = true, page = hudPage),
                        style = hudType,
                        color = androidx.compose.ui.graphics.lerp(ink, accents.repeatInk, hudPulse).copy(alpha = 0.72f + 0.18f * hudPulse),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = zoom.value },
                    )
                }
                // The verses sit under the leaf's own name and are read after
                // it, so they are set below it in the lighter ink the running
                // head uses for everything subordinate.
                //
                // Only one tier writes this line at all, so its cross-fade is
                // the line against nothing — which is the fade itself, and
                // wants no second setting to fade against. What the comb
                // writes here is the hard space: an empty string measures to
                // no line, and the paper the verses arrive on has to be
                // already theirs, or the head would step down as they came in.
                Text(
                    text = mushafDialLabelFoot(hud, zoomed = true).ifEmpty { " " },
                    style = hudType,
                    color = androidx.compose.ui.graphics.lerp(ink, accents.repeatInk, hudPulse).copy(alpha = 0.48f + 0.22f * hudPulse),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = zoom.value },
                )
            }
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
                .height(MushafDialSlot + MushafDialBelowGrab)
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
                        val troughInsetPx = insetPx + MushafDialRunOut.toPx()
                        val strayPx = MushafDialStray.toPx()
                        // The line the reader took hold of. It does not creep
                        // along with the finger — the question the stray test
                        // asks is whether they have left it, and a reference
                        // that followed them could not be left. It moves once,
                        // and only when an insistent hold declares a new one.
                        var pressY = down.position.y
                        var handY = pressY
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
                        // How long the hand has been out in the run-out. The
                        // width of it is room to aim; this is its resistance.
                        var runOutS = 0f
                        // Whether the trough standing open was asked for from
                        // out in the run-out. The resistance is suspended
                        // while it is — a trough handed over out there would
                        // otherwise be taken back within the fifth of a second
                        // it takes the same clock to fill again. It lapses the
                        // moment the hand is back over the measure, which is
                        // the moment the ordinary law can be obeyed.
                        var spared = false
                        // Whether a closing still stands. It is what stops a
                        // hand that has just been handed the book back from
                        // being handed the trough again while it sits there
                        // not having moved — and it is *all* that stops it.
                        // Position used to: the ordinary open was refused to
                        // anyone out in the run-out or off their press line.
                        // Both refusals turned out to fire constantly during
                        // honest use. A thumb sweeping the length of the rule
                        // pivots from the wrist and traces an arc, so it ends
                        // a long stroke well off the line it started on; and
                        // the chapter tier lays the whole book across the full
                        // measure while the run-out is measured against the
                        // trough's shorter one, so the first and last forty
                        // leaves of the book stand *inside* the run-out by
                        // construction. Between them that refused the ordinary
                        // hold to most long strokes and to al-Fatiha, leaving
                        // the insistent hold to do it — which is why holding
                        // still took a second and a half instead of an eighth.
                        var shut = false
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
                        hasPulsed = false
                        scope.launch { pulse.snapTo(0f) }
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
                                // Out along the rule, or off across it. The
                                // trough is a place; both of these say the
                                // hand is no longer in it, and the same pair
                                // that lets the reader out keeps them out
                                // until they are back on the line and over the
                                // measure. Only the leaving is weighted — the
                                // guard takes either at once, because a hand
                                // that is out there is over no leaf whether it
                                // has been for a moment or a second.
                                val past =
                                    mushafDialPastTrough(handPx, widthPxNow, troughInsetPx)
                                val strayed = mushafDialStrayed(handY, pressY, strayPx)
                                if (!past) spared = false
                                runOutS = if (past && !spared) runOutS + dt else 0f
                                if (open) {
                                    // Absolute, inside the chapter: the finger
                                    // names a place between the two ends.
                                    val fraction =
                                        mushafDialTrackFraction(handPx, widthPxNow, troughInsetPx)
                                    val target = mushafDialTroughPage(fraction, troughRun)
                                    settleOffset *= exp(-dt / MUSHAF_DIAL_TROUGH_SETTLE_TAU_S)
                                    raw = (target + settleOffset).coerceIn(
                                        troughRun.first.toFloat(),
                                        troughRun.last.toFloat(),
                                    )
                                    dialPage.floatValue = raw
                                    if (mushafDialShouldLeaveTrough(runOutS, strayed)) {
                                        open = false
                                        heldS = 0f
                                        shut = true
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
                                // The chapter tier, read the same way the
                                // trough is: the finger's place along the
                                // measure names a place in the book, right end
                                // to left end. The comb it is reading is
                                // nailed to the rule, so any other mapping
                                // would put the bracket somewhere the reader's
                                // own finger is not — and both ends would stop
                                // meaning the ends of the book after the first
                                // stroke that ran past one.
                                raw = mushafDialChapterPage(
                                    // The tier's fractions run 0 at leaf 1
                                    // (the rule's right end); the track runs
                                    // 0 at its left edge. One flip joins them.
                                    1f - mushafDialTrackFraction(handPx, widthPxNow, insetPx),
                                    chapterMarks,
                                    pages,
                                )
                                dialPage.floatValue = raw
                                val landed = raw.roundToInt().coerceIn(1, pages)
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
                                if (heldS == 0f) hasPulsed = false
                                // 500ms before pop: 300ms orange pulse + 200ms breather.
                                if (!hasPulsed && !open && heldS >= MUSHAF_DIAL_HOLD_S - 0.5f && heldS < MUSHAF_DIAL_HOLD_S) {
                                    hasPulsed = true
                                    scope.launch {
                                        pulse.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
                                        pulse.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
                                    }
                                }
                                // Travel lifts the closing. A hold that has
                                // not moved since the trough was taken away is
                                // the same gesture still going; a hold that
                                // follows fresh steering is a new request, and
                                // the reader has said so with the only thing
                                // they can say it with. Asking what they have
                                // *done* rather than where they are is what
                                // keeps this from refusing honest holds.
                                if (abs(speed) >= MUSHAF_DIAL_HOLD_DP_S) shut = false
                                val insists = mushafDialInsists(speed, heldS)
                                if ((!shut || insists) &&
                                    mushafDialShouldOpen(speed, heldS)
                                ) {
                                    // The trough names its own line and buys
                                    // off the run-out as it opens, wherever
                                    // the hand happens to be. Both are needed
                                    // whichever hold got here: a trough opened
                                    // from off the press line, or from inside
                                    // the run-out — and the chapter tier puts
                                    // the ends of the book there — would read
                                    // its own closing law on the very next
                                    // frame and shut what it had just opened.
                                    pressY = handY
                                    spared = past
                                    runOutS = 0f
                                    // Enter on the leaf the hand is actually
                                    // on, and let the trough's absolute scale
                                    // arrive underneath it.
                                    val fraction =
                                        mushafDialTrackFraction(handPx, widthPxNow, troughInsetPx)
                                    settleOffset =
                                        raw - mushafDialTroughPage(fraction, chapter)
                                    open = true
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
                            // Read but never clamped: how far off the line the
                            // hand has gone is the whole question, so the
                            // travel that leaves the strip is the travel that
                            // matters. The meter drains it.
                            handY = change.position.y
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
                        hasPulsed = false
                        scope.launch { pulse.snapTo(0f) }
                        // One motion: the trough shuts back into the line
                        // while the thumb rides down onto the seat. Same spec
                        // on both, so they still arrive together — but the
                        // close is not the ride's child. A hand that comes
                        // back before the ride is over cancels the ride, and a
                        // close cancelled halfway leaves the next stroke
                        // steering the chapter tier underneath a trough that
                        // is still half open, with the last chapter's leaves
                        // standing across the rule. Nothing would shut it
                        // again until that stroke opened the trough itself.
                        scope.launch { zoom.animateTo(0f, MushafDialZoomOut) }
                        glide = scope.launch {
                            resting.snapTo(landed.toFloat())
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
