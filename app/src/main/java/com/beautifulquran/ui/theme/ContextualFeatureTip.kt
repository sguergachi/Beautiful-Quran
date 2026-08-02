package com.beautifulquran.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * A short lesson written into unused paper around a live feature spotlight.
 *
 * [spotlightCenter] is the feature's position in this composable. [placement]
 * casts a ray from it at any angle and places the teaching body along that ray;
 * the GPU vellum enters from behind the body and feathers toward the spotlight.
 * The spotlight half remains interactive. There is no floating layer, shadow,
 * generic scrim, or direction-specific rendering path.
 */
@Composable
fun ContextualFeatureTip(
    visible: Boolean,
    title: String,
    body: String,
    onDismiss: () -> Unit,
    mark: @Composable () -> Unit,
    spotlightCenter: DpOffset,
    placement: ContextualTipPlacement,
    modifier: Modifier = Modifier,
    actionCenter: DpOffset? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    dismissLabel: String = "Got it",
    dismissPaperColor: Color? = null,
    dismissInkColor: Color? = null,
    onRenderedChange: (Boolean) -> Unit = {},
    guideWidthFraction: Float = 0.72f,
    guideHeight: Dp = 188.dp,
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
                launch { paper.animateTo(1f, tween(380, easing = InkExpandEasing)) }
                launch {
                    delay(110)
                    ink.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
                }
            }
        } else if (rendered) {
            coroutineScope {
                launch { ink.animateTo(0f, tween(160)) }
                launch { paper.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) }
            }
            rendered = false
        }
    }

    if (!rendered) return

    val paperColor = MaterialTheme.colorScheme.background
    val buttonPaper = dismissPaperColor ?: MaterialTheme.colorScheme.onSurface
    val buttonInk = dismissInkColor ?: paperColor
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val surface = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val spotlight = with(density) {
            Offset(spotlightCenter.x.toPx(), spotlightCenter.y.toPx())
        }.coerceTo(surface)
        val bodyCenter = placement.bodyCenter(spotlight, surface)
        val defaultAction = placement.actionCenter(spotlight, surface)
        val action = actionCenter?.let {
            with(density) { Offset(it.x.toPx(), it.y.toPx()) }
        }?.coerceTo(surface) ?: defaultAction
        val flow = (spotlight - bodyCenter).normalized()
        val interactionBoundary = (spotlight + bodyCenter) / 2f

        val bodyDp = with(density) { DpOffset(bodyCenter.x.toDp(), bodyCenter.y.toDp()) }
        val actionDp = with(density) {
            DpOffset(
                action.x.toDp().coerceIn(52.dp, (maxWidth - 52.dp).coerceAtLeast(52.dp)),
                action.y.toDp().coerceIn(52.dp, (maxHeight - 52.dp).coerceAtLeast(52.dp)),
            )
        }
        val spotlightDp = with(density) {
            DpOffset(
                spotlight.x.toDp().coerceIn(14.dp, (maxWidth - 14.dp).coerceAtLeast(14.dp)),
                spotlight.y.toDp().coerceIn(14.dp, (maxHeight - 14.dp).coerceAtLeast(14.dp)),
            )
        }
        val laneWidth = maxWidth * guideWidthFraction.coerceIn(0.25f, 1f)
        val laneHeight = minOf(guideHeight, maxHeight)
        val laneLeft = (bodyDp.x - laneWidth / 2)
            .coerceIn(0.dp, (maxWidth - laneWidth).coerceAtLeast(0.dp))
        val laneTop = (bodyDp.y - laneHeight / 2)
            .coerceIn(0.dp, (maxHeight - laneHeight).coerceAtLeast(0.dp))

        InkSpillField(
            progress = { paper.value },
            spotlightCenter = spotlightDp,
            bodyCenter = bodyDp,
            actionCenter = actionDp,
            color = paperColor,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .absorbPointerEventsWhere { point ->
                    (point - interactionBoundary).dot(flow) <= 0f
                },
        )
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = laneLeft, y = laneTop)
                .width(laneWidth)
                .height(laneHeight)
                .padding(contentPadding)
                .graphicsLayer { alpha = ink.value },
        ) {
            LessonCopy(title, body)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = actionDp.x, y = actionDp.y)
                .graphicsLayer {
                    translationX = -size.width / 2f
                    translationY = -size.height / 2f
                    alpha = ink.value
                }
                .clip(RoundedCornerShape(50))
                .background(buttonPaper)
                .ownedQuietClickable(role = Role.Button) { dismissLatest.value() }
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Text(
                text = dismissLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = buttonInk,
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = spotlightDp.x, y = spotlightDp.y)
                .width(28.dp)
                .height(28.dp)
                .graphicsLayer {
                    translationX = -size.width / 2f
                    translationY = -size.height / 2f
                    alpha = ink.value
                },
        ) {
            mark()
        }
    }
}

@Composable
private fun LessonCopy(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 28.sp,
                lineHeight = 32.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 19.sp,
                lineHeight = 24.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
        )
    }
}

private fun Offset.coerceTo(surface: Size): Offset = Offset(
    x.coerceIn(0f, surface.width),
    y.coerceIn(0f, surface.height),
)

private fun Offset.normalized(): Offset {
    val length = hypot(x, y)
    return if (length > 1e-4f) this / length else Offset(1f, 0f)
}

private fun Offset.dot(other: Offset): Float = x * other.x + y * other.y
