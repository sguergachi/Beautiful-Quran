package com.beautifulquran

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beautifulquran.assistant.AssistantAction
import com.beautifulquran.assistant.AssistantIntents
import com.beautifulquran.assistant.ForegroundAppFunctions
import com.beautifulquran.data.HomeBookmarkStyle
import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.ui.AppViewModelFactory
import com.beautifulquran.ui.PageTurnSounds
import com.beautifulquran.ornamentslab.OrnamentsLabScreen
import com.beautifulquran.ornamentslab.OrnamentsLabViewModel
import com.beautifulquran.ui.bookmarks.BookmarksScreen
import com.beautifulquran.ui.bookmarks.BookmarksViewModel
import com.beautifulquran.ui.entrance.EntranceCover
import com.beautifulquran.ui.entrance.readCoverWindowChrome
import com.beautifulquran.ui.theme.ornament.generateCoverOrnament
import kotlin.random.Random
import com.beautifulquran.ui.home.FloatingPlaybackCoverVisibleMaxPage
import com.beautifulquran.ui.home.FloatingPlaybackListClearance
import com.beautifulquran.ui.home.HomeScreen
import com.beautifulquran.ui.home.HomeViewModel
import com.beautifulquran.ui.reader.BackToOriginPill
import com.beautifulquran.ui.reader.ReaderPlaybackSnapshot
import com.beautifulquran.ui.reader.ReaderScreen
import com.beautifulquran.ui.reader.ReaderViewModel
import com.beautifulquran.ui.reader.RootReturnTarget
import com.beautifulquran.ui.rootviewer.RootViewerScreen
import com.beautifulquran.ui.rootviewer.RootViewerViewModel
import com.beautifulquran.ui.rootviewer.WordHoldChooser
import com.beautifulquran.ui.settings.CustomizeScreen
import com.beautifulquran.ui.settings.DownloadManagerPage
import com.beautifulquran.ui.settings.SettingsDetail
import com.beautifulquran.ui.settings.SettingsInkPreviewState
import com.beautifulquran.ui.settings.SettingsScreen
import com.beautifulquran.ui.settings.SettingsViewModel
import com.beautifulquran.ui.share.ShareHost
import com.beautifulquran.ui.share.ShareViewModel
import com.beautifulquran.share.AyahRef
import com.beautifulquran.timingslab.TimingsLabScreen
import com.beautifulquran.timingslab.TimingsLabViewModel
import com.beautifulquran.tarjilab.TarjiLabScreen
import com.beautifulquran.tarjilab.TarjiLabViewModel
import com.beautifulquran.ui.theme.BeautifulQuranTheme
import com.beautifulquran.ui.theme.FloatingPaperControl
import com.beautifulquran.ui.theme.InkRevealOverlay
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.TimingsLabAccents
import com.beautifulquran.ui.theme.absorbPointerEvents
import com.beautifulquran.ui.theme.contrastingOverlayColorScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Grace after a concordance jump so the programmatic settle / page turn
 *  that lands the jump does not arm the "Back to …" dismiss timer. */
private const val ROOT_RETURN_SETTLE_GRACE_MS = 1_200L

/** How long the "Back to …" line stays after the first hand scroll or
 *  paper-stack move. */
private const val ROOT_RETURN_DISMISS_MS = 30_000L

class MainActivity : ComponentActivity() {

    /** Assistant / deep-link / Routine actions waiting for the paper stack. */
    private val pendingAssistantAction = MutableStateFlow<AssistantAction?>(null)
    private var foregroundAppFunctions: ForegroundAppFunctions? = null

