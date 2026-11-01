package com.beautifulquran.ui.share

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Image
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
 * Leave at the start. What follows it depends on the gather
 * ([gatherBarMiddle]):
 *
 * - **nothing gathered:** a hairline from the cross to TAP VERSES TO SHARE,
 *   in small caps at the end of the bar where the send controls will come —
 *   the only time the reader needs telling (gather opens with the held verse
 *   already in it, so this shows once every verse has been dropped). There
 *   is nothing to send, so no send controls.
 * - **one or more gathered:** the send controls at the end, text and image,
 *   with the middle left empty — one row, two jobs, like a running head.
 * - **an export failed:** the error in the middle, the controls still at the end.
 *
 * The count lives on the verses. The bar is the theme's DeepGreen, so gather
 * is a different foot of the sheet than play.
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
    val exportTint = if (canExport) ink else ink.copy(alpha = 0.35f)
    val middle = gatherBarMiddle(count, error, busy)

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
                    contentDescription = when (count) {
                        0 -> GATHER_HINT
                        1 -> "1 verse selected"
                        else -> "$count verses selected"
                    }
                },
        ) {
            ShareBarIcon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cancel share",
                tint = ink.copy(alpha = 0.55f),
                onClick = onCancel,
            )
            Crossfade(
                targetState = middle,
                animationSpec = tween(durationMillis = 180),
                label = "gatherBar",
                modifier = Modifier.weight(1f),
            ) { state ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    when (state) {
                        GatherBarMiddle.Hint -> GatherHint(
                            ink = ink,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                        GatherBarMiddle.Options -> {
                            Spacer(Modifier.weight(1f))
                            SendControls(exportTint, canExport, preparingText, preparingImage, onShareText, onShareImage)
                        }
                        GatherBarMiddle.Error -> {
                            Text(
                                text = error.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = ink.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                            )
                            if (count >= 1) {
                                SendControls(exportTint, canExport, preparingText, preparingImage, onShareText, onShareImage)
                            } else {
                                Spacer(Modifier.size(BAR_ICON_SIZE))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What follows the cross on the bar. */
internal enum class GatherBarMiddle { Hint, Options, Error }

/**
 * An error outranks everything until the next export starts; otherwise the
 * send controls show once there is something to send, and the hint teaches
 * the gesture when there is not.
 */
internal fun gatherBarMiddle(count: Int, error: String?, busy: Boolean): GatherBarMiddle = when {
    !error.isNullOrBlank() && !busy -> GatherBarMiddle.Error
    count >= 1 -> GatherBarMiddle.Options
    else -> GatherBarMiddle.Hint
}

private const val GATHER_HINT = "Tap verses to share"

private val BAR_ICON_SIZE = 48.dp

/** Text and image, a tight pair at the end of the bar. */
@Composable
private fun SendControls(
    tint: Color,
    enabled: Boolean,
    preparingText: Boolean,
    preparingImage: Boolean,
    onShareText: () -> Unit,
    onShareImage: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShareBarIcon(
            imageVector = Icons.Rounded.FormatQuote,
            contentDescription = "Share as text",
            tint = tint,
            enabled = enabled,
            preparing = preparingText,
            onClick = onShareText,
        )
        ShareBarIcon(
            imageVector = Icons.Rounded.Image,
            contentDescription = "Share as image",
            tint = tint,
            enabled = enabled,
            preparing = preparingImage,
            onClick = onShareImage,
        )
    }
}

/**
 * The hint, set at the end of the bar where the send controls will appear: a
 * one-pixel hairline at 28% runs from the cross to small caps at whisper ink.
 * Page furniture, not a control. The label's right edge sits where the image
 * glyph's will (12 dp inside the bar's end), so the row keeps one silhouette
 * whether or not anything is gathered.
 */
@Composable
private fun GatherHint(ink: Color, modifier: Modifier = Modifier) {
    val rule = ink.copy(alpha = 0.28f)
    // One physical pixel. (Dp.Hairline is a stroke width: as a layout height
    // it is zero, and the rules would not exist.)
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.weight(1f).height(hairline).background(rule))
        Text(
            text = GATHER_HINT.uppercase(),
            style = MaterialTheme.typography.labelSmall
                .copy(fontSize = CAPS_LABEL_SIZE, letterSpacing = CAPS_TRACKING)
                .forCaps(),
            color = ink.copy(alpha = 0.6f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp),
        )
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
            .size(BAR_ICON_SIZE)
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
