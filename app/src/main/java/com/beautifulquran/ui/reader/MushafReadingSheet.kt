package com.beautifulquran.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.beautifulquran.data.PageNumberScript
import com.beautifulquran.domain.MUSHAF_LINE_PITCH_EM
import kotlin.math.roundToInt
import com.beautifulquran.domain.MushafGrid
import com.beautifulquran.domain.mushafLeafBands
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
 * Folio band, with the figure centred in it.
 *
 * The page number belongs to the leaf, so it must sit nearer the last line of
 * revelation than the rule below it — proximity is what assigns it. Measured
 * against the transport instead, it drifted every time the chrome changed:
 * when the progress rule arrived between them it left the folio 120px under
 * the text and 64px over the rule, reading as part of the controls.
 *
 * And it is fixed, which is the one figure on this page deliberately *off* the
 * leaf's grid. It was `leafUnit * MushafGrid.FOLIO` for a while, and that is a
 * loop: the leaf's height is what is left after this band, and this band was a
 * fraction of the leaf's height. The first pass measures with the unit still
 * zero, so the leaf comes out a band too tall — and that is the size the ruler
 * paginates the whole book against ([EnglishLeafRuler]). The leaf then settles
 * a band shorter and holds a line less than it was given text for, which reads
 * as a leaf stopping a few words short of its own last line, on every leaf in
 * the book. The figure inside the band is still set from the leaf's hand,
 * because that is type and belongs on the grid; the paper it stands on is
 * furniture of the frame and does not.
 */
internal val MushafFolioBand = 30.dp
/** Paper between the rule and the transport it divides the leaf from. */
private val MushafRuleTailAir = 0.dp

/**
 * Paper between the leaf's last line and the hairline. The rule sat at the
 * top of its own band — flush under the leaf, with all its air below — and
 * read as shifted up. This sets it off the leaf so the line sits between
 * the page and the transport instead of hanging off the page.
 */
// The air between the leaf's last line and the dial's rule. The folio now
// stands in it — it used to cost the leaf three quarters of a line to stand on
// the paper — so what is left is the paper under the figure rather than under
// the text.
private val MushafDialHeadAir = 8.dp

/** Each folio figure's column, equal either side of the centre line. */
private val MushafFolioColumn = 40.dp
/** Paper between the two figures. */
private val MushafFolioSpread = 28.dp
/** The lozenge set between them. */
private val MushafFolioDiamond = 5.dp

/**
 * Paper outside the mark gutter.
 *
 * The leaf's type is bound by its width, not its height — the well has room to
 * spare, and every pixel of measure is a pixel of type. So this is as narrow as
 * the fore-edge fade can be drawn over without reaching the mark gutter.
 */
internal val MushafPageMargin = 4.dp

/**
 * The transport row's fore-edge: where the hairline itself now ends. The rule
 * is drawn only as wide as the comb (the dial's own edge inset inside the
 * sheet's page gutter), and Back/Settings read as flush with it when their
 * *ink* starts at the line's end — a 20dp icon inside a 40dp touch target
 * carries 10dp of bearing each side, so the row pads to the line minus that.
 */
