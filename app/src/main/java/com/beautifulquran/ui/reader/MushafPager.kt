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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        reverseLayout = true,
        key = { it },
        modifier = modifier.fillMaxSize(),
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
                MushafPageFolio(page.page)
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
    val packsState = remember { mutableStateOf(emptyMap<Pair<Int, Int>, AyahInkPack>()) }
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
    var pageFont by remember(page.page) {
        mutableStateOf(
            MushafQcfFonts.cached(page.page)
                ?: if (liveInk) MushafQcfFonts.family(context, page.page) else null,
        )
    }
    LaunchedEffect(page.page, context) {
        if (MushafQcfFonts.cached(page.page) == null) {
            pageFont = withContext(Dispatchers.Default) {
                MushafQcfFonts.family(context, page.page)
            }
        } else {
            pageFont = MushafQcfFonts.cached(page.page)
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
                                    surahNameArabic = surahsById[start.surahId]?.nameArabic,
                                    fontSize = fontSp * 0.60f,
                                    bandHeight = lineSlot * 0.30f,
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
    packsState: MutableState<Map<Pair<Int, Int>, AyahInkPack>>,
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
                rememberMushafRecessPack(dimmed = recitingActive)
            }
            SideEffect {
                packsState.value = packsState.value + ((ayah.surahId to ayah.number) to pack)
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


