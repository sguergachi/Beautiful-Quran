package com.beautifulquran.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/*
 * Procedural Islamic ornament, drawn — never imaged — so it stays crisp at
 * any density and costs almost nothing.
 *
 * Three techniques, used sparingly:
 *  - KHATAM geometry: the classical eight-fold star (two overlapped squares)
 *    and the {8/3} octagram, the foundation of star-and-cross tessellation.
 *  - GILDING: gold is never flat; it is a three-stop gradient (deep bronze →
 *    bright gilt → deep bronze) whose axis tilts with page scroll, so light
 *    appears to catch the leaf as the sheet moves. The sheen value is read
 *    inside the draw phase only — it re-draws during scroll frames (which
 *    are being produced anyway) and never recomposes anything.
 *  - EMBOSSING: each figure is drawn three times — a dark copy nudged toward
 *    the lower-right, a light copy nudged toward the upper-left, then the
 *    face — reading as relief pressed into the paper under a top-left light.
 */

/** Gilded gold ramp; [t] 0..1 tilts the lighting axis. */
internal fun DrawScope.goldBrush(bright: Color, deep: Color, t: Float): Brush {
    val w = size.width
    val h = size.height
    return Brush.linearGradient(
        colors = listOf(deep, bright, deep),
        start = Offset(0f, h * t),
        end = Offset(w, h * (1f - t)),
    )
}

/** Appends the classical khatam (two squares, one at 45°) around a center. */
private fun Path.addKhatam(cx: Float, cy: Float, vertexRadius: Float) {
    val a = vertexRadius * 0.7071f // half-side of the axis-aligned square
    moveTo(cx - a, cy - a)
    lineTo(cx + a, cy - a)
    lineTo(cx + a, cy + a)
    lineTo(cx - a, cy + a)
    close()
    moveTo(cx, cy - vertexRadius)
    lineTo(cx + vertexRadius, cy)
    lineTo(cx, cy + vertexRadius)
    lineTo(cx - vertexRadius, cy)
    close()
}

/** Appends the {8/3} octagram — every third vertex of the octagon. */
private fun Path.addOctagram(cx: Float, cy: Float, radius: Float) {
    // Octagon vertices at 22.5° + k*45°, visited with step 3 (returns after 8).
    val angles = FloatArray(8) { Math.toRadians(22.5 + it * 45.0).toFloat() }
    var k = 0
    repeat(9) { i ->
        val x = cx + radius * kotlin.math.cos(angles[k])
        val y = cy + radius * kotlin.math.sin(angles[k])
        if (i == 0) moveTo(x, y) else lineTo(x, y)
        k = (k + 3) % 8
    }
    close()
}

private fun DrawScope.embossed(path: Path, stroke: Stroke, dark: Color, light: Color) {
    translate(0.8f, 0.8f) { drawPath(path, dark, style = stroke) }
    translate(-0.8f, -0.8f) { drawPath(path, light, style = stroke) }
}

/**
 * An eight-fold gilded rosette: khatam ring, interlaced octagram, a centre
 * seed, and eight vertex pearls — embossed into the paper, faced in shifting
 * gold. [sheen] is read only at draw time.
 */
@Composable
fun GildedRosette(
    size: Dp,
    brightGold: Color,
    deepGold: Color,
    embossDark: Color,
    embossLight: Color,
    sheen: State<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.then(Modifier.size(size))) {
        val s = min(this.size.width, this.size.height)
        val c = s / 2f
        val line = Stroke(width = s * 0.016f)

        val khatam = Path().apply { addKhatam(c, c, s * 0.46f) }
        val octagram = Path().apply { addOctagram(c, c, s * 0.33f) }

        // Relief first, face last.
        embossed(khatam, line, embossDark, embossLight)
        embossed(octagram, line, embossDark, embossLight)

        val gold = goldBrush(brightGold, deepGold, sheen.value.coerceIn(0f, 1f))
        drawPath(khatam, gold, style = line)
        drawPath(octagram, gold, style = line)

        // Centre seed and vertex pearls.
        drawCircle(gold, radius = s * 0.035f, center = Offset(c, c))
        for (k in 0 until 8) {
            val ang = Math.toRadians(k * 45.0).toFloat()
            drawCircle(
                brush = gold,
                radius = s * 0.018f,
                center = Offset(
                    c + s * 0.46f * kotlin.math.cos(ang),
                    c + s * 0.46f * kotlin.math.sin(ang),
                ),
            )
        }
    }
}