private val MushafTransportEdge =
    MushafPageMargin + MushafEdgeGutter + MushafDialEdgeInset - 10.dp

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
    /** The leaf in view, 1-based. A lambda, so turning a page redraws the
     * dial rather than recomposing the reader that hosts this sheet. */
    pageAt: () -> Int,
    /** Leaves in the book — 604, once the catalog is up. */
    pageCount: Int,
    /** One cell per surah, in order 1..114: equal on the comb even when two
     *  tiny surahs share a leaf, so Chapter 93's mark is not lost because it
     *  shares paper with 92. */
    chapterPages: IntArray,
    /** What the dial writes over its thumb for a leaf and selected chapter. */
    pageLabel: (page: Int, surahId: Int?) -> MushafDialLabel?,
    chapterLabel: (Int) -> MushafDialLabel? = { null },
    /** Where a scrub landed, once the hand comes off the rule. */
    onSeekPage: (Int) -> Unit,
    onSeekSurah: ((Int) -> Unit)? = null,
    /** Warms the leaf while the dial's hand is still deciding. */
    onWarmPage: (suspend (Int) -> Unit)? = null,
    /** Raised while a hand is physically on the rule, for the folio fade. */
    onScrubbing: (Boolean) -> Unit,
    /** Parks pager neighbours while a distant dial landing is entering. */
    onLanding: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Sits on the leaf's foot, above the dial and the play bar. */
    leafFooter: @Composable () -> Unit = {},
    /** Which hand the leaf is set in — the two divide their height differently. */
    english: Boolean = false,
    pageNumberScript: PageNumberScript = PageNumberScript.BOTH,
    /** The pager's own page, 0-based — the leaf the folio band is centred on. */
    pageIndex: () -> Int = { 0 },
    /** How far the leaf has been dragged, in leaves: the pager's offset. */
    pageOffset: () -> Float = { 0f },
    /** The figure printed on the leaf at an index — its folio. */
    folioAt: (index: Int) -> Int = { it + 1 },
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
    // The leaf's own pitch, kept so the folio below it can be set on the same
    // grid as the lines it numbers. The leaf is this Box, so its height is the
    // only place the figure can be read from.
    val leafUnit = remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    // The folio stands down while the dial is under the thumb: scrubbing, the
    // figure the dial itself is calling out does not need saying twice.
    val scrubbing = remember { mutableStateOf(false) }
    val folioInk by animateFloatAsState(
        targetValue = if (scrubbing.value) 0f else 1f,
        animationSpec = tween(InkEngine.tuning.recessMs, easing = FastOutSlowInEasing),
        label = "mushafFolioStandDown",
    )
    Column(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val unit = with(density) {
                mushafLeafBands(english).unitPx(constraints.maxHeight.toFloat()).toDp()
            }
            SideEffect { leafUnit.value = unit }
            content()
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            ) {
                leafFooter()
            }
        }
        // The folio, off the paper but not off the leaf.
        //
        // It used to be the leaf's last band, and the leaf paid for it twice
        // over — the figure and the tail above it — for a number that belongs
        // to the frame as much as to the page. Standing it in the dial's head
        // air costs the text nothing. But a page number is *printed on the
        // page*, and one that snaps to the new leaf after the turn instead of
        // travelling with it reads as a label on the frame rather than as the
        // leaf's own. So it travels: the band carries the leaf on either side
        // of this one and slides them by exactly the pager's offset, which is
        // the paper moving under the finger with nothing on the leaf paying for
        // it.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(MushafFolioBand)
                .clipToBounds()
                .graphicsLayer { alpha = folioInk },
        ) {
            val trackPx = constraints.maxWidth.toFloat()
            val glyph = with(density) { (leafUnit.value.toPx() / MUSHAF_LINE_PITCH_EM).toSp() }
            val centre = pageIndex()
            for (index in (centre - 1)..(centre + 1)) {
                if (index < 0 || index >= pageCount) continue
                MushafFolioMarks(
                    page = folioAt(index),
                    glyphSize = glyph,
                    script = pageNumberScript,
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset {
                            // Read here rather than in composition: a turn
                            // moves the figure without recomposing the sheet.
                            val away = index - centre - pageOffset()
                            val x = if (english) away else -away
                            IntOffset((x * trackPx).roundToInt(), 0)
                        }
                        .padding(horizontal = MushafPageMargin + MushafEdgeGutter)
                        .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true),
                )
            }
        }
        MushafPageDial(
            pageAt = pageAt,
            pageCount = pageCount,
            chapterPages = chapterPages,
            pageLabel = pageLabel,
            chapterLabel = chapterLabel,
            onSeekPage = onSeekPage,
            onSeekSurah = onSeekSurah,
            onWarmPage = onWarmPage,
            onScrubbing = { scrubbing.value = it; onScrubbing(it) },
            // The rule is the book's edge seen side-on, so it runs the way the
            // book turns: right to left for the mushaf, left to right for a
            // book of the translation.
            rightToLeft = !english,
            onLanding = onLanding,
            reciting = reciting,
            // Paper between the folio and the rule. The figure now stands in
            // this band rather than on the leaf, so the air above it is the
            // leaf's foot and the air below is the dial's own.
            modifier = Modifier.padding(
                start = MushafPageMargin + MushafEdgeGutter,
                end = MushafPageMargin + MushafEdgeGutter,
                top = MushafDialHeadAir,
                bottom = MushafRuleTailAir,
            ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MushafTransportEdge, vertical = 2.dp),
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
                Box(
                    modifier = Modifier
                        .width(MushafGutterSlot)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = secondaryFade }
                        .then(
                            // Faded out of sight, and out of reach with it.
                            if (secondaryEnabled) {
                                Modifier.ownedQuietClickable(role = Role.Button, onClick = onSpeed)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${if (playerState.speed % 1f == 0f) playerState.speed.toInt() else playerState.speed}×",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (playerState.speed == 1f) quiet else MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                }
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
    /**
     * The leaf's fore-edge — the same one the text block is set to. A running
     * head is furniture of the measure, not of the paper: standing it at its
     * own inset put it a finger's width outside the block it names.
     */
    foreEdge: Dp = MushafEdgeGutter,
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
            .padding(horizontal = foreEdge)
            .height(unit * MushafGrid.RUNNING_HEAD),
        // Hard against the top of the leaf. Centred, the label carried a strip
        // of air above it, and the leaf already begins below the status bar —
        // the phone's forehead is the margin, and buying a second one came out
        // of the text well.
        verticalAlignment = Alignment.Top,
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
 * Folio: quiet ink on the leaf's centre line. A single script is the
 * number itself; both scripts sit either side of a diamond. Never gold —
 * at 9 sp a gold folio vanishes on cream.
 */
@Composable
internal fun MushafPageFolio(
    page: Int,
    unit: Dp,
    glyphSize: TextUnit,
    script: PageNumberScript = PageNumberScript.BOTH,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(unit * MushafGrid.FOLIO),
        contentAlignment = Alignment.Center,
    ) {
        MushafFolioMarks(
            page = page,
            glyphSize = glyphSize,
            script = script,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true),
        )
    }
}

/**
 * The folio figures, without the leaf's band. Customize's miniature uses
 * this so the preview and the pager share one layout.
 */
@Composable
internal fun MushafFolioMarks(
    page: Int,
    glyphSize: TextUnit,
    script: PageNumberScript = PageNumberScript.BOTH,
    modifier: Modifier = Modifier,
) {
    // The pair sits on one baseline, not on one centre line. Two scripts at
    // two sizes have boxes of very different depth — Hafs carries an ascent
    // half again as tall as the Latin face's — so centring the boxes stood
    // the numerals a few pixels apart and the folio read as a typo.
    val ink = MaterialTheme.colorScheme.onBackground
    val folio = mushafFolioLayout(page, script)
    val westernStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = glyphSize * MushafType.RATIO.pow(MushafType.FOLIO_GLOSS),
        letterSpacing = 0.14.em,
    )
    val westernColor = ink.copy(alpha = 0.50f)
    val arabicSize = glyphSize * MushafType.RATIO.pow(MushafType.FOLIO_FIGURE)
    val arabicColor = ink.copy(alpha = 0.54f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folio.western != null) {
            Text(
                text = folio.western,
                style = westernStyle,
                color = westernColor,
                textAlign = if (folio.diamond) TextAlign.End else TextAlign.Center,
                maxLines = 1,
                modifier = if (folio.diamond) {
                    Modifier.width(MushafFolioColumn).alignByBaseline()
                } else {
                    Modifier.alignByBaseline()
                },
            )
        }
        if (folio.diamond) {
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
        }
        if (folio.arabic != null) {
            Text(
                text = folio.arabic,
                fontFamily = HafsFontFamily,
                fontSize = arabicSize,
                color = arabicColor,
                textAlign = if (folio.diamond) TextAlign.Start else TextAlign.Center,
                maxLines = 1,
                modifier = if (folio.diamond) {
                    Modifier.width(MushafFolioColumn).alignByBaseline()
                } else {
                    Modifier.alignByBaseline()
                },
            )
        }
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
