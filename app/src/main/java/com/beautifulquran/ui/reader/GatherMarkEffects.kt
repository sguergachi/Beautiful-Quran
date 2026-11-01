package com.beautifulquran.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.beautifulquran.data.BrushCircleStyle
import com.beautifulquran.data.GatherMarkEffect
import com.beautifulquran.ui.theme.BrushOutline
import com.beautifulquran.ui.theme.brushCircleParams
import com.beautifulquran.ui.theme.hash11
import com.beautifulquran.ui.theme.inkBrushCirclePath
import com.beautifulquran.ui.theme.toClosedCubic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Feedback on the `﴿N﴾` a reader taps to gather a verse. One effect is
 * active at a time ([LocalGatherMarkEffect]); each paints in when its verse is
 * gathered and lifts off the page when it is dropped. See docs/VERSE_ACTIONS.md.
 *
 * Geometry lives in pure functions over [MarkFrame] so tests can hold it;
 * the draw functions only turn it into paths.
 */
internal val LocalGatherMarkEffect = staticCompositionLocalOf { GatherMarkEffect.BRUSH_CIRCLE }

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

/**
 * The tap on `﴿N﴾` is one small gesture in three beats, the way a pen meets
 * paper:
 *
 * 1. **Press.** The finger goes down and the pen touches: the effect shows its
 *    first [ANTICIPATION] of ink — a bead of the crown, the loop's first
 *    stroke, the first pearl — pressed in slightly ([PRESS_SQUASH]).
 * 2. **Release.** The verse is gathered and the effect completes from exactly
 *    where it was, springing up past full size and settling ([pop]).
 * 3. **Cancel.** A press that turns into a scroll draws its bead back off.
 *
 * Pressing an already gathered mark lifts its ink a little ([PRESS_LIFT])
 * before the release takes it off the page.
 */
internal const val ANTICIPATION = 0.12f
internal const val PRESS_SQUASH = 0.1f
internal const val PRESS_LIFT = 0.35f

/**
 * [paint] runs 0→1 as the effect is laid down. [presence] is its opacity: a
 * dropped verse's ink lifts off as a whole instead of un-drawing. [press] is
 * the finger on the mark; [pop] the release spring's scale.
 */
@Stable
internal class GatherMarkInk(
    private val paintAnim: Animatable<Float, *>,
    private val presenceAnim: Animatable<Float, *>,
    private val pressAnim: Animatable<Float, *>,
    private val popAnim: Animatable<Float, *>,
) {
    val paint: Float get() = paintAnim.value
    val presence: Float get() = presenceAnim.value
    val press: Float get() = pressAnim.value
    val pop: Float get() = popAnim.value
}

/** What one frame of the gesture paints: how far, how strongly, how large. */
internal data class GatherMarkFrameState(val paint: Float, val alpha: Float, val scale: Float)

/**
 * Resolves the gesture's animations into one frame, or null when nothing is
 * on the page. Pure, so the three beats can be held by tests.
 */
internal fun gatherMarkFrameState(paint: Float, presence: Float, press: Float, pop: Float): GatherMarkFrameState? {
    if (presence > 0f) {
        return GatherMarkFrameState(paint = paint, alpha = presence * (1f - PRESS_LIFT * press), scale = pop)
    }
    if (press > 0f) {
        return GatherMarkFrameState(paint = ANTICIPATION * press, alpha = press, scale = 1f - PRESS_SQUASH * press)
    }
    return null
}

/** The pen touching down: quick, so the press is felt under the finger. */
private const val PRESS_IN_MS = 90

/** A press that came to nothing draws back a little slower than it came. */
private const val PRESS_OUT_MS = 160

/** Leaving fade, shared by every effect. */
private const val GATHER_MARK_LIFT_MS = 220

/**
 * Animates [GatherMarkInk] from [gathered] and the finger on the mark
 * ([pressed], read through a snapshot flow so a press redraws but never
 * recomposes the verse). A mark that scrolls in already gathered starts fully
 * drawn, so nothing repaints as the list recycles.
 */
