package com.beautifulquran.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/** Which edge of a contextual lesson owns the feature being explained. */
enum class ContextualTipAnchor { START, END }

/**
 * A short lesson written into the feature's existing place on the paper.
 *
 * The parent keeps the real feature above this composable as the visual anchor.
 * An inverse spotlight blooms the current background out from the anchored
 * feature into a shallow, feathered lesson field, with a soft clear window
 * left around the feature itself. [mark], [title], and [body] then ink into
 * that quiet field. The bloom deliberately stops inside the local surface;
 * there is no card, hard edge, shadow, dimming scrim, or blur.
 */
@Composable
fun ContextualFeatureTip(
    visible: Boolean,
    title: String,
    body: String,
    onDismiss: () -> Unit,
    mark: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    anchor: ContextualTipAnchor = ContextualTipAnchor.START,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    dismissLabel: String = "Got it",
    onRenderedChange: (Boolean) -> Unit = {},
    guideHeight: Dp = 156.dp,
    spotlightInset: Dp = 14.dp,
    spotlightRadius: Dp = 18.dp,
    spotlightFeather: Dp = 10.dp,
) {
    var rendered by remember { mutableStateOf(visible) }
    val paper = remember { Animatable(0f) }
    val ink = remember { Animatable(0f) }
    val dismissLatest = rememberUpdatedState(onDismiss)
    val renderedChangeLatest = rememberUpdatedState(onRenderedChange)

    LaunchedEffect(rendered) { renderedChangeLatest.value(rendered) }

    LaunchedEffect(visible) {
        if (visible) {
            if (!rendered) {
                paper.snapTo(0f)
                ink.snapTo(0f)
                rendered = true
            }
            coroutineScope {
                launch {
                    paper.animateTo(1f, tween(380, easing = InkExpandEasing))
                }
                launch {
                    delay(110)
                    ink.animateTo(1f, tween(270, easing = FastOutSlowInEasing))
                }
            }
        } else if (rendered) {
            coroutineScope {
                launch { ink.animateTo(0f, tween(160)) }
                launch { paper.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
            }
            rendered = false
        }
    }

    if (!rendered) return

    val paperColor = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val bandHeight = min(size.height, guideHeight.toPx())
            val clearRadius = spotlightRadius.toPx()
            val outerRadius = (clearRadius + spotlightFeather.toPx()).coerceAtLeast(0.5f)
            val center = Offset(
                x = if (anchor == ContextualTipAnchor.START) {
                    spotlightInset.toPx()
                } else {
                    size.width - spotlightInset.toPx()
                }.coerceIn(0f, size.width),
                y = bandHeight / 2f,
            )
            // Wide enough to leave solid green under the lesson at the far
            // edge, but shallow enough that scripture below remains on paper.
            val settledRadius = size.width * 1.32f
            val bloomRadius = settledRadius * paper.value
            if (bloomRadius > 0.5f) {
                val verticalScale = (bandHeight * 0.48f / settledRadius).coerceIn(0.08f, 1f)
                scale(scaleX = 1f, scaleY = verticalScale, pivot = center) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to paperColor,
                                0.76f to paperColor,
                                1f to paperColor.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = bloomRadius,
                        ),
                        radius = bloomRadius,
                        center = center,
                    )
                }
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Black,
                        (clearRadius / outerRadius).coerceIn(0f, 1f) to Color.Black,
                        1f to Color.Transparent,
                    ),
                    center = center,
                    radius = outerRadius,
                ),
                radius = outerRadius,
                center = center,
                blendMode = BlendMode.DstOut,
            )
        }
        Box(Modifier.fillMaxSize().absorbPointerEvents())
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = guideHeight)
                .fillMaxHeight()
                .padding(contentPadding)
                .graphicsLayer { alpha = ink.value },
        ) {
            if (anchor == ContextualTipAnchor.START) {
                mark()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = dismissLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                        modifier = Modifier
                            .quietClickable(role = Role.Button) { dismissLatest.value() }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
            }
            if (anchor == ContextualTipAnchor.END) {
                Spacer(Modifier.width(10.dp))
                mark()
            }
        }
    }
}
