package com.beautifulquran.ui.reader

import com.beautifulquran.ui.theme.paperink.PaperInk
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.beautifulquran.ui.theme.leafCapacityChars
import com.beautifulquran.ui.theme.leafLeadingEm
import com.beautifulquran.ui.theme.bookTextMotion
import com.beautifulquran.ui.theme.quietSuppliedBrackets
import com.beautifulquran.ui.theme.QuranTypePalette
import com.beautifulquran.ui.theme.LocalQuranTypePalette
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.data.VerseNumberScript
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.domain.ENGLISH_LEAF_PROBE_FONT_PX
import com.beautifulquran.domain.ENGLISH_LEAF_SPECIMEN
import com.beautifulquran.domain.englishLeafReferenceBlock
import com.beautifulquran.domain.EnglishLeaf
import com.beautifulquran.domain.EnglishLeafBlock
import com.beautifulquran.domain.EnglishVerseRun
import com.beautifulquran.domain.EnglishVerseAlignments
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.englishLeaf
import com.beautifulquran.domain.englishLeafBreak
import com.beautifulquran.domain.englishLeafFittedLeadingEm
import com.beautifulquran.domain.englishLeafClosingLeadingEm
import com.beautifulquran.domain.englishLeafHandPx
import com.beautifulquran.domain.EnglishLeafFill
import com.beautifulquran.domain.EnglishLeafRuler
import com.beautifulquran.domain.EnglishLeafVerse
import com.beautifulquran.domain.EnglishJustify
import com.beautifulquran.domain.EnglishRag
import com.beautifulquran.domain.EnglishRulerCut
import com.beautifulquran.domain.mushafLeafBands
import com.beautifulquran.domain.quranWordKey
import com.beautifulquran.ui.theme.PaperCoverPad
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.ShapedWordBloom
import com.beautifulquran.ui.theme.letterFadeIn
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.shapedWordBloom
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The English leaf — the same Madinah page, set as a page of a book.
 *
 * Everything about the leaf that is not the text itself is the Arabic leaf's:
 * the page number, the juzʾ, the running head, the folio, the chapter panel,
 * the page dial and the reciter's own place on the paper. Only the hand and the
 * setting change. `domain/EnglishLeaf.kt` says why the page boundary is
 * borrowed rather than invented and why the text is the verse translation
 * rather than the word gloss; `domain/EnglishLeafFit.kt` says how the book's
 * one hand is fitted and how each page fills its well.
 *
 * The ink is the same ink, driven from the very same [AyahInkPack] the Arabic
 * leaf and the scrolling reader are driven from ([MushafPageInkClocks]). The
 * reciter's timings name Arabic words and this page prints none of them, so the
 * leaf finds them: `EnglishWordAlignment` says which English each Arabic word
 * is about, and the wash blooms *that span* on that word's own letter sweep —
 * one word at a time, as the scrolling reader does ([englishVerseBlooms]).
 * Verses still to come wait under the same recess; verses already read hold
 * their ink.
 *
 * The orange repeat rides the same map: a word the reciter goes back over is
 * tinted on its own English ([addEnglishRepeatBlooms]). The leaf carried
 * neither that nor the wet-ink glint while it had no alignment, because both
 * are statements about one Arabic word and there was no word here to say them
 * of. There is now. The glint stays off: it is the sheen on ink being laid this
 * instant, and a span of prose is too big a thing to glisten.
 */
@Composable
internal fun MushafEnglishSheet(
    page: MushafPage,
    /** Verses that begin on this leaf, keyed by `quranWordKey(s, a, 1)`; null
     * until the query lands. */
    leafText: Map<Long, String>?,
    content: SurahContent,
    basmalahWash: StateFlow<Float?>,
    surahsById: Map<Int, Surah>,
    liveInk: Boolean,
    pageOwnsVoice: Boolean,
    waitingForVoice: Boolean,
    pageHasActiveWord: Boolean,
    activeWordState: State<ActiveWord?>,
    washClock: StateFlow<EnglishWashClock?>,
    playback: State<MushafPlayback>,
    playbackSpeed: Float,
    flashAyah: Int?,
    flashWordPosition: Int?,
    /** Every word a search grounded, and whether that focus is live — the ink
     *  clocks are shared with the Arabic leaf and read both. */
    flashWordPositions: Set<Int>,
    searchFocusActive: Boolean,
    /** The leaf's well and measure, in px, once it has laid out. */
    onMetrics: (wellPx: Float, measurePx: Float) -> Unit = { _, _ -> },
    /**
     * Whether the book these leaves came from was measured rather than counted.
     *
     * The leaf still lays out when it is false — that is how the well and the
     * measure are known at all, and the book cannot be measured until they are.
     * It simply does not *ink* until the leaves are the right ones, so the
     * reader never watches a page rearrange itself.
     */
    measured: Boolean = true,
    verseNumberScript: VerseNumberScript,
    /** The leaf's own size — see [englishLeafSlotPx], which is where it is from. */
    wellPx: Float,
    measurePx: Float,
    /** What this leaf sets, in the book's order — whole verses and carried ones. */
    leafRuns: List<EnglishVerseRun>,
    /**
     * The Arabic word each of those verses opens with — what a tap on an
     * English sentence plays from. Gathered by the pager, because a leaf may
     * draw its verses from more than one Madinah page.
     */
    leafTokens: Map<Pair<Int, Int>, MushafToken>,
    /**
     * Where each Arabic word of a verse lands in its English — the map the wash
     * crosses the sentence on. Shared with the pager so the leaf a carried
     * verse's voice is on is read the same way the ink is.
     */
    alignments: EnglishVerseAlignments,
    /**
     * Play from a share of a verse. A tap says which verse and how far into it;
     * the reader turns that back into a word through the same alignment
     * (`englishSeekWordPosition`).
     */
    onVerseSeek: (surahId: Int, ayah: Int, through: Float) -> Unit,
    /**
     * A hold on the prose: the same verse and share as a tap, which the reader
     * turns into the Arabic word under that English for the Root Word Viewer —
     * what a hold on a word does on every other surface.
     */
    onVerseLongPress: (surahId: Int, ayah: Int, through: Float) -> Unit,
    onBasmalahClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val typePalette = LocalQuranTypePalette.current
    // The verses this leaf actually sets. The ink clocks what is on the paper,
    // which since the book paginates itself is not what is on any one page —
    // and a carried verse is on two leaves, so this is the distinct set.
    val keysOnLeaf = remember(leafRuns) { leafRuns.map { it.key }.distinct() }
    val ayahsOnPage = remember(keysOnLeaf, content.surah.id, content.ayahs) {
        keysOnLeaf.mapNotNull { (surahId, ayah) ->
            if (surahId != content.surah.id) return@mapNotNull null
            content.ayahs.firstOrNull { it.number == ayah }
        }
    }
    // The neighbouring chapters on a shared leaf, whose text is not loaded.
    // Same reading as the Arabic leaf gives them: a lower id is behind the
    // reciter and keeps its ink, a higher one is still to come and waits.
    val upcomingOnPage = remember(keysOnLeaf, content.surah.id) {
        keysOnLeaf.filter { (surahId, _) -> surahId > content.surah.id }
    }
    val recitedOnPage = remember(keysOnLeaf, content.surah.id) {
        keysOnLeaf.filter { (surahId, _) -> surahId < content.surah.id }
    }
    val packsState = remember { mutableStateMapOf<Pair<Int, Int>, AyahInkPack>() }
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
    val leaf = remember(page.page, leafRuns, leafText) {
        val text = leafText ?: return@remember null
        englishLeaf(page.page, leafRuns) { surahId, ayah ->
            text[quranWordKey(surahId, ayah, 1)].orEmpty()
        }
    }
    // The alignment for every verse this leaf sets, gathered once: the wash
    // reads it every frame, and the shared memo solves each verse only once.
    val leafAlignments = remember(ayahsOnPage, alignments) {
        buildMap {
            ayahsOnPage.forEach { ayah ->
                alignments.alignment(ayah.number)?.let { put(ayah.surahId to ayah.number, it) }
            }
        }
    }
    val clock = rememberAnimatedEnglishWashClock(washClock.collectAsStateWithLifecycle())
    val washWindows = rememberEnglishWashWindows(clock, alignments, content)
    // The leaf comes up on the same short fade the Arabic one uses while its
    // page face loads: a page settling into the light rather than text
    // appearing on paper that was already there.
    val leafInk by animateFloatAsState(
        targetValue = if (leaf == null) 0f else 1f,
        animationSpec = tween(EnglishLeafFadeMs, easing = FastOutSlowInEasing),
        label = "englishLeafInk",
    )
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { alpha = leafInk }) {
        if (leaf == null) return@BoxWithConstraints
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        // The leaf's own size, for the ruler that paginates the book from it.
        // Reported and not recomputed — the figures come from englishLeafSlotPx
        // and the report is what proves the root predicted them correctly. If
        // it ever did not, this is the truth and the book is set again from it.
        LaunchedEffect(wellPx, measurePx) { onMetrics(wellPx, measurePx) }
        val palette = rememberWordInkPalette()
        val gold = LocalQuranAccents.current.gold
        val blocks = remember(
            leaf,
            palette.fullInkColor,
            gold,
            verseNumberScript,
            leafTokens,
            leafAlignments,
            typePalette,
        ) {
            englishLeafBlockTexts(
                leaf,
                leafTokens,
                leafAlignments,
                palette.fullInkColor,
                gold,
                verseNumberScript,
                typePalette,
            )
        }
        val justifyLast = (blocks.lastOrNull() as? EnglishLeafBlockText.Prose)
            ?.proseRunsOn(surahsById) == true
        val resolver = LocalFontFamilyResolver.current
        val direction = LocalLayoutDirection.current
        val settingKey = remember(blocks, wellPx, measurePx, density, resolver, direction, typePalette, justifyLast) {
            EnglishLeafSettingKey(
                blocks.map { if (it is EnglishLeafBlockText.Prose) it.copy(verses = emptyList()) else it },
                wellPx, measurePx, density, resolver, direction, typePalette, justifyLast,
            )
        }
        val settingState = remember(settingKey) {
            mutableStateOf(synchronized(EnglishLeafSettings) { EnglishLeafSettings[settingKey] })
        }
        // The pager holds both neighbours. Fit them without blocking a swipe,
        // with a worker-owned measurer rather than the drawing cache below.
        LaunchedEffect(settingState) {
            if (settingState.value != null) return@LaunchedEffect
            settingState.value = withContext(Dispatchers.Default) {
                setEnglishLeaf(
                    blocks, wellPx, measurePx, density,
                    TextMeasurer(resolver, density, direction), typePalette, justifyLast,
                ).also { synchronized(EnglishLeafSettings) { EnglishLeafSettings[settingKey] = it } }
            }
        }
        val setting = settingState.value ?: return@BoxWithConstraints
        val fontSize = with(density) { setting.handPx.toSp() }
        val pitchDp = with(density) { (setting.handPx * setting.leadingEm).toDp() }
        val basmalahDp = remember(setting.handPx, measurePx, density, measurer, typePalette) {
            with(density) {
                englishBasmalahPx(
                    setting.handPx,
                    measurePx,
                    density,
                    measurer,
                    typePalette,
                ).toDp()
            }
        }
        val basmalahFontSize = remember(setting.handPx, measurePx, density, measurer, typePalette) {
            with(density) {
                englishBasmalahHandPx(
                    setting.handPx,
                    measurePx,
                    density,
                    measurer,
                    typePalette,
                ).toSp()
            }
        }
        val pitch = with(density) { (setting.handPx * setting.leadingEm).toSp() }
        // The leaf lays out either way; it inks only once the book that decided
        // its leaves was measured against a leaf this size. A page that arrives
        // a moment late is a page; a page that rearranges itself is a fault.
        val inked by animateFloatAsState(
            targetValue = if (measured) 1f else 0f,
            animationSpec = tween(EnglishLeafFadeMs, easing = FastOutSlowInEasing),
            label = "englishLeafInk",
        )
        Column(
            modifier = Modifier
                .graphicsLayer { alpha = inked }
                .fillMaxSize()
                .padding(horizontal = MushafEdgeGutter),
            // A leaf whose content will not reach the foot hangs from the
            // head, as a book's last page of a chapter does — the paper simply
            // runs out under it. Every leaf, al-Fatihah's included.
            //
            // The Arabic pager makes pages 1-2 the exception and centres them,
            // because the print does: they are framed leaves and the chapter's
            // medallion sits in the middle of the frame. This leaf copied that
            // and should not have. Nothing in an English book floats in the
            // middle of a page — a chapter opens at the head and the text runs
            // down from there, and a short chapter simply leaves paper under
            // itself. Centring read as a layout that had not finished.
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            blocks.forEach { block ->
                when (block) {
                    is EnglishLeafBlockText.Opening -> {
                        val bandDp = with(density) { setting.lineInkPx.toDp() }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(bandDp + pitchDp * EnglishLeafPanelAir * 2f),
                            contentAlignment = Alignment.Center,
                        ) {
                            MushafSurahTitleBand(
                                surah = surahsById[block.surahId],
                                fontSize = fontSize * EnglishLeafPanelType,
                                bandHeight = bandDp,
                                latin = true,
                            )
                        }
                        if (block.basmalah) {
                            EnglishBasmalahLine(
                                fontSize = basmalahFontSize,
                                slotHeight = basmalahDp,
                                topAir = with(density) {
                                    englishBasmalahAirPx(setting.handPx, typePalette).toDp()
                                },
                                active = pageOwnsVoice &&
                                    block.surahId == content.surah.id &&
                                    playback.value.basmalahActive,
                                wash = basmalahWash,
                                onClick = { onBasmalahClick(block.surahId) },
                            )
                        }
                    }

                    is EnglishLeafBlockText.Prose -> EnglishProseBlock(
                        block = block,
                        packs = packsState,
                        activeWord = activeWordState,
                        activeSurahId = content.surah.id,
                        clock = clock,
                        washWindows = washWindows,
                        fontSize = fontSize,
                        lineHeight = pitch,
                        measurePx = measurePx,
                        // The leaf's last paragraph runs on to the next leaf
                        // unless its chapter ends here: its last line is then
                        // a line like any other, and set like one.
                        justifyLast = block === blocks.last() && justifyLast,
                        liveInk = liveInk,
                        onVerseSeek = onVerseSeek,
                        onVerseLongPress = onVerseLongPress,
                    )
                }
            }
        }
    }
}

