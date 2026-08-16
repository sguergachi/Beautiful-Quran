package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.domain.MUSHAF_LINE_EM
import com.beautifulquran.domain.MushafCatalog
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.BASMALAH_UTHMANI
import com.beautifulquran.domain.buildMushafQcfLine
import com.beautifulquran.domain.mushafFontPreloadPages
import com.beautifulquran.domain.mushafGridSlots
import com.beautifulquran.domain.mushafUniformFontPx
import com.beautifulquran.domain.mushafLineSlotPx
import com.beautifulquran.domain.surahOpensWithBasmalahPreface
import kotlin.math.abs
import com.beautifulquran.ui.theme.MushafFontFamily

/**
 * Ayah-mark overhang allowance at each end of a line.
 *
 * A circled mark's medallion inks about half its own width wider than its
 * advance, so a line fitted flush to the text block leaves that overhang
 * hanging outside it, where it is clipped and the number comes out sliced.
 * Reserve enough paper at both fore-edges for the medallion to sit whole.
 */
internal val MushafEdgeGutter = 12.dp

/**
 * A line is measured as one concatenated run but drawn one [Text] per word,
 * so per-word rounding can sum a hair wider. Size against a slightly narrower
 * page than the one that draws it — a full line then lands inside the box
 * instead of exactly on it.
 */
private val MushafFitSlack = 4.dp

/** How far a turning leaf dissolves in from each fore-edge. */
private val MushafForeEdgeFade = 76.dp

/**
 * Paper drawn back over both fore-edges while a leaf is in motion, so a page
 * dissolves into the margin as it turns instead of sliding off a hard edge.
 *
 * Only while it moves: the fade follows the pager's offset, so a settled leaf
 * carries none of it and the revelation is never dimmed at rest. The band is
 * the leaf's own margin, so even at full strength it washes paper, not text.
 */
private fun Modifier.mushafForeEdgeFade(
    paper: Color,
    offsetFraction: () -> Float,
): Modifier = drawWithContent {
    drawContent()
    val turning = (abs(offsetFraction()) * 3.4f).coerceIn(0f, 1f)
    if (turning <= 0.01f) return@drawWithContent
    // Deep enough to take the last words of a line with it: a leaf that only
    // faded its margin looked no different from one that slid off the edge.
    val band = MushafForeEdgeFade.toPx()
    val edge = paper.copy(alpha = turning)
    drawRect(
        brush = Brush.horizontalGradient(
            0f to edge,
            1f to Color.Transparent,
            startX = 0f,
            endX = band,
        ),
        size = Size(band, size.height),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to edge,
            startX = size.width - band,
            endX = size.width,
        ),
        topLeft = Offset(size.width - band, 0f),
        size = Size(band, size.height),
    )
}

/**
 * Virtualized 604-page mushaf. Only the settled page runs ink clocks;
 * neighbours paint static Hafs so a fling never starts 30+ wash loops.
 */
