package com.beautifulquran.ui.theme

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beautifulquran.data.BrushCircleStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The app's hand-drawn selection marks: an ink-brush circle that loops around a
 * chosen word, a brush check for on/off, and a plain inked disc.
 *
 * These are the paper metaphor's answer to Material's radio buttons, checkboxes
 * and segmented containers (see docs/DESIGN.md — no cards, no borders, hierarchy
 * from ink). They started out private to the Settings sheet; they live here so
 * any surface can use the same vocabulary. The Settings brush lab still tunes
 * the [BrushCircleParams] / [BrushCheckParams] knobs and remains the only place
 * that writes them.
 *
 * Web counterparts: `brushMark.ts` (circle) and `brushCheck.ts` (check). Keep
 * the params, the shipped revisions, and the style labels in lockstep.
 */

// ---------------------------------------------------------------- haptics

/** Soft selection tick — same family as ayah-rail commits, not a heavy long-press. */
fun View.paperSelectHaptic() {
    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
}

/** Toggle tick — confirm when turning on, lighter clock tick when off. */
fun View.paperToggleHaptic(turningOn: Boolean) {
    performHapticFeedback(
        if (turningOn) HapticFeedbackConstants.CONTEXT_CLICK
        else HapticFeedbackConstants.CLOCK_TICK,
    )
}

// ------------------------------------------------------------------ params

/**
 * Shipped ink-brush circle. Bump when defaults change so the lab reseeds.
 * Keep in lockstep with web `SHIPPED_BRUSH_REVISION` / BASE in brushMark.ts.
 */
const val SHIPPED_BRUSH_REVISION = 9

/** Bump when [BrushCheckParams] defaults change so the lab reseeds. */
const val SHIPPED_CHECK_REVISION = 2

/** Dimensionless (dp-valued) knobs for one ink-brush circle style. */
data class BrushCircleParams(
    val label: String,
    val padXDp: Float = 15.5f,
    val padYDp: Float = 6f,
    val peakHalfDp: Float = 2.2f,
    /** Nominal join angle. Tips overshoot past this join. */
    val startDeg: Float = 254f,
    /** Degrees the entry tip begins *before* the join. */
    val startOvershoot: Float = 43f,
    /** Degrees the exit tip continues past a full turn past the join. */
    val endOvershoot: Float = 22f,
    /**
     * Radial bow at the tips (dp): entry tip eases outward, exit tip eases
     * inward so the ends cross in a bow rather than riding the same track.
     */
    val bow: Float = 4.25f,
    /** Fraction of stroke length used to ease the bow in/out at each tip. */
    val bowSpan: Float = 0.19f,
    val breath: Float = 0.025f,
    val nibBias: Float = 0.58f,
    val attack: Float = 0.195f,
    val releaseStart: Float = 0.6f,
    val bodyAmp: Float = 0.34f,
    val bodyFreq: Float = 5f,
    val paintMs: Int = 620,
    val alpha: Float = 0.9f,
)

/** Lab-tunable ink check. Keep in lockstep with web brushCheck.ts. */
data class BrushCheckParams(
    val p0x: Float = 0.1f,
    val p0y: Float = 0.49f,
    val p1x: Float = 0.39f,
    val p1y: Float = 0.8f,
    val p2x: Float = 0.73f,
    val p2y: Float = 0.11f,
    val sizeDp: Float = 24f,
    val peakHalfDp: Float = 1.68f,
    val nibBias: Float = 0.56f,
    val attack: Float = 0.184f,
    val releaseStart: Float = 0.74f,
    val bodyAmp: Float = 0.1f,
    val bodyFreq: Float = 2.2f,
    val paintMs: Int = 833,
    val alpha: Float = 0.75f,
)

fun shippedCheckParams() = BrushCheckParams()