/**
 * The English leaf's fore-edge margin, shared by its running head, its text
 * block and its folio.
 *
 * A proportion, not a dp, so a tablet gets a tablet's margins.
 */
/**
 * The English leaf's well and measure, from the paper it is set on.
 *
 * This is the whole chain from a page of the pager down to the two figures the
 * book is paginated by, and it is a chain of constants — the grid's bands, the
 * page margin, the fore-edge, the rounding slack. Nothing in it needs a leaf to
 * have been composed. That matters: the book is paginated by measuring against
 * these, and a first launch that had to wait for a leaf to exist before it
 * could learn them was a first launch that paginated with the mushaf already
 * open and the reader watching.
 *
 * [paperWidthPx] and [leafHeightPx] are the page *inside* MushafPageMargin —
 * the box the running head, the well and the folio all share.
 *
 * The one definition. The pager calls it to set the leaf it draws, and the
 * root calls it to paginate before any of this exists; two of these that
 * disagree by a pixel are a book paginated for a leaf that never appears.
 */
internal fun englishLeafSlotPx(
    paperWidthPx: Int,
    leafHeightPx: Int,
    density: Density,
): FloatArray = with(density) {
    val bands = mushafLeafBands(english = true)
    val unit = bands.unitPx(leafHeightPx.toFloat()).toDp()
    // A hair off the well: the block is solved to fill this exactly, and
    // rounding must not put the last line's descenders past the foot.
    val wellPx = ((unit * bands.well).roundToPx() - EnglishLeafFitSlack.roundToPx())
        .toFloat().coerceAtLeast(1f)
    val measurePx = (paperWidthPx - MushafEdgeGutter.roundToPx() * 2)
        .toFloat().coerceAtLeast(1f)
    floatArrayOf(wellPx, measurePx)
}

private val EnglishLeafFitSlack = 2.dp

/**
 * The air on each side of the chapter's panel, in line pitches of the page.
 *
 * The band itself is **one line of the page** — one line's own type box, so it
 * stands exactly as deep as a line of the revelation. This is the paper set
 * around it: about one of the page's own interlines on each side, which makes
 * the panel's whole slot around a line and a half.
 *
 * Not more. Half a pitch a side reads beautifully on its own and costs the leaf
 * two lines of paper for every chapter that opens on it — on a leaf of juz' 30
 * with two openings it took the page's interline from 27 px down to 20 to pay
 * for itself, which is the rest of the page giving up its air so the panel can
 * have some.
 *
 * Equal by construction, because the two sides were not. The prose block is set
 * `Trim.Both`, so its last line stops at the descender and contributes no
 * trailing white of its own; everything that separates the panel from the text
 * has to come out of the panel's own slot. Sized as a fraction of the band it
 * came to 14 px above and 20 px below on a page whose lines sit 27 px apart —
 * tighter than the text it divides, and visibly tighter on one side than the
 * other.
 *
 * Half a pitch is generous on purpose. What is left over after the arithmetic
 * is glyph slack — the last line above may have no descender, the first line
 * below may open on a capital rather than an ascender — and that slack is a
 * fixed few pixels. Against a token gap it is the whole difference; against
 * half a line it is nothing the eye picks out.
 *
 * The panel therefore rides the leading, and is a little deeper on an open leaf
 * than on a close-set one. That is right rather than a fault: the eye reads the
 * panel against the lines it sits *among*, not against a panel on some other
 * leaf it saw ten minutes ago. The Arabic leaf sets its own ʿunwān the same
 * way, on the slot a line of revelation would have had.
 */
private const val EnglishLeafPanelAir = 0.3f


/**
 * The chapter's name inside the panel.
 *
 * Under the page's own hand rather than a step above it, as the Arabic leaf
 * sets it: the cartouche has to sit inside a band of 0.72 of a line at the
 * *tightest* leading in the book, and a name set larger than this stops fitting
 * there. [MushafSurahTitleBand] takes it down again for Latin, which spells a
 * chapter out where Hafs writes it in three or four letters.
 */
private const val EnglishLeafPanelType = 0.95f

/** The mark rides at this share of the prose size, as the scrolling reader sets it. */
private const val EnglishLeafMarkType = 17f / 22f

private const val EnglishLeafFadeMs = 220

/** One block of the leaf, with its text already built. */
private sealed class EnglishLeafBlockText {
    data class Opening(val surahId: Int, val basmalah: Boolean) : EnglishLeafBlockText() {
        /**
         * The paper this opening takes: one line of the page for the panel,
         * plus the basmalah's own measured height where it takes one.
         */
        fun heightPx(pitchPx: Float, lineInkPx: Float, basmalahPx: Float): Float =
            lineInkPx + pitchPx * EnglishLeafPanelAir * 2f +
                if (basmalah) basmalahPx else 0f
    }

    /**
     * One paragraph. [text] runs its verses together as continuous prose —
     * this is a book of the meaning, not a list of verses — and [verses]
     * carries where each of them sits inside it.
     */
    data class Prose(
        val text: AnnotatedString,
        val verses: List<EnglishProseVerse>,
    ) : EnglishLeafBlockText()
}

/**
 * Whether this paragraph carries on past its leaf: its last verse is broken off,
 * or ends without ending its chapter.
 */
private fun EnglishLeafBlockText.Prose.proseRunsOn(surahsById: Map<Int, Surah>): Boolean =
    verses.lastOrNull()?.let { last ->
        last.markRange.isEmpty() || last.ayah < (surahsById[last.surahId]?.ayahCount ?: 0)
    } == true

/** Where one verse sits in the paragraph, and what a tap on it means. */
@Immutable
internal data class EnglishProseVerse(
    val surahId: Int,
    val ayah: Int,
    /** The sentence itself, without its mark: what the wash crosses. */
    val range: IntRange,
    /** Empty on a fragment that does not end its verse — the mark closes it. */
    val markRange: IntRange,
    /** The verse's first word on the page — what a tap plays from. */
    val token: MushafToken?,
    /**
     * Where the reciter is inside *this* fragment's printed text, given where
     * they are inside the verse — carried across by letters, not proportion
     * (`EnglishLeafVerse.fragmentInkProgress`), so a band edge lands on the
     * word it names after whitespace and asides have been removed.
     */
    val fragmentProgress: (Float) -> Float = { it },
    /** Continuous travel between source letters, without changing the owned ranges. */
    val washProgress: (Float) -> Float = fragmentProgress,
    /**
     * The share of the verse a point this many characters into [range] stands
     * at — what a tap seeks by. Identity-ish for a verse set whole.
     */
    val verseFractionAt: (Int) -> Float = { 0f },
    /** Printed ranges owned by spoken words, projected once onto this fragment. */
    val wordSpans: List<EnglishInkSpan>? = null,
    /** Continuous forward ranges; a reordered meaning includes all words before it. */
    val washSpans: List<EnglishInkSpan>? = null,
)

/** A range in the paragraph, owned by a 1-based Arabic word position. */
internal data class EnglishInkSpan(val range: IntRange, val position: Int, val from: Float = 0f, val to: Float = 1f)

/** A fading tint after a backward seek must not reveal words beyond the new prefix. */
internal fun englishInkLayerAllowed(state: InkEngine.State): Boolean = state != InkEngine.State.Upcoming

/** Run continuously like scroll ink; only transport changes interrupt the spoken clock. */
@Composable
private fun rememberAnimatedEnglishWashClock(clock: State<EnglishWashClock?>): State<EnglishWashClock?> {
    val timeline = LocalHighlightTimeline.current
    return remember(clock, timeline) {
        derivedStateOf {
            clock.value?.onFrame(timeline.frame())
        }
    }
}

/** Cache windows on source changes; frame ticks invalidate drawing and leaf thresholds only. */
@Composable
internal fun rememberEnglishWashWindows(
    clock: State<EnglishWashClock?>,
    alignments: EnglishVerseAlignments,
    content: SurahContent,
): Map<Pair<Int, Int>, Map<Int, EnglishWashWindow>> {
    val segments by remember { derivedStateOf(structuralEqualityPolicy()) { clock.value?.segments.orEmpty() } }
    val verse by remember { derivedStateOf(structuralEqualityPolicy()) { clock.value?.let { it.surahId to it.ayah } } }
    return remember(alignments, segments, verse, content) {
        verse?.takeIf { it.first == content.surah.id }?.let { key ->
            val positions = alignments.alignment(key.second)?.washSpans?.map { it.position }
                ?: content.ayahs.firstOrNull { it.number == key.second }?.words?.map { it.position }.orEmpty()
            mapOf(key to englishWashWindows(positions, segments))
        }.orEmpty()
    }
}

/** Projects the continuous wash or semantic ownership through asides and page cuts. */
internal fun englishOwnedSpans(
    range: IntRange,
    alignment: EnglishAlignment,
    fragmentProgress: (Float) -> Float = { it },
    text: CharSequence = "",
    forwardWash: Boolean = true,
): List<EnglishInkSpan> = (if (forwardWash) alignment.washSpans else alignment.spans).mapNotNull { span ->
    val printed = englishWashBands(
        range, fragmentProgress(span.from), fragmentProgress(span.to), 1f, text,
    ).saying
    printed.takeUnless { it.isEmpty() }?.let { EnglishInkSpan(it, span.position, span.from, span.to) }
}

/**
 * Builds every block's text once. The ranges are recorded as the string is
 * assembled, which is the only moment they are knowable.
 */
private fun englishLeafBlockTexts(
    leaf: EnglishLeaf,
    openingTokens: Map<Pair<Int, Int>, MushafToken>,
    alignments: Map<Pair<Int, Int>, EnglishAlignment>,
    ink: Color,
    gold: Color,
    verseNumberScript: VerseNumberScript,
    typePalette: QuranTypePalette,
): List<EnglishLeafBlockText> = leaf.blocks.map { block ->
    when (block) {
        is EnglishLeafBlock.ChapterOpening ->
            EnglishLeafBlockText.Opening(block.surahId, block.basmalah)

        is EnglishLeafBlock.Prose -> {
            val verses = ArrayList<EnglishProseVerse>(block.verses.size)
            val text = buildAnnotatedString {
                block.verses.forEach { verse ->
                    if (length > 0) append(" ")
                    val start = length
                    withStyle(SpanStyle(color = ink)) { append(verse.text) }
                    quietSuppliedBrackets(verse.text, start, ink)
                    val range = start until length
                    // Only the fragment that ends the verse carries its mark: a
                    // carried sentence is numbered where it finishes, as a
                    // paragraph carried over a page is punctuated where it ends.
                    val markRange = if (verse.endsVerse) {
                        // A narrow no-break space, and both halves of that are
                        // load-bearing.
                        //
                        // *No-break*, because the mark closes the verse before
                        // it and a closing number that opens a line reads as if
                        // it introduced the verse below — on this leaf, *the
                        // Knowing* ending a line and `⟨6⟩ Lord of the heavens`
                        // beginning the next. Measured over eight leaves the
                        // free mark opened a line six times in 124; bound, it
                        // never does. The paragraph optimizer was spending them
                        // to score the page — three of the six sat under holes
                        // of 80–101 px the mark would have fitted in.
                        //
                        // *Narrow*, because binding makes the word and its mark
                        // one atom and the rag pays for every pixel of it. A
                        // reference mark takes a thin space in any case; at a
                        // word space the same eight leaves lost 11 px of mean
                        // rag and pushed their worst hole to 188 px, at a thin
                        // one they lose 9 and their worst hole stays where it
                        // was (162 → 159).
                        append("\u202F")
                        val markStart = length
                        appendAyahNumberMark(
                            number = verse.ayah,
                            useArabicIndicDigits =
                                verseNumberScript == VerseNumberScript.ARABIC,
                            style = SpanStyle(
                                color = gold, fontSize = EnglishLeafMarkType.em,
                                letterSpacing = 0.em,
                            ),
                            bookFontFamily = typePalette.leaf,
                            // The leaf is set left to right whichever digits the
                            // reader has chosen, so the cups are always the LTR
                            // pair.
                            ltr = true,
                        )
                        markStart until length
                    } else {
                        IntRange.EMPTY
                    }
                    fun printedSpans(forwardWash: Boolean) = alignments[verse.surahId to verse.ayah]?.let { alignment ->
                        englishOwnedSpans(
                            0 until verse.text.length, alignment, verse::fragmentInkProgress, verse.text, forwardWash,
                        ).map { it.copy(range = (start + it.range.first)..(start + it.range.last)) }
                    }
                    verses += EnglishProseVerse(
                        surahId = verse.surahId,
                        ayah = verse.ayah,
                        range = range,
                        markRange = markRange,
                        token = openingTokens[verse.surahId to verse.ayah],
                        fragmentProgress = verse::fragmentInkProgress,
                        washProgress = verse::fragmentWashProgress,
                        verseFractionAt = { at ->
                            verse.verseFractionAt(at, verse.text.length)
                        },
                        wordSpans = printedSpans(forwardWash = false),
                        washSpans = printedSpans(forwardWash = true),
                    )
                }
            }
            EnglishLeafBlockText.Prose(text = text, verses = verses)
        }
    }
}