@Composable
internal fun rememberGatherMarkInk(gathered: Boolean, paintMs: Int, pressed: State<Boolean>): GatherMarkInk {
    val paint = remember { Animatable(if (gathered) 1f else 0f) }
    val presence = remember { Animatable(if (gathered) 1f else 0f) }
    val press = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        snapshotFlow { pressed.value }.collectLatest { down ->
            press.animateTo(
                if (down) 1f else 0f,
                tween(durationMillis = if (down) PRESS_IN_MS else PRESS_OUT_MS, easing = FastOutSlowInEasing),
            )
        }
    }
    LaunchedEffect(gathered) {
        if (gathered) {
            if (presence.value < 1f || paint.value < 1f) {
                // Carry on from the bead the press laid, at the press's size.
                val from = ANTICIPATION * press.value
                pop.snapTo(1f - PRESS_SQUASH * press.value)
                presence.snapTo(1f)
                paint.snapTo(from)
                coroutineScope {
                    // Linear time: each effect shapes its own motion (strokes
                    // ease, beads overshoot), so a curve here would double it.
                    launch {
                        paint.animateTo(
                            1f,
                            tween(durationMillis = (paintMs * (1f - from)).toInt(), easing = LinearEasing),
                        )
                    }
                    launch {
                        pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f))
                    }
                }
            }
        } else if (presence.value > 0f) {
            presence.animateTo(0f, tween(durationMillis = GATHER_MARK_LIFT_MS, easing = FastOutSlowInEasing))
            paint.snapTo(0f)
        }
    }
    return remember(paint, presence, press, pop) { GatherMarkInk(paint, presence, press, pop) }
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
 * Paints [effect] round the ornament at [bounds], sitting on the line's
 * [baseline] when it is known. [rtl] is the verse's
 * writing direction: strokes and sequences run the way the line reads.
 * [seed] gives each verse its own grain.
 */
internal fun DrawScope.drawGatherMark(
    effect: GatherMarkEffect,
    bounds: Rect,
    baseline: Float?,
    ink: GatherMarkInk,
    color: Color,
    rtl: Boolean,
    seed: Int,
) {
    if (bounds.width <= 0f) return
    val state = gatherMarkFrameState(ink.paint, ink.presence, ink.press, ink.pop) ?: return
    if (state.alpha <= 0f) return
    val frame = markFrame(bounds, baseline, minRy = 9.dp.toPx())
    val paint = state.paint
    val presence = state.alpha
    scale(state.scale, pivot = Offset(frame.cx, frame.cy)) {
        when (effect) {
            GatherMarkEffect.BRUSH_CIRCLE -> drawBrushCircle(frame, paint, color, presence)
            GatherMarkEffect.INK_DROP -> drawInkDrop(frame, paint, color, presence, seed)
            GatherMarkEffect.QALAM_SWASH -> drawQalamSwash(frame, paint, color, presence, rtl)
            GatherMarkEffect.SHAMSA_RAYS -> drawShamsaRays(frame, paint, color, presence, rtl)
            GatherMarkEffect.PEARL_RING -> drawPearlRing(frame, paint, color, presence, rtl)
            GatherMarkEffect.TAJ_CROWN -> drawTajCrown(frame, paint, color, presence)
        }
    }
}

