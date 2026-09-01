package com.beautifulquran.ui.reader

import com.beautifulquran.DevProfiling
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.model.Surah
import com.beautifulquran.domain.EnglishVerseAlignments
import com.beautifulquran.domain.englishSeekWordPosition
import com.beautifulquran.domain.BASMALAH_PLAYLIST_AYAH
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.mushafFontPreloadPages
import com.beautifulquran.ui.reader.focus.FocusEngine
import com.beautifulquran.ui.reader.focus.rememberReaderFocusController
import com.beautifulquran.ui.reader.MushafQcfFonts
import com.beautifulquran.ui.theme.FloatingPaperControl
import com.beautifulquran.ui.theme.IslamicReturnToAyahButton
import com.beautifulquran.ui.theme.ReturnArrowHeading
import com.beautifulquran.ui.theme.InkRevealOverlay
import com.beautifulquran.ui.theme.absorbPointerEvents
import com.beautifulquran.ui.theme.contrastingOverlayColorScheme
import com.beautifulquran.ui.theme.contextualGuideProgressiveBlur
import com.beautifulquran.ui.theme.verticalFadingEdges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Paused highlight polling is 250 ms; leave one scheduling beat for a fresh sample. */
private const val HELD_WORD_REFRESH_MS = 300L

/**
 * How long a chapter takes to settle onto the paper when it first opens.
 *
 * The old 220ms, coming straight off a progress wheel, read as the page
 * appearing from nowhere rather than as ink arriving on paper.
 */
private const val ReaderEntranceFadeMs = 450

/** One-shot request to reveal a held word after opening or foreground resume. */
private data class WordFocusRequest(
    val generation: Int,
    val ayah: Int,
    val wordPosition: Int,
    val activation: Long,
) {
    fun matches(word: ActiveWord?): Boolean =
        word?.ayah == ayah &&
            word.wordPosition == wordPosition &&
            word.activation == activation
}

/** Flying next-chapter opening while it slides from footer → header slot. */
private data class FlyingChapterHeader(
    val surah: Surah,
    val startYInRoot: Float,
    val endYInRoot: Float,
    /**
     * List translation already applied by rubber-band overscroll at release.
     * The fly continues from this lift so the page never snaps back down.
     */
    val startLiftPx: Float,
)

