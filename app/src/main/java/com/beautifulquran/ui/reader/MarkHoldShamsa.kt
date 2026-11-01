package com.beautifulquran.ui.reader

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.paperToggleHaptic
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
 * illuminator's shamsa round the mark. See docs/VERSE_ACTIONS.md.
 *
 * 1. **Press.** Faint stubs of sixteen rays ring the mark under the finger.
 * 2. **Hold.** The rays catch in turn round the ring, in reading order, each
 *    lengthening and darkening while the whole sunburst breathes outward — a
 *    progress ring drawn in the page's own ornament. [MARK_HOLD_MS] fills it.
 * 3. **Pop.** The full shamsa bursts: rays fly out, thin and fade, as gather
 *    mode's gold ink spreads from the mark. Nothing stays on the number.
 * 4. **Cancel.** Let go early, or slide into a scroll, and the rays draw back.
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

/** Let go early: the rays draw back a little faster than they came. */
private const val MARK_HOLD_RETRACT_MS = 180

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

/**
 * [charge] is the hold, 0..1; [burst] the pop after it fills, 0..1. A ray
 * catches when the sweep reaches it and grows from its stub to full; at the
 * pop every ray flies out, lengthens, thins and fades together.
 */
internal fun shamsaRayLook(index: Int, count: Int, charge: Float, burst: Float): ShamsaRayLook {
    val lit = easeOutCubic(staggered(charge, index, count, spread = 0.65f))
    // The ring itself fades in over the first moment of the press.
    val arrive = (charge / 0.06f).coerceIn(0f, 1f)
    var length = RAY_STUB + (1f - RAY_STUB) * lit
    var width = 0.7f + 0.3f * lit
    var lift = CHARGE_LIFT_DP * charge.coerceIn(0f, 1f)
    var alpha = (0.22f + 0.66f * lit) * arrive
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

/** The hold's live state: the finger, how full the shamsa is, and its pop. */
@Stable
internal class MarkHold internal constructor(
    val pressed: MutableState<Boolean>,
    private val chargeAnim: Animatable<Float, *>,
    private val burstAnim: Animatable<Float, *>,
) {
    val charge: Float get() = chargeAnim.value
    val burst: Float get() = burstAnim.value
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
    val pressed = remember { mutableStateOf(false) }
    val charge = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val latestOnHold = rememberUpdatedState(onHold)
    val view = LocalView.current
    // The pop outlives the finger: lifting it mid-burst must not cut it short.
    val popScope = rememberCoroutineScope()
    LaunchedEffect(pressed) {
        snapshotFlow { pressed.value }.collectLatest { down ->
            if (down) {
                if (burst.value > 0f) return@collectLatest
                charge.animateTo(
                    1f,
                    tween(durationMillis = (MARK_HOLD_MS * (1f - charge.value)).toInt(), easing = LinearEasing),
                )
                // Still down when the ring closed: that is the hold. Feel it
                // now, see it burst now, and let the action follow mid-flight.
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
            } else if (burst.value == 0f) {
                charge.animateTo(0f, tween(durationMillis = MARK_HOLD_RETRACT_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    return remember(pressed, charge, burst) { MarkHold(pressed, charge, burst) }
}

/**
 * Watches for a finger landing on the mark ([hits]) without consuming
 * anything: the verse's own tap handling still owns the gesture. A press
 * ends on release, or as soon as it travels past touch slop into a scroll.
 */
internal fun Modifier.markPressWatcher(pressed: MutableState<Boolean>, hits: (Offset) -> Boolean): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!hits(down.position)) return@awaitEachGesture
            pressed.value = true
            try {
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - down.position).getDistance() > slop) break
                }
            } finally {
                pressed.value = false
            }
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
    color: Color,
    rtl: Boolean,
) {
    if (!hold.visible || bounds.width <= 0f) return
    val charge = hold.charge
    val burst = hold.burst
    val frame = markFrame(bounds, baseline, minRy = 9.dp.toPx())
    val rays = shamsaRays(
        frame = frame,
        gap = 2.5.dp.toPx(),
        longRay = 5.dp.toPx(),
        shortRay = 2.8.dp.toPx(),
        halfWidth = 1.dp.toPx(),
        rtl = rtl,
    )
    val dpPx = 1.dp.toPx()
    val path = Path()
    rays.forEachIndexed { i, ray ->
        val look = shamsaRayLook(i, rays.size, charge, burst)
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
        drawPath(path, color.copy(alpha = 0.85f * look.alpha))
    }
}
