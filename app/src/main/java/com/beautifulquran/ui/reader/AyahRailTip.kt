package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.ui.theme.ContextualFeatureTip
import com.beautifulquran.ui.theme.ContextualTipPlacement
import com.beautifulquran.ui.theme.royalGreenOverlayColorScheme

/** First-chapter lesson, written around the live collapsed ayah rail. */
@Composable
internal fun AyahRailTip(
    visible: Boolean,
    railSide: AyahSelectorSide,
    targetCenterY: Dp,
    onDismiss: () -> Unit,
    onRenderedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val readerPaper = MaterialTheme.colorScheme.background
    val readerInk = MaterialTheme.colorScheme.onSurface
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = royalGreenOverlayColorScheme(),
        typography = typography,
    ) {
        val railOnLeft = railSide == AyahSelectorSide.LEFT
        ContextualFeatureTip(
            visible = visible,
            title = "Find any ayah",
            body = "Press and drag this rail.",
            onDismiss = onDismiss,
            spotlightCenter = {
                DpOffset(
                    x = if (railOnLeft) 14.dp else maxWidth - 14.dp,
                    y = targetCenterY,
                )
            },
            placement = ContextualTipPlacement(
                bodyAngleDegrees = if (railOnLeft) 0f else 180f,
            ),
            actionCenter = {
                DpOffset(
                    x = if (railOnLeft) maxWidth - 68.dp else 68.dp,
                    y = maxHeight - 72.dp,
                )
            },
            dismissPaperColor = readerPaper,
            dismissInkColor = readerInk,
            onRenderedChange = onRenderedChange,
            contentPadding = PaddingValues(
                start = if (railOnLeft) 18.dp else 32.dp,
                end = if (railOnLeft) 32.dp else 18.dp,
            ),
            mark = { ContextualPulseMark() },
            modifier = modifier.fillMaxSize(),
        )
    }
}
