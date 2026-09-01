package com.beautifulquran.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.quietClickable

/**
 * Replaces the player bar while gathering. Not a copy of play.
 *
 * One row, two jobs, like a running head: leave at the start, send at the
 * end. The count lives on the verses. The empty middle is paper.
 */
@Composable
fun ShareRibbon(
    count: Int,
    preparingText: Boolean,
    preparingImage: Boolean,
    onCancel: () -> Unit,
    onShareText: () -> Unit,
    onShareImage: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val busy = preparingText || preparingImage
    val canExport = count >= 1 && !busy
    val exportTint = if (canExport) ink else ink.copy(alpha = 0.35f)

    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
                .semantics {
                    contentDescription =
                        if (count == 1) "1 verse selected" else "$count verses selected"
                },
        ) {
            ShareBarIcon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cancel share",
                tint = ink.copy(alpha = 0.55f),
                onClick = onCancel,
            )
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShareBarIcon(
                    imageVector = Icons.Rounded.FormatQuote,
                    contentDescription = "Share as text",
                    tint = exportTint,
                    enabled = canExport,
                    preparing = preparingText,
                    onClick = onShareText,
                )
                ShareBarIcon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = "Share as image",
                    tint = exportTint,
                    enabled = canExport,
                    preparing = preparingImage,
                    onClick = onShareImage,
                )
            }
        }
    }
}

@Composable
private fun ShareBarIcon(
    imageVector: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    preparing: Boolean = false,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .quietClickable(
                enabled = enabled && !preparing,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
    ) {
        if (preparing) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else {
            Icon(
                imageVector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
