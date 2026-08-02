package com.beautifulquran.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which edge of a contextual lesson owns the feature being explained. */
enum class ContextualTipAnchor { START, END }

/**
 * A short lesson written into the feature's existing place on the paper.
 *
 * The parent keeps the real feature above this composable as the visual anchor.
 * The feature remains untouched on its edge while a directional ink field
 * enters from the opposite paper edge, holds across half the screen surface,
 * and feathers away before reaching the feature. [mark], [title], and [body]
 * then ink into that quiet field. There is no floating layer, shadow, or
 * generic scrim; the GPU-softened vellum keeps the sampled page below this
 * sharp teaching content visibly present through its feather.
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
    dismissPaperColor: Color? = null,
    dismissInkColor: Color? = null,
    onRenderedChange: (Boolean) -> Unit = {},
    guideHeight: Dp = 188.dp,
    targetCenterY: Dp? = null,
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
    BoxWithConstraints(modifier.fillMaxSize()) {
        val laneHeight = minOf(guideHeight, maxHeight)
        val targetY = (targetCenterY ?: maxHeight / 2).coerceIn(0.dp, maxHeight)
        val laneTop = (targetY - laneHeight / 2)
            .coerceIn(0.dp, (maxHeight - laneHeight).coerceAtLeast(0.dp))
        val markTop = (targetY - 14.dp)
            .coerceIn(0.dp, (maxHeight - 28.dp).coerceAtLeast(0.dp))
        InkSpillField(
            progress = { paper.value },
            targetCenterY = targetY,
            lessonOnLeft = anchor == ContextualTipAnchor.END,
            color = paperColor,
            modifier = Modifier.fillMaxSize(),
        )
        // The teaching half owns taps; the untouched half stays attached to
        // the LazyColumn so a vertical drag there can dismiss and keep moving.
        Box(
            Modifier
                .align(
                    if (anchor == ContextualTipAnchor.END) {
                        Alignment.TopStart
                    } else {
                        Alignment.TopEnd
                    },
                )
                .fillMaxWidth(0.7f)
                .fillMaxHeight()
                .absorbPointerEvents(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(
                    if (anchor == ContextualTipAnchor.END) {
                        Alignment.TopStart
                    } else {
                        Alignment.TopEnd
                    },
                )
                .fillMaxWidth(0.72f)
                .height(laneHeight)
                .offset(y = laneTop)
                .padding(contentPadding)
                .graphicsLayer { alpha = ink.value },
        ) {
            Column(Modifier.weight(1f)) {
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
        Box(
            modifier = Modifier
                .align(
                    if (anchor == ContextualTipAnchor.END) {
                        Alignment.BottomStart
                    } else {
                        Alignment.BottomEnd
                    },
                )
                .fillMaxWidth(0.5f)
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 28.dp)
                .graphicsLayer { alpha = ink.value },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(buttonPaper)
                    .quietClickable(role = Role.Button) { dismissLatest.value() }
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    text = dismissLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = buttonInk,
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(
                    if (anchor == ContextualTipAnchor.END) {
                        Alignment.TopEnd
                    } else {
                        Alignment.TopStart
                    },
                )
                .width(28.dp)
                .height(28.dp)
                .offset(y = markTop)
                .graphicsLayer { alpha = ink.value },
        ) {
            mark()
        }
    }
}