/**
 * The tooled frame of the entrance cover: a doubled gilt rule following the
 * sheet's edge — the fillets a hand-bound mushaf carries on its leather.
 * The generated border frieze and corner seals (GeneratedOrnament.kt) live
 * between and over these rules. Fills whatever it is given; meant to sit
 * full-bleed on the cover.
 *
 * [geometry] is the concentric inset/radius set derived from the display's
 * corner radii ([com.beautifulquran.ui.entrance.coverFrameGeometry]) so the
 * gilt rule shares the phone's silhouette rather than a fixed square frame.
 */
@Composable
fun MushafCoverFrame(
    brightGold: Color,
    deepGold: Color,
    embossDark: Color,
    embossLight: Color,
    sheen: State<Float>,
    geometry: com.beautifulquran.ui.entrance.CoverFrameGeometry,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val outerInset = geometry.outerInsetPx
        val innerInset = geometry.innerInsetPx
        val rule = Stroke(width = 2.dp.toPx())
        val hairline = Stroke(width = 1.dp.toPx())

        val oc = geometry.outerCorners
        val ic = geometry.innerCorners
        val outer = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(
                        left = outerInset,
                        top = outerInset,
                        right = size.width - outerInset,
                        bottom = size.height - outerInset,
                    ),
                    topLeft = CornerRadius(oc.topLeft, oc.topLeft),
                    topRight = CornerRadius(oc.topRight, oc.topRight),
                    bottomRight = CornerRadius(oc.bottomRight, oc.bottomRight),
                    bottomLeft = CornerRadius(oc.bottomLeft, oc.bottomLeft),
                ),
            )
        }
        val inner = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(
                        left = innerInset,
                        top = innerInset,
                        right = size.width - innerInset,
                        bottom = size.height - innerInset,
                    ),
                    topLeft = CornerRadius(ic.topLeft, ic.topLeft),
                    topRight = CornerRadius(ic.topRight, ic.topRight),
                    bottomRight = CornerRadius(ic.bottomRight, ic.bottomRight),
                    bottomLeft = CornerRadius(ic.bottomLeft, ic.bottomLeft),
                ),
            )
        }
        embossed(outer, rule, embossDark, embossLight)

        val gold = goldBrush(brightGold, deepGold, sheen.value.coerceIn(0f, 1f))
        drawPath(outer, gold, style = rule)
        drawPath(inner, gold, style = hairline)
    }
}

/**
 * A small horizontal flourish for flanking a title: a gilded khatam star at
 * the inner end with a hairline rule tapering away from it, embossed like the
 * rosette. [mirrored] flips it for the far side of the title.
 */
@Composable
fun GildedFlourish(
    width: Dp,
    height: Dp,
    brightGold: Color,
    deepGold: Color,
    embossDark: Color,
    embossLight: Color,
    sheen: State<Float>,
    mirrored: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .then(Modifier.size(width, height))
            .graphicsLayer { scaleX = if (mirrored) -1f else 1f },
    ) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val starR = h * 0.42f
        val starCx = w - starR - 1f
        val star = Path().apply { addKhatam(starCx, cy, starR) }
        val line = Stroke(width = (h * 0.07f).coerceAtLeast(1f))

        embossed(star, line, embossDark, embossLight)
        val gold = goldBrush(brightGold, deepGold, sheen.value.coerceIn(0f, 1f))
        drawPath(star, gold, style = line)
        drawCircle(gold, radius = h * 0.07f, center = Offset(starCx, cy))

        // Rule tapering to nothing away from the star.
        val ruleEnd = starCx - starR - h * 0.25f
        if (ruleEnd > 0f) {
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(deepGold.copy(alpha = 0f), deepGold),
                    startX = 0f,
                    endX = ruleEnd,
                ),
                start = Offset(0f, cy),
                end = Offset(ruleEnd, cy),
                strokeWidth = line.width,
            )
        }
    }
}