/**
 * The hand and the leading this leaf came out at, and how tall one line of it
 * inks — which is what the chapter's panel is built on.
 */
private data class EnglishLeafSetting(
    val handPx: Float,
    val leadingEm: Float,
    val lineInkPx: Float,
)

/** Geometry only: verse tap/wash callbacks belong to this visit, not its measured fit. */
private data class EnglishLeafSettingKey(
    val blocks: List<EnglishLeafBlockText>,
    val wellPx: Float,
    val measurePx: Float,
    val density: Density,
    val resolver: FontFamily.Resolver,
    val direction: LayoutDirection,
    val typePalette: QuranTypePalette,
    val justifyLast: Boolean,
)

/** Reopening a recent leaf reuses its completed fit; old leaves never retain a whole book. */
private val EnglishLeafSettings = object : LinkedHashMap<EnglishLeafSettingKey, EnglishLeafSetting>(12, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EnglishLeafSettingKey, EnglishLeafSetting>?) =
        size > 12
}

/**
 * Keeps the book's type size and fits only the leading to the measured leaf.
 * The ruler already paginates at this same hand, carrying long verses across
 * leaves. A page must never change font size to absorb its amount of text.
 */
private fun setEnglishLeaf(
    blocks: List<EnglishLeafBlockText>,
    wellPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
    justifyLast: Boolean,
): EnglishLeafSetting {
    val requestedLeading = typePalette.leafLeadingEm
    val handPx = englishBookHandPx(wellPx, measurePx, density, measurer, typePalette)
    val basmalahPx = englishBasmalahPx(handPx, measurePx, density, measurer, typePalette)
    val pitches = englishLeafPitches(blocks, handPx, measurePx, density, measurer, typePalette, justifyLast)
    val stands = englishLeafHeightPx(
        blocks, handPx, basmalahPx, requestedLeading, measurePx, density,
        measurer, typePalette, justifyLast,
    )
    val pitchesPx = (pitches * handPx).coerceAtLeast(1f)
    var leadingEm = englishLeafFittedLeadingEm(requestedLeading, stands, wellPx, pitchesPx)
    // Paragraph heights round to pixels while opening furniture keeps fractional
    // pitches. Check the fitted result, correcting only leading if it overruns.
    for (pass in 0 until 2) {
        if (leadingEm == requestedLeading) break
        val fittedHeight = englishLeafHeightPx(
            blocks, handPx, basmalahPx, leadingEm, measurePx, density,
            measurer, typePalette, justifyLast,
        )
        if (fittedHeight <= wellPx) break
        val next = englishLeafClosingLeadingEm(leadingEm, fittedHeight, wellPx, pitchesPx, handPx)
        if (next >= leadingEm) break
        leadingEm = next
    }
    return EnglishLeafSetting(
        handPx = handPx,
        lineInkPx = englishLineInkPx(handPx, density, measurer, typePalette),
        leadingEm = leadingEm,
    )
}

/**
 * How many baseline steps the leaf's blocks hold — its prose lines less one
 * each, plus the air its chapter panels take, which rides the leading too.
 *
 * This is the rate at which the block's height moves with the leading, and it
 * is the only thing the fit guarantee needs: how far the leading must close to
 * lose a given overflow. What does *not* move with the leading — a line's own
 * ink, a panel's band, the basmalah — is deliberately not counted.
 */
private fun englishLeafPitches(
    blocks: List<EnglishLeafBlockText>,
    handPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
    justifyLast: Boolean,
): Float {
    var pitches = 0f
    blocks.forEach { block ->
        when (block) {
            is EnglishLeafBlockText.Opening -> pitches += EnglishLeafPanelAir * 2f
            is EnglishLeafBlockText.Prose -> {
                val lines = measurer
                    .measureEnglishProse(
                        text = block.text,
                        style = englishProseStyle(
                            with(density) { handPx.toSp() },
                            TextUnit.Unspecified,
                            typePalette,
                        ),
                        measurePx = measurePx,
                        density = density,
                        justifyLast = block === blocks.last() && justifyLast,
                    )
                    .lineCount
                pitches += (lines - 1).coerceAtLeast(0).toFloat()
            }
        }
    }
    return pitches.coerceAtLeast(0.001f)
}

/**
 * One line of the book's own ink, top of ascent to foot of descender.
 *
 * Measured unbounded, which is the whole point: constrained to the measure the
 * specimen wraps as soon as the hand is large enough to break it, and then this
 * returns *two* lines' ink. It did — the chapter panel, which is built on this,
 * came out twice as deep as a line the moment the book was set a size larger.
 */
private fun englishLineInkPx(
    handPx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
): Float = measurer.measure(
    text = AnnotatedString(ENGLISH_LEAF_SPECIMEN),
    style = englishProseStyle(
        with(density) { handPx.toSp() },
        TextUnit.Unspecified,
        typePalette,
    ),
    constraints = Constraints(),
    density = density,
).size.height.toFloat()

/**
 * What the leaf really stands at, in px, set at this hand and this leading.
 *
 * Not the model above. That is what the leading was *chosen* from; this is the
 * page as it will be drawn, panels and rounding and all. A leaf that came out
 * one line over its well lost that line off the foot, which here is revelation
 * the reader cannot see.
 */
private fun englishLeafHeightPx(
    blocks: List<EnglishLeafBlockText>,
    handPx: Float,
    basmalahPx: Float,
    leadingEm: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
    justifyLast: Boolean,
): Float {
    val inkPx = englishLineInkPx(handPx, density, measurer, typePalette)
    val style = englishProseStyle(
        with(density) { handPx.toSp() },
        with(density) { (handPx * leadingEm).toSp() },
        typePalette,
    )
    return blocks.sumOf { block ->
        when (block) {
            is EnglishLeafBlockText.Opening -> block
                .heightPx(handPx * leadingEm, inkPx, basmalahPx)
                .toDouble()
            is EnglishLeafBlockText.Prose -> measurer
                .measureEnglishProse(
                    text = block.text,
                    style = style,
                    measurePx = measurePx,
                    density = density,
                    justifyLast = block === blocks.last() && justifyLast,
                )
                .size
                .height
                .toDouble()
        }
    }.toFloat()
}

/**
 * The book's one hand, measured: the size at which a leaf's worth of prose
 * exactly fills the well.
 *
 * Two passes. The first lands within a percent or so; the second takes up the
 * rounding, because a block's height moves in whole lines and the arithmetic
 * moves continuously. Cheap — two layouts of about 1,700 characters, once per
 * screen geometry rather than once per leaf.
 */
private fun englishBookHandPx(
    wellPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
): Float {
    // Junicode is set as Garamond: the hand Garamond's leaf solves to, at
    // Junicode's size. Solved in its own metrics the two-pass estimate landed
    // 2 % smaller and a line longer, a different page from the same measure.
    if (typePalette.profile == com.beautifulquran.ui.theme.QuranTypeProfile.JUNICODE) {
        val garamond = com.beautifulquran.ui.theme.quranTypePalette(com.beautifulquran.ui.theme.QuranTypeProfile.CLASSIC)
        return englishBookHandPx(wellPx, measurePx, density, measurer, garamond) *
            com.beautifulquran.ui.theme.JUNICODE_SIZE
    }
    val block = AnnotatedString(englishLeafReferenceBlock(typePalette.leafCapacityChars))
    var handPx = ENGLISH_LEAF_PROBE_FONT_PX
    // Calibrate at natural spacing so tighter prose keeps the book's type size.
    repeat(2) {
        val stands = measurer.measure(
            text = block,
            style = englishProseStyle(
                with(density) { handPx.toSp() },
                with(density) { (handPx * typePalette.leafLeadingEm).toSp() },
                typePalette,
            ).copy(letterSpacing = 0.em),
            constraints = Constraints(maxWidth = measurePx.toInt().coerceAtLeast(1)),
            density = density,
        ).size.height.toFloat()
        handPx = englishLeafHandPx(handPx, stands, wellPx)
    }
    return handPx
}

/**
 * The book's hand: justified, with whole words.
 *
 * [englishSetProse] chooses the breaks ([EnglishJustify]) and draws each line
 * out to the measure with spans; this style is what it sets them in. See §13.5
 * of `docs/QURAN_TYPOGRAPHY.md` for the measured tradeoffs.
 *
 * Hyphenation is off for both the ruler and the page. The chosen breaks replace
 * spaces in place, so the wash, paper covers and taps keep their character
 * offsets. Simple wrapping is the fallback for paragraphs the setter declines.
 */
