package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.beautifulquran.playback.PlayerUiState

/**
 * Flat playback controls that sit on the same sheet of paper as the text —
 * no elevation, no card. The reading column fades out just above it.
 *
 * The reciter name gets its own centered line above the transport row, so the
 * row itself stays symmetric — two controls either side of play — and the
 * play button lands exactly on the page's center line.
 */
@Composable
fun PlayerBar(
    state: PlayerUiState,
    isThisSurahLoaded: Boolean,
    /**
     * When false, transport stays visible but does not compete with a
     * contextual guide's **Got it** action drawn over the same corner.
     */
    enabled: Boolean = true,
    chromeAlpha: () -> Float,
    reciterName: String,
    onPlayPause: () -> Unit,
    onFastBackward: () -> Unit,
    onFastForward: () -> Unit,
    onRepeatClick: () -> Unit,
    onSpeed: () -> Unit,
    onReciterClick: () -> Unit,
) {
    val compact = LocalConfiguration.current.screenWidthDp < 340
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            TextButton(
                onClick = onReciterClick,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                modifier = Modifier.graphicsLayer { alpha = chromeAlpha() },
            ) {
                Text(
                    text = reciterName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                val rangeActive = state.repeatRange != null
                val singleAyahRange = state.repeatRange?.let { it.first == it.last } == true
                IconButton(
                    onClick = onRepeatClick,
                    enabled = enabled,
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { alpha = chromeAlpha() },
                ) {
                    Icon(
                        imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE || singleAyahRange) {
                            Icons.Rounded.RepeatOne
                        } else {
                            Icons.Rounded.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF && !rangeActive) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                IconButton(
                    onClick = onFastBackward,
                    enabled = enabled && isThisSurahLoaded,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.FastRewind,
                        contentDescription = "Fast backward",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onPlayPause,
                    enabled = enabled,
                    modifier = Modifier.size(56.dp),
                ) {
                    if (state.isBuffering && isThisSurahLoaded) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying && isThisSurahLoaded) {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onFastForward,
                    enabled = enabled && isThisSurahLoaded,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.FastForward,
                        contentDescription = "Fast forward",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onSpeed,
                    enabled = enabled,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { alpha = chromeAlpha() },
                ) {
                    Text(
                        text = "${if (state.speed % 1f == 0f) state.speed.toInt() else state.speed}×",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (state.speed == 1f) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
    }
}
