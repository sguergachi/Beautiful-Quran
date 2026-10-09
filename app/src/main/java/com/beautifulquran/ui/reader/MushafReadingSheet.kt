package com.beautifulquran.ui.reader

import com.beautifulquran.ui.theme.quietClickable
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.beautifulquran.data.PageNumberScript
import com.beautifulquran.domain.MushafGrid
import com.beautifulquran.domain.MushafType
import kotlin.math.pow
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.ownedQuietClickable

internal val MushafGutterSlot = 44.dp
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
/**
 * Which way the book turns: the leaf, the rule under it and the folio between
 * them, from one answer.
 *
 * They are one thing seen three ways — the paper, its edge side-on, and the
 * number printed on it — so they cannot hold separate opinions. They did, twice
 * in one afternoon, and each time half a turn was worse than none.
 *
 * A mushaf is bound on the right and read right to left. A book of the
 * translation is set in English, read left to right and bound on the left, and
 * turning it the mushaf's way puts every chapter's evenly divided ending
 * *after* its opening instead of before it — so a reader met a half page each
 * time a chapter began, the pagination correct and the direction reading it
 * backwards. It is the same book either way; it is not the same object.
 *
 * The [english] handed here must be the one the *leaf* is set from, and one
 * value of it, not two definitions that agree until they do not. An English
 * book exists whenever the mushaf does — the pagination is built for both
 * settings — so "is there an English book" is not the question. "Which is this
 * leaf" is.
 *
 * Mirroring the rule with it is not one flip, which is what made this hard.
 * [mushafDialAlong] turns a book fraction into a place on the rule and every
 * mark, seat, trough and landing asks it — but two of them then *relax* their
 * results apart, walking the rule and pushing each neighbour clear of the last,
 * and those walks had the mushaf's direction built into them. Reversed they do
 * not mirror the comb: they force every seat past its neighbour and pile the
 * whole book into half the rule, which is what shipped the first time and what
 * the thumb cannot show you. Prove it on the comb.
 */
internal fun mushafTurnsRightToLeft(english: Boolean): Boolean = !english

private val MushafDialHeadAir = 8.dp

/** The transport's own row of controls, and the air around the block. */
private val MushafTransportRow = 44.dp
private val MushafTransportAir = 2.dp

/**
 * The reciter's name or Ink Lab above the transport.
 *
 * The closed band is always reserved. Opening Ink Lab replaces the name and
 * lets the band take the panel's measured height; the leaf gets what remains.
 */
private val MushafReciterBand = 48.dp

/**
 * Everything reserved under the English leaf while Ink Lab is closed.
 *
 * Both hands carry their folio in the running head. Every
 * term is constant, so the root can subtract it from the window to learn
 * the closed leaf's size before a leaf has ever been composed and paginate the
 * English book from that on the very first launch. Open Ink Lab adds measured
 * footer height live; its temporary leaf metrics are not remembered.
 *
 * **Anything permanently reserved under the leaf belongs in this sum.** A term
 * that drifts shows as a book paginated for a leaf a little larger than the
 * one it is drawn on, and its last lines come up short. The leaf reports what
 * it really measured and the book is set again if the two disagree.
 */
internal val MushafBelowLeaf: Dp =
    MushafDialHeadAir + MushafDialSlot + MushafDialBelowGrab + MushafRuleTailAir +
        MushafTransportAir * 2 + MushafTransportRow + MushafReciterBand

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
 * Book window: the leaf turns above; transport is a quiet line of ink on
 * the paper under the page. No frame — the paper runs to the edges and the
 * text block is the only thing composed on it. Chapters and settings live
 * down here at the fore-edges, with the book's other controls: the leaf
 * itself stays scripture and running head only.
 */
