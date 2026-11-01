package com.beautifulquran.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.reader.CAPS_LABEL_SIZE
import com.beautifulquran.ui.reader.CAPS_TRACKING
import com.beautifulquran.ui.theme.forCaps
import com.beautifulquran.ui.theme.quietClickable

/**
 * Replaces the player bar while gathering. Not a copy of play.
 *
 * One row, two jobs, like a running head: leave at the start, send at the
 * end. The count lives on the verses. The middle carries one quiet line —
 * TAP VERSES TO SHARE, in small caps, between two hairlines that run out to
 * the icons on either side — which gives way to an export error when there
 * is one. The bar itself is the theme's DeepGreen, so gather is a different
 * foot of the sheet than play.
 */
@Composable
fun ShareRibbon(
    count: Int,
    preparingText: Boolean,
    preparingImage: Boolean,
    error: String? = null,
    onCancel: () -> Unit,
    onShareText: () -> Unit,
    onShareImage: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onPrimary
    val busy = preparingText || preparingImage
    val canExport = count >= 1 && !busy
    // Send is the bar's action, but two solid white glyphs against one pale
    // cross tipped it to the right: ease the pair off pure ink, lift the cross,
    // and draw the image as an outline — a filled block was the heaviest mark
    // on the bar, at its far edge — to match the cross's line weight. The
    // quotation marks stay solid: they are only two commas.
    val exportTint = if (canExport) ink.copy(alpha = 0.88f) else ink.copy(alpha = 0.35f)
    val errorLine = error?.takeIf { it.isNotBlank() && !busy }

    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = ink,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
                .semantics {
                    contentDescription =
                        (if (count == 1) "1 verse selected" else "$count verses selected") +
                        ". $GATHER_HINT"
                },
        ) {
            ShareBarIcon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cancel share",
                tint = ink.copy(alpha = 0.78f),
                onClick = onCancel,
            )
            if (errorLine != null) {
                Text(
                    text = errorLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
            } else {
                GatherHint(
                    ink = ink,
                    // One icon sits before the middle and two after it, so the
                    // middle's own centre is off the bar's. The left rule runs
                    // 56 dp longer than the right to put the label back on the
                    // bar's centre — and to fill the side the cross leaves bare.
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
            }
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
                    imageVector = Icons.Outlined.Image,
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

private const val GATHER_HINT = "Tap verses to share"

/** How much longer the label's left rule runs than its right: the bar has one icon before the middle and two after. */
private val GATHER_HINT_LEFT_EXTRA = 56.dp

/**
 * The bar's centred label: small caps at whisper ink, flanked by hairlines
 * that extend to the edges of the middle. Page furniture, not a control, so
 * it is quieter than the icons beside it and takes no touches.
 */
@Composable
private fun GatherHint(ink: Color, modifier: Modifier = Modifier) {
    val rule = ink.copy(alpha = 0.28f)
    // One physical pixel. (Dp.Hairline is a stroke width: as a layout height
    // it is zero, and the rules would not exist.)
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.width(GATHER_HINT_LEFT_EXTRA).height(hairline).background(rule))
        Box(Modifier.weight(1f).height(hairline).background(rule))
        Text(
            text = GATHER_HINT.uppercase(),
            style = MaterialTheme.typography.labelSmall
                .copy(fontSize = CAPS_LABEL_SIZE, letterSpacing = CAPS_TRACKING)
                .forCaps(),
            color = ink.copy(alpha = 0.6f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).height(hairline).background(rule))
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
