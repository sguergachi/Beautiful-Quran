package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beautifulquran.DevProfiling
import com.beautifulquran.data.PageNumberScript
import com.beautifulquran.data.VerseNumberScript
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.domain.MUSHAF_LINE_PITCH_EM
import com.beautifulquran.domain.MushafCatalog
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.mushafFontPreloadPages
import com.beautifulquran.domain.mushafLeafBands
import com.beautifulquran.domain.EnglishBook
import com.beautifulquran.domain.EnglishVerseAlignments
import com.beautifulquran.domain.quranWordKey
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.snap
import com.beautifulquran.ui.theme.oneSpace
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

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
    /** Seek/repeat generation: a new performance releases the previous location. */
    val inkId: Long = 0L,
)

/** Stable equality key for the small set of voice changes follow reacts to. */
private data class MushafFollowMoment(
    val word: ActiveWord?,
    val activeAyah: Int?,
    val basmalahActive: Boolean,
    val playingHere: Boolean,
    val isPlaying: Boolean,
    val playingAyah: Int?,
    val inkId: Long,
    val voiceLeaf: Int?,
    val followLeaf: Int?,
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

/** How many words a verse of the loaded chapter has, or 0 if it is not loaded. */
private fun SurahContent.wordsIn(ayah: Int?): Int =
    ayahs.firstOrNull { it.number == ayah }?.words?.size ?: 0

internal const val MushafLeafFadeMs = 220

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

/** Between the near pair of held leaves and the outer pair. */
private const val MushafOuterNeighbourDelayMs = 260L

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
                colorStops = oneSpace(
                    *if (fromLeft) {
                        arrayOf(0f to paper, 0.45f to paper.copy(alpha = 0.70f), 1f to Color.Transparent)
                    } else {
                        arrayOf(0f to Color.Transparent, 0.55f to paper.copy(alpha = 0.70f), 1f to paper)
                    },
                ),
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
    /** The same reveal for the English leaf's prose line, which carries no
     * Arabic letterforms: even word bands, no tajweed pacing. */
    proseBasmalahWash: StateFlow<Float?>,
    surahsById: Map<Int, Surah>,
    pagerState: PagerState,
    activeWordState: State<ActiveWord?>,
    englishWashClock: StateFlow<EnglishWashClock?>,
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
    /** A transport command starts its swipe before Media3 reports the new word. */
    transportTarget: ReaderInteraction.MushafPlayTarget? = null,
    onTransportTargetShown: (ReaderInteraction.MushafPlayTarget) -> Unit = {},
    /**
     * 0-based leaf a chapter hand-off turns to: the next chapter's opening,
     * reached by the same turn playback makes inside a chapter rather than a
     * jump. Taken as the pager's own move so follow stays on across it.
     */
    chapterTurnLeaf: Int? = null,
    onChapterTurnShown: (Int) -> Unit = {},
    flashAyah: Int?,
    flashWordPosition: Int?,
    flashWordPositions: Set<Int>,
    searchFocusActive: Boolean,
    /** True only while a distant dial landing keeps neighbour leaves parked. */
    parkNeighbours: () -> Boolean,
    onUserTurnedPage: () -> Unit,
    onWordClick: (MushafToken) -> Unit,
    onWordLongClick: (MushafToken) -> Unit,
    onAyahClick: (MushafToken) -> Unit,
    /**
     * Play from a share of a verse — the English leaf's tap. It has no word to
     * aim at, so it says which verse and how far into it and the reader turns
     * that into a word. See `englishSeekWordPosition`.
     */
    onVerseSeek: (surahId: Int, ayah: Int, through: Float) -> Unit = { _, _, _ -> },
    /** A hold on the English leaf's prose: the verse and share it landed on. */
    onVerseLongPress: (surahId: Int, ayah: Int, through: Float) -> Unit = { _, _, _ -> },
    onBasmalahClick: (Int) -> Unit,
    /**
     * Sets the leaf in English instead of the page's own hand — the same 604
     * leaves, read in the reader's language. See [MushafEnglishSheet].
     */
    english: Boolean = false,
    wordByWord: Boolean = false,
    verseNumberScript: VerseNumberScript = VerseNumberScript.ARABIC,
    pageNumberScript: PageNumberScript = PageNumberScript.BOTH,
    /** The English leaf's well and measure, once it has laid out. */
    onLeafMetrics: (wellPx: Float, measurePx: Float) -> Unit = { _, _ -> },
    /** Whether the book's leaves were measured — see MushafUi.measured. */
    bookMeasured: Boolean = true,
    /** The prose the book was paginated from. English only. */
    leafText: Map<Long, String> = emptyMap(),
    /**
     * The English book's leaves. A Madinah page takes more than one where its
     * English will not fit a leaf at a legible size, so the pager's index is
     * the *leaf* and every leaf still names the page it belongs to. Null on the
     * Arabic leaf, where the two are the same thing.
     */
    book: EnglishBook? = null,
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
    // Read through a holder so the follow rule and the voice-leaf rule can ask
    // how long a verse is without either restarting when the chapter's text
    // arrives.
    val contentNow = rememberUpdatedState(content)
    // Where each Arabic word lands in its English, solved per verse on first
    // ask and kept. The English leaf's wash, the leaf a carried verse's voice
    // is on, and the lead onto the book's own cut all read the same map — if
    // they disagreed, the page would turn away from ink still running.
    val alignments = remember(content, wordByWord) { EnglishVerseAlignments(content, wordByWord) }
    val alignmentsNow = rememberUpdatedState(alignments)
    val heldPageNow = rememberUpdatedState(heldPage)
    val lastVoiceWord = remember(loadedSurahId) { mutableStateOf<ActiveWord?>(null) }
    val washClock = englishWashClock.collectAsStateWithLifecycle()
    val washWindows = if (english) rememberEnglishWashWindows(washClock, alignments, content) else emptyMap()
    val washWindowsNow = rememberUpdatedState(washWindows)
    fun timedThrough(word: ActiveWord?, aheadMs: Long = 0L): Float? {
        if (!english || book == null || word == null || word.fromTap) return null
        val clock = washClock.value?.takeIf { it.followsFirstWash(word, loadedSurahId) } ?: return null
        val alignment = alignmentsNow.value.alignment(word.ayah) ?: return null
        val windows = washWindowsNow.value[loadedSurahId to word.ayah] ?: return null
        return englishWashThrough(windows, clock.positionMs + aheadMs)
    }
    // The leaf the voice is on, 1-based, or null. A Madinah page may be two
    // leaves, so which of them holds the verse is a question the book answers.
    val voiceLeaf = remember(catalog, loadedSurahId, english, book, lastVoiceWord) {
        derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) {
            val voice = playback.value
            val word = mushafLocationWord(
                activeWordState.value, lastVoiceWord.value, voice.playingAyah, voice.inkId,
            )
            val page = mushafVoicePage(
                catalog = catalog,
                surahId = loadedSurahId,
                voice = voice,
                word = word,
                wholeVerses = english,
            ) ?: return@derivedStateOf null
            // The basmalah belongs to the chapter's first verse, which is what
            // puts it on the leaf that opens the chapter. Without this the
            // preface had no verse at all and the leaf carrying it never owned
            // the voice, so it was read with no ink running across it.
            val ayah = if (voice.basmalahActive) 1 else
                word?.ayah ?: voice.playingAyah ?: voice.activeAyah
            mushafLeafNumber(
                book = book,
                surahId = loadedSurahId,
                ayah = ayah,
                page = page,
                through = timedThrough(word) ?: mushafVerseThrough(
                    word?.wordPosition,
                    contentNow.value.wordsIn(ayah),
                    alignment = if (english) alignments.alignment(ayah) else null,
                ),
            )
        }
    }
    val clockFollowLeaf = remember(catalog, loadedSurahId, english, book) {
        derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) {
            val word = activeWordState.value ?: return@derivedStateOf null
            val ahead = if (playback.value.isPlaying) (MushafTurnLeadMs * speedNow.value).toLong() else 0L
            val through = timedThrough(word, ahead) ?: return@derivedStateOf null
            mushafLeafNumber(book, loadedSurahId, word.ayah,
                catalog.readingPageOf(loadedSurahId, word.ayah, word.wordPosition, wholeVerses = true), through)
        }
    }
    LaunchedEffect(loadedSurahId, activeWordState, playback) {
        snapshotFlow { activeWordState.value?.takeIf { playback.value.playingHere } }
            .collect { word ->
                if (word != null && !word.fromTap) lastVoiceWord.value = word
            }
    }
    LaunchedEffect(transportTarget) {
        val target = transportTarget ?: return@LaunchedEffect
        val index = mushafLeafIndex(
            mushafTransportLeaf(
                catalog, book, target, contentNow.value.wordsIn(target.ayah),
                alignment = if (english) alignmentsNow.value.alignment(target.ayah) else null,
            ), pagerState.pageCount,
        )
        followPage = index
        waitingPage = if (playback.value.isPlaying) index + 1 else 0
        onTappedLeaf(index + 1)
        try {
            pagerState.animateScrollToPage(index, animationSpec = MushafFollowTurnSpec)
        } finally {
            onTransportTargetShown(target)
        }
    }
    LaunchedEffect(chapterTurnLeaf) {
        val index = chapterTurnLeaf ?: return@LaunchedEffect
        // The turn is follow's own: followPage is set before the paper moves,
        // so the settle watcher below does not read it as the reader turning
        // away and switch follow off for the whole of the next chapter.
        followPage = index
        waitingPage = index + 1
        try {
            pagerState.animateScrollToPage(index, animationSpec = MushafFollowTurnSpec)
        } finally {
            onChapterTurnShown(index)
        }
    }
    // heldPage is read, not keyed: keying it restarted this collector on
    // every tap and cancelled in-flight work. The hold is checked per tick.
    LaunchedEffect(followEnabled, loadedSurahId, catalog, pagerState, english, transportTarget) {
        snapshotFlow {
            val voice = playback.value
            MushafFollowMoment(
                word = activeWordState.value,
                activeAyah = voice.activeAyah,
                basmalahActive = voice.basmalahActive,
                playingHere = voice.playingHere,
                isPlaying = voice.isPlaying,
                playingAyah = voice.playingAyah,
                inkId = voice.inkId,
                voiceLeaf = voiceLeaf.value,
                followLeaf = clockFollowLeaf.value,
            )
        }
            .collect { moment ->
                if (transportTarget != null) return@collect
                if (!followEnabled || !moment.playingHere || !moment.isPlaying) return@collect
                val word = moment.word
                val locationWord = mushafLocationWord(
                    word, lastVoiceWord.value, moment.playingAyah, moment.inkId,
                )
                // The active word carries no surah of its own. Right after a
                // tap that loaded another chapter, the word still belongs to
                // the outgoing one — following it would turn the leaf out from
                // under the reader, to whatever page that verse number happens
                // to fall on in the new surah. Wait for the player to arrive.
                val focusAyah = locationWord?.ayah ?: moment.playingAyah ?: moment.activeAyah
                val onPage = when {
                    moment.basmalahActive -> catalog.firstPageOf(loadedSurahId)
                    locationWord != null -> catalog.readingPageOf(
                        loadedSurahId,
                        locationWord.ayah,
                        locationWord.wordPosition,
                        wholeVerses = english,
                    )
                    focusAyah != null -> catalog.pageOf(loadedSurahId, focusAyah, 1)
                    else -> return@collect
                }
                // In leaves from here down: a Madinah page may be more than one.
                // And a verse the book carried over stands on two, so where the
                // reciter is *inside* the verse decides which of them to turn
                // to — without it the reader sat on the first half for the
                // whole verse and the page only turned at the next one.
                val followAyah = if (moment.basmalahActive) 1 else focusAyah
                val page = moment.voiceLeaf ?: mushafLeafNumber(
                    book = book,
                    surahId = loadedSurahId,
                    ayah = followAyah,
                    page = onPage,
                    through = mushafVerseThrough(
                        locationWord?.wordPosition,
                        contentNow.value.wordsIn(followAyah),
                        alignment = if (english) alignmentsNow.value.alignment(followAyah) else null,
                    ),
                )
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
                val desiredPage = moment.followLeaf ?: page
                val index = mushafLeafIndex(desiredPage - 1, pagerState.pageCount)
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
                    if (word == null && !mushafNullWordFollowStillCurrent(
                            activeAyah = moment.activeAyah,
                            basmalahActive = moment.basmalahActive,
                            playingAyah = moment.playingAyah,
                            inkId = moment.inkId,
                            currentVoice = currentVoice,
                            currentWord = activeWordState.value,
                        )
                    ) {
                        return@collect
                    }
                    if (pagerState.isScrollInProgress) return@collect
                    if (desiredPage > page) waitingPage = desiredPage
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
                //
                // On the English leaf the lead is taken at the book's own cut
                // rather than at the Arabic page's last word. That word is very
                // often in the middle of a sentence the leaf has set whole (see
                // MushafCatalog.readingPageOf) and leading on it would turn the
                // paper away from the sentence being read. The cut is the
                // opposite case: the leaf really does stop mid-sentence there
                // and the rest is printed overleaf, so it is exactly where a
                // reader turning for someone else would start to move.
                if (word == null) return@collect
                if (english) {
                    // The media edge supplies the same 500 ms lead, including
                    // a cut inside a held/reordered phrase. Keep the word-based
                    // lead only until that clock is available.
                    if (moment.followLeaf != null) return@collect
                    val lead = mushafLeadCarriedTurn(
                        book = book,
                        surahId = loadedSurahId,
                        ayah = followAyah,
                        onPage = onPage,
                        word = word,
                        words = contentNow.value.wordsIn(followAyah),
                        leafNow = page,
                        alignment = alignmentsNow.value.alignment(followAyah),
                    ) ?: return@collect
                    val leadIndex = mushafLeafIndex(lead - 1, pagerState.pageCount)
                    // Cover the incoming leaf before the paper moves, so the
                    // turn reveals Upcoming paper rather than a finished page.
                    waitingPage = lead
                    delay(mushafTurnLeadDelayMs(word.durationMs, speedNow.value))
                    // Paused, seeked, or turned by hand while the word was
                    // still being said: the leaf is no longer ours to move.
                    val voiceStill = playback.value
                    if (!voiceStill.playingHere || !voiceStill.isPlaying ||
                        !mushafSameActivation(word, activeWordState.value)
                    ) {
                        waitingPage = 0
                        return@collect
                    }
                    if (pagerState.currentPage != index || pagerState.isScrollInProgress) {
                        waitingPage = 0
                        return@collect
                    }
                    followPage = leadIndex
                    pagerState.animateScrollToPage(
                        leadIndex,
                        animationSpec = MushafFollowTurnSpec,
                    )
                    return@collect
                }
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
    // Page faces for the leaves around the resting one, and the leaves a turn
    // is heading for set ahead of it (see [MushafLeafWarmup]). Followed by the
    // page in view and the turn's target, not only the settled page: a run of
    // quick flips never settles, and the leaves it reaches were composed cold.
    LaunchedEffect(pagerState, context, catalog, book, surahsById) {
        snapshotFlow { Triple(pagerState.settledPage, pagerState.currentPage, pagerState.targetPage) }
            .collectLatest { (settled, current, target) ->
                val pages = mushafFontPreloadPages(settled, catalog.pageCount)
                withContext(Dispatchers.Default) {
                    MushafQcfFonts.preload(context, pages)
                    if (book != null) return@withContext
                    mushafWarmOrder(settled, current, target, catalog.pageCount).forEach { number ->
                        val leaf = catalog.page(number) ?: return@forEach
                        MushafLeafWarmup.warm(context, leaf) { surahsById[it]?.ayahCount }
                    }
                }
            }
    }
    LaunchedEffect(followEnabled) {
        if (!followEnabled) waitingPage = 0
    }
    val onWordClickNow = rememberUpdatedState(onWordClick)
    val onWordLongClickNow = rememberUpdatedState(onWordLongClick)
    val onAyahClickNow = rememberUpdatedState(onAyahClick)
    val onVerseSeekNow = rememberUpdatedState(onVerseSeek)
    val onVerseLongPressNow = rememberUpdatedState(onVerseLongPress)
    val onBasmalahClickNow = rememberUpdatedState(onBasmalahClick)
    val onTappedLeafNow = rememberUpdatedState(onTappedLeaf)
    val paper = MaterialTheme.colorScheme.background
    // Opening a chapter should cost one leaf, not five. Holding neighbours
    // composed either side is what makes a page turn smooth, but doing it
    // before the first frame means ~450 word nodes are composed, measured and
    // indexed while the reader is still coming up — profiled as the reader's
    // worst frames by a wide margin, most of the time inside Compose's own
    // spatial index walking the tree. The neighbours are composed one frame
    // later instead: the open is a single leaf, and the turn is still warm.
    // They come back in two pairs — the leaves a turn away, then the two
    // beyond, a beat later — so no one frame composes four of them, here or
    // when a dial landing hands its parked neighbours back.
    val parkNeighboursNow = rememberUpdatedState(parkNeighbours)
    var heldNeighbours by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(MushafNeighbourHoldDelayMs)
        snapshotFlow { parkNeighboursNow.value() }.collectLatest { parked ->
            if (parked) {
                heldNeighbours = 0
                return@collectLatest
            }
            if (heldNeighbours == 0) {
                heldNeighbours = 1
                delay(MushafOuterNeighbourDelayMs)
            }
            heldNeighbours = MushafHeldNeighbours
        }
    }
    // High only while the leaf moves. Recitation redraws the wash every frame,
    // and a standing vote kept the panel at its top rate through a whole
    // listening session for a page that was not turning. Read inside its own
    // scope, so a turn starting or settling recomposes the pager call alone
    // and not the whole of this function.
    val turnSnapshots = rememberMushafTurnSnapshots()
    MushafPictureProbe.Watch()
    // The leaves publish their setting for the warm-up; it goes with the
    // reader, so a rotation or a density change never warms by a stale one.
    DisposableEffect(Unit) { onDispose { MushafLeafWarmup.setting = null } }
    // The page the pager rests on. settledPage moves on
    // the frame a finger lifts — the drag's scroll ends there and the fling's
    // begins — while the leaf still has half the screen to travel, and every
    // leaf keyed on it recomposed mid-fling. This one waits until the pager
    // has stayed still.
    val restPage = remember(pagerState) { mutableIntStateOf(pagerState.settledPage) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
            .collectLatest { (scrolling, settledPage) ->
                if (scrolling) return@collectLatest
                repeat(MushafRestFrames) { withFrameNanos { } }
                restPage.intValue = settledPage
            }
    }
    // The leaves, built outside the frame-rate vote below. Written inline in
    // its content, every vote change (a turn starting, ending, or the frame a
    // finger lifts) handed the pager a new block, which recomposed every leaf
    // and re-recorded it mid-turn.
    val leafContent: @Composable PagerScope.(Int) -> Unit = { pageIndex ->
        val bookLeaf = book?.leaf(pageIndex)
        val page = catalog.page(bookLeaf?.page ?: (pageIndex + 1))
        // Halfway through a turn the pager's window moves on, and the leaf
        // that joined it at the far end was composed and measured on that
        // very frame — ~150 word nodes, 28–48 ms on the emulator; the leaf
        // it prefetches past the window at rest had its lines measured on
        // the next swipe's first frame, 40–70 ms. Two leaves are held
        // either side at rest, so whatever a turn can show is ready, and a
        // leaf further out stays blank paper until the pager rests within
        // reach of it (see mushafDefersLeaf). A leaf in or beside the view
        // or a jump's destination is composed at once, and a leaf once
        // composed stays so.
        var leafComposed by remember(pageIndex) { mutableStateOf(false) }
        val deferLeaf by remember(pageIndex) {
            derivedStateOf {
                !leafComposed &&
                    mushafDefersLeaf(
                        pageIndex = pageIndex,
                        restPage = restPage.intValue,
                        held = heldNeighbours,
                        targetPage = pagerState.targetPage,
                        nearView = pagerState.layoutInfo.visiblePagesInfo.any { abs(it.index - pageIndex) <= 1 },
                    )
            }
        }
        if (page == null || deferLeaf) {
            Box(Modifier.fillMaxSize())
        } else {
            SideEffect { leafComposed = true }
            val settled by remember {
                derivedStateOf { pageIndex == restPage.intValue }
            }
            // Whether the leaf's own entrance has finished: a picture taken
            // partway through it would keep it half-inked.
            val leafStill = remember(pageIndex) { mutableStateOf(false) }
            val leafOverlay = remember(pageIndex) { LeafOverlay() }
            // Keyed on [voiceLeaf] itself: it is rebuilt when the loaded chapter
            // changes, and a derivation remembered on the page alone kept
            // reading the old chapter's. Playing an unloaded chapter from its
            // leaf then left that leaf disowned through the basmalah and in
            // every gap between verses — the whole page at full ink, with no
            // wash — until the leaf was composed again.
            val pageOwnsVoice by remember(pageIndex, voiceLeaf) {
                derivedStateOf { voiceLeaf.value == pageIndex + 1 }
            }
            val pageHasActiveWord by remember(pageIndex, catalog, loadedSurahId, english, book) {
                derivedStateOf {
                    val word = activeWordState.value ?: return@derivedStateOf false
                    val page = catalog.readingPageOf(
                        loadedSurahId,
                        word.ayah,
                        word.wordPosition,
                        wholeVerses = english,
                    )
                    val timedLeaf = timedThrough(word)?.let { through ->
                        mushafLeafNumber(book, loadedSurahId, word.ayah, page, through)
                    }
                    (timedLeaf ?: mushafWordLeafNumber(
                        book = book,
                        surahId = loadedSurahId,
                        word = word,
                        page = page,
                        wordCount = contentNow.value.wordsIn(word.ayah),
                        alignment = if (english) alignments.alignment(word.ayah) else null,
                    )) == pageIndex + 1
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
            // Every input's own state object is a key: each of them is
            // remembered on keys of its own, and a capture of a replaced one
            // goes on answering for a chapter that is no longer loaded.
            val liveInk by remember(pageIndex, voiceLeaf, catalog, loadedSurahId, english, book, heldPage) {
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
                    if (mushafLeafAcceptsTap(pageIndex, pagerState.currentPage)) {
                        waitingPage = pageIndex + 1
                        onTappedLeafNow.value(pageIndex + 1)
                        onWordClickNow.value(token)
                    }
                }
            }
            val leafWordLongClick = remember(pageIndex) {
                { token: MushafToken ->
                    if (mushafLeafAcceptsTap(pageIndex, pagerState.currentPage)) {
                        onWordLongClickNow.value(token)
                    }
                }
            }
            val leafAyahClick = remember(pageIndex, page.page) {
                { token: MushafToken ->
                    if (mushafLeafAcceptsTap(pageIndex, pagerState.currentPage)) {
                        waitingPage = pageIndex + 1
                        onTappedLeafNow.value(pageIndex + 1)
                        onAyahClickNow.value(token)
                    }
                }
            }
            val leafVerseSeek = remember(pageIndex, page.page) {
                { surahId: Int, ayah: Int, through: Float ->
                    if (mushafLeafAcceptsTap(pageIndex, pagerState.currentPage)) {
                        waitingPage = pageIndex + 1
                        onTappedLeafNow.value(pageIndex + 1)
                        onVerseSeekNow.value(surahId, ayah, through)
                    }
                }
            }
            val leafVerseLongPress = remember(pageIndex) {
                { surahId: Int, ayah: Int, through: Float ->
                    if (mushafLeafAcceptsTap(pageIndex, pagerState.currentPage)) {
                        onVerseLongPressNow.value(surahId, ayah, through)
                    }
                }
            }
            val leafBasmalahClick = remember(pageIndex, page.page) {
                { surahId: Int ->
                    if (mushafLeafAcceptsTap(pageIndex, pagerState.currentPage)) {
                        waitingPage = pageIndex + 1
                        onTappedLeafNow.value(pageIndex + 1)
                        onBasmalahClickNow.value(surahId)
                    }
                }
            }
            // Every Madinah page the leaf draws from — two or three, since the
            // English book paginates itself and its leaves do not stop where
            // the Arabic ones do.
            val leafPages = bookLeaf?.pages ?: page.page..page.page
            val leafRuns = bookLeaf?.runs.orEmpty()
            val leafVerses = bookLeaf?.verses.orEmpty()
            // The Arabic word each verse of the leaf opens with — what a tap on
            // an English sentence plays from. Gathered here rather than in the
            // sheet because it is the pager that holds the catalog, and a leaf
            // spanning two pages needs both of them.
            val leafTokens = remember(leafPages, english) {
                if (!english) {
                    emptyMap()
                } else {
                    buildMap {
                        leafPages.forEach { number ->
                            catalog.page(number)?.lines?.forEach { line ->
                                line.tokens.forEach { token ->
                                    if (token.word.position == 1) {
                                        put(token.surahId to token.ayah, token)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // One description for the leaf, not ~450 word nodes: the QCF
            // glyphs are private-use artwork — meaningless to a screen reader
            // — and an active accessibility service re-sorted and re-geometried
            // every one of them per frame of a swipe, which was the mushaf's
            // swipe lag. Taps are pointer-based and unaffected.
            // The chapter the leaf opens in. For English that is its first
            // verse's, not the page's — a leaf may open partway down a page
            // whose "primary" chapter it never reaches.
            val leafSurahId = if (english) {
                leafVerses.firstOrNull()?.first ?: page.primarySurahId
            } else {
                page.primarySurahId
            }
            val leafSurah = surahsById[leafSurahId]?.nameTransliteration
            val leafDescription = remember(page, leafSurah, english, leafText, leafVerses) {
                buildString {
                    append("Mushaf page ")
                    append(page.page)
                    if (leafSurah != null) {
                        append(", ")
                        append(leafSurah)
                    }
                    append(", Juz ")
                    append(page.juz)
                    val verses = leafText
                    if (english) {
                        leafVerses.forEach { (surahId, ayah) ->
                            append(". ")
                            append(verses[quranWordKey(surahId, ayah, 1)].orEmpty())
                        }
                    } else {
                        page.lines.forEach { line ->
                            if (line.tokens.isNotEmpty()) append(". ")
                            line.tokens.forEach { token ->
                                append(token.word.arabic)
                                append(' ')
                            }
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
                    .mushafTurnSnapshot(
                        snapshots = turnSnapshots,
                        pictured = !english && (!liveInk || MushafPictureProbe.forcesPicture),
                        still = { leafStill.value },
                        overlay = leafOverlay,
                        bleed = LeafFootBleed,
                    )
                    .clipLeafSides()
                    .padding(horizontal = MushafPageMargin),
            ) {
                val density = LocalDensity.current
                // Both hands spend the same frame; English fills it with prose.
                val bands = mushafLeafBands(english)
                // One unit for the whole leaf — see MushafGrid. Both bands below
                // are a whole number of them, so the head and the well sit on the
                // same rhythm as the lines of revelation.
                val unit = with(density) {
                    bands.unitPx(constraints.maxHeight.toFloat()).toDp()
                }
                // The leaf's own size, from the paper it is set on — the same
                // chain the app's root walks before any of this is composed, so
                // the book it paginated is the book this leaf draws.
                val englishSlot = if (english) {
                    englishLeafSlotPx(constraints.maxWidth, constraints.maxHeight, density)
                } else {
                    null
                }
                Column(Modifier.fillMaxSize()) {
                    MushafPageHeader(
                        surahNameArabic = surahsById[leafSurahId]?.nameArabic,
                        surahNameLatin = surahsById[leafSurahId]?.nameTransliteration,
                        unit = unit,
                        glyphSize = leafGlyphSize(unit),
                        page = if (english) pageIndex + 1 else page.page,
                        pageNumberScript = pageNumberScript,
                    )
                    Spacer(Modifier.height(unit * bands.headGutter))
                    val wellModifier = Modifier
                        .height(unit * bands.well)
                        .fillMaxWidth()
                    if (english) {
                        MushafEnglishSheet(
                            page = page,
                            leafText = leafText,
                            content = content,
                            basmalahWash = proseBasmalahWash,
                            surahsById = surahsById,
                            liveInk = liveInk,
                            pageOwnsVoice = pageOwnsVoice,
                            waitingForVoice = waitingForVoice,
                            pageHasActiveWord = pageHasActiveWord,
                            activeWordState = activeWordState,
                            washClock = englishWashClock,
                            playback = playback,
                            playbackSpeed = playbackSpeed,
                            flashAyah = flashAyah.takeIf { settled },
                            flashWordPosition = flashWordPosition.takeIf { settled },
                            flashWordPositions = flashWordPositions.takeIf { settled }.orEmpty(),
                            searchFocusActive = searchFocusActive && settled,
                            onMetrics = onLeafMetrics,
                            measured = bookMeasured,
                            verseNumberScript = verseNumberScript,
                            wellPx = englishSlot?.get(0) ?: 1f,
                            measurePx = englishSlot?.get(1) ?: 1f,
                            leafRuns = leafRuns,
                            leafTokens = leafTokens,
                            alignments = alignments,
                            onVerseSeek = leafVerseSeek,
                            onVerseLongPress = leafVerseLongPress,
                            onBasmalahClick = leafBasmalahClick,
                            modifier = wellModifier,
                        )
                    } else {
                        MushafPageSheet(
                            leafStill = leafStill,
                            leafOverlay = leafOverlay,
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
                            flashWordPositions = flashWordPositions.takeIf { settled }.orEmpty(),
                            searchFocusActive = searchFocusActive && settled,
                            onWordClick = leafWordClick,
                            onWordLongClick = leafWordLongClick,
                            onAyahClick = leafAyahClick,
                            onBasmalahClick = leafBasmalahClick,
                            unit = unit,
                            modifier = wellModifier,
                        )
                    }
                    // Zero on both hands now: the folio is in the running
                    // head, the well runs to the leaf's bottom edge and the
                    // dial's own head air is the foot (see clipLeafSides).
                    Spacer(Modifier.height(unit * bands.tail))
                }
            }
        }
    }
    TurningFrameRate(pagerState) { turningRate ->
        HorizontalPager(
            state = pagerState,
            // A distant dial jump composes one selected leaf first. Its neighbours
            // return two frames later, a pair at a time; asking Compose to build
            // them all at once was the visible pause between release and the
            // new folio.
            beyondViewportPageCount = if (parkNeighbours()) 0 else heldNeighbours,
            // The leaf, the rule and the folio turn together — see
            // mushafTurnsRightToLeft, which is the only thing that decides it, and
            // is handed the same `english` the sheet's rule is.
            reverseLayout = mushafTurnsRightToLeft(english),
            key = { it },
            modifier = modifier
                .fillMaxSize()
                // High only while the leaf moves. Recitation redraws the wash
                // every frame, and a standing vote kept the panel at its top rate
                // through a whole listening session for a page that was not
                // turning.
                .then(turningRate)
                .mushafForeEdgeFade(paper, MushafForeEdgeFade),
            pageContent = leafContent,
        )
    }
}

/**
 * Clips a leaf at its fore-edges only.
 *
 * The sides keep a neighbouring leaf's ink off this one through a swipe. The
 * foot must not be clipped: the well runs to the leaf's bottom edge, and the
 * last line's descenders (g, p, y) hang a few pixels past it into the air
 * over the dial. A whole-box clip cut the feet off the English leaf's last
 * line.
 */
private fun Modifier.clipLeafSides(): Modifier = this.then(ClipLeafSides)

// One instance, so a recomposed leaf hands Compose an equal modifier: a fresh
// draw lambda each time re-recorded the leaf, and retired its turn picture.
private val ClipLeafSides = Modifier.drawWithContent {
    val bleed = LeafFootBleed.toPx()
    clipRect(top = -bleed, bottom = size.height + bleed) {
        this@drawWithContent.drawContent()
    }
}

private val LeafFootBleed = 48.dp

/** Hands [content] the high frame-rate vote while [pagerState] is moving. */
@Composable
private fun TurningFrameRate(
    pagerState: PagerState,
    content: @Composable (Modifier) -> Unit,
) {
    content(
        if (pagerState.isScrollInProgress) {
            Modifier.preferredFrameRate(FrameRateCategory.High)
        } else {
            Modifier
        },
    )
}