private sealed interface LazyItem {
    val key: String
    data object Header : LazyItem {
        override val key = "header"
    }
    /** Chapter-opening basmalah calligraphy — its own focusable list item
     *  above ayah 1 (playlist sentinel [BASMALAH_PLAYLIST_AYAH]). */
    data object Basmalah : LazyItem {
        override val key = "basmalah"
    }
    data class AyahItem(val ayahIndex: Int) : LazyItem {
        override val key = "ayah_$ayahIndex"
    }
    data class PageDivider(val page: Int) : LazyItem {
        override val key = "page_$page"
    }
    /** End-of-chapter invitation to the next surah (absent on 114). */
    data object NextChapter : LazyItem {
        override val key = "next_chapter"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    surahId: Int,
    startAyah: Int?,
    /** True when [startAyah] came from an autoplay intent, not a bare focus selection. */
    startPlaybackRequested: Boolean = false,
    /** 1-based word from a home word-search hit — triggers the orange flash. */
    startWordPosition: Int? = null,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens the following chapter from the end-of-chapter footer. */
    onOpenNextChapter: (surahId: Int) -> Unit = {},
    /** Opens the previous chapter from a top-of-chapter overscroll pull. */
    onOpenPreviousChapter: (surahId: Int) -> Unit = {},
    onAyahSelectorExpandedChange: (Boolean) -> Unit = {},
    /** Opens the Root Word Viewer (default word long-press). In developer mode
     *  MainActivity may intercept this into a chooser that can also open the
     *  Timings Lab. See docs/ROOT_VIEWER.md. */
    onOpenRootViewer: (surahId: Int, ayah: Int, wordPosition: Int) -> Unit = { _, _, _ -> },
    /** Fired on the first hand scroll/drag while a concordance "Back to"
     *  line is showing — MainActivity owns the floating line and its timer. */
    onRootReturnUserMoved: () -> Unit = {},
    /** True while a concordance "Back to" line is showing above the stack
     *  (hides the return-to-ayah ornament so the two never compete). */
    rootReturnVisible: Boolean = false,
    /** True while an ink-bleed overlay (Root Viewer / Timings Lab / chooser)
     *  is riding over this reader, so the status bar stays visible under its
     *  header. */
    keepStatusBarVisible: Boolean = false,
    /** Reports reader-owned ink surfaces to the paper stack so a horizontal
     * page turn cannot begin while the surface is entering, open, or closing. */
    onInkOverlayVisibilityChange: (Boolean) -> Unit = {},
    /** Gather mode — ordered verse selection for text share (docs/SHARE.md). */
    gathering: Boolean = false,
    /** 1-based ordinal for a gathered verse, or null when not selected. */
    gatherOrdinal: (surahId: Int, ayah: Int) -> Int? = { _, _ -> null },
    onToggleGatheredAyah: (surahId: Int, ayah: Int) -> Unit = { _, _ -> },
) {
    LaunchedEffect(surahId) { viewModel.load(surahId) }
    DisposableEffect(onAyahSelectorExpandedChange) {
        onDispose { onAyahSelectorExpandedChange(false) }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    // Deliberately NOT delegated: the value is only read inside individual
    // list items (via derivedStateOf), so a word change recomposes exactly
    // one ayah block — never the whole screen.
    val activeWordState = viewModel.activeWord.collectAsStateWithLifecycle()
    val settings by viewModel.settings.settings.collectAsStateWithLifecycle()
    // Snapshot the shared Continue / green-ribbon target for this visit. A
    // deliberate pause moves this local marker and persists the same target.
    var parkedPlace by remember(surahId) {
        mutableStateOf(
            viewModel.settings.settings.value.let { saved ->
                readingPlace(saved.lastSurah, saved.lastAyah)
            },
        )
    }
    var placeUnfurlTarget by remember(surahId) { mutableStateOf<ReadingPlace?>(null) }
    var placeUnfurlToken by remember(surahId) { mutableIntStateOf(0) }
    val mushafUi by viewModel.mushaf.collectAsStateWithLifecycle()
    val mushafMode = settings.readingLayout == ReadingLayout.MUSHAF
    // The English leaf sets a straddling verse whole on the page it begins on,
    // so every "which leaf is the voice on" answer is the verse's opening leaf
    // rather than the word's own. See MushafCatalog.readingPageOf.
    val mushafWholeVerses = mushafMode && settings.readingMode == ReadingMode.ENGLISH_ONLY
    LaunchedEffect(mushafMode) {
        if (mushafMode) viewModel.ensureMushaf(settings.englishLeafText)
    }
    val mushafCatalog = mushafUi?.catalog
    // The English book's leaves. A Madinah page takes more than one where its
    // English will not fit a leaf at a legible size, so the pager is indexed by
    // leaf and every leaf still names its page. Null keeps the Arabic leaf on
    // the identity mapping it has always had.
    val englishBook = mushafUi?.englishBook?.takeIf { mushafWholeVerses }
    // Leaf index -> Madinah page, and page -> its first leaf.
    val leafPage = remember(englishBook) {
        { index: Int -> englishBook?.leaf(index)?.page ?: (index + 1) }
    }
    val pageLeaf = remember(englishBook) {
        { page: Int -> englishBook?.firstLeafOf(page) ?: (page - 1) }
    }
    // The leaf the reader asked for, as soon as there is a catalog to ask.
    val mushafOpeningPage = remember(
        mushafCatalog,
        englishBook,
        surahId,
        startAyah,
        startWordPosition,
        mushafWholeVerses,
    ) {
        val catalog = mushafCatalog ?: return@remember null
        val ayah: Int = startAyah?.coerceAtLeast(1) ?: 1
        val page: Int = catalog.readingPageOf(
            surahId,
            ayah,
            startWordPosition ?: 1,
            wholeVerses = mushafWholeVerses,
        )
        // Typed locals, and no elvis unboxed in place — the second instance in
        // this file of the fault fixed in `repeatStartAyah`, and it arrived the
        // same way: an NPE on Integer.intValue() where nothing is nullable.
        val book = englishBook
        val leaves: Int = book?.leafCount ?: catalog.pageCount
        val leaf: Int = book?.leafOfVerse(surahId, ayah, page) ?: (page - 1)
        leaf.coerceIn(0, (leaves - 1).coerceAtLeast(0))
    }
    // Keyed on whether there is a catalog at all, so the state is built once
    // and built knowing where the book opens.
    //
    // `initialPage` is read exactly once, at construction. Left at 0 with a
    // LaunchedEffect to correct it, the pager mounted al-Fatihah first and
    // started that leaf's fade before jumping — so opening al-Kahf flashed the
    // wrong page every time, and no amount of catalog warmth could fix it,
    // because by then the state existed. MushafPager itself is not composed
    // until `mushafUi` is non-null, which is the same composition this key
    // flips on: the first leaf ever mounted is the right one.
    val mushafPagerState = key(mushafCatalog != null, englishBook != null) {
        rememberPagerState(
            initialPage = mushafOpeningPage ?: 0,
            pageCount = { englishBook?.leafCount ?: mushafCatalog?.pageCount ?: 1 },
        )
    }
    // Where a dial scrub landed. The reader owns the pager, so the dial hands
    // a leaf back rather than scrolling it itself; the effect is the third and
    // last writer of the pager's position.
    var mushafSeekPage by remember { mutableStateOf<Int?>(null) }
    var mushafSeekSurahId by remember { mutableStateOf<Int?>(null) }
    // Separate physical contact from landing work: every hand fades the folio,
    // but only a confirmed distant landing parks expensive neighbour leaves.
    val mushafScrubbing = remember { mutableStateOf(false) }
    val mushafDialLanding = remember { mutableStateOf(false) }
    LaunchedEffect(mushafSeekPage) {
        val target = mushafSeekPage ?: return@LaunchedEffect
        val catalog = mushafCatalog ?: return@LaunchedEffect
        // A dial release must move the book at once. MushafPage already loads
        // a missing QCF face off the main thread; awaiting it here pins the
        // old leaf under the released comb until that load finishes.
        val leaves = englishBook?.leafCount ?: catalog.pageCount
        // The dial hands back what it counts: a leaf in English, a page in
        // Arabic.
        val landing = if (englishBook != null) target - 1 else pageLeaf(target)
        mushafPagerState.scrollToPage(landing.coerceIn(0, leaves - 1))
        mushafSeekPage = null
    }
    // Later navigation only: a chapter opened from the index while the reader
    // is already on a leaf. The opening leaf itself arrives as `initialPage`
    // above, so this no-ops on the way in rather than turning the page to
    // where it already is. The target's face is warmed before the jump: a
    // leaf composing without a resident face holds blank for its face wait
    // and then fades in, which read as the whole screen flashing out and
    // back when play loaded another chapter.
    val activityContext = LocalContext.current
    LaunchedEffect(mushafOpeningPage) {
        val page = mushafOpeningPage ?: return@LaunchedEffect
        if (mushafPagerState.currentPage == page) return@LaunchedEffect
        val catalog = mushafCatalog ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            MushafQcfFonts.preload(
                activityContext,
                mushafFontPreloadPages(page, catalog.pageCount),
            )
        }
        mushafPagerState.scrollToPage(page)
    }
    val bookmarkedAyahs by viewModel.bookmarkedAyahs.collectAsStateWithLifecycle()
    // Like bookmarkedAyahs: read per-ayah so a note change recomposes only
    // that one block.
    val annotationsForSurah = viewModel.annotationsForSurah.collectAsStateWithLifecycle()
    // Saveable: an in-progress note must survive rotation and process death —
    // it is the one piece of user data with no other copy anywhere. The draft
    // carries its own (surah, ayah) so a chapter advance mid-edit can never
    // land it on the verse that happens to be loaded when it commits.
    var editingAnnotationSurah by rememberSaveable { mutableStateOf(0) }
    var editingAnnotationAyah by rememberSaveable { mutableStateOf(0) }
    var editingAnnotationText by rememberSaveable { mutableStateOf("") }
    var bookmarkNoteTipSurah by rememberSaveable { mutableIntStateOf(0) }
    var bookmarkNoteTipAyah by rememberSaveable { mutableIntStateOf(0) }
    var bookmarkNoteTipOpen by rememberSaveable { mutableStateOf(false) }
    var bookmarkNoteTipRendered by remember { mutableStateOf(false) }
    var bookmarkNoteTipRibbonCenterY by remember { mutableFloatStateOf(Float.NaN) }
    var ayahRailTipOpen by rememberSaveable { mutableStateOf(false) }
    var ayahRailTipRendered by remember { mutableStateOf(false) }
    var ayahRailTipCenterY by remember { mutableFloatStateOf(Float.NaN) }
    /**
     * Commits the open draft and closes the editor. Called when the field loses
     * focus, *before* opening another verse's note (so a draft is never carried
     * across verses), and when the reader leaves the sheet.
     */
    fun commitOpenAnnotation() {
        if (editingAnnotationAyah == 0) return
        viewModel.writeAnnotation(editingAnnotationSurah, editingAnnotationAyah, editingAnnotationText)
        editingAnnotationSurah = 0
        editingAnnotationAyah = 0
        editingAnnotationText = ""
    }
    // Turning the sheet commits — paper has no Save button (docs/ANNOTATIONS.md).
    val openAnnotation = rememberUpdatedState(
        Triple(editingAnnotationSurah, editingAnnotationAyah, editingAnnotationText),
    )
    DisposableEffect(Unit) {
        onDispose {
            val (surah, ayah, text) = openAnnotation.value
            if (ayah != 0) viewModel.writeAnnotation(surah, ayah, text)
        }
    }
    val focusManager = LocalFocusManager.current

    val listState = rememberLazyListState()
    // Two ways out of the editor besides Done, both meaning "I've stopped
    // writing": dismissing the keyboard, and moving the page. Without the
    // first the field keeps focus and its caret blinks on a page with no
    // keyboard; without the second the keyboard rides over the verses the
    // reader is trying to scroll to. Clearing focus commits through the
    // field's own focus-loss path.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible, editingAnnotationAyah) {
        if (!imeVisible && editingAnnotationAyah != 0) focusManager.clearFocus()
    }
    val listDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(
        listDragged,
        editingAnnotationAyah,
        bookmarkNoteTipOpen,
        ayahRailTipOpen,
    ) {
        if (listDragged && editingAnnotationAyah != 0) focusManager.clearFocus()
        if (listDragged && bookmarkNoteTipOpen) {
            viewModel.dismissBookmarkNoteTip()
            bookmarkNoteTipOpen = false
        }
        if (listDragged && ayahRailTipOpen) {
            viewModel.dismissAyahRailTip()
            ayahRailTipOpen = false
        }
    }
    // Gilding sheen: light catches the header rosette as the page moves.
    // At chapter end (scrolled) sheen is bright (~0.85); cold open at the top
    // rests dimmer (~0.15). Next-chapter advance pins the bright value for the
    // whole fly and **keeps** it after landing so the medallion stays lit.
    fun scrollSheenValue(): Float =
        if (listState.firstVisibleItemIndex == 0) {
            0.15f + 0.7f *
                (listState.firstVisibleItemScrollOffset / 900f).coerceIn(0f, 1f)
        } else {
            0.85f
        }
    val sheenAnim = remember { Animatable(0.15f) }
    var sheenFollowScroll by remember { mutableStateOf(true) }
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        sheenFollowScroll,
    ) {
        if (!sheenFollowScroll) return@LaunchedEffect
        sheenAnim.snapTo(scrollSheenValue())
    }
    val sheen = remember { derivedStateOf { sheenAnim.value } }
    // Follow / jump / annotation precedence — pure rules in ReaderInteraction.
    var didInitialScroll by rememberSaveable { mutableStateOf(false) }
    var interaction by remember {
        mutableStateOf(
            ReaderInteraction.initialState(
                requestedAyah = startAyah.takeUnless { didInitialScroll },
                isThisSurahPlaying = playerState.nowPlaying?.surahId == surahId,
                isPlaying = playerState.isPlaying,
                playbackAyah = playerState.nowPlaying?.ayah,
                playbackRequested = startPlaybackRequested,
            ),
        )
    }
    fun dispatch(event: ReaderInteractionEvent) {
        interaction = ReaderInteraction.reduce(interaction, event)
    }
    val followEnabled = interaction.followEnabled
    val requestedJumpAyah = interaction.pendingJumpAyah
    var showRepeatDialog by remember { mutableStateOf(false) }
    /** True while the repeat bleed is still on screen (including close wash). */
    var repeatRendered by remember { mutableStateOf(false) }
    var retainedRepeatChoice by rememberSaveable { mutableStateOf<RepeatChoice?>(null) }
    val bookmarkNoteTipVisible = bookmarkNoteTipOpen &&
        bookmarkNoteTipSurah != 0 && bookmarkNoteTipAyah != 0
    val ayahRailTipVisible = ayahRailTipOpen && ayahRailTipCenterY.isFinite()
    // Guide **Got it** rests near the playback fold; mute transport while open
    // so an overlapping thumb cannot fire Repeat / Play instead of dismiss.
    val contextualGuideOpen = bookmarkNoteTipOpen || ayahRailTipOpen
    LaunchedEffect(settings.developerModeEnabled, settings.educationGuidesEnabled) {
        if (!settings.developerModeEnabled || !settings.educationGuidesEnabled) {
            bookmarkNoteTipOpen = false
            ayahRailTipOpen = false
        }
    }
    val haptics = LocalHapticFeedback.current
    val onRootReturnUserMovedLatest = rememberUpdatedState(onRootReturnUserMoved)
    // Continuous next-chapter advance: fly the opening from footer → header.
    var chapterAdvancing by remember { mutableStateOf(false) }
    val headerMorph = remember { Animatable(0f) }
    val flyProgress = remember { Animatable(0f) }
    /** Flyer opacity — fades out on handoff so removal is never a snap. */
    val flyerAlpha = remember { Animatable(1f) }
    /** In-list opening fade while the flyer carries the medallion/title. */
    val openingInListAlpha = remember { Animatable(1f) }
    /** Real SurahHeader fade-in under the departing flyer. */
    val realHeaderAlpha = remember { Animatable(1f) }
    var flyingHeader by remember { mutableStateOf<FlyingChapterHeader?>(null) }
    /** Latest opening-block root Y from the footer (for the fly animation). */
    var footerOpeningRootY by remember { mutableFloatStateOf(Float.NaN) }
    var readerRootY by remember { mutableFloatStateOf(0f) }
    /**
     * 0 = verse body parked after a chapter handoff; 1 = settled.
     * Next-chapter parks below and rises; previous-chapter parks above and
     * settles downward when [verseEnterFromAbove] is true.
     */
    val verseReveal = remember { Animatable(1f) }
    /** Surah the delayed verse motion belongs to; 0 = none. */
    var verseRevealForSurah by remember { mutableIntStateOf(0) }
    /** When true, parked verses sit above the header and animate downward. */
    var verseEnterFromAbove by remember { mutableStateOf(false) }
    // Normal navigation (not continuous handoff): restore scroll-linked sheen.
    // Advance pins bright gold and leaves sheenFollowScroll false on purpose.
    LaunchedEffect(surahId) {
        if (!chapterAdvancing && verseRevealForSurah == 0) {
            sheenFollowScroll = true
            sheenAnim.snapTo(scrollSheenValue())
        }
    }
    val chapterAdvanceEasing = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
    // Bottom overscroll fills the Continue pill (0..1). Release at full opens.
    var nextChapterPull by remember { mutableFloatStateOf(0f) }
    var nextChapterPullArmed by remember { mutableStateOf(false) }
    // Top overscroll fills the Previous invitation (0..1). Release at full opens.
    var previousChapterPull by remember { mutableFloatStateOf(0f) }
    var previousChapterPullArmed by remember { mutableStateOf(false) }
    /** True when the current pointer gesture began docked at a chapter edge. */
    var gestureBeganAtChapterTop by remember { mutableStateOf(false) }
    var gestureBeganAtChapterBottom by remember { mutableStateOf(false) }
    /** 0 = idle/settled; 1 = current page fully exited downward (prev advance). */
    val previousPageExit = remember { Animatable(0f) }
    /** Rubber-band lift captured at previous-advance release. */
    var previousExitStartRubberPx by remember { mutableFloatStateOf(0f) }
    /** 0 = new previous chapter entering from above; 1 = settled. */
    val previousPageEnter = remember { Animatable(1f) }
    /**
     * Top-bar chapter title pinned at advance start so the previous surah
     * name can fade out instead of vanishing when the list remounts.
     */
    var pinnedTopNavTitle by remember {
        mutableStateOf<Triple<Int, String, String>?>(null)
    }

    // In-surah English search: matches are ayahs whose translation or any
    // word gloss contains the query.
    val search = rememberSurahSearchState()
    val activeQuery = search.activeQuery
    val searchMatches = remember(uiState.content, activeQuery) {
        val content = uiState.content
        if (activeQuery == null || content == null) {
            emptyList()
        } else {
            content.ayahs.filter { a ->
                a.translation.contains(activeQuery, ignoreCase = true) ||
                    a.words.any { it.translation.contains(activeQuery, ignoreCase = true) }
            }.map { it.number }
        }
    }
    val currentMatch = search.index.coerceIn(0, (searchMatches.size - 1).coerceAtLeast(0))

    // The chapter actually on the page, which is not always the navigation
    // argument: a mushaf tap past a surah boundary loads that surah in place,
    // without renavigating (the pager must stay on the leaf the reader is
    // looking at). Transport, ink and follow all key off what is rendered —
    // comparing against the stale argument leaves the play button convinced
    // nothing of "this" surah is playing, so pause restarts instead of pausing.
    val renderedSurahId = uiState.content?.surah?.id ?: surahId
    val isThisSurahPlaying = playerState.nowPlaying?.surahId == renderedSurahId
    val playingNow = isThisSurahPlaying && playerState.isPlaying
    val parkedPlaceAyah = parkedPlace.ayahIn(renderedSurahId)
    val placeUnfurlAyah = placeUnfurlTarget.ayahIn(renderedSurahId)
    var playedHere by remember(renderedSurahId) { mutableStateOf(playingNow) }
    val pausedPlaceAyah = pausedReadingPlaceRibbonAyah(
        renderedSurahId = renderedSurahId,
        mediaSurahId = playerState.nowPlaying?.surahId,
        mediaAyah = playerState.nowPlaying?.ayah,
        isPlaying = playerState.isPlaying,
        isBuffering = playerState.isBuffering,
        playedHere = playedHere,
    )
    LaunchedEffect(playingNow, pausedPlaceAyah) {
        if (playingNow) {
            playedHere = true
        } else if (pausedPlaceAyah != null) {
            // Ignore Media3's brief non-playing dip between repeat items. The
            // ribbon arrives with the chrome only after the pause holds.
            delay(350)
            val place = ReadingPlace(renderedSurahId, pausedPlaceAyah)
            parkedPlace = place
            viewModel.onPausedAyah(place.surahId, place.ayah)
            placeUnfurlTarget = place
            placeUnfurlToken++
            playedHere = false
        }
    }
    // Lead-adjusted: crosses to the next ayah ~500ms before the current one's
    // audio ends, so the block fade to the next ayah starts a touch early.
    val activeAyahState = viewModel.activeAyah.collectAsStateWithLifecycle()
    val activeBasmalah by viewModel.activeBasmalah.collectAsStateWithLifecycle()
    val activeAyah = if (isThisSurahPlaying) activeAyahState.value else null

    // When a repeat range loops back, the player dips out of "playing" for a
    // frame or two before it resumes at the range's start. Debounce that so the
    // receded chrome / status-bar overlay hold steady across the restart instead
    // of flashing in — only a genuine, sustained pause brings the chrome back.
    var recitingActive by remember { mutableStateOf(playingNow) }
    LaunchedEffect(playingNow) {
        if (playingNow) {
            recitingActive = true
        } else {
            delay(350)
            recitingActive = false
        }
    }
    val view = LocalView.current
    DisposableEffect(view, recitingActive, keepStatusBarVisible) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (recitingActive) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Immersive reading hides the status bar — in the mushaf too, where the
        // leaf reserves its top inset from statusBarsIgnoringVisibility, so the
        // page holds still as the clock goes. (It was held back while the mushaf
        // still had a gilt frame, which jumped into the cutout when the bar
        // left; the frame is gone.) The one exception is the Timings Lab sheet
        // riding over this reader: the Lab is a workbench, and its playback must
        // not push the clock off its own header.
        if (recitingActive && !keepStatusBarVisible) {
            controller?.hide(WindowInsetsCompat.Type.statusBars())
            controller?.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    val readerItems = remember(uiState.content, uiState.nextSurah) {
        val c = uiState.content
        if (c == null) emptyList() else buildList {
            // Header is the true list top so a fling from mid-chapter lands on
            // it cleanly. Previous-chapter pull is overscroll-only, and only
            // from a gesture that *began* already at this top.
            add(LazyItem.Header)
            // Own list item so the focus engine can home / place / return onto
            // the calligraphy the same way it does for any verse — not buried
            // inside the taller surah-header geometry.
            if (surahOpensWithBasmalahPreface(c.surah.id)) {
                add(LazyItem.Basmalah)
            }
            var lastPage = 0
            c.ayahs.forEachIndexed { i, ayah ->
                val page = ayah.page
                if (page != 0 && page != lastPage && lastPage != 0) {
                    add(LazyItem.PageDivider(page))
                }
                lastPage = page
                add(LazyItem.AyahItem(i))
            }
            if (uiState.nextSurah != null) {
                add(LazyItem.NextChapter)
            }
        }
    }

    // Maps between focus targets and their slot in the lazy item list, so the
    // focus engine can resolve either direction cheaply. Ayah numbers are
    // 1-based; [BASMALAH_PLAYLIST_AYAH] (0) maps to the dedicated basmalah
    // item that sits above ayah 1 on preface chapters.
    val itemIndexOfAyah = remember(readerItems) {
        buildMap {
            readerItems.forEachIndexed { index, item ->
                when (item) {
                    LazyItem.Basmalah -> put(BASMALAH_PLAYLIST_AYAH, index)
                    is LazyItem.AyahItem -> put(item.ayahIndex + 1, index)
                    LazyItem.Header, is LazyItem.PageDivider, LazyItem.NextChapter -> Unit
                }
            }
        }
    }
    val ayahNumberByItemIndex = remember(readerItems) {
        buildMap {
            readerItems.forEachIndexed { index, item ->
                // Ayahs only — basmalah is a focus target via itemIndexOfAyah
                // but must not enter the rail readout (1..N).
                if (item is LazyItem.AyahItem) put(index, item.ayahIndex + 1)
            }
        }
    }
    // The last ayah number in the surah — the highest position the reading
    // marker can reach. Derived once from the (stable) item list.
    val lastAyahNumber = remember(readerItems) {
        readerItems.count { it is LazyItem.AyahItem }.coerceAtLeast(1)
    }
    // Bottom reading band above the player bar / edge fade. Used as the focus
    // engine's bottom guard (verse anchors never park lines there) and as the
    // word-follow band (active words are lifted clear of it). Top band margin
    // stays 0 so short-verse top anchors are never fought.
    val density = LocalDensity.current
    val wordBandBottomMarginPx = with(density) { ActiveWordBottomMargin.toPx() }
    val wordBandBottomGuardPx = with(density) { ActiveWordBottomMargin.roundToPx() }
    // The one authority over where verses sit and how the reader scrolls to
    // them: jumps from the selector, recitation-follow, word-band follow, the
    // initial settle, and return-to-verse all route through this, so nothing
    // fights the list state.
    val focusController = rememberReaderFocusController(
        listState = listState,
        itemIndexOfAyah = itemIndexOfAyah,
        ayahNumberByItemIndex = ayahNumberByItemIndex,
        lastAyahNumber = lastAyahNumber,
        bottomGuardPx = wordBandBottomGuardPx,
    )
    var initialFocusSettled by remember { mutableStateOf(false) }
    var restoreFocusGeneration by remember { mutableIntStateOf(1) }
    var wordFocusRequest by remember { mutableStateOf<WordFocusRequest?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Composition can attach before Activity.onResume (cold open) or while
        // already resumed (opening the reader from another sheet). Generation 1
        // owns that initial restore; only later foreground resumes need a new one.
        var currentResumeSeen =
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (currentResumeSeen) restoreFocusGeneration++ else currentResumeSeen = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val scope = rememberCoroutineScope()
    val onKeepWordInView: (() -> Pair<Float, Float>?) -> Unit = remember(
        focusController,
        wordBandBottomMarginPx,
        wordFocusRequest,
    ) {
        val request = wordFocusRequest
        { measure ->
            scope.launch {
                val measured = focusController.keepWordInView(
                    // Bottom-only: lift words clear of the play-bar fold; do not
                    // pull short verses down from their reading-line anchor.
                    bandTopMarginPx = 0f,
                    bandBottomMarginPx = wordBandBottomMarginPx,
                    measureInViewport = measure,
                )
                if (measured && wordFocusRequest == request) wordFocusRequest = null
            }
        }
    }
    val onKeepAnnotationInView:
        suspend (Float, Float, () -> Pair<Float, Float>?) -> Unit = remember(focusController) {
            { keyboardOverlapPx, keyboardPaddingPx, measure ->
                focusController.keepAnnotationInView(
                    keyboardOverlapPx = keyboardOverlapPx,
                    keyboardPaddingPx = keyboardPaddingPx,
                    measureInViewport = measure,
                )
            }
        }
    // The verse at the reading line, and the continuous position through the
    // surah — the single read-out the rail, the return control, and the play
    // target all share.
    val scrolledAyah = focusController.focusedAyah
    val scrolledAyahPosition = focusController.focusedPosition

    // Track the verse under the reading line for Assistant "bookmark this".
    LaunchedEffect(scrolledAyah.value, surahId) {
        val ayah = scrolledAyah.value
        if (ayah >= 1) viewModel.onAyahBecameActive(ayah)
    }

    // While reciting, chrome recedes into the paper — the words and core
    // transport controls stay present. Read inside graphicsLayer / Canvas
    // draw blocks so the fade is draw-phase-only (docs/PERFORMANCE.md).
    val chromeAlpha = animateFloatAsState(
        targetValue = if (recitingActive) 0.08f else 1f,
        animationSpec = tween(ChromeRecedeMs, easing = FastOutSlowInEasing),
        label = "chromeAlpha",
    )
    val topBarAlpha = animateFloatAsState(
        targetValue = if (recitingActive) 0f else 1f,
        animationSpec = tween(ChromeRecedeMs, easing = FastOutSlowInEasing),
        label = "topBarAlpha",
    )

    val onInkOverlayVisibilityChangeLatest = rememberUpdatedState(onInkOverlayVisibilityChange)
    // Union of reader-owned ink surfaces. Report open *and* still-rendered so
    // MainActivity keeps stackGesturesBlocked through the close wash (same
    // pattern as ShareHost + shareSendRendered).
    LaunchedEffect(
        showRepeatDialog,
        repeatRendered,
        bookmarkNoteTipVisible,
        bookmarkNoteTipRendered,
        ayahRailTipVisible,
        ayahRailTipRendered,
    ) {
        onInkOverlayVisibilityChangeLatest.value(
            showRepeatDialog || repeatRendered ||
                bookmarkNoteTipVisible || bookmarkNoteTipRendered ||
                ayahRailTipVisible || ayahRailTipRendered,
        )
    }
    DisposableEffect(Unit) {
        onDispose { onInkOverlayVisibilityChangeLatest.value(false) }
    }
    // System Back must dismiss the bleed, not pop the paper stack beneath it
    // (MainActivity's stack BackHandlers fire otherwise — see overlay backs there).
    BackHandler(enabled = showRepeatDialog) { showRepeatDialog = false }
    BackHandler(enabled = bookmarkNoteTipVisible) {
        viewModel.dismissBookmarkNoteTip()
        bookmarkNoteTipOpen = false
    }
    BackHandler(enabled = ayahRailTipVisible) {
        viewModel.dismissAyahRailTip()
        ayahRailTipOpen = false
    }

    // Reading by hand pauses the follow mode via pointerInput.

    val statusBarTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    // Where the reciting focus target sits relative to its ideal focus, and
    // whether it is taller than the screen — the return-to-verse control reads
    // the former, the word-level follow gate reads the latter. Both watch
    // layoutInfo, so they recompute only when their answer actually changes.
    // The camera follows the media item, not the fade-led ink target: lifting
    // the next ayah's recess early must not move the page before its audio.
    // During the basmalah lead-in the target is ayah 0 (its dedicated item).
    val listeningAyah = playerState.nowPlaying?.ayah
    val playbackFocusTarget = FocusEngine.playbackFocusTarget(
        playingAyah = listeningAyah?.takeIf { it >= 1 && isThisSurahPlaying },
        activeBasmalah = isThisSurahPlaying && activeBasmalah == true,
    )
    val activeAyahPlacement = remember(playbackFocusTarget) {
        derivedStateOf { focusController.placementOf(playbackFocusTarget) }
    }

    // A fresh query restarts from its first match…
    LaunchedEffect(activeQuery) { search.index = 0 }
    // …and the sheet glides to whichever match is current.
    LaunchedEffect(searchMatches, currentMatch, mushafMode, mushafCatalog) {
        val target = searchMatches.getOrNull(currentMatch) ?: return@LaunchedEffect
        dispatch(ReaderInteractionEvent.SearchNavigated)
        val catalog = mushafCatalog
        if (mushafMode && catalog != null) {
            val leaves = englishBook?.leafCount ?: catalog.pageCount
            val onPage = catalog.pageOf(renderedSurahId, target, 1)
            val page = (englishBook?.leafOfVerse(renderedSurahId, target, onPage) ?: (onPage - 1))
                .coerceIn(0, leaves - 1)
            withContext(Dispatchers.Default) {
                MushafQcfFonts.preload(
                    activityContext,
                    mushafFontPreloadPages(page, catalog.pageCount),
                )
            }
            mushafPagerState.animateScrollToPage(page)
        } else if (!mushafMode) {
            focusController.focus(target, animate = true, preRoll = true)
        }
    }

    LaunchedEffect(requestedJumpAyah) {
        val content = uiState.content ?: return@LaunchedEffect
        val request = requestedJumpAyah
            .takeIf { it > 0 }
            ?: return@LaunchedEffect
        val target = request.coerceIn(1, content.surah.ayahCount)
        // Do NOT clear pendingJump before focus() finishes: this effect is
        // keyed on it, so settling early cancels the coroutine mid-slide and the
        // jump reads as a pop. Clear in finally once the approach has landed (or
        // a newer jump has superseded this one). Follow was already set by
        // JumpRequested via the arbiter.
        // Focus only — Continue Listening updates when audio plays this verse.
        viewModel.onAyahBecameActive(target)
        if (isThisSurahPlaying) viewModel.player.seekToAyah(target)
        try {
            focusController.focus(target, animate = true, preRoll = true)
        } finally {
            dispatch(ReaderInteractionEvent.JumpSettled(request))
        }
    }

    /** The first ayah of the loaded chapter on the leaf in view, or null when
     * the reader is not on a leaf. */
    // Read in the draw/derived phase, not in the reader's own scope: reading
    // pagerState.currentPage directly here recomposed the entire reader body on
    // every settled page, which is exactly what MushafPager takes such care to
    // avoid by handing playback down as one State.
    // What the dial names. The English book has its own pagination — a Madinah
    // page takes as many leaves as its English needs — so the rule counts
    // *leaves* there and Madinah pages on the Arabic leaf, and the folio says
    // the same number the dial does.
    val mushafLeafPage = remember(mushafCatalog, englishBook) {
        {
            if (englishBook != null) {
                mushafPagerState.currentPage + 1
            } else {
                leafPage(mushafPagerState.currentPage)
            }
        }
    }
    // The leaves that open a chapter, walked once when the catalog arrives.
    // These are the dial's coarse tier: it keeps drawing them after the single
    // leaves have closed up, so a hand moving at a normal pace is steering by
    // chapters and a hand that slows down is steering by leaves — one comb at
    // two magnifications.
    val mushafChapterFirstPages = remember(mushafCatalog, englishBook) {
        val catalog = mushafCatalog ?: return@remember IntArray(0)
        IntArray(114) { idx ->
            val surahId = idx + 1
            val page = catalog.firstPageOf(surahId)
            // A chapter opens where its first verse is *set*, which on the
            // English book is a leaf partway through a Madinah page as often
            // as not.
            englishBook?.let { it.leafOfVerse(surahId, 1, page) + 1 } ?: page
        }
    }
    // What the dial writes over its thumb. The scrubbed leaf almost never
    // belongs to the chapter that is loaded, so this reads the leaf's own
    // chapter rather than the reader's — and it carries the run of verses the
    // leaf holds, which is what the label says once the dial has zoomed in far
    // enough for a single leaf to be a thing you can aim at.
    val mushafPageLabel = remember(mushafCatalog, mushafUi, englishBook) {
        label@{ page: Int, requestedSurahId: Int? ->
            val catalog = mushafCatalog ?: return@label null
            // Whatever the rule is counting: a Madinah page's verses, or one
            // English leaf's. The label names what is actually on that paper.
            val keys = englishBook?.leaf(page - 1)?.verses
                ?: catalog.page(page)?.ayahKeys?.toList()
                ?: return@label null
            if (keys.isEmpty()) return@label null
            val surahId = requestedSurahId?.takeIf { requested ->
                keys.any { it.first == requested }
            } ?: keys.first().first
            val surah = mushafUi?.surahsById?.get(surahId) ?: return@label null
            var fromAyah = Int.MAX_VALUE
            var toAyah = Int.MIN_VALUE
            keys.forEach { (id, ayah) ->
                if (id == surahId) {
                    fromAyah = minOf(fromAyah, ayah)
                    toAyah = maxOf(toAyah, ayah)
                }
            }
            MushafDialLabel(
                number = surahId,
                chapter = surah.nameTransliteration,
                fromAyah = fromAyah.takeUnless { it == Int.MAX_VALUE } ?: 1,
                toAyah = toAyah.takeUnless { it == Int.MIN_VALUE } ?: 1,
            )
        }
    }
    val mushafChapterLabel = remember(mushafUi) {
        label@{ idx: Int ->
            val surah = mushafUi?.surahsById?.get(idx + 1) ?: return@label null
            MushafDialLabel(
                number = idx + 1,
                chapter = surah.nameTransliteration,
                fromAyah = 1,
                toAyah = surah.ayahCount,
            )
        }
    }
    val mushafLeafSurahId = remember(mushafCatalog, englishBook) {
        derivedStateOf {
            mushafCatalog?.page(leafPage(mushafPagerState.currentPage))?.primarySurahId
        }
    }

    fun mushafScrolledAyah(): Int? {
        if (!mushafMode) return null
        val catalog = mushafCatalog ?: return null
        val page = catalog.page(leafPage(mushafPagerState.currentPage)) ?: return null
        return page.ayahKeys
            .filter { it.first == renderedSurahId }
            .minOfOrNull { it.second }
    }

    /**
     * Where play should start for the leaf on screen, or null to resume.
     * The playhead is offered as the held verse only while this chapter is the
     * one loaded, since that is the only chapter whose verse numbers the leaf's
     * keys can be compared against.
     */
    fun mushafPlayTarget(): ReaderInteraction.MushafPlayTarget? {
        val catalog = mushafCatalog ?: return null
        val leaf = catalog.page(mushafPagerState.currentPage + 1) ?: return null
        val scrubbedSurah = mushafSeekSurahId?.takeIf { sid ->
            leaf.surahStarts.any { it.surahId == sid } || leaf.ayahKeys.any { it.first == sid }
        }
        if (scrubbedSurah != null) {
            val firstOfScrubbed = leaf.lines
                .flatMap { it.tokens }
                .firstOrNull { it.surahId == scrubbedSurah }
            if (firstOfScrubbed != null) {
                // A scrub explicitly chose a surah whose opening leaf this is;
                // honour that choice instead of the page's overall first word,
                // which may belong to the previous surah on a shared leaf.
                return ReaderInteraction.MushafPlayTarget(
                    scrubbedSurah, firstOfScrubbed.ayah, firstOfScrubbed.word.position,
                )
            }
        }
        val first = leaf.lines.firstOrNull { it.tokens.isNotEmpty() }?.tokens?.firstOrNull()
        return ReaderInteraction.mushafPlayTarget(
            pendingJumpAyah = requestedJumpAyah,
            loadedSurahId = renderedSurahId,
            heldAyah = activeAyah.takeIf { isThisSurahPlaying },
            leafFirstWord = first?.let {
                ReaderInteraction.MushafPlayTarget(it.surahId, it.ayah, it.word.position)
            },
            leafAyahs = leaf.ayahKeys,
        )
    }

    fun selectedPlaybackAyah(): Int {
        val ayahCount = uiState.content?.surah?.ayahCount ?: return startAyah ?: 1
        return ReaderInteraction.selectedPlaybackAyah(
            state = interaction,
            isThisSurahPlaying = isThisSurahPlaying,
            activeAyah = activeAyah,
            // Where the reader is looking. On a scrolling page that is the
            // focused verse of the list; on a leaf the list is never composed,
            // so its focus stays pinned at the first verse and pressing play on
            // page forty of al-Baqarah began the chapter again — and follow
            // then turned the leaf back to page two. A leaf answers with the
            // first verse it carries of the chapter being read.
            scrolledAyah = mushafScrolledAyah() ?: scrolledAyah.value,
            fallbackAyah = startAyah ?: 1,
            ayahCount = ayahCount,
        )
    }


    // Lyric-style auto scroll: the focus engine keeps the active target
    // anchored — a verse's whole body if it fits, its top pinned if taller than
    // the screen, or the surah-header basmalah while the chapter-opening
    // lead-in plays. Word-level following then carries the eye through a tall
    // verse. The very first scroll after follow turns back on (return-to-verse,
    // or pressing play from a scrolled-away spot) is a deliberate jump, so it
    // gets the pre-roll slide; boundary-to-boundary tracking after that stays
    // smooth. Playback follow is gated by [ReaderInteraction.shouldFollowPlayback]
    // and by the Ink Lab's session-only [InkEngine.focusEngineEnabled] freeze.
    //
    // Do **not** key this effect on isPlaying: pause/play (and brief seek
    // buffering) would re-home a tall verse to its top anchor, then word-follow
    // would scroll back down to the active word — the up/down/up stutter.
    val labFocusEnabled = InkEngine.focusEngineEnabled
    val followPlayback =
        ReaderInteraction.shouldFollowPlayback(interaction) && labFocusEnabled
    // Each layout hands the other the location under the reader. Without this,
    // switching to the mushaf reopened the route's original leaf, while
    // switching back exposed the hidden list's stale position.
    var lastScrollAyah by remember(renderedSurahId) {
        mutableIntStateOf(
            startAyah?.coerceAtLeast(1) ?: scrolledAyah.value.coerceAtLeast(1),
        )
    }
    LaunchedEffect(mushafMode, renderedSurahId) {
        if (!mushafMode) {
            snapshotFlow { scrolledAyah.value }
                .collect { ayah -> if (ayah >= 1) lastScrollAyah = ayah }
        }
    }
    var previousMushafMode by remember { mutableStateOf(mushafMode) }
    LaunchedEffect(mushafMode, mushafCatalog, renderedSurahId) {
        if (previousMushafMode == mushafMode) return@LaunchedEffect
        val catalog = mushafCatalog
        if (mushafMode && catalog == null) return@LaunchedEffect
        previousMushafMode = mushafMode
        if (mushafMode) {
            catalog ?: return@LaunchedEffect
            val word = activeWordState.value.takeIf {
                followPlayback && isThisSurahPlaying
            }
            val targetPage = when {
                word != null -> catalog.readingPageOf(
                    renderedSurahId,
                    word.ayah,
                    word.wordPosition,
                    wholeVerses = mushafWholeVerses,
                )
                followPlayback && isThisSurahPlaying && activeBasmalah == true ->
                    catalog.firstPageOf(renderedSurahId)
                followPlayback && isThisSurahPlaying && activeAyah != null ->
                    catalog.pageOf(renderedSurahId, activeAyah, 1)
                else -> catalog.pageOf(renderedSurahId, lastScrollAyah, 1)
            }
            val leaves = englishBook?.leafCount ?: catalog.pageCount
            val targetAyah = word?.ayah ?: activeAyah ?: lastScrollAyah
            val page = (englishBook?.leafOfVerse(renderedSurahId, targetAyah, targetPage)
                ?: (targetPage - 1)).coerceIn(0, leaves - 1)
            withContext(Dispatchers.Default) {
                MushafQcfFonts.preload(
                    activityContext,
                    mushafFontPreloadPages(page, catalog.pageCount),
                )
            }
            mushafPagerState.scrollToPage(page)
        } else {
            val target = if (followPlayback && isThisSurahPlaying) {
                playbackFocusTarget
            } else {
                catalog?.page(mushafPagerState.currentPage + 1)
                    ?.ayahKeys
                    ?.filter { it.first == renderedSurahId }
                    ?.minOfOrNull { it.second }
            }
            target?.let { focusController.focus(it, animate = false) }
        }
    }
    var followWasEnabled by remember { mutableStateOf(followEnabled) }
    /** Last target we already homed onto while follow stayed on. Skips
     * re-focus when activeAyah flickers null→same during seeks. */
    var lastFollowFocusTarget by remember { mutableStateOf<Int?>(null) }
    /** Word tap whose Media3 seek has not yet named this ayah. */
    var pendingWordTapAyah by remember { mutableStateOf<Int?>(null) }

    // Continue Listening tracks recited ayahs without driving scroll.
    //
    // Keyed on the *playing media item*, never [playbackFocusTarget]: that
    // target is fade-led, so it names the next verse up to InkEngine.fadeLeadMs
    // before a note of it is heard. Persisting it meant pausing inside the lead
    // recorded a verse the listener never reached — breaking the repository's
    // "only verses actually recited" contract. Ayah 0 is the basmalah lead-in
    // and is filtered by the >= 1 guard.
    LaunchedEffect(listeningAyah, isThisSurahPlaying, playerState.isPlaying) {
        if (listeningAyah != null && listeningAyah >= 1 &&
            isThisSurahPlaying && playerState.isPlaying
        ) {
            viewModel.onListenedAyah(listeningAyah)
        }
    }

    LaunchedEffect(
        playbackFocusTarget,
        followPlayback,
        isThisSurahPlaying,
        mushafMode,
        pendingWordTapAyah,
    ) {
        if (mushafMode) return@LaunchedEffect
        if (!followPlayback) {
            followWasEnabled = false
            lastFollowFocusTarget = null
            pendingWordTapAyah = null
            return@LaunchedEffect
        }
        // Playlist finished (nowPlaying cleared at STATE_ENDED): leave the page
        // on the last word — re-homing the final verse pins its top and feels
        // like a random scroll-up at the end of the surah.
        if (!isThisSurahPlaying) return@LaunchedEffect
        val target = playbackFocusTarget ?: return@LaunchedEffect
        if (ReaderInteraction.wordTapAwaitingSeek(target, pendingWordTapAyah)) {
            return@LaunchedEffect
        }
        if (pendingWordTapAyah == target) {
            pendingWordTapAyah = null
            lastFollowFocusTarget = target
            followWasEnabled = true
            return@LaunchedEffect
        }
        val justEnabled = !followWasEnabled
        followWasEnabled = true
        if (ReaderInteraction.shouldRestoreWordBeforeVerseHome(
                verseHomeRequested = justEnabled,
                playingAyahHasLiveTallGeometry =
                    listeningAyah?.let(focusController::exceedsViewport) == true,
            )
        ) {
            // Play / return-follow inside a long ayah: the active word is the
            // destination. Never glide backward to line one before going there.
            lastFollowFocusTarget = target
            restoreFocusGeneration++
            return@LaunchedEffect
        }
        // Same verse still following: word-band keep-in-view owns the camera.
        // Re-homing here fights mid-verse position after pause/play/FF/back.
        if (!ReaderInteraction.shouldHomeOntoPlaybackTarget(
                target = target,
                justEnabledFollow = justEnabled,
                lastHomedTarget = lastFollowFocusTarget,
            )
        ) {
            return@LaunchedEffect
        }
        lastFollowFocusTarget = target
        focusController.focus(target, animate = true, preRoll = justEnabled)
    }

    // A reciter can restart the whole ayah inside one audio item. The ayah key
    // does not change, so observe the sparse word state without making the
    // screen recompose on every word and restore the verse's top anchor once.
    LaunchedEffect(isThisSurahPlaying, interaction, labFocusEnabled) {
        if (!isThisSurahPlaying || !followPlayback) {
            return@LaunchedEffect
        }
        var wasAtRepeatStart = false
        snapshotFlow { activeWordState.value }.collect { word ->
            if (!followPlayback) return@collect
            val repeatAyah = word?.takeIf {
                FocusEngine.startsFullAyahRepeat(
                    wordPosition = it.wordPosition,
                    isRepeat = it.isRepeat,
                    repeatStart = it.repeatStart,
                )
            }?.ayah
            if (repeatAyah != null && !wasAtRepeatStart) {
                focusController.focus(repeatAyah, animate = true)
            }
            wasAtRepeatStart = repeatAyah != null
        }
    }

    // Opening from "Continue listening": settle on the saved ayah once.
    LaunchedEffect(uiState.content) {
        initialFocusSettled = false
        val content = uiState.content
        if (content != null) {
            if (!didInitialScroll) {
                didInitialScroll = true
                // A different verse in this paused playlist was seeded into
                // pendingJump above; that path both focuses it and moves the
                // held media item without starting playback.
                if (requestedJumpAyah == 0 &&
                    startAyah != null && startAyah in 1..content.ayahs.size
                ) {
                    focusController.focus(startAyah, animate = false)
                }
            }
            initialFocusSettled = true
        }
    }

    // Offer the rail lesson only as a chapter settles. Enabling the developer
    // gate over an already-open reader waits for the next chapter opening.
    LaunchedEffect(
        uiState.content?.surah?.id,
        initialFocusSettled,
        ayahRailTipCenterY,
        chapterAdvancing,
        verseRevealForSurah,
    ) {
        if (!initialFocusSettled || !ayahRailTipCenterY.isFinite() ||
            chapterAdvancing || verseRevealForSurah != 0 || recitingActive
        ) {
            return@LaunchedEffect
        }
        delay(320)
        if (viewModel.shouldShowAyahRailTip() && !bookmarkNoteTipOpen) {
            ayahRailTipOpen = true
        }
    }

    // Verse focus alone is not enough for a paused long ayah: its adaptive
    // anchor deliberately shows line one, while the held word may be many
    // screens lower. On initial open, foreground resume, and display reflow,
    // restore the exact active word once. Continuous tracking remains play-only
    // so an ended playlist cannot snap from the final word back to word one.
    LaunchedEffect(
        restoreFocusGeneration,
        initialFocusSettled,
        uiState.content?.surah?.id,
        isThisSurahPlaying,
        followPlayback,
        mushafMode,
    ) {
        val content = uiState.content
        if (mushafMode || !initialFocusSettled || content == null ||
            !isThisSurahPlaying || !followPlayback
        ) {
            return@LaunchedEffect
        }
        val playlistAyah = playerState.nowPlaying?.ayah?.takeIf { it >= 1 }
            ?: return@LaunchedEffect
        // An explicit word-search open owns its requested orange hit on the
        // first pass; a later app resume may restore playback normally.
        if (restoreFocusGeneration == 1 && startWordPosition != null) return@LaunchedEffect
        // The highlight StateFlow retains its last value while the lifecycle is
        // stopped. If playback was paused from outside the app, wait through one
        // 250 ms paused poll so a same-ayah stale word cannot win this restore.
        if (!playerState.isPlaying) delay(HELD_WORD_REFRESH_MS)
        val word = snapshotFlow { activeWordState.value }
            .first { it?.ayah == playlistAyah }
            ?: return@LaunchedEffect
        if (word.ayah !in 1..content.surah.ayahCount) return@LaunchedEffect
        // If the ayah is wholly offscreen, materialize it first. When a tall
        // ayah is already attached, skip the top-anchor pass to avoid the old
        // up/down stutter and let the word correction move directly.
        if (!focusController.isLaidOut(word.ayah)) {
            focusController.focus(word.ayah, animate = false)
            withFrameNanos { }
        }
        wordFocusRequest = WordFocusRequest(
            generation = restoreFocusGeneration,
            ayah = word.ayah,
            wordPosition = word.wordPosition,
            activation = word.activation,
        )
    }

    // Home word-search hit: orange repeat wash (wash in → dissolve × 2) on the
    // matched word once the verse is on screen. The wash itself lives in the
    // word unit / Hafs bloom; this effect only gates which word is active.
    var searchFlashAyah by remember { mutableStateOf<Int?>(null) }
    var searchFlashWord by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(uiState.content?.surah?.id, startAyah, startWordPosition) {
        searchFlashAyah = null
        searchFlashWord = null
        val ayah = startAyah
        val word = startWordPosition
        val content = uiState.content
        if (ayah == null || word == null || content == null) return@LaunchedEffect
        if (ayah !in 1..content.ayahs.size) return@LaunchedEffect
        val ayahWords = content.ayahs[ayah - 1].words
        if (ayahWords.none { it.position == word }) return@LaunchedEffect
        delay(SearchHitFlash.START_DELAY_MS)
        searchFlashAyah = ayah
        searchFlashWord = word
        delay(SearchHitFlash.totalMs())
        searchFlashAyah = null
        searchFlashWord = null
    }

    // Reading-mode / display toggles reflow every ayah's height. LazyList keeps
    // the same *item index* at the top while the pinned verse drifts away under
    // the reading line — so re-home onto whichever ayah the reader was looking
    // at (or following) once the new layout has measured.
    //
    // [stickyAyah] is only updated while the layout signature is stable, so the
    // reflow composition that fires this effect still carries the pre-change
    // verse rather than the already-drifted read-out.
    val layoutSignature = listOf(
        settings.readingMode,
        settings.readingLayout,
        settings.verseNumberScript,
        settings.pageNumberScript,
        settings.showWordGloss,
        settings.showTransliteration,
        settings.showTranslation,
        settings.hideEnglishParentheticals,
        settings.fontScale,
    )
    var lastLayoutSignature by remember { mutableStateOf(layoutSignature) }
    var lastReadingLayout by remember { mutableStateOf(settings.readingLayout) }
    var stickyAyah by remember { mutableIntStateOf(1) }
    var layoutFocusSeeded by remember { mutableStateOf(false) }
    val playbackOwnsReflow = followPlayback && isThisSurahPlaying
    val latestListeningAyah = rememberUpdatedState(listeningAyah)
    SideEffect {
        if (layoutSignature == lastLayoutSignature) {
            stickyAyah = ReaderInteraction.layoutStickyAyah(
                playbackOwnsFocus = playbackOwnsReflow,
                playingAyah = listeningAyah,
                scrolledAyah = scrolledAyah.value,
            ).coerceIn(1, lastAyahNumber)
        }
    }
    LaunchedEffect(layoutSignature, playbackOwnsReflow) {
        if (!layoutFocusSeeded) {
            // First composition matches the initial settle above; don't fight it.
            layoutFocusSeeded = true
            lastLayoutSignature = layoutSignature
            lastReadingLayout = settings.readingLayout
            return@LaunchedEffect
        }
        // Layout-to-layout location transfer above owns this change. Running
        // the LazyList reflow recovery as well would restore its hidden stale
        // ayah a beat later and undo the leaf-to-scroll handoff.
        if (lastReadingLayout != settings.readingLayout) {
            lastReadingLayout = settings.readingLayout
            lastLayoutSignature = layoutSignature
            return@LaunchedEffect
        }
        // A playback-ownership change restarts an in-flight reflow recovery so
        // its older manual-reading target cannot scroll after Play. If no
        // layout change is pending, playback follow already owns the move.
        // Two frames + a short beat so sibling ayahs finish remasuring before
        // we glide against the final geometry (otherwise the home lands on a
        // height that is still shifting).
        withFrameNanos { }
        withFrameNanos { }
        delay(48)
        val playingAyah = latestListeningAyah.value
        val recovery = ReaderInteraction.layoutReflowRecovery(
            layoutChanged = layoutSignature != lastLayoutSignature,
            playbackOwnsFocus = playbackOwnsReflow,
            playingAyah = playingAyah,
            stickyAyah = stickyAyah.coerceIn(1, lastAyahNumber),
            playingAyahHasLiveTallGeometry =
                playingAyah?.let(focusController::exceedsViewport) == true,
        ) ?: return@LaunchedEffect
        if (!recovery.restoreWordDirectly) {
            focusController.focus(recovery.focusAyah, animate = true, preRoll = false)
        }
        // Tall playback ayahs go straight to their current word; other reflows
        // restore it after the verse-level anchor settles.
        restoreFocusGeneration++
        lastLayoutSignature = layoutSignature
    }

    // Errors surface as a quiet line on the sheet, then dissolve.
    LaunchedEffect(playerState.error) {
        if (playerState.error != null) {
            delay(5_000)
            viewModel.player.clearError()
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(search.active) {
        if (search.active) searchFocus.requestFocus() else keyboard?.hide()
    }
    val bookmarkTipSide = if (settings.ayahSelectorSide == AyahSelectorSide.RIGHT) {
        AyahSelectorSide.LEFT
    } else {
        AyahSelectorSide.RIGHT
    }
    val bookmarkTipHasTarget = bookmarkNoteTipRibbonCenterY.isFinite()
    val bookmarkGuidePresent = (bookmarkNoteTipVisible || bookmarkNoteTipRendered) &&
        bookmarkTipHasTarget
    val railGuidePresent = (ayahRailTipVisible || ayahRailTipRendered) &&
        ayahRailTipCenterY.isFinite()
    val contextualGuideVisible =
        (bookmarkNoteTipVisible && bookmarkTipHasTarget) || ayahRailTipVisible
    val contextualGuideRendered = bookmarkGuidePresent || railGuidePresent
    val contextualGuideTargetSide = if (bookmarkGuidePresent) {
        bookmarkTipSide
    } else {
        settings.ayahSelectorSide
    }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = topBar@{
            if (mushafMode) return@topBar
            if (editingAnnotationAyah != 0) {
                // Reclaim the app bar while writing, but keep ink below a
                // visible system status bar. Recitation already hides it.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (recitingActive) 0.dp else statusBarTop),
                )
                return@topBar
            }
            // Unread-style chrome: quiet marks that recede behind the text.
            // Once the opening header scrolls off, the surah name reappears
            // here between gilded flourishes. In search, the bar becomes the
            // search field with match navigation.
            CenterAlignedTopAppBar(
                modifier = Modifier.graphicsLayer {
                    alpha = if (search.active) 1f else topBarAlpha.value
                },
                title = {
                    if (search.active) {
                        BasicTextField(
                            value = search.query,
                            onValueChange = { search.query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (search.query.isEmpty()) {
                                        Text(
                                            text = "Find an English word…",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.5f),
                                            maxLines = 1,
                                        )
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus),
                        )
                    } else {
                        val scrolledPastHeader by remember {
                            derivedStateOf { listState.firstVisibleItemIndex > 0 }
                        }
                        val live = uiState.content?.surah
                        val pinned = pinnedTopNavTitle
                        val mushafSurah = if (mushafMode) {
                            mushafLeafSurahId.value?.let { mushafUi?.surahsById?.get(it) } ?: live
                        } else {
                            null
                        }
                        // While advancing, keep painting the pinned previous
                        // chapter so its fade-out has something to fade.
                        val displayNumber = pinned?.first
                            ?: mushafSurah?.id
                            ?: live?.takeIf { scrolledPastHeader && !chapterAdvancing }?.id
                        val displayArabic = pinned?.second
                            ?: mushafSurah?.nameArabic
                            ?: live?.takeIf { scrolledPastHeader && !chapterAdvancing }?.nameArabic
                        val displayTranslit = pinned?.third
                            ?: mushafSurah?.nameTransliteration
                            ?: live?.takeIf { scrolledPastHeader && !chapterAdvancing }
                                ?.nameTransliteration
                        val topTitleAlpha by animateFloatAsState(
                            targetValue = when {
                                // Next-chapter advance: always fade the top name away.
                                chapterAdvancing -> 0f
                                mushafMode && (mushafSurah != null || live != null) -> 1f
                                scrolledPastHeader && live != null -> 1f
                                else -> 0f
                            },
                            animationSpec = tween(
                                durationMillis = if (chapterAdvancing) 300 else 350,
                                easing = FastOutSlowInEasing,
                            ),
                            label = "topNavTitleAlpha",
                        )
                        if (
                            displayNumber != null &&
                            displayArabic != null &&
                            displayTranslit != null
                        ) {
                            Box(
                                Modifier.graphicsLayer { alpha = topTitleAlpha },
                            ) {
                                OrnateSurahTitle(
                                    chapterNumber = displayNumber,
                                    nameArabic = displayArabic,
                                    nameTransliteration = displayTranslit,
                                    sheen = sheen,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    Row {
                        IconButton(
                            onClick = { if (search.active) search.close() else onBack() },
                            enabled = search.active || !recitingActive,
                        ) {
                            Icon(
                                imageVector = when {
                                    search.active -> Icons.Rounded.Close
                                    mushafMode -> Icons.AutoMirrored.Rounded.MenuBook
                                    else -> Icons.AutoMirrored.Rounded.ArrowBack
                                },
                                contentDescription = when {
                                    search.active -> "Close search"
                                    mushafMode -> "Chapters"
                                    else -> "Back"
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.55f),
                            )
                        }
                        // Match the two trailing buttons so Material's title
                        // slot stays on the physical centre line at narrow widths.
                        if (!search.active) Spacer(Modifier.width(48.dp))
                    }
                },
                actions = {
                    if (search.active) {
                        Text(
                            text = if (searchMatches.isEmpty()) {
                                if (activeQuery == null) "" else "0/0"
                            } else {
                                "${currentMatch + 1}/${searchMatches.size}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        IconButton(
                            onClick = {
                                if (searchMatches.isNotEmpty()) {
                                    keyboard?.hide()
                                    search.index =
                                        (currentMatch - 1 + searchMatches.size) % searchMatches.size
                                }
                            },
                            enabled = searchMatches.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowUp,
                                contentDescription = "Previous match",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                if (searchMatches.isNotEmpty()) {
                                    keyboard?.hide()
                                    search.index = (currentMatch + 1) % searchMatches.size
                                }
                            },
                            enabled = searchMatches.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Next match",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        if (!mushafMode) {
                            IconButton(
                                onClick = { search.active = true },
                                enabled = !recitingActive,
                            ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = "Search in surah",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                    modifier = Modifier
                                        .offset(x = 4.dp)
                                        .size(26.dp),
                                )
                            }
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }
                        IconButton(
                            onClick = onOpenSettings,
                            enabled = !recitingActive,
                        ) {
                            Icon(
                                Icons.Rounded.Tune,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .offset(x = (-4).dp)
                                    .size(26.dp),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = bottomBar@{
            if (mushafMode) return@bottomBar
            Column {
                // Errors stay a quiet line on the sheet above the player.
                // Return-to-ayah / Back-to float above the bar (see content).
                AnimatedVisibility(
                    visible = playerState.error != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        text = playerState.error.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    )
                }
                PlayerBar(
                    state = playerState,
                    isThisSurahLoaded = isThisSurahPlaying,
                    enabled = !contextualGuideOpen,
                    chromeAlpha = { chromeAlpha.value },
                    reciterName = uiState.currentReciter?.name.orEmpty(),
                    onPlayPause = {
                        if (isThisSurahPlaying) {
                            if (playerState.isPlaying) {
                                viewModel.player.togglePlayPause()
                            } else {
                                dispatch(ReaderInteractionEvent.EnableFollow)
                                if (requestedJumpAyah > 0) {
                                    val selectedAyah = selectedPlaybackAyah()
                                    viewModel.playLoadedFromAyah(selectedAyah)
                                } else {
                                    viewModel.player.togglePlayPause()
                                }
                            }
                        } else {
                            dispatch(ReaderInteractionEvent.EnableFollow)
                            viewModel.playFromAyah(selectedPlaybackAyah())
                        }
                    },
                    onFastBackward = viewModel::fastBackward,
                    onFastForward = viewModel::fastForward,
                    onRepeatClick = { showRepeatDialog = true },
                    onSpeed = viewModel::cycleSpeed,
                    onReciterClick = onOpenSettings,
                )
            }
        },
    ) { padding ->
        val content = uiState.content
        // Not keyed on surahId: next-chapter handoff updates the sheet id while
        // the flyer is still dissolving — recreating Animatable(0) blanked the
        // whole page (including the flyer) for a frame.
        val readerContentAlpha = remember { Animatable(0f) }
        // Only fade in on cold open / external nav — never when finishing a
        // next-chapter handoff (that used to snap the settled header), and
        // never across a playback-driven chapter swap (hitting play on
        // another chapter's leaf swapped the ink under a composed page; a
        // fade there read as the whole screen flashing out and back).
        LaunchedEffect(content?.surah?.id, startAyah) {
            if (chapterAdvancing || verseRevealForSurah != 0) {
                readerContentAlpha.snapTo(1f)
                DevProfiling.mark("entranceHold s${content?.surah?.id}")
                return@LaunchedEffect
            }
            if (uiState.keepsContentThroughLoad) {
                readerContentAlpha.snapTo(1f)
                viewModel.onKeptContentCommitted()
                DevProfiling.mark("entranceHold s${content?.surah?.id}")
                return@LaunchedEffect
            }
            if (content == null) {
                DevProfiling.mark("contentNull")
                DevProfiling.mark("readerContentAlpha0")
                readerContentAlpha.snapTo(0f)
            } else {
                DevProfiling.mark("entranceFade s${content.surah.id} a$startAyah")
                readerContentAlpha.snapTo(0f)
                readerContentAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = ReaderEntranceFadeMs,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }
        // Hold full opacity while the continuous advance is in flight.
        LaunchedEffect(chapterAdvancing) {
            if (chapterAdvancing) readerContentAlpha.snapTo(1f)
        }
        if (content == null) {
            // Blank paper, not a spinner. A chapter takes a moment to come off
            // the disk, and a Material wheel spinning on the leaf is exactly the
            // chrome this reader does not have — and the wheel is what made the
            // page seem to appear out of nowhere, because the eye was fixed on
            // a moving thing that vanished the instant the text arrived. Empty
            // paper waits quietly, and the text settles onto it.
            DevProfiling.mark("blankPaper s$surahId")
            Box(Modifier.fillMaxSize())
            return@Scaffold
        }

        var ayahSelectorExpanded by remember { mutableStateOf(false) }
        var ayahSelectorDismissRequests by remember { mutableIntStateOf(0) }
        LaunchedEffect(ayahSelectorExpanded) {
            onAyahSelectorExpandedChange(ayahSelectorExpanded)
        }
        LaunchedEffect(editingAnnotationAyah) {
            if (editingAnnotationAyah != 0) ayahSelectorExpanded = false
            dispatch(ReaderInteractionEvent.SetAnnotating(editingAnnotationAyah != 0))
        }
        // Shared with settle / list rubber-band (defined before advance uses it).
        val pullRubberMaxPx = with(density) { 56.dp.toPx() }
        // Previous pull shoves the list down by this much at full fill — matches
        // the previous-chrome band height so the invitation is fully exposed.
        val previousPullRubberMaxPx = with(density) { 156.dp.toPx() }
        val previousExitScrollPx = with(density) { 360.dp.toPx() }
        // Header travel into place — enough to read as a settle, not a leap.
        val previousEnterScrollPx = with(density) { 120.dp.toPx() }

        fun advanceToPreviousChapter(prevId: Int) {
            if (chapterAdvancing) return
            val prev = uiState.previousSurah?.takeIf { it.id == prevId } ?: return
            scope.launch {
                val pullAtRelease = previousChapterPull.coerceIn(0f, 1f)
                val rubberAtRelease =
                    previousPullRubberMaxPx * sin(pullAtRelease * PI.toFloat() * 0.5f)

                chapterAdvancing = true
                previousChapterPullArmed = false
                dispatch(ReaderInteractionEvent.ChapterAdvanceStarted)
                previousExitStartRubberPx = rubberAtRelease
                previousPageExit.snapTo(0f)
                previousPageEnter.snapTo(1f)
                // Hand lift to exit anim so the page never snaps back up.
                previousChapterPull = 0f

                val prepared = viewModel.materialize(prevId)
                if (prepared == null) {
                    chapterAdvancing = false
                    return@launch
                }

                // 1) Current page continues down from the rubber pose and fades.
                previousPageExit.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 480,
                        easing = chapterAdvanceEasing,
                    ),
                )

                // 2) Install previous chapter at its header; verses stay parked
                //    above until the header has settled.
                previousPageEnter.snapTo(0f)
                previousPageExit.snapTo(0f)
                verseEnterFromAbove = true
                verseRevealForSurah = prevId
                verseReveal.snapTo(0f)
                viewModel.installPrepared(prepared)
                listState.scrollToItem(0)
                withFrameNanos { }
                withFrameNanos { }

                // 3) Previous header fades and eases downward into place.
                previousPageEnter.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 560,
                        easing = chapterAdvanceEasing,
                    ),
                )

                // 4) Then verses fade and settle downward under the header.
                if (verseRevealForSurah == prevId) {
                    verseReveal.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = 640,
                            easing = chapterAdvanceEasing,
                        ),
                    )
                }
                if (verseRevealForSurah == prevId) {
                    verseRevealForSurah = 0
                }
                verseEnterFromAbove = false
                chapterAdvancing = false
                onOpenPreviousChapter(prevId)
            }
        }

        fun advanceToNextChapter(nextId: Int) {
            if (chapterAdvancing) return
            val next = uiState.nextSurah?.takeIf { it.id == nextId } ?: return
            scope.launch {
                // Pin the top-nav title while we still have the previous surah
                // (user is usually past the header at the chapter end).
                val prev = uiState.content?.surah
                if (prev != null && listState.firstVisibleItemIndex > 0) {
                    pinnedTopNavTitle = Triple(
                        prev.id,
                        prev.nameArabic,
                        prev.nameTransliteration,
                    )
                }
                // Capture rubber-band lift BEFORE clearing pull so the fly can
                // continue upward from the finger's release point.
                val pullAtRelease = nextChapterPull.coerceIn(0f, 1f)
                val rubberAtRelease =
                    pullRubberMaxPx * sin(pullAtRelease * PI.toFloat() * 0.5f)
                val morphAtRelease =
                    headerMorph.value.coerceAtLeast(pullAtRelease * 0.4f)

                // One frame so onGloballyPositioned still reflects the rubbered
                // layout (do not zero pull yet).
                withFrameNanos { }
                val startY = footerOpeningRootY
                val endY = readerRootY +
                    with(density) {
                        (padding.calculateTopPadding() + 32.dp).toPx()
                    }

                chapterAdvancing = true
                // Keep pull at release for one more frame so the list doesn't
                // drop; fly takes over translation via startLiftPx.
                nextChapterPullArmed = false
                dispatch(ReaderInteractionEvent.ChapterAdvanceStarted)
                // Hold the bright end-of-chapter sheen for the whole fly +
                // handoff so the medallion doesn't dim when we scrollToItem(0).
                sheenFollowScroll = false
                sheenAnim.snapTo(scrollSheenValue())
                headerMorph.snapTo(morphAtRelease)
                flyerAlpha.snapTo(1f)
                openingInListAlpha.snapTo(1f)
                realHeaderAlpha.snapTo(1f)

                val prepared = viewModel.materialize(nextId)
                if (prepared == null) {
                    nextChapterPull = 0f
                    headerMorph.snapTo(0f)
                    chapterAdvancing = false
                    sheenFollowScroll = true
                    return@launch
                }

                if (!startY.isNaN()) {
                    // Continue from the rubber-band pose — never snap back down.
                    flyingHeader = FlyingChapterHeader(
                        surah = next,
                        startYInRoot = startY,
                        endYInRoot = endY,
                        startLiftPx = rubberAtRelease,
                    )
                    flyProgress.snapTo(0f)
                    flyerAlpha.snapTo(1f)
                    // Now safe to clear pull: advance lift owns list translation.
                    nextChapterPull = 0f
                    // Soft handoff into the flyer: fade the in-list opening and
                    // invitation chrome while the flyer slides up.
                    launch {
                        openingInListAlpha.animateTo(
                            0f,
                            tween(200, easing = chapterAdvanceEasing),
                        )
                    }
                    launch {
                        headerMorph.animateTo(
                            1f,
                            tween(280, easing = chapterAdvanceEasing),
                        )
                    }
                    // Flyer carries the opening to the header slot; list
                    // graphicsLayer (exitingPreviousPage) scrolls/fades the
                    // previous verses out on the same flyProgress.
                    flyProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = 780,
                            easing = chapterAdvanceEasing,
                        ),
                    )
                } else {
                    nextChapterPull = 0f
                    // No position — still fade invitation chrome before swap.
                    headerMorph.animateTo(
                        1f,
                        tween(220, easing = chapterAdvanceEasing),
                    )
                }

                // Handoff under the flying opening (covers the list remount).
                // Weave + medallion ownership switch to the settled header (full
                // strength, never dual-stacked). Only titles crossfade with
                // complementary alphas: flyer = t, real = 1 − t.
                // List translation must already be 0 so scroll lands the real
                // header exactly under the flyer (skip previous-chapter item).
                verseEnterFromAbove = false
                verseRevealForSurah = nextId
                verseReveal.snapTo(0f)
                realHeaderAlpha.snapTo(1f)
                // Flyer still at full chrome; complementary real chrome starts at 0.
                flyerAlpha.snapTo(1f)
                viewModel.installPrepared(prepared)
                listState.scrollToItem(0)
                // Two frames so the new LazyColumn lays out at scroll 0 under
                // the still-visible flyer before the chrome crossfade.
                withFrameNanos { }
                withFrameNanos { }
                if (flyingHeader != null) {
                    // Linear so flyer + real chrome sum to 1 throughout.
                    flyerAlpha.animateTo(
                        0f,
                        tween(320, easing = LinearEasing),
                    )
                    withFrameNanos { }
                }
                flyingHeader = null
                // Reset flyer animatables only after the overlay has left the tree.
                withFrameNanos { }
                flyProgress.snapTo(0f)
                flyerAlpha.snapTo(1f)
                openingInListAlpha.snapTo(1f)
                headerMorph.snapTo(0f)
                chapterAdvancing = false
                // Sync the sheet id after the dissolve so surahId/startAyah
                // prop changes cannot interrupt the handoff composition.
                onOpenNextChapter(nextId)
                // Top-nav pin has finished fading (or was never set).
                pinnedTopNavTitle = null

                // Keep the bright sheen after landing (do not ease to the dim
                // at-rest header value). sheenFollowScroll stays false so the
                // medallion remains bright on the new chapter top.

                // Verses fade and rise in as soon as the header has landed.
                if (verseRevealForSurah != nextId) return@launch
                verseReveal.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 640,
                        easing = chapterAdvanceEasing,
                    ),
                )
                if (verseRevealForSurah == nextId) {
                    verseRevealForSurah = 0
                }
            }
        }

        // If navigation leaves the handoff surah, drop any parked verse state.
        LaunchedEffect(content.surah.id) {
            if (verseRevealForSurah != 0 && verseRevealForSurah != content.surah.id) {
                verseRevealForSurah = 0
                verseEnterFromAbove = false
                verseReveal.snapTo(1f)
            }
        }

        // Bottom/top overscroll → pill progress + elastic rubber-band.
        // Release while fully filled continues into the chapter transition
        // from the finger's pose (never snaps back first). Retract / release
        // below full animates the bar empty (unfills).
        val pullFillThresholdPx = with(density) { 104.dp.toPx() }
        // Previous needs a longer pull than next — small nubs should not fill.
        val previousPullFillThresholdPx = with(density) { 220.dp.toPx() }
        val nextSurahLatest = rememberUpdatedState(uiState.nextSurah)
        val previousSurahLatest = rememberUpdatedState(uiState.previousSurah)
        val advancingLatest = rememberUpdatedState(chapterAdvancing)
        val beganAtTopLatest = rememberUpdatedState(gestureBeganAtChapterTop)
        val beganAtBottomLatest = rememberUpdatedState(gestureBeganAtChapterBottom)
        var pullSettling by remember { mutableStateOf(false) }
        fun animatePullUnfill(isNext: Boolean, from: Float) {
            pullSettling = true
            scope.launch {
                val anim = Animatable(from)
                anim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) {
                    if (isNext) nextChapterPull = value else previousChapterPull = value
                }
                if (isNext) nextChapterPullArmed = false else previousChapterPullArmed = false
                pullSettling = false
            }
        }
        val settleChapterPull: () -> Unit = settle@{
            if (pullSettling || advancingLatest.value) return@settle
            val next = nextSurahLatest.value
            val previous = previousSurahLatest.value
            val nextProgress = nextChapterPull
            val prevProgress = previousChapterPull
            // Commit only while still completely filled — retracting then releasing
            // unfills the bar instead of advancing (armed is haptic-only).
            when {
                next != null && nextProgress >= 1f -> {
                    advanceToNextChapter(next.id)
                }
                previous != null && prevProgress >= 1f -> {
                    advanceToPreviousChapter(previous.id)
                }
                nextProgress > 0f -> animatePullUnfill(isNext = true, from = nextProgress)
                prevProgress > 0f -> animatePullUnfill(isNext = false, from = prevProgress)
            }
        }
        val settlePullLatest = rememberUpdatedState(settleChapterPull)
        val chapterPullConnection = remember(
            listState,
            pullFillThresholdPx,
            previousPullFillThresholdPx,
        ) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Opposite motion drains the pull before the list can move,
                    // so even a completely filled invitation can be cancelled.
                    if (nextChapterPull > 0f && available.y > 0f) {
                        val previous = nextChapterPull
                        nextChapterPull =
                            (previous - available.y / pullFillThresholdPx).coerceIn(0f, 1f)
                        if (nextChapterPull < 1f) nextChapterPullArmed = false
                        return Offset(
                            x = 0f,
                            y = (previous - nextChapterPull) * pullFillThresholdPx,
                        )
                    }
                    if (previousChapterPull > 0f && available.y < 0f) {
                        val previous = previousChapterPull
                        previousChapterPull =
                            (previous + available.y / previousPullFillThresholdPx)
                                .coerceIn(0f, 1f)
                        if (previousChapterPull < 1f) previousChapterPullArmed = false
                        return Offset(
                            x = 0f,
                            y = -(previous - previousChapterPull) * previousPullFillThresholdPx,
                        )
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (advancingLatest.value) return Offset.Zero
                    // Bottom: next chapter (content wants up / finger up).
                    if (
                        nextSurahLatest.value != null &&
                        previousChapterPull <= 0f &&
                        beganAtBottomLatest.value &&
                        !listState.canScrollForward &&
                        available.y < 0f
                    ) {
                        val add = -available.y / pullFillThresholdPx
                        val next = (nextChapterPull + add).coerceIn(0f, 1f)
                        if (next >= 1f && !nextChapterPullArmed) {
                            nextChapterPullArmed = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        nextChapterPull = next
                        return Offset(0f, available.y)
                    }
                    // Top: previous chapter — only if this gesture *began* already
                    // at the header top. A fling from mid-chapter stops on the
                    // header and must not turn residual velocity into a pull.
                    // Longer threshold than next so a small tug does not fill.
                    // (beganAtTop is set on pointer-down; no UserInput-only gate —
                    // some devices never report that source for nested overscroll.)
                    if (
                        previousSurahLatest.value != null &&
                        nextChapterPull <= 0f &&
                        beganAtTopLatest.value &&
                        !listState.canScrollBackward &&
                        available.y > 0f
                    ) {
                        val add = available.y / previousPullFillThresholdPx
                        val next = (previousChapterPull + add).coerceIn(0f, 1f)
                        if (next >= 1f && !previousChapterPullArmed) {
                            previousChapterPullArmed = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        previousChapterPull = next
                        return Offset(0f, available.y)
                    }
                    // Soft guard: absorb leftover fling overscroll at the top so
                    // it never jiggles into a previous pull when the gesture
                    // didn't start docked on the header.
                    if (
                        previousSurahLatest.value != null &&
                        !beganAtTopLatest.value &&
                        !listState.canScrollBackward &&
                        available.y > 0f
                    ) {
                        return Offset(0f, available.y)
                    }
                    // Symmetric bottom guard: arriving at the footer ends this
                    // gesture. A fresh pull from the footer is required to open.
                    if (
                        nextSurahLatest.value != null &&
                        !beganAtBottomLatest.value &&
                        !listState.canScrollForward &&
                        available.y < 0f
                    ) {
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    // Kill residual fling at the top when the gesture didn't
                    // begin there — soft stop on the header.
                    if (
                        !beganAtTopLatest.value &&
                        !listState.canScrollBackward &&
                        available.y > 0f
                    ) {
                        if (previousChapterPull > 0f) {
                            // Shouldn't happen, but never commit from fling junk.
                            previousChapterPull = 0f
                            previousChapterPullArmed = false
                        }
                        return available
                    }
                    // Kill residual fling at the footer unless this gesture
                    // began there, matching the header's soft stop.
                    if (
                        !beganAtBottomLatest.value &&
                        !listState.canScrollForward &&
                        available.y < 0f
                    ) {
                        if (nextChapterPull > 0f) {
                            nextChapterPull = 0f
                            nextChapterPullArmed = false
                        }
                        return available
                    }
                    if (nextChapterPull > 0f || previousChapterPull > 0f) {
                        settlePullLatest.value()
                    }
                    return Velocity.Zero
                }
            }
        }
        LaunchedEffect(content.surah.id) {
            nextChapterPull = 0f
            nextChapterPullArmed = false
            previousChapterPull = 0f
            previousChapterPullArmed = false
            gestureBeganAtChapterTop = false
            gestureBeganAtChapterBottom = false
            previousPageExit.snapTo(0f)
            previousPageEnter.snapTo(1f)
        }

        // One column of text at a book-like measure: full-bleed on phones,
        // centered with air on tablets and in landscape.
        Box(
            Modifier
                .padding(bottom = padding.calculateBottomPadding())
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer { alpha = readerContentAlpha.value }
                .onGloballyPositioned { readerRootY = it.positionInRoot().y },
        ) {
            val selectorSide = settings.ayahSelectorSide
            // Bookmark ribbon lives inside each verse block, on the edge opposite
            // the ayah selector — same chrome rules (hidden while reciting).
            val bookmarkSide = if (selectorSide == AyahSelectorSide.RIGHT) {
                AyahSelectorSide.LEFT
            } else {
                AyahSelectorSide.RIGHT
            }
            val bookmarkChromeAlpha: () -> Float = { topBarAlpha.value }
            // Soft dissolve heights — list padding matches so content sits
            // clear of the edge at rest; scrolling draws under it.
            // Bottom pad is the active-word reading band (≥ fade) so word-follow
            // can lift the last lines clear of the dissolve above the player bar.
            val listFadeTop = 32.dp
            val listFadeBottom = 64.dp
            // The sheet is edge-to-edge, so the window never resizes for the
            // keyboard — the reader would be writing on the last visible line
            // with the IME right under the caret. While a note is open, clear
            // the keyboard's own height plus a hand's-width of paper so the
            // line being written sits well above it.
            val imeBottom =
                WindowInsets.imeAnimationTarget.asPaddingValues().calculateBottomPadding()
            val listBottomPad = if (editingAnnotationAyah != 0) {
                132.dp + imeBottom + 96.dp
            } else {
                132.dp // matches ActiveWordBottomMargin in ReaderComponents
            }
            // Read Animatable in this composition so morph frames recompose the list.
            val headerMorphNow = headerMorph.value
            val footerMorph = maxOf(headerMorphNow, nextChapterPull * 0.4f)
            val flyProgressNow = flyProgress.value
            val flyerAlphaNow = flyerAlpha.value
            val openingInListAlphaNow = openingInListAlpha.value
            val realHeaderAlphaNow = realHeaderAlpha.value
            val flying = flyingHeader
            val verseRevealNow = verseReveal.value
            val verseRisePx = with(density) { 40.dp.toPx() }
            val verseFromAbove = verseEnterFromAbove
            // Soft fade: hold ink low early, then wash in (reads more as a fade
            // than a linear opacity ramp tied 1:1 to the motion).
            val verseFadeAlpha = run {
                val t = verseRevealNow.coerceIn(0f, 1f)
                val u = ((t - 0.08f) / 0.92f).coerceIn(0f, 1f)
                u * u * (3f - 2f * u)
            }
            // Next-chapter: park below and rise. Previous-chapter: park above
            // and settle downward after the header lands.
            val verseRevealY =
                if (verseFromAbove) {
                    -(1f - verseRevealNow) * verseRisePx
                } else {
                    (1f - verseRevealNow) * verseRisePx
                }
            // Elastic overscroll rubber-band (bottom next / top previous).
            val nextPullRubberPx = run {
                val t = nextChapterPull.coerceIn(0f, 1f)
                val eased = sin(t * PI.toFloat() * 0.5f)
                pullRubberMaxPx * eased
            }
            val previousPullRubberPx = run {
                val t = previousChapterPull.coerceIn(0f, 1f)
                // Near-linear rubber so the revealed band tracks the finger
                // without jumping ahead of the list edge.
                val eased = t * (2f - t) // ease-out quad
                previousPullRubberMaxPx * eased
            }
            val previousPageExitNow = previousPageExit.value
            val previousPageEnterNow = previousPageEnter.value
            // Enough travel to clear a full phone page of verse ink (next-fly).
            val nextExitScrollPx = with(density) { 420.dp.toPx() }
            val paper = MaterialTheme.colorScheme.background
            // Below the top app bar. Previous chrome is a real layout slot that
            // grows with pull (Column height) — not a graphicsLayer sibling that
            // can be covered by the full-size LazyColumn layer. (Drawing at y=0
            // of the scaffold body put the chrome under the TopAppBar.)
            val topInset = if (mushafMode) {
                statusBarTop
            } else {
                padding.calculateTopPadding()
            }
            val previous = uiState.previousSurah
            val revealPx = when {
                previousPageExitNow > 0f ->
                    previousExitStartRubberPx +
                        (previousExitScrollPx - previousExitStartRubberPx) *
                        previousPageExitNow.coerceIn(0f, 1f)
                previousPullRubberPx > 0.5f -> previousPullRubberPx
                else -> 0f
            }
            val revealDp = with(density) { revealPx.toDp() }
            val pullT = previousChapterPull.coerceIn(0f, 1f)
            // Ornamented return-to-ayah. Yields while MainActivity's concordance
            // Back-to capsule is showing so the two never compete. Lab freeze
            // already parks the page; suppress the return capsule so it does
            // not fight the deliberate "leave me alone" state.
            val showReturnToAyah =
                playerState.error == null &&
                    !rootReturnVisible &&
                    !followEnabled &&
                    labFocusEnabled &&
                    recitingActive
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxSize()
                    .padding(top = topInset)
                    .clipToBounds(),
            ) {
                // Layout-owned reveal: height grows with pull, list is weight(1)
                // below it. Chrome cannot be obscured by a translated list layer.
                if (previous != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(revealDp)
                            .clipToBounds()
                            .background(paper),
                    ) {
                        if (revealPx > 1f) {
                            PreviousChapterPullChrome(
                                nameTransliteration = previous.nameTransliteration,
                                pullProgress = if (previousPageExitNow > 0f) 1f else pullT,
                                onOpen = { advanceToPreviousChapter(previous.id) },
                                enabled = !chapterAdvancing && pullT > 0.35f,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .widthIn(max = 680.dp)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
                val mushafReady = mushafUi
                // The leaf opens as a leaf. Falling through to the scroll
                // layout while the catalog builds meant a chapter opened in the
                // wrong mode and changed under the reader a moment later; the
                // sheet is up immediately and its paper is simply blank until
                // the pages arrive.
                if (mushafMode) {
                    MushafReadingSheet(
                        reciterName = uiState.currentReciter?.name.orEmpty(),
                        playerState = playerState,
                        isThisSurahLoaded = isThisSurahPlaying,
                        enabled = !contextualGuideOpen,
                        onOpenChapters = onBack,
                        onOpenSettings = onOpenSettings,
                        // The leaf on screen is the request. Pressing play on a
                        // page recites that page, from the first word standing
                        // on it — including when the page belongs to a chapter
                        // the reader only scrubbed past and never loaded, which
                        // used to resume the old chapter and turn the leaf away
                        // underneath them. A paused verse the leaf itself
                        // carries still resumes, so pause and play in place
                        // stay a pair.
                        onPlayPause = {
                            if (playerState.isPlaying) {
                                viewModel.player.togglePlayPause()
                            } else {
                                dispatch(ReaderInteractionEvent.EnableFollow)
                                val target = mushafPlayTarget()
                                when {
                                    target == null -> if (isThisSurahPlaying) {
                                        viewModel.player.togglePlayPause()
                                    } else {
                                        viewModel.playFromAyah(selectedPlaybackAyah())
                                    }
                                    target.surahId != renderedSurahId -> viewModel.load(
                                        surahId = target.surahId,
                                        startPlaybackAtAyah = target.ayah,
                                        startPlaybackAtWord = target.word,
                                        keepContent = true,
                                    )
                                    else -> viewModel.playFromAyahWord(target.ayah, target.word)
                                }
                            }
                        },
                        onFastBackward = viewModel::fastBackward,
                        onFastForward = viewModel::fastForward,
                        onRepeatClick = { showRepeatDialog = true },
                        onSpeed = viewModel::cycleSpeed,
                        // Where the reader is in the book, by leaf — so the
                        // rule answers while pages are turned as well as while
                        // they are recited, and the thumb has something to mark
                        // and something to be dragged along.
                        pageAt = mushafLeafPage,
                        english = englishBook != null,
                        pageNumberScript = settings.pageNumberScript,
                        // Whatever the rule is counting: leaves on the English
                        // book, Madinah pages on the Arabic one.
                        pageCount = mushafBookLength(
                            englishBook,
                            mushafCatalog?.pageCount ?: 1,
                        ),
                        chapterPages = mushafChapterFirstPages,
                        pageLabel = mushafPageLabel,
                        chapterLabel = mushafChapterLabel,
                        onSeekPage = { mushafSeekPage = it },
                        onSeekSurah = { mushafSeekSurahId = it },
                        onWarmPage = { page ->
                            mushafCatalog?.let { catalog ->
                                val face = MushafQcfFonts.face(activityContext.applicationContext, page)
                                warmMushafInkProfiles(catalog.page(page), face?.typeface)
                            }
                        },
                        onScrubbing = { mushafScrubbing.value = it },
                        onLanding = { mushafDialLanding.value = it },
                        modifier = Modifier.weight(1f),
                        leafFooter = {
                            val catalog = mushafCatalog
                            val word = activeWordState.value
                            val playbackPage = when {
                                catalog == null -> null
                                word != null && isThisSurahPlaying -> {
                                    val on = catalog.readingPageOf(
                                        renderedSurahId,
                                        word.ayah,
                                        word.wordPosition,
                                        wholeVerses = mushafWholeVerses,
                                    )
                                    englishBook?.leafOfVerse(renderedSurahId, word.ayah, on)
                                        ?: (on - 1)
                                }
                                activeBasmalah == true && isThisSurahPlaying ->
                                    pageLeaf(catalog.firstPageOf(renderedSurahId))
                                activeAyah != null && isThisSurahPlaying -> {
                                    val on = catalog.pageOf(renderedSurahId, activeAyah, 1)
                                    englishBook?.leafOfVerse(renderedSurahId, activeAyah, on)
                                        ?: (on - 1)
                                }
                                else -> null
                            }
                            val way = playbackPage?.let {
                                mushafReturnWay(mushafPagerState.currentPage, it)
                            }
                            FloatingPaperControl(visible = showReturnToAyah && way != null) {
                                IslamicReturnToAyahButton(
                                    heading = when (way) {
                                        MushafReturnWay.Left -> ReturnArrowHeading.Left
                                        MushafReturnWay.Right -> ReturnArrowHeading.Right
                                        null -> ReturnArrowHeading.Down
                                    },
                                    onClick = {
                                        dispatch(ReaderInteractionEvent.EnableFollow)
                                    },
                                )
                            }
                        },
                    ) {
                    if (mushafReady == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                    val mushafSurahId = content.surah.id
                    // Deferred: the leaf reads playback where it uses it, so a
                    // play or a pause never recomposes the pages themselves.
                    val mushafPlayback = remember { mutableStateOf(MushafPlayback(null, false, false)) }
                    SideEffect {
                        mushafPlayback.value = MushafPlayback(
                            activeAyah = activeAyah,
                            reciting = recitingActive,
                            playingHere = isThisSurahPlaying,
                            basmalahActive = isThisSurahPlaying && activeBasmalah == true,
                            isPlaying = isThisSurahPlaying && playerState.isPlaying,
                            playingAyah = playerState.nowPlaying?.ayah
                                ?.takeIf { isThisSurahPlaying },
                        )
                    }
                    val mushafDispatch = rememberUpdatedState(
                        { event: ReaderInteractionEvent -> dispatch(event) },
                    )
                    val mushafRootMoved = rememberUpdatedState(onRootReturnUserMoved)
                    val mushafOpenRoot = rememberUpdatedState(onOpenRootViewer)
                    // The leaf the reader tapped on. The active word carries no
                    // page of its own and the position poll can still be
                    // reporting the word from before the seek, which used to
                    // turn the page back and then forward again under the
                    // finger. Hold the pager here until the clock names this
                    // leaf — a timer let a slow seek yank it away.
                    var mushafTappedPage by remember { mutableStateOf<Int?>(null) }
                    LaunchedEffect(
                        mushafTappedPage,
                        mushafReady.catalog,
                        mushafSurahId,
                        mushafWholeVerses,
                        englishBook,
                    ) {
                        val held = mushafTappedPage ?: return@LaunchedEffect
                        val catalog = mushafReady.catalog
                        snapshotFlow { activeWordState.value }.collect { word ->
                            if (word == null || word.fromTap) return@collect
                            val on = catalog.readingPageOf(
                                mushafSurahId,
                                word.ayah,
                                word.wordPosition,
                                wholeVerses = mushafWholeVerses,
                            )
                            if (mushafLeafNumber(englishBook, mushafSurahId, word.ayah, on) == held) {
                                mushafTappedPage = null
                            }
                        }
                    }
                    val onMushafTurnedPage = remember {
                        {
                            mushafTappedPage = null
                            mushafDispatch.value(ReaderInteractionEvent.UserMovedPage)
                            mushafRootMoved.value()
                        }
                    }
                    val onMushafWordClick = remember(mushafSurahId, viewModel) {
                        { token: MushafToken ->
                            mushafDispatch.value(ReaderInteractionEvent.EnableFollow)
                            if (token.surahId == mushafSurahId) {
                                viewModel.playFromAyahWord(token.ayah, token.word.position)
                            } else {
                                // The reader turned past a surah boundary: the
                                // leaf is another surah's, whose timings are not
                                // loaded. Load it and open on the tapped word
                                // rather than swallowing the tap.
                                viewModel.load(
                                    surahId = token.surahId,
                                    startPlaybackAtAyah = token.ayah,
                                    startPlaybackAtWord = token.word.position,
                                    keepContent = true,
                                )
                            }
                        }
                    }
                    val onMushafWordLongClick = remember(haptics) {
                        { token: MushafToken ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            mushafOpenRoot.value(token.surahId, token.ayah, token.word.position)
                        }
                    }
                    val onMushafAyahClick = remember(mushafSurahId, viewModel) {
                        { token: MushafToken ->
                        mushafDispatch.value(ReaderInteractionEvent.EnableFollow)
                        if (token.surahId == mushafSurahId) {
                            viewModel.playFromAyah(token.ayah)
                        } else {
                            viewModel.load(
                                surahId = token.surahId,
                                startPlaybackAtAyah = token.ayah,
                                keepContent = true,
                            )
                        }
                        }
                    }
                    // The English leaf has no word to aim at, so its tap says
                    // which verse and how far into it. Resolving that to a word
                    // needs the verse itself, which lives here: its word count,
                    // and where each of those words lands in its English.
                    val leafTextSource = settings.englishLeafText
                    val leafTextForSetting: suspend (Int) -> Map<Long, String> =
                        remember(viewModel, leafTextSource) {
                            { page -> viewModel.leafText(page, leafTextSource) }
                        }
                    val seekAlignments = remember(content) { EnglishVerseAlignments(content) }
                    val onMushafVerseSeek = remember(mushafSurahId, viewModel, content, seekAlignments) {
                        { surahId: Int, ayah: Int, through: Float ->
                            mushafDispatch.value(ReaderInteractionEvent.EnableFollow)
                            val verse = content.ayahs
                                .firstOrNull { it.number == ayah }
                                ?.takeIf { surahId == mushafSurahId }
                            val words = verse?.words?.size ?: 0
                            val wordEnds = verse?.let { seekAlignments.of(it.number) }
                            val position = englishSeekWordPosition(through, words, wordEnds)
                            if (surahId == mushafSurahId) {
                                viewModel.playFromAyahWord(ayah, position)
                            } else {
                                viewModel.load(
                                    surahId = surahId,
                                    startPlaybackAtAyah = ayah,
                                    keepContent = true,
                                )
                            }
                        }
                    }
                    val onMushafBasmalahClick = remember(mushafSurahId, viewModel) {
                        { surahId: Int ->
                            mushafDispatch.value(ReaderInteractionEvent.EnableFollow)
                            if (surahId == mushafSurahId) {
                                viewModel.playFromAyah(1)
                            } else {
                                viewModel.load(
                                    surahId = surahId,
                                    startPlaybackAtAyah = 1,
                                    keepContent = true,
                                )
                            }
                        }
                    }
                    MushafPager(
                        catalog = mushafReady.catalog,
                        content = content,
                        basmalahWash = viewModel.basmalahWashProgress,
                        proseBasmalahWash = viewModel.englishBasmalahWashProgress,
                        surahsById = mushafReady.surahsById,
                        pagerState = mushafPagerState,
                        activeWordState = activeWordState,
                        playback = mushafPlayback,
                        playbackSpeed = playerState.speed,
                        followEnabled = followPlayback,
                        loadedSurahId = mushafSurahId,
                        flashAyah = searchFlashAyah,
                        flashWordPosition = searchFlashWord,
                        heldPage = mushafTappedPage,
                        onTappedLeaf = { mushafTappedPage = it },
                        parkNeighbours = { mushafDialLanding.value },
                        onUserTurnedPage = onMushafTurnedPage,
                        onWordClick = onMushafWordClick,
                        onWordLongClick = onMushafWordLongClick,
                        onAyahClick = onMushafAyahClick,
                        onVerseSeek = onMushafVerseSeek,
                        onBasmalahClick = onMushafBasmalahClick,
                        english = settings.readingMode == ReadingMode.ENGLISH_ONLY,
                        verseNumberScript = settings.verseNumberScript,
                        hideEnglishParentheticals = settings.hideEnglishParentheticals,
                        leafText = leafTextForSetting,
                        book = englishBook,
                        modifier = Modifier.fillMaxSize(),
                    )
                    }
                    }
                } else LazyColumn(
                    state = listState,
                    userScrollEnabled = !chapterAdvancing,
                    contentPadding = PaddingValues(
                        top = listFadeTop,
                        bottom = listBottomPad,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .background(paper)
                        .graphicsLayer {
                            val fly = flying
                            when {
                                fly != null && content.surah.id != fly.surah.id -> {
                                    // Front-load: outgoing page fully gone ~28% into
                                    // the fly (~220ms of the 780ms slide).
                                    val exitT = (flyProgressNow / 0.28f).coerceIn(0f, 1f)
                                    val e = 1f - (1f - exitT) * (1f - exitT)
                                    alpha = 1f - e
                                    val lift = fly.startLiftPx +
                                        (nextExitScrollPx - fly.startLiftPx) * e
                                    translationY = -lift
                                }
                                previousPageExitNow > 0f -> {
                                    // Slot already grew via revealPx; only fade out.
                                    val e = previousPageExitNow.coerceIn(0f, 1f)
                                    val u = e * e * (3f - 2f * e)
                                    alpha = 1f - u
                                }
                                previousPageEnterNow < 0.999f -> {
                                    // Previous header eases downward into place with a
                                    // soft fade (verses still parked via verseReveal).
                                    val e = previousPageEnterNow.coerceIn(0f, 1f)
                                    val u = e * e * (3f - 2f * e)
                                    alpha = u
                                    translationY = -previousEnterScrollPx * (1f - u)
                                }
                                nextPullRubberPx > 0.5f -> {
                                    translationY = -nextPullRubberPx
                                }
                                // previous pull: no translationY — Column slot owns it
                            }
                        }
                        .nestedScroll(chapterPullConnection)
                        .onGloballyPositioned { listCoordinates = it }
                        .pointerInput(Unit) {
                            val touchSlop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // Capture dock state for this gesture only. A fling that
                                // later reaches either edge must stop there, not pull.
                                gestureBeganAtChapterTop = !listState.canScrollBackward
                                gestureBeganAtChapterBottom = !listState.canScrollForward
                                var dragStarted = false
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        if (!dragStarted) {
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change != null) {
                                                val distance =
                                                    (change.position - down.position).getDistance()
                                                if (distance > touchSlop) {
                                                    dragStarted = true
                                                    if (rootReturnVisible) {
                                                        onRootReturnUserMovedLatest.value()
                                                    }
                                                    val dx = change.position.x - down.position.x
                                                    val dy = change.position.y - down.position.y
                                                    if (abs(dy) > abs(dx)) {
                                                        dispatch(ReaderInteractionEvent.UserMovedPage)
                                                    }
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    // Release after top/bottom pull: full → open, else unfill.
                                    if (nextChapterPull > 0f || previousChapterPull > 0f) {
                                        settlePullLatest.value()
                                    }
                                    gestureBeganAtChapterTop = false
                                    gestureBeganAtChapterBottom = false
                                }
                            }
                        }
                        .verticalFadingEdges(
                            color = paper,
                            top = listFadeTop,
                            bottom = listFadeBottom,
                            // List is already below the top bar — no status-bar inset.
                            topInset = 0.dp,
                        ),
                ) {
                items(
                    count = readerItems.size,
                    key = { readerItems[it].key },
                ) { index ->
                    when (val item = readerItems[index]) {
                        LazyItem.Header -> {
                            // Weave + medallion stay full-strength on this header
                            // during handoff; only titles complementary-crossfade.
                            val handoffUnderFlyer =
                                flying != null && content.surah.id == flying.surah.id
                            ChapterOpening(
                                chapterNumber = content.surah.id,
                                nameArabic = content.surah.nameArabic,
                                nameTransliteration = content.surah.nameTransliteration,
                                nameTranslation = content.surah.nameTranslation,
                                revelationPlace = content.surah.revelationPlace,
                                ayahCount = content.surah.ayahCount,
                                sheen = sheen,
                                showFieldWeave = true,
                                showRosette = true,
                                contentAlpha = if (handoffUnderFlyer) {
                                    (1f - flyerAlphaNow).coerceIn(0f, 1f)
                                } else {
                                    realHeaderAlphaNow
                                },
                            )
                        }
                        LazyItem.Basmalah -> {
                            Box(
                                Modifier.graphicsLayer {
                                    translationY = verseRevealY
                                    alpha = verseFadeAlpha
                                },
                            ) {
                                BasmalahBlock(
                                    active = isThisSurahPlaying && activeBasmalah == true,
                                    dimmed = recitingActive && activeBasmalah != true,
                                    washProgress = viewModel.basmalahWashProgress,
                                    onClick = {
                                        dispatch(ReaderInteractionEvent.EnableFollow)
                                        viewModel.playFromAyah(1)
                                    },
                                )
                            }
                        }
                        is LazyItem.AyahItem -> {
                            val ayah = content.ayahs[item.ayahIndex]
                            // Per-ayah derived reads so an ayah/word boundary
                            // recomposes exactly the blocks whose bit flips —
                            // never every visible AyahBlock (docs/PERFORMANCE.md).
                            val activeWord by remember(ayah.number) {
                                derivedStateOf {
                                    activeWordState.value?.takeIf { it.ayah == ayah.number }
                                }
                            }
                            // Ink policy matches web readerAyahInkPolicyActive: the
                            // karaoke owner *and* the fade-lead target both stay
                            // undimmed, so a waqf hold is not recessed early and the
                            // next verse can lift its recess before handoff.
                            val policyActive by remember(ayah.number, isThisSurahPlaying) {
                                derivedStateOf {
                                    if (!isThisSurahPlaying) return@derivedStateOf false
                                    val inkAyah = activeWordState.value?.ayah
                                    val leadAyah = activeAyahState.value
                                    ayah.number == inkAyah || ayah.number == leadAyah
                                }
                            }
                            val bookmarked = ayah.number in bookmarkedAyahs
                            val bookmarkLessonTarget = bookmarkNoteTipOpen &&
                                bookmarkNoteTipSurah == ayah.surahId &&
                                bookmarkNoteTipAyah == ayah.number
                            val ribbonBookmarked = bookmarked || bookmarkLessonTarget
                            Box(
                                Modifier.contextualGuideProgressiveBlur(
                                    enabled = settings.developerModeEnabled &&
                                        settings.educationGuidesEnabled &&
                                        contextualGuideRendered,
                                    visible = contextualGuideVisible,
                                    rendered = contextualGuideRendered,
                                    flow = if (
                                        contextualGuideTargetSide == AyahSelectorSide.LEFT
                                    ) {
                                        Offset(-1f, 0f)
                                    } else {
                                        Offset(1f, 0f)
                                    },
                                ) {
                                    translationY = verseRevealY
                                    alpha = verseFadeAlpha
                                },
                            ) {
                            AyahBlock(
                                ayah = ayah,
                                readingMode = settings.readingMode,
                                activeWord = activeWord,
                                playbackSpeed = playerState.speed,
                                isActiveAyah = policyActive,
                                dimmed = recitingActive && !policyActive,
                                // Keep the page readable the moment a jump
                                // commits — the decelerating scroll is the cue,
                                // and a 7 % fade would hide it.
                                obscuredBySelector =
                                    ayahSelectorExpanded && requestedJumpAyah == 0,
                                fontScale = settings.fontScale,
                                showGloss = settings.showWordGloss,
                                showTransliteration = settings.showTransliteration,
                                showTranslation = settings.showTranslation,
                                verseNumberScript = settings.verseNumberScript,
                                hideEnglishParentheticals = settings.hideEnglishParentheticals,
                                searchQuery = activeQuery,
                                flashWordPosition = searchFlashWord
                                    ?.takeIf { searchFlashAyah == ayah.number },
                                // Word-level following tracks the karaoke owner,
                                // not the fade-led focus target — otherwise the
                                // last word is abandoned during the lead window.
                                // Use [playingNow] (not debounced recitingActive):
                                // after the last ayah ends, position can snap
                                // toward the item start while chrome is still
                                // recessed — word-follow would scroll back up.
                                keepActiveWordInView = ReaderInteraction.shouldKeepWordInView(
                                    followPlayback = followPlayback,
                                    isPlaying = playingNow,
                                    hasActiveWord = activeWord != null,
                                    restoreRequested =
                                        wordFocusRequest?.matches(activeWord) == true,
                                ),
                                listCoordinates = { listCoordinates },
                                onKeepWordInView = onKeepWordInView,
                                onKeepAnnotationInView = onKeepAnnotationInView,
                                bookmarkSide = bookmarkSide,
                                // The repository update is synchronous, but the
                                // collected projection can arrive one frame after
                                // the lesson. Keep its live anchor ruby meanwhile.
                                bookmarked = ribbonBookmarked,
                                placeMarked = ayah.number == parkedPlaceAyah,
                                placeUnfurlSignal = if (ayah.number == placeUnfurlAyah) {
                                    placeUnfurlToken
                                } else {
                                    0
                                },
                                onPlaceUnfurlConsumed = { consumed ->
                                    placeUnfurlToken = remainingUnfurlSignal(
                                        current = placeUnfurlToken,
                                        consumed = consumed,
                                    )
                                },
                                bookmarkChromeAlpha = bookmarkChromeAlpha,
                                // Keep the lesson target live so its taught hold
                                // can be completed without leaving the paper.
                                bookmarkInteractive = !recitingActive &&
                                    !gathering &&
                                    editingAnnotationAyah == 0,
                                // Gather mode owns the outer margin (ordinals)
                                // and verse taps; hide the ribbon while gathering.
                                onToggleBookmark = if (gathering) {
                                    null
                                } else {
                                    bookmarkToggle@{
                                        if (bookmarkLessonTarget) return@bookmarkToggle true
                                        val result = viewModel.toggleBookmark(ayah.number)
                                        if (result.showNoteTip) {
                                            if (ayahRailTipOpen) {
                                                viewModel.dismissAyahRailTip()
                                                ayahRailTipOpen = false
                                            }
                                            bookmarkNoteTipRibbonCenterY = Float.NaN
                                            bookmarkNoteTipSurah = ayah.surahId
                                            bookmarkNoteTipAyah = ayah.number
                                            scope.launch {
                                                focusController.focus(ayah.number, animate = true)
                                                bookmarkNoteTipOpen = true
                                            }
                                        }
                                        result.bookmarked
                                    }
                                },
                                onBookmarkRibbonPositioned = if (
                                    bookmarkNoteTipSurah == ayah.surahId &&
                                    bookmarkNoteTipAyah == ayah.number
                                ) {
                                    { coordinates ->
                                        bookmarkNoteTipRibbonCenterY =
                                            coordinates.positionInRoot().y +
                                            coordinates.size.height / 2f
                                    }
                                } else {
                                    null
                                },
                                gatherOrdinal = if (gathering) {
                                    gatherOrdinal(ayah.surahId, ayah.number)
                                } else {
                                    null
                                },
                                onWordClick = if (gathering) {
                                    { onToggleGatheredAyah(ayah.surahId, ayah.number) }
                                } else {
                                    wordClick@{ word ->
                                        if (editingAnnotationAyah != 0) return@wordClick
                                        val segment = viewModel.segmentsFor(ayah.number)
                                            ?.firstOrNull { it.position == word.position }
                                        // Mid-verse word play must not verse-home: for tall
                                        // ayahs that pins the top, un-lays-out the bottom
                                        // line the reader tapped, and word-band follow cannot
                                        // measure it — so the page jumps up. Seed follow as
                                        // already on this ayah so shouldHomeOnto skips once
                                        // the seek lands. Until then the playback target is
                                        // still the previous item — do not home onto it.
                                        lastFollowFocusTarget = ayah.number
                                        followWasEnabled = true
                                        pendingWordTapAyah = ayah.number
                                        dispatch(ReaderInteractionEvent.EnableFollow)
                                        if (segment != null) {
                                            viewModel.playFromWord(ayah.number, segment.startMs)
                                        } else {
                                            viewModel.playFromAyah(ayah.number)
                                        }
                                    }
                                },
                                onAyahClick = if (gathering) {
                                    { onToggleGatheredAyah(ayah.surahId, ayah.number) }
                                } else {
                                    ayahClick@{
                                        if (editingAnnotationAyah != 0) return@ayahClick
                                        dispatch(ReaderInteractionEvent.EnableFollow)
                                        viewModel.playFromAyah(ayah.number)
                                    }
                                },
                                onWordLongClick = if (gathering) {
                                    null
                                } else {
                                    { word ->
                                        // Hold opens the Root Word Viewer (or, in
                                        // developer mode, a chooser that can also
                                        // open the Timings Lab). MainActivity owns
                                        // the branch — see docs/ROOT_VIEWER.md.
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onOpenRootViewer(ayah.surahId, ayah.number, word.position)
                                    }
                                },
                                // Switched off, annotations are simply not part
                                // of the page. Notes are currently also bound to
                                // saved ribbons, so unmarking hides (but does not
                                // delete) the stored writing.
                                annotationText = when {
                                    gathering || !bookmarked ||
                                        !settings.annotationsEnabled -> null
                                    editingAnnotationAyah == ayah.number -> editingAnnotationText
                                    else -> annotationsForSurah.value[ayah.number]
                                },
                                isEditingAnnotation = !gathering &&
                                    bookmarked &&
                                    settings.annotationsEnabled &&
                                    editingAnnotationAyah == ayah.number,
                                onAnnotationChange = { editingAnnotationText = it },
                                // Guarded by identity: switching verses makes the
                                // old field lose focus *after* the new one opened,
                                // and that stale callback must not close the new
                                // editor or write the new draft to the old verse.
                                onAnnotationEditDone = {
                                    if (editingAnnotationAyah == ayah.number) commitOpenAnnotation()
                                },
                                onEditAnnotation = if (
                                    gathering || !ribbonBookmarked || !settings.annotationsEnabled
                                ) {
                                    null
                                } else {
                                    {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (bookmarkLessonTarget) {
                                            viewModel.dismissBookmarkNoteTip()
                                            bookmarkNoteTipOpen = false
                                        }
                                        commitOpenAnnotation()
                                        editingAnnotationText =
                                            annotationsForSurah.value[ayah.number] ?: ""
                                        editingAnnotationSurah = ayah.surahId
                                        editingAnnotationAyah = ayah.number
                                    }
                                },
                                annotationsHidden = recitingActive,
                                reciting = recitingActive,
                                onAnnotationDelete = {
                                    editingAnnotationText = ""
                                    commitOpenAnnotation()
                                    focusManager.clearFocus()
                                },
                                // Writing on one verse quiets every other verse
                                // on the sheet.
                                recededForAnnotationEdit = editingAnnotationAyah != 0 &&
                                    editingAnnotationAyah != ayah.number,
                            )
                            }
                        }
                        is LazyItem.PageDivider -> {
                            Box(
                                Modifier.graphicsLayer {
                                    translationY = verseRevealY
                                    alpha = verseFadeAlpha
                                },
                            ) {
                                PageBreak(
                                    page = item.page,
                                    script = settings.pageNumberScript,
                                )
                            }
                        }
                        LazyItem.NextChapter -> {
                            val next = uiState.nextSurah
                            if (next != null) {
                                NextChapterFooter(
                                    chapterNumber = next.id,
                                    nameArabic = next.nameArabic,
                                    nameTransliteration = next.nameTransliteration,
                                    nameTranslation = next.nameTranslation,
                                    revelationPlace = next.revelationPlace,
                                    ayahCount = next.ayahCount,
                                    sheen = sheen,
                                    onOpen = { advanceToNextChapter(next.id) },
                                    enabled = !chapterAdvancing,
                                    pullProgress = nextChapterPull,
                                    headerMorph = footerMorph,
                                    openingAlpha = openingInListAlphaNow,
                                    onOpeningPositioned = { coords ->
                                        footerOpeningRootY = coords.positionInRoot().y
                                    },
                                )
                            }
                        }
                    }
                }
                } // LazyColumn
            } // Column pull viewport (below top bar)
            // Flying next-chapter opening: continuous slide from footer → header.
            if (flying != null) {
                val yInBox = flying.startYInRoot +
                    (flying.endYInRoot - flying.startYInRoot) * flyProgressNow -
                    readerRootY
                // Weave + medallion ride the flyer until the settled header owns
                // them (same surah id after install) — one of each at full strength.
                val flyerOwnsEmbellishment = content.surah.id != flying.surah.id
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .zIndex(2f)
                        .graphicsLayer { translationY = yInBox },
                ) {
                    ChapterOpening(
                        chapterNumber = flying.surah.id,
                        nameArabic = flying.surah.nameArabic,
                        nameTransliteration = flying.surah.nameTransliteration,
                        nameTranslation = flying.surah.nameTranslation,
                        revelationPlace = flying.surah.revelationPlace,
                        ayahCount = flying.surah.ayahCount,
                        sheen = sheen,
                        // Match the settled SurahHeader so the dissolve doesn't
                        // pop padding when the flyer unmounts.
                        compactBottom = surahOpensWithBasmalahPreface(flying.surah.id),
                        rosetteScale = 1f,
                        rosetteAlpha = 1f,
                        showFieldWeave = flyerOwnsEmbellishment,
                        showRosette = flyerOwnsEmbellishment,
                        // Titles only — complementary with settled header (1 − t).
                        contentAlpha = flyerAlphaNow,
                    )
                }
            }
            if (ayahSelectorExpanded) {
                Box(
                    Modifier
                        .matchParentSize()
                        .zIndex(0.5f)
                        .absorbPointerEvents { ayahSelectorDismissRequests += 1 },
                )
            }
            val latestActiveAyahForRail by rememberUpdatedState(activeAyah)
            // The rail follows the recitation only while it is actively playing.
            // A paused surah keeps a (frozen) active ayah, so following it would
            // pin the rail to that ayah and stop it tracking the reader's own
            // scrolling — the rail is visible only when not reciting, and while
            // visible it should always mirror where the reader is looking.
            val railCurrentAyah = remember(content.surah.ayahCount) {
                derivedStateOf {
                    (latestActiveAyahForRail?.takeIf { recitingActive } ?: scrolledAyah.value)
                        .coerceIn(1, content.surah.ayahCount)
                }
            }
            val railCurrentPosition = remember(content.surah.ayahCount) {
                derivedStateOf {
                    (latestActiveAyahForRail?.takeIf { recitingActive }?.toFloat() ?: scrolledAyahPosition.value)
                        .coerceIn(1f, content.surah.ayahCount.toFloat())
                }
            }
            // Page-boundary marks for the expanded selector wheel.
            val railPageStarts = remember(content.surah.id) { pageStartByAyah(content.ayahs) }
            if (!mushafMode && editingAnnotationAyah == 0) {
                AyahSelectorRail(
                    ayahCount = content.surah.ayahCount,
                    side = selectorSide,
                    currentAyah = railCurrentAyah,
                    currentPosition = railCurrentPosition,
                    placeAyah = parkedPlaceAyah,
                    bookmarkedAyahs = bookmarkedAyahs,
                    pageStarts = railPageStarts,
                    chromeAlpha = { topBarAlpha.value },
                    interactive = !recitingActive,
                    onJumpToAyah = { ayah ->
                        dispatch(
                            ReaderInteractionEvent.JumpRequested(
                                ayah = ayah,
                                resumeFollowIfPlaying = isThisSurahPlaying,
                            ),
                        )
                        // No leaf to turn here: the rail is composed only when
                        // the reader is *not* in mushaf mode (see the gate on
                        // this whole block), so a branch turning the pager from
                        // it was unreachable. Should the rail ever be shown over
                        // a leaf, it will need turning again.
                    },
                    onExpandedChange = { expanded ->
                        ayahSelectorExpanded = expanded
                        if (expanded && ayahRailTipOpen) {
                            viewModel.dismissAyahRailTip()
                            ayahRailTipOpen = false
                        }
                    },
                    onCollapsedCenterY = { centerY -> ayahRailTipCenterY = centerY },
                    dismissRequests = ayahSelectorDismissRequests,
                    modifier = Modifier
                        .align(
                            if (selectorSide == AyahSelectorSide.RIGHT) {
                                AbsoluteAlignment.CenterRight
                            } else {
                                AbsoluteAlignment.CenterLeft
                            },
                        )
                        .fillMaxHeight()
                        .padding(top = padding.calculateTopPadding())
                        .zIndex(1f),
                )
            }

            // Scroll layout: above the scaffold PlayerBar. Mushaf hosts its
            // own roundel on the leaf's foot, above the dial and play row.
            if (!mushafMode) {
                Box(
                    contentAlignment = Alignment.BottomCenter,
                    modifier = Modifier
                        .matchParentSize()
                        .zIndex(1.2f),
                ) {
                    FloatingPaperControl(visible = showReturnToAyah) {
                        IslamicReturnToAyahButton(
                            heading = if (activeAyahPlacement.value.pointUp) {
                                ReturnArrowHeading.Up
                            } else {
                                ReturnArrowHeading.Down
                            },
                            onClick = {
                                dispatch(ReaderInteractionEvent.EnableFollow)
                            },
                        )
                    }
                }
            }

        }
    }

        // Opaque bar over the status-bar strip while reciting, so the verse
        // scrolling up never shows beneath the notch/camera. Held steady
        // across loop restarts via recitingActive.
        if (recitingActive && statusBarTop > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarTop)
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(1.5f),
            )
        }

        AyahRailTip(
            visible = ayahRailTipVisible && !mushafMode,
            railSide = settings.ayahSelectorSide,
            targetCenterY = with(density) { ayahRailTipCenterY.toDp() },
            onDismiss = {
                viewModel.dismissAyahRailTip()
                ayahRailTipOpen = false
            },
            onRenderedChange = { ayahRailTipRendered = it },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1.7f),
        )

        BookmarkNoteTip(
            visible = bookmarkNoteTipVisible && bookmarkNoteTipRibbonCenterY.isFinite(),
            ribbonSide = bookmarkTipSide,
            targetCenterY = with(density) { bookmarkNoteTipRibbonCenterY.toDp() },
            onDismiss = {
                viewModel.dismissBookmarkNoteTip()
                bookmarkNoteTipOpen = false
            },
            onRenderedChange = { rendered ->
                bookmarkNoteTipRendered = rendered
                if (!rendered && !bookmarkNoteTipOpen) {
                    bookmarkNoteTipSurah = 0
                    bookmarkNoteTipAyah = 0
                    bookmarkNoteTipRibbonCenterY = Float.NaN
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1.7f),
        )

        // Keep developer controls above the guide so its shader can be tuned
        // in place; full reader ink overlays still cover the lab at z=1.8.
        if (settings.developerModeEnabled && settings.inkLabEnabled) {
            InkLabPanel(
                guideActive = bookmarkNoteTipVisible || ayahRailTipVisible,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 10.dp)
                    .zIndex(1.75f),
            )
        }

        // The repeat question is an ink bleed on this sheet, not a dialog: the
        // shared InkRevealOverlay soaks the reader paper from the player bar's
        // repeat control, exactly as the Root Word Viewer opens. It must live
        // inside this Box so the bleed has the sheet to spread across.
        val repeatContent = uiState.content
        val repeatOverlayColors = contrastingOverlayColorScheme(settings.themeMode)
        // Read outside the `let`, and into locals.
        //
        // These four were unboxed inside the lambda, and in a debug build that
        // NPEd on `Integer.intValue()` after a page turn — reliably, twice out
        // of two. The null is not observable: logging the four values at that
        // same spot makes the crash go away, every time, and prints four
        // non-null numbers. So this does not claim to name which read came back
        // null; it stops the unboxing happening inside a nested lambda inside a
        // conditional, which is the shape the fault needs. Semantics are
        // unchanged for every case that has a defined answer, and the last
        // resort is the first verse rather than a crash.
        val activeNow = activeAyah
        val jumpNow = requestedJumpAyah.takeIf { n -> n > 0 }
        val scrolledNow: Int? = scrolledAyah.value
        val repeatStartAyah = repeatContent?.let {
            val chosen = activeNow ?: jumpNow ?: startAyah ?: scrolledNow ?: 1
            chosen.coerceIn(1, it.surah.ayahCount.coerceAtLeast(1))
        }
        InkRevealOverlay(
            visible = showRepeatDialog && repeatContent != null,
            backgroundColor = repeatOverlayColors.background,
            modifier = Modifier.zIndex(1.8f),
            originX = REPEAT_BLEED_ORIGIN_X,
            originY = REPEAT_BLEED_ORIGIN_Y,
            onRenderedChange = { repeatRendered = it },
        ) {
            MaterialTheme(
                colorScheme = repeatOverlayColors,
                typography = MaterialTheme.typography,
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Nothing beneath the bleed may be touched through it.
                    Box(Modifier.matchParentSize().absorbPointerEvents())
                    if (repeatContent != null && repeatStartAyah != null) {
                        RepeatSheet(
                            ayahCount = repeatContent.surah.ayahCount,
                            repeatMode = playerState.repeatMode,
                            repeatRange = playerState.repeatRange
                                .takeIf { playerState.nowPlaying?.surahId == renderedSurahId },
                            currentAyah = repeatStartAyah,
                            retainedChoice = retainedRepeatChoice,
                            onDismiss = { showRepeatDialog = false },
                            onRepeatMode = viewModel::setRepeatMode,
                            onRepeatRange = { from, to ->
                                dispatch(ReaderInteractionEvent.EnableFollow)
                                viewModel.setRepeatRange(from, to)
                            },
                            onChoiceApplied = { retainedRepeatChoice = it },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Where the repeat bleed starts, as a fraction of the reader sheet: low and
 * centred, on the player bar that carries the repeat control, so the ink reads
 * as spreading out of the thing that was touched.
 */
private const val REPEAT_BLEED_ORIGIN_X = 0.5f
private const val REPEAT_BLEED_ORIGIN_Y = 0.88f

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Chrome / rail / bookmark fade while recitation starts or stops. */
private const val ChromeRecedeMs = 520

/**
 * In-surah English search state: whether the top bar is in search mode, the
 * live query, and which match is current. Backed by rememberSaveable so an
 * open search survives rotation and process recreation.
 */
private class SurahSearchState(
    activeState: MutableState<Boolean>,
    queryState: MutableState<String>,
    indexState: MutableState<Int>,
) {
    var active by activeState
    var query by queryState
    var index by indexState

    /** Non-null while a usable query is live (search open, ≥ 2 chars). */
    val activeQuery: String?
        get() = query.trim().takeIf { active && it.length >= 2 }

    fun close() {
        active = false
        query = ""
        index = 0
    }
}

@Composable
private fun rememberSurahSearchState(): SurahSearchState {
    val active = rememberSaveable { mutableStateOf(false) }
    val query = rememberSaveable { mutableStateOf("") }
    val index = rememberSaveable { mutableIntStateOf(0) }
    return remember { SurahSearchState(active, query, index) }
}