    override fun onStart() {
        super.onStart()
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            foregroundAppFunctions = ForegroundAppFunctions(this).also { it.register() }
        }
    }

    override fun onStop() {
        foregroundAppFunctions?.unregister()
        foregroundAppFunctions = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hold the leather splash until the first Compose frame paints — either
        // the closed mushaf (cold start) or the sheets (deep-link skip).
        var splashPending = true
        installSplashScreen().setKeepOnScreenCondition { splashPending }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The entrance cover is always dark. The sheet theme sets its own icon
        // appearance once Compose has its persisted settings.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // Pre-read safe insets + corner radii, and grow this launch's ornament,
        // before Compose so the first cover frame is only layout + paint.
        val coverChrome = DevProfiling.trace("coverChrome") { readCoverWindowChrome() }
        val coverOrnament = DevProfiling.trace("coverOrnament") {
            generateCoverOrnament(Random.nextInt())
        }
        DevProfiling.mark("preComposeReady")
        pendingAssistantAction.value = AssistantIntents.parse(intent)

        val app = application as QuranApp

        setContent {
            LaunchedEffect(Unit) {
                app.assistantActions.collect { pendingAssistantAction.value = it }
            }
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            val assistantAction by pendingAssistantAction.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val usesNightfall = settings.themeMode == ThemeMode.DARK ||
                (settings.themeMode == ThemeMode.SYSTEM && systemDark)
            val lightSystemBarIcons = !(usesNightfall || settings.themeMode == ThemeMode.ROYAL_GREEN)
            val view = LocalView.current
            // The entrance ceremony plays once per cold start; saveable so a
            // rotation or process-restore never replays it mid-session. Deep
            // links / Routines skip it so the user lands on the target.
            var entranceDone by rememberSaveable {
                mutableStateOf(AssistantIntents.parse(intent) != null)
            }
            LaunchedEffect(assistantAction) {
                if (assistantAction != null) entranceDone = true
            }
            // Deep links skip the cover: dismiss splash as soon as sheets draw.
            if (entranceDone) {
                SideEffect { splashPending = false }
            }

            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightSystemBarIcons
                    isAppearanceLightNavigationBars = lightSystemBarIcons
                }
            }

            BeautifulQuranTheme(themeMode = settings.themeMode) {
                // Cold start paints the closed mushaf first; the paper stack
                // mounts under it after the title settles (onWarmStack), not
                // at splash handoff — so ViewModel init cannot jank first paint.
                var stackMounted by remember { mutableStateOf(entranceDone) }
                var coverSounds by remember { mutableStateOf<PageTurnSounds?>(null) }
                DisposableEffect(Unit) {
                    onDispose { coverSounds?.release() }
                }
                Box(Modifier.fillMaxSize()) {
                    if (stackMounted) {
                        PaperStackApp(
                            themeMode = settings.themeMode,
                            developerModeEnabled = settings.developerModeEnabled,
                            homeBookmarkStyle = settings.homeBookmarkStyle,
                            entranceVisible = !entranceDone,
                            pendingAssistantAction = assistantAction,
                            onAssistantActionConsumed = {
                                pendingAssistantAction.value = null
                            },
                            onRecordSystemTrace = {
                                DevProfiling.recordSystemTrace(this@MainActivity)
                            },
                        )
                    }
                    if (!entranceDone) {
                        EntranceCover(
                            chrome = coverChrome,
                            ornament = coverOrnament,
                            onOpenBegan = {
                                val sounds = coverSounds
                                    ?: PageTurnSounds(this@MainActivity).also { coverSounds = it }
                                sounds.playCoverOpen()
                            },
                            onReady = {
                                splashPending = false
                                DevProfiling.mark("coverReady")
                            },
                            onWarmStack = {
                                DevProfiling.mark("warmStack")
                                stackMounted = true
                                if (coverSounds == null) {
                                    coverSounds = DevProfiling.trace("coverSounds") {
                                        PageTurnSounds(this@MainActivity)
                                    }
                                }
                            },
                            onFinished = {
                                entranceDone = true
                                DevProfiling.reportFullyDrawn(this@MainActivity)
                            },
                            modifier = Modifier.zIndex(1f),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAssistantAction.value = AssistantIntents.parse(intent)
    }
}

private const val BOOKMARKS_LAYER = -1
private const val COVER_LAYER = 0
private const val AYAH_LAYER = 1
private const val SETTINGS_LAYER = 2
private const val STACK_PAGE_DURATION_MS = 460
private const val STACK_PAGE_TURN_THRESHOLD = 0.15f
private const val STACK_PAGE_FLING_THRESHOLD = 0.30f
private const val STACK_PAGE_PULL_RESISTANCE_DP = 14
private const val STACK_OFFSCREEN_OVERSCAN_DP = 36f
private val StackMotionEasing = CubicBezierEasing(0.24f, 0.02f, 0.12f, 1f)

@Composable
private fun PaperStackApp(
    themeMode: ThemeMode,
    developerModeEnabled: Boolean,
    homeBookmarkStyle: HomeBookmarkStyle,
    entranceVisible: Boolean,
    pendingAssistantAction: AssistantAction? = null,
    onAssistantActionConsumed: () -> Unit = {},
    onRecordSystemTrace: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as QuranApp
    val homeViewModel: HomeViewModel = viewModel(factory = AppViewModelFactory)
    val bookmarksViewModel: BookmarksViewModel = viewModel(factory = AppViewModelFactory)
    val readerViewModel: ReaderViewModel = viewModel(factory = AppViewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory)
    val timingsLabViewModel: TimingsLabViewModel = viewModel(factory = AppViewModelFactory)
    val ornamentsLabViewModel: OrnamentsLabViewModel = viewModel(factory = AppViewModelFactory)
    val tarjiLabViewModel: TarjiLabViewModel = viewModel(factory = AppViewModelFactory)
    val rootViewerViewModel: RootViewerViewModel = viewModel(factory = AppViewModelFactory)
    val shareViewModel: ShareViewModel = viewModel(factory = AppViewModelFactory)
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val settingsInkPreview = remember {
        SettingsInkPreviewState(settings.brushCircleStyle)
    }
    val bookmarkCount by bookmarksViewModel.bookmarkCount.collectAsStateWithLifecycle()
    val shareUi by shareViewModel.ui.collectAsStateWithLifecycle()

    var selectedSurahId by rememberSaveable { mutableIntStateOf(0) }
    var selectedStartAyah by rememberSaveable { mutableIntStateOf(0) }
    var selectedStartPlayback by rememberSaveable { mutableStateOf(false) }
    /** 1-based word position from a home word-search hit; 0 means no flash. */
    var selectedStartWord by rememberSaveable { mutableIntStateOf(0) }
    var settingsDetail by rememberSaveable(
        stateSaver = Saver<SettingsDetail?, String>(
            save = { it?.name },
            restore = SettingsDetail::valueOf,
        ),
    ) { mutableStateOf<SettingsDetail?>(null) }
    var downloadsRefreshKey by remember { mutableIntStateOf(0) }
    /**
     * Remount key for the reader. Bumped on home/bookmarks/concordance/voice
     * opens so scroll state resets; **not** bumped on next-chapter advance so
     * the continuous scroll transition can keep the same composition.
     */
    var readerSession by rememberSaveable { mutableIntStateOf(0) }
    var settledLayer by rememberSaveable { mutableIntStateOf(COVER_LAYER) }
    var ayahSelectorExpanded by remember { mutableStateOf(false) }
    /** The Timings Lab is not a page in the stack: it is a work sheet that
     * rises in place over whatever is open (usually the reader) and lowers
     * back onto it, so a long-pressed word is fixed exactly where it was
     * noticed and back returns exactly there. */
    var labVisible by remember { mutableStateOf(false) }
    /** Root Word Viewer — same ink-bleed overlay pattern as the Lab. */
    var rootVisible by remember { mutableStateOf(false) }
    /** Developer-mode chooser after a word hold (Root Viewer vs Timings Lab). */
    var chooserVisible by remember { mutableStateOf(false) }
    /** The Ornaments Lab — same stack-level ink-bleed pattern as the Timings
     *  Lab; it only ever opens from Settings, never from a word hold. */
    var ornamentsLabVisible by remember { mutableStateOf(false) }
    /** The Tarjīʿ Lab — same ink-bleed overlay, reachable from a word hold
     *  (with the Timings Lab) or Settings; owns its own audio while open. */
    var tarjiLabVisible by remember { mutableStateOf(false) }
    var chooserRendered by remember { mutableStateOf(false) }
    var rootRendered by remember { mutableStateOf(false) }
    var labRendered by remember { mutableStateOf(false) }
    var ornamentsLabRendered by remember { mutableStateOf(false) }
    var tarjiLabRendered by remember { mutableStateOf(false) }
    var readerInkOverlayVisible by remember { mutableStateOf(false) }
    /** Send page (share) rendered lifetime — ShareHost reports this. */
    var shareSendRendered by remember { mutableStateOf(false) }
    var rootPlaybackSnapshot by remember { mutableStateOf<ReaderPlaybackSnapshot?>(null) }
    var pendingWord by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    /** Bumped on every concordance jump so the reader remounts even when the
     * target surah/ayah pair is unchanged. */
    var jumpEpoch by remember { mutableIntStateOf(0) }
    /** Origin verse for the "Back to …" line after a concordance jump.
     *  Owned here (not in the reader) so the line survives closing the
     *  reader sheet / returning to chapter selection. */
    var rootReturnTarget by remember { mutableStateOf<RootReturnTarget?>(null) }
    /** First hand scroll or paper-stack move after the jump settle arms a
     *  30s countdown that clears [rootReturnTarget]. */
    var rootReturnDismissArmed by remember { mutableStateOf(false) }
    val stackPosition = remember { Animatable(settledLayer.toFloat()) }
    val stackPositionProvider = remember(stackPosition) { { stackPosition.value } }
    val stackPastCover by remember {
        derivedStateOf { stackPosition.value > COVER_LAYER + 0.01f }
    }
    val coverSheetVisible by remember {
        derivedStateOf { stackPosition.value <= FloatingPlaybackCoverVisibleMaxPage }
    }
    val stackAboveReaderPlayer by remember {
        derivedStateOf { stackPosition.value in 0.5f..1.5f }
    }
    val scope = rememberCoroutineScope()
    val settingsLayer = if (selectedSurahId == 0) AYAH_LAYER else SETTINGS_LAYER
    // The stack's top sheet, read live: a detail page (Customize, Downloads)
    // raises the ceiling the moment it is asked for — a captured value would
    // still hold the old bound when settleTo runs, clamping the turn to
    // Settings and the new sheet would never arrive.
    fun maxStackLayer(): Int = if (settingsDetail != null) settingsLayer + 1 else settingsLayer
    val overlayBlocking = labVisible || rootVisible || chooserVisible || ornamentsLabVisible ||
        tarjiLabVisible ||
        labRendered || rootRendered || chooserRendered || ornamentsLabRendered || tarjiLabRendered ||
        readerInkOverlayVisible || shareUi.sendOpen || shareSendRendered
    val mushafPageTurns = settings.readingLayout == ReadingLayout.MUSHAF &&
        selectedSurahId != 0 &&
        settledLayer == AYAH_LAYER
    val stackGesturesBlocked = rememberUpdatedState(
        ayahSelectorExpanded || overlayBlocking || entranceVisible || mushafPageTurns,
    )
    val rootReturnVisible = rootReturnTarget != null && !overlayBlocking
    val onRootReturnUserMovedLatest = rememberUpdatedState {
        if (rootReturnTarget != null) rootReturnDismissArmed = true
    }

    val context = LocalContext.current
    val pageTurnSounds = remember { PageTurnSounds(context) }
    DisposableEffect(pageTurnSounds) {
        onDispose { pageTurnSounds.release() }
    }
    // Feed the live stack position to the page-turn audio every frame, so the
    // flip stems track the swipe (or settle animation) rather than firing once.
    LaunchedEffect(pageTurnSounds) {
        snapshotFlow { stackPosition.value }.collect { pageTurnSounds.onPosition(it) }
    }

    suspend fun settleTo(layer: Int) {
        val minimumLayer = if (bookmarkCount > 0) BOOKMARKS_LAYER else COVER_LAYER
        val boundedLayer = layer.coerceIn(minimumLayer, maxStackLayer())
        val distance = abs(boundedLayer - stackPosition.value)
        if (settingsDetail == SettingsDetail.DOWNLOADS &&
            boundedLayer == settingsLayer &&
            stackPosition.value > settingsLayer + 0.01f
        ) {
            downloadsRefreshKey++
        }
        settledLayer = boundedLayer
        stackPosition.animateTo(
            targetValue = boundedLayer.toFloat(),
            animationSpec = tween(
                durationMillis = (STACK_PAGE_DURATION_MS * distance).roundToInt()
                    .coerceAtLeast(STACK_PAGE_DURATION_MS / 2),
                easing = StackMotionEasing,
            ),
        )
    }

    fun animateTo(layer: Int) {
        scope.launch { settleTo(layer) }
    }

    LaunchedEffect(bookmarkCount) {
        if (bookmarkCount == 0 && stackPosition.value < COVER_LAYER) {
            settleTo(COVER_LAYER)
        }
    }

    fun openTimingsLab(surahId: Int? = null, ayah: Int? = null, wordPosition: Int? = null) {
        if (!developerModeEnabled) return
        chooserVisible = false
        rootVisible = false
        if (surahId != null && ayah != null) {
            timingsLabViewModel.changeTarget(surahId, ayah, wordPosition)
        } else {
            timingsLabViewModel.initFromLastOpened()
        }
        labVisible = true
    }

    fun closeTimingsLab() {
        if (!labVisible) return
        timingsLabViewModel.onExit()
        labVisible = false
    }

    fun openOrnamentsLab() {
        if (!developerModeEnabled) return
        ornamentsLabVisible = true
    }

    fun closeOrnamentsLab() {
        ornamentsLabVisible = false
    }

    fun openTarjiLab(surahId: Int? = null, ayah: Int? = null, wordPosition: Int? = null) {
        if (!developerModeEnabled) return
        chooserVisible = false
        rootVisible = false
        labVisible = false
        if (surahId != null && ayah != null) {
            tarjiLabViewModel.changeTarget(surahId, ayah, wordPosition)
        } else {
            tarjiLabViewModel.initFromLastOpened()
        }
        tarjiLabVisible = true
    }

    fun closeTarjiLab() {
        if (!tarjiLabVisible) return
        tarjiLabViewModel.onExit()
        tarjiLabVisible = false
    }

    fun openRootViewer(surahId: Int, ayah: Int, wordPosition: Int) {
        chooserVisible = false
        labVisible = false
        rootPlaybackSnapshot = readerViewModel.pauseForRootViewer()
        rootViewerViewModel.open(surahId, ayah, wordPosition)
        rootVisible = true
    }

    fun closeRootViewer(resumeReading: Boolean = true) {
        if (!rootVisible) return
        rootVisible = false
        rootViewerViewModel.clear()
        val snapshot = rootPlaybackSnapshot
        rootPlaybackSnapshot = null
        if (resumeReading && snapshot != null) {
            readerViewModel.resumeAfterRootViewer(snapshot)
        }
    }

    fun openVerseFromAssistant(surahId: Int, ayah: Int, play: Boolean = false) {
        if (surahId !in 1..114) return
        val startAyah = ayah.coerceAtLeast(1)
        // load() is a no-op when this surah is already open — still pass play
        // so "play chapter 2" restarts recitation in place.
        readerViewModel.load(surahId, startPlaybackAtAyah = startAyah.takeIf { play })
        selectedSurahId = surahId
        selectedStartAyah = startAyah
        selectedStartPlayback = play
        selectedStartWord = 0
        jumpEpoch++
        readerSession++
        animateTo(AYAH_LAYER)
    }

    fun fulfillAssistantAction(action: AssistantAction) {
        // A voice command answers on the page itself — any ink surface riding
        // over the stack (labs, root lexicon, chooser) would swallow the
        // response, so lower them before the stack moves.
        chooserVisible = false
        pendingWord = null
        closeTimingsLab()
        closeOrnamentsLab()
        closeTarjiLab()
        closeRootViewer(resumeReading = false)
        when (action) {
            is AssistantAction.OpenVerse -> {
                openVerseFromAssistant(action.surahId, action.ayah, play = action.play)
            }
            AssistantAction.OpenBookmarks -> {
                if (bookmarkCount > 0) {
                    animateTo(BOOKMARKS_LAYER)
                } else {
                    animateTo(COVER_LAYER)
                }
            }
            AssistantAction.OpenChapters -> animateTo(COVER_LAYER)
            AssistantAction.OpenSettings -> animateTo(settingsLayer)
            is AssistantAction.Search -> {
                homeViewModel.onQueryChange(action.query)
                animateTo(COVER_LAYER)
            }
            is AssistantAction.ContinueReading -> {
                val surah = settings.lastSurah
                if (surah in 1..114) {
                    openVerseFromAssistant(
                        surah,
                        settings.lastAyah.coerceAtLeast(1),
                        play = action.play,
                    )
                } else {
                    // No saved position yet — open Al-Fatiha rather than looking broken.
                    openVerseFromAssistant(1, 1, play = action.play)
                }
            }
            AssistantAction.SaveBookmark -> {
                // Prefer the verse currently focused in the reader (last active
                // ayah), not the open-start ayah — that was bookmarking the
                // wrong place after the user scrolled.
                val fromReader = readerViewModel.currentVerseForBookmark()
                val surah = fromReader?.first
                    ?: selectedSurahId.takeIf { it in 1..114 }
                    ?: settings.lastSurah.takeIf { it in 1..114 }
                    ?: return
                val ayah = fromReader?.second
                    ?: if (settings.lastSurah == surah) {
                        settings.lastAyah.coerceAtLeast(1)
                    } else {
                        selectedStartAyah.coerceAtLeast(1)
                    }
                app.bookmarks.ensure(surah, ayah)
                // Stay put when already on this chapter so the ribbon appears
                // in place; only jump when we need to open the reader.
                if (selectedSurahId != surah) {
                    openVerseFromAssistant(surah, ayah)
                } else {
                    animateTo(AYAH_LAYER)
                }
            }
        }
    }

    // Deep links, pinned shortcuts, and App Actions land here once the stack is up.
    LaunchedEffect(pendingAssistantAction) {
        val action = pendingAssistantAction ?: return@LaunchedEffect
        fulfillAssistantAction(action)
        onAssistantActionConsumed()
    }

    fun onWordLongPress(surahId: Int, ayah: Int, wordPosition: Int) {
        if (developerModeEnabled) {
            pendingWord = Triple(surahId, ayah, wordPosition)
            chooserVisible = true
        } else {
            openRootViewer(surahId, ayah, wordPosition)
        }
    }

    fun jumpFromConcordance(surahId: Int, ayah: Int) {
        val origin = rootViewerViewModel.ui.value
        // Remember where the hold started so the reader can offer a way back.
        if (origin.surahId > 0 && origin.ayah > 0 &&
            (origin.surahId != surahId || origin.ayah != ayah)
        ) {
            rootReturnTarget = RootReturnTarget(
                surahId = origin.surahId,
                ayah = origin.ayah,
                surahNameTransliteration = origin.surahNameTransliteration,
            )
        }
        closeRootViewer(resumeReading = false)
        if (surahId != selectedSurahId) {
            readerViewModel.load(surahId)
        }
        selectedSurahId = surahId
        selectedStartAyah = ayah
        selectedStartPlayback = false
        selectedStartWord = 0
        jumpEpoch++
        readerSession++
        animateTo(AYAH_LAYER)
    }

    fun returnFromConcordanceJump() {
        val target = rootReturnTarget ?: return
        rootReturnTarget = null
        rootReturnDismissArmed = false
        if (target.surahId != selectedSurahId) {
            readerViewModel.load(target.surahId)
        }
        selectedSurahId = target.surahId
        selectedStartAyah = target.ayah
        selectedStartPlayback = false
        selectedStartWord = 0
        jumpEpoch++
        readerSession++
        animateTo(AYAH_LAYER)
    }

    // Concordance "Back to …" line: stay across the paper stack (including
    // chapter selection) until the reader moves by hand or turns a page,
    // then clear it 30s after that first move. Ignore the programmatic
    // settle that lands the jump itself.
    LaunchedEffect(rootReturnTarget) {
        rootReturnDismissArmed = false
        if (rootReturnTarget == null) return@LaunchedEffect
        delay(ROOT_RETURN_SETTLE_GRACE_MS)
        val baseline = stackPosition.value
        snapshotFlow { stackPosition.value }.collect { pos ->
            if (!rootReturnDismissArmed && abs(pos - baseline) > 0.05f) {
                rootReturnDismissArmed = true
            }
        }
    }
    LaunchedEffect(rootReturnDismissArmed, rootReturnTarget) {
        if (!rootReturnDismissArmed || rootReturnTarget == null) return@LaunchedEffect
        delay(ROOT_RETURN_DISMISS_MS)
        rootReturnTarget = null
    }

    BackHandler(enabled = settledLayer > COVER_LAYER || stackPastCover) {
        animateTo((stackPosition.value.roundToInt() - 1).coerceAtLeast(COVER_LAYER))
    }
    BackHandler(enabled = settledLayer < COVER_LAYER || stackPosition.value < -0.01f) {
        animateTo(COVER_LAYER)
    }
    BackHandler(enabled = settingsDetail != null && settledLayer > settingsLayer) {
        // Back from a detail page lands on Settings, but the page itself
        // stays where it is — the leaf you turned back from is still the
        // one under your thumb, so swiping forward from Settings re-opens
        // exactly that sheet.
        animateTo(settingsLayer)
    }
    // Composed after the stack handler so overlay backs dismiss the bleed
    // instead of turning the page beneath it.
    BackHandler(enabled = chooserVisible) {
        chooserVisible = false
        pendingWord = null
    }
    BackHandler(enabled = rootVisible) { closeRootViewer() }
    BackHandler(enabled = labVisible) { closeTimingsLab() }
    BackHandler(enabled = ornamentsLabVisible) { closeOrnamentsLab() }
    BackHandler(enabled = tarjiLabVisible) { closeTarjiLab() }

    LaunchedEffect(selectedSurahId) {
        ayahSelectorExpanded = false
        if (selectedSurahId == 0) settleTo(COVER_LAYER)
    }

    var dragStartPosition by remember { mutableFloatStateOf(0f) }
    var dragSnapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .paperStackDrag(
                gestureKey = selectedSurahId,
                position = { stackPosition.value },
                minLayer = {
                    if (bookmarkCount > 0) BOOKMARKS_LAYER else COVER_LAYER
                },
                // When no surah is open, Settings occupies layer 1 and is
                // reachable by swiping from Chapters. With a reader open,
                // Settings sits at layer 2 (Cover → Reader → Settings).
                // Detail sheets (Customize, Downloads) sit one layer beyond.
                maxLayer = { maxStackLayer() },
                // The pointerInput coroutine is intentionally keyed only by
                // navigation identity. Read a stable state holder here so the
                // long-lived detector sees overlays that open after it starts.
                gesturesBlocked = { stackGesturesBlocked.value },
                onDragStart = {
                    dragStartPosition = stackPosition.value
                },
                onDrag = { deltaPages ->
                    // A single gesture may advance at most one layer, so a hard swipe
                    // from the cover lands on the next sheet instead of overshooting.
                    val startLayer = dragStartPosition.roundToInt()
                    val minimumLayer = if (bookmarkCount > 0) {
                        BOOKMARKS_LAYER
                    } else {
                        COVER_LAYER
                    }
                    val lower = (startLayer - 1).coerceAtLeast(minimumLayer).toFloat()
                    val upper = (startLayer + 1).coerceAtMost(maxStackLayer()).toFloat()
                    dragSnapJob?.cancel()
                    dragSnapJob = scope.launch {
                        stackPosition.snapTo(
                            (dragStartPosition + deltaPages).coerceIn(lower, upper),
                        )
                    }
                },
                onSettle = { target ->
                    dragSnapJob?.cancel()
                    animateTo(target)
                },
            ),
    ) {
        PaperPage(
            layer = PaperLayer.Bookmarks,
            stackPosition = stackPositionProvider,
            settingsLayer = settingsLayer,
            // The left index is a top sheet: it slides over Chapters rather
            // than exposing another copy of the cover underneath it.
            modifier = Modifier.zIndex(4f),
        ) {
            BookmarksScreen(
                viewModel = bookmarksViewModel,
                sheetVisible = { stackPosition.value <= BOOKMARKS_LAYER + 0.02f },
                onClose = { animateTo(COVER_LAYER) },
                onOpenAyah = { surahId, ayah ->
                    if (surahId != selectedSurahId) readerViewModel.load(surahId)
                    selectedSurahId = surahId
                    selectedStartAyah = ayah
                    selectedStartPlayback = false
                    selectedStartWord = 0
                    jumpEpoch++
                    readerSession++
                    animateTo(AYAH_LAYER)
                },
            )
        }

        PaperPage(
            layer = PaperLayer.Settings,
            stackPosition = stackPositionProvider,
            settingsLayer = settingsLayer,
            modifier = Modifier.zIndex(0f),
        ) {
            SettingsScreen(
                viewModel = settingsViewModel,
                inkPreview = settingsInkPreview,
                downloadsRefreshKey = downloadsRefreshKey,
                onBack = {
                    animateTo(if (selectedSurahId == 0) COVER_LAYER else AYAH_LAYER)
                },
                onOpenCustomize = {
                    settingsDetail = SettingsDetail.CUSTOMIZE
                    animateTo(settingsLayer + 1)
                },
                onOpenDownloads = {
                    settingsDetail = SettingsDetail.DOWNLOADS
                    animateTo(settingsLayer + 1)
                },
                onOpenTimingsLab = { openTimingsLab() },
                onOpenTarjiLab = { openTarjiLab() },
                onOpenOrnamentsLab = { openOrnamentsLab() },
                onRecordSystemTrace = onRecordSystemTrace,
            )
        }

        if (settingsDetail != null) {
            PaperPage(
                layer = PaperLayer.Detail,
                stackPosition = stackPositionProvider,
                settingsLayer = settingsLayer,
                // Beneath Settings: the detail page is what the settings
                // sheet turns away to reveal, so it must draw under it.
                modifier = Modifier.zIndex(-0.5f),
            ) {
                when (settingsDetail) {
                    SettingsDetail.CUSTOMIZE -> CustomizeSheet(
                        viewModel = settingsViewModel,
                        inkPreview = settingsInkPreview,
                        // Back keeps the page where it is: the leaf you turned
                        // back from stays under your thumb, so swiping forward
                        // from Settings re-opens exactly that sheet.
                        onBack = { animateTo(settingsLayer) },
                    )
                    SettingsDetail.DOWNLOADS -> DownloadsSheet(
                        viewModel = settingsViewModel,
                        onBack = { animateTo(settingsLayer) },
                    )
                    null -> {}
                }
            }
        }

        if (selectedSurahId != 0) {
            // While the root lexicon (or hold chooser) is open, lift this sheet
            // above the cover so the ink wash stays on the paper it soaks —
            // not trapped under the off-screen cover's higher stack z-index.
            val readerBleedOpen = rootVisible || chooserVisible || shareUi.sendOpen || shareSendRendered
            PaperPage(
                layer = PaperLayer.Ayah,
                stackPosition = stackPositionProvider,
                settingsLayer = settingsLayer,
                modifier = Modifier.zIndex(if (readerBleedOpen) 3f else 1f),
            ) {
                key(readerSession) {
                    ReaderScreen(
                        surahId = selectedSurahId,
                        startAyah = selectedStartAyah.takeIf { it > 0 },
                        startPlaybackRequested = selectedStartPlayback,
                        startWordPosition = selectedStartWord.takeIf { it > 0 },
                        viewModel = readerViewModel,
                        onBack = { animateTo(COVER_LAYER) },
                        onOpenSettings = { animateTo(SETTINGS_LAYER) },
                        onOpenNextChapter = { nextId ->
                            // Content is already installed by the reader's
                            // continuous-scroll advance — only sync the sheet id.
                            selectedSurahId = nextId
                            selectedStartAyah = 0
                            selectedStartPlayback = false
                            selectedStartWord = 0
                        },
                        onOpenPreviousChapter = { prevId ->
                            selectedSurahId = prevId
                            selectedStartAyah = 0
                            selectedStartPlayback = false
                            selectedStartWord = 0
                        },
                        onAyahSelectorExpandedChange = { ayahSelectorExpanded = it },
                        onOpenRootViewer = { sid, a, word -> onWordLongPress(sid, a, word) },
                        onRootReturnUserMoved = { onRootReturnUserMovedLatest.value() },
                        rootReturnVisible = rootReturnVisible,
                        keepStatusBarVisible = overlayBlocking,
                        onInkOverlayVisibilityChange = { readerInkOverlayVisible = it },
                        gathering = shareUi.gathering,
                        gatherOrdinal = { sid, a -> shareUi.ordinals[AyahRef(sid, a)] },
                        onToggleGatheredAyah = shareViewModel::toggle,
                    )
                }

                // Gather/Send: back handling + Send ink-bleed (text share PR1).
                ShareHost(
                    viewModel = shareViewModel,
                    themeMode = themeMode,
                    onOverlayRenderedChange = { shareSendRendered = it },
                )

                // Root lexicon + hold chooser live ON this sheet — ink soaks
                // the reader paper, not a full-screen layer above the stack.
                // (Timings Lab stays stack-level: it can also open from Settings.)
                val overlayColors = contrastingOverlayColorScheme(themeMode)
                InkRevealOverlay(
                    visible = chooserVisible,
                    backgroundColor = overlayColors.background,
                    modifier = Modifier.zIndex(3f),
                    onRenderedChange = { chooserRendered = it },
                ) {
                    MaterialTheme(colorScheme = overlayColors, typography = MaterialTheme.typography) {
                        CompositionLocalProvider(LocalQuranAccents provides TimingsLabAccents) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.matchParentSize().absorbPointerEvents())
                                WordHoldChooser(
                                    onOpenRootViewer = {
                                        val target = pendingWord ?: return@WordHoldChooser
                                        pendingWord = null
                                        openRootViewer(target.first, target.second, target.third)
                                    },
                                    onOpenTimingsLab = {
                                        val target = pendingWord ?: return@WordHoldChooser
                                        pendingWord = null
                                        openTimingsLab(target.first, target.second, target.third)
                                    },
                                    onOpenTarjiLab = {
                                        val target = pendingWord ?: return@WordHoldChooser
                                        pendingWord = null
                                        openTarjiLab(target.first, target.second, target.third)
                                    },
                                    onDismiss = {
                                        chooserVisible = false
                                        pendingWord = null
                                    },
                                )
                            }
                        }
                    }
                }
                InkRevealOverlay(
                    visible = rootVisible,
                    backgroundColor = overlayColors.background,
                    modifier = Modifier.zIndex(4f),
                    onRenderedChange = { rootRendered = it },
                ) {
                    MaterialTheme(colorScheme = overlayColors, typography = MaterialTheme.typography) {
                        CompositionLocalProvider(LocalQuranAccents provides TimingsLabAccents) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.matchParentSize().absorbPointerEvents())
                                RootViewerScreen(
                                    viewModel = rootViewerViewModel,
                                    onBack = ::closeRootViewer,
                                    onJumpToOccurrence = ::jumpFromConcordance,
                                )
                            }
                        }
                    }
                }
            }
        }

        PaperPage(
            layer = PaperLayer.Cover,
            stackPosition = stackPositionProvider,
            settingsLayer = settingsLayer,
            modifier = Modifier.zIndex(2f),
        ) {
            HomeScreen(
                viewModel = homeViewModel,
                onOpenSurah = { surahId, ayah, wordPosition ->
                    readerViewModel.load(surahId)
                    selectedSurahId = surahId
                    selectedStartAyah = ayah ?: 0
                    selectedStartPlayback = false
                    selectedStartWord = wordPosition ?: 0
                    readerSession++
                    animateTo(AYAH_LAYER)
                },
                onOpenSettings = { animateTo(SETTINGS_LAYER) },
                // Drive the float's enter/exit from the live page turn so it
                // slides in when returning to chapter selection and out when
                // leaving for the reader — not only when nowPlaying flips.
                coverSheetVisible = coverSheetVisible,
                bookmarkCount = bookmarkCount,
                bookmarkStyle = homeBookmarkStyle,
                onOpenBookmarks = { animateTo(BOOKMARKS_LAYER) },
            )
        }

        // Concordance "Back to …" — opaque floating capsule above the paper
        // stack so it survives closing the reader / returning to chapter
        // selection. Same FloatingPaperControl host (enter/exit + bottom
        // inset) as the return-to-ayah roundel. On the cover it clears the
        // floating playback transport; on the reader it clears the embedded
        // player bar.
        val abovePlayer = stackAboveReaderPlayer && selectedSurahId != 0
        FloatingPaperControl(
            visible = rootReturnVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    bottom = if (abovePlayer) {
                        100.dp
                    } else {
                        FloatingPlaybackListClearance
                    },
                )
                .zIndex(2.5f),
        ) {
            val target = rootReturnTarget
            if (target != null) {
                BackToOriginPill(
                    target = target,
                    onClick = ::returnFromConcordanceJump,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
            }
        }

        // The Lab blooms in as a contrasting ink spot over the stack — the
        // same ink-bleed language as the notification prompt — and closes by
        // opening a hole back to the exact page it came from. Hosted above
        // the sheets because Settings can open it too (not only the reader).
        val overlayColors = contrastingOverlayColorScheme(themeMode)
        InkRevealOverlay(
            visible = labVisible,
            backgroundColor = overlayColors.background,
            modifier = Modifier.zIndex(5f),
            onRenderedChange = { labRendered = it },
        ) {
            MaterialTheme(colorScheme = overlayColors, typography = MaterialTheme.typography) {
                CompositionLocalProvider(LocalQuranAccents provides TimingsLabAccents) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.matchParentSize().absorbPointerEvents())
                        TimingsLabScreen(
                            viewModel = timingsLabViewModel,
                            onBack = ::closeTimingsLab,
                        )
                    }
                }
            }
        }

        // The Ornaments Lab — same ink-bleed language, its own overlay slot
        // since it can rise independently of the Timings Lab (both are
        // Settings-only entry points, never simultaneous, but kept distinct
        // so each closes back to exactly the page it bloomed from).
        InkRevealOverlay(
            visible = ornamentsLabVisible,
            backgroundColor = overlayColors.background,
            modifier = Modifier.zIndex(5f),
            onRenderedChange = { ornamentsLabRendered = it },
        ) {
            MaterialTheme(colorScheme = overlayColors, typography = MaterialTheme.typography) {
                CompositionLocalProvider(LocalQuranAccents provides TimingsLabAccents) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.matchParentSize().absorbPointerEvents())
                        OrnamentsLabScreen(
                            viewModel = ornamentsLabViewModel,
                            onBack = ::closeOrnamentsLab,
                        )
                    }
                }
            }
        }

        // The Tarjīʿ Lab — the same ink-bleed language, its own slot: it can
        // rise from a word hold or Settings and owns its audio while open
        // (the captured word loops on its own track), so it must be able to
        // close back to exactly the page it bloomed from.
        InkRevealOverlay(
            visible = tarjiLabVisible,
            backgroundColor = overlayColors.background,
            modifier = Modifier.zIndex(5f),
            onRenderedChange = { tarjiLabRendered = it },
        ) {
            MaterialTheme(colorScheme = overlayColors, typography = MaterialTheme.typography) {
                CompositionLocalProvider(LocalQuranAccents provides TimingsLabAccents) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.matchParentSize().absorbPointerEvents())
                        TarjiLabScreen(
                            viewModel = tarjiLabViewModel,
                            onBack = ::closeTarjiLab,
                        )
                    }
                }
            }
        }
    }

}