internal fun englishProseStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    typePalette: QuranTypePalette,
) = TextStyle(
    fontFamily = typePalette.leaf,
    fontSize = fontSize,
    // Tighter prose fits whole words without reducing the book's type size.
    letterSpacing = (-0.025f).em,
    lineHeight = lineHeight,
    textAlign = TextAlign.Start,
    // Greedy, deliberately. Balanced and HighQuality both even the lines out,
    // and an evened rag is the one thing a rag must not be: 39% of lines
    // landed within 20 px of the mean and only 10% reached the margin, which
    // draws a soft second margin a word's width inside the true one and leaves
    // the block looking pinned to the left. Greedy fills each line as far as
    // it goes: 20% of lines now reach the margin and the mean cluster falls to
    // 29%, for 3 px more mean shortfall and eight more deep holes. The holes
    // are the price of an active rag; the phantom edge was not worth avoiding
    // them. See docs/QURAN_TYPOGRAPHY.md §13.5.
    //
    // The leaf no longer pays it: englishSetProse breaks and justifies its
    // lines itself and writes the breaks into the text. This is the fallback
    // for a paragraph it declines.
    lineBreak = LineBreak.Paragraph.copy(strategy = LineBreak.Strategy.Simple),
    hyphens = Hyphens.None,
    // The book face's refinements: kerning and ligatures on, old-style figures
    // so the prose (and its brackets and quotes) sets with an even colour —
    // the same features the web leaf and every other English surface set.
    fontFeatureSettings = typePalette.bookFeatures,
    textMotion = typePalette.bookTextMotion,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    // Trim.Both puts the block's own edges on the grid: the first line starts
    // at its ascent and the last stops at its descender, instead of half a
    // leading beyond each. Untrimmed, that half-leading grew with the leading —
    // so the first line of a light leaf sat 7 dp lower than the first line of a
    // heavy one, and the head gutter the grid promises was not the paper the
    // reader saw. It also makes the block's height (n − 1) pitches plus one
    // line's ink, which is what setEnglishLeaf solves.
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * A paragraph on the leaf's measure, set as the leaf sets it — or, with
 * [setLines] off, as the platform wraps it, which the ruler reads only as a
 * fallback (it counts justified lines: [englishProseLineCount]).
 */
private fun TextMeasurer.measureEnglishProse(
    text: AnnotatedString,
    style: TextStyle,
    measurePx: Float,
    density: Density,
    setLines: Boolean = true,
    justifyLast: Boolean = false,
): TextLayoutResult {
    val set = if (setLines) englishSetProse(text, style, measurePx, density, this, justifyLast) else text
    return measure(
        text = set,
        style = style,
        constraints = englishProseConstraints(measurePx),
        softWrap = set.englishSoftWrap,
        density = density,
    )
}

/**
 * Whether the platform may still wrap a set paragraph. Not where its lines are
 * already broken: those lines are known to fit, and the platform's own line
 * breaker measures a styled run — a quiet bracket, a verse mark's isolated
 * figures — a few px wider than it draws it, and would break a full justified
 * line early.
 */
internal val AnnotatedString.englishSoftWrap: Boolean get() = '\n' !in text

/** Leave one pixel for rounding when the chosen lines are drawn at the full measure. */
private fun englishProseConstraints(measurePx: Float) =
    Constraints(maxWidth = (measurePx.toInt() - 1).coerceAtLeast(1))

/**
 * The paragraph as the leaf sets it: its lines broken where [EnglishJustify]
 * chooses, and each drawn out to the measure.
 *
 * Each word space a line ends at becomes a line break *in place*, one
 * character for one, and the drawing-out is spans over the text — the line's
 * letters set a little wider, its word spaces a little looser — so every offset
 * the wash, the paper covers and the taps hold still names the same letter. The
 * platform then only sets lines that already fit, and breaks nothing itself.
 *
 * The positions are its words' own widths laid end to end
 * ([englishRagCandidates]), and the answer is remembered: the leaf is measured
 * several times at the same hand while the leaf is fitted.
 *
 * [justifyLast] draws the last line out too, for a paragraph the leaf breaks
 * off and the next leaf carries on. Where the setting cannot be trusted to keep
 * the ruler's line count, the paragraph falls back to [EnglishRag]'s ragged
 * breaks, and failing those to the platform's own.
 */
internal fun englishSetProse(
    text: AnnotatedString,
    style: TextStyle,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    justifyLast: Boolean = false,
): AnnotatedString {
    val source = text.text
    val n = source.length
    if (n < 2 || measurePx <= 1f) return text
    val key = EnglishRagKey(text, style.englishWidthStyle, measurePx, density.density, density.fontScale, justifyLast)
    synchronized(EnglishRagMemo) { EnglishRagMemo[key]?.let { return it } }

    val constraints = englishProseConstraints(measurePx)
    // The platform's own wrapping: only the ragged fallback's line count.
    val laid by lazy(LazyThreadSafetyMode.NONE) {
        measurer.measure(text = text, style = style, constraints = constraints, density = density)
    }
    val found = englishRagCandidates(text, style, density, measurer) ?: return memo(key, text)
    val candidates = found.candidates
    val offsets = found.offsets
    val spacePx = found.spacePx
    val measure = measurePx.toInt().toFloat()
    val fontPx = with(density) { style.fontSize.toPx() }
    fun boxed(set: AnnotatedString) =
        measurer.measure(text = set, style = style, constraints = constraints, density = density)

    englishJustifyModel(found, source, measure, fontPx, justifyLast)?.let { lines ->
        val breaks = lines.filter { it.end >= 0 }.map { offsets[it.end] }.toIntArray()
        val broken = text.withEnglishBreaks(breaks)
        // The model's widths are chained positions; the platform rounds, and
        // a widened line's own spaces widen with it. So the paragraph is set,
        // each line's drawn right edge read back, and what is left over shared
        // among that line's spaces again. The lines are already broken, so it
        // is measured unbounded — nothing can wrap while it converges — and
        // only the final setting is tried in the real box.
        val target = constraints.maxWidth - EnglishJustifyEdgePx
        val extras = FloatArray(lines.size) { lines[it].spaceExtraPx }
        var set = broken.justifiedBy(lines, extras, style, fontPx)
        repeat(EnglishJustifyPasses) {
            val free = measurer.measure(set, style, density = density)
            if (free.lineCount != lines.size) return@let
            for (i in lines.indices) {
                val line = lines[i]
                if (!line.justified) continue
                val residual = target - free.getLineRight(i)
                extras[i] = extras[i] + residual / (line.spaces * (1f + line.expansion))
            }
            set = broken.justifiedBy(lines, extras, style, fontPx)
        }
        return memo(key, set)
    }
    // The ragged setting, where the justified one could not be trusted.
    val chosen = EnglishRag.breaks(
        candidates,
        measurePx = measure,
        emPx = with(density) { style.fontSize.toPx() },
    ) ?: return memo(key, text)
    val ragged = text.withEnglishBreaks(IntArray(chosen.size) { offsets[chosen[it]] })
    // Tight tracking can round a chosen line wider than its chained positions.
    // Keep the ruler's line count rather than spending another line on the page.
    return memo(key, if (boxed(ragged).lineCount == laid.lineCount) ragged else text)
}

/**
 * The justified setting of a paragraph whose candidates are [found]: the one
 * model the ruler counts lines with and the leaf draws, so the two can never
 * disagree about how many lines a leaf holds.
 */
private fun englishJustifyModel(
    found: EnglishRagFound,
    source: String,
    measure: Float,
    fontPx: Float,
    justifyLast: Boolean = false,
): List<EnglishJustify.Line>? = EnglishJustify.set(
    found.candidates, measure, found.spacePx, justifyLast,
    tuning = EnglishJustifyTuning,
    text = englishJustifyText(source, found.offsets, fontPx),
)

/**
 * How many lines the leaf sets [text] in, justified: what the ruler paginates
 * by. The platform's own wrapping where the paragraph is not one the
 * justifier sets.
 */
private fun TextMeasurer.englishProseLineCount(
    text: AnnotatedString,
    style: TextStyle,
    measurePx: Float,
    density: Density,
    /** Whether the next leaf carries this one on; see [englishSetProse]. */
    justifyLast: Boolean,
): Int {
    fun platform() = measure(
        text = text, style = style, constraints = englishProseConstraints(measurePx), density = density,
    ).lineCount
    if (text.length < 2) return platform()
    val found = englishRagCandidates(text, style, density, this) ?: return platform()
    val fontPx = with(density) { style.fontSize.toPx() }
    return englishJustifyModel(found, text.text, measurePx.toInt().toFloat(), fontPx, justifyLast)?.size
        ?: platform()
}

/** A paragraph's break candidates on one unbroken line: see [englishRagCandidates]. */
private class EnglishRagFound(
    val candidates: EnglishRag.Candidates,
    /** The text offset of each candidate's word space. */
    val offsets: IntArray,
    val spacePx: Float,
    /** Where the word ending at an offset ends on the one line; NaN inside a word. */
    val positionOf: (Int) -> Float,
) {
    /**
     * The same paragraph cut to its first [length] characters, without
     * measuring it again: positions are words laid end to end, so a prefix's
     * are the whole's up to the cut — exactly the ones the leaf cut there will
     * read for itself. Null when the cut is not at a word's end.
     */
    fun prefix(length: Int): EnglishRagFound? {
        val end = positionOf(length).takeUnless { it.isNaN() } ?: return null
        var m = 0
        while (m < offsets.size && offsets[m] < length - 1) m++
        return EnglishRagFound(
            EnglishRag.Candidates(
                candidates.contentEnd.copyOf(m),
                candidates.nextStart.copyOf(m),
                end,
            ),
            offsets.copyOf(m),
            spacePx,
            positionOf,
        )
    }
}

/**
 * Where a paragraph may break, and where each break falls on one unbroken
 * line: its words' widths laid end to end, a word space between.
 *
 * Every word space is a candidate except the one before a dash, which stays
 * with the word before it. A no-break space (the one binding a verse mark to
 * its verse) is a gap that stretches, never a break.
 */
private fun englishRagCandidates(
    text: AnnotatedString,
    style: TextStyle,
    density: Density,
    measurer: TextMeasurer,
): EnglishRagFound? {
    val source = text.text
    val n = source.length
    if (n < 2) return null
    val spacePx = englishWordWidth(AnnotatedString(" "), style, density, measurer)
    // The paragraph as words laid end to end: each word's own width, read once
    // for the whole book, and a word space between. A word measures the same
    // wherever it falls, so the ruler that cuts a leaf from a longer offer and
    // the leaf that sets only itself read the same positions — the line count
    // the ruler pays for is the one the leaf draws. The leaf then measures each
    // line it actually draws and closes on the edge from there.
    val offsets = ArrayList<Int>()
    val ends = ArrayList<Float>()
    val starts = ArrayList<Float>()
    // Where each word ends, by the offset it ends at: what a prefix's end is.
    val wordEnds = HashMap<Int, Float>()
    var x = 0f
    var wordStart = 0
    val bias = englishWordBias(style, density, measurer)
    fun word(end: Int) {
        x += englishWordWidth(text.subSequence(wordStart, end), style, density, measurer) - bias
        wordEnds[end] = x
    }
    for (i in 1 until n - 1) {
        // A dash stays with the word before it: a line never opens on one.
        if (source[i] == ' ' && source[i + 1] != '\u2013' && source[i + 1] != '\u2014') {
            word(i)
            offsets += i
            ends += x
            x += spacePx
            starts += x
            wordStart = i + 1
        }
    }
    word(n)
    val textEnd = x
    return EnglishRagFound(
        EnglishRag.Candidates(ends.toFloatArray(), starts.toFloatArray(), textEnd),
        offsets.toIntArray(),
        spacePx,
    ) { at -> wordEnds[at] ?: Float.NaN }
}

/** A word's advance in [style], remembered: the book sets the same words over and over. */
private fun englishWordWidth(
    word: AnnotatedString,
    style: TextStyle,
    density: Density,
    measurer: TextMeasurer,
): Float {
    val key = EnglishWordKey(word, style.englishWidthStyle, density.density, density.fontScale)
    synchronized(EnglishWordWidths) { EnglishWordWidths[key]?.let { return it } }
    // Measured with a space after it, and read where the space begins: the
    // advance the word has inside a line. Alone, its last letter loses the
    // tracking it carries mid-line, and every word came out a pixel wide.
    val laid = measurer.measure(word + AnnotatedString(" "), style, softWrap = false, maxLines = 1, density = density)
    val width = if (word.isEmpty()) 0f else laid.getHorizontalPosition(word.length, true)
    synchronized(EnglishWordWidths) { EnglishWordWidths[key] = width }
    return width
}

/**
 * How much wider a word measures on its own than it sets among others, in px:
 * kerning against the spaces either side and the tracking at its edges, which
 * a word alone never meets. Steady to a fraction of a pixel across the book
 * (1.4–1.7 px a word at the leaf's hand), so it is read once per style off a
 * specimen set both ways and taken off every word.
 */
private fun englishWordBias(style: TextStyle, density: Density, measurer: TextMeasurer): Float {
    val key = EnglishWordKey(AnnotatedString(ENGLISH_LEAF_SPECIMEN), style.englishWidthStyle, density.density, density.fontScale)
    synchronized(EnglishWordWidths) { EnglishWordBiases[key]?.let { return it } }
    val words = ENGLISH_LEAF_SPECIMEN.split(' ').filter { it.isNotEmpty() }
    val set = words.joinToString(" ")
    val real = measurer.measure(AnnotatedString(set), style, softWrap = false, maxLines = 1, density = density)
        .getLineRight(0)
    val space = englishWordWidth(AnnotatedString(" "), style, density, measurer)
    val model = words.sumOf { englishWordWidth(AnnotatedString(it), style, density, measurer).toDouble() }.toFloat() +
        space * (words.size - 1)
    val bias = (model - real) / words.size
    synchronized(EnglishWordWidths) { EnglishWordBiases[key] = bias }
    return bias
}

private val EnglishWordBiases = HashMap<EnglishWordKey, Float>()

/** Horizontal layout is shared across leading changes; measurement keeps the caller's full style. */
private val TextStyle.englishWidthStyle: TextStyle
    get() = copy(lineHeight = TextUnit.Unspecified)

private data class EnglishWordKey(
    val word: AnnotatedString,
    val style: TextStyle,
    val density: Float,
    val fontScale: Float,
)

private val EnglishWordWidths = object : LinkedHashMap<EnglishWordKey, Float>(4096, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EnglishWordKey, Float>?) =
        size > 60_000
}

/**
 * How far inside the box a drawn-out line stops, in px: a line set exactly to
 * the box's width can measure a hair over it and wrap.
 */
private const val EnglishJustifyEdgePx = 0.5f

/** Measured passes over the modelled setting: two close on the edge to well under a pixel. */
private const val EnglishJustifyPasses = 2

/** How the leaf justifies: see [EnglishJustify.LEAF]. */
internal val EnglishJustifyTuning = EnglishJustify.LEAF

/** A word space, or the bound space before a verse mark: what a line stretches. */
private fun Char.isEnglishJustifyGap() = this == ' ' || this == '\u202F'

/** The paragraph's gaps and letters, by offset, for [EnglishJustify]. */
private fun englishJustifyText(source: String, offsets: IntArray, emPx: Float): EnglishJustify.Text {
    val gaps = IntArray(source.length + 1)
    val letters = IntArray(source.length + 1)
    source.forEachIndexed { i, c ->
        gaps[i + 1] = gaps[i] + if (c.isEnglishJustifyGap()) 1 else 0
        val advances = !c.isEnglishJustifyGap() && Character.getType(c) != Character.FORMAT.toInt()
        letters[i + 1] = letters[i] + if (advances) 1 else 0
    }
    return EnglishJustify.Text(gaps, letters, offsets, emPx)
}

private fun AnnotatedString.withEnglishBreaks(breaks: IntArray): AnnotatedString {
    if (breaks.isEmpty()) return this
    val chars = text.toCharArray()
    breaks.forEach { at -> chars[at] = '\n' }
    return AnnotatedString(String(chars), spanStyles, paragraphStyles)
}

