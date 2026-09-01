package com.beautifulquran.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.SerifFontFamily

/**
 * Replaces the player bar while gathering.
 *
 * The count is the fact of the mode, so it sits where the reciter name sits:
 * centered on the top row, Close in the trailing 48 dp slot. Text and Image
 * are a pair of equal verbs on the row below, centered together. The count
 * is not Play, and the two exports are not Rewind / Forward.
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
    val compact = LocalConfiguration.current.screenWidthDp < 340
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val busy = preparingText || preparingImage
    val canExport = count >= 1 && !busy
    val exportTint = if (canExport) ink else ink.copy(alpha = 0.35f)

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(Modifier.size(48.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription =
                                if (count == 1) "1 verse" else "$count verses"
                        },
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = SerifFontFamily,
                        ),
                        color = if (count >= 1) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                        } else {
                            ink.copy(alpha = 0.35f)
                        },
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Cancel share",
                        tint = ink.copy(alpha = 0.55f),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    if (compact) 20.dp else 32.dp,
                    Alignment.CenterHorizontally,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                IconButton(
                    onClick = onShareText,
                    enabled = canExport,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (preparingText) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = ink,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Notes,
                            contentDescription = "Share as text",
                            tint = exportTint,
                        )
                    }
                }
                IconButton(
                    onClick = onShareImage,
                    enabled = canExport,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (preparingImage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = ink,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Image,
                            contentDescription = "Share as image",
                            tint = exportTint,
                        )
                    }
                }
            }
        }
    }
}
