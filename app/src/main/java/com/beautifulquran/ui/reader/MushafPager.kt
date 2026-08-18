package com.beautifulquran.ui.reader

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.domain.MUSHAF_LINE_EM
import com.beautifulquran.domain.MUSHAF_LINE_PITCH_EM
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
import com.beautifulquran.domain.mushafUniformFontPx
import com.beautifulquran.domain.mushafLineSlotPx
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


/**
 * How long a leaf takes to come up once its page face has landed.
 *
 * Long enough to be read as paper settling rather than as a frame that
 * happened to be missing: at 180ms, under an easing that front-loads the
 * alpha, nearly all of it landed inside the first four frames and the page
 * simply appeared.
 */
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
private fun leafGlyphSize(unit: Dp, fontScale: Float): TextUnit = with(LocalDensity.current) {
    (unit.toPx() / MUSHAF_LINE_PITCH_EM * fontScale.coerceIn(0.88f, 1.12f)).toSp()
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
 * Virtualized 604-page mushaf. Only the settled page runs ink clocks;
 * neighbours paint static Hafs so a fling never starts 30+ wash loops.
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
    fontScale: Float,
    followEnabled: Boolean,
    loadedSurahId: Int,
    /**
     * The leaf the reader just tapped on, held against playback follow until
     * the clock catches up (or the hold lapses). Without it the poll's word
     * from before the seek turns the page back and forward again under the
     * finger.
     */
    heldPage: Int?,
    flashWordPosition: Int?,
    onUserTurnedPage: () -> Unit,
    onWordClick: (MushafToken) -> Unit,
    onWordLongClick: (MushafToken) -> Unit,
    onAyahClick: (MushafToken) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var followPage by remember {
        mutableIntStateOf(pagerState.currentPage)
    }
    LaunchedEffect(followEnabled, loadedSurahId, catalog, pagerState, heldPage) {
        snapshotFlow { activeWordState.value to playback.value.playingHere }
            .collect { (word, playingHere) ->
                if (!followEnabled || word == null) return@collect
                // The active word carries no surah of its own. Right after a
                // tap that loaded another chapter, the word still belongs to
                // the outgoing one — following it would turn the leaf out from
                // under the reader, to whatever page that verse number happens
                // to fall on in the new surah. Wait for the player to arrive.
                if (!playingHere) return@collect
                val page = catalog.pageOf(loadedSurahId, word.ayah, word.wordPosition)
                // Still hearing the word from before the tap: stay on the leaf
                // the reader is looking at rather than turning to wherever that
                // word lives and straight back.
                if (heldPage != null && page != heldPage) return@collect
                val index = (page - 1).coerceIn(0, catalog.pageCount - 1)
                if (pagerState.currentPage != index) {
                    followPage = index
                    pagerState.animateScrollToPage(index)
                }
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
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (scrolling) return@collect
                if (!followSeeded) {
                    followSeeded = true
                    followPage = page
                    return@collect
                }
                if (page != followPage) {
                    followPage = page
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
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    // Each leaf gets a surface of its own, so turning the page
                    // moves something already recorded instead of drawing it
                    // again. Without this the pager's offset dirtied the leaf's
                    // display list on every frame of a swipe and all ~150 of its
                    // word nodes were re-recorded: measured, that was the whole
                    // of the hitch — 99th percentile 101ms against 38 with it,
                    // and half as many frames blamed on the UI thread.
                    .graphicsLayer { }
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
                    glyphSize = leafGlyphSize(unit, fontScale),
                )
                Spacer(Modifier.height(unit * MushafGrid.HEAD_GUTTER))
                MushafPageSheet(
                    basmalahWash = basmalahWash,
                    page = page,
                    content = content,
                    surahsById = surahsById,
                    liveInk = settled,
                    activeWordState = activeWordState,
                    playback = playback,
                    playbackSpeed = playbackSpeed,
                    fontScale = fontScale,
                    loadedSurahId = loadedSurahId,
                    flashWordPosition = flashWordPosition.takeIf { settled },
                    onWordClick = onWordClick,
                    onWordLongClick = onWordLongClick,
                    onAyahClick = onAyahClick,
                    unit = unit,
                    modifier = Modifier
                        .height(unit * MushafGrid.TEXT_LINES)
                        .fillMaxWidth(),
                )
                Spacer(Modifier.height(unit * MushafGrid.TAIL))
                MushafPageFolio(
                    page = page.page,
                    unit = unit,
                    glyphSize = leafGlyphSize(unit, fontScale),
                    modifier = Modifier.padding(horizontal = MushafEdgeGutter),
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
    activeWordState: State<ActiveWord?>,
    playback: State<MushafPlayback>,
    playbackSpeed: Float,
    fontScale: Float,
    loadedSurahId: Int,
    flashWordPosition: Int?,
    onWordClick: (MushafToken) -> Unit,
    onWordLongClick: (MushafToken) -> Unit,
    onAyahClick: (MushafToken) -> Unit,
    unit: Dp,
    modifier: Modifier = Modifier,
) {
    val ayahsOnPage = remember(page.page, content.surah.id, content.ayahs) {
        page.ayahKeys.mapNotNull { (surahId, ayah) ->
            if (surahId != content.surah.id) return@mapNotNull null
            content.ayahs.firstOrNull { it.number == ayah }
        }
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
    LaunchedEffect(ayahsOnPage) {
        val live = ayahsOnPage.mapTo(HashSet()) { it.surahId to it.number }
        packsState.keys.retainAll(live)
    }
    if (liveInk) {
        MushafPageInkClocks(
            ayahs = ayahsOnPage,
            activeWordState = activeWordState,
            playback = playback,
            playbackSpeed = playbackSpeed,
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
    var pageFont by remember(page.page) { mutableStateOf(residentFace) }
    LaunchedEffect(page.page, context) {
        if (pageFont != null) {
            DevProfiling.mark("leafFaceCached p${page.page}")
            return@LaunchedEffect
        }
        DevProfiling.mark("leafFaceLoadStart p${page.page}")
        pageFont = withContext(Dispatchers.Default) {
            MushafQcfFonts.family(context, page.page)
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
    val leafReady = pageFont != null || faceOverdue
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
            // ayah mark's overhang at the line end, and nothing more.
            val edgeGutterPx = with(density) { MushafEdgeGutter.toPx() }
            val fitInsetPx = with(density) { (MushafEdgeGutter + MushafFitSlack).toPx() }
            val availableH = (constraints.maxHeight.toFloat() - edgeGutterPx)
                .coerceAtLeast(1f)
            val availableW = (constraints.maxWidth.toFloat() - fitInsetPx * 2)
                .coerceAtLeast(1f)
            // Every chapter opening costs the grid a line for its title band,
            // and another for the basmalah under it where the chapter takes one.
            val slotCount = (page.lines.size + page.surahStarts.size +
                page.surahStarts.count { surahOpensWithBasmalahPreface(it.surahId) })
                .coerceAtLeast(1)
            // One size for the whole book: the measure, not this page's own
            // longest line. Fitting each leaf to itself made the hand grow and
            // shrink as the pages turned.
            val unitPx = with(density) { unit.toPx() }
            val fontPx = remember(unitPx, availableW, fontScale, slotCount) {
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
                    slots = mushafGridSlots(slotCount),
                    fontScale = fontScale,
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
                (availableH / mushafGridSlots(slotCount)).toDp()
            }
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = MushafEdgeGutter),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    page.lines.forEachIndexed { index, line ->
                        page.surahStarts.firstOrNull { it.beforeLineIndex == index }?.let { start ->
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
                                        active = playback.value.basmalahActive,
                                        dimmed = playback.value.reciting &&
                                            !playback.value.basmalahActive,
                                        wash = basmalahWash,
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
                                packs = packsState,
                                fontSize = fontSp,
                                measureWidthPx = lineMeasurePx,
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
    activeWordState: State<ActiveWord?>,
    playback: State<MushafPlayback>,
    playbackSpeed: Float,
    flashWordPosition: Int?,
    packsState: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
) {
    ayahs.forEach { ayah ->
        key(ayah.surahId, ayah.number) {
            val activeWord by remember(ayah.number) {
                derivedStateOf { activeWordState.value?.takeIf { it.ayah == ayah.number } }
            }
            val activeAyah = playback.value.activeAyah
            val recitingActive = playback.value.reciting
            val isThisSurahPlaying = playback.value.playingHere
            val policyActive = isThisSurahPlaying &&
                (activeWord != null || ayah.number == activeAyah)
            val pack = if (policyActive) {
                rememberAyahInkPack(
                    ayah = ayah,
                    activeWord = activeWord,
                    playbackSpeed = playbackSpeed,
                    isActiveAyah = true,
                    dimmed = false,
                    flashWordPosition = flashWordPosition?.takeIf { ayah.number == activeWord?.ayah },
                )
            } else {
                // A leaf is read once and filled in. A verse already recited
                // keeps its ink, so the page darkens line by line and the
                // reader can see how much of it is done; only what is still
                // to come waits in the recess. (The scroll layout recesses
                // both sides of the active verse, because there is no page
                // there to complete — just a river of text going by.)
                val recited = activeAyah != null && ayah.number < activeAyah
                rememberMushafRecessPack(dimmed = recitingActive && !recited)
            }
            SideEffect {
                packsState[ayah.surahId to ayah.number] = pack
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
    )
}

@Composable
private fun MushafBasmalahLine(
    fontSize: TextUnit,
    slotHeight: Dp,
    active: Boolean,
    dimmed: Boolean,
    wash: StateFlow<Float?>,
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
    // read, dimmed back while the reader is elsewhere in the chapter. The wash
    // masks the glyph's own drawing, so it follows the phrase's own contour.
    val inkState = InkEngine.prefaceState(isActive = active, dimmed = dimmed)
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


