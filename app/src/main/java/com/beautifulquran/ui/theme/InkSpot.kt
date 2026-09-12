package com.beautifulquran.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ink-spot selector. Use this — not a circled word, not a pill — when
 * the choice is a short tool or icon strip. Any surface may wear it.
 *
 * - [inkSpotHighlight] is the effect: a circular ink drop behind
 *   whatever you already drew.
 * - [InkSpotChoice] is one option: stain + haptic + ink-strength.
 * - [InkSpotChoiceRow] is the strip.
 */

/**
 * A circular ink drop on vellum behind the chosen item. Android 13+
 * soaks the progressive-vellum pigment as a round stain with a fibre
 * rim; older platforms keep three soft circles. [seed] keeps each
 * splash a different grain. The stain lands and spreads in 170 ms.
 *
 * [fillBox] lays a pale even rounded-rect wash — fibre on the rim
 * only — so verse type stays readable. Size and opacity both follow
 * [progress]: select fades in as it grows, deselect fades out as it
 * recedes. Tool-strip drops still land mid-size.
 */
@Composable
fun Modifier.inkSpotHighlight(
    selected: Boolean,
    seed: Int,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fillBox: Boolean = false,
    durationMillis: Int = 170,
    easing: Easing = FastOutSlowInEasing,
): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = durationMillis, easing = easing),
        label = "inkSpot",
    )
    val shader = rememberVellumSpotShader()
    val brush = remember(shader) { shader?.let { ShaderBrush(it) } }
    val tuning = ContextualGuideStyle.tuning
    val fill = if (fillBox) 1f else 0f
    return drawBehind {
        if (progress <= 0.01f) return@drawBehind
        if (shader != null && brush != null) {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("progress", progress)
            shader.setFloatUniform("seed", seed.toFloat())
            shader.setFloatUniform("fadeSoftness", tuning.fadeSoftness)
            shader.setFloatUniform("vellumGrain", tuning.vellumGrain)
            shader.setFloatUniform("fill", fill)
            shader.setFloatUniform("rimInset", VerseSoakRimInset.toPx())
            shader.setColorUniform(
                "inkColor",
                android.graphics.Color.valueOf(
                    color.red,
                    color.green,
                    color.blue,
                    // Verse soaks keep the caller's ink; tool-strip drops
                    // are a concentrated stain and need the 0.38 wash.
                    if (fillBox) color.alpha else color.alpha * 0.38f,
                ),
            )
            drawRect(brush)
        } else {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val center = Offset(cx, cy)
            if (fillBox) {
                val half = verseSoakHalfSize(
                    width = size.width,
                    height = size.height,
                    progress = progress,
                    rimInset = VerseSoakRimInset.toPx(),
                )
                val cr = minOf(half.width, half.height) * 0.12f
                drawRoundRect(
                    color.copy(alpha = inkSpotAppear(progress) * color.alpha),
                    topLeft = Offset(cx - half.width, cy - half.height),
                    size = Size(half.width * 2f, half.height * 2f),
                    cornerRadius = CornerRadius(cr, cr),
                )
            } else {
                val reach = minOf(size.width, size.height) * 0.5f
                drawCircle(color.copy(alpha = 0.06f * progress), radius = reach * 0.92f, center = center)
                drawCircle(color.copy(alpha = 0.10f * progress), radius = reach * 0.74f, center = center)
                drawCircle(color.copy(alpha = 0.16f * progress), radius = reach * 0.56f, center = center)
            }
        }
    }
}

@Composable
private fun rememberVellumSpotShader(): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return remember { RuntimeShader(VellumSpotShader) }
}

/**
 * One option in an ink-spot selector. [content] receives the ink it should
 * draw with — full when chosen, faint otherwise.
 */
@Composable
fun InkSpotChoice(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    seed: Int = 0,
    contentPadding: Dp = 14.dp,
    content: @Composable (ink: Color) -> Unit,
) {
    val view = LocalView.current
    val inkAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else UnselectedChoiceInk,
        label = "inkSpotChoice",
    )
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = inkAlpha)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .inkSpotHighlight(selected, seed)
            .quietClickable {
                if (!selected) view.paperSelectHaptic()
                onSelect()
            }
            .padding(contentPadding),
    ) {
        content(ink)
    }
}

/**
 * A short strip of options. The chosen one sits on an ink drop.
 * [selected] may be null so a strip can rest with nothing stained.
 */
