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
 * real target — holding it until it is full pins it — and the lesson's body
 * takes the paper above or below it, whichever half of the sheet is open
 * ([bodyBelow], from [pinTipBodyBelow]). [target] is the row's centre in this
 * sheet's own space.
 *
 * The body sits deep in the vellum along the ray, where the chapters
 * beneath are fully dissolved and the copy is clean — but not most of
 * the way to the sheet's edge, where it read as a detached bottom sheet
 * with its **Got it** pill stranded at the navigation bar. Its copy
 * starts in the chapter column — ribbon lane, number column and gap —
 * so title and instruction line up with the chapter names they refer to.
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
                placement = pinTipPlacement(bodyBelow),
                dismissPaperColor = paper,
                dismissInkColor = ink,
                onRenderedChange = onRenderedChange,
                // Chapter column: 28 dp ribbon lane + 26 dp number column
                // + 4 dp gap, mirroring the chapter rows' name inset.
                contentPadding = PaddingValues(start = 58.dp, end = 28.dp),
                guideWidthFraction = 1f,
                guideHeight = 124.dp,
                mark = { ContextualPulseMark() },
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

/** The lesson sits in whichever half of the sheet the row leaves open. */
internal fun pinTipBodyBelow(targetY: Float, sheetHeight: Float): Boolean = targetY < sheetHeight / 2f

/**
 * The lesson's ray from the row: body deep in the vellum (0.55 of the way
 * to the sheet's edge, not the shared 0.78 that stranded it at the edge),
 * **Got it** just beyond it (0.74, not 0.94 at the navigation bar).
 */
internal fun pinTipPlacement(bodyBelow: Boolean) = ContextualTipPlacement(
    bodyAngleDegrees = if (bodyBelow) 90f else 270f,
    bodyDistanceFraction = 0.55f,
    actionDistanceFraction = 0.74f,
)