@Composable
private fun CustomizeSheet(
    viewModel: com.beautifulquran.ui.settings.SettingsViewModel,
    inkPreview: SettingsInkPreviewState,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.settings.collectAsStateWithLifecycle()
    CustomizeScreen(
        settings = settings,
        brushParams = inkPreview.brushParams,
        paintToken = inkPreview.paintToken,
        checkParams = inkPreview.checkParams,
        checkPaintToken = inkPreview.checkPaintToken,
        onBack = onBack,
        onUpdate = { transform -> viewModel.settings.update(transform) },
    )
}

@Composable
private fun DownloadsSheet(
    viewModel: com.beautifulquran.ui.settings.SettingsViewModel,
    onBack: () -> Unit,
) {
    val reciters by viewModel.reciters.collectAsStateWithLifecycle()
    val surahs by viewModel.surahs.collectAsStateWithLifecycle()
    DownloadManagerPage(
        reciters = reciters,
        surahs = surahs,
        onBack = onBack,
    )
}

private enum class PaperLayer {
    Bookmarks,
    Settings,
    Ayah,
    Cover,
    Detail,
}

@Composable
private fun PaperPage(
    layer: PaperLayer,
    stackPosition: () -> Float,
    settingsLayer: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .paperLayerTransform(layer, stackPosition, settingsLayer)
            .paperDropShadow(layer, stackPosition, settingsLayer),
    ) {
        content()
    }
}

