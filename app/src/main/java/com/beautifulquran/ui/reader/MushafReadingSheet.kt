package com.beautifulquran.ui.reader

import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.quietClickable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.beautifulquran.data.PageNumberScript
import com.beautifulquran.domain.MushafGrid
import com.beautifulquran.domain.MushafType
import kotlin.math.pow
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.theme.ownedQuietClickable

internal val MushafGutterSlot = 48.dp
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
private val MushafTransportRow = 56.dp
private val MushafTransportAir = 2.dp
/** Chapters and Settings at the row's fore-edges. */
private val MushafEdgeControl = 40.dp
/** The narrowest a centre control may get before it stops being a target. */
private val MushafMinControl = 32.dp

/**
 * The reciter's name or Ink Lab below the transport.
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
    onDismissError: () -> Unit,
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
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(MushafTransportRow),
            ) {
            // The centre row shares the width with Chapters and Settings at the
            // edges, so its slots come from what is left between them. A fixed
            // compact width keyed on the screen let the edges cover Repeat and
            // Speed on ordinary 360dp phones.
            val controlSize = ((maxWidth - MushafEdgeControl * 2 - MushafTransportRow) / 4)
                .coerceIn(MushafMinControl, MushafGutterSlot)
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
                        .size(MushafEdgeControl)
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
                    buttonSize = controlSize,
                    iconSize = 22.dp,
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
                    buttonSize = controlSize,
                    iconSize = 24.dp,
                    tint = primary,
                )
                IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(MushafTransportRow)) {
                    if (playerState.isBuffering && isThisSurahLoaded) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
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
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                GutterIcon(
                    onClick = onFastForward,
                    enabled = enabled && isThisSurahLoaded,
                    image = Icons.Rounded.FastForward,
                    label = "Next",
                    buttonSize = controlSize,
                    iconSize = 24.dp,
                    tint = primary,
                )
                Box(
                    modifier = Modifier
                        .width(controlSize)
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
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        ),
                        color = if (speedActive) MaterialTheme.colorScheme.onBackground else quiet,
                        textAlign = TextAlign.Center,
                        // "0.75×" is wider than a narrow slot at 15sp, and at a
                        // large font scale wider than any. One line, set down
                        // until it fits, never wrapped under itself.
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 9.sp,
                            maxFontSize = 15.sp,
                            stepSize = 0.5.sp,
                        ),
                    )
                }
            }
            }
            // Opening Ink Lab grows the reserved name band; the leaf takes
            // the measured remainder. Closed, the band is exactly what
            // MushafBelowLeaf reserves for it: the English book is paginated
            // against that constant, and a name grown by the system font
            // scale would leave every leaf set for paper it does not have.
            val labOpen = inkLabAvailable && inkLabOpen && playerState.error == null
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (labOpen) {
                            Modifier.heightIn(min = MushafReciterBand)
                        } else {
                            Modifier.height(MushafReciterBand)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (labOpen) {
                    InkLabPanel(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(start = 40.dp),
                    )
                } else if (reciterName.isNotEmpty() || playerState.error != null) {
                    // An error stands in for the name and is never faded with
                    // it: a stream that fails mid-recitation can arrive while
                    // the reading still counts as reciting, and the notice is
                    // the only place the reader learns why the voice stopped.
                    val notice = playerState.error
                    ReciterNameButton(
                        name = reciterName,
                        notice = notice,
                        onDismissNotice = onDismissError,
                        onClick = onOpenSettings,
                        enabled = enabled && (!reciting || notice != null),
                        disclosure = true,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = if (inkLabAvailable && notice == null) 48.dp else 0.dp)
                            .graphicsLayer { alpha = if (notice != null) 1f else secondaryFade },
                    )
                }
                if (inkLabAvailable && playerState.error == null) {
                    InkLabToggleButton(
                        expanded = inkLabOpen,
                        onClick = onInkLabClick,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
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
    modifier: Modifier = Modifier,
) {
    // Type alone up here, and in ink rather than gold: gold is illumination —
    // ayah marks and the chapter's title — while the running head is a finding
    // aid. Gold also loses what little contrast it has on cream, which is why
    // this line used to disappear on paper.
    //
    // Both hands spend the former bottom folio band on the text instead.
    val ink = MaterialTheme.colorScheme.onBackground
    MushafRunningHead(
        page = page,
        pageNumberScript = pageNumberScript,
        chapter = surahNameLatin.orEmpty(),
        // The running head and the text share the same inset.
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MushafEdgeGutter)
            .height(unit * MushafGrid.RUNNING_HEAD),
        // Hard against the top of the leaf. Centred, the label carried a strip
        // of air above it, and the leaf already begins below the status bar —
        // the phone's forehead is the margin, and buying a second one came out
        // of the text well.
        verticalAlignment = Alignment.Top,
    ) { text, align, labelModifier ->
        MushafHeadLabel(
            text = text,
            ink = ink,
            align = align,
            glyphSize = glyphSize,
            modifier = labelModifier,
        )
    }
}

/**
 * The running head's arrangement, shared by the leaf and the Customize
 * miniature so the two cannot drift: with both scripts, Western at the left,
 * the chapter centred and Arabic-Indic at the right, on equal end columns; with
 * one, the figure at the left and the chapter at the right. [label] sets each
 * piece of text in its caller's own type.
 */
@Composable
internal fun MushafRunningHead(
    page: Int,
    pageNumberScript: PageNumberScript,
    chapter: String,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    label: @Composable (text: String, align: TextAlign, modifier: Modifier) -> Unit,
) {
    val folio = mushafFolioLayout(page, pageNumberScript)
    val both = folio.western != null && folio.arabic != null
    Row(modifier = modifier, verticalAlignment = verticalAlignment) {
        label(
            folio.western ?: requireNotNull(folio.arabic),
            TextAlign.Start,
            Modifier.weight(1f),
        )
        label(
            chapter,
            if (both) TextAlign.Center else TextAlign.End,
            Modifier.weight(if (both) 3f else 1f),
        )
        if (both) {
            label(folio.arabic.orEmpty(), TextAlign.End, Modifier.weight(1f))
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

/**
 * The running head's style for one piece of its text. Arabic-Indic figures are
 * the page's own numerals and are set in Hafs, untracked: in the Latin label
 * face they fell back to the system's digits with the head's Latin tracking
 * between them, and read as a different hand from the page they number.
 */
internal fun mushafHeadStyle(latin: TextStyle, text: String): TextStyle =
    if (text.any { it in '\u0660'..'\u0669' }) {
        latin.copy(fontFamily = HafsFontFamily, letterSpacing = TextUnit.Unspecified)
    } else {
        latin
    }

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
        style = mushafHeadStyle(
            MaterialTheme.typography.labelSmall.copy(
                fontSize = glyphSize.furnitureStep(MushafType.HEAD),
                letterSpacing = 0.10.em,
            ),
            text,
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


@Composable
private fun GutterIcon(
    onClick: () -> Unit,
    enabled: Boolean,
    image: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(buttonSize)) {
        Icon(image, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}
