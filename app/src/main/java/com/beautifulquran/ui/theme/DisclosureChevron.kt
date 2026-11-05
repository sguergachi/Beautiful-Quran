package com.beautifulquran.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A disclosure cue: sideways toward hidden content, down when that content is visible. */
@Composable
fun DisclosureChevron(
    expanded: Boolean,
    pointsLeftWhenCollapsed: Boolean = false,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Icon(
        imageVector = when {
            expanded -> Icons.Rounded.KeyboardArrowDown
            pointsLeftWhenCollapsed -> Icons.AutoMirrored.Rounded.KeyboardArrowLeft
            else -> Icons.AutoMirrored.Rounded.KeyboardArrowRight
        },
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(20.dp),
    )
}
