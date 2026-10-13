package com.beautifulquran.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.beautifulquran.R
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahWordSearchSection
import com.beautifulquran.data.model.WordSearchHit
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.domain.EnglishTypography
import com.beautifulquran.domain.WORD_SEARCH_PREVIEW_LIMIT
import com.beautifulquran.domain.englishTranslationHighlightSpans
import com.beautifulquran.domain.parseSearchQuery
import com.beautifulquran.domain.spellingCorrection
import com.beautifulquran.ui.reader.VerseBookmarkRibbon
import com.beautifulquran.ui.reader.CAPS_TRACKING
import com.beautifulquran.ui.reader.remainingUnfurlSignal
import com.beautifulquran.ui.theme.quietSuppliedBrackets
import com.beautifulquran.ui.theme.ArabicTitleStyle
import com.beautifulquran.ui.theme.FloatingPaperControl
import com.beautifulquran.ui.theme.FloatingPaperEnter
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.typeScale
import com.beautifulquran.ui.theme.PaperSearchField
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.verticalFadingEdges
import com.beautifulquran.ui.theme.QuranTheme

/** The chapter list's first content item — focusing search returns here. */
private const val SEARCH_ITEM_INDEX = 0

/** How far (px) the list must move before a scroll counts as "dismiss". */
internal const val DISMISS_SCROLL_THRESHOLD_PX = 24

/** The pill's flight up to its row: far enough to travel that it earns a
 * drawer-scale duration; the strong ease-in-out keeps both ends graceful. */
internal const val ContinueDockMs = 500

/** The return flight settles at the same graceful pace. */
internal const val ContinueUndockMs = 500

/**
 * Strong ease-in-out for on-screen movement: the flight leaves and lands
 * gently instead of snapping at either end.
 */
internal val ContinueFlightEase = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

/**
 * The undock flight may run only off a completed dock: once the pill has
 * landed, leaving always earns the flight home. No extra delay — the dock
 * already required arriving fully in view, so any departure is contextual.
 */
internal fun undockFlightReady(docked: Boolean): Boolean = docked

/** Grace so fast scrolls never flap the pill: the row must sit fully in
 * view a beat before the dock flight runs. */
internal const val ContinueDockDwellMs = 500L

/** Raw flight progress where the docked pill starts fading into its row. */
internal const val ContinueDockFadeFrom = 0.5f

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
/**
 * With the feather below it, this sets the field as far above the Continue
 * band (34 dp) as the masthead title's baseline sits above the field.
 */
