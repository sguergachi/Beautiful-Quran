package com.beautifulquran.ui.reader

import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import kotlinx.coroutines.delay
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beautifulquran.DevProfiling
import com.beautifulquran.data.PageNumberScript
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.domain.MUSHAF_LINE_EM
import com.beautifulquran.domain.MUSHAF_LINE_PITCH_EM
import com.beautifulquran.domain.MUSHAF_DISPLAY_LINES_PER_PAGE
import com.beautifulquran.domain.MUSHAF_WORD_GAP_EM
import com.beautifulquran.domain.mushafDisplayFontPx
import com.beautifulquran.domain.MushafCatalog
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.BASMALAH_UTHMANI
import com.beautifulquran.domain.buildMushafQcfLine
import com.beautifulquran.domain.mushafFontPreloadPages
import com.beautifulquran.domain.MushafGrid
import com.beautifulquran.domain.MushafType
import com.beautifulquran.domain.mushafGridSlots
import com.beautifulquran.domain.mushafIsOpeningLeaf
import com.beautifulquran.domain.mushafUniformFontPx
import com.beautifulquran.domain.mushafLineSlotPx
import com.beautifulquran.domain.qcfTrailingMark
import com.beautifulquran.domain.qcfWordGlyphs
import com.beautifulquran.domain.reflowMushafPage
import com.beautifulquran.domain.surahOpensWithBasmalahPreface
import kotlin.math.abs
import com.beautifulquran.ui.theme.MushafBasmalahFontFamily
import androidx.compose.foundation.Canvas
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.beautifulquran.R
import com.beautifulquran.ui.theme.MUSHAF_BASMALAH_INK_MID_EM
import com.beautifulquran.ui.theme.MUSHAF_BASMALAH_GLYPH
import com.beautifulquran.ui.theme.MUSHAF_BASMALAH_HAND_SCALE
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.snap
import com.beautifulquran.ui.theme.letterFadeIn
import com.beautifulquran.ui.theme.MushafFontFamily
import com.beautifulquran.ui.theme.quietClickable

/**
 * What the leaf needs to know about playback, in one place so it can be handed
 * over as a single [State] and read where it is used rather than where the page
 * is built — a play or a pause then never recomposes the pages themselves.
 */
@Immutable
internal data class MushafPlayback(
    val activeAyah: Int?,
    val reciting: Boolean,
    val playingHere: Boolean,
    /** The chapter's opening basmalah is the thing being recited. */
    val basmalahActive: Boolean = false,
    /** Undebounced player truth; page turns must stop on the first pause. */
    val isPlaying: Boolean = false,
    /** Media3's actual playlist ayah, before the fade-led ink frontier. */
    val playingAyah: Int? = null,
)

/** Stable equality key for the small set of voice changes follow reacts to. */
private data class MushafFollowMoment(
    val word: ActiveWord?,
    val activeAyah: Int?,
    val basmalahActive: Boolean,
    val playingHere: Boolean,
    val isPlaying: Boolean,
)

/**
 * Ayah-mark overhang allowance at each end of a line.
 *
 * A circled mark's medallion inks about half its own width wider than its
 * advance, so a line fitted flush to the text block leaves that overhang
 * hanging outside it, where it is clipped and the number comes out sliced.
 * Reserve enough paper at both fore-edges for the medallion to sit whole.
 */
internal val MushafEdgeGutter = 10.dp

/**
 * A line is measured as one concatenated run but drawn one [Text] per word,
 * so per-word rounding can sum a hair wider. Size against a slightly narrower
 * page than the one that draws it — a full line then lands inside the box
 * instead of exactly on it.
 */
private val MushafFitSlack = 2.dp

// The extra text row is bought from the leaf's furniture, not by squeezing
// sixteen rows into the old fifteen-row well. Together these still total the
// original 16.75-unit leaf: .30 head + .20 gutter + 16 text + .05 tail + .20 folio.
private const val MushafDisplayHeadGutter = 0.20f
private const val MushafDisplayTail = 0.05f
private const val MushafDisplayFolio = 0.20f


/**
 * How long a leaf takes to come up once its page face has landed.
 *
 * Long enough to be read as paper settling rather than as a frame that
 * happened to be missing: at 180ms, under an easing that front-loads the
 * alpha, nearly all of it landed inside the first four frames and the page
 * simply appeared.
 */
/**
 * The page turn playback makes on the reader's behalf.
 *
 * A turn under a hand is the hand's: the pager's own spring answers the fling
 * and lands as fast as the throw did. This one has no hand behind it — the
 * voice has simply reached the foot of the leaf — so it is paced instead of
 * flung: slower than a snap, and eased at both ends, so the paper starts and
 * stops the way a page being turned for you does rather than arriving all at
 * once in the middle of a word.
 */
private val MushafFollowTurnSpec = tween<Float>(
    durationMillis = 620,
    easing = FastOutSlowInEasing,
)

/**
 * How far ahead of the voice the leaf turns, in milliseconds.
 *
 * A page turned exactly when the first word of the next leaf is spoken is
 * always late: the reader is looking at the word being recited, the paper
 * starts moving only once the voice has already left the leaf, and the first
 * word of the new page is half-said by the time it arrives. A person turning
 * a page for someone else starts before the line runs out. So the turn is
 * begun inside the last word of the leaf instead, while there is still voice
 * on the page it is leaving.
 */
private const val MushafTurnLeadMs = 500L

/**
 * How long to wait, from the moment the leaf's last word begins, before
 * starting the turn: that word's own dwell at [speed], less the lead.
 *
 * Clamped at zero — a short final word simply turns at once, which is the
 * same thing the lead is asking for.
 */
internal fun mushafTurnLeadDelayMs(durationMs: Long, speed: Float): Long =
    ((durationMs / speed.coerceAtLeast(0.1f)).toLong() - MushafTurnLeadMs)
        .coerceAtLeast(0L)

/** A second page owns clocks only while the voice is crossing onto it. */
internal fun mushafUsesLiveInk(
    isSettled: Boolean,
    isVoicePage: Boolean,
    waitingForVoice: Boolean = false,
    pageHasActiveWord: Boolean = false,
): Boolean = isSettled || isVoicePage || waitingForVoice || pageHasActiveWord

internal enum class MushafInkPackKind { ACTIVE_WORD, UPCOMING, SEARCH_FLASH, STATIC }

