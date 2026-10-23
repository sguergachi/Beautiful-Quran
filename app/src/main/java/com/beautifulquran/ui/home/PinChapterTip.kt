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
 * real target — its hold fills it and offers Pin — and the lesson's body
 * takes the paper above or below it, whichever half of the sheet is open
 * ([bodyBelow], from [pinTipBodyBelow]). [target] is the row's centre in this
 * sheet's own space.
 */
@Composable
internal fun PinChapterTip(
    visible: Boolean,
    target: DpOffset,
    bodyBelow: Boolean,
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
                placement = ContextualTipPlacement(bodyAngleDegrees = if (bodyBelow) 90f else 270f),
                dismissPaperColor = paper,
                dismissInkColor = ink,
                onRenderedChange = onRenderedChange,
                contentPadding = PaddingValues(horizontal = 32.dp),
                mark = { ContextualPulseMark() },
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

/** The lesson sits in whichever half of the sheet the row leaves open. */
internal fun pinTipBodyBelow(targetY: Float, sheetHeight: Float): Boolean = targetY < sheetHeight / 2f
