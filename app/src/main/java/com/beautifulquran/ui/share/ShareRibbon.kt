package com.beautifulquran.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.quietClickable

/**
 * Replaces [com.beautifulquran.ui.reader.PlayerBar] during share.
 *
 * Prompt (action-line, before gather): `Cancel · Share`
 * Gather (every design, verse already selected): `Cancel · N · Text · Image`
 *
 * Ink on paper — no icons, ripples, or elevation. Text/Image stay faint
 * until the list is non-empty, then gold.
 */
@Composable
fun ShareRibbon(
    gathering: Boolean,
    count: Int,
    preparingText: Boolean,
    preparingImage: Boolean,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onShareText: () -> Unit,
    onShareImage: () -> Unit,
) {
    val gold = LocalQuranAccents.current.gold
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val busy = preparingText || preparingImage
    val canExport = gathering && count >= 1 && !busy

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            ) {
                RibbonVerb("Cancel", muted, onClick = onCancel)
                if (gathering) {
                    Text(
                        text = count.toArabicIndicOrdinal(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (count >= 1) gold else faint,
                    )
                    RibbonVerb(
                        label = if (preparingText) "Text…" else "Text",
                        color = if (canExport) gold else faint,
                        enabled = canExport,
                        onClick = onShareText,
                    )
                    RibbonVerb(
                        label = if (preparingImage) "Image…" else "Image",
                        color = if (canExport) gold else faint,
                        enabled = canExport,
                        onClick = onShareImage,
                    )
                } else {
                    RibbonVerb("Share", gold, onClick = onShare)
                }
            }
        }
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

private fun Int.toArabicIndicOrdinal(): String =
    toString().map { '٠' + (it - '0') }.joinToString("")
