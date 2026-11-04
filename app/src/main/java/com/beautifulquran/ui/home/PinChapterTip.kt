package com.beautifulquran.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.reader.ContextualPulseMark
import com.beautifulquran.ui.theme.ContextualFeatureTip
import com.beautifulquran.ui.theme.ContextualTipPlacement
import com.beautifulquran.ui.theme.LocalQuranInk
import com.beautifulquran.ui.theme.royalGreenOverlayColorScheme
import com.beautifulquran.ui.theme.royalGreenOverlayInk

/**
 * The pinning lesson, written around the live row of the chapter being read:
 * the one a reader is most likely to want kept at the top. The row stays the
 * real target — holding it until it is full pins it. The lesson occupies the
 * right side, level with the chapter, and the vellum tapers left toward its
 * number column. [target] is that column's centre in this sheet's own space.
 * The copy follows the chapter; **Got it** stays at the bottom of the guide.
 */
@Composable
internal fun PinChapterTip(
    visible: Boolean,
    target: DpOffset,
    onDismiss: () -> Unit,
    onRenderedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paper = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onSurface
    val typography = MaterialTheme.typography
    // The overlay swaps the sheet out from under its content, so it has to
    // swap the ink ladder too (see AyahRailTip).
    CompositionLocalProvider(LocalQuranInk provides royalGreenOverlayInk()) {
        MaterialTheme(
            colorScheme = royalGreenOverlayColorScheme(),
            typography = typography,
        ) {
            ContextualFeatureTip(
                visible = visible,
                title = "Keep it at the top",
                body = "Press and hold this chapter to pin it.",
                onDismiss = onDismiss,
                spotlightCenter = { target },
                placement = ContextualTipPlacement(bodyAngleDegrees = 0f),
                actionCenter = {
                    DpOffset(maxWidth - 68.dp, maxHeight - 72.dp)
                },
                dismissPaperColor = paper,
                dismissInkColor = ink,
                onRenderedChange = onRenderedChange,
                contentPadding = PaddingValues(start = 18.dp, end = 28.dp),
                guideWidthFraction = 0.62f,
                guideHeight = 300.dp,
                mark = { ContextualPulseMark() },
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}