@Composable
internal fun MushafPager(
    catalog: MushafCatalog,
    content: SurahContent,
    surahsById: Map<Int, Surah>,
    pagerState: PagerState,
    activeWordState: State<ActiveWord?>,
    activeAyah: Int?,
    recitingActive: Boolean,
    isThisSurahPlaying: Boolean,
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
        snapshotFlow { activeWordState.value to isThisSurahPlaying }
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
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling && page != followPage) {
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
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        reverseLayout = true,
        key = { it },
        modifier = modifier
            .fillMaxSize()
            .mushafForeEdgeFade(paper) { pagerState.currentPageOffsetFraction },
    ) { pageIndex ->
        val page = catalog.page(pageIndex + 1)
        if (page == null) {
            Box(Modifier.fillMaxSize())
        } else {
            val settled by remember {
                derivedStateOf { pageIndex == pagerState.settledPage }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = MushafPageMargin),
            ) {
                MushafPageHeader(
                    surahNameArabic = surahsById[page.primarySurahId]?.nameArabic,
                    surahNameLatin = surahsById[page.primarySurahId]?.nameTransliteration,
                    juz = page.juz,
                )
                MushafPageSheet(
                    page = page,
                    content = content,
                    surahsById = surahsById,
                    liveInk = settled,
                    activeWordState = activeWordState,
                    activeAyah = activeAyah.takeIf { settled },
                    recitingActive = recitingActive && settled,
                    isThisSurahPlaying = isThisSurahPlaying && settled,
                    playbackSpeed = playbackSpeed,
                    fontScale = fontScale,
                    loadedSurahId = loadedSurahId,
                    flashWordPosition = flashWordPosition.takeIf { settled },
                    onWordClick = onWordClick,
                    onWordLongClick = onWordLongClick,
                    onAyahClick = onAyahClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = MushafTextGutter, bottom = MushafTailGutter),
                )
                MushafPageFolio(
                    page = page.page,
                    modifier = Modifier.padding(horizontal = MushafEdgeGutter),
                )
            }
        }
    }
}

@Composable
private fun MushafPageSheet(
    page: MushafPage,
    content: SurahContent,
    surahsById: Map<Int, Surah>,
    liveInk: Boolean,
    activeWordState: State<ActiveWord?>,
    activeAyah: Int?,
    recitingActive: Boolean,
    isThisSurahPlaying: Boolean,
    playbackSpeed: Float,
    fontScale: Float,
    loadedSurahId: Int,
    flashWordPosition: Int?,
    onWordClick: (MushafToken) -> Unit,
    onWordLongClick: (MushafToken) -> Unit,
    onAyahClick: (MushafToken) -> Unit,
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
    if (liveInk) {
        MushafPageInkClocks(
            ayahs = ayahsOnPage,
            activeWordState = activeWordState,
            activeAyah = activeAyah,
            recitingActive = recitingActive,
            isThisSurahPlaying = isThisSurahPlaying,
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
    var pageFont by remember(page.page) { mutableStateOf(MushafQcfFonts.cached(page.page)) }
    LaunchedEffect(page.page, context) {
        if (pageFont != null) return@LaunchedEffect
        pageFont = withContext(Dispatchers.Default) {
            MushafQcfFonts.family(context, page.page)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
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
            val fontPx = remember(availableH, availableW, fontScale, slotCount) {
                mushafUniformFontPx(
                    measureWidthPx = availableW,
                    wellHeightPx = availableH,
                    slots = mushafGridSlots(slotCount),
                    fontScale = fontScale,
                )
            }
            val fontSp = with(density) { fontPx.toSp() }
            val lineSlot = with(density) {
                mushafLineSlotPx(
                    pageHeightPx = availableH,
                    slots = mushafGridSlots(slotCount),
                    fontPx = fontPx,
                ).toDp()
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
                                    fontSize = fontSp * 0.80f,
                                    // Air above and below: the panel is a plate
                                    // set into the page, not another line of it.
                                    bandHeight = lineSlot * 0.86f,
                                )
                            }
                            if (surahOpensWithBasmalahPreface(start.surahId)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(lineSlot),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MushafBasmalahLine(fontSize = fontSp)
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
    activeAyah: Int?,
    recitingActive: Boolean,
    isThisSurahPlaying: Boolean,
    playbackSpeed: Float,
    flashWordPosition: Int?,
    packsState: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
) {
    ayahs.forEach { ayah ->
        key(ayah.surahId, ayah.number) {
            val activeWord by remember(ayah.number) {
                derivedStateOf { activeWordState.value?.takeIf { it.ayah == ayah.number } }
            }
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
private fun MushafBasmalahLine(fontSize: androidx.compose.ui.unit.TextUnit) {
    Text(
        text = BASMALAH_UTHMANI,
        fontFamily = MushafFontFamily,
        fontSize = fontSize * 0.78f,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}