/** Which clock pack an ayah owns on the page carrying the voice. */
internal fun mushafInkPackKind(
    pageOwnsVoice: Boolean,
    ayah: Int,
    activeWordAyah: Int?,
    frontierAyah: Int?,
    basmalahActive: Boolean,
    hasSearchFlash: Boolean,
    /** The frontier is selected, but its first word timing has not arrived. */
    frontierWaitingForFirstWord: Boolean = false,
    /**
     * Follow is turning onto this leaf (or just did) and the voice is
     * still on the previous one. The whole leaf waits under paper so the
     * wash can fill it; a hand-browsed leaf stays [STATIC].
     */
    waitingForVoice: Boolean = false,
    /**
     * This leaf is where [activeWordAyah] lives, even if Media3 has not
     * set [pageOwnsVoice] yet. A dial-then-tap seed puts the word here
     * while `playingHere` is still false; dropping Active at play-start
     * disposes the wash Animatable.
     */
    pageHasActiveWord: Boolean = false,
): MushafInkPackKind = when {
    pageOwnsVoice && basmalahActive -> MushafInkPackKind.UPCOMING
    // Own the wash wherever the word is, not only once Media3 or the
    // waiting cover says so. pageOwnsVoice lags the seek; waitingForVoice
    // is released when play starts — both used to swap this pack to
    // STATIC and kill the letter fade mid-run.
    (pageOwnsVoice || waitingForVoice || pageHasActiveWord) &&
        activeWordAyah == ayah ->
        MushafInkPackKind.ACTIVE_WORD
    hasSearchFlash -> MushafInkPackKind.SEARCH_FLASH
    (pageOwnsVoice || pageHasActiveWord) &&
        (frontierAyah == null || ayah > frontierAyah ||
            ayah == frontierAyah && frontierWaitingForFirstWord) ->
        MushafInkPackKind.UPCOMING
    waitingForVoice && (activeWordAyah == null || ayah > activeWordAyah) ->
        MushafInkPackKind.UPCOMING
    else -> MushafInkPackKind.STATIC
}

/** A timing backtrack at a physical page tail must not turn the leaf forward. */
internal fun mushafTailTurnAllowed(
    nextTimingPage: Int?,
    followingPage: Int,
    isFinalAyah: Boolean,
): Boolean = !isFinalAyah &&
    (nextTimingPage == null || nextTimingPage == followingPage)

/**
 * Neighbour leaves stay composed for a warm turn, but they must not own the
 * tap. A hit that leaks onto the previous or next page plays that page's
 * verse with no wash on the leaf the reader is looking at.
 */
internal fun mushafLeafAcceptsTap(pageIndex: Int, currentPage: Int): Boolean =
    pageIndex == currentPage

/**
 * A tap pins the leaf until the seek's word arrives on it. Auto-follow —
 * including the last-word lead turn — must not steal that leaf while the
 * poll still names the word from before the seek.
 *
 * [heldPage] and [voicePage] are 1-based. A null voice has not arrived.
 */
internal fun mushafHoldBlocksFollow(heldPage: Int?, voicePage: Int?): Boolean =
    heldPage != null && voicePage != heldPage

/**
 * The leaf follow is turning onto, or the leaf a tap just pinned, waits
 * under paper so the wash has something to fill. A hand-browsed neighbour
 * stays fully readable.
 *
 * [pageNumber], [waitingPage] and [heldPage] are 1-based; 0 / null means none.
 */
internal fun mushafLeafWaitingForVoice(
    pageNumber: Int,
    waitingPage: Int,
    heldPage: Int?,
): Boolean = pageNumber == waitingPage || heldPage == pageNumber

/**
 * The 33 ms poll has named the waiting leaf. A tap seed must not count:
 * treating it as arrival drops the cover and disposes the wash mid-run.
 */
internal fun mushafWaitingLeafReleased(
    waitingPage: Int,
    voicePage: Int,
    fromTap: Boolean,
): Boolean = waitingPage != 0 && voicePage == waitingPage && !fromTap

/** Delayed turns retain ownership only while the exact activation survives. */
internal fun mushafSameActivation(expected: ActiveWord, current: ActiveWord?): Boolean =
    current != null &&
        expected.ayah == current.ayah &&
        expected.wordPosition == current.wordPosition &&
        expected.startMs == current.startMs &&
        expected.activation == current.activation

/**
 * A last-word lead turn puts the paper on [waitingPage] while the voice is
 * still on the previous leaf. Catch-up that rewinds to the spoken leaf is the
 * bounce: next, previous, next again. Hold until the voice arrives.
 *
 * [voicePage], [visiblePage] and [waitingPage] are 1-based leaf numbers;
 * [waitingPage] is 0 when no lead turn is in flight.
 */
internal fun mushafLeadTurnHoldsPager(
    voicePage: Int,
    visiblePage: Int,
    waitingPage: Int,
): Boolean = waitingPage != 0 &&
    visiblePage == waitingPage &&
    voicePage == waitingPage - 1

/**
 * Follow may pull the paper only while the leaf in view is the one it last
 * aimed at. A swipe lands on a different leaf; catching up to the voice from
 * there rewinds the turn the hand just made.
 */
internal fun mushafFollowOwnsVisiblePage(
    currentPageIndex: Int,
    followPage: Int,
): Boolean = currentPageIndex == followPage

/**
 * The playing leaf sits to the left (later pages) or the right (earlier
 * pages) of the leaf under the reader. The mushaf pager is reversed: page
 * one is on the right, so a higher page number is a turn to the left.
 */
internal enum class MushafReturnWay { Left, Right }

internal fun mushafReturnWay(currentPage: Int, playbackPage: Int): MushafReturnWay? =
    when {
        playbackPage > currentPage -> MushafReturnWay.Left
        playbackPage < currentPage -> MushafReturnWay.Right
        else -> null
    }

private const val MushafLeafFadeMs = 220

/**
 * How long a leaf waits for its own face before showing itself in the Hafs
 * stand-in. Long enough for a warm cache hit or a cold read off the asset,
 * short enough that a reader never sits looking at blank paper.
 */
private const val MushafLeafFaceWaitMs = 450L

/**
 * How long the pager waits before holding a neighbour composed either side.
 *
 * Opening or closing a chapter turns the paper stack under an animating
 * transform, and Compose keeps the rect index of everything beneath such a
 * transform current by walking it — profiled during open/close, that walk is
 * the reader's hottest work by a wide margin, and every node the neighbours
 * add is another step of it. So the neighbours wait until the entrance is
 * over; a turn taken inside that window simply composes its leaf on demand.
 */
private const val MushafNeighbourHoldDelayMs = 520L

/**
 * The page's own hand, as the chrome sees it: the size a line of revelation is
 * set in on this leaf, which anchors the whole type scale (see [MushafType]).
 * Read from the grid rather than measured, so the running head and folio can
 * be sized before a page font has even loaded.
 */
@Composable
private fun leafGlyphSize(unit: Dp): TextUnit = with(LocalDensity.current) {
    (unit.toPx() / MUSHAF_LINE_PITCH_EM).toSp()
}