/** Baseline + 10 developer variants — keep labels aligned with web brushMark.ts. */
fun brushCircleParams(style: BrushCircleStyle): BrushCircleParams = when (style) {
    BrushCircleStyle.BASELINE -> BrushCircleParams(
        label = "Baseline · current",
        padXDp = 15.5f,
        padYDp = 6f,
        peakHalfDp = 2.2f,
        startDeg = 254f,
        startOvershoot = 43f,
        endOvershoot = 22f,
        bow = 4.25f,
        bowSpan = 0.19f,
        breath = 0.025f,
        nibBias = 0.58f,
        attack = 0.195f,
        releaseStart = 0.6f,
        bodyAmp = 0.34f,
        bodyFreq = 5f,
        paintMs = 620,
        alpha = 0.9f,
    )
    BrushCircleStyle.HAIRLINE -> BrushCircleParams(
        label = "Hairline",
        peakHalfDp = 1.35f,
        alpha = 0.82f,
        bodyAmp = 0.12f,
    )
    BrushCircleStyle.HEAVY -> BrushCircleParams(
        label = "Heavy ink",
        peakHalfDp = 3.2f,
        alpha = 0.95f,
        bodyAmp = 0.18f,
    )
    BrushCircleStyle.TIGHT -> BrushCircleParams(
        label = "Tight frame",
        padXDp = 6f,
        padYDp = 1f,
        peakHalfDp = 1.9f,
    )
    BrushCircleStyle.LOOSE -> BrushCircleParams(
        label = "Loose frame",
        padXDp = 16f,
        padYDp = 5f,
        peakHalfDp = 2.3f,
    )
    BrushCircleStyle.SHARP_NIB -> BrushCircleParams(
        label = "Sharp nib",
        nibBias = 0.42f,
        peakHalfDp = 2.0f,
    )
    BrushCircleStyle.SOFT_NIB -> BrushCircleParams(
        label = "Soft nib",
        nibBias = 0.06f,
        peakHalfDp = 2.3f,
        bodyAmp = 0.1f,
    )
    BrushCircleStyle.LONG_OVERSHOOT -> BrushCircleParams(
        label = "Long overshoot",
        startOvershoot = 22f,
        endOvershoot = 40f,
        bow = 6.5f,
        bowSpan = 0.22f,
        releaseStart = 0.82f,
        paintMs = 640,
    )
    BrushCircleStyle.CLOSED_RING -> BrushCircleParams(
        label = "Nearly closed",
        startOvershoot = 6f,
        endOvershoot = 6f,
        bow = 2.2f,
        bowSpan = 0.12f,
        releaseStart = 0.92f,
        attack = 0.06f,
    )
    BrushCircleStyle.LIVELY -> BrushCircleParams(
        label = "Lively breath",
        breath = 0.038f,
        bodyAmp = 0.32f,
        bodyFreq = 4.5f,
        peakHalfDp = 2.25f,
        bow = 5.5f,
    )
    BrushCircleStyle.DRY_BRUSH -> BrushCircleParams(
        label = "Dry brush",
        peakHalfDp = 1.7f,
        bodyAmp = 0.45f,
        bodyFreq = 7.0f,
        attack = 0.14f,
        releaseStart = 0.8f,
        alpha = 0.78f,
        paintMs = 520,
        bow = 3.5f,
    )
}

// ------------------------------------------------------- circle: state + use

/**
 * The live ink-brush circle for one group of choices: which child to loop, and
 * how far the stroke has painted.
 *
 * Created by [rememberInkBrushCircle]. Mark every choice with
 * [Modifier.inkBrushCircleTarget] and put [Modifier.inkBrushCircleMark] on their
 * container; the stroke then paints itself around whichever child is selected.
 * Works for a `Row` or a `Column` — the mark is derived from the child's own
 * measured bounds, not the container's.
 */
class InkBrushCircle internal constructor(
    val params: BrushCircleParams,
    internal val progress: State<Float>,
    internal val bounds: MutableMap<Any, Rect>,
)

