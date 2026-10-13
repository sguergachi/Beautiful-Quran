package com.beautifulquran.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bottom clearance shared by every paper float above the player / cover
 * transport: return-to-ayah roundel, Back-to capsule, and the chapter-list
 * [com.beautifulquran.ui.home.FloatingPlaybackControl]. One inset keeps the
 * paper stack on a single vertical rhythm when turning between cover and
 * reader. See docs/DESIGN.md.
 */
val FloatingControlBottomInset: Dp = 10.dp

/** Enter: fade + full-height slide up from below the slot. */
val FloatingPaperEnter: EnterTransition =
    fadeIn(tween(280)) + slideInVertically(
        animationSpec = tween(320),
        initialOffsetY = { it },
    )

/** Exit: fade + half-height slide down. */
val FloatingPaperExit: ExitTransition =
    fadeOut(tween(220)) + slideOutVertically(
        animationSpec = tween(260),
        targetOffsetY = { it / 2 },
    )

/**
 * Shared host for floating paper ornaments (return-to-ayah roundel, Back-to
 * capsule). Owns the entrance/exit motion and the bottom inset; callers
 * supply alignment / nav-bar / player clearance on [modifier] and the
 * ornament itself as [content].
 */
@Composable
fun FloatingPaperControl(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = FloatingPaperEnter,
    exit: ExitTransition = FloatingPaperExit,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.padding(bottom = FloatingControlBottomInset),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
