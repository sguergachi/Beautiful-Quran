package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLocaleList
import com.beautifulquran.ui.theme.LocalQuranAccents
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.MushafType
import com.beautifulquran.domain.mushafIsOpeningLeaf
import com.beautifulquran.domain.mushafLineMayStandShort
import com.beautifulquran.domain.surahOpensWithBasmalahPreface
import androidx.compose.foundation.Canvas
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.beautifulquran.R
import com.beautifulquran.ui.theme.MUSHAF_BASMALAH_INK_MID_EM
import com.beautifulquran.ui.theme.MUSHAF_BASMALAH_GLYPH
import com.beautifulquran.ui.theme.MUSHAF_BASMALAH_HAND_SCALE
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.snap
import com.beautifulquran.ui.theme.letterFadeIn
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.setManagedColor

// One Madinah leaf: its face, its lines, its chapter bands and basmalah, and
// the ink clocks that drive them (MushafPageSheet); the pager that turns the
// leaves is MushafPager.kt.

/**
 * A line is measured as one concatenated run but drawn one [Text] per word,
 * so per-word rounding can sum a hair wider. Size against a slightly narrower
 * page than the one that draws it — a full line then lands inside the box
 * instead of exactly on it.
 */
private val MushafFitSlack = 2.dp


/**
 * How long a leaf waits for its own face before showing itself in the Hafs
 * stand-in. Long enough for a warm cache hit or a cold read off the asset,
 * short enough that a reader never sits looking at blank paper.
 */
private const val MushafLeafFaceWaitMs = 450L

/**
 * The page's own hand, as the chrome sees it: the size a line of revelation is
 * set in on this leaf, which anchors the whole type scale (see [MushafType]).
 * Read from the grid rather than measured, so the running head and folio can
 * be sized before a page font has even loaded.
 */
/**
 * How deep the chapter's panel is ruled, as a share of the line slot it stands
 * in — and so, by what is left over, how much air it keeps on each side.
 *
 * The panel is a plate set into the page, not another line of it, and it was
 * ruled at 0.94 of its slot: three percent of air a side, which is none. On the
 * last leaf of the Qur'an, where three chapters open, the line above each panel
 * closed to 10-16px of it while the basmalah below stood at 23 — cramped, and
 * visibly unequal.
 *
 * A slot is a line's ink and the leading around it. Ruling the panel to the ink
 * alone leaves that leading as the panel's own air, half above and half below,
 * equal by construction and about a seventh of a line each way. It is the same
 * law the English leaf sets its ʿunwān by, which rules its band to one line's
 * measured ink and centres it in a box of that plus air
 * (`EnglishLeafPanelAir`) — one panel, one grammar, whichever language the leaf
 * is set in.
 *
 * Nothing else moves. The slot is the same slot, so the leaf still spends one
 * line of its grid on an opening and the hand is still the one hand of all 604
 * pages; only the rules inside the slot come in.
 */
private const val MushafPanelBand = 0.72f

/**
 * The chapter's name inside the panel, against the page's own hand.
 *
 * It stood a step above the page at 1.08, which the old band was deep enough to
 * carry. Ruled to the ink the cartouche is a quarter shallower, and a name set
 * larger than the band stops fitting inside it. Under the hand rather than over
 * it, as the English leaf sets the same panel.
 */
private const val MushafPanelType = 0.95f