/**
 * Remembers an [InkBrushCircle] that repaints whenever [selectedKey] changes.
 *
 * [paintToken] re-triggers the stroke without a selection change (the brush lab
 * uses it to preview a tuning); [params] default to the shipped baseline, so a
 * caller that does not care about lab tuning can ignore them entirely.
 */
@Composable
fun rememberInkBrushCircle(
    selectedKey: Any?,
    params: BrushCircleParams = brushCircleParams(BrushCircleStyle.BASELINE),
    paintToken: Int = 0,
): InkBrushCircle {
    val bounds = remember { mutableStateMapOf<Any, Rect>() }
    // Fresh Animatable per pick/token so the first frame is empty, then paints.
    val paint = remember(selectedKey, paintToken) { Animatable(0f) }
    val hasBounds = selectedKey != null && (bounds[selectedKey]?.width ?: 0f) > 0f
    LaunchedEffect(selectedKey, hasBounds, paintToken, params) {
        if (!hasBounds) return@LaunchedEffect
        paint.snapTo(0f)
        paint.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = params.paintMs,
                easing = FastOutSlowInEasing,
            ),
        )
    }
    return remember(params, paint, bounds) {
        InkBrushCircle(params = params, progress = paint.asState(), bounds = bounds)
    }
}

/** Reports this choice's bounds to [circle] under [key]. */
fun Modifier.inkBrushCircleTarget(circle: InkBrushCircle, key: Any): Modifier =
    onGloballyPositioned { coords -> circle.bounds[key] = coords.boundsInParent() }

/**
 * Paints [circle]'s stroke behind the container's children, looping the child
 * registered under [selectedKey]. [color] defaults to the theme's accent ink.
 */
@Composable
fun Modifier.inkBrushCircleMark(
    circle: InkBrushCircle,
    selectedKey: Any?,
    color: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    val params = circle.params
    return drawBehind {
        val progress = circle.progress.value
        val target = selectedKey?.let { circle.bounds[it] } ?: return@drawBehind
        if (target.width <= 0f || progress <= 0f) return@drawBehind
        val padX = params.padXDp.dp.toPx()
        val padY = params.padYDp.dp.toPx()
        val mark = inkBrushCirclePath(
            cx = target.center.x,
            cy = target.center.y,
            rx = target.width / 2f + padX,
            ry = (target.height / 2f - padY).coerceAtLeast(10.dp.toPx()),
            peakHalf = params.peakHalfDp.dp.toPx(),
            bowPx = params.bow.dp.toPx(),
            progress = progress,
            params = params,
        )
        drawPath(path = mark, color = color.copy(alpha = params.alpha))
    }
}

// --------------------------------------------------------- circle: choice rows

/**
 * A short enum laid out side by side. The chosen word is *circled* by an
 * ink-brush stroke that paints itself around the letters.
 */