/**
 * [lines] drawn out: each justified line's letters widened by its expansion,
 * and its word spaces each [extras] px looser, as spans over the text.
 */
private fun AnnotatedString.justifiedBy(
    lines: List<EnglishJustify.Line>,
    extras: FloatArray,
    style: TextStyle,
    fontPx: Float,
): AnnotatedString = buildAnnotatedString {
    append(this@justifiedBy)
    val tracking = if (style.letterSpacing.isEm) style.letterSpacing.value else 0f
    var start = 0
    lines.forEachIndexed { i, line ->
        var end = start
        while (end < length && text[end] != '\n') end++
        if (line.justified && end > start) {
            if (line.expansion != 0f) {
                addStyle(
                    SpanStyle(textGeometricTransform = TextGeometricTransform(scaleX = 1f + line.expansion)),
                    start,
                    end,
                )
            }
            val letters = tracking + line.trackingPx / fontPx
            if (line.trackingPx != 0f) addStyle(SpanStyle(letterSpacing = letters.em), start, end)
            val gap = SpanStyle(letterSpacing = (letters + extras[i] / fontPx).em)
            for (at in start until end) {
                if (text[at].isEnglishJustifyGap()) addStyle(gap, at, at + 1)
            }
        }
        start = end + 1
    }
}

private data class EnglishRagKey(
    val text: AnnotatedString,
    val style: TextStyle,
    val measurePx: Float,
    val density: Float,
    val fontScale: Float,
    val justifyLast: Boolean,
)

/** Recent paragraphs' settings: fitting and drawing ask for the same text repeatedly. */
private val EnglishRagMemo = object : LinkedHashMap<EnglishRagKey, AnnotatedString>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EnglishRagKey, AnnotatedString>?) =
        size > 96
}

private fun memo(key: EnglishRagKey, set: AnnotatedString): AnnotatedString {
    synchronized(EnglishRagMemo) { EnglishRagMemo[key] = set }
    return set
}

/** One paragraph of the leaf, with the reciter's ink over it. */
@Composable
private fun EnglishProseBlock(
    block: EnglishLeafBlockText.Prose,
    packs: Map<Pair<Int, Int>, AyahInkPack>,
    activeWord: State<ActiveWord?>,
    activeSurahId: Int,
    clock: State<EnglishWashClock?>,
    washWindows: Map<Pair<Int, Int>, Map<Int, EnglishWashWindow>>,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    /** The measure the ruler paginated against, which the lines are broken to. */
    measurePx: Float,
    /** Whether the paragraph runs on past this leaf; see [englishSetProse]. */
    justifyLast: Boolean,
    liveInk: Boolean,
    onVerseSeek: (surahId: Int, ayah: Int, through: Float) -> Unit,
    onVerseLongPress: (surahId: Int, ayah: Int, through: Float) -> Unit,
) {
    val palette = rememberWordInkPalette()
    // Null on Paper, which does not define the accent and so does not glimmer.
    val glintInk = LocalQuranAccents.current.glintInk
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hitSlopPx = with(LocalDensity.current) { 6.dp.toPx() }
    val style = englishProseStyle(fontSize, lineHeight, LocalQuranTypePalette.current)
    val measurer = rememberTextMeasurer()
    // The setting keeps the ruler's line count and block.text's offsets, so
    // the verses' ranges still name the same words.
    val density = LocalDensity.current
    val set = remember(block.text, style, measurePx, density, justifyLast) {
        englishSetProse(block.text, style, measurePx, density, measurer, justifyLast)
    }
    // English words have their own positions within an Arabic owner's band.
    // Capture those positions rather than replaying the Arabic letter mask.
    val paperDeposits = remember(block) { HashMap<Int, PaperInkDeposit>() }
    val printedWords = remember(block.text, block.verses) {
        englishPaperInkWordRanges(block.text, block.verses.map { it.range })
    }
    val washEntries = remember(block) { HashMap<Pair<Int, Int>, EnglishWashEntry>() }
    Text(
        text = set,
        style = style,
        softWrap = set.englishSoftWrap,
        // Never clip. The leading is solved to fill the well exactly, and the
        // pitch's px→sp rounding across a full leaf can leave the block a few
        // pixels taller than the room the column has left. Text's default Clip
        // then cut at its own box, which on the last line sits just under the
        // baseline — the foot line lost the feet of its y, p and q. The
        // pagination decides what the leaf holds; this only lets the last
        // line's descenders draw into the fit slack beneath it.
        overflow = TextOverflow.Visible,
        modifier = Modifier
            .fillMaxWidth()
            .shapedWordBloom(
                blooms = {
                    if (!liveInk) {
                        emptyList()
                    } else {
                        block.verses.flatMap { verse ->
                            englishVerseBlooms(
                                verse = verse,
                                pack = packs[verse.surahId to verse.ayah],
                                palette = palette,
                                glintInk = glintInk,
                                text = block.text,
                                word = activeWord.value?.takeIf {
                                    it.ayah == verse.ayah && activeSurahId == verse.surahId
                                },
                                clock = clock.value,
                                windows = washWindows[verse.surahId to verse.ayah].orEmpty(),
                                paperDeposits = paperDeposits,
                                printedWords = printedWords[verse.range].orEmpty(),
                                washEntry = washEntries.getOrPut(verse.surahId to verse.ayah) { EnglishWashEntry() },
                            )
                        }
                    }
                },
                layout = { layoutResult },
                rtl = false,
                feather = InkEngine.tuning.washFeather,
                // No reach past the box, and this is the one caller that must
                // not have it. The default 4 dp is right where a bloom covers a
                // word with whitespace either side of it — the reach lands on
                // the space and nothing shows. This leaf's blooms *abut*: the
                // word being said is drawn against the band still to come, and
                // a reach on both sides of that seam paints the same strip of
                // prose with paper twice, so an eight-dp notch of half-erased
                // text travelled along with the voice. The bands tile the
                // sentence exactly, so they need no reach to close over it.
                coverPad = 0.dp,
                justified = false,
                // A line-end "f" hooks past the abutting bands; see lineEndReach.
                lineEndPad = PaperCoverPad,
            )
            .pointerInput(block, layoutResult) {
                // The sentence is the unit of this page, as the word is the
                // unit of the Arabic one — but a touch still says *where* in
                // the sentence, and that share of the verse names a word. See
                // englishSeekWordPosition.
                fun at(point: Offset, wordMiddle: Boolean, act: (Int, Int, Float) -> Unit) {
                    val layout = layoutResult ?: return
                    val verse = block.verses.getOrNull(
                        layout.wordIndexAt(point, block.verses.map { it.range }, hitSlopPx),
                    )
                        ?: return
                    var caret = layout.getOffsetForPosition(point)
                    // A hold names a word, and the caret sits at its edge
                    // when the finger is on the word's first or last letter,
                    // which a share of the verse reads as its neighbour.
                    if (wordMiddle) {
                        val word = layout.getWordBoundary(caret)
                        if (!word.collapsed) caret = (word.start + word.end) / 2
                    }
                    val offset = caret - verse.range.first
                    act(verse.surahId, verse.ayah, verse.verseFractionAt(offset.coerceAtLeast(0)))
                }
                detectTapGestures(
                    onLongPress = { press -> at(press, true, onVerseLongPress) },
                    onTap = { tap -> at(tap, false, onVerseSeek) },
                )
            },
        onTextLayout = { layoutResult = it },
    )
}

/**
 * The blooms for one verse of the paragraph — the scrolling reader's own word
 * states, drawn as bands of a sentence instead of a row of word nodes.
 *
 * The scrolling reader washes **one word at a time**: the word being said gets
 * an [ShapedWordBloom.InkReveal] over its own glyphs, on its own letter sweep,
 * with the engine's own feather; the words behind it hold full ink and draw
 * nothing; the words ahead sit under paper. That is the pace and the fidelity
 * of this app's ink, and for a while the leaf could not copy it — it had no way
 * to say which English a word was — so it swept one continuous front across the
 * whole sentence instead. A front crossing a paragraph is not a word blooming,
 * however narrow its edge is made, and it read as the page brightening rather
 * than as words being said.
 *
 * The base wash is one continuous prefix. A reordered meaning takes every
 * intervening English word into its wash, so there can be no dim hole before
 * the leading edge. Each band follows the calibrated media clock until the
 * next band starts, or until the final spoken segment ends. Pauses hold the
 * edge and reordered Arabic tails keep it moving. Each first wash completes
 * before a backward repeat begins. Glimmer and repeat tint
 * cannot pass that edge; the shared pack retains repeat owners and seek recess.
 */
private fun englishVerseBlooms(
    verse: EnglishProseVerse,
    pack: AyahInkPack?,
    palette: WordInkPalette,
    /** Fresh-ink sheen; null on themes without it. See [addEnglishInkLayerBlooms]. */
    glintInk: Color?,
    /** The paragraph, so a band's edge can be kept out of the middle of a word. */
    text: CharSequence,
    word: ActiveWord?,
    clock: EnglishWashClock?,
    windows: Map<Int, EnglishWashWindow>,
    paperDeposits: MutableMap<Int, PaperInkDeposit>,
    printedWords: List<IntRange>,
    washEntry: EnglishWashEntry,
): List<ShapedWordBloom> {
    if (pack == null) return emptyList()
    val paper = palette.paperColor
    val blooms = ArrayList<ShapedWordBloom>(4)
    val cover = pack.recessCover.value.coerceIn(0f, 1f)
    val resting = InkEngine.State.Upcoming.inkAlpha()
    val waiting = maxOf(cover, 1f - resting)
    val motions = pack.motions
    val spans = verse.wordSpans ?: motions.indices.mapNotNull { index ->
        val span = englishWashBands(
            verse.range, verse.fragmentProgress(index.toFloat() / motions.size),
            verse.fragmentProgress((index + 1f) / motions.size), 1f, text,
        ).saying
        span.takeUnless { it.isEmpty() }?.let {
            EnglishInkSpan(it, index + 1, index.toFloat() / motions.size, (index + 1f) / motions.size)
        }
    }
    val washSpans = verse.washSpans ?: spans
    val performing = word?.takeIf { motions.any { motion -> motion.isActive } }
    val sample = performing?.let { active -> clock?.takeIf { it.matches(active, verse.surahId) } }
    val positionMs = performing?.let { englishWashPositionMs(it, sample, verse.surahId) }
    fun progress(span: EnglishInkSpan, motion: InkMotion): Float =
        if (positionMs != null && windows[span.position] != null) {
            englishWashFragmentProgress(span, windows.getValue(span.position).progress(
                positionMs,
                if (span.position == 1) sample?.voiceStartMs ?: performing.startMs else Long.MIN_VALUE,
            ), verse.washProgress)
        } else when (motion.ink.state) {
            InkEngine.State.Upcoming -> 0f
            InkEngine.State.Active -> if (motion.repeat) 1f else performing?.let {
                EnglishWashWindow(it.startMs, it.startMs + it.durationMs).progress(positionMs ?: it.startMs)
            } ?: motion.plainSweepProgress.coerceIn(0f, 1f)
            else -> 1f
        }
    var washEdge = verse.range.first.toFloat()
    if (motions.isEmpty()) {
        // No clock of its own: a leaf the voice is not on, waiting or settled.
        if (cover > 0f) blooms += cover(verse.range, paper, cover)
    } else {
        for (span in washSpans) {
            val motion = motions.getOrNull(span.position - 1) ?: continue
            val progress = progress(span, motion)
            if (progress > 0f) washEdge = span.range.first + (span.range.last + 1 - span.range.first) * progress
        }
        performing?.let { washEntry.onFrame(it.playbackActivation, it.reciterId, washEdge) }
        // The material uses character progress within printed words. A phrase-
        // wide pixel mask is a different edge and can re-cover a completed word
        // when its wet material stops drawing. Keep the fallback on the same edge.
        for (range in printedWords) {
            val motion = washSpans.firstOrNull { range.first in it.range }
                ?.let { motions.getOrNull(it.position - 1) }
            val bloom = if (palette.paperInk != null && motion?.paperDeparture?.active == true) {
                ShapedWordBloom.InkReveal(
                    range = range,
                    progress = (paperDeposits[range.first]?.progress ?: 0f).coerceAtLeast(0f),
                    paper = paper,
                    restingAlpha = resting,
                    revealedAlpha = motion.paperDepartureAlpha,
                )
            } else englishWordWashCover(
                range, englishPaperInkWordProgress(washEdge, range.first, range.last + 1),
                paper, resting, waiting,
                if (performing != null) washEntry.recitedCover(range, cover) else cover,
            )
            val previous = blooms.lastOrNull() as? ShapedWordBloom.UpcomingDim
            if (bloom is ShapedWordBloom.UpcomingDim && previous != null &&
                previous.coverAlpha == bloom.coverAlpha && previous.range.last < range.first) {
                blooms[blooms.lastIndex] = previous.copy(range = previous.range.first..range.last)
            } else if (bloom != null) blooms += bloom
        }
    }
    // The mark's cover is paper, so it goes down before any ink — left after
    // the ink layers it landed on the last word's glint halo and cut it.
    val markCover = (1f - pack.markAlpha.value).coerceIn(0f, 1f)
    if (markCover > 0f && !verse.markRange.isEmpty()) {
        blooms += cover(verse.markRange, paper, markCover)
    }
    if (motions.isNotEmpty() && palette.paperInk != null) {
        blooms.addEnglishPaperInk(printedWords, washSpans, motions, palette.paperInk, washEdge, paperDeposits)
    }
    if (motions.isNotEmpty()) {
        blooms.addEnglishInkLayerBlooms(washSpans, motions, palette, glintInk, repeatLayers = false, washEdge)
        blooms.addEnglishInkLayerBlooms(spans, motions, palette, glintInk, repeatLayers = true, washEdge,
            departures = paperDeposits.takeIf { palette.paperInk != null })
    }
    return blooms
}