/**
 * Gilds the content it wraps: a vertical gold-leaf gradient is composited
 * onto the rendered glyphs (SrcAtop), so text itself reads as leafed gold.
 * The offscreen layer is only the size of the mark — a few dp — so this is
 * reserved for small ornaments like ayah numbers.
 */
fun Modifier.gilded(bright: Color, deep: Color): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(listOf(bright, deep)),
                blendMode = BlendMode.SrcAtop,
            )
        }

/**
 * A textless "return to ayah" control drawn as the ornament every mushaf
 * reader already knows: the illuminated ayah-marker roundel — a corolla of
 * twelve pointed petals with a pearl nestled in each notch — embossed and
 * gilded like the rest of the page. Inside it, a reed-pen (qalam) arrow in
 * three thick calligraphic strokes: the shaft, then each barb of the head,
 * each wiped in with a soft ink edge in the order a hand would draw them.
 */
@Composable
fun IslamicReturnToAyahButton(
    pointUp: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val accents = LocalQuranAccents.current
    val colors = MaterialTheme.colorScheme
    val ink = remember { Animatable(0f) }
    val rotation by animateFloatAsState(
        targetValue = if (pointUp) 180f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing,
        ),
        label = "arrowRotation",
    )

    // Re-draw the arrow from a dry page whenever the direction flips.
    LaunchedEffect(pointUp) {
        ink.snapTo(0f)
        ink.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1100, delayMillis = 120, easing = LinearEasing),
        )
    }

    Canvas(
        modifier
            .size(size)
            .semantics {
                contentDescription = "Return to ayah"
                role = Role.Button
            }
            .quietClickable(onClick = onClick),
    ) {
        val s = min(this.size.width, this.size.height)
        val c = s / 2f
        val center = Offset(c, c)

        fun polar(degrees: Float, radius: Float) = Offset(
            c + radius * kotlin.math.cos(Math.toRadians(degrees.toDouble())).toFloat(),
            c + radius * kotlin.math.sin(Math.toRadians(degrees.toDouble())).toFloat(),
        )

        // ── The roundel: twelve pointed petals, cusp-to-tip-to-cusp, each
        // edge leaving the notch radially and arriving at the tip radially,
        // which is what gives mushaf markers their soft ogee lobes.
        val petals = 12
        val cuspR = s * 0.365f
        val tipR = s * 0.475f
        val reach = tipR - cuspR
        val corolla = Path().apply {
            val step = 360f / petals
            for (k in 0 until petals) {
                val cuspA = k * step - 90f
                val tipA = cuspA + step / 2f
                val nextA = cuspA + step
                val cusp = polar(cuspA, cuspR)
                val tip = polar(tipA, tipR)
                val next = polar(nextA, cuspR)
                if (k == 0) moveTo(cusp.x, cusp.y)
                val outFromCusp = polar(cuspA, cuspR + reach * 0.55f)
                val inToTip = polar(tipA, tipR - reach * 0.45f)
                cubicTo(outFromCusp.x, outFromCusp.y, inToTip.x, inToTip.y, tip.x, tip.y)
                val outFromTip = polar(tipA, tipR - reach * 0.45f)
                val inToNext = polar(nextA, cuspR + reach * 0.55f)
                cubicTo(outFromTip.x, outFromTip.y, inToNext.x, inToNext.y, next.x, next.y)
            }
            close()
        }

        val gold = Brush.linearGradient(
            colors = listOf(accents.goldBright, accents.gold, accents.goldDeep),
            start = Offset(c, 0f),
            end = Offset(c, s),
        )
        val edge = Stroke(width = s * 0.020f, join = StrokeJoin.Round)

        // Paper body with a faint gilt bloom toward the rim, then the
        // embossed, gilded edge — the marker sits on the page as one piece.
        drawPath(
            corolla,
            Brush.radialGradient(
                0.0f to colors.background,
                0.62f to colors.background,
                1.0f to accents.gold.copy(alpha = 0.14f),
                center = center,
                radius = tipR,
            ),
        )
        embossed(corolla, edge, accents.embossDark, accents.embossLight)
        drawPath(corolla, gold, style = edge)

        // A pearl in each notch between petals.
        for (k in 0 until petals) {
            drawCircle(gold, radius = s * 0.016f, center = polar(k * 360f / petals - 90f, s * 0.44f))
        }

        // Hairline ring framing the ink field.
        drawCircle(
            color = accents.gold.copy(alpha = 0.45f),
            radius = s * 0.285f,
            center = center,
            style = Stroke(width = s * 0.009f),
        )

        // ── The qalam arrow: three filled strokes with real pen weight.
        rotate(rotation, center) {
            drawQalamArrow(
                size = s,
                center = center,
                ink = colors.primary,
                progress = ink.value,
            )
        }
    }
}