@Composable
fun <T> InkSpotChoiceRow(
    entries: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    contentPadding: Dp = 14.dp,
    content: @Composable (entry: T, selected: Boolean, ink: Color) -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEachIndexed { index, entry ->
            val on = entry == selected
            InkSpotChoice(
                selected = on,
                onSelect = { onSelect(entry) },
                seed = index,
                contentPadding = contentPadding,
            ) { ink ->
                content(entry, on, ink)
            }
        }
    }
}

/** Text-only strip — labels wear the same ink-spot effect. */
@Composable
fun <T> InkSpotChoiceRow(
    entries: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    spacing: Dp = 6.dp,
) {
    InkSpotChoiceRow(
        entries = entries,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        spacing = spacing,
    ) { entry, _, ink ->
        Text(text = label(entry), style = textStyle, color = ink)
    }
}

/** Pigment follows [progress] so select fades in and deselect fades out. */
internal fun inkSpotAppear(progress: Float): Float = progress.coerceIn(0f, 1f)

/**
 * Pixel margin the verse soak reserves for its warped, diffused rim, so
 * the organic edge is never cut square by the draw rect. At the usual
 * reader density this is the same room the old 93% ceiling left across
 * the width of a verse — it is now the room left on every side.
 */
internal val VerseSoakRimInset = 9.dp

/** Where the wash opens, as a fraction of the block, before it grows. */
internal const val VerseSoakStart = 0.36f

/**
 * Half-extents of the verse soak's rounded rectangle, in pixels.
 *
 * The wash used to land at 93% of the block in **both** axes. That reads
 * right on a short verse and fails on a long one: the rim inset was a
 * fraction, so raising the reader's text size made the block taller and
 * grew the inset with it, until it exceeded the block's own 14dp padding
 * and the first and last lines of a gathered verse sat outside their own
 * highlight. The margin is a fixed [rimInset] instead, which is what the
 * rim actually needs, so coverage no longer depends on how tall the verse
 * is set. [rimInset] is capped at half the block so a one-line verse
 * cannot invert.
 */
internal fun verseSoakHalfSize(
    width: Float,
    height: Float,
    progress: Float,
    rimInset: Float,
): Size {
    val halfWidth = width * 0.5f
    val halfHeight = height * 0.5f
    val grownWidth = maxOf(halfWidth - rimInset, halfWidth * 0.5f)
    val grownHeight = maxOf(halfHeight - rimInset, halfHeight * 0.5f)
    val t = progress.coerceIn(0f, 1f)
    return Size(
        halfWidth * VerseSoakStart + (grownWidth - halfWidth * VerseSoakStart) * t,
        halfHeight * VerseSoakStart + (grownHeight - halfHeight * VerseSoakStart) * t,
    )
}

/** Closed cubic blot. [scale] > 1 draws the fainter outer soak. */
fun inkSpotPath(
    width: Float,
    height: Float,
    seed: Int,
    pad: Float,
    scale: Float = 1f,
): Path = inkSpotOutline(width, height, seed, pad, scale).toClosedCubic()

/** Boundary samples of the blot — the testable half. */
internal fun inkSpotOutline(
    width: Float,
    height: Float,
    seed: Int,
    pad: Float,
    scale: Float = 1f,
    steps: Int = 16,
): List<Offset> {
    val reach = minOf(width, height) * 0.5f + pad
    val cx = width / 2f + hash11(seed, 1) * reach * 0.03f
    val cy = height / 2f + hash11(seed, 2) * reach * 0.03f
    val radius = reach * scale
    val phase = hash11(seed, 3) * PI.toFloat()
    return List(steps) { i ->
        val t = i * 2f * PI.toFloat() / steps
        val wobble = 1f + 0.03f * sin(3f * t + phase)
        Offset(cx + cos(t) * radius * wobble, cy + sin(t) * radius * wobble)
    }
}

/** Catmull-Rom through [this] as a closed cubic path. */
internal fun List<Offset>.toClosedCubic(): Path {
    val path = Path()
    if (size < 3) return path
    val n = size
    path.moveTo(this[0].x, this[0].y)
    for (i in 0 until n) {
        val p0 = this[(i - 1 + n) % n]
        val p1 = this[i]
        val p2 = this[(i + 1) % n]
        val p3 = this[(i + 2) % n]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f,
            p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f,
            p2.y - (p3.y - p1.y) / 6f,
            p2.x,
            p2.y,
        )
    }
    path.close()
    return path
}

/** Deterministic −1..1 from [seed] and a slot. */
internal fun hash11(seed: Int, slot: Int): Float {
    var x = seed * 374_761_393 + slot * 668_265_263
    x = (x xor (x ushr 13)) * 1_274_126_177
    return ((x ushr 16) and 0xFFFF) / 32767.5f - 1f
}
