package com.beautifulquran.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.paperToggleHaptic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Gather is entered by holding a verse's `﴿N﴾`, and the hold is drawn as an
 * illuminator's gilded shamsa round the mark ([MarkHoldGold]). See docs/VERSE_ACTIONS.md.
 *
 * 1. **Press.** Faint stubs of sixteen rays ring the mark under the finger.
 * 2. **Hold.** The ring grows out from the cups to stand clear around the
 *    fingertip ([shamsaExpansion]), and the rays catch in turn round it, in
 *    reading order, each lengthening and darkening — a progress ring drawn in
 *    the page's own ornament. [MARK_HOLD_MS] fills it.
 * 3. **Pop.** The full shamsa bursts: rays fly out, thin and fade, as gather
 *    mode's gold ink spreads from the mark. Nothing stays on the number.
 * 4. **Let go early.** A tap — or a hold let go too soon — gets a lesson:
 *    power up (the ring surges out round the finger to [MARK_TAP_TEASE],
 *    swelling past itself), the unlit rays pulse to show the gap, then power
 *    down (an accelerating drain as the ring contracts back into the mark).
 *    Slide into a scroll and the rays simply draw back.
 *
 * Geometry and the per-frame look are pure functions so tests can hold them;
 * the draw function only turns them into paths.
 */
internal const val MARK_HOLD_MS = 550

/** The burst after the hold fills: long enough to read as a release, no longer. */
internal const val MARK_HOLD_BURST_MS = 420

/**
 * How far into the burst the hold's action runs. Entering gather recomposes
 * the whole reader and stalls the main thread for a moment; run first, that
 * stall froze a full ring with nothing happening. Run here, the haptic and
 * the rays' flight are already under way, and the stall lands mid-flight.
 */
internal const val MARK_HOLD_ACTION_DELAY_MS = 120L

/** A press that slid into a scroll: the rays draw back quickly, no lesson. */
private const val MARK_HOLD_RETRACT_MS = 180

/**
 * The tap lesson. A finger lifted before the ring closes is a reader who
 * does not yet know the mark wants holding, so the shamsa shows them: the
 * sweep carries on to [MARK_TAP_TEASE] of the ring, the unlit stubs pulse
 * once ([shamsaRayLook]'s hint) to point at what is missing, and the ring
 * drains back in reverse — a progress ring that was let go too soon.
 */
internal const val MARK_TAP_TEASE = 0.5f

/*
 * The lesson's score, about a second end to end. The beats overlap: a ring
 * left sitting still between them read as stuck, not as powering down.
 *
 *   0 ─ rise ──── 260                      charge → peak
 *   0 ─ surge in ─ 200 ─ surge out ─ 500   swell past itself, settle
 *        220 ─ gap pulse ─ 580             unlit rays darken, fade
 *                          580 ─ drain ─ 1040   power down, accelerating
 */
private const val MARK_TAP_TEASE_RISE_MS = 260
private const val MARK_TAP_SURGE_IN_MS = 200
private const val MARK_TAP_SURGE_OUT_MS = 300
private const val MARK_TAP_HINT_DELAY_MS = 220L
private const val MARK_TAP_HINT_IN_MS = 120
private const val MARK_TAP_HINT_OUT_MS = 240

/** Powering down: the drain accelerates as the ring contracts back into the mark. */
private const val MARK_TAP_DRAIN_MS = 460

/** Where the tap lesson carries the ring to: never back, at least [MARK_TAP_TEASE]. */
internal fun markTapTeasePeak(charge: Float): Float = maxOf(charge, MARK_TAP_TEASE)

/** Sixteen: an illuminator's count, and never a six-fold star. */
internal const val SHAMSA_RAY_COUNT = 16

// ------------------------------------------------------------- mark frame

