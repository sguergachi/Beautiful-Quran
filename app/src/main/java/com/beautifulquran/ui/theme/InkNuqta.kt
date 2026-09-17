package com.beautifulquran.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/**
 * A qalam-cut Arabic dot whose selected ink blooms until it meets the nuqta.
 * The faint outline keeps the unselected choice legible without becoming a
 * Material radio ring.
 */
@Composable
fun InkNuqta(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val spread by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (selected) 420 else 180,
            easing = FastOutSlowInEasing,
        ),
        label = "nuqtaInkSpread",
    )
    val ink = MaterialTheme.colorScheme.primary
    val restingInk = MaterialTheme.colorScheme.outline

    Canvas(modifier.size(20.dp)) {
        val outline = inkNuqtaPath(size.minDimension)
        val centre = Offset(size.width * 0.5f, size.height * 0.5f)
        val radius = size.minDimension * 0.7f * spread

        if (spread > 0f) {
            clipPath(outline) {
                // A translucent wet edge arrives before the pooled pigment.
                drawCircle(
                    color = ink.copy(alpha = 0.18f * spread),
                    radius = radius * 1.12f,
                    center = centre,
                )
                drawCircle(
                    color = ink.copy(alpha = 0.55f * spread),
                    radius = radius * 0.98f,
                    center = centre + Offset(1.1.dp.toPx(), -0.7.dp.toPx()),
                )
                drawCircle(
                    color = ink.copy(alpha = 0.96f * spread),
                    radius = radius * 0.84f,
                    center = centre + Offset(-0.6.dp.toPx(), 0.8.dp.toPx()),
                )
            }
        }

        drawPath(
            path = outline,
            color = if (selected) ink.copy(alpha = 0.82f) else restingInk.copy(alpha = 0.46f),
            style = Stroke(width = 1.15.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}

/** The lightly bowed rhombus left by one full-width touch of an Arabic qalam. */
internal fun inkNuqtaPath(size: Float): Path = Path().apply {
    moveTo(size * 0.49f, size * 0.09f)
    quadraticTo(size * 0.69f, size * 0.16f, size * 0.91f, size * 0.43f)
    quadraticTo(size * 0.86f, size * 0.66f, size * 0.53f, size * 0.91f)
    quadraticTo(size * 0.31f, size * 0.84f, size * 0.09f, size * 0.58f)
    quadraticTo(size * 0.14f, size * 0.33f, size * 0.49f, size * 0.09f)
    close()
}
