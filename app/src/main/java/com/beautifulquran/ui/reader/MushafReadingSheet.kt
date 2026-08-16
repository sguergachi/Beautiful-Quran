package com.beautifulquran.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.theme.GeneratedHeadRule
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.ornament.generateCoverOrnament
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.ownedQuietClickable

internal val MushafGutterSlot = 44.dp
/** Running head band — a tap target tall, nothing more. */
internal val MushafRunningHead = 36.dp
/** Juzʾ slot in the running head, mirrored on the far side to centre the name. */
private val MushafHeadJuzSlot = 56.dp
/** Height of the tooled bar flanking the chapter name. */
private val MushafHeadRuleBand = 9.dp
/**
 * How long a tapped leaf is held against playback follow. Long enough for a
 * seek to land and the position poll to report a word from where the reader
 * actually tapped, short enough that a verse crossing onto the next page still
 * turns it.
 */
internal const val MushafTapPageHoldMs = 1_500L
/** One frieze for every leaf's running head, from the ornament kit. */
private const val MushafHeadOrnamentSeed = 2_000_003
/**
 * Folio band. The figure is centred in it, so the page number floats midway
 * between the last line of revelation and the transport rather than hanging
 * off the text block.
 */
internal val MushafFolioBand = 44.dp
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
    // Rank by role, not by taste. Back / play / forward are what a listener
    // reaches for, so they carry real ink; chapters, settings, repeat and speed
    // choose what to hear rather than hearing it, so they sit back. They used
    // to be the other way round — the secondaries darker than the play button,
    // and the whole bar too faint to read as active at all.
    val ink = MaterialTheme.colorScheme.onBackground
    val primary = ink.copy(alpha = 0.62f)
    val quiet = ink.copy(alpha = 0.34f)
    // Reciting, the leaf keeps only what a listener reaches for: back, pause,
    // forward. Chapters, settings, repeat and speed are for choosing what to
    // hear, not for hearing it, so they leave the paper until playback stops.
    val reciting = playerState.isPlaying && isThisSurahLoaded
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
            if (!reciting) {
                GutterIcon(
                    onClick = onOpenChapters,
                    enabled = enabled,
                    image = Icons.AutoMirrored.Rounded.MenuBook,
                    label = "Chapters",
                    tint = quiet,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                GutterIcon(
                    onClick = onOpenSettings,
                    enabled = enabled,
                    image = Icons.Rounded.Tune,
                    label = "Settings",
                    tint = quiet,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .height(44.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val rangeActive = playerState.repeatRange != null
                val singleAyah = playerState.repeatRange?.let { it.first == it.last } == true
                if (!reciting) GutterIcon(
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
                    tint = primary,
                )
                IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(44.dp)) {
                    if (playerState.isBuffering && isThisSurahLoaded) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 1.5.dp,
                            color = primary,
                        )
                    } else {
                        Icon(
                            imageVector = if (playerState.isPlaying && isThisSurahLoaded) {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            tint = primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                GutterIcon(
                    onClick = onFastForward,
                    enabled = enabled && isThisSurahLoaded,
                    image = Icons.Rounded.FastForward,
                    label = "Next",
                    tint = primary,
                )
                if (!reciting) Text(
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
    surahNameLatin: String?,
    juz: Int,
    modifier: Modifier = Modifier,
) {
    // Type alone up here, and in ink rather than gold: gold is illumination —
    // ayah marks and the chapter's title — while the running head is a finding
    // aid. Gold also loses what little contrast it has on cream, which is why
    // this line used to disappear on paper.
    //
    // Each end carries the same thing twice, Arabic over Latin, so the two read
    // as one mirrored pair rather than two labels: the chapter at the spine,
    // the juzʾ at the fore-edge.
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MushafEdgeGutter)
            .height(MushafRunningHead),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MushafHeadStack(
            arabic = "ٱلْجُزْءُ ${juz.toArabicIndic()}",
            latin = "Part $juz",
            ink = ink,
            align = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        MushafHeadStack(
            arabic = surahNameArabic?.let { "سُورَةُ $it" }.orEmpty(),
            latin = surahNameLatin.orEmpty(),
            ink = ink,
            align = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One end of the running head: the name in the book's hand, glossed beneath. */
@Composable
private fun MushafHeadStack(
    arabic: String,
    latin: String,
    ink: Color,
    align: TextAlign,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = arabic,
            fontFamily = HafsFontFamily,
            fontSize = 12.sp,
            color = ink.copy(alpha = 0.30f),
            textAlign = align,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (latin.isNotEmpty()) {
            Text(
                text = latin,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.5.sp,
                    letterSpacing = 0.10.em,
                ),
                color = ink.copy(alpha = 0.44f),
                textAlign = align,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun MushafSurahTitleBand(
    surahNameArabic: String?,
    fontSize: TextUnit,
    bandHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val gold = LocalQuranAccents.current.gold
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The rules run the full text measure; the only inset is the air the
        // name needs, so the band hangs on the same margin as the scripture.
        MushafTitleRule(
            ink = gold,
            towardStart = true,
            modifier = Modifier.weight(1f).height(bandHeight),
        )
        Text(
            text = surahNameArabic.orEmpty(),
            fontFamily = HafsFontFamily,
            fontSize = fontSize,
            color = gold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        MushafTitleRule(
            ink = gold,
            modifier = Modifier.weight(1f).height(bandHeight),
        )
    }
}

/**
 * The rule either side of a chapter's name: two hairlines with a lozenge
 * closing them at the title, dissolving toward the fore-edge.
 *
 * A tooled lattice was too loud for a line of type to sit in — it competed
 * with the name instead of carrying it. Ruled bands are what a printed mushaf
 * actually sets a title on, and they leave the gold to the name.
 */
@Composable
private fun MushafTitleRule(
    ink: Color,
    /** True for the rule whose title end is its left, mirroring the pair. */
    towardStart: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val mid = size.height / 2f
        val spread = size.height * 0.22f
        val hair = size.height * 0.055f
        // Full ink where it meets the name, gone by the margin. The rule is
        // laid in the page's right-to-left run, so each side already fades
        // away from the title it starts at.
        val brush = if (towardStart) {
            Brush.horizontalGradient(
                0f to ink.copy(alpha = 0.62f),
                0.55f to ink.copy(alpha = 0.34f),
                1f to Color.Transparent,
            )
        } else {
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.45f to ink.copy(alpha = 0.34f),
                1f to ink.copy(alpha = 0.62f),
            )
        }
        for (dy in floatArrayOf(-spread, spread)) {
            drawLine(
                brush = brush,
                start = Offset(0f, mid + dy),
                end = Offset(size.width, mid + dy),
                strokeWidth = hair,
            )
        }
        // Lozenge finial, closing the pair at the title end.
        val r = size.height * 0.30f
        val cx = if (towardStart) r else size.width - r
        val lozenge = Path().apply {
            moveTo(cx, mid - r)
            lineTo(cx + r * 0.62f, mid)
            lineTo(cx, mid + r)
            lineTo(cx - r * 0.62f, mid)
            close()
        }
        drawPath(lozenge, color = ink.copy(alpha = 0.62f))
    }
}

/**
 * Folio: the mushaf's own Arabic-Indic figure, with the Western numeral
 * as a faint letterspaced gloss beside it — the same pairing the scroll
 * layout's page-break hairline uses, minus the dot that made it a label.
 */
@Composable
internal fun MushafPageFolio(page: Int, modifier: Modifier = Modifier) {
    // In ink, like the running head: at gold-on-cream the figure measured
    // 1.5:1 against the paper and simply vanished.
    val ink = MaterialTheme.colorScheme.onBackground
    val folio = buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = ink.copy(alpha = 0.50f),
                fontSize = 9.sp,
                letterSpacing = 0.14.em,
            ),
        ) {
            append("$page")
        }
        append("  ")
        withStyle(
            SpanStyle(
                color = ink.copy(alpha = 0.54f),
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
