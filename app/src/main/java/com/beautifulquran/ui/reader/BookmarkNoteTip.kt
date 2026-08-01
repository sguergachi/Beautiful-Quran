package com.beautifulquran.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.ui.theme.ContextualFeatureTip
import com.beautifulquran.ui.theme.ContextualTipAnchor
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.royalGreenOverlayColorScheme

/** First-bookmark lesson, composed inside the ayah whose ribbon was unfurled. */
@Composable
internal fun BookmarkNoteTip(
    visible: Boolean,
    ribbonSide: AyahSelectorSide,
    onDismiss: () -> Unit,
    onRenderedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchor = if (ribbonSide == AyahSelectorSide.LEFT) {
        ContextualTipAnchor.START
    } else {
        ContextualTipAnchor.END
    }
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = royalGreenOverlayColorScheme(),
        typography = typography,
    ) {
        ContextualFeatureTip(
            visible = visible,
            title = "Add a note",
            body = "Press and hold this ribbon.",
            onDismiss = onDismiss,
            onRenderedChange = onRenderedChange,
            anchor = anchor,
            contentPadding = PaddingValues(
                start = if (anchor == ContextualTipAnchor.START) 4.dp else 28.dp,
                end = if (anchor == ContextualTipAnchor.END) 4.dp else 28.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
            mark = { HoldGestureMark(pointsToStart = anchor == ContextualTipAnchor.START) },
            modifier = modifier,
        )
    }
}

/** A leader from the live ribbon ending in two quiet, geometric hold rings. */
@Composable
private fun HoldGestureMark(pointsToStart: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val ruby = LocalQuranAccents.current.bookmarkRibbon
    Canvas(Modifier.size(42.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val edgeX = if (pointsToStart) 0f else size.width
        val ringEdgeX = center.x + if (pointsToStart) -11.dp.toPx() else 11.dp.toPx()
        drawLine(
            color = ink.copy(alpha = 0.34f),
            start = Offset(edgeX, center.y),
            end = Offset(ringEdgeX, center.y),
            strokeWidth = 1.dp.toPx(),
        )
        drawCircle(
            color = ink.copy(alpha = 0.4f),
            radius = 10.dp.toPx(),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = ink.copy(alpha = 0.62f),
            radius = 5.dp.toPx(),
            center = center,
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawCircle(color = ruby, radius = 1.8.dp.toPx(), center = center)
    }
}