private fun Modifier.paperLayerTransform(
    layer: PaperLayer,
    stackPosition: () -> Float,
    settingsLayer: Int,
): Modifier = graphicsLayer {
    val position = stackPosition()
    val width = size.width
    cameraDistance = 18f * density
    when (layer) {
        PaperLayer.Bookmarks -> {
            val reveal = (-position).coerceIn(0f, 1f)
            // A genuine top sheet entering from the left. At Chapters it is
            // parked wholly beyond the edge; pulling right lays it over Home.
            translationX = -(width + STACK_OFFSCREEN_OVERSCAN_DP * density) *
                (1f - reveal)
            rotationY = -4f * (1f - reveal)
            shadowElevation = 22f * reveal
            alpha = if (position <= 0f) 1f else 0f
        }
        PaperLayer.Cover -> {
            val forwardTurn = position.coerceIn(0f, 1f)
            translationX = -(width + STACK_OFFSCREEN_OVERSCAN_DP * density) * forwardTurn
            rotationY = -5f * forwardTurn
            shadowElevation = 22f * (1f - forwardTurn)
        }
        PaperLayer.Ayah -> {
            val reveal = position.coerceIn(0f, 1f)
            val turn = (position - 1f).coerceIn(0f, 1f)
            // Overscan past full width so rotationY foreshortening cannot leave
            // the ayah rail peeking over Settings once this sheet has turned.
            translationX = width * 0.055f * (1f - reveal) -
                (width + STACK_OFFSCREEN_OVERSCAN_DP * density) * turn
            scaleX = 0.985f + 0.015f * reveal
            scaleY = 0.985f + 0.015f * reveal
            rotationY = -4f * turn
            shadowElevation = 18f * (1f - turn)
        }
        PaperLayer.Settings -> {
            // Under the sheets above, no lateral shift — until a detail sheet
            // (Customize, Downloads) is asked for: then Settings itself turns
            // away like the Cover does, revealing the detail page beneath it.
            val reveal = (position / settingsLayer.toFloat()).coerceIn(0f, 1f)
            val turn = (position - settingsLayer.toFloat()).coerceIn(0f, 1f)
            translationX = -(width + STACK_OFFSCREEN_OVERSCAN_DP * density) * turn
            rotationY = -4f * turn
            shadowElevation = 18f * (1f - turn)
            scaleX = 0.985f + 0.015f * reveal
            scaleY = 0.985f + 0.015f * reveal
        }
        PaperLayer.Detail -> {
            // The detail sheet is revealed from under Settings as Settings
            // turns away — the same underneath-page the reader is to the
            // cover. It sits a touch small and shifted right at rest-past,
            // settling up into place as the turn completes.
            val reveal = (position - settingsLayer.toFloat()).coerceIn(0f, 1f)
            translationX = width * 0.055f * (1f - reveal)
            scaleX = 0.985f + 0.015f * reveal
            scaleY = 0.985f + 0.015f * reveal
        }
    }
}