@Composable
fun <T> InkCircledChoiceRow(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    params: BrushCircleParams = brushCircleParams(BrushCircleStyle.BASELINE),
    paintToken: Int = 0,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    spacing: androidx.compose.ui.unit.Dp = 28.dp,
) {
    val selectedIndex = entries.indexOfFirst { it == selected }.coerceAtLeast(0)
    val circle = rememberInkBrushCircle(selectedIndex, params, paintToken)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .inkBrushCircleMark(circle, selectedIndex),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        entries.forEachIndexed { index, entry ->
            InkCircledChoiceLabel(
                text = label(entry),
                selected = entry == selected,
                textStyle = textStyle,
                onSelect = { onSelect(entry) },
                modifier = Modifier
                    .inkBrushCircleTarget(circle, index)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * The same vocabulary stacked vertically, for choices whose labels are too long
 * to sit side by side (the reader's repeat sheet). Each line fills the width, so
 * the stroke loops the whole line.
 */
@Composable
fun <T> InkCircledChoiceColumn(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    params: BrushCircleParams = brushCircleParams(BrushCircleStyle.BASELINE),
    paintToken: Int = 0,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    val selectedIndex = entries.indexOfFirst { it == selected }.coerceAtLeast(0)
    val circle = rememberInkBrushCircle(selectedIndex, params, paintToken)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .inkBrushCircleMark(circle, selectedIndex),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        entries.forEachIndexed { index, entry ->
            InkCircledChoiceLabel(
                text = label(entry),
                selected = entry == selected,
                textStyle = textStyle,
                textAlign = TextAlign.Center,
                onSelect = { onSelect(entry) },
                modifier = Modifier
                    .fillMaxWidth()
                    .inkBrushCircleTarget(circle, index),
            )
        }
    }
}

/** One choice word: full ink when chosen, faint otherwise, quiet to the touch. */
@Composable
private fun InkCircledChoiceLabel(
    text: String,
    selected: Boolean,
    textStyle: TextStyle,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val view = LocalView.current
    val ink by animateFloatAsState(
        targetValue = if (selected) 1f else UnselectedChoiceInk,
        label = "choiceInk",
    )
    Text(
        text = text,
        style = textStyle,
        textAlign = textAlign,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ink),
        modifier = modifier
            .quietClickable {
                if (!selected) view.paperSelectHaptic()
                onSelect()
            }
            .padding(vertical = 10.dp),
    )
}

/** Resting ink of an unchosen choice word — faint but still legible on paper. */
const val UnselectedChoiceInk = 0.42f

// ----------------------------------------------------------- check + disc

/** The on/off mark: empty ring at rest; lab-tunable brush check paints on. */
@Composable
fun InkCheck(
    checked: Boolean,
    params: BrushCheckParams = shippedCheckParams(),
    paintToken: Int = 0,
) {
    val paint = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked, paintToken, params) {
        if (!checked) {
            paint.snapTo(0f)
            return@LaunchedEffect
        }
        paint.snapTo(0.02f)
        paint.animateTo(
            1f,
            androidx.compose.animation.core.tween(
                durationMillis = params.paintMs,
                easing = FastOutSlowInEasing,
            ),
        )
    }
    val progress = paint.value
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(params.sizeDp.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        drawCircle(
            color = outline.copy(alpha = 0.5f * (1f - progress.coerceIn(0f, 1f))),
            radius = r - 1.2.dp.toPx(),
            center = c,
            style = Stroke(width = 1.4.dp.toPx()),
        )
        if (progress > 0.02f) {
            val mark = inkBrushCheckPath(
                size = size.minDimension,
                progress = progress,
                params = params,
            )
            drawPath(path = mark, color = accent.copy(alpha = params.alpha))
        }
    }
}

/**
 * The plain selection mark: an accent disc that inks in when chosen and settles
 * to a faint hollow ring when not — one vocabulary for every choice.
 */
@Composable
fun InkDisc(selected: Boolean) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, label = "discFill")
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(18.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        // Faint resting ring, fading out as the fill arrives.
        drawCircle(
            color = outline.copy(alpha = 0.5f * (1f - fill)),
            radius = r - 1.2.dp.toPx(),
            center = c,
            style = Stroke(width = 1.4.dp.toPx()),
        )
        // Accent disc, inked in on selection.
        drawCircle(
            color = accent.copy(alpha = fill),
            radius = (r - 2.dp.toPx()) * fill,
            center = c,
        )
    }
}

// ------------------------------------------------------------ path primitives

/**
 * The two edges of a filled brush ribbon: the stroke's centreline offset by
 * ±half its pressure-varying width, in draw order.
 *
 * Kept as plain [Offset] lists rather than a [Path] so the geometry is pure and
 * JVM-testable — the same split `OrnamentGenerator` uses (pure specs out,
 * Compose drawing separate). `androidx.compose.ui.graphics.Path` wraps
 * `android.graphics.Path`, which is stubbed in unit tests; [toPath] is the only
 * Android-bound step and holds no logic.
 */