/** The painted ornament as an ellipse: centre and radii, in the drawing node's pixels. */
internal data class MarkFrame(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/**
 * Hafs' ayah cups, measured on device in both the English and Arabic lines:
 * the pair stands 0.71 of its width tall, centred 0.105 widths above the
 * baseline it shares with the verse. The line box is no guide — Hafs sets
 * 1.95 em of leading, and its centre sits well above the ornament.
 */
internal const val CUP_HEIGHT_PER_WIDTH = 0.71f
internal const val CUP_CENTRE_ABOVE_BASELINE_PER_WIDTH = 0.105f

/**
 * The ellipse that hugs the ornament. With the line's [baseline] it is placed
 * on the cups themselves; without one it falls back to the box's centre,
 * with the height following the width rather than the leading.
 */
internal fun markFrame(bounds: Rect, baseline: Float?, minRy: Float): MarkFrame {
    val width = bounds.width
    val rx = width / 2f
    if (baseline != null && baseline.isFinite()) {
        return MarkFrame(
            cx = bounds.center.x,
            cy = baseline - CUP_CENTRE_ABOVE_BASELINE_PER_WIDTH * width,
            rx = rx,
            ry = (CUP_HEIGHT_PER_WIDTH * width / 2f).coerceAtLeast(minRy),
        )
    }
    val ry = minOf(bounds.height / 2f, rx * 0.78f).coerceAtLeast(minRy)
    return MarkFrame(bounds.center.x, bounds.center.y, rx, ry)
}

// ------------------------------------------------------------------ easing

internal fun easeOutCubic(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

/**
 * Local 0..1 for item [index] of [count] in a staggered sequence: the last item
 * starts once [spread] of the whole has passed.
 */
internal fun staggered(progress: Float, index: Int, count: Int, spread: Float): Float {
    if (count <= 1) return progress.coerceIn(0f, 1f)
    val start = spread * index / (count - 1)
    return ((progress - start) / (1f - spread)).coerceIn(0f, 1f)
}

// ------------------------------------------------------------ ray geometry

/** One ray at rest: where it leaves the ring, its outward direction, full length and half width. */
internal data class ShamsaRay(val base: Offset, val dirX: Float, val dirY: Float, val length: Float, val halfWidth: Float)

/**
 * Rays round the mark, starting at the top and running the way the line
 * reads. Long and short alternate, as on a marginal shamsa.
 */
internal fun shamsaRays(
    frame: MarkFrame,
    gap: Float,
    longRay: Float,
    shortRay: Float,
    halfWidth: Float,
    rtl: Boolean,
    count: Int = SHAMSA_RAY_COUNT,
): List<ShamsaRay> {
    val a = frame.rx + gap
    val b = frame.ry + gap
    val turn = if (rtl) -1f else 1f
    return List(count) { i ->
        val theta = -PI.toFloat() / 2f + turn * i * 2f * PI.toFloat() / count
        val c = cos(theta)
        val s = sin(theta)
        // Outward normal of the ellipse, so rays stand square to the ring.
        val nx = c * b
        val ny = s * a
        val nLen = hypot(nx, ny).coerceAtLeast(1e-4f)
        val long = i % 2 == 0
        ShamsaRay(
            base = Offset(frame.cx + a * c, frame.cy + b * s),
            dirX = nx / nLen,
            dirY = ny / nLen,
            length = if (long) longRay else shortRay,
            halfWidth = if (long) halfWidth else halfWidth * 0.72f,
        )
    }
}

// ------------------------------------------------------- around the finger

/**
 * The finger covers the mark it holds, so a shamsa the size of the cups is
 * hidden under it. As the hold builds, the ring grows from the cups out to
 * [EXPANDED_RADIUS_DP] and re-centres on the fingertip, its rays lengthening
 * and thickening as it goes, so the whole sunburst stands clear around the
 * finger. It is driven by [charge], so letting go contracts it back too.
 */
internal const val EXPANDED_RADIUS_DP = 34f

/** Rays at full expansion, in dp: long, short, half width. */
internal const val EXPANDED_LONG_RAY_DP = 10f
internal const val EXPANDED_SHORT_RAY_DP = 5.5f
internal const val EXPANDED_HALF_WIDTH_DP = 1.6f

/** 0 at the cups, 1 around the finger; reached by 45% of the hold, so it shows early. */
internal fun shamsaExpansion(charge: Float): Float = easeOutCubic((charge / 0.45f).coerceIn(0f, 1f))

/**
 * The ring's ellipse at [expansion]: the mark's own [frame] at 0, a circle of
 * [expandedRadius] on [finger] at 1. Without a finger it grows in place.
 */
internal fun expandedFrame(frame: MarkFrame, finger: Offset, expandedRadius: Float, expansion: Float): MarkFrame {
    val e = expansion.coerceIn(0f, 1f)
    val centre = if (finger.isSpecified) finger else Offset(frame.cx, frame.cy)
    return MarkFrame(
        cx = lerp(frame.cx, centre.x, e),
        cy = lerp(frame.cy, centre.y, e),
        rx = lerp(frame.rx, expandedRadius, e),
        ry = lerp(frame.ry, expandedRadius, e),
    )
}

// --------------------------------------------------------------- the look

/**
 * One ray's look at one moment of the gesture, as factors of its resting
 * geometry: [length] and [width] scale the ray, [lift] pushes it out from the
 * ring (in dp), [alpha] is its ink.
 */
internal data class ShamsaRayLook(val length: Float, val width: Float, val lift: Float, val alpha: Float)

/** Unlit rays: short stubs at a whisper of ink, there the moment the finger lands. */
internal const val RAY_STUB = 0.22f

/** How far the whole sunburst breathes outward as the hold fills, in dp. */
internal const val CHARGE_LIFT_DP = 1.5f

/** How far the rays fly at the pop, in dp. */
internal const val BURST_LIFT_DP = 6f

/** How far the tap lesson's power-up pushes the rays out, in dp. */
internal const val SURGE_LIFT_DP = 3f

/**
 * [charge] is the hold, 0..1; [burst] the pop after it fills, 0..1. A ray
 * catches when the sweep reaches it and grows from its stub to full; at the
 * pop every ray flies out, lengthens, thins and fades together. [hint] is the
 * tap lesson's pulse: it darkens and stretches only the rays still unlit, so
 * the eye goes to the part of the ring the hold has not reached. [surge] is
 * the lesson's power-up: every ray swells, darkens and pushes out together.
 */
internal fun shamsaRayLook(
    index: Int,
    count: Int,
    charge: Float,
    burst: Float,
    hint: Float = 0f,
    surge: Float = 0f,
): ShamsaRayLook {
    val lit = easeOutCubic(staggered(charge, index, count, spread = 0.65f))
    // The ring itself fades in over the first moment of the press.
    val arrive = (charge / 0.06f).coerceIn(0f, 1f)
    var length = RAY_STUB + (1f - RAY_STUB) * lit
    var width = 0.7f + 0.3f * lit
    var lift = CHARGE_LIFT_DP * charge.coerceIn(0f, 1f)
    var alpha = (0.22f + 0.66f * lit) * arrive
    if (hint > 0f) {
        val unlit = (1f - lit) * hint.coerceIn(0f, 1f)
        alpha += 0.32f * unlit * arrive
        length += 0.2f * unlit
    }
    if (surge > 0f) {
        val s = surge.coerceIn(0f, 1f)
        length *= 1f + 0.3f * s
        width *= 1f + 0.2f * s
        lift += SURGE_LIFT_DP * s
        alpha = minOf(1f, alpha + 0.14f * s * arrive)
    }
    if (burst > 0f) {
        val fly = easeOutCubic(burst)
        length *= 1f + 0.7f * fly
        width *= 1f - 0.45f * fly
        lift += BURST_LIFT_DP * fly
        alpha *= (1f - burst.coerceIn(0f, 1f)).pow(1.6f)
    }
    return ShamsaRayLook(length = length, width = width, lift = lift, alpha = alpha)
}

// ------------------------------------------------------------ the gesture

/**
 * Where a pressed mark is and what holding it does, captured when the finger
 * lands. Geometry is in root pixels, so the host can paint it over the whole
 * list rather than inside the verse that reported it.
 */
internal class MarkHoldTarget(
    /** The painted cups' glyph box. */
    val bounds: Rect,
    /** The line's baseline, when known; [markFrame] sits the ring on the cups by it. */
    val baseline: Float?,
    val finger: Offset,
    /** The verse's writing direction: the rays catch the way the line reads. */
    val rtl: Boolean,
    val onHold: () -> Unit,
)

/** What a finger did on a mark, in the order it did it. */
internal sealed interface MarkPressEvent {
    class Down(val target: MarkHoldTarget) : MarkPressEvent

    /** Lifted in place: a tap, or a hold let go too soon. */
    data object Released : MarkPressEvent

    /** Slid past touch slop, or the gesture was torn down: not a tap, no lesson. */
    data object Cancelled : MarkPressEvent
}

internal enum class HoldPhase {
    Idle,

    /** A finger is down and the ring is filling. */
    Charging,

    /** The ring filled: bursting, and the hold's action is on its way. */
    Popped,

    /** Let go early: the tap lesson is playing. */
    Lesson,

    /** Slid into a scroll: the rays are drawing back. */
    Retracting,
}

/** What the host does with one event. */
internal enum class HoldAction { StartCharge, StartLesson, Retract, Ignore }

internal data class HoldState(
    val phase: HoldPhase = HoldPhase.Idle,
    /** A press that landed mid-burst: it and its lift are ignored as a whole. */
    val ignoringPress: Boolean = false,
)

/**
 * The hold's rules, pure so the edge cases can be held by tests:
 *
 * - a press charges from wherever the ring stands (so pressing again during
 *   a lesson or a retract carries on);
 * - a lift while charging is a tap: the lesson — however quick the tap;
 * - a slide while charging retracts, with no lesson;
 * - once the ring has popped, nothing a finger does can take the pop back: a
 *   lift or slide is ignored, and a new press during the burst is ignored
 *   along with its own lift, rather than half-handled.
 */
internal fun nextHoldStep(state: HoldState, event: MarkPressEvent): Pair<HoldState, HoldAction> =
    when (event) {
        is MarkPressEvent.Down ->
            if (state.phase == HoldPhase.Popped) {
                state.copy(ignoringPress = true) to HoldAction.Ignore
            } else {
                HoldState(HoldPhase.Charging) to HoldAction.StartCharge
            }
        MarkPressEvent.Released -> when {
            state.ignoringPress -> state.copy(ignoringPress = false) to HoldAction.Ignore
            state.phase == HoldPhase.Charging -> HoldState(HoldPhase.Lesson) to HoldAction.StartLesson
            else -> state to HoldAction.Ignore
        }
        MarkPressEvent.Cancelled -> when {
            state.ignoringPress -> state.copy(ignoringPress = false) to HoldAction.Ignore
            state.phase == HoldPhase.Charging -> HoldState(HoldPhase.Retracting) to HoldAction.Retract
            else -> state to HoldAction.Ignore
        }
    }

/**
 * The reader's one hold: only one finger can hold a mark at a time, so one
 * set of animations serves every verse. Marks report presses as ordered
 * events ([send]); nothing is conflated, so a tap whose down and lift land in
 * the same frame still gets its lesson. The host paints the shamsa itself, in
 * an overlay over the verse list ([markHoldOverlay]) — inside a verse it was
 * clipped by the verse's own layers and dimmed with its mark.
 */
@Stable
internal class MarkHoldHost(
    private val scope: CoroutineScope,
    private val onPop: () -> Unit,
) {
    private val chargeAnim = Animatable(0f)
    private val burstAnim = Animatable(0f)
    private val hintAnim = Animatable(0f)
    private val surgeAnim = Animatable(0f)
    private val events = Channel<MarkPressEvent>(Channel.UNLIMITED)
    private var state = HoldState()
    private var running: Job? = null

    val charge: Float get() = chargeAnim.value
    val burst: Float get() = burstAnim.value
    val hint: Float get() = hintAnim.value
    val surge: Float get() = surgeAnim.value
    val visible: Boolean get() = charge > 0f || burst > 0f

    /** The mark being held, or last held while its rays finish. Read at draw time. */
    var target: MarkHoldTarget? = null
        private set

    /** The overlay's own coordinates, to bring root-pixel targets into its space. */
    internal var overlay: LayoutCoordinates? = null

    fun send(event: MarkPressEvent) {
        events.trySend(event)
    }

    /** Consumes presses in order; runs for the host's lifetime. */
    suspend fun run() {
        for (event in events) {
            val (next, action) = nextHoldStep(state, event)
            state = next
            when (action) {
                HoldAction.StartCharge -> startCharge((event as MarkPressEvent.Down).target)
                HoldAction.StartLesson -> replace { lesson() }
                HoldAction.Retract -> replace { retract() }
                HoldAction.Ignore -> Unit
            }
        }
    }

    private fun replace(block: suspend () -> Unit) {
        running?.cancel()
        running = scope.launch { block() }
    }

    private fun startCharge(target: MarkHoldTarget) {
        this.target = target
        replace {
            hintAnim.snapTo(0f)
            surgeAnim.snapTo(0f)
            chargeAnim.animateTo(
                1f,
                tween(durationMillis = (MARK_HOLD_MS * (1f - chargeAnim.value)).toInt(), easing = LinearEasing),
            )
            // Still down when the ring closed: that is the hold. Mark it popped
            // before anything can suspend, so a slide or lift arriving now is
            // ignored rather than retracting the ring under its own burst.
            state = HoldState(HoldPhase.Popped, state.ignoringPress)
            onPop()
            // The pop outlives the finger and the verse: neither the lift nor
            // the reader turning to gather may cut it short.
            scope.launch {
                burstAnim.animateTo(1f, tween(durationMillis = MARK_HOLD_BURST_MS, easing = LinearEasing))
                chargeAnim.snapTo(0f)
                burstAnim.snapTo(0f)
                state = HoldState(HoldPhase.Idle, state.ignoringPress)
            }
            scope.launch {
                delay(MARK_HOLD_ACTION_DELAY_MS)
                target.onHold()
            }
        }
    }

    /** The tap lesson: power up, point at the gap, power down. */
    private suspend fun lesson() {
        // Power up: the ring surges out round the finger to the lesson's
        // peak, swelling past itself…
        val peak = markTapTeasePeak(chargeAnim.value)
        coroutineScope {
            if (chargeAnim.value < peak) {
                launch {
                    chargeAnim.animateTo(
                        peak,
                        tween(durationMillis = MARK_TAP_TEASE_RISE_MS, easing = FastOutSlowInEasing),
                    )
                }
            }
            launch {
                surgeAnim.animateTo(1f, tween(durationMillis = MARK_TAP_SURGE_IN_MS, easing = FastOutSlowInEasing))
                surgeAnim.animateTo(0f, tween(durationMillis = MARK_TAP_SURGE_OUT_MS, easing = FastOutSlowInEasing))
            }
            // …points at the gap the hold never reached, as it settles…
            launch {
                delay(MARK_TAP_HINT_DELAY_MS)
                hintAnim.animateTo(1f, tween(durationMillis = MARK_TAP_HINT_IN_MS, easing = FastOutSlowInEasing))
                hintAnim.animateTo(0f, tween(durationMillis = MARK_TAP_HINT_OUT_MS, easing = FastOutSlowInEasing))
            }
        }
        // …and powers down, the drain quickening as the ring contracts back
        // into the mark.
        chargeAnim.animateTo(0f, tween(durationMillis = MARK_TAP_DRAIN_MS, easing = FastOutLinearInEasing))
        state = HoldState(HoldPhase.Idle)
    }

    private suspend fun retract() {
        hintAnim.snapTo(0f)
        surgeAnim.snapTo(0f)
        chargeAnim.animateTo(0f, tween(durationMillis = MARK_HOLD_RETRACT_MS, easing = FastOutSlowInEasing))
        state = HoldState(HoldPhase.Idle)
    }

    /** Paints the shamsa, if one is up, into the overlay at [origin] in root pixels. */
    internal fun DrawScope.drawInto(origin: Offset, gold: MarkHoldGold) {
        if (!visible) return
        val t = target ?: return
        translate(-origin.x, -origin.y) {
            drawMarkHold(t.bounds, t.baseline, t.finger, this@MarkHoldHost, gold, t.rtl)
        }
    }
}

/** The reader's [MarkHoldHost], when it has one; null leaves every mark inert. */
internal val LocalMarkHoldHost = staticCompositionLocalOf<MarkHoldHost?> { null }

/** One host for a reader, consuming presses for as long as it is composed. */
@Composable
internal fun rememberMarkHoldHost(): MarkHoldHost {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val host = remember(scope, view) { MarkHoldHost(scope) { view.paperToggleHaptic(turningOn = true) } }
    LaunchedEffect(host) { host.run() }
    return host
}

/**
 * Paints [host]'s shamsa over everything this element draws. Put it on the
 * verse list itself, so the sunburst stands above every verse's layers.
 */
@Composable
internal fun Modifier.markHoldOverlay(host: MarkHoldHost): Modifier {
    val gold = rememberMarkHoldGold()
    return onPlaced { host.overlay = it }.drawWithContent {
        drawContent()
        // Read the animations first, unconditionally: a draw only re-runs for
        // state it read, and [MarkHoldHost.target] is a plain field. Bailing out
        // on it before touching the charge left this draw deaf to every hold.
        if (!host.visible) return@drawWithContent
        val coords = host.overlay ?: return@drawWithContent
        if (!coords.isAttached) return@drawWithContent
        with(host) { drawInto(coords.positionInRoot(), gold) }
    }
}

/**
 * Watches a mark for a finger and reports what it does to [host], without
 * consuming anything: the verse's own tap handling still owns the gesture. A
 * press ends on a lift, or as soon as it travels past touch slop into a
 * scroll. [resolve] turns the touch (in this element's pixels, with this
 * element's coordinates) into a [MarkHoldTarget], or null for a miss; it is
 * read fresh on every press, so a re-laid-out verse is hit where it now is.
 */
@Composable
internal fun Modifier.markHoldWatcher(
    host: MarkHoldHost,
    resolve: (touch: Offset, here: LayoutCoordinates) -> MarkHoldTarget?,
): Modifier {
    val latestResolve = rememberUpdatedState(resolve)
    val here = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onPlaced { here[0] = it }.pointerInput(host) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val coords = here[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
            val target = latestResolve.value(down.position, coords) ?: return@awaitEachGesture
            host.send(MarkPressEvent.Down(target))
            // Anything but a clean lift in place — slop, a lost pointer, the
            // gesture torn down — is not a tap, and earns no lesson.
            var ended: MarkPressEvent = MarkPressEvent.Cancelled
            try {
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        ended = MarkPressEvent.Released
                        break
                    }
                    if ((change.position - down.position).getDistance() > slop) break
                }
            } finally {
                host.send(ended)
            }
        }
    }
}

