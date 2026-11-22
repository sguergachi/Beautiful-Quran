package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.beautifulquran.playback.PlayerUiState

/*
 * What the three transports (the reader's PlayerBar, the mushaf gutter and
 * Home's floating control) show for the same player state. Their containers
 * differ; the glyphs, labels and "lit" rules must not.
 */

/** Repeat is lit for any repeat mode or an active verse range. */
internal val PlayerUiState.repeatActive: Boolean
    get() = repeatMode != Player.REPEAT_MODE_OFF || repeatRange != null

/** Repeat-one for a single verse (by mode or by a one-verse range). */
internal val PlayerUiState.repeatIcon: ImageVector
    get() = if (repeatMode == Player.REPEAT_MODE_ONE || repeatRange?.let { it.first == it.last } == true) {
        Icons.Rounded.RepeatOne
    } else {
        Icons.Rounded.Repeat
    }

internal val PlayerUiState.speedActive: Boolean get() = speed != 1f

/** `1×`, `1.25×` — whole speeds without a decimal. */
internal val PlayerUiState.speedLabel: String
    get() = "${if (speed % 1f == 0f) speed.toInt() else speed}×"

/**
 * The play/pause glyph: a spinner while [buffering], else Pause while
 * [playing], else Play. The spoken label follows the same condition the icon is
 * drawn from — while another chapter plays, a sheet's button shows Play and
 * must not be announced as Pause (#725).
 */
@Composable
internal fun PlayPauseGlyph(playing: Boolean, buffering: Boolean, tint: Color) {
    if (buffering) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = tint,
        )
    } else {
        Icon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(34.dp),
        )
    }
}
