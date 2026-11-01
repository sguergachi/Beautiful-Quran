package com.beautifulquran.ui.reader

import androidx.compose.animation.core.Animatable
import com.beautifulquran.ui.theme.LocalQuranAccents
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Immutable
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.paperToggleHaptic
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
 *    down (an accelerating drain as the ring contracts back into the mark). Slide into a scroll and the rays simply
 *    draw back.
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

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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

/** How the finger is on the mark — and, once it is not, how it left. */
internal enum class MarkPress {
    Idle,
    Down,

    /** Lifted in place: a tap, or a hold let go too soon. */
    Released,

    /** Slid past touch slop: the reader is scrolling, not holding. */
    Cancelled,
}

/** The hold's live state: the finger, how full the shamsa is, its pop and its tap lesson. */
@Stable
internal class MarkHold internal constructor(
    val press: MutableState<MarkPress>,
    private val chargeAnim: Animatable<Float, *>,
    private val burstAnim: Animatable<Float, *>,
    private val hintAnim: Animatable<Float, *>,
    private val surgeAnim: Animatable<Float, *>,
) {
    val charge: Float get() = chargeAnim.value
    val burst: Float get() = burstAnim.value
    val hint: Float get() = hintAnim.value
    val surge: Float get() = surgeAnim.value

    /**
     * Where the finger came down, in the drawing node's own pixels. Read only
     * at draw time, and only while the ring is up, so a plain field will do.
     */
    var finger: Offset = Offset.Unspecified
    val visible: Boolean get() = charge > 0f || burst > 0f
}

/**
 * Drives [MarkHold] from the finger. When the shamsa fills under a finger
 * still down, the toggle haptic fires and the burst starts at once; [onHold]
 * follows [MARK_HOLD_ACTION_DELAY_MS] later, even if the finger has lifted. The press is read through a
 * snapshot flow, so holding redraws the mark but never recomposes the verse.
 */
@Composable
internal fun rememberMarkHold(onHold: () -> Unit): MarkHold {
    val press = remember { mutableStateOf(MarkPress.Idle) }
    val charge = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val hint = remember { Animatable(0f) }
    val surge = remember { Animatable(0f) }
    // Whether this press already popped. A lift after a pop is no lesson; and
    // a tap so quick its down and up arrive as one update must still get one.
    val popped = remember { booleanArrayOf(false) }
    val latestOnHold = rememberUpdatedState(onHold)
    val view = LocalView.current
    // The pop outlives the finger: lifting it mid-burst must not cut it short.
    val popScope = rememberCoroutineScope()
    LaunchedEffect(press) {
        snapshotFlow { press.value }.collectLatest { state ->
            if (burst.value > 0f) return@collectLatest
            if (state == MarkPress.Down) {
                // Pressing again mid-lesson carries on from the ring as it stands.
                popped[0] = false
                hint.snapTo(0f)
                surge.snapTo(0f)
                charge.animateTo(
                    1f,
                    tween(durationMillis = (MARK_HOLD_MS * (1f - charge.value)).toInt(), easing = LinearEasing),
                )
                // Still down when the ring closed: that is the hold. Feel it
                // now, see it burst now, and let the action follow mid-flight.
                popped[0] = true
                view.paperToggleHaptic(turningOn = true)
                popScope.launch {
                    burst.animateTo(1f, tween(durationMillis = MARK_HOLD_BURST_MS, easing = LinearEasing))
                    charge.snapTo(0f)
                    burst.snapTo(0f)
                }
                popScope.launch {
                    delay(MARK_HOLD_ACTION_DELAY_MS)
                    latestOnHold.value()
                }
            } else if (state == MarkPress.Released && !popped[0]) {
                // The tap lesson. Power up: the ring surges out round the
                // finger to the lesson's peak, swelling past itself…
                val peak = markTapTeasePeak(charge.value)
                coroutineScope {
                    if (charge.value < peak) {
                        launch {
                            charge.animateTo(
                                peak,
                                tween(durationMillis = MARK_TAP_TEASE_RISE_MS, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                    launch {
                        surge.animateTo(1f, tween(durationMillis = MARK_TAP_SURGE_IN_MS, easing = FastOutSlowInEasing))
                        surge.animateTo(0f, tween(durationMillis = MARK_TAP_SURGE_OUT_MS, easing = FastOutSlowInEasing))
                    }
                    // …points at the gap the hold never reached, as it settles…
                    launch {
                        delay(MARK_TAP_HINT_DELAY_MS)
                        hint.animateTo(1f, tween(durationMillis = MARK_TAP_HINT_IN_MS, easing = FastOutSlowInEasing))
                        hint.animateTo(0f, tween(durationMillis = MARK_TAP_HINT_OUT_MS, easing = FastOutSlowInEasing))
                    }
                }
                // …and powers down, the drain quickening as the ring contracts
                // back into the mark.
                charge.animateTo(0f, tween(durationMillis = MARK_TAP_DRAIN_MS, easing = FastOutLinearInEasing))
            } else if (state == MarkPress.Cancelled) {
                hint.snapTo(0f)
                surge.snapTo(0f)
                charge.animateTo(0f, tween(durationMillis = MARK_HOLD_RETRACT_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    return remember(press, charge, burst, hint, surge) { MarkHold(press, charge, burst, hint, surge) }
}

/**
 * Watches for a finger landing on the mark ([hits]) without consuming
 * anything: the verse's own tap handling still owns the gesture. A press
 * ends on release, or as soon as it travels past touch slop into a scroll.
 */
internal fun Modifier.markPressWatcher(
    hold: MarkHold,
    /** From this node's pixels to the drawing node's, where they differ. */
    toDrawSpace: () -> Offset = { Offset.Zero },
    hits: (Offset) -> Boolean,
): Modifier =
    pointerInput(Unit) {
        val press = hold.press
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!hits(down.position)) return@awaitEachGesture
            hold.finger = down.position + toDrawSpace()
            press.value = MarkPress.Down
            // Anything but a clean lift in place — slop, a lost pointer, the
            // gesture torn down — is not a tap, and earns no lesson.
            var ended = MarkPress.Cancelled
            try {
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        ended = MarkPress.Released
                        break
                    }
                    if ((change.position - down.position).getDistance() > slop) break
                }
            } finally {
                press.value = ended
            }
        }
    }

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
 * line's [baseline] when it is known. [rtl] is the verse's writing direction:
 * the rays catch the way the line reads.
 */
internal fun DrawScope.drawMarkHold(
    bounds: Rect,
    baseline: Float?,
    hold: MarkHold,
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
        finger = hold.finger,
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