private val SearchBottomBreath = 10.dp
private val SearchBottomFeather = 24.dp

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    /** Search hits carry a word position, or zero plus [searchText] when the
     * visible match is a translator addition with no Quran-word position. */
    onOpenSurah: (surahId: Int, ayah: Int?, wordPosition: Int?, searchText: String?) -> Unit,
    onOpenSearchHit: (hit: WordSearchHit, query: String) -> Unit = { hit, query ->
        onOpenSurah(hit.surahId, hit.ayahNumber, hit.position, query)
    },
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
    onOpenBookmarks: () -> Unit = {},
    /**
     * The chapter bar is pinned above the paper stack, so this sheet does
     * not draw a second transport. [pinnedPlaybackHeight] is that bar's
     * body, already above the navigation inset. Read it here, not in the
     * activity, so the pill's expansion does not recompose the reader.
     */
    playbackPinned: Boolean = false,
    pinnedPlaybackHeight: () -> Dp = { 0.dp },
    playbackHost: com.beautifulquran.ui.reader.PinnedPlaybackHost? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val floatingPlayback = uiState.floatingPlayback
    // ContentPadding matches each fade so the first and last ink sit clear of
    // them at rest; rows only dissolve once they scroll into one.
    val listFadeBottom = 48.dp
    // Measured height of the floating transport; falls back to the clearance
    // estimate until the first layout pass. Used as bottomInset so the soft
    // fade sits on the paper just above the bar, matching the reader.
    var floatingPlaybackHeight by remember { mutableStateOf(FloatingPlaybackListClearance) }

    // Focus + geometry that anchor the fading dials pane just under the search
    // field, floating over the surah list.
    var searchFocused by remember { mutableStateOf(false) }
    var boxTop by remember { mutableFloatStateOf(0f) }
    var boxLeft by remember { mutableFloatStateOf(0f) }
    var boxHeight by remember { mutableFloatStateOf(0f) }
    var searchBottom by remember { mutableFloatStateOf(0f) }
    var searchBounds by remember { mutableStateOf<Rect?>(null) }
    var searchPaneBounds by remember { mutableStateOf<Rect?>(null) }
    val listState = rememberLazyListState()
    val searchPaneVisible = searchFocused && uiState.query.isEmpty()
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density).toFloat()
    val searchPaneTopGap = 12.dp
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
    // The list reaches the screen edge. A visible bar clears its own paper.
    // With no bar, the last row still sits above the gesture inset.
    val navigationBottom = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }
    // No staging on dismiss anywhere: Close clears the session in the
    // same frame the sweep starts, so the inset target falls at tap and
    // glides with the slide — bar, fade band, and rows travel as one.
    // (host.closing still stages the chrome layering above; just never
    // the paper: holding the inset through the slide reads as a second
    // bar sliding behind the first.)
    val pinnedSession = playbackPinned && !searchActive && floatingPlayback != null
    // The transport owns the bottom paper while it is up: the pill stands
    // down entirely instead of stacking above it.
    val transportOnScreen = !playbackPinned && showFloatingPlayback
    // The reading place lives in its chapter row. While that row is
    // scrolled out of view the pill floats at the bottom; once the row
    // itself is visible the pill docks away so the row owns the tap.
    val continueSurahId = uiState.continueTarget?.surah?.id
    // Dock only once the row is fully in view — flying to a sliver reads
    // as a miss. The row then owns the tap; the pill lands on its bounds.
    val continueRowVisible by remember(continueSurahId, searching) {
        derivedStateOf {
            if (continueSurahId == null || searching) {
                false
            } else {
                val info = listState.layoutInfo
                info.visibleItemsInfo.firstOrNull { it.key == "surah-$continueSurahId" }?.let { item ->
                    item.offset >= info.viewportStartOffset &&
                        item.offset + item.size <= info.viewportEndOffset
                } == true
            }
        }
    }
    val showContinuePill = shouldShowContinuePill(
        continuePresent = uiState.continueTarget != null,
        searchActive = searchActive,
        coverVisible = coverSheetVisible,
        continueRowVisible = continueRowVisible,
        transportVisible = transportOnScreen,
    )
    var continuePillHeight by remember { mutableStateOf(0.dp) }
    // Window bounds of the resting pill and its chapter row, for the dock
    // flight. The seat freezes while the pill is up: exit frames must not
    // shift the launch point. The row rect tracks live so the ghost homes
    // onto the row even while the list is still moving under it.
    var pillSeatRect by remember { mutableStateOf<Rect?>(null) }
    var continueRowRect by remember { mutableStateOf<Rect?>(null) }
    var pillWasShown by remember { mutableStateOf(false) }
    var dockStart by remember { mutableStateOf<Rect?>(null) }
    var docking by remember { mutableStateOf(false) }
    var dockProgress by remember { mutableFloatStateOf(0f) }
    var undockStart by remember { mutableStateOf<Rect?>(null) }
    var undocking by remember { mutableStateOf(false) }
    var undockProgress by remember { mutableFloatStateOf(0f) }
    var rowWasVisible by remember { mutableStateOf(false) }
    var dockReady by remember { mutableStateOf(false) }
    var undockArrived by remember { mutableStateOf(false) }
    /** True once a dock flight has landed and the row owns the place. */
    var docked by remember { mutableStateOf(false) }
    // The transport owns the bottom paper while it is up: stand the pill
    // down and forget its flight history so nothing launches from a stale
    // seat or lands on the transport's paper.
    if (transportOnScreen) {
        pillWasShown = false
        rowWasVisible = false
        docked = false
        docking = false
        dockStart = null
        undocking = false
        undockStart = null
    }
    // The pill holds its seat through the dwell so the flight launches
    // from the visible pill instead of chasing a three-second-old fade —
    // and keeps its list clearance, so arrival never shifts rows under
    // the waiting row and flaps the visibility guard.
    val dwellHolding = continueRowVisible && pillWasShown && !searchActive && coverSheetVisible
    // Dock flights run once the row has sat fully in view through the
    // dwell: fly the pill to the row — morphing onto its exact bounds —
    // instead of fading it away. Fast scrolls never arm the flight.
    if (showContinuePill) pillWasShown = true
    if (continueRowVisible && pillWasShown && !docking && !undocking && dockReady) {
        val start = pillSeatRect
        val end = if (!searching && coverSheetVisible) continueRowRect else null
        if (start != null && end != null) {
            dockStart = start
            docking = true
            pillWasShown = false
        }
    }
    if (continueRowVisible) rowWasVisible = true
    // The row left through the top while it owned the place: peel a copy
    // off the row and fly it back down to the pill's seat. A bottom exit
    // just lets the pill slide up on its own enter.
    val continueIndex = continueLazyIndex(
        uiState.surahs.map { it.id },
        continueSurahId,
        searching,
    )
    if (!continueRowVisible && rowWasVisible && !docking && !undocking) {
        val seat = pillSeatRect
        val start = continueRowRect
        if (seat != null && start != null && !searchActive && coverSheetVisible &&
            continueIndex >= 0 && listState.firstVisibleItemIndex >= continueIndex &&
            undockFlightReady(docked)
        ) {
            undockStart = start
            undocking = true
        } else {
            // No landing owns the row, a bottom exit, or nowhere to fly
            // from: the pill returns on its plain enter, flightless.
            docked = false
        }
        rowWasVisible = false
    }
    LaunchedEffect(continueRowVisible, pillWasShown, searchActive, coverSheetVisible) {
        // showContinuePill is already false once the row is visible, so the
        // dwell watches pillWasShown (the pill was up when the row arrived).
        if (continueRowVisible && pillWasShown && !searchActive && coverSheetVisible) {
            delay(ContinueDockDwellMs)
            dockReady = true
        } else {
            dockReady = false
        }
    }
    LaunchedEffect(docking) {
        if (docking) {
            dockProgress = 0f
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(ContinueDockMs, easing = LinearEasing),
            ) { value, _ -> dockProgress = value }
            docked = true
            docking = false
            dockStart = null
        }
    }
    LaunchedEffect(undocking) {
        if (undocking) {
            undockProgress = 0f
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(ContinueUndockMs, easing = LinearEasing),
            ) { value, _ -> undockProgress = value }
            docked = false
            undocking = false
            undockStart = null
            undockArrived = true
        }
    }
    // The row came back, or search/cover took the sheet: abandon any
    // flight and let the floats' own enters bring them back.
    LaunchedEffect(continueRowVisible, searchActive, coverSheetVisible) {
        if (!continueRowVisible || searchActive || !coverSheetVisible) {
            docking = false
            dockStart = null
        }
        if (continueRowVisible || searchActive || !coverSheetVisible) {
            undocking = false
            undockStart = null
        }
        if (searchActive || !coverSheetVisible) {
            pillWasShown = false
            rowWasVisible = false
            docked = false
        }
    }
    // The undocked pill lands exactly on its seat: skip its enter so the
    // handoff from the ghost is seamless. Any later hide re-arms it.
    LaunchedEffect(showContinuePill) {
        if (!showContinuePill) undockArrived = false
    }
    val listBottomInsetTarget = homeListBottomInset(
        pinnedSession = pinnedSession,
        pinnedHeight = if (pinnedSession) pinnedPlaybackHeight() else 0.dp,
        floatingVisible = showFloatingPlayback,
        floatingHeight = floatingPlaybackHeight,
        navigationBottom = navigationBottom,
        continueVisible = showContinuePill || dwellHolding,
        continueHeight = continuePillHeight,
    )
    // One motion, not two: the sweep and the inset share duration and
    // easing, so bar, fade band, and rows travel down together. Staging
    // them (hold, then settle) reads as a second bar sliding behind.
    val listBottomInset by animateDpAsState(
        targetValue = listBottomInsetTarget,
        animationSpec = tween(260),
        label = "homeListBottomInset",
    )
    val host = playbackHost
    if (host != null) {
        if (!host.closing) {
            val session = floatingPlayback != null && !searchActive
            if (host.coverSession != session) host.coverSession = session
            if (host.chapterLabel != floatingPlayback?.surah?.nameTransliteration.orEmpty()) {
                host.chapterLabel = floatingPlayback?.surah?.nameTransliteration.orEmpty()
            }
            val ayah = floatingPlayback?.let { "${it.surah.id}:${it.ayah}" }.orEmpty()
            if (host.ayahLabel != ayah) host.ayahLabel = ayah
        }
        SideEffect {
            host.onOpenNowPlaying = open@{
                val target = floatingPlayback ?: return@open
                focusManager.clearFocus()
                onOpenSurah(target.surah.id, target.ayah, null, null)
            }
            host.onClose = viewModel::dismissFloatingPlayback
        }
    }
    val listBottomPadding = listFadeBottom + listBottomInset
    val showSurahMatches = searching && uiState.surahs.isNotEmpty()
    val showWordSections = searching &&
        (uiState.wordSections.isNotEmpty() || uiState.wordSearchLoading)
    val showEmpty = searching &&
        uiState.surahs.isEmpty() &&
        uiState.wordSections.isEmpty() &&
        !uiState.wordSearchLoading
    val correctedQuery = spellingCorrection(uiState.wordSections.flatMap { it.hits })
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

    // Lift the results to the top on focus, then dismiss the keyboard
    // on a deliberate scroll. Results stay: searchActive covers a query
    // without focus, so the field keeps its fixed seat while the keyboard
    // and dials go away. Back still dismisses without scrolling.
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
                if (shouldDismissSearchOnScroll(isScrolling, index, offset)) {
                    focusManager.clearFocus()
                }
            }
    }

    BackHandler(enabled = searchFocused) { focusManager.clearFocus() }

    val layoutDirection = LocalLayoutDirection.current
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            Modifier
                // Bottom stays open so the playback bar can meet the screen edge.
                // The list pads itself for the bar, or for the gesture inset.
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(layoutDirection),
                )
                .fillMaxSize()
                .pointerInput(searchFocused, searchPaneVisible, searchBounds, searchPaneBounds) {
                    // Empty-query dials only: an outside tap dismisses without
                    // activating the list under the pane. Once a query is up,
                    // result rows must receive the gesture (they clear focus
                    // themselves on open). Scroll dismissal lives in the
                    // snapshotFlow watcher above, for both states.
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
                    boxLeft = it.boundsInWindow().left
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
                // Single fixed top bar: masthead + search + feather never
                // scroll, so the field can never be caught half-cut mid-list.
                // Focused search claims the top of the sheet: the masthead
                // recedes so the field sits higher above the keyboard.
                val paper = MaterialTheme.colorScheme.background
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(paper),
                ) {
                    AnimatedVisibility(
                        visible = !searchFocused,
                        enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                        exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
                    ) {
                        HomeHeader(onOpenSettings = onOpenSettings)
                    }
                    // Small top breath when the masthead is gone so the
                    // field is not hard against the status-bar padding.
                    if (searchFocused) {
                        Spacer(Modifier.height(8.dp))
                    }
                    PaperSearchField(
                        value = uiState.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = "Search concept, “exact phrase”, or 2:255",
                        contentDescription = "Search",
                        onFocusChanged = { focused ->
                            searchFocused = focused
                            if (focused) viewModel.onSearchFocused()
                        },
                        modifier = Modifier
                            .padding(start = HomeStartInset, end = HomeEndInset)
                            .fillMaxWidth()
                            .onGloballyPositioned {
                                searchBottom = it.boundsInWindow().bottom
                                searchBounds = it.boundsInRoot()
                            },
                    )
                    Spacer(Modifier.height(SearchBottomBreath))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalFadingEdges(
                        color = MaterialTheme.colorScheme.background,
                        // The feather under the search field. It is the list's
                        // own top edge, not a strip above the list: a strip
                        // has nothing passing under it, so once the field
                        // left the list (#853) it faded paper into paper and
                        // the rows met the bar on a hard line.
                        top = SearchBottomFeather,
                        bottom = listFadeBottom,
                        // Opaque band over the float's footprint so the soft
                        // edge dissolves just above the player, not through it.
                        bottomInset = listBottomInset,
                    ),
                    contentPadding = PaddingValues(
                        top = SearchBottomFeather,
                        bottom = listBottomPadding,
                    ),
                ) {
                    // The reading place no longer gets a persistent top band:
                    // its chapter row carries the place ribbon in situ, and
                    // the floating pill below stands in only while that row
                    // is scrolled out of view.
                    if (showSurahMatches) {
                        item(key = "surahs-label") {
                            SearchSectionLabel(text = "Surahs")
                        }
                    }
                    items(uiState.surahs, key = { "surah-${it.id}" }) { surah ->
                        SurahRow(
                            surah = surah,
                            currentAyah = uiState.continueTarget
                                ?.takeIf { it.surah.id == surah.id }
                                ?.ayah,
                            placeUnfurlSignal = placeRibbonUnfurlEpoch,
                            onPlaceUnfurlConsumed = { consumed ->
                                placeRibbonUnfurlEpoch = remainingUnfurlSignal(
                                    current = placeRibbonUnfurlEpoch,
                                    consumed = consumed,
                                )
                            },
                            onClick = {
                                focusManager.clearFocus()
                                onOpenSurah(
                                    surah.id,
                                    uiState.ayahTarget ?: uiState.continueTarget
                                        ?.takeIf { it.surah.id == surah.id }
                                        ?.ayah,
                                    null,
                                    null,
                                )
                            },
                            modifier = if (!searching && continueSurahId == surah.id) {
                                Modifier.onGloballyPositioned {
                                    continueRowRect = it.boundsInWindow()
                                }
                            } else {
                                Modifier
                            },
                        )
                    }

                    if (showWordSections) {
                        item(key = "word-results") {
                            Column(Modifier.fillMaxWidth()) {
                                if (correctedQuery != null) {
                                    SearchCorrectionLabel(correctedQuery)
                                }
                                SearchSectionLabel(
                                    text = if (uiState.wordSearchLoading && uiState.wordSections.isEmpty()) {
                                        "Searching ayahs…"
                                    } else {
                                        val count = uiState.wordSections.sumOf { it.totalCount }
                                        "In the Quran · $count relevant ${if (count == 1) "ayah" else "ayahs"}" +
                                            if (uiState.wordSearchLoading) " · refining…" else ""
                                    },
                                    topPadding = if (correctedQuery == null) 8.dp else 2.dp,
                                )
                                uiState.wordSections.forEach { section ->
                                    WordSearchSurahHeader(section = section)
                                    section.hits.forEach { hit ->
                                        WordSearchHitRow(
                                            hit = hit,
                                            query = uiState.query,
                                            onClick = {
                                                focusManager.clearFocus()
                                                onOpenSearchHit(hit, uiState.query)
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
                        }
                    }

                    if (showEmpty) {
                        item(key = "empty") {
                        Text(
                            text = "No relevant ayahs found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = QuranTheme.ink.quiet,
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

            // The ribbon is the bookmarks entry; search owns the sheet while
            // active (dials or results), so it steps aside until the query
            // clears and focus leaves.
            if (!searchActive && bookmarkCount > 0) {
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
                    onOpenSurah(surahId, ayah, null, null)
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

            // Bottom stack: the pill stands down entirely while the
            // transport is up (no stacking). While the reader sheet is open
            // the chapter bar is pinned above the stack and carries the
            // transport itself; the continue pill is cover-gated, so it is
            // already gone there and needs no pinned copy.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(),
            ) {
                uiState.continueTarget?.let { target ->
                    FloatingPaperControl(
                        visible = (showContinuePill || dwellHolding) && !docking && !undocking,
                        enter = if (undockArrived) EnterTransition.None else FloatingPaperEnter,
                        modifier = Modifier
                            .fillMaxWidth()
                            // The pill only ever shows while the transport is
                            // off, so it always carries its own gesture pad.
                            .navigationBarsPadding()
                            .onGloballyPositioned { coords ->
                                continuePillHeight = with(density) {
                                    coords.size.height.toDp()
                                }
                            },
                    ) {
                        // Seat on the ink, not the host: the AnimatedVisibility
                        // wrapper carries the bottom clearance and nav pad,
                        // which would launch every flight tall and drift it.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    if (showContinuePill || dwellHolding) {
                                        pillSeatRect = coords.boundsInWindow()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            ContinuePill(
                                target = target,
                                onClick = {
                                    focusManager.clearFocus()
                                    onOpenSurah(target.surah.id, target.ayah, null, null)
                                },
                            )
                        }
                    }
                }
                if (!playbackPinned) FloatingPlaybackControl(
                    visible = showFloatingPlayback,
                    state = uiState.playerState,
                    chapterLabel = floatingPlayback?.surah?.nameTransliteration.orEmpty(),
                    ayahLabel = floatingPlayback?.let { "${it.surah.id}:${it.ayah}" }.orEmpty(),
                    reciterName = uiState.reciterName,
                    onOpenNowPlaying = {
                        val target = floatingPlayback ?: return@FloatingPlaybackControl
                        focusManager.clearFocus()
                        onOpenSurah(target.surah.id, target.ayah, null, null)
                    },
                    onReciterClick = onOpenSettings,
                    onDismissError = viewModel::clearPlaybackError,
                    onPlayPause = viewModel::togglePlayPause,
                    onFastBackward = viewModel::fastBackward,
                    onFastForward = viewModel::fastForward,
                    onRepeatClick = viewModel::cycleRepeatMode,
                    onSpeed = viewModel::cycleSpeed,
                    onClose = viewModel::dismissFloatingPlayback,
                    inkLabAvailable = host?.inkLabAvailable == true,
                    inkLabOpen = host?.inkLabOpen == true,
                    onInkLabClick = { host?.let { it.inkLabOpen = !it.inkLabOpen } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            floatingPlaybackHeight = with(density) {
                                coords.size.height.toDp()
                            }
                        },
                )
            }
            // Dock flights: a live copy of the pill travels between its
            // resting seat and the chapter row — resizing, squaring its
            // corners, settling every inset onto the row, then fading to
            // reveal it (or landing back on its seat, seamless, on the way
            // out). Sizing runs on layout because only a full morph lands
            // exactly; the tree is tiny and the flight is half a second.
            // The landing end tracks live bounds so flights still connect
            // while the list moves. Undock reverses the morph instead of
            // fading.
            if (docking || undocking) {
                val start = if (docking) dockStart else undockStart
                val end = if (docking) continueRowRect else pillSeatRect
                val target = uiState.continueTarget
                val raw = if (docking) dockProgress else undockProgress
                if (start != null && end != null && target != null &&
                    start.width > 0f && start.height > 0f
                ) {
                    val flight = ContinueFlightEase.transform(raw.coerceIn(0f, 1f))
                    val settled = if (docking) flight else 1f - flight
                    // The Box origin is already the content origin: the
                    // onGloballyPositioned feeding boxLeft/boxTop sits after
                    // the Scaffold padding in the chain, so no padding math
                    // belongs here (subtracting it lands every flight high).
                    val left = lerp(start.left, end.left, flight) - boxLeft
                    val top = lerp(start.top, end.top, flight) - boxTop
                    val width = with(density) { lerp(start.width, end.width, flight).toDp() }
                    val height = with(density) { lerp(start.height, end.height, flight).toDp() }
                    Box(
                        modifier = Modifier
                            .absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                            .width(width)
                            .height(height)
                            .graphicsLayer {
                                shape = RoundedCornerShape(continueDockRadius(settled))
                                clip = true
                                alpha = if (docking) continueDockAlpha(raw) else 1f
                            }
                            .clearAndSetSemantics {},
                    ) {
                        ContinuePill(
                            target = target,
                            onClick = {
                                focusManager.clearFocus()
                                onOpenSurah(target.surah.id, target.ayah, null, null)
                            },
                            docked = settled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    onOpenSettings: () -> Unit,
) {
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
                        fontSize = typeScale(34.sp),
                        lineHeight = 40.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                // The title sits 7 dp below the masthead's overall center.
                .offset(y = 7.dp)
                .quietClickable(role = Role.Button, onClick = onOpenSettings)
                .semantics { contentDescription = "Open settings" },
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = QuranTheme.ink.quiet,
                modifier = Modifier.size(26.dp),
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

/** The pill's wash: a calm fixed radius the ribbon's straight-cut ends sit inside. */
internal val ContinuePillRadius: Dp = 28.dp
internal val ContinuePillShape = RoundedCornerShape(ContinuePillRadius)

/** Resting pill geometry. */
internal val ContinuePillMargin: Dp = 16.dp
internal val ContinuePillStartInset: Dp = 8.dp
internal val ContinuePillEndInset: Dp = 16.dp
internal val ContinuePillVertical: Dp = 12.dp
internal val ContinuePillRibbonHeight: Dp = 40.dp
internal val ContinuePillArabicSize: TextUnit = 20.sp

/** The chapter row geometry the flight lands on. */
internal val ContinueRowVertical: Dp = 15.dp
internal val ContinueRowRibbonHeight: Dp = 44.dp
internal val ContinueRowEndInset: Dp = HomeEndInset + HomeArabicOpticalInset

/** Wash-to-row corner collapse over the dock flight (pill radius → rectangle). */
internal fun continueDockRadius(flight: Float): Dp =
    ContinuePillRadius * (1f - flight.coerceIn(0f, 1f))

/** The docked pill holds opaque through the flight, then fades into its row. */
internal fun continueDockAlpha(progress: Float): Float =
    1f - ((progress - ContinueDockFadeFrom) / (1f - ContinueDockFadeFrom)).coerceIn(0f, 1f)

/** Outer margin melts so the wash lands full-bleed on the row's width. */
internal fun continueDockMargin(docked: Float): Dp =
    ContinuePillMargin * (1f - docked.coerceIn(0f, 1f))

/** Ribbon lane slides to the row's lane so the cloths coincide on landing. */
internal fun continueDockStartInset(docked: Float): Dp =
    ContinuePillStartInset * (1f - docked.coerceIn(0f, 1f))

/** Trailing inset glides out so the Arabic lands on the row's Arabic rail. */
internal fun continueDockEndInset(docked: Float): Dp =
    ContinuePillEndInset + (ContinueRowEndInset - ContinuePillEndInset) * docked.coerceIn(0f, 1f)

/** The CONTINUE flag dissolves across the flight; the row has none. */
internal fun continueDockCapsAlpha(docked: Float): Float = 1f - docked.coerceIn(0f, 1f)

/**
 * Lazy-column index of the reading-place row: the top breath, then every
 * surah in order (no section label outside search). Negative when the row
 * is not on the sheet — searching, or no reading place at all.
 */
internal fun continueLazyIndex(
    surahIds: List<Int>,
    continueId: Int?,
    searching: Boolean,
): Int {
    if (searching || continueId == null) return -1
    val position = surahIds.indexOf(continueId)
    return if (position < 0) -1 else 1 + position
}

/**
 * The reading place floats only while its chapter row is scrolled out of
 * view. Search owns the sheet while active, and the pill is cover-gated
 * like the transport, so it never stacks on the pinned chapter session.
 */
internal fun shouldShowContinuePill(
    continuePresent: Boolean,
    searchActive: Boolean = false,
    coverVisible: Boolean = true,
    continueRowVisible: Boolean = false,
    /** The transport owns the bottom paper while it is up: no stacking. */
    transportVisible: Boolean = false,
): Boolean = continuePresent && !searchActive && coverVisible && !continueRowVisible && !transportVisible

/**
 * Pill-shaped stand-in for the chapter row: the green place ribbon and a
 * small-caps CONTINUE flag over the same bilingual identity
 * (transliterated name, ayah place, Arabic name) on the quiet green wash,
 * floating over the paper until its row scrolls into view and the pill
 * docks away. No card, elevation, or border — the wash is the container,
 * matching [ReturnToAyahPill]. The ribbon is the shared
 * [VerseBookmarkRibbon] place cloth, not a bespoke mark. Ribbon, gold
 * number, and bilingual name keep the chapter row's order; Arabic ends
 * where chapter Arabic ends.
 */
@Composable
private fun ContinuePill(
    target: ContinueTarget,
    onClick: () -> Unit,
    /** Dock flight progress: 0 resting, 1 settled exactly onto the chapter row. */
    docked: Float = 0f,
) {
    val colors = MaterialTheme.colorScheme
    val settled = docked.coerceIn(0f, 1f)
    val vertical = ContinuePillVertical + (ContinueRowVertical - ContinuePillVertical) * settled
    val shape = RoundedCornerShape(continueDockRadius(settled))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = continueDockMargin(settled))
            .background(QuranTheme.accents.greenWash, shape)
            // Clip the cloth to the wash so the ribbon's straight-cut ends
            // can never poke past the curve.
            .clip(shape)
            .quietClickable(onClick = onClick)
            .padding(
                start = continueDockStartInset(settled),
                end = continueDockEndInset(settled),
                top = vertical,
                bottom = vertical,
            )
            .semantics {
                contentDescription =
                    "Continue listening: ${target.surah.nameTransliteration}, ayah ${target.ayah}"
                role = Role.Button
            },
    ) {
        VerseBookmarkRibbon(
            bookmarked = false,
            placeMarked = true,
            side = AyahSelectorSide.LEFT,
            chromeAlpha = { 1f },
            interactive = false,
            onToggle = { false },
            bookmarkTipVisible = false,
            edgeInset = HomeRibbonGutter,
            ribbonWidth = HomeRibbonWidth,
            topInset = 0.dp,
            bottomGap = 0.dp,
            modifier = Modifier
                .width(HomeRibbonLane)
                .height(
                    ContinuePillRibbonHeight +
                        (ContinueRowRibbonHeight - ContinuePillRibbonHeight) * settled,
                ),
        )
        // Row order, compacted: ribbon, gold number, then the name —
        // the pill keeps the chapter row's structure, not its rails.
        Box(Modifier.width(HomeNumberColumn)) {
            Text(
                text = target.surah.id.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = LocalQuranAccents.current.goldInk,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(HomeColumnGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = "CONTINUE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = CAPS_TRACKING),
                fontSize = 10.sp,
                color = QuranTheme.accents.greenQuiet.copy(alpha = continueDockCapsAlpha(settled)),
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = target.surah.nameTransliteration,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = " · Ayah ${target.ayah}",
                    style = MaterialTheme.typography.titleMedium,
                    color = QuranTheme.accents.greenInk,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // At rest 16 margin + 16 inset = 32, the rows' Arabic rail (28 + 4
        // optical); docked the wash is full-bleed with the rail's own 32.
        Text(
            text = target.surah.nameArabic,
            style = ArabicTitleStyle,
            fontSize = (
                ContinuePillArabicSize.value +
                    (ArabicTitleStyle.fontSize.value - ContinuePillArabicSize.value) * settled
                ).sp,
            color = QuranTheme.accents.greenInk,
            maxLines = 1,
        )
    }
}

@Composable
private fun SurahRow(
    surah: Surah,
    currentAyah: Int?,
    placeUnfurlSignal: Int,
    onPlaceUnfurlConsumed: (Int) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalQuranAccents.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(
                end = HomeEndInset,
                top = ContinueRowVertical,
                bottom = ContinueRowVertical,
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
                onPlaceUnfurlConsumed = onPlaceUnfurlConsumed,
                edgeInset = HomeRibbonGutter,
                ribbonWidth = HomeRibbonWidth,
                topInset = 0.dp,
                bottomGap = 0.dp,
                modifier = Modifier
                    .width(HomeRibbonLane)
                    .height(ContinueRowRibbonHeight)
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
                color = accents.goldInk,
            )
        }
        Spacer(Modifier.width(HomeColumnGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = surah.nameTransliteration,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = typeScale(17.sp),
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${surah.nameTranslation} · ${surah.ayahCount} ayahs",
                style = MaterialTheme.typography.labelMedium,
                color = QuranTheme.ink.quiet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = surah.nameArabic,
            style = ArabicTitleStyle,
            color = QuranTheme.accents.greenInk,
            modifier = Modifier.padding(end = HomeArabicOpticalInset),
        )
    }
}

internal fun shouldUnfurlReadingPlaceRibbon(
    pendingReturn: Boolean,
    chapterRibbonReady: Boolean,
    currentPlacePresent: Boolean,
): Boolean = pendingReturn && chapterRibbonReady && currentPlacePresent

/** A deliberate list scroll under the fixed search dismisses the keyboard. */
internal fun shouldDismissSearchOnScroll(
    isScrolling: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
): Boolean = isScrolling &&
    (
        firstVisibleItemIndex != SEARCH_ITEM_INDEX ||
            firstVisibleItemScrollOffset > DISMISS_SCROLL_THRESHOLD_PX
        )

@Composable
private fun SearchSectionLabel(text: String, topPadding: Dp = 8.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = QuranTheme.ink.quiet,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HomeStartInset,
                end = HomeEndInset,
                top = topPadding,
                bottom = 4.dp,
            ),
    )
}

@Composable
private fun SearchCorrectionLabel(correctedQuery: String) {
    val accents = LocalQuranAccents.current
    Text(
        text = buildAnnotatedString {
            append("Searching instead for ")
            withStyle(SpanStyle(color = accents.goldInk)) {
                append(correctedQuery)
            }
        },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Normal,
        color = QuranTheme.ink.quiet,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HomeStartInset,
                end = HomeEndInset,
                top = 8.dp,
            ),
    )
}

@Composable
private fun WordSearchSurahHeader(section: SurahWordSearchSection) {
    val accents = LocalQuranAccents.current
    val rule = QuranTheme.ink.wash
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
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = typeScale(16.sp),
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${section.totalCount}",
            style = MaterialTheme.typography.labelMedium,
            color = accents.goldInk,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = section.surahNameArabic,
            style = ArabicTitleStyle,
            fontSize = 20.sp,
            color = QuranTheme.accents.greenInk,
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
    val translationInk = QuranTheme.ink.strong
    val displayQuery = remember(query) { parseSearchQuery(query).text }
    val translation = remember(
        hit.displayText,
        hit.translation,
        hit.matchTerms,
        hit.matchLabel,
        displayQuery,
        highlightColor,
        translationInk,
    ) {
        buildAnnotatedString {
            for (span in englishTranslationHighlightSpans(
                ayahTranslation = hit.displayText,
                query = displayQuery,
                wordGloss = EnglishTypography.typesetName(hit.translation),
                semanticLabel = hit.matchLabel.orEmpty(),
                semanticTerms = hit.matchTerms,
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
            quietSuppliedBrackets(toAnnotatedString().text, 0, translationInk)
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
            text = buildString {
                append("${hit.surahId}:${hit.ayahNumber}")
                append(" · ${hit.matchReason}")
            },
            style = MaterialTheme.typography.labelMedium,
            color = QuranTheme.ink.quiet,
        )
        if (translation.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyLarge,
                color = QuranTheme.ink.strong,
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
        color = QuranTheme.accents.greenInk,
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
        color = QuranTheme.ink.quiet,
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
