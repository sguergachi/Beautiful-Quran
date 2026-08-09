package com.beautifulquran.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.theme.FloatingControlBottomInset
import com.beautifulquran.ui.theme.FloatingPaperEnter
import com.beautifulquran.ui.theme.FloatingPaperExit
import com.beautifulquran.ui.theme.quietClickable

/** Extra list padding so the last surah rows clear the floating transport. */
val FloatingPlaybackListClearance: Dp = 96.dp

/**
 * How far the paper stack may leave the cover before the float starts its
 * exit (and how close it must return before the enter). Tuned so the slide
 * plays while the chapter sheet is still mostly on screen.
 */
const val FloatingPlaybackCoverVisibleMaxPage = 0.45f

/**
 * Paper-native floating transport for the chapter list. Same controls as the
 * reader's embedded [com.beautifulquran.ui.reader.PlayerBar], but it lives as
 * quiet ink over the cover sheet — no card, elevation, or border — and slides
 * up only while a verse is loaded (playing or paused mid-session) and the
 * chapter-selection page is in view. Its now-playing reference is a quiet
 * green return pill. An opaque paper [Surface] masks the list beneath,
 * matching the embedded bar. Uses the same enter/exit motion as
 * [com.beautifulquran.ui.theme.FloatingPaperControl]. A quiet Close dismisses
 * the session so the bar leaves with the shared exit animation.
 */
@Composable
fun FloatingPlaybackControl(
    visible: Boolean,
    state: PlayerUiState,
    chapterLabel: String,
    ayahLabel: String,
    reciterName: String,
    onOpenNowPlaying: () -> Unit,
    onReciterClick: () -> Unit,
    onPlayPause: () -> Unit,
    onFastBackward: () -> Unit,
    onFastForward: () -> Unit,
    onRepeatClick: () -> Unit,
    onSpeed: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = FloatingPaperEnter,
        exit = FloatingPaperExit,
        modifier = modifier,
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = FloatingControlBottomInset),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = onReciterClick,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
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
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                RoundedCornerShape(50),
                            )
                            .quietClickable(onClick = onOpenNowPlaying)
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                            .semantics {
                                contentDescription = "Return to $chapterLabel · $ayahLabel"
                                role = Role.Button
                            },
                    ) {
                        Text(
                            text = chapterLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                        Text(
                            text = "  ·  ",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        )
                        Text(
                            text = ayahLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(18.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        modifier = Modifier
                            .widthIn(max = 680.dp)
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                    ) {
                        val rangeActive = state.repeatRange != null
                        val singleAyahRange = state.repeatRange?.let { it.first == it.last } == true
                        IconButton(onClick = onRepeatClick, modifier = Modifier.size(48.dp)) {
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
                        IconButton(onClick = onFastBackward, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Rounded.FastRewind,
                                contentDescription = "Fast backward",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                            if (state.isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Icon(
                                    imageVector = if (state.isPlaying) {
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
                        IconButton(onClick = onFastForward, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Rounded.FastForward,
                                contentDescription = "Fast forward",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = onSpeed,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Text(
                                text = "${if (state.speed % 1f == 0f) state.speed.toInt() else state.speed}×",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.speed == 1f) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp, top = 2.dp)
                        .size(40.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close playback",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Cover-sheet float is shown only while a verse is loaded **and** the paper
 * stack is still on (or returning to) chapter selection. [coverSheetVisible]
 * is driven from the stack page so enter/exit play across open/close.
 * [searchActive] (field focused or a query on the sheet) keeps the transport
 * off the paper until search mode is dismissed.
 */
internal fun shouldShowFloatingPlayback(
    nowPlayingPresent: Boolean,
    coverSheetVisible: Boolean,
    searchActive: Boolean = false,
): Boolean = nowPlayingPresent && coverSheetVisible && !searchActive