/**
 * How far the leaf feathers in from each fore-edge: exactly the paper outside
 * the text measure — the margin and the mark gutter, and not a hair more. The
 * edge of a page may dissolve; the revelation may not.
 */
private val MushafForeEdgeFade
    @Composable get() = MushafPageMargin + MushafEdgeGutter

/**
 * Both fore-edges feathered into the paper, always.
 *
 * A leaf in a bound book does not end at a cut line: it turns away from the
 * eye. So the edges are opaque paper at the very margin and feather inward
 * over [MushafForeEdgeFade] — always, not only while a page moves. Tying it to
 * the pager's offset meant the effect appeared halfway through a swipe and
 * vanished again, which reads as a glitch rather than as the shape of a book;
 * and the band is the leaf's own margin, so at rest it washes paper, never
 * text.
 */
private fun Modifier.mushafForeEdgeFade(paper: Color, band: Dp): Modifier = drawWithContent {
    drawContent()
    val bandPx = band.toPx()
    for (side in 0..1) {
        val fromLeft = side == 0
        drawRect(
            brush = Brush.horizontalGradient(
                // Solid at the very edge, gone by the time the measure begins.
                colorStops = if (fromLeft) {
                    arrayOf(0f to paper, 0.45f to paper.copy(alpha = 0.70f), 1f to Color.Transparent)
                } else {
                    arrayOf(0f to Color.Transparent, 0.55f to paper.copy(alpha = 0.70f), 1f to paper)
                },
                startX = if (fromLeft) 0f else size.width - bandPx,
                endX = if (fromLeft) bandPx else size.width,
            ),
            topLeft = Offset(if (fromLeft) 0f else size.width - bandPx, 0f),
            size = Size(bandPx, size.height),
        )
    }
}

/**
 * Virtualized 604-page mushaf. The settled page runs ink clocks, joined only
 * by the voice's page during a turn; other neighbours paint static Hafs so a
 * fling never starts 30+ wash loops.
 */