/** The screen-reader way in: holding has no pointer to watch there. */
internal fun Modifier.markHoldAccessibility(onHold: () -> Unit): Modifier =
    semantics {
        customActions = listOf(
            CustomAccessibilityAction(label = "Gather this verse") {
                onHold()
                true
            },
        )
    }

/** A rect's corners carried from [from]'s pixels into root pixels. */
internal fun LayoutCoordinates.rectToRoot(rect: Rect): Rect =
    Rect(localToRoot(rect.topLeft), localToRoot(rect.bottomRight))

// ------------------------------------------------------------------ gilding

/**
 * The shamsa is gilded: it is the ayah mark's own illumination, the one place
 * gold marks an interaction (docs/DESIGN.md). Each ray runs from [base] at
 * the ring to [tip] at its point. The stops are chosen per page for contrast,
 * not the mark's own muted gold: on cream the deep gold
 * carries the ray and warms toward its tip; on a dark page the gold carries
 * it and kindles to bright gold at the tip.
 */
@Immutable
internal data class MarkHoldGold(val base: Color, val tip: Color)

internal fun markHoldGold(gold: Color, goldBright: Color, goldDeep: Color, darkPage: Boolean): MarkHoldGold =
    if (darkPage) {
        MarkHoldGold(base = gold, tip = goldBright)
    } else {
        MarkHoldGold(base = goldDeep, tip = gold)
    }

