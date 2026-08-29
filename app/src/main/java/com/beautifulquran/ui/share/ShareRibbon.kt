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
import androidx.compose.foundation.layout.widthIn
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
 * Replaces the player bar while gathering. Same five-slot geometry as
 * [com.beautifulquran.ui.reader.PlayerBar]: two 48 dp controls, a 56 dp
 * centre, two 48 dp controls — so the count sits on the page's centre line
 * the way Play does. Trailing spacer matches Close.
 *
 * Close · Text · N · Image · —
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
                horizontalArrangement = Arrangement.spacedBy(
                    if (compact) 4.dp else 12.dp,
                    Alignment.CenterHorizontally,
                ),
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 8.dp else 12.dp,
                        end = if (compact) 8.dp else 12.dp,
                        bottom = 4.dp,
                    ),
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Cancel share",
                        tint = ink,
                    )
                }
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
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .semantics {
                            contentDescription = if (count == 1) "1 verse" else "$count verses"
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
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}