// A lifted sheet casts a soft shadow onto the page beneath it, spilling just
// past its leading edge rather than darkening the sheet's own edge. The cast
// is strongest mid-swipe and fades to nothing once either sheet settles.
private fun Modifier.paperDropShadow(
    layer: PaperLayer,
    stackPosition: () -> Float,
    settingsLayer: Int,
): Modifier = drawWithContent {
    drawContent()
    val position = stackPosition()
    val turning = when (layer) {
        PaperLayer.Bookmarks -> (-position).coerceIn(0f, 1f)
        PaperLayer.Cover -> position.coerceIn(0f, 1f)
        PaperLayer.Ayah -> (position - 1f).coerceIn(0f, 1f)
        // Settings casts onto the detail sheet while it turns away to it.
        PaperLayer.Settings -> (position - settingsLayer.toFloat()).coerceIn(0f, 1f)
        PaperLayer.Detail -> 0f
    }
    val depth = (4f * turning * (1f - turning)).coerceIn(0f, 1f)
    if (depth > 0.01f) {
        val shadowWidth = 24.dp.toPx()
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    ComposeColor.Black.copy(alpha = 0.26f * depth),
                    ComposeColor.Black.copy(alpha = 0.09f * depth),
                    ComposeColor.Transparent,
                ),
                startX = size.width,
                endX = size.width + shadowWidth,
            ),
            topLeft = Offset(size.width, 0f),
            size = Size(shadowWidth, size.height),
        )
    }
}