/**
 * Paper ink on the English leaf (docs/PAPER_INK.md). The leaf's first-pass
 * wash is one continuous prefix with its edge at [washEdge] (a character
 * position), so each English word's progress is its own distance from that
 * edge: words behind it are laid, the word it is crossing is mid-ink, and the
 * next word stays unread until the edge reaches its start. A word
 * stays with the paper ink while its owner's ink is still wet.
 *
 * Drawn over the leaf's covers — the paper ink erases what they left of the
 * word — and under its orange repeat tint. With no fallback of its own: a
 * word whose field is not ready simply keeps the leaf's wash.
 */
private fun MutableList<ShapedWordBloom>.addEnglishPaperInk(
    printedWords: List<IntRange>,
    washSpans: List<EnglishInkSpan>,
    motions: List<InkMotion>,
    style: PaperInkStyle,
    washEdge: Float,
    deposits: MutableMap<Int, PaperInkDeposit>,
) {
    // Observe settled owners too, so a rewind has a dry deposit to release.
    motions.forEach { it.paperInkLive }
    var warmedNext = false
    for (range in printedWords) {
        val i = range.first
        val j = range.last + 1
        val owner = washSpans.firstOrNull { i in it.range }?.let { motions.getOrNull(it.position - 1) }
        val raw = englishPaperInkWordProgress(washEdge, i, j)
        val evaporating = owner?.paperDeparture?.active == true
        val current = PaperInkDeposit(raw, owner?.paperWetness ?: 0f, 0f, 0f, 0f)
        val deposit = if (evaporating) deposits[i] ?: current else current.also { deposits[i] = it }
        val wet = deposit.wet
        if (englishPaperInkWordVisible(deposit.progress, wet, evaporating) && owner != null) {
            val frame = (owner ?: motions.first()).paperInkFrame(style)
            add(
                ShapedWordBloom.PaperInk(
                    range = range,
                    frame = PaperInk.Frame(
                        progress = deposit.progress,
                        wet = wet,
                        // The leaf keeps its own orange layer for repeats.
                        repeatPass = 0f,
                        repeatTint = 0f,
                        retained = 0f,
                        dryInk = frame.dryInk,
                        wetInk = frame.wetInk,
                        repeatColor = frame.repeatColor,
                        unreadAlpha = frame.unreadAlpha,
                        pooling = frame.pooling,
                        reflection = frame.reflection,
                        softness = frame.softness,
                        leadFade = ENGLISH_PAPER_INK_LEAD_FADE,
                        fadeLeadOnEntry = true,
                        flowing = frame.flowing,
                        evaporation = frame.evaporation,
                    ),
                    fallback = emptyList(),
                ),
            )
        } else if (raw < 0f && !warmedNext) {
            add(ShapedWordBloom.PaperInkWarm(range))
            warmedNext = true
        }
    }
}

/**
 * The tinted layers one word of the leaf's English wears: the orange of a
 * repeat, and the fresh-ink glimmer above it.
 *
 * The leaf used to carry no repeat, and the reason was sound while it lasted:
 * a repeat is a statement about one Arabic word, and the leaf had no way to
 * say which English that was. It has one now ([EnglishWordAlignment]), so the
 * statement can be made — and it is the same statement the scrolling reader
 * and the Arabic leaf make, drawn the same way: a [ShapedWordBloom.ColorReveal]
 * on each word of the chain, on that word's own repeat clock.
 *
 * One bloom per word rather than one over the chain, because the chain's words
 * do not share a clock: the occurrence being spoken sweeps its orange on, the
 * ones before it hold theirs at full, and they release together when the chain
 * completes. The chain is a handful of words, and with the leaf's covers
 * abutting rather than reaching (`coverPad = 0`) the spans tile without
 * double-tinting at the seams.
 *
 * The **glimmer** had never been said here at all. It is a statement about the
 * word being recited, and it needed the same alignment the repeat needed before
 * the leaf could place it; with that in hand it is the layer the scrolling
 * reader and the Arabic leaf already draw, on this leaf's English of the word.
 * See `docs/GLIMMER.md` — the word-side gate is being Active (plus its
 * `glintFadeMs` dry-down), and the theme side is [glintInk] existing at all.
 *
 * The first-pass sheen rides `plainSweepProgress` with the engine's own
 * feather, because that is the wash it is a sheen *on*: this leaf deliberately
 * drops tajweed pacing, which places ink on Arabic letters, and there are none
 * here. A repeat's glimmer rides the repeat wash instead, as it does everywhere
 * else, and takes the terracotta with it.
 *
 * First-pass sheen uses the continuous wash bands, so it cannot light a word
 * beyond their leading edge. Repeat layers use the semantic spans: all of that
 * prefix is already laid, and the tint still names the meaning being repeated.
 */
private fun MutableList<ShapedWordBloom>.addEnglishInkLayerBlooms(
    spans: List<EnglishInkSpan>,
    motions: List<InkMotion>,
    palette: WordInkPalette,
    glintInk: Color?,
    repeatLayers: Boolean,
    washEdge: Float,
    departures: MutableMap<Int, PaperInkDeposit>? = null,
) {
    spans.forEach { owned ->
        val motion = motions.getOrNull(owned.position - 1) ?: return@forEach
        val departing = repeatLayers && motion.paperDeparture?.active == true
        // Negative keys hold semantic tint spans; nonnegative keys hold the
        // first-pass English words. Both maps belong to this paragraph only.
        val key = -owned.range.first - 1
        val old = departures?.get(key).takeIf { departing }
        val laid = if (old != null) 1f else englishInkLayerProgress(owned.range, washEdge)
        if (repeatLayers && !departing && departures != null) {
            departures[key] = PaperInkDeposit(minOf(motion.repeatProgress, laid), 0f, 1f, motion.repeatAlpha, 0f)
        }
        if (!englishInkLayerAllowed(motion.ink.state) && old == null) return@forEach
        val repeatAlpha = if (old != null) old.repeatTint * (motion.paperDeparture?.remaining?.value ?: 0f)
            else motion.repeatAlpha
        val repeating = repeatLayers && repeatAlpha > 0f
        val glinting = glintInk != null && motion.showGlintLayer &&
            motion.glintLayerAlpha > 0f && motion.glintIsRepeat == repeatLayers
        if (!repeating && !glinting) return@forEach
        val span = owned.range
        if (laid <= 0f) return@forEach
        val repeatProgress = old?.progress ?: motion.repeatProgress
        if (repeating) {
            add(
                ShapedWordBloom.ColorReveal(
                    range = span,
                    progress = minOf(repeatProgress, laid),
                    color = palette.repeatInkColor,
                    restingAlpha = 0f,
                    layerAlpha = repeatAlpha,
                    feather = motion.repeatFeather,
                    colorAlpha = InkEngine.tuning.repeatInkAlpha,
                ),
            )
        }
        if (glinting && glintInk != null) {
            val onRepeat = motion.glintIsRepeat
            add(
                ShapedWordBloom.ColorReveal(
                    range = span,
                    progress = minOf(laid, if (onRepeat) {
                        repeatProgress
                    } else {
                        motion.plainSweepProgress.coerceIn(0f, 1f)
                    }),
                    color = motion.glintColor(if (onRepeat) palette.repeatInkColor else glintInk),
                    restingAlpha = 0f,
                    layerAlpha = motion.glintLayerAlpha,
                    feather = if (onRepeat) motion.repeatFeather else null,
                    colorAlpha = motion.glintTintColorAlpha(
                        if (onRepeat) {
                            InkEngine.tuning.repeatInkAlpha
                        } else {
                            InkEngine.tuning.glintTintAlpha
                        },
                    ),
                    glowAlpha = motion.glintGlowColorAlpha(InkEngine.tuning.glintGlowAlpha),
                    glowRadius = InkEngine.tuning.glintGlowRadius,
                    bloomAlpha = motion.glintGlowColorAlpha(InkEngine.tuning.glintBloomAlpha),
                    veilAlpha = motion.glintGlowColorAlpha(InkEngine.tuning.glintVeilAlpha),
                ),
            )
        }
    }
}

private fun cover(range: IntRange, paper: Color, alpha: Float) =
    ShapedWordBloom.UpcomingDim(range = range, paper = paper, coverAlpha = alpha)

/**
 * The three bands a verse's sentence stands in while one of its words is being
 * said: what is behind the voice, the word itself, and what is still to come.
 *
 * [from] and [to] are where that word's English begins and ends as a share of
 * the *fragment* on this leaf, so a verse the book carried over needs no special
 * case: the half the voice is not on comes out with an empty middle band and
 * either all read or all ahead, which is what it is.
 */
internal fun englishWashBands(
    range: IntRange,
    from: Float,
    to: Float,
    unread: Float,
    text: CharSequence = "",
): EnglishWashBands {
    if (range.isEmpty()) {
        return EnglishWashBands(IntRange.EMPTY, IntRange.EMPTY, IntRange.EMPTY, IntRange.EMPTY)
    }
    val length = range.last - range.first + 1
    fun at(fraction: Float) =
        range.first + (fraction.coerceIn(0f, 1f) * length).roundToInt().coerceIn(0, length)
    val opens = englishBandEdge(at(from), range, text)
    val closes = maxOf(englishBandEdge(at(to), range, text), opens)
    val waits = maxOf(englishBandEdge(at(unread), range, text), closes)
    return EnglishWashBands(
        read = range.first until opens,
        saying = opens until closes,
        retained = closes until waits,
        ahead = waits..range.last,
    )
}

/**
 * Moves a band's edge out of the middle of an English word.
 *
 * The alignment's own boundaries are already word ends, and for a verse set
 * whole they arrive here unchanged. A carried verse, or one the reader has
 * asked to have its bracketed asides taken off, is a shorter string than the
 * one the shares were measured against, so the arithmetic can land a character
 * or two inside a word — and with the bands abutting, that edge is a hard cut
 * down the middle of a letter. The nearest gap between words is never far.
 */
private fun englishBandEdge(at: Int, range: IntRange, text: CharSequence): Int {
    if (text.isEmpty() || at <= range.first || at > range.last) return at
    if (!text[at - 1].isLetter() || !text[at].isLetter()) return englishMarkEdge(at, range, text)
    for (step in 1..EnglishBandEdgeReach) {
        val back = at - step
        if (back > range.first && !text[back - 1].isLetter()) return englishMarkEdge(back, range, text)
        val on = at + step
        if (on <= range.last && !text[on].isLetter()) return englishMarkEdge(on + 1, range, text)
    }
    return at
}

/**
 * Moves a band's edge off the marks a word wears, so they take its ink.
 *
 * The alignment's boundaries are *letter* ends, and the translation's brackets
 * and punctuation are not letters: left there, the `]` of "[think]" fell into
 * the next word's band and took the orange of a repeat that was never on it.
 * A run of marks touching a word belongs to that word — trailing marks to the
 * word before them, opening ones to the word after — and the edge goes to the
 * space beside the run. A run that joins two words (`well-known`) has no space
 * to go to, and the edge stays.
 */
private fun englishMarkEdge(at: Int, range: IntRange, text: CharSequence): Int {
    fun isMark(c: Char) = !c.isLetterOrDigit() && !c.isWhitespace()
    if (at <= range.first || at > range.last) return at
    if (!text[at - 1].isWhitespace() && isMark(text[at])) {
        var on = at
        while (on <= range.last && isMark(text[on])) on++
        if (on > range.last || text[on].isWhitespace()) return on
    }
    if (isMark(text[at - 1]) && !text[at].isWhitespace()) {
        var back = at
        while (back > range.first && isMark(text[back - 1])) back--
        if (back == range.first || text[back - 1].isWhitespace()) return back
    }
    return at
}

/** How far to look for a gap between words. Longer than any word worth cutting. */
private const val EnglishBandEdgeReach = 14

/** See [englishWashBands]. */
internal data class EnglishWashBands(
    /** Behind the voice: full ink, or rising out of the page recess. */
    val read: IntRange,
    /** The word being said — the only thing on the leaf that blooms. */
    val saying: IntRange,
    /**
     * Said already, but ahead of where the voice now stands: what a reciter
     * going back over a phrase leaves behind them. It keeps its ink — that is
     * the whole of "ink once laid never lifts" — and is empty whenever the
     * voice is at its own furthest point, which is nearly always.
     */
    val retained: IntRange,
    /** Never yet said: paper. */
    val ahead: IntRange,
)