/**
 * Opaque floating capsule for "Back to" after a concordance jump — the
 * stadium twin of [IslamicReturnToAyahButton]. Same paper fill, embossed
 * gilt rim, and reed-pen arrow (pointing back); destination text sits in
 * the capsule as quiet ink. No Material icon, no chip chrome.
 */
@Composable
fun IslamicBackToOriginCapsule(
    chapterLabel: String,
    ayahLabel: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    val accents = LocalQuranAccents.current
    val colors = MaterialTheme.colorScheme
    val ink = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        ink.snapTo(0f)
        ink.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1100, delayMillis = 80, easing = LinearEasing),
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(height)
            .widthIn(max = 320.dp)
            .drawBehind {
                val h = size.height
                val r = h / 2f
                val capsule = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = size.width,
                            bottom = h,
                            cornerRadius = CornerRadius(r, r),
                        ),
                    )
                }
                val gold = Brush.linearGradient(
                    colors = listOf(accents.goldBright, accents.gold, accents.goldDeep),
                    start = Offset(0f, 0f),
                    end = Offset(0f, h),
                )
                val edge = Stroke(width = h * 0.045f, join = StrokeJoin.Round)
                // Opaque paper body — same fill language as the ayah roundel.
                drawPath(capsule, colors.background)
                drawPath(
                    capsule,
                    Brush.verticalGradient(
                        0f to accents.gold.copy(alpha = 0.10f),
                        0.55f to Color.Transparent,
                        1f to accents.gold.copy(alpha = 0.08f),
                    ),
                )
                embossed(capsule, edge, accents.embossDark, accents.embossLight)
                drawPath(capsule, gold, style = edge)
            }
            .quietClickable(onClick = onClick)
            .padding(start = 10.dp, end = 18.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
    ) {
        val inkProgress = ink.value
        Canvas(Modifier.size(28.dp)) {
            val s = min(this.size.width, this.size.height)
            val center = Offset(s / 2f, s / 2f)
            // Tip points left — "back" — same strokes as the roundel's arrow.
            rotate(90f, center) {
                drawQalamArrow(
                    size = s,
                    center = center,
                    ink = colors.primary,
                    progress = inkProgress,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = chapterLabel,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Text(
            text = "  ·  ",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface.copy(alpha = 0.28f),
        )
        Text(
            text = ayahLabel,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface.copy(alpha = 0.62f),
            maxLines = 1,
        )
    }
}

/**
 * Three calligraphic strokes of the reed-pen arrow used by the return-to-ayah
 * roundel and the Back-to capsule. [progress] 0..1 wipes the ink in.
 */
private fun DrawScope.drawQalamArrow(
    size: Float,
    center: Offset,
    ink: Color,
    progress: Float,
) {
    val c = center.x
    val s = size
    fun pt(x: Float, y: Float) = Offset(c + x * s, center.y + y * s)

    // Stroke 1 — the shaft, cut at the top like a nib entry, swelling
    // through the middle and running all the way down to the tip.
    val shaft = Path().apply {
        val a = pt(-0.034f, -0.150f)
        moveTo(a.x, a.y)
        val nib = pt(0.030f, -0.184f)
        lineTo(nib.x, nib.y)
        val c1 = pt(0.044f, -0.060f)
        val c2 = pt(0.012f, 0.020f)
        val br = pt(0.015f, 0.150f)
        cubicTo(c1.x, c1.y, c2.x, c2.y, br.x, br.y)
        val tip = pt(0.000f, 0.192f)
        cubicTo(pt(0.012f, 0.172f).x, pt(0.012f, 0.172f).y, pt(0.006f, 0.187f).x, pt(0.006f, 0.187f).y, tip.x, tip.y)
        val bl = pt(-0.017f, 0.148f)
        cubicTo(pt(-0.008f, 0.187f).x, pt(-0.008f, 0.187f).y, pt(-0.015f, 0.172f).x, pt(-0.015f, 0.172f).y, bl.x, bl.y)
        val c3 = pt(-0.032f, 0.020f)
        val c4 = pt(-0.046f, -0.060f)
        cubicTo(c3.x, c3.y, c4.x, c4.y, a.x, a.y)
        close()
    }

    // Strokes 2 and 3 — the barbs: rooted thick at the tip itself, each
    // swept up and outward like a pen flick, tapering to a point.
    fun barb(side: Float) = Path().apply {
        val tip = pt(side * 0.004f, 0.192f)
        moveTo(tip.x, tip.y)
        val o1 = pt(side * -0.070f, 0.158f)
        val o2 = pt(side * -0.118f, 0.100f)
        val end = pt(side * -0.150f, 0.038f)
        cubicTo(o1.x, o1.y, o2.x, o2.y, end.x, end.y)
        val i1 = pt(side * -0.100f, 0.058f)
        val i2 = pt(side * -0.055f, 0.100f)
        val root = pt(side * -0.012f, 0.128f)
        cubicTo(i1.x, i1.y, i2.x, i2.y, root.x, root.y)
        close()
    }

    fun span(from: Float, to: Float): Float =
        FastOutSlowInEasing.transform(((progress - from) / (to - from)).coerceIn(0f, 1f))

    inkStroke(shaft, ink, span(0.00f, 0.48f), pt(0f, -0.19f), pt(0f, 0.20f))
    inkStroke(barb(1f), ink, span(0.42f, 0.74f), pt(0f, 0.18f), pt(-0.16f, 0.03f))
    inkStroke(barb(-1f), ink, span(0.66f, 1.00f), pt(0f, 0.18f), pt(0.16f, 0.03f))
}

/**
 * Reveals a filled calligraphic stroke as if inked: an alpha wipe advances
 * from [from] toward [to] with a soft feathered frontier — the wet edge of
 * the stroke — instead of a hard clip.
 */
private fun DrawScope.inkStroke(
    path: Path,
    color: Color,
    progress: Float,
    from: Offset,
    to: Offset,
) {
    if (progress <= 0f) return
    val feather = 0.28f
    val head = progress * (1f + feather)
    if (head - feather >= 1f) {
        drawPath(path, color)
        return
    }
    val bounds = path.getBounds()
    val pad = 4f
    drawContext.canvas.saveLayer(
        Rect(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad),
        Paint(),
    )
    drawPath(path, color)
    drawRect(
        brush = Brush.linearGradient(
            0f to Color.Black,
            (head - feather).coerceIn(0f, 1f) to Color.Black,
            head.coerceAtMost(1f) to Color.Transparent,
            1f to Color.Transparent,
            start = from,
            end = to,
        ),
        topLeft = Offset(bounds.left - pad, bounds.top - pad),
        size = Size(bounds.width + pad * 2, bounds.height + pad * 2),
        blendMode = BlendMode.DstIn,
    )
    drawContext.canvas.restore()
}