private fun Modifier.paperStackDrag(
    gestureKey: Any,
    position: () -> Float,
    minLayer: () -> Int,
    maxLayer: () -> Int,
    gesturesBlocked: () -> Boolean,
    onDragStart: () -> Unit,
    onDrag: (deltaPages: Float) -> Unit,
    onSettle: (targetLayer: Int) -> Unit,
): Modifier = pointerInput(gestureKey) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Main,
        )
        if (gesturesBlocked()) {
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
            } while (event.changes.any { it.pressed })
            return@awaitEachGesture
        }
        val velocityTracker = VelocityTracker()
        val touchSlop = viewConfiguration.touchSlop
        val width = size.width.toFloat().coerceAtLeast(1f)
        val pullResistance = maxOf(
            touchSlop,
            minOf(STACK_PAGE_PULL_RESISTANCE_DP.dp.toPx(), width * 0.04f),
        )
        var horizontalDrag = false
        var axisDecided = false
        var startLayer = position().roundToInt()
        var startPosition = position()
        var totalDx = 0f
        var totalDy = 0f
        velocityTracker.addPosition(down.uptimeMillis, down.position)

        while (true) {
            if (gesturesBlocked()) break
            // Watch the Main pass so child controls get first claim. This keeps
            // controls such as the text-size slider from becoming page swipes.
            // The sheet only claims a gesture after a clear horizontal pull.
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!horizontalDrag && change.isConsumed) break
            val delta = change.positionChange()
            totalDx += delta.x
            totalDy += delta.y
            velocityTracker.addPosition(change.uptimeMillis, change.position)

            if (!horizontalDrag) {
                if (!axisDecided) {
                    // Decide the axis at touch-slop scale — the same distance
                    // at which child scrollables lock — so the dominant
                    // direction wins a diagonal. Waiting longer means the
                    // reader's vertical scroll claims nearly every swipe.
                    if (maxOf(abs(totalDx), abs(totalDy)) > touchSlop) {
                        axisDecided = true
                        if (abs(totalDx) > abs(totalDy) * 1.1f) {
                            horizontalDrag = true
                            startPosition = position()
                            startLayer = startPosition.roundToInt()
                            onDragStart()
                        }
                    }
                } else {
                    // A gesture that opened vertical over a static area can
                    // still become a page turn on a clear horizontal pull.
                    val mostlyHorizontal = abs(totalDx) > abs(totalDy) * 1.35f
                    if (abs(totalDx) > pullResistance && mostlyHorizontal) {
                        horizontalDrag = true
                        startPosition = position()
                        startLayer = startPosition.roundToInt()
                        onDragStart()
                    }
                }
            }

            if (horizontalDrag) {
                onDrag(-resistedSwipeDistance(totalDx, pullResistance) / width)
                change.consume()
            }

            if (!change.pressed) break
        }

        if (horizontalDrag) {
            val dragPages = -resistedSwipeDistance(totalDx, pullResistance) / width
            val draggedPosition = startPosition + dragPages
            val velocityPages = -velocityTracker.calculateVelocity().x / width
            val turnDirection = when {
                dragPages > STACK_PAGE_TURN_THRESHOLD ||
                    velocityPages > STACK_PAGE_FLING_THRESHOLD -> 1
                dragPages < -STACK_PAGE_TURN_THRESHOLD ||
                    velocityPages < -STACK_PAGE_FLING_THRESHOLD -> -1
                else -> 0
            }
            // Clamp the fling to one layer per gesture so a hard swipe settles on the
            // adjacent page rather than overshooting past it.
            val lower = (startLayer - 1).coerceAtLeast(minLayer())
            val upper = (startLayer + 1).coerceAtMost(maxLayer())
            val target = if (turnDirection == 0) {
                draggedPosition.roundToInt()
            } else {
                startLayer + turnDirection
            }.coerceIn(lower, upper)
            onSettle(target)
        }
    }
}

private fun resistedSwipeDistance(distance: Float, resistance: Float): Float = when {
    distance > resistance -> distance - resistance
    distance < -resistance -> distance + resistance
    else -> 0f
}
