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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.ownedQuietClickable

internal val MushafGutterSlot = 44.dp
/** Running head band — a tap target tall, nothing more. */
internal val MushafRunningHead = 36.dp
/** Folio line: type only, so it costs a line of ink and no more. */
internal val MushafFolioBand = 24.dp
/** Fore-edge margin. The page has no frame, so this is the whole margin. */
internal val MushafPageMargin = 10.dp
/** Running head to first line of revelation. */
internal val MushafTextGutter = 10.dp
/** Last line to folio. A book's tail margin is the deeper of the two. */
internal val MushafTailGutter = 14.dp
/**
 * Book window: the leaf turns above; transport is a quiet line of ink on
 * the paper under the page. No frame — the paper runs to the edges and the
 * text block is the only thing composed on it. Chapters and settings live
 * down here at the fore-edges, with the book's other controls: the leaf
 * itself stays scripture and running head only.
 */
@Composable
internal fun MushafReadingSheet(
    reciterName: String,
    playerState: PlayerUiState,
    isThisSurahLoaded: Boolean,
    enabled: Boolean,
    onOpenChapters: () -> Unit,
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
                .padding(horizontal = MushafPageMargin, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
            GutterIcon(
                onClick = onOpenChapters,
                enabled = enabled,
                image = Icons.AutoMirrored.Rounded.MenuBook,
                label = "Chapters",
                tint = quiet.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterStart),
            )
            GutterIcon(
                onClick = onOpenSettings,
                enabled = enabled,
                image = Icons.Rounded.Tune,
                label = "Settings",
                tint = quiet.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
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

/**
 * The printed page's own running head: surah name at the fore-edge the
 * reading starts from, juzʾ at the other. No controls — the leaf carries
 * nothing but what the mushaf prints on it.
 */
@Composable
internal fun MushafPageHeader(
    surahNameArabic: String?,
    juz: Int,
    modifier: Modifier = Modifier,
) {
    val gold = LocalQuranAccents.current.gold.copy(alpha = 0.50f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MushafRunningHead),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ٱلْجُزْءُ ${juz.toArabicIndic()}",
            fontFamily = HafsFontFamily,
            fontSize = 12.sp,
            color = gold.copy(alpha = 0.38f),
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = surahNameArabic?.let { "سُورَةُ $it" }.orEmpty(),
            fontFamily = HafsFontFamily,
            fontSize = 13.sp,
            color = gold,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Folio: the mushaf's own Arabic-Indic figure, with the Western numeral
 * as a faint letterspaced gloss beside it — the same pairing the scroll
 * layout's page-break hairline uses, minus the dot that made it a label.
 */
@Composable
internal fun MushafPageFolio(page: Int, modifier: Modifier = Modifier) {
    val gold = LocalQuranAccents.current.gold
    val folio = buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = gold.copy(alpha = 0.38f),
                fontSize = 9.sp,
                letterSpacing = 0.14.em,
            ),
        ) {
            append("$page")
        }
        append("  ")
        withStyle(
            SpanStyle(
                color = gold.copy(alpha = 0.46f),
                fontFamily = HafsFontFamily,
                fontSize = 12.sp,
            ),
        ) {
            append(page.toArabicIndic())
        }
    }
    Text(
        text = folio,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(MushafFolioBand)
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
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(40.dp)) {
        Icon(image, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}
