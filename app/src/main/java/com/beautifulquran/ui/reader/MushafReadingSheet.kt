package com.beautifulquran.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
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
import com.beautifulquran.domain.MushafGrid
import com.beautifulquran.domain.MushafType
import kotlin.math.pow
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.ownedQuietClickable

internal val MushafGutterSlot = 44.dp
/** Running head band — a tap target tall, nothing more. */
internal val MushafRunningHead = 36.dp
/**
 * How long a tapped leaf is held against playback follow. Long enough for a
 * seek to land and the position poll to report a word from where the reader
 * actually tapped, short enough that a verse crossing onto the next page still
 * turns it.
 */
internal const val MushafTapPageHoldMs = 1_500L
/**
 * Folio band, with the figure centred in it.
 *
 * The page number belongs to the leaf, so it must sit nearer the last line of
 * revelation than the rule below it — proximity is what assigns it. Measured
 * against the transport instead, it drifted every time the chrome changed:
 * when the progress rule arrived between them it left the folio 120px under
 * the text and 64px over the rule, reading as part of the controls.
 */
internal val MushafFolioBand = 30.dp
/** Paper above and below the rule that divides the leaf from the transport. */
private val MushafRuleTailAir = 10.dp

/** Each folio figure's column, equal either side of the centre line. */
private val MushafFolioColumn = 40.dp
/** Paper between the two figures. */
private val MushafFolioSpread = 28.dp
/** The lozenge set between them. */
private val MushafFolioDiamond = 5.dp

/** Fore-edge margin. The page has no frame, so this is the whole margin. */
/**
 * Paper outside the mark gutter.
 *
 * The leaf's type is bound by its width, not its height — the well has room to
 * spare, and every pixel of measure is a pixel of type. So this is as narrow as
 * the fore-edge fade can be drawn over without reaching the mark gutter.
 */
internal val MushafPageMargin = 4.dp
/**
 * Running head to first line of revelation. A head that sits closer than
 * about a line's pitch reads as part of the block instead of standing off it;
 * at 10dp it stood off by half a line while the tail margin ran to a full one.
 */