/**
 * The basmalah, in English, as a printed translation sets it: a display line of
 * its own, in the book's italic, centred under the chapter's panel.
 *
 * It takes the same ink as any other line — washed while it is recited, then
 * retained — because it is recited, and the Arabic leaf's own basmalah is set
 * under the same rule.
 */
@Composable
private fun EnglishBasmalahLine(
    fontSize: TextUnit,
    slotHeight: Dp,
    /** The paper over the line, inside its slot: see [englishBasmalahPx]. */
    topAir: Dp,
    active: Boolean,
    wash: StateFlow<Float?>,
    onClick: () -> Unit,
) {
    val inkState = InkEngine.prefaceState(isActive = active, dimmed = false)
    val lyricInk by animateFloatAsState(
        targetValue = inkState.inkAlpha(),
        animationSpec = if (inkState == InkEngine.State.Active) {
            snap()
        } else {
            tween(InkEngine.tuning.inkFadeMs, easing = FastOutSlowInEasing)
        },
        label = "englishBasmalahInk",
    )
    val washValue = wash.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxWidth()
            .height(slotHeight)
            .quietClickable(onClick = onClick),
        // Held off the head of the slot by its own air, and the rest of the
        // slot is the air under it. See englishBasmalahPx.
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = ENGLISH_BASMALAH,
            style = englishBasmalahStyle(fontSize, LocalQuranTypePalette.current),
            color = MaterialTheme.colorScheme.onBackground,
            // Sized to the measure by englishBasmalahHandPx; held to one line
            // here so a pixel of rounding can never break it across two.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .padding(top = topAir)
                .fillMaxWidth()
                .then(
                    if (active) {
                        Modifier.letterFadeIn(
                            progress = { washValue.value?.coerceIn(0f, 1f) ?: 0f },
                            // The wash runs with the reading, and this line is
                            // read left to right.
                            rtl = false,
                            restingAlpha = InkEngine.State.Upcoming.inkAlpha(),
                            // The prose line's own cap: its four words own even
                            // quarters of the sentence, not the bands their
                            // glyphs cover in the calligraphy.
                            feather = InkEngine.prefaceProseFeather(),
                        )
                    } else {
                        Modifier.graphicsLayer { alpha = lyricInk }
                    },
                ),
        )
    }
}

/**
 * The basmalah's own hand: the book's italic, centred.
 *
 * Subpixel advances keep the measured fit and the drawn line at the same width.
 */