@Composable
internal fun MushafPager(
    catalog: MushafCatalog,
    content: SurahContent,
    /** The chapter-opening basmalah's reveal, 0..1, or null when it is idle. */
    basmalahWash: StateFlow<Float?>,
    surahsById: Map<Int, Surah>,
    pagerState: PagerState,
    activeWordState: State<ActiveWord?>,
    /**
     * Playback, deferred. Passed as one [State] rather than three values so a
     * play or a pause does not recompose every leaf in the pager — only the ink
     * clocks, which is where the swap of packs actually belongs.
     */
    playback: State<MushafPlayback>,
    playbackSpeed: Float,
    followEnabled: Boolean,
    loadedSurahId: Int,
    /**
     * The leaf the reader just tapped on, held against playback follow until
     * the clock catches up (or the hold lapses). Without it the poll's word
     * from before the seek turns the page back and forward again under the
     * finger.
     */
    heldPage: Int?,
    /** The leaf the pointer actually hit — not a word-to-page lookup. */
    onTappedLeaf: (Int) -> Unit,
    flashAyah: Int?,
    flashWordPosition: Int?,
    /**
     * True while the page dial is under a hand. The folio and the dial's label
     * both name a leaf, one the reader is on and one they are heading for, and
     * they sit in the same band — so the folio gives the band up for as long
     * as the scrub lasts rather than arguing with it.
     */
    scrubbing: () -> Boolean,
    onUserTurnedPage: () -> Unit,
    onWordClick: (MushafToken) -> Unit,
    onWordLongClick: (MushafToken) -> Unit,
    onAyahClick: (MushafToken) -> Unit,
    onBasmalahClick: (Int) -> Unit,
    pageNumberScript: PageNumberScript = PageNumberScript.BOTH,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var followPage by remember {
        mutableIntStateOf(pagerState.currentPage)
    }
    // 1-based leaf follow is turning onto, 0 if none. Set before the
    // paper moves so that leaf is already under Upcoming paper.
    var waitingPage by remember { mutableIntStateOf(0) }
    // Read inside the follow effect, which must not restart when the reader
    // changes speed mid-recitation.
    val speedNow = rememberUpdatedState(playbackSpeed)
    val heldPageNow = rememberUpdatedState(heldPage)
    // heldPage is read, not keyed: keying it restarted this collector on
    // every tap and cancelled in-flight work. The hold is checked per tick.
    LaunchedEffect(followEnabled, loadedSurahId, catalog, pagerState) {
        snapshotFlow {
            val voice = playback.value
            MushafFollowMoment(
                word = activeWordState.value,
                activeAyah = voice.activeAyah,
                basmalahActive = voice.basmalahActive,
                playingHere = voice.playingHere,
                isPlaying = voice.isPlaying,
            )
        }
            .collect { moment ->
                if (!followEnabled || !moment.playingHere || !moment.isPlaying) return@collect
                val word = moment.word
                // The active word carries no surah of its own. Right after a
                // tap that loaded another chapter, the word still belongs to
                // the outgoing one — following it would turn the leaf out from
                // under the reader, to whatever page that verse number happens
                // to fall on in the new surah. Wait for the player to arrive.
                val focusAyah = word?.ayah ?: moment.activeAyah
                val page = when {
                    moment.basmalahActive -> catalog.firstPageOf(loadedSurahId)
                    word != null -> catalog.pageOf(
                        loadedSurahId,
                        word.ayah,
                        word.wordPosition,
                    )
                    focusAyah != null -> catalog.pageOf(loadedSurahId, focusAyah, 1)
                    else -> return@collect
                }
                // Still hearing the word from before the tap, or sitting on the
                // leaf's last word: stay put. The hold exists so the seek can
                // land and the wash can run where the reader tapped — following
                // the stale clock, or leading the next leaf, both leave it.
                if (mushafWaitingLeafReleased(waitingPage, page, word?.fromTap == true)) {
                    waitingPage = 0
                }
                if (mushafHoldBlocksFollow(heldPageNow.value, page)) return@collect
                // A hand on the pager owns the turn: while a scroll is in
                // progress — the user's swipe or a turn this collector just
                // started — do not pull. Pulling mid-swipe yanked the page
                // back under the finger, a 100-350ms hitch on every word
                // tick; the next tick after the scroll settles re-aims if
                // the voice is still elsewhere.
                if (pagerState.isScrollInProgress) return@collect
                // Lead-turn already put this leaf on the paper. The voice is
                // still on the last word of the previous one — that is the
                // point of the lead — so catching up to it would rewind.
                if (mushafLeadTurnHoldsPager(
                        voicePage = page,
                        visiblePage = pagerState.currentPage + 1,
                        waitingPage = waitingPage,
                    )
                ) {
                    return@collect
                }
                val index = (page - 1).coerceIn(0, catalog.pageCount - 1)
                if (pagerState.currentPage != index) {
                    if (!mushafFollowOwnsVisiblePage(pagerState.currentPage, followPage)) {
                        return@collect
                    }
                    // Warm the target leaf's face before the turn. A leaf
                    // composing without a resident face holds blank for its
                    // face wait and then fades in — on a playback turn that
                    // read as the whole screen flashing out and back.
                    DevProfiling.mark("followTurnStart p${index + 1}")
                    withContext(Dispatchers.Default) {
                        MushafQcfFonts.face(context, index + 1)
                    }
                    val currentVoice = playback.value
                    if (!currentVoice.playingHere || !currentVoice.isPlaying) return@collect
                    if (word != null && !mushafSameActivation(word, activeWordState.value)) {
                        return@collect
                    }
                    if (word == null) {
                        if (currentVoice.basmalahActive != moment.basmalahActive) {
                            return@collect
                        }
                        if (!moment.basmalahActive &&
                            currentVoice.activeAyah != moment.activeAyah
                        ) {
                            return@collect
                        }
                    }
                    if (pagerState.isScrollInProgress) return@collect
                    followPage = index
                    pagerState.animateScrollToPage(
                        index,
                        animationSpec = MushafFollowTurnSpec,
                    )
                    DevProfiling.mark("followTurnEnd p${index + 1}")
                    return@collect
                }
                // The voice is still on this leaf. If it is on the last word
                // of it, the turn is started inside that word rather than
                // after it (see [MushafTurnLeadMs]); the next leaf's own word
                // then arrives to a page that is already there, and this
                // collector finds nothing left to do.
                if (word == null) return@collect
                val next = index + 1
                if (next > catalog.pageCount - 1) return@collect
                val tail = catalog.page(page)
                    ?.lines?.lastOrNull { it.tokens.isNotEmpty() }
                    ?.tokens?.lastOrNull()
                    ?: return@collect
                if (tail.surahId != loadedSurahId ||
                    tail.ayah != word.ayah ||
                    tail.word.position != word.wordPosition
                ) {
                    return@collect
                }
                val nextTimingPage = word.nextWordPosition?.let { nextPosition ->
                    catalog.pageOf(loadedSurahId, word.ayah, nextPosition) - 1
                }
                if (!mushafTailTurnAllowed(
                        nextTimingPage = nextTimingPage,
                        followingPage = next,
                        isFinalAyah = word.ayah >= content.surah.ayahCount,
                    )
                ) {
                    return@collect
                }
                // Cover the incoming leaf before the paper moves, so the
                // turn reveals Upcoming paper rather than a finished page.
                waitingPage = next + 1
                delay(mushafTurnLeadDelayMs(word.durationMs, speedNow.value))
                // Paused, seeked, or turned by hand while the word was still
                // being said: the leaf under the reader is no longer this
                // collector's to move.
                val currentVoice = playback.value
                if (!currentVoice.playingHere || !currentVoice.isPlaying ||
                    !mushafSameActivation(word, activeWordState.value)
                ) {
                    waitingPage = 0
                    return@collect
                }
                if (pagerState.currentPage != index || pagerState.isScrollInProgress) {
                    waitingPage = 0
                    return@collect
                }
                followPage = next
                pagerState.animateScrollToPage(
                    next,
                    animationSpec = MushafFollowTurnSpec,
                )
            }
    }
    // The reader is opened at a chapter's own page by a scroll issued from
    // ReaderScreen, which lands before anyone has touched the leaf. Seeded at
    // page zero, that arrival looked exactly like a reader turning the page:
    // follow was switched off and the return-to-root pill armed away before a
    // single frame of playback, for every chapter that does not begin on page
    // one. The first settled page is therefore where following starts, not a
    // turn away from it.
    var followSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        var wasScrolling = false
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                // Swipe boundaries as named marks: they land in logcat and in
                // the system trace, so a captured frame log can be read
                // against them.
                if (scrolling && !wasScrolling) {
                    DevProfiling.mark("swipeBegin p${page + 1}")
                } else if (!scrolling && wasScrolling) {
                    DevProfiling.mark("swipeEnd p${page + 1}")
                }
                wasScrolling = scrolling
                if (scrolling) return@collect
                if (!followSeeded) {
                    followSeeded = true
                    followPage = page
                    return@collect
                }
                if (page != followPage) {
                    followPage = page
                    waitingPage = 0
                    onUserTurnedPage()
                }
            }
    }
    LaunchedEffect(pagerState, context, catalog.pageCount) {
        snapshotFlow { pagerState.settledPage }
            .collect { settled ->
                val pages = mushafFontPreloadPages(settled, catalog.pageCount)
                withContext(Dispatchers.Default) {
                    MushafQcfFonts.preload(context, pages)
                }
            }
    }
    LaunchedEffect(followEnabled) {
        if (!followEnabled) waitingPage = 0
    }
    val currentPageNow = rememberUpdatedState(pagerState.currentPage)
    val onWordClickNow = rememberUpdatedState(onWordClick)
    val onWordLongClickNow = rememberUpdatedState(onWordLongClick)
    val onAyahClickNow = rememberUpdatedState(onAyahClick)
    val onBasmalahClickNow = rememberUpdatedState(onBasmalahClick)
    val onTappedLeafNow = rememberUpdatedState(onTappedLeaf)
    val paper = MaterialTheme.colorScheme.background
    // Opening a chapter should cost one leaf, not three. Holding a neighbour
    // composed either side is what makes a page turn smooth, but doing it
    // before the first frame means ~450 word nodes are composed, measured and
    // indexed while the reader is still coming up — profiled as the reader's
    // worst frames by a wide margin, most of the time inside Compose's own
    // spatial index walking the tree. The neighbours are composed one frame
    // later instead: the open is a single leaf, and the turn is still warm.
    var holdNeighbours by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(MushafNeighbourHoldDelayMs)
        holdNeighbours = true
    }
    val voicePage = remember(catalog, loadedSurahId) {
        derivedStateOf {
            val voice = playback.value
            if (!voice.playingHere) return@derivedStateOf null
            val word = activeWordState.value
            when {
                voice.basmalahActive -> catalog.firstPageOf(loadedSurahId)
                word != null -> catalog.pageOf(
                    loadedSurahId,
                    word.ayah,
                    word.wordPosition,
                )
                voice.activeAyah != null -> catalog.pageOf(
                    loadedSurahId,
                    voice.activeAyah,
                    1,
                )
                else -> null
            }
        }
    }
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = if (holdNeighbours) 1 else 0,
        reverseLayout = true,
        key = { it },
        modifier = modifier
            .fillMaxSize()
            .mushafForeEdgeFade(paper, MushafForeEdgeFade),
    ) { pageIndex ->
        val page = catalog.page(pageIndex + 1)
        if (page == null) {
            Box(Modifier.fillMaxSize())
        } else {
            val settled by remember {
                derivedStateOf { pageIndex == pagerState.settledPage }
            }
            val pageOwnsVoice by remember(pageIndex) {
                derivedStateOf { voicePage.value == pageIndex + 1 }
            }
            val pageHasActiveWord by remember(pageIndex, catalog, loadedSurahId) {
                derivedStateOf {
                    val word = activeWordState.value ?: return@derivedStateOf false
                    catalog.pageOf(
                        loadedSurahId,
                        word.ayah,
                        word.wordPosition,
                    ) == pageIndex + 1
                }
            }
            val waitingForVoice by remember(pageIndex, heldPage) {
                derivedStateOf {
                    mushafLeafWaitingForVoice(
                        pageNumber = pageIndex + 1,
                        waitingPage = waitingPage,
                        heldPage = heldPage,
                    )
                }
            }
            val liveInk by remember(pageIndex) {
                derivedStateOf {
                    mushafUsesLiveInk(
                        settled,
                        pageOwnsVoice,
                        waitingForVoice,
                        pageHasActiveWord,
                    )
                }
            }
            val leafWordClick = remember(pageIndex, page.page) {
                { token: MushafToken ->
                    if (mushafLeafAcceptsTap(pageIndex, currentPageNow.value)) {
                        waitingPage = page.page
                        onTappedLeafNow.value(page.page)
                        onWordClickNow.value(token)
                    }
                }
            }
            val leafWordLongClick = remember(pageIndex) {
                { token: MushafToken ->
                    if (mushafLeafAcceptsTap(pageIndex, currentPageNow.value)) {
                        onWordLongClickNow.value(token)
                    }
                }
            }
            val leafAyahClick = remember(pageIndex, page.page) {
                { token: MushafToken ->
                    if (mushafLeafAcceptsTap(pageIndex, currentPageNow.value)) {
                        waitingPage = page.page
                        onTappedLeafNow.value(page.page)
                        onAyahClickNow.value(token)
                    }
                }
            }
            val leafBasmalahClick = remember(pageIndex, page.page) {
                { surahId: Int ->
                    if (mushafLeafAcceptsTap(pageIndex, currentPageNow.value)) {
                        waitingPage = page.page
                        onTappedLeafNow.value(page.page)
                        onBasmalahClickNow.value(surahId)
                    }
                }
            }
            // One description for the leaf, not ~450 word nodes: the QCF
            // glyphs are private-use artwork — meaningless to a screen reader
            // — and an active accessibility service re-sorted and re-geometried
            // every one of them per frame of a swipe, which was the mushaf's
            // swipe lag. Taps are pointer-based and unaffected.
            val leafSurah = surahsById[page.primarySurahId]?.nameTransliteration
            val leafDescription = remember(page, leafSurah) {
                buildString {
                    append("Mushaf page ")
                    append(page.page)
                    if (leafSurah != null) {
                        append(", ")
                        append(leafSurah)
                    }
                    append(", Juz ")
                    append(page.juz)
                    page.lines.forEach { line ->
                        if (line.tokens.isNotEmpty()) append(". ")
                        line.tokens.forEach { token ->
                            append(token.word.arabic)
                            append(' ')
                        }
                    }
                }.trimEnd()
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {
                        contentDescription = leafDescription
                    }
                    // Each leaf gets a surface of its own, so turning the page
                    // moves something already recorded instead of drawing it
                    // again. Without this the pager's offset dirtied the leaf's
                    // display list on every frame of a swipe and all ~150 of its
                    // word nodes were re-recorded: measured, that was the whole
                    // of the hitch — 99th percentile 101ms against 38 with it,
                    // and half as many frames blamed on the UI thread.
                    .graphicsLayer { }
                    .clipToBounds()
                    .padding(horizontal = MushafPageMargin),
            ) {
                val density = LocalDensity.current
                // One unit for the whole leaf — see MushafGrid. Every band below is
                // a whole number of them, so the head, the well, the tail and the
                // folio all sit on the same rhythm as the lines of revelation.
                val unit = with(density) {
                    MushafGrid.unitPx(constraints.maxHeight.toFloat()).toDp()
                }
                Column(Modifier.fillMaxSize()) {
                    MushafPageHeader(
                        surahNameArabic = surahsById[page.primarySurahId]?.nameArabic,
                        surahNameLatin = surahsById[page.primarySurahId]?.nameTransliteration,
                        juz = page.juz,
                        unit = unit,
                        glyphSize = leafGlyphSize(unit),
                    )
                    Spacer(Modifier.height(unit * MushafDisplayHeadGutter))
                    MushafPageSheet(
                        basmalahWash = basmalahWash,
                        page = page,
                        content = content,
                        surahsById = surahsById,
                        liveInk = liveInk,
                        pageOwnsVoice = pageOwnsVoice,
                        waitingForVoice = waitingForVoice,
                        pageHasActiveWord = pageHasActiveWord,
                        activeWordState = activeWordState,
                        playback = playback,
                        playbackSpeed = playbackSpeed,
                        flashAyah = flashAyah.takeIf { settled },
                        flashWordPosition = flashWordPosition.takeIf { settled },
                        onWordClick = leafWordClick,
                        onWordLongClick = leafWordLongClick,
                        onAyahClick = leafAyahClick,
                        onBasmalahClick = leafBasmalahClick,
                        unit = unit,
                        modifier = Modifier
                            .height(unit * MUSHAF_DISPLAY_LINES_PER_PAGE)
                            .fillMaxWidth(),
                    )
                    Spacer(Modifier.height(unit * MushafDisplayTail))
                    val folioInk by animateFloatAsState(
                        targetValue = if (scrubbing()) 0f else 1f,
                        animationSpec = tween(
                            InkEngine.tuning.recessMs,
                            easing = FastOutSlowInEasing,
                        ),
                        label = "mushafFolioStandDown",
                    )
                    MushafPageFolio(
                        page = page.page,
                        unit = unit,
                        glyphSize = leafGlyphSize(unit),
                        script = pageNumberScript,
                        modifier = Modifier
                            .height(unit * MushafDisplayFolio)
                            .padding(horizontal = MushafEdgeGutter)
                            .graphicsLayer { alpha = folioInk },
                    )
                }
            }
        }
    }
}

