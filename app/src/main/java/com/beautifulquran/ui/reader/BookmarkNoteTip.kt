package com.beautifulquran.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.ui.theme.ContextualFeatureTip
import com.beautifulquran.ui.theme.ContextualTipPlacement
import com.beautifulquran.ui.theme.royalGreenOverlayColorScheme

/** First-bookmark lesson, composed across the reader sheet around the live ribbon. */
@Composable
internal fun BookmarkNoteTip(
    visible: Boolean,
    ribbonSide: AyahSelectorSide,
    onDismiss: () -> Unit,
    onRenderedChange: (Boolean) -> Unit,
    targetCenterY: Dp,
    modifier: Modifier = Modifier,
) {
    val readerPaper = MaterialTheme.colorScheme.background
    val readerInk = MaterialTheme.colorScheme.onSurface
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = royalGreenOverlayColorScheme(),
        typography = typography,
    ) {
        BoxWithConstraints(modifier) {
            val ribbonOnLeft = ribbonSide == AyahSelectorSide.LEFT
            ContextualFeatureTip(
                visible = visible,
                title = "Add a note",
                body = "Press and hold this ribbon.",
                onDismiss = onDismiss,
                spotlightCenter = DpOffset(
                    x = if (ribbonOnLeft) 14.dp else maxWidth - 14.dp,
                    y = targetCenterY,
                ),
                placement = ContextualTipPlacement(
                    bodyAngleDegrees = if (ribbonOnLeft) 0f else 180f,
                ),
                actionCenter = DpOffset(
                    x = if (ribbonOnLeft) maxWidth - 68.dp else 68.dp,
                    y = maxHeight - 72.dp,
                ),
                dismissPaperColor = readerPaper,
                dismissInkColor = readerInk,
                onRenderedChange = onRenderedChange,
                contentPadding = PaddingValues(
                    start = if (ribbonOnLeft) 18.dp else 32.dp,
                    end = if (ribbonOnLeft) 32.dp else 18.dp,
                    top = 0.dp,
                    bottom = 0.dp,
                ),
                mark = { HoldGestureMark() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** One quiet, ink-only breath around the live ribbon's cloth tip. */
@Composable
private fun HoldGestureMark() {
    val ink = MaterialTheme.colorScheme.background
    val pulse by rememberInfiniteTransition(label = "bookmarkHoldPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bookmarkHoldPulseRadius",
    )
    Canvas(Modifier.size(28.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = ink.copy(alpha = 0.2f * (1f - pulse)),
            radius = (7.dp + 6.dp * pulse).toPx(),
            center = center,
            style = Stroke(width = 0.8.dp.toPx()),
        )
        drawCircle(
            color = ink.copy(alpha = 0.34f),
            radius = 7.dp.toPx(),
            center = center,
            style = Stroke(width = 0.9.dp.toPx()),
        )
    }
}
