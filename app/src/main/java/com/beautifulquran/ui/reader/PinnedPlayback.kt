package com.beautifulquran.ui.reader

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.home.ReturnToAyahPill
import com.beautifulquran.ui.theme.QuranTheme

/**
 * Chapter playback published by the reader sheet and drawn above the paper
 * stack, so a cover ↔ reader turn does not carry the bar away with the page.
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

    /** A verse is loaded and search is not covering the chapter list. */
    var coverSession by mutableStateOf(false)
    var chapterLabel by mutableStateOf("")
    var ayahLabel by mutableStateOf("")
    var onOpenNowPlaying: () -> Unit = {}
    var onClose: () -> Unit = {}
}

/**
 * The chapter bar stays while the cover and the reader trade places
 * ([stackPage] 0..1). On the chapter list it stays only when a verse is
 * loaded. Mushaf, gather, and ink-bleed overlays keep their own chrome.
 *
 * Past the reader it stays pinned too, and rides the reader sheet away as
 * Settings arrives ([pinnedBarTurn]). It used to be dropped there and the
 * reader set its own bar instead: that swap recomposed the whole reader and
 * the chapter list, and built a second bar, two points into the swipe — the
 * dropped frames at the start of every turn to Settings.
 */
internal fun showPinnedChapterBar(
    stackPage: Float,
    readerOpen: Boolean,
    mushaf: Boolean,
    gathering: Boolean,
    overlayBlocking: Boolean,
    coverSession: Boolean,
): Boolean {
    if (!readerOpen || mushaf || gathering || overlayBlocking) return false
    if (stackPage < 0.5f && !coverSession) return false
    return true
}

/**
 * How far the pinned bar has turned away with the reader sheet: 0 while the
 * reader (or the cover) holds it, 1 once Settings has taken the stack.
 */
internal fun pinnedBarTurn(stackPage: Float): Float = (stackPage - 1f).coerceIn(0f, 1f)

/**
 * Scroll's top-bar back arrow. Shown only while that sheet is parked.
 * A swipe and the settle after it keep the arrow off. Mushaf keeps its book
 * control. The scrolling play bar itself never draws a back arrow.
 */
internal fun showScrollReaderBackArrow(
    page: Float,
    mushaf: Boolean,
    dragHidesBack: Boolean,
): Boolean {
    if (mushaf) return true
    if (dragHidesBack) return false
    return page in 0.99f..1.01f
}

/**
 * Finger-up never reveals the scroll reader's back arrow.
 *
 * True when this settle must hide it. A release already parked on the
 * reader returns false, so the caller leaves the flag as the drag set it.
 * The settle's end is the only place that shows the arrow again.
 */
internal fun hideScrollBackOnFingerUp(
    scrollReaderOpen: Boolean,
    target: Int,
    page: Float,
): Boolean {
    if (!scrollReaderOpen) return false
    val alreadyParked = target == 1 && page in 0.99f..1.01f
    return !alreadyParked
}

@Composable
internal fun PinnedChapterPlayback(
    host: PinnedPlaybackHost,
    onCover: Boolean,
    onHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = host.player ?: return
    val density = LocalDensity.current
    val showCoverChrome = onCover && host.coverSession
    val edgePad by animateDpAsState(
        targetValue = if (showCoverChrome) 48.dp else 0.dp,
        animationSpec = tween(200),
        label = "playbackEdgePad",
    )
    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                onHeight(with(density) { coords.size.height.toDp() })
            },
    ) {
        PlayerBar(
            state = state,
            isThisSurahLoaded = host.thisSurahLoaded,
            enabled = if (onCover) true else host.enabled,
            chromeAlpha = { if (onCover) 1f else host.chromeAlpha() },
            reciterName = host.reciterName,
            onPlayPause = host.onPlayPause,
            onFastBackward = host.onFastBackward,
            onFastForward = host.onFastForward,
            onRepeatClick = host.onRepeatClick,
            onSpeed = host.onSpeed,
            onReciterClick = host.onReciterClick,
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
                    IconButton(onClick = host.onClose, modifier = Modifier.size(40.dp)) {
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
