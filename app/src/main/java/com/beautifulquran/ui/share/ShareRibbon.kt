package com.beautifulquran.ui.share

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatQuote
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.reader.CAPS_LABEL_SIZE
import com.beautifulquran.ui.reader.CAPS_TRACKING
import com.beautifulquran.ui.theme.forCaps
import com.beautifulquran.ui.theme.quietClickable

/**
 * Replaces the player bar while gathering. Not a copy of play.
 *
 * Leave at the start; everything else is one centred slot between two
 * hairlines, and what it holds follows the gather ([gatherBarMiddle]):
 *
 * - nothing gathered: TAP VERSES TO SHARE, in small caps — the only time the
 *   reader needs telling (gather opens with the held verse already in it);
 * - one or more: the ways it can go, named — ❝ TEXT · ▣ IMAGE;
 * - an export failed: the error, in the same place.
 *
 * Nothing sits at the end. A blank the cross's width mirrors it, so the slot
 * is centred on the bar, not on what is left of it — the old row of one
 * control before the middle and two after it could not be balanced by any
 * amount of tone or rule length. The bar is the theme's DeepGreen, so
 * gather is a different foot of the sheet than play.
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
    val middle = gatherBarMiddle(count, error, busy)
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    val rule = ink.copy(alpha = 0.28f)
    val capsStyle = MaterialTheme.typography.labelSmall
        .copy(fontSize = CAPS_LABEL_SIZE, letterSpacing = CAPS_TRACKING)
        .forCaps()

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
                    contentDescription = when {
                        count == 0 -> GATHER_HINT
                        count == 1 -> "1 verse selected"
                        else -> "$count verses selected"
                    }
                },
        ) {
            ShareBarIcon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cancel share",
                tint = ink.copy(alpha = 0.7f),
                onClick = onCancel,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                // The rules stay put; only what sits between them changes.
                Box(Modifier.weight(1f).height(hairline).background(rule))
                Crossfade(
                    targetState = middle,
                    animationSpec = tween(durationMillis = 180),
                    label = "gatherBarMiddle",
                ) { state ->
                    when (state) {
                        GatherBarMiddle.Hint -> Text(
                            text = GATHER_HINT.uppercase(),
                            style = capsStyle,
                            color = ink.copy(alpha = 0.6f),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        GatherBarMiddle.Options -> Row(verticalAlignment = Alignment.CenterVertically) {
                            ShareOption(
                                word = "Text",
                                icon = Icons.Rounded.FormatQuote,
                                contentDescription = "Share as text",
                                tint = ink,
                                style = capsStyle,
                                enabled = !busy,
                                preparing = preparingText,
                                onClick = onShareText,
                            )
                            Text(
                                text = "·",
                                style = capsStyle,
                                color = ink.copy(alpha = 0.45f),
                            )
                            ShareOption(
                                word = "Image",
                                icon = Icons.Outlined.Image,
                                contentDescription = "Share as image",
                                tint = ink,
                                style = capsStyle,
                                enabled = !busy,
                                preparing = preparingImage,
                                onClick = onShareImage,
                            )
                        }
                        GatherBarMiddle.Error -> Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = ink.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                Box(Modifier.weight(1f).height(hairline).background(rule))
            }
            // The cross's width, mirrored, so the slot centres on the bar.
            Spacer(Modifier.size(BAR_ICON_SIZE))
        }
    }
}

/** What the bar's centred slot holds. */
internal enum class GatherBarMiddle { Hint, Options, Error }

/**
 * An error outranks everything until the next export starts; otherwise the
 * slot names the share options once there is something to share, and
 * teaches the gesture when there is not.
 */
internal fun gatherBarMiddle(count: Int, error: String?, busy: Boolean): GatherBarMiddle = when {
    !error.isNullOrBlank() && !busy -> GatherBarMiddle.Error
    count >= 1 -> GatherBarMiddle.Options
    else -> GatherBarMiddle.Hint
}

private const val GATHER_HINT = "Tap verses to share"

private val BAR_ICON_SIZE = 48.dp

/** One way to share, named: its glyph and its word in small caps, a 48 dp target. */
@Composable
private fun ShareOption(
    word: String,
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    style: TextStyle,
    enabled: Boolean,
    preparing: Boolean,
    onClick: () -> Unit,
) {
    val shown = if (enabled || preparing) tint else tint.copy(alpha = 0.35f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(BAR_ICON_SIZE)
            .quietClickable(
                enabled = enabled && !preparing,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
            if (preparing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.5.dp,
                    color = shown,
                )
            } else {
                Icon(icon, contentDescription = null, tint = shown, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = word.uppercase(),
            style = style,
            color = shown,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ShareBarIcon(
    imageVector: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BAR_ICON_SIZE)
            .quietClickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(
            imageVector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
