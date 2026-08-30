package com.beautifulquran.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.beautifulquran.R
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahWordSearchSection
import com.beautifulquran.data.model.WordSearchHit
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.data.HomeBookmarkStyle
import com.beautifulquran.domain.WORD_SEARCH_PREVIEW_LIMIT
import com.beautifulquran.domain.englishTranslationHighlightSpans
import com.beautifulquran.ui.reader.VerseBookmarkRibbon
import com.beautifulquran.ui.theme.AlphaTag
import com.beautifulquran.ui.theme.ArabicTitleStyle
import com.beautifulquran.ui.theme.GildedRosette
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.PaperSearchField
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.verticalFadingEdges

/** The search field leads the scrolling document beneath the fixed masthead. */
private const val SEARCH_ITEM_INDEX = 0

/** How far (px) the list must scroll past the lifted search before a scroll counts as "dismiss". */
private const val DISMISS_SCROLL_THRESHOLD_PX = 24

private val HomeRibbonLane = 28.dp
private val HomeRibbonWidth = 13.dp
private val HomeRibbonGutter = (HomeRibbonLane - HomeRibbonWidth) / 2f
private val HomeStartInset = HomeRibbonLane
private val HomeEndInset = 28.dp
private val HomeNumberColumn = 26.dp
private val HomeRowRibbonGutter = (HomeNumberColumn - HomeRibbonWidth) / 2f
private val HomeColumnGap = 4.dp
private val HomeArabicOpticalInset = 4.dp
private val TopBoundRibbonHeight = 96.dp

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    /** [wordPosition] is set when opening from a Quran-wide word hit so the
     *  reader can flash that word; null for surah / reference / continue. */
    onOpenSurah: (surahId: Int, ayah: Int?, wordPosition: Int?) -> Unit,
    onOpenSettings: () -> Unit,
    /** True while the paper stack is on (or near) the chapter list — drives
     *  the floating transport's enter/exit across page turns. */
    coverSheetVisible: Boolean = true,
    /** A reader turn has committed, so its parked place should be revealed on return. */
    readerVisitActive: Boolean = false,
    /** The returning chapter sheet is close enough to prepare its entering ribbon lane. */
    chapterRibbonReady: Boolean = true,
    /** Number of saved verses; zero removes the Home-page ribbon entirely. */
    bookmarkCount: Int = 0,
    bookmarkStyle: HomeBookmarkStyle = HomeBookmarkStyle.TOP_BOUND,
    onOpenBookmarks: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val floatingPlayback = uiState.floatingPlayback
    // Soft dissolve heights — contentPadding matches so the first/last
    // ink sits clear of the edge at rest; scrolling draws content under it.
    val listFadeTop = 24.dp
    val listFadeBottom = 48.dp
    // Measured height of the floating transport; falls back to the clearance
    // estimate until the first layout pass. Used as bottomInset so the soft
    // fade sits on the paper just above the bar, matching the reader.
    var floatingPlaybackHeight by remember { mutableStateOf(FloatingPlaybackListClearance) }

    // Focus + geometry that anchor the fading dials pane just under the search
    // field, floating over the surah list.
    var searchFocused by remember { mutableStateOf(false) }
    var boxTop by remember { mutableFloatStateOf(0f) }
    var boxHeight by remember { mutableFloatStateOf(0f) }
    var searchBottom by remember { mutableFloatStateOf(0f) }
    var searchBounds by remember { mutableStateOf<Rect?>(null) }
    var searchPaneBounds by remember { mutableStateOf<Rect?>(null) }
    val listState = rememberLazyListState()
    val searchPaneVisible = searchFocused && uiState.query.isEmpty()
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density).toFloat()
    val searchPaneTopGap = 10.dp
    val searchPaneBottomGap = 10.dp
    val searchPaneTopGapPx = with(density) { searchPaneTopGap.toPx() }
    val searchPaneBottomGapPx = with(density) { searchPaneBottomGap.toPx() }
    val searchPaneHeight = with(density) {
        val paneTop = searchBottom - boxTop + searchPaneTopGapPx
        val paneBottom = boxHeight - imeBottom - searchPaneBottomGapPx
        (paneBottom - paneTop).coerceAtLeast(0f).toDp()
    }
    val searching = uiState.query.isNotBlank()
    // Search mode: field focused or a query still on the sheet (results stay
    // after the keyboard dismisses). Float stays gone for the whole stretch.
    val searchActive = searchFocused || searching
    val showFloatingPlayback = shouldShowFloatingPlayback(
        nowPlayingPresent = floatingPlayback != null,
        coverSheetVisible = coverSheetVisible,
        searchActive = searchActive,
    )
    val listBottomInset = if (showFloatingPlayback) floatingPlaybackHeight else 0.dp
    val listBottomPadding = listFadeBottom + listBottomInset
    val showSurahMatches = searching && uiState.surahs.isNotEmpty()
    val showWordSections = searching &&
        (uiState.wordSections.isNotEmpty() || uiState.wordSearchLoading)
    val showEmpty = searching &&
        uiState.surahs.isEmpty() &&
        uiState.wordSections.isEmpty() &&
        !uiState.wordSearchLoading
    var previousBookmarkCount by remember { mutableIntStateOf(bookmarkCount) }
    var ribbonUnfurlPending by remember { mutableStateOf(false) }
    var ribbonUnfurlEpoch by remember { mutableIntStateOf(0) }
    var placeRibbonUnfurlPending by remember { mutableStateOf(false) }
    var placeRibbonUnfurlEpoch by remember { mutableIntStateOf(0) }

    // A mark is normally added on Reader while Home is covered. Remember that
    // event, then begin the long unfurl only once the page turn exposes Home.
    LaunchedEffect(bookmarkCount) {
        if (bookmarkCount > previousBookmarkCount) ribbonUnfurlPending = true
        previousBookmarkCount = bookmarkCount
    }
    LaunchedEffect(coverSheetVisible, ribbonUnfurlPending) {
        if (coverSheetVisible && ribbonUnfurlPending) {
            ribbonUnfurlEpoch++
            ribbonUnfurlPending = false
        }
    }
    LaunchedEffect(readerVisitActive) {
        if (readerVisitActive) placeRibbonUnfurlPending = true
    }
    LaunchedEffect(chapterRibbonReady, placeRibbonUnfurlPending, uiState.continueTarget) {
        if (
            shouldUnfurlReadingPlaceRibbon(
                pendingReturn = placeRibbonUnfurlPending,
                chapterRibbonReady = chapterRibbonReady,
                currentPlacePresent = uiState.continueTarget != null,
            )
        ) {
            placeRibbonUnfurlEpoch++
            placeRibbonUnfurlPending = false
        }
    }

    // Keep the focused search at the top of its scrolling document. A
    // deliberate list scroll then dismisses it, hiding the keyboard and dials.
    // Search result changes themselves never steal focus.
    LaunchedEffect(searchFocused) {
        if (!searchFocused) return@LaunchedEffect
        listState.animateScrollToItem(SEARCH_ITEM_INDEX)
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
            .drop(1)
            .collect { (isScrolling, index, offset) ->
                if (isScrolling && (index != SEARCH_ITEM_INDEX || offset > DISMISS_SCROLL_THRESHOLD_PX)) {
                    focusManager.clearFocus()
                }
            }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .pointerInput(searchFocused, searchPaneVisible, searchBounds, searchPaneBounds) {
                    // Empty-query dials only: an outside tap dismisses without
                    // activating the list under the pane. Once a query is up,
                    // result rows must receive the gesture (they clear focus
                    // themselves on open). Scroll still dismisses while focused.
                    if (!searchFocused || !searchPaneVisible) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                            ?: return@awaitEachGesture
                        val tappedSearch = searchBounds?.contains(up.position) == true
                        val tappedPane = searchPaneBounds?.contains(up.position) == true
                        if (!tappedSearch && !tappedPane) {
                            up.consume()
                            focusManager.clearFocus()
                        }
                    }
                }
                .onGloballyPositioned {
                    boxTop = it.boundsInWindow().top
                    boxHeight = it.size.height.toFloat()
                },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(),
            ) {
                // Focused search claims the top of the sheet: the masthead
                // recedes so the field sits higher above the keyboard.
                AnimatedVisibility(
                    visible = !searchFocused,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
                ) {
                    HomeHeader(onOpenSettings = onOpenSettings)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalFadingEdges(
                        color = MaterialTheme.colorScheme.background,
                        top = listFadeTop,
                        bottom = listFadeBottom,
                        // Opaque band over the float's footprint so the soft
                        // edge dissolves just above the player, not through it.
                        bottomInset = listBottomInset,
                    ),
                    contentPadding = PaddingValues(
                        top = listFadeTop,
                        bottom = listBottomPadding,
                    ),
                ) {
            item(key = "chapter-page") {
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        // Small top breath when the masthead is gone so the
                        // field is not hard against the status-bar padding.
                        if (searchFocused) {
                            Spacer(Modifier.height(8.dp))
                        }
                        PaperSearchField(
                            value = uiState.query,
                            onValueChange = viewModel::onQueryChange,
                            placeholder = "Search surah, word, or 2:255",
                            onFocusChanged = { searchFocused = it },
                            modifier = Modifier
                                .padding(start = HomeStartInset, end = HomeEndInset)
                                .fillMaxWidth()
                                .onGloballyPositioned {
                                    searchBottom = it.boundsInWindow().bottom
                                    searchBounds = it.boundsInRoot()
                                },
                        )

                        if (!searching) {
                            uiState.continueTarget?.let { target ->
                                ContinueRow(
                                    target = target,
                                    onClick = { onOpenSurah(target.surah.id, target.ayah, null) },
                                )
                            }
                        }
                        if (
                            bookmarkCount > 0 &&
                            bookmarkStyle == HomeBookmarkStyle.SAVED_PASSAGES &&
                            !searching
                        ) {
                            SavedPassagesRow(
                                bookmarkCount = bookmarkCount,
                                unfurlSignal = ribbonUnfurlEpoch,
                                onClick = onOpenBookmarks,
                            )
                        }
                        Spacer(Modifier.height(16.dp))

                        if (showSurahMatches) SearchSectionLabel(text = "Surahs")
                        uiState.surahs.forEach { surah ->
                            SurahRow(
                                surah = surah,
                                currentAyah = uiState.continueTarget
                                    ?.takeIf { it.surah.id == surah.id }
                                    ?.ayah,
                                placeUnfurlSignal = placeRibbonUnfurlEpoch,
                                onClick = {
                                    focusManager.clearFocus()
                                    onOpenSurah(
                                        surah.id,
                                        uiState.ayahTarget ?: uiState.continueTarget
                                            ?.takeIf { it.surah.id == surah.id }
                                            ?.ayah,
                                        null,
                                    )
                                },
                            )
                        }

                        if (showWordSections) {
                            SearchSectionLabel(
                                text = if (uiState.wordSearchLoading && uiState.wordSections.isEmpty()) {
                                    "Searching ayahs…"
                                } else {
                                    "In the Quran"
                                },
                            )
                            uiState.wordSections.forEach { section ->
                                WordSearchSurahHeader(section = section)
                                section.hits.forEach { hit ->
                                    WordSearchHitRow(
                                        hit = hit,
                                        query = uiState.query,
                                        onClick = {
                                            focusManager.clearFocus()
                                            onOpenSurah(hit.surahId, hit.ayahNumber, hit.position)
                                        },
                                    )
                                }
                                if (section.hiddenCount > 0) {
                                    WordSearchExpandRow(
                                        section = section,
                                        onClick = { viewModel.toggleWordSearchSection(section.surahId) },
                                    )
                                } else if (
                                    section.expanded &&
                                    section.totalCount > WORD_SEARCH_PREVIEW_LIMIT
                                ) {
                                    WordSearchCollapseRow(
                                        onClick = { viewModel.toggleWordSearchSection(section.surahId) },
                                    )
                                }
                            }
                        }

                        if (showEmpty) {
                            Text(
                                text = "No matches",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = HomeStartInset,
                                        end = HomeEndInset,
                                        top = 24.dp,
                                        bottom = 24.dp,
                                    ),
                            )
                        }
                    }

                }
            }
            }
            }

            if (bookmarkCount > 0 && bookmarkStyle != HomeBookmarkStyle.SAVED_PASSAGES) {
                HomeBookmarkOverlay(
                    height = TopBoundRibbonHeight + padding.calculateTopPadding(),
                    unfurlSignal = ribbonUnfurlEpoch,
                    onClick = onOpenBookmarks,
                    modifier = Modifier
                        .matchParentSize()
                        .offset(y = -padding.calculateTopPadding()),
                )
            }

            SearchDialsPane(
                surahs = uiState.allSurahs,
                visible = searchPaneVisible,
                onOpen = { surahId, ayah ->
                    focusManager.clearFocus()
                    onOpenSurah(surahId, ayah, null)
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .offset {
                        IntOffset(0, (searchBottom - boxTop + searchPaneTopGapPx).roundToInt())
                    }
                    .padding(start = HomeStartInset, end = HomeEndInset)
                    .height(searchPaneHeight)
                    .onGloballyPositioned { searchPaneBounds = it.boundsInRoot() },
            )

            // Floating transport — same bottom inset as the reader's floating
            // Back-to / return-to-ayah controls so the paper stack keeps one
            // vertical rhythm. Embedded PlayerBar takes over on the reader.
            FloatingPlaybackControl(
                visible = showFloatingPlayback,
                state = uiState.playerState,
                chapterLabel = floatingPlayback?.surah?.nameTransliteration.orEmpty(),
                ayahLabel = floatingPlayback?.let { "${it.surah.id}:${it.ayah}" }.orEmpty(),
                reciterName = uiState.reciterName,
                onOpenNowPlaying = {
                    val target = floatingPlayback ?: return@FloatingPlaybackControl
                    focusManager.clearFocus()
                    onOpenSurah(target.surah.id, target.ayah, null)
                },
                onReciterClick = onOpenSettings,
                onPlayPause = viewModel::togglePlayPause,
                onFastBackward = viewModel::fastBackward,
                onFastForward = viewModel::fastForward,
                onRepeatClick = viewModel::cycleRepeatMode,
                onSpeed = viewModel::cycleSpeed,
                onClose = viewModel::dismissFloatingPlayback,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        floatingPlaybackHeight = with(density) {
                            coords.size.height.toDp()
                        }
                    },
            )
        }
    }
}