@Composable
internal fun MushafReadingSheet(
    reciterName: String,
    inkLabAvailable: Boolean = false,
    inkLabOpen: Boolean = false,
    onInkLabClick: () -> Unit = {},
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
    /** Which hand the leaf is set in, determining the dial's direction. */
    english: Boolean = false,
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
    // Default choosers recede during recitation. Repeat and speed stay visible
    // when they alter playback, so the listener can see and change that state.
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
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            ) {
                leafFooter()
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
            onScrubbing = onScrubbing,
            rightToLeft = mushafTurnsRightToLeft(english),
            onLanding = onLanding,
            reciting = reciting,
            // The dial's head air clears the leaf's last line.
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
                .padding(horizontal = MushafTransportEdge, vertical = MushafTransportAir),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Opening Ink Lab grows the reserved name band; the leaf takes
            // the measured remainder.
            Box(
                Modifier.fillMaxWidth().heightIn(min = MushafReciterBand),
                contentAlignment = Alignment.Center,
            ) {
                if (inkLabAvailable && inkLabOpen) {
                    InkLabPanel(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(start = 40.dp),
                    )
                } else if (reciterName.isNotEmpty()) {
                    ReciterNameButton(
                        name = reciterName,
                        notice = playerState.error,
                        onClick = onOpenSettings,
                        enabled = enabled && !reciting,
                        disclosure = true,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = if (inkLabAvailable) 48.dp else 0.dp)
                            .graphicsLayer { alpha = secondaryFade },
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(MushafTransportRow),
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
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp)
                        .quietClickable(
                            enabled = secondaryEnabled,
                            role = Role.Button,
                            onClick = onOpenSettings,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Settings",
                        tint = quiet,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .height(MushafTransportRow),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val repeatActive = playerState.repeatMode != Player.REPEAT_MODE_OFF || playerState.repeatRange != null
                val speedActive = playerState.speed != 1f
                val singleAyah = playerState.repeatRange?.let { it.first == it.last } == true
                GutterIcon(
                    onClick = onRepeatClick,
                    enabled = enabled && (!reciting || repeatActive),
                    image = if (playerState.repeatMode == Player.REPEAT_MODE_ONE || singleAyah) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    },
                    label = "Repeat",
                    tint = if (repeatActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        quiet
                    },
                    modifier = Modifier.graphicsLayer { alpha = if (repeatActive) 1f else secondaryFade },
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
                        .graphicsLayer { alpha = if (speedActive) 1f else secondaryFade }
                        .then(
                            // Faded out of sight, and out of reach with it.
                            if (enabled && (!reciting || speedActive)) {
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
                        color = if (speedActive) MaterialTheme.colorScheme.onBackground else quiet,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            }
        }
    }
}

/**
 * Both folio scripts flank a centred chapter; a single script sits at the
 * left. English numbers its own leaves in the same frame as Arabic.
 */
@Composable
internal fun MushafPageHeader(
    surahNameArabic: String?,
    surahNameLatin: String?,
    unit: Dp,
    glyphSize: TextUnit,
    page: Int,
    pageNumberScript: PageNumberScript = PageNumberScript.BOTH,
    /**
     * The shared running-head inset, independent of the prose's wider measure.
     */
    foreEdge: Dp = MushafEdgeGutter,
    modifier: Modifier = Modifier,
) {
    // Type alone up here, and in ink rather than gold: gold is illumination —
    // ayah marks and the chapter's title — while the running head is a finding
    // aid. Gold also loses what little contrast it has on cream, which is why
    // this line used to disappear on paper.
    //
    // Both hands spend the former bottom folio band on the text instead.
    val ink = MaterialTheme.colorScheme.onBackground
    val folio = mushafFolioLayout(page, pageNumberScript)
    val both = folio.western != null && folio.arabic != null
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
            text = folio.western ?: requireNotNull(folio.arabic),
            ink = ink,
            align = TextAlign.Start,
            glyphSize = glyphSize,
            modifier = Modifier.weight(1f),
        )
        MushafHeadLabel(
            text = surahNameLatin.orEmpty(),
            ink = ink,
            align = if (both) TextAlign.Center else TextAlign.End,
            glyphSize = glyphSize,
            modifier = Modifier.weight(if (both) 3f else 1f),
        )
        if (both) {
            MushafHeadLabel(
                text = folio.arabic.orEmpty(),
                ink = ink,
                align = TextAlign.End,
                glyphSize = glyphSize,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One end of the running head: a single line of wayfinding. */
/**
 * What the leaf's furniture takes over the scale, in sp.
 *
 * [MushafType] is a geometric scale — one ratio, one anchor — and a geometric
 * scale under-serves its own smallest rungs. That is not a flaw in the ratio,
 * it is why type families are cut in optical sizes at all: a face set small
 * needs to be relatively larger than the geometry says to hold its colour and
 * stay legible. On this leaf the two smallest rungs come out at about 10 sp for
 * the running head and the folio's Arabic figure and 8 sp for the Latin numeral
 * beside it, and at that size wayfinding is a squint.
 *
 * So the furniture takes a flat two points over its rung. Flat, and not a rung:
 * a rung is +25% and would scale with the device, so on a tablet it would be
 * five points where a phone got two — and the interval between the head, the
 * figure and the gloss is the thing the scale exists to keep. Adding the same
 * absolute correction to all three moves them together and leaves every
 * interval where it was.
 *
 * The Customize miniature takes it too, through the shared composables, and is
 * still faithful: it previews at 22 sp against the leaf's ~20 sp, so the two sit
 * on the same correction rather than on two different ones.
 */
private const val MushafFurnitureBump = 2f

/**
 * A furniture size: [steps] down the leaf's scale from the page's hand, plus
 * the optical correction. [this] must be an sp size — every caller's is.
 */
private fun TextUnit.furnitureStep(steps: Int): TextUnit =
    (value * MushafType.RATIO.pow(steps) + MushafFurnitureBump).sp

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
            fontSize = glyphSize.furnitureStep(MushafType.HEAD),
            letterSpacing = 0.10.em,
        ),
        color = ink.copy(alpha = 0.44f),
        textAlign = align,
        maxLines = 1,
        // Measured against the paper rather than against its band. The band is
        // 0.30 of a unit and the head's line box is taller than that, so a Row
        // of exactly the band's height handed the label a maxHeight it did not
        // fit in and sheared the descenders off flat — *Maryam* came out as
        // *Marvam*. The band still spends 0.30 of a unit of the grid, which is
        // what the grid is for; the glyphs are simply allowed to hang past it
        // into the head gutter, which is a whole unit of air with nothing in
        // it. Anything that puts ink in that gutter has to revisit this.
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.wrapContentHeight(
            align = Alignment.Top,
            unbounded = true,
        ),
    )
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
        fontSize = glyphSize.furnitureStep(MushafType.FOLIO_GLOSS),
        letterSpacing = 0.14.em,
    )
    val westernColor = ink.copy(alpha = 0.50f)
    val arabicSize = glyphSize.furnitureStep(MushafType.FOLIO_FIGURE)
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