internal val MushafTextGutter = 20.dp
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
    /** How far into the chapter the recitation has come, 0..1. */
    chapterProgress: Float,
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
    // Reciting, the choosers recede almost to nothing rather than blinking out
    // of the row: the same fade the scroll layout gives its chrome, so the
    // transport never jumps under the finger.
    val secondaryFade by animateFloatAsState(
        targetValue = if (reciting) 0.05f else 1f,
        animationSpec = tween(InkEngine.tuning.recessMs, easing = FastOutSlowInEasing),
        label = "mushafSecondaryFade",
    )
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
        MushafProgressRule(
            progress = chapterProgress,
            reciting = reciting,
            // Paper between the leaf's own tail and the rule, so the folio
            // groups with the page above it rather than with the controls.
            modifier = Modifier.padding(
                horizontal = MushafPageMargin + MushafEdgeGutter,
                vertical = MushafRuleTailAir,
            ),
        )
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
            // Faded to 5% while reciting — and untouchable with it. Alpha
            // alone left an invisible Chapters button under the thumb at the
            // fore-edge, which walked the reader out of the page mid-recitation.
            val secondaryEnabled = enabled && !reciting
            Box(Modifier.matchParentSize().graphicsLayer { alpha = secondaryFade }) {
                GutterIcon(
                    onClick = onOpenChapters,
                    enabled = secondaryEnabled,
                    image = Icons.AutoMirrored.Rounded.ArrowBack,
                    label = "Chapters",
                    tint = quiet,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                GutterIcon(
                    onClick = onOpenSettings,
                    enabled = secondaryEnabled,
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
                GutterIcon(
                    onClick = onRepeatClick,
                    enabled = secondaryEnabled,
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
                    modifier = Modifier.graphicsLayer { alpha = secondaryFade },
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
                            // The same condition the icon is drawn from: while
                            // another chapter plays this button shows Play, and
                            // used to be announced as Pause.
                            contentDescription = if (playerState.isPlaying && isThisSurahLoaded) {
                                "Pause"
                            } else {
                                "Play"
                            },
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
                Text(
                    text = "${if (playerState.speed % 1f == 0f) playerState.speed.toInt() else playerState.speed}×",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (playerState.speed == 1f) quiet else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .width(MushafGutterSlot)
                        .graphicsLayer { alpha = secondaryFade }
                        .then(
                            // Faded out of sight, and out of reach with it.
                            if (secondaryEnabled) {
                                Modifier.ownedQuietClickable(role = Role.Button, onClick = onSpeed)
                            } else {
                                Modifier
                            },
                        ),
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
    unit: Dp,
    glyphSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    // Type alone up here, and in ink rather than gold: gold is illumination —
    // ayah marks and the chapter's title — while the running head is a finding
    // aid. Gold also loses what little contrast it has on cream, which is why
    // this line used to disappear on paper.
    //
    // One label at each end, in the reader's own language: the chapter at the
    // spine, the juzʾ at the fore-edge. It carried the Arabic above the Latin
    // as well, which said the same thing twice and cost the leaf a whole line
    // of paper for the saying — paper the revelation now has instead.
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MushafEdgeGutter)
            .height(unit * MushafGrid.RUNNING_HEAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MushafHeadLabel(
            text = "Part $juz",
            ink = ink,
            align = TextAlign.Start,
            glyphSize = glyphSize,
            modifier = Modifier.weight(1f),
        )
        MushafHeadLabel(
            text = surahNameLatin.orEmpty(),
            ink = ink,
            align = TextAlign.End,
            glyphSize = glyphSize,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One end of the running head: a single line of wayfinding. */
@Composable
private fun MushafHeadLabel(
    text: String,
    ink: Color,
    align: TextAlign,
    glyphSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    if (text.isEmpty()) {
        Box(modifier)
        return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = glyphSize * MushafType.RATIO.pow(MushafType.HEAD),
            letterSpacing = 0.10.em,
        ),
        color = ink.copy(alpha = 0.44f),
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Folio: the mushaf's own Arabic-Indic figure, with the Western numeral
 * as a faint letterspaced gloss beside it — the same pairing the scroll
 * layout's page-break hairline uses, minus the dot that made it a label.
 */
@Composable
internal fun MushafPageFolio(
    page: Int,
    unit: Dp,
    glyphSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    // In ink, like the running head: at gold-on-cream the figure measured
    // 1.5:1 against the paper and simply vanished.
    //
    // The two figures are set well apart and hung on the leaf's centre line —
    // which is the play button's line too — so the folio reads as a pair of
    // marks either side of the spine rather than one clump of digits.
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(unit * MushafGrid.FOLIO),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$page",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = glyphSize * MushafType.RATIO.pow(MushafType.GLOSS),
                letterSpacing = 0.14.em,
            ),
            color = ink.copy(alpha = 0.50f),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(MushafFolioColumn),
        )
        Box(
            Modifier.width(MushafFolioSpread),
            contentAlignment = Alignment.Center,
        ) {
            // A lozenge between the two figures: the mark a compositor sets
            // between a pair, so the folio reads as one thing rather than two
            // numbers that happen to share a line.
            Canvas(Modifier.size(MushafFolioDiamond)) {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                drawPath(
                    Path().apply {
                        moveTo(c.x, c.y - r)
                        lineTo(c.x + r * 0.62f, c.y)
                        lineTo(c.x, c.y + r)
                        lineTo(c.x - r * 0.62f, c.y)
                        close()
                    },
                    color = ink.copy(alpha = 0.34f),
                )
            }
        }
        Text(
            text = page.toArabicIndic(),
            fontFamily = HafsFontFamily,
            fontSize = glyphSize * MushafType.RATIO.pow(MushafType.FURNITURE),
            color = ink.copy(alpha = 0.54f),
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.width(MushafFolioColumn),
        )
    }
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