@Composable
private fun HomeHeader(
    onOpenSettings: () -> Unit,
) {
    val accents = LocalQuranAccents.current
    val titleSheen = remember { mutableStateOf(0.35f) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HomeStartInset, end = HomeEndInset),
    ) {
        Column(Modifier.weight(1f)) {
            Spacer(Modifier.height(38.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Beautiful Quran",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 34.sp,
                        lineHeight = 40.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                AlphaTag()
            }
            Spacer(Modifier.height(24.dp))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .quietClickable(role = Role.Button, onClick = onOpenSettings)
                .semantics { contentDescription = "Open settings" },
        ) {
            GildedRosette(
                size = 30.dp,
                brightGold = accents.goldBright,
                deepGold = accents.goldDeep,
                embossDark = accents.embossDark,
                embossLight = accents.embossLight,
                sheen = titleSheen,
            )
        }
    }
}

/** The top-bound cloth and navigation remain separate so opening Bookmarks
 * never borrows the ribbon's retract interaction. */
@Composable
private fun HomeBookmarkOverlay(
    height: Dp,
    unfurlSignal: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        val placement = Modifier
            .align(Alignment.TopStart)
            .width(HomeRibbonLane)
            .height(height)

        VerseBookmarkRibbon(
            bookmarked = true,
            placeMarked = false,
            side = AyahSelectorSide.LEFT,
            chromeAlpha = { 1f },
            interactive = false,
            onToggle = { true },
            animateOnTap = false,
            unfurlSignal = unfurlSignal,
            edgeInset = HomeRibbonGutter,
            ribbonWidth = HomeRibbonWidth,
            topInset = 0.dp,
            bottomGap = 0.dp,
            modifier = placement,
        )
        Box(
            modifier = placement
                .quietClickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Open bookmarks" },
        )
    }
}