@Composable
private fun MushafPageSheet(
    page: MushafPage,
    content: SurahContent,
    basmalahWash: StateFlow<Float?>,
    surahsById: Map<Int, Surah>,
    liveInk: Boolean,
    pageOwnsVoice: Boolean,
    waitingForVoice: Boolean,
    pageHasActiveWord: Boolean,
    activeWordState: State<ActiveWord?>,
    playback: State<MushafPlayback>,
    playbackSpeed: Float,
    flashAyah: Int?,
    flashWordPosition: Int?,
    onWordClick: (MushafToken) -> Unit,
    onWordLongClick: (MushafToken) -> Unit,
    onAyahClick: (MushafToken) -> Unit,
    onBasmalahClick: (Int) -> Unit,
    unit: Dp,
    modifier: Modifier = Modifier,
) {
    val ayahsOnPage = remember(page.page, content.surah.id, content.ayahs) {
        page.ayahKeys.mapNotNull { (surahId, ayah) ->
            if (surahId != content.surah.id) return@mapNotNull null
            content.ayahs.firstOrNull { it.number == ayah }
        }
    }
    // The other chapter on a shared leaf. Juz 30 puts three of them on one
    // page; Fatihah and Baqarah share the second. Only the chapter the reader
    // opened is loaded, so these verses have no text of ours to clock — but
    // they are on the paper, and a word with no pack at all was answering at
    // full ink, which on a leaf being recited is the one thing full ink means:
    // already read. The neighbouring chapter sat there looking finished.
    //
    // They get a recess pack keyed the same way, driven by nothing but where
    // they stand relative to the chapter the voice is in. The mushaf runs in
    // chapter order, so a lower id on this leaf is behind the reciter and
    // keeps its ink, and a higher one is still to come and waits with the rest
    // of what is ahead.
    val upcomingOnPage = remember(page.page, content.surah.id) {
        page.ayahKeys.filter { (surahId, _) -> surahId > content.surah.id }
    }
    val recitedOnPage = remember(page.page, content.surah.id) {
        page.ayahKeys.filter { (surahId, _) -> surahId < content.surah.id }
    }
    // A snapshot map, not a state holding an immutable one: the old form
    // rebuilt the whole map once per ayah on every composition (fifteen copies
    // a page) and invalidated every reader each time. Writes are per key now,
    // and the draw side still registers its read so a pack swap repaints.
    val packsState = remember { mutableStateMapOf<Pair<Int, Int>, AyahInkPack>() }
    // A pack outlives the clocks that fed it. When a leaf's ayahs change — a
    // tap that loads the chapter on the other side of a page boundary — the
    // old ayah's animations leave composition and their values freeze wherever
    // they stood. The entry left behind still answers for those words, so they
    // kept whatever dim they were last given instead of falling back to full
    // ink. Drop what the leaf no longer carries.
    LaunchedEffect(ayahsOnPage, upcomingOnPage, recitedOnPage) {
        val live = ayahsOnPage.mapTo(HashSet()) { it.surahId to it.number }
        live.addAll(upcomingOnPage)
        live.addAll(recitedOnPage)
        packsState.keys.retainAll(live)
    }
    if (liveInk) {
        MushafPageInkClocks(
            ayahs = ayahsOnPage,
            upcoming = upcomingOnPage,
            recited = recitedOnPage,
            activeWordState = activeWordState,
            playback = playback,
            pageOwnsVoice = pageOwnsVoice,
            waitingForVoice = waitingForVoice,
            pageHasActiveWord = pageHasActiveWord,
            playbackSpeed = playbackSpeed,
            flashAyah = flashAyah,
            flashWordPosition = flashWordPosition,
            packsState = packsState,
        )
    }
    val context = LocalContext.current
    // Only what is already resident may be read here: building a page face is
    // Typeface.createFromAsset over a multi-megabyte font, and composition runs
    // on the UI thread. A miss draws the Hafs stand-in for a frame while the
    // effect below loads the real one — which is precisely what that fallback
    // path is for. (It used to load inline on a miss, which cost a frame the
    // first time a leaf was opened, and every time after the cache window had
    // moved on from it.)
    val residentFace = remember(page.page) { MushafQcfFonts.cached(page.page) }
    var pageFace by remember(page.page) { mutableStateOf(residentFace) }
    LaunchedEffect(page.page, context) {
        if (pageFace != null) {
            DevProfiling.mark("leafFaceCached p${page.page}")
            return@LaunchedEffect
        }
        DevProfiling.mark("leafFaceLoadStart p${page.page}")
        pageFace = withContext(Dispatchers.Default) {
            MushafQcfFonts.face(context, page.page)
        }
        DevProfiling.mark("leafFaceLoaded p${page.page}")
    }
    // A leaf is set in the Hafs stand-in until its own page face arrives, and
    // the two are not the same width, so the first frames of a newly opened
    // page reflow under the reader's eye — the type jumps as the real face
    // lands. Hold the leaf back until its face is in hand and bring it up on a
    // short fade: paper settling into the light, rather than type jumping.
    // The wait is capped, so a page whose face never loads still shows itself
    // in the stand-in rather than staying blank.
    // Every leaf begins at nothing and is brought up, whether or not its face
    // had to be fetched: a cached face otherwise meant a chapter snapped into
    // existence while an uncached one faded, so the same act looked like two
    // different things depending on what had been read before.
    var faceOverdue by remember(page.page) { mutableStateOf(false) }
    LaunchedEffect(page.page) {
        delay(MushafLeafFaceWaitMs)
        faceOverdue = true
    }
    val pageFont = pageFace?.family
    val pageTypeface = pageFace?.typeface
    val displayPage = remember(page, pageTypeface) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = pageTypeface
            textSize = 100f
        }
        reflowMushafPage(page) { token ->
            val qcf = token.word.qcfV2
            val word = if (qcf.isEmpty()) {
                token.word.arabic
            } else {
                qcfWordGlyphs(qcf, token.endsAyah)
            }
            val mark = if (qcf.isEmpty()) "" else qcfTrailingMark(qcf, token.endsAyah)
            paint.measureText(word) + paint.measureText(mark) + MUSHAF_WORD_GAP_EM * paint.textSize
        }
    }
    val leafReady = pageFace != null || faceOverdue
    // A face already resident cannot reflow, so there is nothing for this fade
    // to hide and the leaf starts fully inked. The chapter's own entrance is
    // the reader's (see ReaderEntranceFadeMs); running a second fade inside it
    // only made the text arrive later than the page it is written on.
    val leafFade = remember(page.page) { Animatable(if (residentFace != null) 1f else 0f) }
    // Settled is its own flag so the fade itself can be read in the draw phase:
    // reading an Animatable in composition would recompose the whole leaf on
    // every frame of its own entrance.
    var leafSettled by remember(page.page) { mutableStateOf(residentFace != null) }
    LaunchedEffect(page.page, leafReady) {
        if (leafSettled || !leafReady) return@LaunchedEffect
        DevProfiling.mark("leafFadeStart p${page.page}")
        leafFade.animateTo(
            targetValue = 1f,
            // Eased at both ends: a fade that starts fast is the one that
            // reads as a snap, however long it is nominally given.
            animationSpec = tween(MushafLeafFadeMs, easing = FastOutSlowInEasing),
        )
        leafSettled = true
        DevProfiling.mark("leafFadeDone p${page.page}")
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Only while it is actually fading. A graphicsLayer marks the node
            // transformed, and Compose keeps a transformed node's rect index
            // current by walking its whole subhierarchy — on a leaf that is
            // every word on the page (see [MushafHafsLine]).
            .then(
                if (leafSettled) {
                    Modifier
                } else {
                    Modifier.graphicsLayer { alpha = leafFade.value }
                },
            ),
    ) {
            val density = LocalDensity.current
            // The only inset left inside the text block: enough for a circled
            // ayah mark's overhang at the line end, and nothing more. It is an
            // inset of the *measure* — taking it off the height as well made
            // the leading a fortieth shorter than MUSHAF_LINE_INK_EM claims and
            // charged the type for the difference. The height gives up nothing
            // but rounding slack, so fifteen slots still land inside the well.
            val edgeGutterPx = with(density) { MushafEdgeGutter.toPx() }
            val fitSlackPx = with(density) { MushafFitSlack.toPx() }
            val fitInsetPx = edgeGutterPx + fitSlackPx
            val availableH = (constraints.maxHeight.toFloat() - fitSlackPx)
                .coerceAtLeast(1f)
            val availableW = (constraints.maxWidth.toFloat() - fitInsetPx * 2)
                .coerceAtLeast(1f)
            // Every chapter opening costs the grid a line for its title band,
            // and another for the basmalah under it where the chapter takes one.
            val fitSlotCount = (page.lines.size + page.surahStarts.size +
                page.surahStarts.count { surahOpensWithBasmalahPreface(it.surahId) })
                .coerceAtLeast(1)
            val displaySlotCount = (displayPage.lines.size + displayPage.surahStarts.size +
                displayPage.surahStarts.count { surahOpensWithBasmalahPreface(it.surahId) })
                .coerceAtLeast(1)
            // One size for the whole book: the measure, not this page's own
            // longest line. Fitting each leaf to itself made the hand grow and
            // shrink as the pages turned.
            val unitPx = with(density) { unit.toPx() }
            val fontPx = remember(unitPx, availableW, fitSlotCount) {
                mushafDisplayFontPx(
                    mushafUniformFontPx(
                        measureWidthPx = availableW,
                        // The well is the grid's fifteen units, whatever the page
                        // holds: a leaf carrying a chapter's opening asks for more
                        // slots than that and packs them into the same paper. Both
                        // arguments used to be scaled by the slot count, which
                        // cancelled and left the guard unable to bite — a chapter
                        // opening kept type cut for a full slot while its slot had
                        // shrunk to about 0.88 of one.
                        wellHeightPx = unitPx * MushafGrid.TEXT_LINES,
                        slots = mushafGridSlots(fitSlotCount),
                    ),
                )
            }
            val fontSp = with(density) { fontPx.toSp() }
            // Every line on the leaf is set to this one measure — the leaf
            // inside its gutters. Computed once here rather than by a
            // BoxWithConstraints per line (see [MushafHafsLine]).
            val lineMeasurePx = (constraints.maxWidth.toFloat() - edgeGutterPx * 2)
                .coerceAtLeast(1f)
            // One slot is one unit of the leaf's grid, whatever the page holds.
            val lineSlot = with(density) {
                (availableH / mushafGridSlots(displaySlotCount)).toDp()
            }
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = MushafEdgeGutter),
                    // Ordinary leaves start at the head, even when the last
                    // lines of a chapter leave the foot empty. The two framed
                    // opening pages are a medallion: the block sits in the
                    // middle of the well, the way the print centres them.
                    verticalArrangement = if (mushafIsOpeningLeaf(page.page)) {
                        Arrangement.Center
                    } else {
                        Arrangement.Top
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    displayPage.lines.forEachIndexed { index, line ->
                        displayPage.surahStarts.firstOrNull { it.beforeLineIndex == index }?.let { start ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(lineSlot),
                                contentAlignment = Alignment.Center,
                            ) {
                                MushafSurahTitleBand(
                                    surah = surahsById[start.surahId],
                                    fontSize = fontSp * 1.08f,
                                    // Air above and below: the panel is a plate
                                    // set into the page, not another line of it.
                                    bandHeight = lineSlot * 0.94f,
                                )
                            }
                            if (surahOpensWithBasmalahPreface(start.surahId)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(lineSlot),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MushafBasmalahLine(
                                        fontSize = fontSp,
                                        slotHeight = lineSlot,
                                        active = pageOwnsVoice &&
                                            start.surahId == content.surah.id &&
                                            playback.value.basmalahActive,
                                        wash = basmalahWash,
                                        onClick = { onBasmalahClick(start.surahId) },
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(lineSlot),
                            contentAlignment = Alignment.Center,
                        ) {
                            MushafHafsLine(
                                line = line,
                                page = page.page,
                                packs = packsState,
                                fontSize = fontSp,
                                measureWidthPx = lineMeasurePx,
                                pageTypeface = pageTypeface,
                                liveInk = liveInk,
                                onWordClick = onWordClick,
                                onWordLongClick = onWordLongClick,
                                onAyahClick = onAyahClick,
                                pageFont = pageFont,
                            )
                        }
                    }
                }
            }
        }
}

