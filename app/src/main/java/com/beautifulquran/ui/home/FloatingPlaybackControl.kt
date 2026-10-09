package com.beautifulquran.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
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
import com.beautifulquran.ui.reader.InkLabPanel
import com.beautifulquran.ui.reader.InkLabToggleButton
import com.beautifulquran.ui.reader.ReciterNameButton
import com.beautifulquran.ui.theme.FloatingPaperEnter
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.QuranTheme

/** Extra list padding so the last surah rows clear the floating transport. */
val FloatingPlaybackListClearance: Dp = 96.dp

/**
 * Dismissal sweep, matching the shared enter's full travel. The transport
 * leaves as one opaque sheet instead of dissolving in place, so it uncovers
 * the rows beneath rather than smearing over them. The list inset eases
 * over exactly this long with the same easing, so the whole assembly
 * travels as one.
 */
internal const val HomeFloatExitMs = 260

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
 * green return pill under the reciter, then the transport, with the same
 * 4 dp foot as the reader bar. The home scaffold already keeps this paper
 * above the navigation bar, so the bar does not add that inset again, and
 * it does not take the ornaments' 10 dp foot. An opaque paper [Surface]
 * masks the list beneath, matching the embedded bar. Enter shares
 * [com.beautifulquran.ui.theme.FloatingPaperEnter]; exit sweeps the whole
 * sheet down ([HomeFloatExitMs]) to uncover the rows instead of dissolving
 * over them. A quiet Close dismisses
 * the session so the bar leaves with that sweep.
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
    onDismissError: () -> Unit,
    onPlayPause: () -> Unit,
    onFastBackward: () -> Unit,
    onFastForward: () -> Unit,
    onRepeatClick: () -> Unit,
    onSpeed: () -> Unit,
    onClose: () -> Unit,
    inkLabAvailable: Boolean = false,
    inkLabOpen: Boolean = false,
    onInkLabClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = FloatingPaperEnter,
        exit = slideOutVertically(animationSpec = tween(HomeFloatExitMs)) { it },
        modifier = modifier,
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            // Paper runs to the screen edge. The navigation inset sits inside
            // it, under the 4 dp foot, so the transport stays above the
            // home gesture bar.
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        if (inkLabOpen && state.error == null) {
                            InkLabPanel(
                                modifier = Modifier.padding(start = 40.dp, end = 44.dp),
                            )
                        } else {
                            ReciterNameButton(
                                name = reciterName,
                                notice = state.error,
                                onDismissNotice = onDismissError,
                                onClick = onReciterClick,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 48.dp),
                            )
                        }
                        if (inkLabAvailable) {
                            InkLabToggleButton(
                                expanded = inkLabOpen,
                                onClick = onInkLabClick,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                        }
                    }
                    ReturnToAyahPill(
                        chapterLabel = chapterLabel,
                        ayahLabel = ayahLabel,
                        onClick = onOpenNowPlaying,
                    )
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
                                    QuranTheme.ink.furniture
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
                                    QuranTheme.ink.furniture
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
                        tint = QuranTheme.ink.furniture,
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
/** Quiet green stadium: chapter · ayah, returning to that verse. */
@Composable
internal fun ReturnToAyahPill(
    chapterLabel: String,
    ayahLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            // Uneven on purpose: the reciter's name is small and quiet, so
            // equal air above reads as more than the air over the transport.
            .padding(top = 2.dp, bottom = 6.dp)
            .background(
                QuranTheme.accents.greenWash,
                RoundedCornerShape(50),
            )
            .quietClickable(onClick = onClick)
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
            color = QuranTheme.accents.greenQuiet,
        )
        Text(
            text = ayahLabel,
            style = MaterialTheme.typography.titleMedium,
            color = QuranTheme.accents.greenInk,
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
}

internal fun shouldShowFloatingPlayback(
    nowPlayingPresent: Boolean,
    coverSheetVisible: Boolean,
    searchActive: Boolean = false,
): Boolean = nowPlayingPresent && coverSheetVisible && !searchActive

/**
 * Bottom paper the chapter list keeps clear of the transport. The pinned
 * chapter session wins while it owns a verse (or is sliding away); else
 * the floating bar while it is up; else just the gesture inset. The caller
 * animates toward this target over the sweep length, so bar, fade band,
 * and rows travel as one instead of popping.
 */
internal fun homeListBottomInset(
    pinnedSession: Boolean,
    pinnedHeight: Dp,
    floatingVisible: Boolean,
    floatingHeight: Dp,
    navigationBottom: Dp,
): Dp = when {
    pinnedSession -> pinnedHeight.takeIf { it > 0.dp } ?: FloatingPlaybackListClearance
    floatingVisible -> floatingHeight
    else -> navigationBottom
}