class BrushOutline internal constructor(
    val top: List<Offset>,
    val bottom: List<Offset>,
) {
    /** Traces the top edge forward and the bottom edge back, then closes. */
    fun toPath(): Path = Path().apply {
        moveTo(top[0].x, top[0].y)
        for (i in 1 until top.size) lineTo(top[i].x, top[i].y)
        for (i in bottom.lastIndex downTo 0) lineTo(bottom[i].x, bottom[i].y)
        close()
    }
}

/**
 * Real ink-brush loop around a word: filled calligraphic stroke on an oval
 * centerline. Matches web `brushMarkPath`.
 */
fun inkBrushCirclePath(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    peakHalf: Float,
    bowPx: Float,
    progress: Float,
    params: BrushCircleParams,
): Path = inkBrushCircleOutline(
    cx, cy, rx, ry, peakHalf, bowPx, progress, params,
).toPath()

/** [inkBrushCirclePath]'s geometry, before it becomes an Android path. */
internal fun inkBrushCircleOutline(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    peakHalf: Float,
    bowPx: Float,
    progress: Float,
    params: BrushCircleParams,
): BrushOutline {
    // Entry tip starts before the join; exit tip runs past a full turn past it.
    val start = Math.toRadians(
        (params.startDeg - params.startOvershoot).toDouble(),
    ).toFloat()
    val sweep = Math.toRadians(
        (360f + params.startOvershoot + params.endOvershoot).toDouble(),
    ).toFloat()
    val steps = 72
    val endStep = (steps * progress.coerceIn(0.02f, 1f)).toInt().coerceAtLeast(1)
    val tops = ArrayList<Offset>(endStep + 1)
    val bots = ArrayList<Offset>(endStep + 1)
    for (i in 0..endStep) {
        val t = i / steps.toFloat()
        val a = start + sweep * t
        val breath = 1f + params.breath * sin(a * 2f + 0.4f)
        val cosA = cos(a)
        val sinA = sin(a)
        val bow = bowOffset(t, bowPx, params.bowSpan)
        var x = cx + cosA * (rx * breath + bow)
        var y = cy + sinA * (ry * breath + bow)
        val tx = -sinA * rx
        val ty = cosA * ry
        val tLen = hypot(tx, ty).coerceAtLeast(1f)
        var nx = -ty / tLen
        var ny = tx / tLen
        val bx = nx + (-ny) * params.nibBias
        val by = ny + nx * params.nibBias
        val nLen = hypot(bx, by).coerceAtLeast(1f)
        nx = bx / nLen
        ny = by / nLen
        // A touch of normal offset at the tips tightens the X of the bow.
        val cross = bow * 0.28f
        x += nx * cross
        y += ny * cross

        val half = peakHalf * brushPressure(t, params)
        tops.add(Offset(x + nx * half, y + ny * half))
        bots.add(Offset(x - nx * half, y - ny * half))
    }
    return BrushOutline(top = tops, bottom = bots)
}

/**
 * Radial tip offset: positive near t=0 (entry out), negative near t=1 (exit in),
 * so the overshooting tips cross in a bow instead of stacking on one curve.
 */
private fun bowOffset(t: Float, bow: Float, span: Float): Float {
    if (bow <= 0f || span <= 0f) return 0f
    val s = span.coerceIn(0.04f, 0.45f)
    return when {
        t < s -> {
            val u = 1f - t / s
            bow * u * u
        }
        t > 1f - s -> {
            val u = (t - (1f - s)) / s
            -bow * u * u
        }
        else -> 0f
    }
}

internal fun brushPressure(t: Float, params: BrushCircleParams): Float {
    val attack = (t / params.attack).coerceIn(0f, 1f)
    val releaseSpan = (1f - params.releaseStart).coerceAtLeast(0.04f)
    val release = if (t > params.releaseStart) {
        ((1f - t) / releaseSpan).coerceAtLeast(0.12f)
    } else {
        1f
    }
    val body = 0.78f + params.bodyAmp * sin(t * PI.toFloat() * params.bodyFreq + 0.3f)
    return (attack * release * body).coerceAtLeast(0.1f)
}