private fun englishBasmalahStyle(fontSize: TextUnit, typePalette: QuranTypePalette) = TextStyle(
    fontFamily = typePalette.serif,
    fontStyle = FontStyle.Italic,
    fontSize = fontSize,
    fontFeatureSettings = typePalette.bookFeatures,
    textMotion = TextMotion.Animated,
    textAlign = TextAlign.Center,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    // Trimmed like the prose, so the line begins at its ascent. Untrimmed, the
    // leading above it reappeared as air under the chapter's panel and put six
    // more pixels below the panel than above it.
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * Fits the italic's size to the column. A second measured pass corrects the
 * initial estimate; fractional caret advances avoid rounding the line's width.
 */
private fun englishBasmalahHandPx(
    handPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
): Float {
    // Shaping still rounds fractions; half a pixel keeps the fitted line inside.
    val targetPx = (measurePx.toInt() - 0.5f).coerceAtLeast(1f)
    var fittedPx = handPx
    repeat(2) {
        val laid = measurer.measure(
            text = AnnotatedString(ENGLISH_BASMALAH),
            style = englishBasmalahStyle(with(density) { fittedPx.toSp() }, typePalette),
            softWrap = false,
            maxLines = 1,
            density = density,
        )
        val width = laid.getHorizontalPosition(ENGLISH_BASMALAH.length, true) -
            laid.getHorizontalPosition(0, true)
        if (width <= 0f) return fittedPx
        fittedPx *= targetPx / width
    }
    return fittedPx
}

/**
 * The basmalah's slot: [englishBasmalahAirPx] of paper over it, the line, and
 * paper under it down to where the chapter's first line begins — so that
 * line's baseline falls one pitch and the same air under the basmalah's. Air
 * over and under alike: the basmalah is a line of its own, set apart from the
 * panel above and the verse below by equal paper.
 *
 * A fixed 0.9 em under the line and nothing over it put the first verse a line
 * and a third below it and left the basmalah crowding the panel. Exactly one
 * pitch read as the chapter's first line of prose, crowded on both sides.
 *
 * Never shorter than the basmalah's own ink, so a tight leading cannot run
 * the first verse's ascenders into its descenders.
 */
private fun englishBasmalahPx(
    handPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
): Float {
    val basmalah = measurer.measure(
        text = AnnotatedString(ENGLISH_BASMALAH),
        style = englishBasmalahStyle(
            with(density) {
                englishBasmalahHandPx(handPx, measurePx, density, measurer, typePalette).toSp()
            },
            typePalette,
        ),
        constraints = Constraints(maxWidth = measurePx.toInt().coerceAtLeast(1)),
        softWrap = false,
        maxLines = 1,
        density = density,
    )
    // Where the first prose line's baseline sits under the top of its block;
    // the block is trimmed, so this is the line's ascent.
    val proseAscentPx = measurer.measure(
        text = AnnotatedString(ENGLISH_LEAF_SPECIMEN),
        style = englishProseStyle(
            with(density) { handPx.toSp() },
            TextUnit.Unspecified,
            typePalette,
        ),
        constraints = Constraints(),
        density = density,
    ).firstBaseline
    val pitchPx = handPx * typePalette.leafLeadingEm
    val airPx = englishBasmalahAirPx(handPx, typePalette)
    return airPx + maxOf(
        basmalah.firstBaseline + pitchPx + airPx - proseAscentPx,
        basmalah.size.height.toFloat(),
    )
}

/** The paper over the basmalah, and the extra under it: a share of the pitch. */
private fun englishBasmalahAirPx(handPx: Float, typePalette: QuranTypePalette): Float =
    handPx * typePalette.leafLeadingEm * EnglishLeafBasmalahAir

/**
 * The basmalah's air either side, in line pitches — like [EnglishLeafPanelAir],
 * so it rides the leading.
 */
private const val EnglishLeafBasmalahAir = 0.25f

/** Saheeh International's rendering — the translation the book is set in. */
private const val ENGLISH_BASMALAH =
    "In the name of Allah, the Entirely Merciful, the Especially Merciful."

/**
 * The book's ruler: the leaf itself, measured.
 *
 * It builds the candidate leaf through exactly the code that draws one —
 * `englishLeaf` and [englishLeafBlockTexts] — lays it out at the book's own
 * hand, leading and measure, and reads off the character its last full line
 * ends on. Nothing here re-implements the leaf, which is the whole point: the
 * leaf closes whitespace, trims, drops the translator's asides when the reader
 * has asked for that, snaps its offsets off the middle of words, and sets a
 * verse's mark only on the run that ends it. A ruler that rebuilt that string
 * itself would drift from it in exactly the places where drift is invisible in
 * a test and obvious on a page.
 *
 * [wellPx] and [measurePx] are the leaf's, so the book repaginates when the
 * leaf changes size — which is what an ebook is.
 */
internal fun englishLeafRuler(
    wellPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
    verseNumberScript: VerseNumberScript,
    translation: (surahId: Int, ayah: Int) -> String,
): EnglishLeafRuler {
    val handPx = englishBookHandPx(wellPx, measurePx, density, measurer, typePalette)
    val pitchPx = handPx * typePalette.leafLeadingEm
    val inkPx = englishLineInkPx(handPx, density, measurer, typePalette)
    val basmalahPx = englishBasmalahPx(handPx, measurePx, density, measurer, typePalette)
    val style = with(density) {
        englishProseStyle(handPx.toSp(), pitchPx.toSp(), typePalette)
    }
    return EnglishLeafRuler { page, runs ->
        val leaf = englishLeaf(page, runs, translation)
        val blocks = englishLeafBlockTexts(
            leaf = leaf,
            openingTokens = emptyMap(),
            alignments = emptyMap(),
            ink = Color.Black,
            gold = Color.Black,
            verseNumberScript = verseNumberScript,
            typePalette = typePalette,
        )
        // What the chapter's panel and basmalah take before a word is set —
        // the block's own figure, not a second guess at it.
        val head = blocks.filterIsInstance<EnglishLeafBlockText.Opening>()
            .sumOf { it.heightPx(pitchPx, inkPx, basmalahPx).toDouble() }
            .toFloat()
        val prose = blocks.filterIsInstance<EnglishLeafBlockText.Prose>().firstOrNull()
        val room = (wellPx - head).coerceAtLeast(1f)
        if (prose == null) {
            EnglishLeafFill(null)
        } else {
            // The platform's own wrapping of the offer: only a fallback now,
            // for a seed or a paragraph the justifier cannot read.
            val laid by lazy(LazyThreadSafetyMode.NONE) {
                measurer.measureEnglishProse(prose.text, style, measurePx, density, setLines = false)
            }
            // The offer's break candidates, read once: every prefix the search
            // below tries is cut from them rather than laid out again.
            val offerText = prose.text.text
            val offerFound = englishRagCandidates(prose.text, style, density, measurer)
            val fontPx = with(density) { style.fontSize.toPx() }
            val measure = measurePx.toInt().toFloat()
            val offerMetrics = offerFound?.let { englishJustifyText(offerText, it.offsets, fontPx) }
            // The whole offer runs to its chapter's end: its last line is the
            // paragraph's own.
            val offerLines = offerFound
                ?.let { englishJustifyModel(it, offerText, measure, fontPx, justifyLast = false)?.size }
                ?: measurer.englishProseLineCount(prose.text, style, measurePx, density, justifyLast = false)
            // How many lines the well holds. A property of the well, the
            // leading and a line's ink — not of any particular text, and
            // deliberately so: it used to be read off the candidate's own line
            // bottoms, which made the target depend on the page being measured
            // rather than on the page being filled.
            val lines = (((room - inkPx) / pitchPx).toInt() + 1).coerceAtLeast(1)
            if (offerLines <= lines) {
                EnglishLeafFill(null)
            } else {
                // Then *find* the cut instead of inferring it.
                //
                // What the cut is: the largest prefix of the offer whose leaf,
                // as the book will draw it, sets no more than `lines` lines.
                // Every earlier version read an offset off the candidate — the
                // offer laid out whole — and trusted the leaf to break its
                // lines in the same places. Mostly it does. Where it does not,
                // the cut lands a word or two inside the last line and the page
                // shows the room, which is the fault that would not go away. No
                // argument makes that trust safe, so it is gone: the leaf is
                // drawn, measured, and the answer searched for.
                //
                // The search runs over the offer's word boundaries — the only
                // places a leaf may break — once for the whole book, written to
                // disk after (EnglishBookCache). Each stop is counted the way
                // the leaf will set it: the justified setting of that prefix,
                // cut from the offer's own word positions rather than measured
                // again, which is exactly what the leaf cut there reads.
                // The offer set whole is an excellent guess at the cut: the
                // search starts where its well's last line ends and walks a
                // word at a time from there.
                val stops = englishLeafCutStops(runs, translation)
                // The justified setting holds more to a line than the
                // platform's wrapping, so the seed is read off the offer as the
                // leaf will set it, carried on: where its `lines`th line ends.
                val heldEnd = offerFound?.let { found ->
                    englishJustifyModel(found, offerText, measure, fontPx, justifyLast = true)
                        ?.getOrNull(lines - 1)?.end?.takeIf { it >= 0 }?.let { found.offsets[it] }
                }
                val seed = englishLeafSeedStop(stops, runs, { laid }, prose, leaf.verses, lines, heldEnd)
                // Fewer lines than the well holds — *including none*. A leaf
                // can measure empty: englishLeaf drops a run whose text comes
                // out blank, which a run that is nothing but a translator's
                // aside does when the reader has asked for those to come off.
                // Reading that as "too long" could settle the walk below the
                // answer.
                // A cut short of the offer's end leaves its last line for the
                // next leaf to carry on, and the leaf draws it as a full line.
                fun exactLines(at: Int): Int = englishLeafLineCount(
                    page, runs, stops[at], translation,
                    verseNumberScript, style, measurePx, density, measurer, typePalette,
                    justifyLast = at < stops.lastIndex,
                )
                // The optimum's line count can dip as a prefix grows. Keep
                // searching past an overflow, but stop once the seen prefix
                // exceeds the offer-wide capacity after maximum shrink.
                val tuning = EnglishJustifyTuning
                val minimumWidthFactor = minOf(
                    1f - tuning.expansion * tuning.letterShrink,
                    tuning.idealSpace * (1f - tuning.shrink),
                )
                val lineFit = measure - 1f
                val maximumOfferWidth = offerMetrics?.let { metrics ->
                    if (minimumWidthFactor > 0f) {
                        val allLetters = metrics.lettersBefore[offerText.length]
                        (lines * lineFit + allLetters * tuning.tracking * tuning.letterShrink * fontPx) /
                            minimumWidthFactor + 1f
                    } else {
                        Float.POSITIVE_INFINITY
                    }
                } ?: Float.POSITIVE_INFINITY
                fun probe(at: Int): Pair<Boolean, Boolean> {
                    val carried = at < stops.lastIndex
                    val cut = englishLeafCutProse(page, runs, stops[at], translation, verseNumberScript, typePalette)
                        ?: return true to true
                    val text = cut.text.text
                    if (offerFound == null || !offerText.startsWith(text) || text.length < 2) {
                        return (exactLines(at) <= lines) to true
                    }
                    val prefix = offerFound.prefix(text.length) ?: return (exactLines(at) <= lines) to true
                    if (offerMetrics == null) return (exactLines(at) <= lines) to true
                    val count = englishJustifyModel(prefix, text, measure, fontPx, carried)
                        ?.size ?: return (exactLines(at) <= lines) to true
                    // This bound gives even this prefix every tracking slot
                    // in the whole offer, so it cannot stop before a later
                    // prefix merely gains more letter-spacing allowance.
                    val canFitLater = prefix.candidates.textEnd <= maximumOfferWidth
                    if (!canFitLater) return false to false
                    return (count <= lines) to true
                }
                // Find the latest fit before the seed if needed, then keep the
                // latest fitting stop as the non-monotone setting is sampled.
                var lo = seed.coerceIn(0, stops.lastIndex)
                while (lo >= 0 && !probe(lo).first) lo--
                var probeAt = seed + 1
                while (probeAt <= stops.lastIndex) {
                    val (fits, canFitLater) = probe(probeAt)
                    if (fits) lo = probeAt
                    if (!canFitLater) break
                    probeAt++
                }
                // Every stop fitted, offer and all. The candidate said the
                // offer overflows and the leaf, drawn, says it does not — they
                // agree almost everywhere, and here they did not. Answer the
                // way an unfilled leaf answers, so the caller offers more:
                // cutting at the offer's end would stop the leaf at a boundary
                // nothing on the page put there.
                if (lo >= stops.lastIndex) EnglishLeafFill(null)
                else EnglishLeafFill(stops[lo.coerceAtLeast(0)])
            }
        }
    }
}


/**
 * Where to start looking: the stop nearest the cut the candidate would have
 * given, found without measuring anything.
 *
 * The candidate is the offer laid out whole. Reading a cut off it was the fault
 * this search exists to remove — but it is wrong by a word or two, not by a
 * page, so as a *starting* point it saves most of the measuring.
 */
private fun englishLeafSeedStop(
    stops: List<EnglishRulerCut>,
    runs: List<EnglishVerseRun>,
    /** The offer laid out whole, read only when [heldEnd] is not known. */
    laid: () -> TextLayoutResult,
    prose: EnglishLeafBlockText.Prose,
    verses: List<EnglishLeafVerse>,
    lines: Int,
    /** Where the justified setting ends the well's last line, when known. */
    heldEnd: Int? = null,
): Int {
    // Where the last line the well holds ends, in the paragraph's own string.
    val at = heldEnd ?: laid().getLineEnd(lines.coerceAtLeast(1) - 1, visibleEnd = true)
    val held = prose.verses.firstOrNull { at <= it.range.last + 1 }
        ?: prose.verses.lastOrNull()
        ?: return stops.lastIndex / 2
    // Named, not counted.
    //
    // Three lists describe the same verses here and none of them is indexed
    // the same way: the offer's runs, the leaf's verses, and the paragraph's.
    // A run whose text lays out blank — a verse that is nothing but a
    // translator's aside, with those turned off — is dropped from the leaf and
    // from the paragraph but is still in the offer, and from there on the
    // positions are off by one. A verse's own number is the same in all three,
    // and a leaf holds each verse once, so the number is what to look it up by.
    val run = runs.indexOfFirst { it.surahId == held.surahId && it.ayah == held.ayah }
    val set = verses.firstOrNull { it.surahId == held.surahId && it.ayah == held.ayah }
    if (run < 0 || set == null) return stops.lastIndex / 2
    val into = (at - held.range.first).coerceIn(0, set.to - set.textFrom)
    val to = set.textFrom + into
    // The stops are in reading order, so the nearest is the last one at or
    // before this verse and offset.
    var best = 0
    for (i in stops.indices) {
        val stop = stops[i]
        val before = stop.runIndex < run || (stop.runIndex == run && stop.to <= to)
        if (before) best = i else break
    }
    return best
}

/**
 * Every place the leaf may break, in order: the word boundaries of the offer.
 *
 * A leaf breaks between words and nowhere else — `englishLeafBreak` sees to
 * that — so these are the only cuts worth measuring. Never the very start
 * of the offer: a leaf that took nothing would never advance.
 */
private fun englishLeafCutStops(
    runs: List<EnglishVerseRun>,
    translation: (Int, Int) -> String,
): List<EnglishRulerCut> {
    val out = ArrayList<EnglishRulerCut>(256)
    runs.forEachIndexed { index, run ->
        val whole = translation(run.surahId, run.ayah)
        var at = whole.indexOf(' ', run.from + 1)
        while (at >= 0 && at < run.to) {
            if (at > run.from) out += EnglishRulerCut(index, at)
            at = whole.indexOf(' ', at + 1)
        }
        if (run.to > run.from) out += EnglishRulerCut(index, run.to)
    }
    if (out.isEmpty()) out += EnglishRulerCut(runs.lastIndex, runs.last().to)
    return out
}

/**
 * How many lines the leaf really sets, cut here — the page the book will draw
 * rather than the candidate it was read off. See [englishLeafRuler].
 */
private fun englishLeafLineCount(
    page: Int,
    runs: List<EnglishVerseRun>,
    cut: EnglishRulerCut,
    translation: (Int, Int) -> String,
    verseNumberScript: VerseNumberScript,
    style: TextStyle,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
    justifyLast: Boolean,
): Int {
    val prose = englishLeafCutProse(page, runs, cut, translation, verseNumberScript, typePalette) ?: return 0
    return measurer.englishProseLineCount(prose.text, style, measurePx, density, justifyLast)
}

/** The leaf's paragraph, cut here, as the ruler builds it. */
private fun englishLeafCutProse(
    page: Int,
    runs: List<EnglishVerseRun>,
    cut: EnglishRulerCut,
    translation: (Int, Int) -> String,
    verseNumberScript: VerseNumberScript,
    typePalette: QuranTypePalette,
): EnglishLeafBlockText.Prose? {
    val kept = runs.subList(0, cut.runIndex + 1).toMutableList()
    kept[kept.lastIndex] = kept.last().let {
        EnglishVerseRun(it.surahId, it.ayah, it.from, cut.to)
    }
    return englishLeafBlockTexts(
        leaf = englishLeaf(page, kept, translation),
        openingTokens = emptyMap(),
        alignments = emptyMap(),
        ink = Color.Black,
        gold = Color.Black,
        verseNumberScript = verseNumberScript,
        typePalette = typePalette,
    ).filterIsInstance<EnglishLeafBlockText.Prose>().firstOrNull()
}

/**
 * Debug only: every leaf of [book] set as the leaf sets it, and a report of how
 * evenly its lines were drawn out — the word space each justified line ends up
 * with, in ems, and how far its letters were widened. The number a justified
 * page lives or dies by is the loosest word space, so the worst lines are
 * listed with their text.
 */
internal fun englishJustifyAudit(
    book: com.beautifulquran.domain.EnglishBook,
    surahsById: Map<Int, Surah>,
    wellPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
    verseNumberScript: VerseNumberScript,
    translation: (surahId: Int, ayah: Int) -> String,
): String {
    val handPx = englishBookHandPx(wellPx, measurePx, density, measurer, typePalette)
    val pitchPx = handPx * typePalette.leafLeadingEm
    val style = with(density) { englishProseStyle(handPx.toSp(), pitchPx.toSp(), typePalette) }
    val spacePx = measurer.measure(AnnotatedString("  "), style, density = density)
        .multiParagraph.getHorizontalPosition(1, true)
    val tracking = style.letterSpacing.value
    val box = englishProseConstraints(measurePx).maxWidth - EnglishJustifyEdgePx
    class Seen(val leaf: Int, val spaceEm: Float, val text: String)
    val seen = ArrayList<Seen>()
    val widths = ArrayList<Float>()
    val jumps = ArrayList<Float>()
    var lines = 0
    var unjustified = 0
    var ragged = 0
    var edgeMisses = 0
    var minHandPx = handPx
    var maxHandPx = handPx
    val overflows = ArrayList<String>()
    synchronized(EnglishRagMemo) { EnglishRagMemo.clear() }
    book.leaves.forEachIndexed { index, bookLeaf ->
        val blocks = englishLeafBlockTexts(
            englishLeaf(bookLeaf.page, bookLeaf.runs, translation),
            emptyMap(), emptyMap(), Color.Black, Color.Black, verseNumberScript, typePalette,
        )
        val justifyLast = (blocks.lastOrNull() as? EnglishLeafBlockText.Prose)?.proseRunsOn(surahsById) == true
        val setting = setEnglishLeaf(blocks, wellPx, measurePx, density, measurer, typePalette, justifyLast)
        minHandPx = minOf(minHandPx, setting.handPx)
        maxHandPx = maxOf(maxHandPx, setting.handPx)
        val heightPx = englishLeafHeightPx(
            blocks, setting.handPx,
            englishBasmalahPx(setting.handPx, measurePx, density, measurer, typePalette),
            setting.leadingEm, measurePx, density, measurer, typePalette, justifyLast,
        )
        if (heightPx > wellPx + 1f) {
            overflows += "leaf ${index + 1} ${bookLeaf.runs.first().key}: " +
                "height=$heightPx well=$wellPx leading=${setting.leadingEm}"
        }
        blocks.forEach { block ->
            if (block !is EnglishLeafBlockText.Prose) return@forEach
            val justifyLast = block === blocks.last() && block.proseRunsOn(surahsById)
            val set = englishSetProse(block.text, style, measurePx, density, measurer, justifyLast)
            val laid = measurer.measure(
                set, style, constraints = englishProseConstraints(measurePx),
                softWrap = set.englishSoftWrap, density = density,
            )
            val drawnOut = set.spanStyles.any { it.item.textGeometricTransform != null || it.item.letterSpacing.let { l -> l.isEm && l.value != tracking } }
            if (!drawnOut && laid.lineCount > 1) ragged++
            var previous = Float.NaN
            for (line in 0 until laid.lineCount) {
                lines++
                val start = laid.getLineStart(line)
                val end = laid.getLineEnd(line, visibleEnd = true)
                val last = line == laid.lineCount - 1
                val gap = set.spanStyles.firstOrNull {
                    it.start in start until end && set.text[it.start].isEnglishJustifyGap() &&
                        it.item.letterSpacing.isEm && it.item.letterSpacing.value != tracking
                }
                if (gap == null) {
                    if (!last || justifyLast) unjustified++
                    previous = Float.NaN
                    continue
                }
                if (kotlin.math.abs(box - laid.getLineRight(line)) > 1f) edgeMisses++
                val scale = set.spanStyles.firstOrNull {
                    it.start == start && it.item.textGeometricTransform != null
                }?.item?.textGeometricTransform?.scaleX ?: 1f
                val em = (spacePx / handPx + gap.item.letterSpacing.value - tracking) * scale
                seen += Seen(index, em, set.text.substring(start, end))
                widths += kotlin.math.abs(scale - 1f)
                if (!previous.isNaN()) jumps += kotlin.math.abs(em - previous)
                previous = em
            }
        }
    }
    fun List<Float>.q(p: Double) = if (isEmpty()) 0f else sorted()[((size - 1) * p).toInt()]
    val spaces = seen.map { it.spaceEm }
    return buildString {
        appendLine(
            "leaves=${book.leaves.size} lines=$lines justified=${seen.size} unjustified=$unjustified " +
                "raggedParagraphs=$ragged edgeMisses=$edgeMisses",
        )
        appendLine("book font px=$handPx leaf font min=$minHandPx max=$maxHandPx overflows=${overflows.size}")
        overflows.forEach { appendLine("  overflow: $it") }
        appendLine(
            "word space em: p10=%.3f p50=%.3f p90=%.3f p99=%.3f max=%.3f; over 0.4em: %d, over 0.5em: %d".format(
                spaces.q(0.10), spaces.q(0.50), spaces.q(0.90), spaces.q(0.99), spaces.maxOrNull() ?: 0f,
                spaces.count { it > 0.4f }, spaces.count { it > 0.5f },
            ),
        )
        appendLine("line-to-line space change em: p50=%.3f p90=%.3f".format(jumps.q(0.5), jumps.q(0.9)))
        appendLine("|glyph width change|: p50=%.4f p90=%.4f".format(widths.q(0.5), widths.q(0.9)))
        appendLine("loosest lines:")
        seen.sortedByDescending { it.spaceEm }.take(20).forEach {
            appendLine("  leaf ${it.leaf + 1} %.3fem | ${it.text}".format(it.spaceEm))
        }
    }
}