@Composable
private fun MushafPageInkClocks(
    ayahs: List<Ayah>,
    /** Verses of a chapter this leaf shares but has not loaded, standing after
     * the loaded one: they wait with everything else still to come. */
    upcoming: List<Pair<Int, Int>>,
    /** The same, standing before it: the reader has passed them, so they keep
     * their ink like any verse already read. */
    recited: List<Pair<Int, Int>>,
    activeWordState: State<ActiveWord?>,
    playback: State<MushafPlayback>,
    pageOwnsVoice: Boolean,
    waitingForVoice: Boolean,
    pageHasActiveWord: Boolean,
    playbackSpeed: Float,
    flashAyah: Int?,
    flashWordPosition: Int?,
    packsState: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
) {
    val activeWordAyah by remember {
        derivedStateOf { activeWordState.value?.ayah }
    }
    val voice = playback.value
    val frontierAyah = activeWordAyah ?: voice.activeAyah
    var playingAyahHasWord by remember(voice.playingAyah) { mutableStateOf(false) }
    if (!playingAyahHasWord && activeWordAyah != null && activeWordAyah == voice.playingAyah) {
        SideEffect { playingAyahHasWord = true }
    }
    // Media3 advances the playlist item before the 33 ms word poll reaches
    // that item's first timing. Keep its frontier under Upcoming paper during
    // that silence. Once a word has appeared, a later null is the audio tail
    // and the completed ayah must retain full ink instead of dimming again.
    val frontierWaitingForFirstWord = activeWordAyah == null &&
        (frontierAyah != voice.playingAyah || !playingAyahHasWord)
    ayahs.forEach { ayah ->
        key(ayah.surahId, ayah.number) {
            val activeWord by remember(ayah.number) {
                derivedStateOf { activeWordState.value?.takeIf { it.ayah == ayah.number } }
            }
            val recitingActive = voice.reciting
            val flashHere = flashAyah == ayah.number && flashWordPosition != null
            val pack = when (mushafInkPackKind(
                pageOwnsVoice = pageOwnsVoice,
                ayah = ayah.number,
                activeWordAyah = activeWordAyah,
                frontierAyah = frontierAyah,
                basmalahActive = voice.basmalahActive,
                hasSearchFlash = flashHere,
                frontierWaitingForFirstWord = frontierWaitingForFirstWord,
                waitingForVoice = waitingForVoice,
                pageHasActiveWord = pageHasActiveWord,
            )) {
                MushafInkPackKind.ACTIVE_WORD -> rememberAyahInkPack(
                    ayah = ayah,
                    activeWord = activeWord,
                    playbackSpeed = playbackSpeed,
                    isActiveAyah = true,
                    dimmed = false,
                    flashWordPosition = flashWordPosition?.takeIf { flashHere },
                    // Debounced on purpose: a repeat range looping back dips
                    // out of "playing" for a frame, and the ink is not dry
                    // between two laps of the same verse.
                    wetInk = recitingActive,
                    initiallyRecessed = true,
                )
                MushafInkPackKind.UPCOMING -> rememberMushafRecessPack(dimmed = true)
                MushafInkPackKind.SEARCH_FLASH -> rememberAyahInkPack(
                    ayah = ayah,
                    activeWord = null,
                    playbackSpeed = playbackSpeed,
                    isActiveAyah = false,
                    dimmed = false,
                    flashWordPosition = flashWordPosition,
                    wetInk = false,
                )
                MushafInkPackKind.STATIC -> rememberMushafRecessPack(dimmed = false)
            }
            SideEffect {
                // Write only on real change: a same-value write to the
                // snapshot map still notifies every word reading its pack,
                // and each of those recompositions re-diffed the word's wash
                // modifiers - profiled as setDetachedListener churn on every
                // page settle.
                val key = ayah.surahId to ayah.number
                if (packsState[key] !== pack) packsState[key] = pack
            }
        }
    }
    // No text of theirs is loaded, so there is nothing to clock word by word.
    // A later chapter on the voice's leaf still waits under paper; a chapter
    // already passed and every manually browsed leaf remain full scripture.
    upcoming.forEach { key ->
        key(key.first, key.second) {
            val pack = rememberMushafRecessPack(
                dimmed = pageOwnsVoice || waitingForVoice || pageHasActiveWord,
            )
            SideEffect {
                if (packsState[key] !== pack) packsState[key] = pack
            }
        }
    }
    recited.forEach { key ->
        key(key.first, key.second) {
            val pack = rememberMushafRecessPack(dimmed = false)
            SideEffect {
                if (packsState[key] !== pack) packsState[key] = pack
            }
        }
    }
}

