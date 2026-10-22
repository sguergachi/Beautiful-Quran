package com.beautifulquran.ui.home

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Key of the chapter row that owns the reading place: the corded copy atop
 * the list when the chapter is being revised, its in-order row otherwise. */
internal fun placeRowKey(continueId: Int?, revisingIds: List<Int>): String? = when (continueId) {
    null -> null
    in revisingIds -> "revising-$continueId"
    else -> "surah-$continueId"
}

/** Same drop as the bookmark ribbon's spill: slow peel, then gravity. */
private val CordDropEasing = CubicBezierEasing(0.45f, 0.02f, 0.22f, 1f)
private val CordGatherEasing = CubicBezierEasing(0.55f, 0.05f, 0.35f, 1f)

/** How far down its row the cord hangs before the tassel. */
private const val CordReach = 0.72f
private val CordWidth = 2.6.dp
private val CordEmerge = 14.dp
/** A laid cord is never ruled straight: it bellies this far toward the
 * text, which is also what keeps it from reading as a scrollbar. */
private val CordSway = 2.2.dp
/** Pitch of the silk's twist, drawn as paper-light diagonal lays. */
private val TwistPitch = 3.4.dp
private val TasselLength = 11.dp
private val TasselSpread = 6.dp
private val KnotRadius = 2.6.dp

/**
 * The study cord: a twisted silk thread laid across the page at a chapter
 * being revised, like the sewn-in marker of a bound mushaf. It emerges from
 * under the paper at the row's top edge (a soft feather, not a cut), hangs
 * in the row's trailing gutter with a slight belly, and ends in a knot and a
 * frayed tassel. Plain ink: green is interaction, gold is ornament, ruby is
 * a saved verse — a cord is none of those, so it takes no hue of its own.
 *
 * Marking drops the thread with the ribbon's gravity spill; unmarking
 * gathers it back up under the page. A row that composes already corded
 * draws it settled, so scrolling never replays the drop.
 */
internal fun Modifier.revisingCord(revising: Boolean, color: Color, gutter: Dp): Modifier = composed {
    val paper = MaterialTheme.colorScheme.background
    val progress by animateFloatAsState(
        targetValue = if (revising) 1f else 0f,
        animationSpec = if (revising) tween(520, easing = CordDropEasing) else tween(300, easing = CordGatherEasing),
        label = "revisingCord",
    )
    drawBehind {
        if (progress <= 0f) return@drawBehind
        val x0 = size.width - gutter.toPx() / 2f
        val full = size.height * CordReach
        val end = max(1f, full * progress)
        val sway = CordSway.toPx()
        // The belly is measured on the settled cord, so dropping only
        // lengthens the thread; it never re-bends as it falls.
        fun xAt(y: Float) = x0 - sway * sin(PI.toFloat() * (y / full).coerceIn(0f, 1f))
        val emerge = CordEmerge.toPx()
        fun fade(y: Float) = (y / emerge).coerceIn(0f, 1f)
        val stroke = CordWidth.toPx()
        val path = Path().apply {
            moveTo(xAt(0f), 0f)
            var y = 0f
            while (y < end) {
                y = minOf(end, y + 2f)
                lineTo(xAt(y), y)
            }
        }
        drawPath(
            path,
            brush = Brush.verticalGradient(
                0f to color.copy(alpha = 0f),
                (emerge / end).coerceAtMost(1f) to color,
                startY = 0f,
                endY = end,
            ),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Twist: short paper-light lays crossing the cord on the diagonal.
        val pitch = TwistPitch.toPx()
        var y = pitch
        while (y < end - pitch / 2f) {
            val x = xAt(y)
            drawLine(
                color = paper.copy(alpha = 0.42f * fade(y)),
                start = Offset(x - stroke * 0.42f, y + stroke * 0.38f),
                end = Offset(x + stroke * 0.42f, y - stroke * 0.38f),
                strokeWidth = stroke * 0.28f,
                cap = StrokeCap.Round,
            )
            y += pitch
        }
        // The knot and tassel arrive with the last of the drop.
        val tassel = ((progress - 0.6f) / 0.4f).coerceIn(0f, 1f)
        if (tassel <= 0f) return@drawBehind
        val ink = color.copy(alpha = color.alpha * tassel)
        val knot = Offset(xAt(end), end)
        val length = TasselLength.toPx() * tassel
        val spread = TasselSpread.toPx()
        for (strand in 0 until TasselStrands) {
            val t = strand / (TasselStrands - 1f) - 0.5f
            drawLine(
                color = ink.copy(alpha = ink.alpha * (1f - abs(t) * 0.5f)),
                start = knot,
                end = Offset(knot.x + t * spread, knot.y + length * (1f - abs(t) * 0.25f)),
                strokeWidth = stroke * 0.42f,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(ink, KnotRadius.toPx(), knot)
    }
}

private const val TasselStrands = 5

/** Accessible name for the cord's gutter target. */
internal fun revisingCordLabel(revising: Boolean, chapter: String): String =
    if (revising) "Stop revising $chapter" else "Revise $chapter, keep it at the top"