/** Filled brush check from [BrushCheckParams]. Matches web `brushCheckPath`. */
fun inkBrushCheckPath(
    size: Float,
    progress: Float,
    params: BrushCheckParams,
): Path = inkBrushCheckOutline(size, progress, params).toPath()

/** [inkBrushCheckPath]'s geometry, before it becomes an Android path. */
internal fun inkBrushCheckOutline(
    size: Float,
    progress: Float,
    params: BrushCheckParams,
): BrushOutline {
    val prog = progress.coerceIn(0.02f, 1f)
    val center = listOf(
        Offset(params.p0x, params.p0y),
        Offset(params.p1x, params.p1y),
        Offset(params.p2x, params.p2y),
    )
    val segs = 24
    val raw = ArrayList<Offset>((center.size - 1) * segs + 1)
    for (s in 0 until center.lastIndex) {
        val a = center[s]
        val b = center[s + 1]
        for (i in 0 until segs) {
            val u = i / segs.toFloat()
            raw.add(Offset(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u))
        }
    }
    raw.add(center.last())

    var total = 0f
    val lens = FloatArray(raw.size)
    for (i in 1 until raw.size) {
        total += hypot(raw[i].x - raw[i - 1].x, raw[i].y - raw[i - 1].y)
        lens[i] = total
    }
    // peakHalfDp is absolute in the same unit system as sizeDp (web: peakHalf vs size px).
    val peakHalfPx = params.peakHalfDp * (size / params.sizeDp.coerceAtLeast(1f))
    val tops = ArrayList<Offset>()
    val bots = ArrayList<Offset>()
    for (i in raw.indices) {
        val t = if (total > 0f) lens[i] / total else 0f
        if (t > prog && tops.isNotEmpty()) break
        val prev = raw[maxOf(0, i - 1)]
        val next = raw[minOf(raw.lastIndex, i + 1)]
        var tx = next.x - prev.x
        var ty = next.y - prev.y
        val tLen = hypot(tx, ty).coerceAtLeast(1e-4f)
        tx /= tLen
        ty /= tLen
        var nx = -ty
        var ny = tx
        val bx = nx + (-ny) * params.nibBias
        val by = ny + nx * params.nibBias
        val nLen = hypot(bx, by).coerceAtLeast(1e-4f)
        nx = bx / nLen
        ny = by / nLen
        val half = peakHalfPx * brushCheckPressure(t, params)
        val x = raw[i].x * size
        val y = raw[i].y * size
        tops.add(Offset(x + nx * half, y + ny * half))
        bots.add(Offset(x - nx * half, y - ny * half))
    }
    if (tops.size < 2) {
        val a = raw[0]
        val b = raw[1]
        val x0 = a.x * size
        val y0 = a.y * size
        val x1 = (a.x + (b.x - a.x) * 0.08f) * size
        val y1 = (a.y + (b.y - a.y) * 0.08f) * size
        tops.clear()
        bots.clear()
        tops.add(Offset(x0, y0 - peakHalfPx * 0.3f))
        tops.add(Offset(x1, y1 - peakHalfPx * 0.3f))
        bots.add(Offset(x0, y0 + peakHalfPx * 0.3f))
        bots.add(Offset(x1, y1 + peakHalfPx * 0.3f))
    }
    return BrushOutline(top = tops, bottom = bots)
}

internal fun brushCheckPressure(t: Float, params: BrushCheckParams): Float {
    val attack = (t / params.attack).coerceIn(0f, 1f)
    val releaseSpan = (1f - params.releaseStart).coerceAtLeast(0.04f)
    val release = if (t > params.releaseStart) {
        ((1f - t) / releaseSpan).coerceAtLeast(0.12f)
    } else {
        1f
    }
    val body = 0.78f + params.bodyAmp * sin(t * PI.toFloat() * params.bodyFreq + 0.3f)
    return (attack * release * body).coerceAtLeast(0.1f)
}