/** This page's [MarkHoldGold]. */
@Composable
internal fun rememberMarkHoldGold(): MarkHoldGold {
    val accents = LocalQuranAccents.current
    val darkPage = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(accents, darkPage) {
        markHoldGold(accents.gold, accents.goldBright, accents.goldDeep, darkPage)
    }
}

/**
 * Paints the hold's shamsa round the ornament at [bounds], sitting on the
 * line's [baseline] when it is known, growing out round [finger]. [rtl] is the
 * verse's writing direction: the rays catch the way the line reads.
 */
internal fun DrawScope.drawMarkHold(
    bounds: Rect,
    baseline: Float?,
    finger: Offset,
    hold: MarkHoldHost,
    gold: MarkHoldGold,
    rtl: Boolean,
) {
    if (!hold.visible || bounds.width <= 0f) return
    val charge = hold.charge
    val burst = hold.burst
    val dpPx = 1.dp.toPx()
    val expansion = shamsaExpansion(charge)
    val frame = expandedFrame(
        frame = markFrame(bounds, baseline, minRy = 9.dp.toPx()),
        finger = finger,
        expandedRadius = EXPANDED_RADIUS_DP * dpPx,
        expansion = expansion,
    )
    val rays = shamsaRays(
        frame = frame,
        gap = 2.5.dp.toPx(),
        longRay = lerp(5f, EXPANDED_LONG_RAY_DP, expansion) * dpPx,
        shortRay = lerp(2.8f, EXPANDED_SHORT_RAY_DP, expansion) * dpPx,
        halfWidth = lerp(1f, EXPANDED_HALF_WIDTH_DP, expansion) * dpPx,
        rtl = rtl,
    )
    // One gilt field for the whole sunburst: base gold where the rays leave
    // the ring, tip gold where the longest reach. Rays flown past it at the
    // pop hold the tip colour.
    val inner = maxOf(frame.rx, frame.ry) + 2.5.dp.toPx()
    val outer = inner + lerp(5f, EXPANDED_LONG_RAY_DP, expansion) * dpPx * 1.2f
    val gilt = Brush.radialGradient(
        0f to gold.base,
        (inner / outer).coerceIn(0f, 0.99f) to gold.base,
        1f to gold.tip,
        center = Offset(frame.cx, frame.cy),
        radius = outer,
    )
    val path = Path()
    rays.forEachIndexed { i, ray ->
        val look = shamsaRayLook(i, rays.size, charge, burst, hold.hint, hold.surge)
        if (look.alpha <= 0.004f) return@forEachIndexed
        val lift = look.lift * dpPx
        val baseX = ray.base.x + ray.dirX * lift
        val baseY = ray.base.y + ray.dirY * lift
        val len = ray.length * look.length
        val half = ray.halfWidth * look.width
        // Perpendicular to the ray for its foot.
        val px = -ray.dirY * half
        val py = ray.dirX * half
        val tipX = baseX + ray.dirX * len
        val tipY = baseY + ray.dirY * len
        // Sides bow inward, so each ray reads as a flame of the pen, not a spike.
        val waistX = baseX + ray.dirX * len * 0.45f
        val waistY = baseY + ray.dirY * len * 0.45f
        path.reset()
        path.moveTo(baseX + px, baseY + py)
        path.quadraticTo(waistX + px * 0.35f, waistY + py * 0.35f, tipX, tipY)
        path.quadraticTo(waistX - px * 0.35f, waistY - py * 0.35f, baseX - px, baseY - py)
        path.close()
        drawPath(path, gilt, alpha = look.alpha.coerceIn(0f, 1f))
    }
}