// ------------------------------------------------------------------ easing

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun easeOutCubic(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

/** Rises past 1 and settles: a bead pressed into wet paper. */
internal fun easeOutBack(t: Float, overshoot: Float = 1.9f): Float {
    val x = t.coerceIn(0f, 1f) - 1f
    return 1f + x * x * ((overshoot + 1f) * x + overshoot)
}

/**
 * Local 0..1 for item [index] of [count] in a staggered sequence: the last item
 * starts once [spread] of the whole has passed.
 */
internal fun staggered(progress: Float, index: Int, count: Int, spread: Float): Float {
    if (count <= 1) return progress.coerceIn(0f, 1f)
    val start = spread * index / (count - 1)
    return ((progress - start) / (1f - spread)).coerceIn(0f, 1f)
}

// ----------------------------------------------------------- brush circle

/** The gather ordinal's tight loop, a little roomier. */
private val GatherMarkBrush = brushCircleParams(BrushCircleStyle.BASELINE).copy(
    // Close: an English mark sits a narrow space from the verse's full stop.
    padXDp = 2f,
    padYDp = 0f,
    bow = 2.5f,
    peakHalfDp = 1.7f,
)

private fun DrawScope.drawBrushCircle(frame: MarkFrame, paint: Float, color: Color, presence: Float) {
    if (paint <= 0f) return
    val path = inkBrushCirclePath(
        cx = frame.cx,
        cy = frame.cy,
        rx = frame.rx + GatherMarkBrush.padXDp.dp.toPx(),
        ry = frame.ry + 2.5.dp.toPx(),
        peakHalf = GatherMarkBrush.peakHalfDp.dp.toPx(),
        bowPx = GatherMarkBrush.bow.dp.toPx(),
        progress = easeOutCubic(paint),
        params = GatherMarkBrush,
    )
    drawPath(path, color.copy(alpha = GatherMarkBrush.alpha * presence))
}

// ---------------------------------------------------------------- ink drop

/**
 * Drop size and wetness over the paint: it lands and runs a little past its
 * resting edge (0..0.4), then draws back as the paper drinks it, drying from
 * a wet pool to a pale stain whose rim keeps the pigment, as ink does.
 */
internal data class InkDropPhase(val spread: Float, val wet: Float)

internal fun inkDropPhase(paint: Float): InkDropPhase {
    val p = paint.coerceIn(0f, 1f)
    val landing = 0.4f
    return if (p < landing) {
        InkDropPhase(spread = 1.08f * easeOutCubic(p / landing), wet = 1f)
    } else {
        val dry = smoothstep(landing, 1f, p)
        InkDropPhase(spread = 1.08f - 0.08f * dry, wet = 1f - dry)
    }
}

/** A drop's edge: round, with the slow wander of fibre it soaked into. */
internal fun inkDropOutline(frame: MarkFrame, radius: Float, seed: Int, steps: Int = 20): List<Offset> {
    val phaseA = hash11(seed, 11) * PI.toFloat()
    val phaseB = hash11(seed, 12) * PI.toFloat()
    // The pool sits a hair off the mark's centre, as a dropped bead does.
    val cx = frame.cx + hash11(seed, 13) * radius * 0.04f
    val cy = frame.cy + hash11(seed, 14) * radius * 0.04f
    val squash = (frame.ry / frame.rx).coerceIn(0.78f, 1f)
    return List(steps) { i ->
        val t = i * 2f * PI.toFloat() / steps
        val wander = 1f + 0.045f * sin(3f * t + phaseA) + 0.022f * sin(5f * t + phaseB)
        Offset(cx + cos(t) * radius * wander, cy + sin(t) * radius * wander * squash)
    }
}

private fun DrawScope.drawInkDrop(frame: MarkFrame, paint: Float, color: Color, presence: Float, seed: Int) {
    if (paint <= 0f) return
    val phase = inkDropPhase(paint)
    val rest = frame.rx + 1.5.dp.toPx()
    val radius = rest * phase.spread
    if (radius <= 0.5f) return
    val edge = inkDropOutline(frame, radius, seed).toClosedCubic()
    // Wet pool: denser while it lands, a pale stain once dry. Gold sits
    // on top of it, so even the wet peak stays well under the cups' weight.
    val pool = 0.07f + 0.07f * phase.wet
    drawPath(edge, color.copy(alpha = pool * presence))
    // Halo of the run: a faint wider soak, gone by the time it dries.
    if (phase.wet > 0f) {
        val halo = inkDropOutline(frame, radius * 1.16f, seed + 1).toClosedCubic()
        drawPath(halo, color.copy(alpha = 0.035f * phase.wet * presence))
    }
    // The tide line: pigment carried to the edge as the drop dries.
    val rim = 0.05f + 0.15f * (1f - phase.wet)
    drawPath(
        edge,
        color.copy(alpha = rim * presence),
        style = Stroke(width = 1.1.dp.toPx()),
    )
}

// ------------------------------------------------------------- qalam swash

/**
 * The swash under the number: a shallow kashida that sags, then lifts into a
 * flick at its far end. Written from the line's start — right to left in an
 * Arabic verse. [progress] is how far the pen has travelled.
 */
internal fun qalamSwashOutline(
    frame: MarkFrame,
    progress: Float,
    rtl: Boolean,
    gap: Float,
    reach: Float,
    peakHalf: Float,
    sag: Float,
    lift: Float,
    steps: Int = 40,
): BrushOutline? {
    val travelled = progress.coerceIn(0f, 1f)
    if (travelled <= 0.02f) return null
    val half = frame.rx + reach
    val startX = if (rtl) frame.cx + half else frame.cx - half
    val endX = if (rtl) frame.cx - half else frame.cx + half
    val baseY = frame.cy + frame.ry + gap
    val count = (steps * travelled).toInt().coerceAtLeast(2)
    val centre = List(count + 1) { i ->
        val u = travelled * i / count
        val flick = smoothstep(0.7f, 1f, u)
        Offset(
            startX + (endX - startX) * u,
            baseY + sag * sin(PI.toFloat() * u) - lift * flick * flick,
        )
    }
    val tops = ArrayList<Offset>(centre.size)
    val bottoms = ArrayList<Offset>(centre.size)
    centre.forEachIndexed { i, point ->
        val u = travelled * i / count
        val prev = centre[maxOf(0, i - 1)]
        val next = centre[minOf(centre.lastIndex, i + 1)]
        val tx = next.x - prev.x
        val ty = next.y - prev.y
        val len = hypot(tx, ty).coerceAtLeast(1e-4f)
        // A broad nib held at the scribe's angle: thick across the run,
        // thinning as the flick turns upward.
        val nx = -ty / len
        val ny = tx / len
        val attack = smoothstep(0f, 0.14f, u)
        val release = 1f - 0.9f * smoothstep(0.5f, 1f, u)
        val w = peakHalf * attack * release + peakHalf * 0.12f
        tops += Offset(point.x + nx * w, point.y + ny * w)
        bottoms += Offset(point.x - nx * w, point.y - ny * w)
    }
    return BrushOutline(top = tops, bottom = bottoms)
}

private fun DrawScope.drawQalamSwash(frame: MarkFrame, paint: Float, color: Color, presence: Float, rtl: Boolean) {
    val outline = qalamSwashOutline(
        frame = frame,
        progress = easeOutCubic(paint),
        rtl = rtl,
        gap = 3.dp.toPx(),
        reach = 6.dp.toPx(),
        peakHalf = 1.6.dp.toPx(),
        sag = 1.4.dp.toPx(),
        lift = 4.5.dp.toPx(),
    ) ?: return
    drawPath(outline.toPath(), color.copy(alpha = 0.86f * presence))
}

// ------------------------------------------------------------- shamsa rays

/** Sixteen: an illuminator's count, and never a six-fold star. */
internal const val SHAMSA_RAY_COUNT = 16

/** One ray: where it leaves the ring, its outward direction, and its full length. */
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

private fun DrawScope.drawShamsaRays(frame: MarkFrame, paint: Float, color: Color, presence: Float, rtl: Boolean) {
    val rays = shamsaRays(
        frame = frame,
        gap = 2.5.dp.toPx(),
        longRay = 4.5.dp.toPx(),
        shortRay = 2.4.dp.toPx(),
        halfWidth = 0.95.dp.toPx(),
        rtl = rtl,
    )
    val tint = color.copy(alpha = 0.7f * presence)
    val path = Path()
    rays.forEachIndexed { i, ray ->
        val grow = easeOutBack(staggered(paint, i, rays.size, spread = 0.55f), overshoot = 1.4f)
        if (grow <= 0f) return@forEachIndexed
        val len = ray.length * grow
        // Perpendicular to the ray for its foot.
        val px = -ray.dirY * ray.halfWidth
        val py = ray.dirX * ray.halfWidth
        val tip = Offset(ray.base.x + ray.dirX * len, ray.base.y + ray.dirY * len)
        // Sides bow inward, so each ray reads as a flame of the pen, not a spike.
        val waist = Offset(ray.base.x + ray.dirX * len * 0.45f, ray.base.y + ray.dirY * len * 0.45f)
        path.moveTo(ray.base.x + px, ray.base.y + py)
        path.quadraticTo(waist.x + px * 0.35f, waist.y + py * 0.35f, tip.x, tip.y)
        path.quadraticTo(waist.x - px * 0.35f, waist.y - py * 0.35f, ray.base.x - px, ray.base.y - py)
        path.close()
    }
    drawPath(path, tint)
}

// -------------------------------------------------------------- pearl ring

/** Fourteen beads: pearl, bead, pearl… and never a six-fold count. */
internal const val PEARL_RING_COUNT = 14

/** Bead centres round the mark, starting at the top and running the way the line reads. */
internal fun pearlRingCentres(frame: MarkFrame, gap: Float, rtl: Boolean, count: Int = PEARL_RING_COUNT): List<Offset> {
    val a = frame.rx + gap
    val b = frame.ry + gap
    val turn = if (rtl) -1f else 1f
    return List(count) { i ->
        val theta = -PI.toFloat() / 2f + turn * i * 2f * PI.toFloat() / count
        Offset(frame.cx + a * cos(theta), frame.cy + b * sin(theta))
    }
}

private fun DrawScope.drawPearlRing(frame: MarkFrame, paint: Float, color: Color, presence: Float, rtl: Boolean) {
    val centres = pearlRingCentres(frame, gap = 3.5.dp.toPx(), rtl = rtl)
    val pearl = 1.35.dp.toPx()
    val bead = 0.85.dp.toPx()
    val tint = color.copy(alpha = 0.8f * presence)
    centres.forEachIndexed { i, centre ->
        val local = staggered(paint, i, centres.size, spread = 0.6f)
        if (local <= 0f) return@forEachIndexed
        val size = (if (i % 2 == 0) pearl else bead) * easeOutBack(local)
        if (size <= 0.2f) return@forEachIndexed
        drawCircle(tint, radius = size, center = centre)
    }
}

// ---------------------------------------------------------------- tāj crown

/**
 * The tāj: the small crown an illuminator sets atop a verse or ḥizb medallion.
 * A teardrop rises from the cups' crown and two palmette volutes flare out
 * from its foot, curling up and back in, each ending in a pearl. Symmetric on
 * purpose — a single curl beside a number reads as a question mark.
 *
 * Sizes are dp for a 25 dp mark and scale with the mark, so the crown keeps
 * its proportion at every type size.
 */
internal object TajCrown {
    const val MARK_WIDTH_DP = 25f
    const val GAP_DP = 2.4f
    const val DROP_DP = 7f
    const val DROP_WIDTH_DP = 2.4f
    const val FOOT_DP = 0.9f
    const val VOLUTE_DP = 12f
    const val STEM_HALF_DP = 0.72f
    const val PEARL_DP = 0.8f

    /** Leaves the foot heading out and a touch down… */
    const val START_HEADING = 0.45f

    /** …then turns 315° the other way, so it lifts and curls back over itself. */
    val TURN = -1.75f * PI.toFloat()

    /** Share of the turn kept for the end: the curl tightens as it grows, like a tendril. */
    const val CURL_BIAS = 0.85f
}

/** The crown's own scale: 1 at a 25 dp mark. */
internal fun tajScale(frame: MarkFrame, dpPx: Float): Float =
    (2f * frame.rx) / (TajCrown.MARK_WIDTH_DP * dpPx)

/** Where the crown stands: centred over the cups, a hair above their top. */
internal fun tajFoot(frame: MarkFrame, dpPx: Float): Offset =
    Offset(frame.cx, frame.cy - frame.ry - TajCrown.GAP_DP * dpPx * tajScale(frame, dpPx))

/**
 * One volute's centreline, foot to curl, as points and unit tangents.
 * [right] picks the side; the left one is its mirror image.
 */
internal fun tajVolute(
    frame: MarkFrame,
    dpPx: Float,
    right: Boolean,
    steps: Int = 48,
): Pair<List<Offset>, List<Offset>> {
    val scale = tajScale(frame, dpPx)
    val foot = tajFoot(frame, dpPx)
    val side = if (right) 1f else -1f
    val step = TajCrown.VOLUTE_DP * dpPx * scale / steps
    var x = foot.x + side * TajCrown.FOOT_DP * dpPx * scale
    var y = foot.y
    val points = ArrayList<Offset>(steps + 1).apply { add(Offset(x, y)) }
    val tangents = ArrayList<Offset>(steps + 1).apply {
        add(Offset(side * cos(TajCrown.START_HEADING), sin(TajCrown.START_HEADING)))
    }
    for (i in 1..steps) {
        val u = (i - 0.5f) / steps
        val heading = TajCrown.START_HEADING +
            TajCrown.TURN * ((1f - TajCrown.CURL_BIAS) * u + TajCrown.CURL_BIAS * u.pow(2.2f))
        val tx = side * cos(heading)
        val ty = sin(heading)
        x += step * tx
        y += step * ty
        points += Offset(x, y)
        tangents += Offset(tx, ty)
    }
    return points to tangents
}

private fun DrawScope.drawTajCrown(frame: MarkFrame, paint: Float, color: Color, presence: Float) {
    if (paint <= 0f) return
    val dpPx = 1.dp.toPx()
    val scale = tajScale(frame, dpPx)
    val foot = tajFoot(frame, dpPx)
    val tint = color.copy(alpha = 0.85f * presence)

    // 1. The teardrop rises from the foot.
    val rise = easeOutBack(paint / 0.45f, overshoot = 1.5f)
    if (rise > 0f) {
        val length = TajCrown.DROP_DP * dpPx * scale * rise
        val halfWidth = TajCrown.DROP_WIDTH_DP * dpPx * scale * 0.5f * rise.coerceAtMost(1f)
        val drop = Path()
        val n = 24
        for (j in 0..n) {
            val t = j / n.toFloat()
            val off = halfWidth * sin(PI.toFloat() * t.pow(0.65f)) * (1f - 0.15f * t)
            val px = foot.x + off
            val py = foot.y - length * t
            if (j == 0) drop.moveTo(px, py) else drop.lineTo(px, py)
        }
        for (j in n downTo 0) {
            val t = j / n.toFloat()
            val off = halfWidth * sin(PI.toFloat() * t.pow(0.65f)) * (1f - 0.15f * t)
            drop.lineTo(foot.x - off, foot.y - length * t)
        }
        drop.close()
        drawPath(drop, tint)
    }

    // 2. Both volutes unfurl outward together, tapering as they curl.
    val unfurl = easeOutCubic((paint - 0.15f) / 0.6f)
    if (unfurl <= 0.02f) return
    val stemHalf = TajCrown.STEM_HALF_DP * dpPx * scale
    val hairline = 0.16f * dpPx * scale
    for (right in booleanArrayOf(true, false)) {
        val (points, tangents) = tajVolute(frame, dpPx, right)
        val last = (points.lastIndex * unfurl).toInt().coerceAtLeast(1)
        val tops = ArrayList<Offset>(last + 1)
        val bottoms = ArrayList<Offset>(last + 1)
        for (i in 0..last) {
            val u = i / points.lastIndex.toFloat()
            val w = stemHalf * (1f - 0.75f * u) * minOf(1f, u / 0.08f + 0.5f) + hairline
            val nx = -tangents[i].y
            val ny = tangents[i].x
            tops += Offset(points[i].x + nx * w, points[i].y + ny * w)
            bottoms += Offset(points[i].x - nx * w, points[i].y - ny * w)
        }
        drawPath(BrushOutline(top = tops, bottom = bottoms).toPath(), tint)

        // 3. A pearl sets at each curl's end once it has closed.
        val pearl = easeOutBack((paint - 0.72f) / 0.28f)
        if (pearl > 0f) {
            drawCircle(tint, radius = TajCrown.PEARL_DP * dpPx * scale * pearl, center = points.last())
        }
    }
}