@Composable
internal fun MushafPageSheet(
    /** Told whether the leaf's entrance has finished. */
    leafStill: MutableState<Boolean>,
    /** Where the leaf's live-drawn ornaments register (see [drawnLiveOverPicture]). */
    leafOverlay: LeafOverlay,
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
    flashWordPositions: Set<Int>,
    searchFocusActive: Boolean,
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
    // keeps its ink; a higher id waits with what is ahead.
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
            flashWordPositions = flashWordPositions,
            searchFocusActive = searchFocusActive,
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
    SideEffect { leafStill.value = leafSettled }
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
            val frame = MushafLeafFrame(
                widthPx = constraints.maxWidth.toFloat(),
                heightPx = constraints.maxHeight.toFloat(),
                edgeGutterPx = with(density) { MushafEdgeGutter.toPx() },
                fitSlackPx = with(density) { MushafFitSlack.toPx() },
                unitPx = with(density) { unit.toPx() },
            )
            val fontPx = remember(frame, page) { frame.fontPx(page) }
            val rowEm = remember(frame, page) { frame.rowEm(page) }
            val displayPage = remember(page, pageTypeface, rowEm, surahsById) {
                mushafDisplayPage(page, pageTypeface, rowEm) { surahsById[it]?.ayahCount }
            }
            val fontSp = with(density) { fontPx.toSp() }
            val lineMeasurePx = frame.lineMeasurePx
            // Handed to the leaves still to come, which are set from it in the
            // background before they are composed (see [MushafLeafWarmup]).
            val wordInk = rememberWordInkPalette().fullInkColor
            val markInk = LocalQuranAccents.current.gold
            val resolver = LocalFontFamilyResolver.current
            val locales = LocalLocaleList.current
            if (pageFont != null && !mushafIsOpeningLeaf(page.page)) {
                val setting = remember(frame, density, resolver, locales, wordInk, markInk) {
                    MushafLeafSetting(frame, density, resolver, locales, wordInk, markInk)
                }
                SideEffect { MushafLeafWarmup.setting = setting }
            }
            val lineSlot = with(density) { frame.lineSlotPx(displayPage).toDp() }
            val medallion = remember(displayPage, pageTypeface, fontPx, frame) {
                mushafMedallion(displayPage, pageTypeface, fontPx, frame)
            }
            val medallionRowWidths = medallion?.rowWidthsPx
            val textSlot = medallion?.let { with(density) { it.pitchPx.toDp() } } ?: lineSlot
            // The band and basmalah stand on the same pitch as the medallion's
            // rows; everywhere else, on the book's leading.
            val furnitureSlot = textSlot
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalLeafOverlay provides leafOverlay,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = MushafEdgeGutter),
                    // Ordinary leaves start at the head, even when the last
                    // lines of a chapter leave the foot empty. The two framed
                    // opening pages are a medallion: the circle sits in the
                    // middle of the well, the way the print centres it.
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
                                    .height(furnitureSlot)
                                    .drawnLiveOverPicture(),
                                contentAlignment = Alignment.Center,
                            ) {
                                MushafSurahTitleBand(
                                    surah = surahsById[start.surahId],
                                    fontSize = fontSp * MushafPanelType,
                                    bandHeight = furnitureSlot * MushafPanelBand,
                                )
                            }
                            if (surahOpensWithBasmalahPreface(start.surahId)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(furnitureSlot)
                                        .drawnLiveOverPicture(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MushafBasmalahLine(
                                        fontSize = fontSp,
                                        slotHeight = furnitureSlot,
                                        active = pageOwnsVoice &&
                                            start.surahId == content.surah.id &&
                                            playback.value.basmalahActive,
                                        wash = basmalahWash,
                                        onClick = { onBasmalahClick(start.surahId) },
                                    )
                                }
                            }
                        }
                        val medallionRowPx = medallionRowWidths?.getOrNull(index)
                        val rowMeasurePx = medallionRowPx ?: lineMeasurePx
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(textSlot),
                            contentAlignment = Alignment.Center,
                        ) {
                            MushafHafsLine(
                                modifier = if (medallionRowPx != null) {
                                    Modifier.width(with(density) { rowMeasurePx.toDp() })
                                } else {
                                    Modifier
                                },
                                line = line,
                                page = page.page,
                                packs = packsState,
                                fontSize = fontSp,
                                measureWidthPx = rowMeasurePx,
                                pageTypeface = pageTypeface,
                                liveInk = liveInk,
                                onWordClick = onWordClick,
                                onWordLongClick = onWordLongClick,
                                onAyahClick = onAyahClick,
                                allowShort = mushafLineMayStandShort(
                                    page.page,
                                    line.tokens.lastOrNull(),
                                    surahsById[line.tokens.lastOrNull()?.surahId]?.ayahCount,
                                ),
                                pageFont = pageFont,
                            )
                        }
                    }
                    // The circle is what the eye centres, not the band above
                    // it: the same paper again below, so the column's centring
                    // lands on the medallion's middle row.
                    if (medallionRowWidths != null) {
                        Spacer(Modifier.height(furnitureSlot * displayPage.surahStarts.size))
                    }
                }
            }
        }
}

