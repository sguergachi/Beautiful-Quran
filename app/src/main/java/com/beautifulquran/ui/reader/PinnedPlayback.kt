package com.beautifulquran.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.home.ReturnToAyahPill
import com.beautifulquran.ui.theme.QuranTheme

/**
 * Chapter playback published by the reader sheet and hosted beside the paper
 * stack. Home covers it until playback has created a session.
 * Callbacks are plain vars: refreshing them must not recompose the bar.
 */
class PinnedPlaybackHost {
    var player by mutableStateOf<PlayerUiState?>(null)
    var reciterName by mutableStateOf("")
    var enabled by mutableStateOf(true)
    var thisSurahLoaded by mutableStateOf(false)
    /** Draw-phase read. Assigning this must not publish the animated float. */
    var chromeAlpha: () -> Float = { 1f }
    var onPlayPause: () -> Unit = {}
    var onFastBackward: () -> Unit = {}
    var onFastForward: () -> Unit = {}
    var onRepeatClick: () -> Unit = {}
    var onSpeed: () -> Unit = {}
    var onReciterClick: () -> Unit = {}
    var inkLabAvailable by mutableStateOf(false)
    var inkLabOpen by mutableStateOf(false)

    /** A verse is loaded and search is not covering the chapter list. */
    var coverSession by mutableStateOf(false)
    /** Keeps the Home chrome above its sheet until the dismissal slide finishes. */
    var closing by mutableStateOf(false)
    var chapterLabel by mutableStateOf("")
    var ayahLabel by mutableStateOf("")
    var onOpenNowPlaying: () -> Unit = {}
    var onClose: () -> Unit = {}
}

/** Keeps one bar mounted for the scroll reader, including every page turn. */
internal fun showPinnedChapterBar(
    readerOpen: Boolean,
    mushaf: Boolean,
    gathering: Boolean,
    overlayBlocking: Boolean,
): Boolean = readerOpen && !mushaf && !gathering && !overlayBlocking

/** Unplayed transport belongs under Home (z=2), above the reader (z=1). */
internal fun pinnedChapterBarZIndex(coverSession: Boolean): Float =
    if (coverSession) 2.3f else 1.3f

/** Before playback, the bar shares the chapter's reveal; afterward it stays pinned. */
internal fun pinnedBarReveal(stackPage: Float, coverSession: Boolean): Float =
    if (coverSession) 1f else stackPage.coerceIn(0f, 1f)

/**
 * How far the pinned bar has turned away with the reader sheet: 0 while the
 * reader (or the cover) holds it, 1 once Settings has taken the stack.
 */
internal fun pinnedBarTurn(stackPage: Float): Float = (stackPage - 1f).coerceIn(0f, 1f)

@Composable
internal fun PinnedChapterPlayback(
    host: PinnedPlaybackHost,
    onCover: Boolean,
    onHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = host.player ?: return
    val density = LocalDensity.current
    val closeProgress = remember { Animatable(0f) }
    LaunchedEffect(host.closing, host.coverSession) {
        if (host.closing) {
            try {
                closeProgress.animateTo(1f, tween(260))
            } finally {
                host.closing = false
            }
        } else if (!host.coverSession) {
            // Reset only after Home has covered the bar, avoiding a one-frame flash.
            closeProgress.snapTo(0f)
        }
    }
    val showCoverChrome = onCover && host.coverSession
    val edgePad by animateDpAsState(
        targetValue = if (showCoverChrome) 48.dp else 0.dp,
        animationSpec = tween(200),
        label = "playbackEdgePad",
    )
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = size.height * closeProgress.value }
            .onGloballyPositioned { coords ->
                onHeight(with(density) { coords.size.height.toDp() })
            },
    ) {
        PlayerBar(
            state = state,
            isThisSurahLoaded = host.thisSurahLoaded,
            enabled = !host.closing && (onCover || host.enabled),
            chromeAlpha = { if (onCover) 1f else host.chromeAlpha() },
            reciterName = host.reciterName,
            onPlayPause = host.onPlayPause,
            onFastBackward = host.onFastBackward,
            onFastForward = host.onFastForward,
            onRepeatClick = host.onRepeatClick,
            onSpeed = host.onSpeed,
            onReciterClick = host.onReciterClick,
            inkLabAvailable = host.inkLabAvailable,
            inkLabOpen = host.inkLabOpen,
            onInkLabClick = { host.inkLabOpen = !host.inkLabOpen },
            includeNavigationPadding = true,
            edgePad = edgePad,
            edgeChrome = {
                AnimatedVisibility(
                    visible = showCoverChrome,
                    enter = fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 3 },
                    exit = fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                ) {
                    IconButton(
                        onClick = {
                            host.closing = true
                            host.onClose()
                        },
                        enabled = !host.closing,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close playback",
                            tint = QuranTheme.ink.furniture,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            belowReciter = {
                AnimatedVisibility(
                    visible = showCoverChrome,
                    enter = fadeIn(tween(220)) + expandVertically(tween(240)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(180)),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ReturnToAyahPill(
                            chapterLabel = host.chapterLabel,
                            ayahLabel = host.ayahLabel,
                            onClick = host.onOpenNowPlaying,
                        )
                    }
                }
            },
        )
    }
}
