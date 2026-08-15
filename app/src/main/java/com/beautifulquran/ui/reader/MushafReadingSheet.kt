package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.ownedQuietClickable

internal val MushafGutterSlot = 48.dp
internal val MushafGutterBand = 40.dp
internal val MushafTextGutter = 16.dp
/**
 * Book window: the gilt leaf turns above; transport is a quiet line of
 * ink on the paper under the book — never on the gilt.
 */
@Composable
internal fun MushafReadingSheet(
    reciterName: String,
    playerState: PlayerUiState,
    isThisSurahLoaded: Boolean,
    enabled: Boolean,
    onOpenSettings: () -> Unit,
    onPlayPause: () -> Unit,
    onFastBackward: () -> Unit,
    onFastForward: () -> Unit,
    onRepeatClick: () -> Unit,
    onSpeed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val rangeActive = playerState.repeatRange != null
                val singleAyah = playerState.repeatRange?.let { it.first == it.last } == true
                GutterIcon(
                    onClick = onRepeatClick,
                    enabled = enabled,
                    image = if (playerState.repeatMode == Player.REPEAT_MODE_ONE || singleAyah) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    },
                    label = "Repeat",
                    tint = if (playerState.repeatMode == Player.REPEAT_MODE_OFF && !rangeActive) {
                        quiet
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                GutterIcon(
                    onClick = onFastBackward,
                    enabled = enabled && isThisSurahLoaded,
                    image = Icons.Rounded.FastRewind,
                    label = "Previous",
                    tint = quiet,
                )
                IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(44.dp)) {
                    if (playerState.isBuffering && isThisSurahLoaded) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 1.5.dp,
                            color = quiet,
                        )
                    } else {
                        Icon(
                            imageVector = if (playerState.isPlaying && isThisSurahLoaded) {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            tint = quiet,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                GutterIcon(
                    onClick = onFastForward,
                    enabled = enabled && isThisSurahLoaded,
                    image = Icons.Rounded.FastForward,
                    label = "Next",
                    tint = quiet,
                )
                Text(
                    text = "${if (playerState.speed % 1f == 0f) playerState.speed.toInt() else playerState.speed}×",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (playerState.speed == 1f) quiet else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .width(MushafGutterSlot)
                        .ownedQuietClickable(role = Role.Button, onClick = onSpeed),
                    textAlign = TextAlign.Center,
                )
            }
            if (reciterName.isNotEmpty()) {
                Text(
                    text = reciterName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = quiet.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .ownedQuietClickable(role = Role.Button, onClick = onOpenSettings),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun MushafPageHeader(
    surahNameArabic: String?,
    enabled: Boolean,
    onOpenChapters: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    val gold = LocalQuranAccents.current.gold.copy(alpha = 0.58f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MushafGutterBand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(MushafGutterSlot), contentAlignment = Alignment.Center) {
            IconButton(onClick = onOpenChapters, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = "Chapters",
                    tint = quiet,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = surahNameArabic?.let { "سُورَةُ $it" }.orEmpty(),
            fontFamily = HafsFontFamily,
            fontSize = 14.sp,
            color = gold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(MushafGutterSlot), contentAlignment = Alignment.Center) {
            IconButton(onClick = onOpenSettings, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = "Settings",
                    tint = quiet,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun MushafPageFolio(page: Int, modifier: Modifier = Modifier) {
    val gold = LocalQuranAccents.current.gold.copy(alpha = 0.58f)
    Text(
        text = "$page  ·  ${page.toArabicIndic()}",
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            color = gold,
        ),
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(MushafGutterBand)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

@Composable
private fun GutterIcon(
    onClick: () -> Unit,
    enabled: Boolean,
    image: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(image, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}