@Composable
internal fun MushafPageInkClocks(
    ayahs: List<Ayah>,
    /** Verses of a chapter this leaf shares but has not loaded, standing after
     * the loaded one: they wait with everything else still to come. */
    upcoming: List<Pair<Int, Int>>,
    /** Earlier chapters sharing the leaf keep their ink like verses already read. */
    recited: List<Pair<Int, Int>>,
    activeWordState: State<ActiveWord?>,
    playback: State<MushafPlayback>,
    pageOwnsVoice: Boolean,
    waitingForVoice: Boolean,
    pageHasActiveWord: Boolean,
    playbackSpeed: Float,
    flashAyah: Int?,
    flashWordPosition: Int?,
    flashWordPositions: Set<Int>,
    searchFocusActive: Boolean,
    packsState: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
) {
    val activeWordAyah by remember {
        derivedStateOf { activeWordState.value?.ayah }
    }
    val voice = playback.value
    val frontierAyah = mushafInkFrontier(activeWordAyah, voice.activeAyah, voice.playingAyah)
    val arrivedInkId by remember {
        derivedStateOf {
            activeWordState.value?.takeIf {
                !it.fromTap && it.ayah == playback.value.playingAyah
            }?.playbackActivation
        }
    }
    var heardInkId by remember(voice.playingAyah) { mutableStateOf<Long?>(null) }
    if (arrivedInkId == voice.inkId && heardInkId != arrivedInkId) {
        SideEffect { heardInkId = arrivedInkId }
    }
    // Media3 advances the playlist item before the 33 ms word poll reaches
    // that item's first timing. Keep its frontier under Upcoming paper during
    // that silence. Once a word has appeared, a later null is the audio tail
    // and the completed ayah must retain full ink instead of dimming again.
    val frontierWaitingForFirstWord = mushafAwaitingFirstWord(
        activeWordAyah, frontierAyah, voice.playingAyah, voice.inkId, heardInkId,
    )
    ayahs.forEach { ayah ->
        key(ayah.surahId, ayah.number) {
            val activeWord by remember(ayah.number) {
                derivedStateOf { activeWordState.value?.takeIf { it.ayah == ayah.number } }
            }
            // Swapping to the motionless recess pack the frame the voice
            // moves on threw the last word's glimmer away mid-glow. Hold the
            // clocks while it dries, as the scrolling reader's do.
            val voiced = activeWord != null
            var drying by remember { mutableStateOf(false) }
            LaunchedEffect(voiced) {
                if (voiced) {
                    drying = true
                } else if (drying) {
                    delay(InkEngine.tuning.glintFadeMs.toLong())
                    drying = false
                }
            }
            val recitingActive = voice.reciting
            val flashHere = flashAyah == ayah.number && flashWordPosition != null
            val searchFocusPositions = if (searchFocusActive) {
                flashWordPositions.takeIf { flashHere }.orEmpty()
            } else {
                null
            }
            val kind = mushafInkPackKind(
                pageOwnsVoice = pageOwnsVoice,
                ayah = ayah.number,
                activeWordAyah = activeWordAyah,
                frontierAyah = frontierAyah,
                basmalahActive = voice.basmalahActive,
                hasSearchFlash = flashHere,
                frontierWaitingForFirstWord = frontierWaitingForFirstWord,
                waitingForVoice = waitingForVoice,
                pageHasActiveWord = pageHasActiveWord,
                hasSearchFocus = searchFocusActive,
                drying = drying,
            )
            val pack = when (kind) {
                MushafInkPackKind.ACTIVE_WORD -> rememberAyahInkPack(
                    ayah = ayah,
                    activeWord = activeWord,
                    playbackSpeed = playbackSpeed,
                    // A completed verse keeps its ink while the last wet word dries.
                    isActiveAyah = voiced,
                    dimmed = false,
                    flashWordPosition = flashWordPosition?.takeIf { flashHere },
                    flashWordPositions = flashWordPositions.takeIf { flashHere }.orEmpty(),
                    searchFocusPositions = searchFocusPositions,
                    searchFocusActive = searchFocusActive,
                    // Debounced on purpose: a repeat range looping back dips
                    // out of "playing" for a frame, and the ink is not dry
                    // between two laps of the same verse.
                    wetInk = recitingActive,
                    initiallyRecessed = true,
                )
                MushafInkPackKind.SEARCH_FLASH -> rememberAyahInkPack(
                    ayah = ayah,
                    activeWord = null,
                    playbackSpeed = playbackSpeed,
                    isActiveAyah = false,
                    dimmed = false,
                    flashWordPosition = flashWordPosition,
                    flashWordPositions = flashWordPositions,
                    searchFocusPositions = flashWordPositions,
                    searchFocusActive = searchFocusActive,
                    wetInk = false,
                )
                MushafInkPackKind.SEARCH_BACKGROUND -> rememberAyahInkPack(
                    ayah = ayah,
                    activeWord = null,
                    playbackSpeed = playbackSpeed,
                    isActiveAyah = false,
                    dimmed = false,
                    searchFocusPositions = emptySet(),
                    searchFocusActive = true,
                    wetInk = false,
                )
                // One branch, deliberately: waiting and settled differ only by
                // whether the paper cover is down, and they must share a clock.
                //
                // Split across two `when` branches they were two composition
                // groups, so every flip between them threw the recess
                // Animatable away and built a new one already at its target.
                // At a verse boundary the active word is briefly null and the
                // voice's page is briefly unknown, which drops every verse
                // ahead of the reciter to STATIC for a frame or two — and with
                // a fresh Animatable that read as the whole page snapping to
                // full ink and back. Sharing the branch keeps one clock, so a
                // flip that lasts two frames moves the cover by two frames'
                // worth of [InkEngine.Tuning.recessMs] and is invisible.
                MushafInkPackKind.UPCOMING, MushafInkPackKind.STATIC ->
                    rememberMushafRecessPack(dimmed = kind == MushafInkPackKind.UPCOMING)
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
    // already passed keeps its ink. A manually browsed leaf remains full scripture.
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
        searchBackgroundAlpha = remember { mutableStateOf(1f) },
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
            setManagedColor(ink)
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
