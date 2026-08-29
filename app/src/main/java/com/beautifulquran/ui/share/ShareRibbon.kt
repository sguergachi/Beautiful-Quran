package com.beautifulquran.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.SerifFontFamily
import com.beautifulquran.ui.theme.quietClickable

/**
 * Replaces the player bar while gathering: `Cancel    2    Text   Image`.
 *
 * A line of type, not a toolbar. Count is Western digits in the book face —
 * furniture, not illumination. Text/Image stay faint until the list is
 * non-empty.
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
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = ink.copy(alpha = 0.62f)
    val faint = ink.copy(alpha = 0.32f)
    val busy = preparingText || preparingImage
    val canExport = count >= 1 && !busy

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
            ) {
                RibbonVerb("Cancel", muted, onClick = onCancel)
                Spacer(Modifier.weight(1f))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SerifFontFamily,
                    ),
                    color = if (count >= 1) ink.copy(alpha = 0.78f) else faint,
                    modifier = Modifier.semantics {
                        contentDescription = if (count == 1) "1 verse" else "$count verses"
                    },
                )
                Spacer(Modifier.weight(1f))
                RibbonVerb(
                    label = if (preparingText) "Text…" else "Text",
                    color = if (canExport) ink.copy(alpha = 0.88f) else faint,
                    enabled = canExport,
                    onClick = onShareText,
                )
                RibbonVerb(
                    label = if (preparingImage) "Image…" else "Image",
                    color = if (canExport) ink.copy(alpha = 0.88f) else faint,
                    enabled = canExport,
                    onClick = onShareImage,
                )
            }
        }
    }
}

/**
 * Action-line prompt: Share sits above the still-living player bar.
 * Transport is not replaced until the verse is actually gathered.
 */
@Composable
fun ShareActionPrompt(
    onCancel: () -> Unit,
    onShare: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 0.dp),
    ) {
        RibbonVerb("Cancel", ink.copy(alpha = 0.62f), onClick = onCancel)
        RibbonVerb("Share", ink.copy(alpha = 0.92f), onClick = onShare)
    }
}

@Composable
private fun RibbonVerb(
    label: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .quietClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
    )
}