/** A bookmark index entry written into the chapter document. */
@Composable
private fun SavedPassagesRow(
    bookmarkCount: Int,
    unfurlSignal: Int,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Open bookmarks" }
            .padding(
                start = HomeStartInset,
                end = HomeEndInset,
                top = 18.dp,
                bottom = 18.dp,
            ),
    ) {
        Box(
            modifier = Modifier
                .width(HomeNumberColumn)
                .height(44.dp),
        ) {
            VerseBookmarkRibbon(
                bookmarked = true,
                placeMarked = false,
                side = AyahSelectorSide.LEFT,
                chromeAlpha = { 1f },
                interactive = false,
                onToggle = { true },
                animateOnTap = false,
                unfurlSignal = unfurlSignal,
                edgeInset = HomeRowRibbonGutter,
                ribbonWidth = HomeRibbonWidth,
                topInset = 0.dp,
                bottomGap = 0.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(HomeColumnGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Saved passages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (bookmarkCount == 1) "1 saved ayah" else "$bookmarkCount saved ayahs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ContinueRow(target: ContinueTarget, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            .quietClickable(onClick = onClick)
            .padding(vertical = 18.dp),
    ) {
        Spacer(Modifier.width(HomeStartInset + HomeNumberColumn))
        Spacer(Modifier.width(HomeColumnGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Continue listening",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${target.surah.nameTransliteration} · Ayah ${target.ayah}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = target.surah.nameArabic,
            style = ArabicTitleStyle,
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = HomeEndInset + HomeArabicOpticalInset),
        )
    }
}

@Composable
private fun SurahRow(
    surah: Surah,
    currentAyah: Int?,
    placeUnfurlSignal: Int,
    onClick: () -> Unit,
) {
    val accents = LocalQuranAccents.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(
                end = HomeEndInset,
                top = 15.dp,
                bottom = 15.dp,
            ),
    ) {
        if (currentAyah != null) {
            VerseBookmarkRibbon(
                bookmarked = false,
                placeMarked = true,
                side = AyahSelectorSide.LEFT,
                chromeAlpha = { 1f },
                interactive = false,
                onToggle = { false },
                bookmarkTipVisible = false,
                placeUnfurlSignal = placeUnfurlSignal,
                edgeInset = HomeRibbonGutter,
                ribbonWidth = HomeRibbonWidth,
                topInset = 0.dp,
                bottomGap = 0.dp,
                modifier = Modifier
                    .width(HomeRibbonLane)
                    .height(44.dp)
                    .semantics {
                        contentDescription = "Current place, ayah $currentAyah"
                    },
            )
        } else {
            Spacer(Modifier.width(HomeRibbonLane))
        }
        Box(Modifier.width(HomeNumberColumn)) {
            Text(
                text = surah.id.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = accents.gold.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.width(HomeColumnGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = surah.nameTransliteration,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${surah.nameTranslation} · ${surah.ayahCount} ayahs",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = surah.nameArabic,
            style = ArabicTitleStyle,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            modifier = Modifier.padding(end = HomeArabicOpticalInset),
        )
    }
}

internal fun shouldUnfurlReadingPlaceRibbon(
    pendingReturn: Boolean,
    chapterRibbonReady: Boolean,
    currentPlacePresent: Boolean,
): Boolean = pendingReturn && chapterRibbonReady && currentPlacePresent

@Composable
private fun SearchSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HomeStartInset,
                end = HomeEndInset,
                top = 8.dp,
                bottom = 4.dp,
            ),
    )
}