@Composable
private fun rememberMushafRecessPack(dimmed: Boolean): AyahInkPack {
    val recessCover = animateFloatAsState(
        targetValue = if (dimmed) {
            1f - InkEngine.State.Upcoming.inkAlpha()
        } else {
            0f
        },
        animationSpec = tween(InkEngine.tuning.recessMs, easing = FastOutSlowInEasing),
        label = "mushafRecessOnly",
    )
    val markAlpha = rememberAyahMarkAlpha(focused = !dimmed)
    val idleRepeat = remember {
        RepeatWash(
            progress = mutableStateOf(1f),
            alpha = mutableStateOf(0f),
            feather = mutableStateOf(null),
        )
    }
    return AyahInkPack(
        motions = emptyList(),
        recessCover = recessCover,
        markAlpha = markAlpha,
        searchHitWash = idleRepeat,
        wholeAyahRecess = dimmed,
    )
}

@Composable
private fun MushafBasmalahLine(
    fontSize: TextUnit,
    slotHeight: Dp,
    active: Boolean,
    wash: StateFlow<Float?>,
    onClick: () -> Unit,
) {
    // Written in the page's own hand: the leaf's type size, scaled by what the
    // header face needs to ink as tall as a word of the verse beneath it, and
    // placed by its own ink rather than by its line box — the box is nearly two
    // ems tall around ink that fills two thirds of one, so letting a Text centre
    // it dropped the phrase onto the first verse.
    val density = LocalDensity.current
    val ink = MaterialTheme.colorScheme.onBackground
    val context = LocalContext.current
    val face = remember(context) { ResourcesCompat.getFont(context, R.font.qcf2_bsml) }
    val fontPx = with(density) { fontSize.toPx() } * MUSHAF_BASMALAH_HAND_SCALE
    val paint = remember(face, fontPx, ink) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = fontPx
            color = ink.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    // The basmalah is recited before the chapter's first verse, so it takes the
    // same ink as any other line: washed letter by letter while it is being
    // read, then retained like scripture already passed. The wash masks the
    // glyph's own drawing, so it follows the phrase's own contour.
    val inkState = InkEngine.prefaceState(isActive = active, dimmed = false)
    val lyricInk by animateFloatAsState(
        targetValue = inkState.inkAlpha(),
        animationSpec = if (inkState == InkEngine.State.Active) {
            snap()
        } else {
            tween(InkEngine.tuning.inkFadeMs, easing = FastOutSlowInEasing)
        },
        label = "mushafBasmalahInk",
    )
    val washValue = wash.collectAsStateWithLifecycle()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(slotHeight)
            .quietClickable(onClick = onClick)
            .then(
                if (active) {
                    Modifier.letterFadeIn(
                        progress = { washValue.value?.coerceIn(0f, 1f) ?: 0f },
                        rtl = true,
                        restingAlpha = InkEngine.State.Upcoming.inkAlpha(),
                        feather = InkEngine.prefaceFeather(),
                    )
                } else {
                    Modifier.graphicsLayer { alpha = lyricInk }
                },
            ),
    ) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(
                MUSHAF_BASMALAH_GLYPH,
                size.width / 2f,
                size.height / 2f + MUSHAF_BASMALAH_INK_MID_EM * fontPx,
                paint,
            )
        }
    }
}