@Composable
private fun WordSearchSurahHeader(section: SurahWordSearchSection) {
    val accents = LocalQuranAccents.current
    val rule = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HomeStartInset,
                end = HomeEndInset,
                top = 18.dp,
                bottom = 8.dp,
            )
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(
                    color = rule,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(bottom = 10.dp),
    ) {
        Text(
            text = section.surahNameTransliteration,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${section.totalCount}",
            style = MaterialTheme.typography.labelMedium,
            color = accents.gold.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = section.surahNameArabic,
            style = ArabicTitleStyle,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun WordSearchHitRow(
    hit: WordSearchHit,
    query: String,
    onClick: () -> Unit,
) {
    val accents = LocalQuranAccents.current
    val highlightColor = accents.gold
    val translation = remember(hit.ayahTranslation, hit.translation, query, highlightColor) {
        buildAnnotatedString {
            for (span in englishTranslationHighlightSpans(
                ayahTranslation = hit.ayahTranslation,
                query = query,
                wordGloss = hit.translation,
            )) {
                if (span.highlighted) {
                    withStyle(
                        SpanStyle(
                            color = highlightColor,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(span.text)
                    }
                } else {
                    append(span.text)
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(
                start = HomeStartInset,
                end = HomeEndInset,
                top = 10.dp,
                bottom = 10.dp,
            ),
    ) {
        Text(
            text = "${hit.surahId}:${hit.ayahNumber}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
        if (translation.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WordSearchExpandRow(
    section: SurahWordSearchSection,
    onClick: () -> Unit,
) {
    Text(
        text = "Show ${section.hiddenCount} more in ${section.surahNameTransliteration}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(role = Role.Button, onClick = onClick)
            .padding(
                start = HomeStartInset,
                end = HomeEndInset,
                top = 12.dp,
                bottom = 12.dp,
            ),
    )
}

@Composable
private fun WordSearchCollapseRow(onClick: () -> Unit) {
    Text(
        text = "Show less",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(role = Role.Button, onClick = onClick)
            .padding(
                start = HomeStartInset,
                end = HomeEndInset,
                top = 12.dp,
                bottom = 12.dp,
            ),
    )
}
