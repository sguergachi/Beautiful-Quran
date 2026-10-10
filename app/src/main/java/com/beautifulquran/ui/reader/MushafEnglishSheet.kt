package com.beautifulquran.ui.reader

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.beautifulquran.ui.theme.leafLeadingEm
import com.beautifulquran.ui.theme.quietSuppliedBrackets
import com.beautifulquran.ui.theme.QuranTypePalette
import com.beautifulquran.ui.theme.LocalQuranTypePalette
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.englishLeaf
import com.beautifulquran.domain.englishLeafBreak
import com.beautifulquran.domain.englishLeafFittedLeadingEm
import com.beautifulquran.domain.englishLeafOverflowHandPx
import com.beautifulquran.domain.englishLeafHandPx
import com.beautifulquran.domain.EnglishLeafFill
import com.beautifulquran.domain.EnglishLeafRuler
import com.beautifulquran.domain.EnglishLeafVerse
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
import kotlinx.coroutines.flow.StateFlow

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
    val leafWordEnds = remember(ayahsOnPage, alignments) {
        buildMap {
            ayahsOnPage.forEach { ayah ->
                alignments.of(ayah.number)?.let { put(ayah.surahId to ayah.number, it) }
            }
        }
    }
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
            leafWordEnds,
            typePalette,
        ) {
            englishLeafBlockTexts(
                leaf,
                leafTokens,
                leafWordEnds,
                palette.fullInkColor,
                gold,
                verseNumberScript,
                typePalette,
            )
        }
        val setting = remember(blocks, wellPx, measurePx, density, measurer, typePalette) {
            setEnglishLeaf(blocks, wellPx, measurePx, density, measurer, typePalette)
        }
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
                        fontSize = fontSize,
                        lineHeight = pitch,
                        measurePx = measurePx,
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
 * Air under the basmalah, so the chapter's first verse does not run into it.
 *
 * Under it, all of it — the line sits at the head of its slot. Centred there,
 * half of this fell *above* the basmalah instead, where it landed under the
 * chapter's panel and made the space below the panel five times the space
 * above it. The panel's own air is what stands it off its neighbours, on both
 * sides and equally; this is the separation between a display line and the
 * body text that follows it.
 */
private const val EnglishLeafBasmalahAirEm = 0.9f

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
    /**
     * The share of the verse a point this many characters into [range] stands
     * at — what a tap seeks by. Identity-ish for a verse set whole.
     */
    val verseFractionAt: (Int) -> Float = { 0f },
    /**
     * Where each Arabic word of the verse ends inside the *whole* translation,
     * as a share of it (`EnglishWordAlignment`). Null when the verse could not
     * be aligned, and the wash divides the sentence evenly instead.
     */
    val wordEnds: FloatArray? = null,
)

/**
 * Builds every block's text once. The ranges are recorded as the string is
 * assembled, which is the only moment they are knowable.
 */
private fun englishLeafBlockTexts(
    leaf: EnglishLeaf,
    openingTokens: Map<Pair<Int, Int>, MushafToken>,
    wordEnds: Map<Pair<Int, Int>, FloatArray>,
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
                            bookFontFamily = typePalette.serif,
                            // The leaf is set left to right whichever digits the
                            // reader has chosen, so the cups are always the LTR
                            // pair.
                            ltr = true,
                        )
                        markStart until length
                    } else {
                        IntRange.EMPTY
                    }
                    verses += EnglishProseVerse(
                        surahId = verse.surahId,
                        ayah = verse.ayah,
                        range = range,
                        markRange = markRange,
                        token = openingTokens[verse.surahId to verse.ayah],
                        fragmentProgress = verse::fragmentInkProgress,
                        verseFractionAt = { at ->
                            verse.verseFractionAt(at, verse.text.length)
                        },
                        wordEnds = wordEnds[verse.surahId to verse.ayah],
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

/**
 * Sets the leaf: the book's hand and the book's leading, then a measurement to
 * make sure the block really does fit the well.
 *
 * Neither number is this leaf's to choose. The hand is the book's, cut for the
 * heaviest leaf in it; the leading is the book's, one figure for all 604
 * leaves (`EnglishLeafFit.kt`). A page that respaced its lines to fill itself
 * would be a page a reader sees change as they turn onto it, which is a worse
 * fault than the white this leaves at the foot.
 *
 * So the only thing solved here is the guarantee: the leaf is measured as it
 * will be drawn, and the leading closes — on that leaf alone, and only far
 * enough — if the estimate the hand was cut from turns out to be wrong here.
 */
private fun setEnglishLeaf(
    blocks: List<EnglishLeafBlockText>,
    wellPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
): EnglishLeafSetting {
    val requestedLeading = typePalette.leafLeadingEm * typePalette.tuning.book.leading
    var handPx = englishBookHandPx(wellPx, measurePx, density, measurer, typePalette)
    // Three passes at most, and all but one leaf in the book settles on the
    // first: the hand is the book's, the leading is the book's, and the leaf
    // fits. The rest is the rescue — close the leading to its floor, and if the
    // block still stands past the foot, give up a little of the hand. See
    // englishLeafOverflowHandPx for why that order and not the other.
    repeat(3) { pass ->
        val basmalahPx = englishBasmalahPx(handPx, measurePx, density, measurer, typePalette)
        val pitches = englishLeafPitches(blocks, handPx, measurePx, density, measurer, typePalette)
        val stands = englishLeafHeightPx(
            blocks,
            handPx,
            basmalahPx,
            requestedLeading,
            measurePx,
            density,
            measurer,
            typePalette,
        )
        val leadingEm = englishLeafFittedLeadingEm(
            leadingEm = requestedLeading,
            measuredHeightPx = stands,
            wellHeightPx = wellPx,
            pitchesPx = (pitches * handPx).coerceAtLeast(1f),
        )
        // What the block stands at the leading it will actually be drawn on.
        // Only a *closed* leading counts here. Carding the leading out fills a
        // short leaf to its foot, which raises the block to about the well's
        // own height — and feeding that back into the overflow test reads a
        // filled leaf as an overflowing one and gives up hand for it. The leaf
        // then sets smaller type than the ruler paginated it for and comes out
        // a line short, its last line half empty. The rescue is for leaves that
        // run past the foot; a leaf carded to reach the foot has not.
        val closed = stands -
            (requestedLeading - minOf(leadingEm, requestedLeading)) *
            pitches * handPx
        val next = englishLeafOverflowHandPx(handPx, closed, wellPx)
        if (next >= handPx || pass == 2) {
            return EnglishLeafSetting(
                handPx = handPx,
                lineInkPx = englishLineInkPx(handPx, density, measurer, typePalette),
                leadingEm = leadingEm,
            )
        }
        handPx = next
    }
    error("unreachable")
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
    val block = AnnotatedString(englishLeafReferenceBlock())
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
    return handPx * typePalette.tuning.book.size
}

/**
 * The book's hand: ragged right, with whole words.
 *
 * The rag chooses word-space breaks that share deep holes between neighbouring
 * lines, while keeping the minimum line count and preferring fuller lines on
 * ties. It leaves ordinary shortfalls alone, so the edge still moves rather
 * than forming the second margin Balanced produced. See §13.5 of
 * `docs/QURAN_TYPOGRAPHY.md` for the measured tradeoffs.
 *
 * Hyphenation is off for both the ruler and the page. The chosen breaks replace
 * spaces in place, so the wash, paper covers and taps keep their character
 * offsets. Simple wrapping is the fallback for paragraphs the rag cannot set.
 */
internal fun englishProseStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    typePalette: QuranTypePalette,
) = TextStyle(
    fontFamily = typePalette.serif,
    fontSize = fontSize,
    // Tighter prose fits whole words without reducing the book's type size.
    letterSpacing = (-0.025f + typePalette.tuning.book.tracking).em,
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
    // The leaf no longer pays it: englishRaggedProse breaks its lines first,
    // greedy wherever greedy was fine and not where it left a hole, and writes
    // the breaks into the text. This is the fallback for a paragraph it
    // declines.
    lineBreak = LineBreak.Paragraph.copy(strategy = LineBreak.Strategy.Simple),
    hyphens = Hyphens.None,
    // The book face's refinements: kerning and ligatures on, old-style figures
    // so the prose (and its brackets and quotes) sets with an even colour —
    // the same features the web leaf and every other English surface set.
    fontFeatureSettings = typePalette.tuning.book.featureSettings,
    textMotion = if (typePalette.profile == com.beautifulquran.ui.theme.QuranTypeProfile.TIMELESS) {
        TextMotion.Animated
    } else TextMotion.Static,
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
 * A paragraph on the leaf's measure. Whole-word greedy wrapping and
 * [EnglishRag] have the same minimum line count; pagination can skip choosing
 * the rag and measuring every word position for thousands of candidate leaves.
 */
private fun TextMeasurer.measureEnglishProse(
    text: AnnotatedString,
    style: TextStyle,
    measurePx: Float,
    density: Density,
    chooseRag: Boolean = true,
): TextLayoutResult = measure(
    text = if (chooseRag) englishRaggedProse(text, style, measurePx, density, this) else text,
    style = style,
    constraints = englishProseConstraints(measurePx),
    density = density,
)

/** Leave one pixel for rounding when the chosen lines are drawn at the full measure. */
private fun englishProseConstraints(measurePx: Float) =
    Constraints(maxWidth = (measurePx.toInt() - 1).coerceAtLeast(1))

/**
 * The paragraph with its lines broken where [EnglishRag] sets them.
 *
 * Each word space the rag ends a line at becomes a line break *in place*,
 * one character for one, so every offset the wash, the paper covers
 * and the taps hold still names the same letter. The platform then only sets
 * lines that already fit, and breaks nothing itself.
 *
 * The positions come from the paragraph set on the measure, chained line to
 * line into one unbroken coordinate, and the answer is remembered: the leaf is
 * measured several times at the same hand while the leaf is fitted.
 */
internal fun englishRaggedProse(
    text: AnnotatedString,
    style: TextStyle,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
): AnnotatedString = text.withEnglishBreaks(
    englishRagBreaks(text, style, measurePx, density, measurer),
)

private fun AnnotatedString.withEnglishBreaks(breaks: IntArray): AnnotatedString {
    if (breaks.isEmpty()) return this
    val chars = text.toCharArray()
    breaks.forEach { at -> chars[at] = '\n' }
    return AnnotatedString(String(chars), spanStyles, paragraphStyles)
}

private fun englishRagBreaks(
    text: AnnotatedString,
    style: TextStyle,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
): IntArray {
    val source = text.text
    val n = source.length
    if (n < 2 || measurePx <= 1f) return NoBreaks
    val key = EnglishRagKey(text, style, measurePx, density.density, density.fontScale)
    synchronized(EnglishRagMemo) { EnglishRagMemo[key]?.let { return it } }

    // Where every offset would sit on one unbroken line. Read off the
    // paragraph set on the measure, not off an unbroken layout: a position on
    // a platform line costs a walk from that line's start, so on one line of
    // a whole leaf reading every candidate was quadratic, and the book took
    // minutes to paginate. Line by line it is a walk of one line each, and the
    // lines are chained by their advances into the one coordinate.
    val laid = measurer.measure(
        text = text,
        style = style,
        constraints = englishProseConstraints(measurePx),
        density = density,
    )
    val spacePx = measurer.measure(AnnotatedString("  "), style, density = density)
        .multiParagraph.getHorizontalPosition(1, true)
    val base = FloatArray(laid.lineCount)
    for (line in 1 until laid.lineCount) {
        // The advance of the line before, as if it had not broken: through its
        // last character, which is a space (trailing, so not reliably boxed),
        // or anything else (its own box).
        val last = laid.getLineEnd(line - 1) - 1
        val advance = when (source[last]) {
            ' ' -> laid.getHorizontalPosition(last, true) + spacePx
            else -> laid.getBoundingBox(last).right
        }
        base[line] = base[line - 1] + advance
    }
    fun x(offset: Int): Float {
        val line = laid.getLineForOffset(offset)
        return base[line] + laid.getHorizontalPosition(offset, true)
    }
    val offsets = ArrayList<Int>()
    val ends = ArrayList<Float>()
    val starts = ArrayList<Float>()
    for (i in 1 until n - 1) {
        if (source[i] == ' ') {
            offsets += i
            ends += x(i)
            starts += x(i + 1)
        }
    }
    // One left-to-right line, read left to right. Anything else (a bidi run
    // the boxes do not order) is not this paragraph, and the platform breaks it.
    for (k in 1 until ends.size) {
        if (ends[k] < ends[k - 1] - 0.5f) return memo(key, NoBreaks)
    }
    val chosen = EnglishRag.breaks(
        EnglishRag.Candidates(
            contentEnd = ends.toFloatArray(),
            nextStart = starts.toFloatArray(),
            textEnd = x(n),
        ),
        measurePx = measurePx.toInt().toFloat(),
        emPx = with(density) { style.fontSize.toPx() },
    ) ?: return memo(key, NoBreaks)
    val set = IntArray(chosen.size) { offsets[chosen[it]] }
    // Tight tracking can round a chosen line wider than its chained positions.
    // Keep the ruler's line count rather than spending another line on the page.
    val shaped = measurer.measure(
        text = text.withEnglishBreaks(set), style = style,
        constraints = englishProseConstraints(measurePx), density = density,
    )
    if (shaped.lineCount != laid.lineCount) return memo(key, NoBreaks)
    return memo(key, set)
}

private data class EnglishRagKey(
    val text: AnnotatedString,
    val style: TextStyle,
    val measurePx: Float,
    val density: Float,
    val fontScale: Float,
)

private val NoBreaks = IntArray(0)

/** Recent paragraphs' breaks: fitting and drawing ask for the same text repeatedly. */
private val EnglishRagMemo = object : LinkedHashMap<EnglishRagKey, IntArray>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EnglishRagKey, IntArray>?) =
        size > 96
}

private fun memo(key: EnglishRagKey, breaks: IntArray): IntArray {
    synchronized(EnglishRagMemo) { EnglishRagMemo[key] = breaks }
    return breaks
}

/** One paragraph of the leaf, with the reciter's ink over it. */
@Composable
private fun EnglishProseBlock(
    block: EnglishLeafBlockText.Prose,
    packs: Map<Pair<Int, Int>, AyahInkPack>,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    /** The measure the ruler paginated against, which the lines are broken to. */
    measurePx: Float,
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
    // The rag keeps the ruler's line count and block.text's offsets, so the
    // verses' ranges still name the same words.
    val density = LocalDensity.current
    val set = remember(block.text, style, measurePx, density) {
        englishRaggedProse(block.text, style, measurePx, density, measurer)
    }
    Text(
        text = set,
        style = style,
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
                fun at(point: Offset, act: (Int, Int, Float) -> Unit) {
                    val layout = layoutResult ?: return
                    val verse = block.verses
                        .firstOrNull { layout.rangeContains(point, it.range, hitSlopPx) }
                        ?: return
                    val offset = layout.getOffsetForPosition(point) - verse.range.first
                    act(verse.surahId, verse.ayah, verse.verseFractionAt(offset.coerceAtLeast(0)))
                }
                detectTapGestures(
                    onLongPress = { press -> at(press, onVerseLongPress) },
                    onTap = { tap -> at(tap, onVerseSeek) },
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
 * With an alignment the leaf can copy it exactly, because the states are
 * contiguous: everything before the word being said is read, everything after
 * is not. So it is three ranges rather than one bloom per word
 * ([englishWashBands]) — the same picture, at three blooms instead of fifty.
 *
 * The recess cover rides the read band, which is what the Arabic leaf does with
 * its already-read words: a verse seeked into rises out of the paper over
 * `recessMs` rather than appearing on it. The word being said never carries the
 * cover — it is revealed by its own bloom, exactly as on the Arabic leaf.
 */
private fun englishVerseBlooms(
    verse: EnglishProseVerse,
    pack: AyahInkPack?,
    palette: WordInkPalette,
    /** Fresh-ink sheen; null on themes without it. See [addEnglishInkLayerBlooms]. */
    glintInk: Color?,
    /** The paragraph, so a band's edge can be kept out of the middle of a word. */
    text: CharSequence,
): List<ShapedWordBloom> {
    if (pack == null) return emptyList()
    val paper = palette.paperColor
    val blooms = ArrayList<ShapedWordBloom>(4)
    val cover = pack.recessCover.value.coerceIn(0f, 1f)
    val resting = InkEngine.State.Upcoming.inkAlpha()
    val waiting = maxOf(cover, 1f - resting)
    val motions = pack.motions
    val active = motions.indexOfFirst { it.isActive }
    // Where the words nobody has said yet begin. Not "after the active word":
    // a reciter going back over a phrase leaves everything they already said
    // Recited (InkEngine.wordState holds it to activeWord.highWater), and ink
    // once laid never lifts. Reading the paper cover off the active index put
    // it back over words the voice had already crossed, so a repeat dimmed the
    // phrase it should have been tinting.
    val unread = motions.indexOfFirst { it.ink.state == InkEngine.State.Upcoming }
    // Where each word's English sits on this leaf. Without an alignment the
    // words divide the sentence evenly, which is the proportion the leaf used
    // before it had one.
    val ends = if (motions.isEmpty()) {
        null
    } else {
        verse.wordEnds?.takeIf { it.size == motions.size }
            ?: FloatArray(motions.size) { (it + 1f) / motions.size }
    }
    if (ends == null) {
        // No clock of its own: a leaf the voice is not on, waiting or settled.
        if (cover > 0f) blooms += cover(verse.range, paper, cover)
    } else {
        fun opensAt(word: Int) = verse.fragmentProgress(if (word <= 0) 0f else ends[word - 1])
        val bands = englishWashBands(
            range = verse.range,
            from = if (active < 0) 0f else opensAt(active),
            to = if (active < 0) 0f else verse.fragmentProgress(ends[active]),
            unread = if (unread < 0) 1f else opensAt(unread),
            text = text,
        )
        if (cover > 0f) {
            if (!bands.read.isEmpty()) blooms += cover(bands.read, paper, cover)
            if (!bands.retained.isEmpty()) blooms += cover(bands.retained, paper, cover)
        }
        // A word the reciter has gone back over takes the orange instead of
        // the first-pass wash, exactly as it does everywhere else — running
        // both over the same span would wash it white and tint it at once.
        if (active >= 0 && !bands.saying.isEmpty() && !motions[active].repeat) {
            blooms += ShapedWordBloom.InkReveal(
                range = bands.saying,
                // The linear clock, never the tajweed-paced one: pacing places
                // ink on Arabic letters, and there are none here.
                progress = motions[active].plainSweepProgress.coerceIn(0f, 1f),
                paper = paper,
                restingAlpha = resting,
                // No override: the bloom's range *is* a word now, so the
                // engine's own figure is the right edge for it.
                feather = null,
            )
        }
        if (!bands.ahead.isEmpty()) blooms += cover(bands.ahead, paper, waiting)
    }
    // The mark's cover is paper, so it goes down before any ink — left after
    // the ink layers it landed on the last word's glint halo and cut it.
    val markCover = (1f - pack.markAlpha.value).coerceIn(0f, 1f)
    if (markCover > 0f && !verse.markRange.isEmpty()) {
        blooms += cover(verse.markRange, paper, markCover)
    }
    if (ends != null) {
        blooms.addEnglishInkLayerBlooms(verse, motions, ends, palette, glintInk, text)
    }
    return blooms
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
 * Both layers are drawn per word in one pass so a word that carries both pays
 * for its span once, and so the sheen stays above the orange rather than under
 * it.
 */
private fun MutableList<ShapedWordBloom>.addEnglishInkLayerBlooms(
    verse: EnglishProseVerse,
    motions: List<InkMotion>,
    ends: FloatArray,
    palette: WordInkPalette,
    glintInk: Color?,
    text: CharSequence,
) {
    motions.forEachIndexed { index, motion ->
        val repeating = motion.repeatAlpha > 0f
        val glinting = glintInk != null && motion.showGlintLayer &&
            motion.glintLayerAlpha > 0f
        if (!repeating && !glinting) return@forEachIndexed
        val span = englishWashBands(
            range = verse.range,
            from = verse.fragmentProgress(if (index == 0) 0f else ends[index - 1]),
            to = verse.fragmentProgress(ends[index]),
            unread = 1f,
            text = text,
        ).saying
        if (span.isEmpty()) return@forEachIndexed
        if (repeating) {
            add(
                ShapedWordBloom.ColorReveal(
                    range = span,
                    progress = motion.repeatProgress,
                    color = palette.repeatInkColor,
                    restingAlpha = 0f,
                    layerAlpha = motion.repeatAlpha,
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
                    progress = if (onRepeat) {
                        motion.repeatProgress
                    } else {
                        motion.plainSweepProgress.coerceIn(0f, 1f)
                    },
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
        // At the head of the slot: every bit of the slot's air belongs below
        // the line, between it and the chapter's first verse. See
        // EnglishLeafBasmalahAirEm.
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
    letterSpacing = typePalette.tuning.bookItalic.tracking.em,
    fontFeatureSettings = typePalette.tuning.bookItalic.featureSettings,
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

/** What the basmalah stands at, plus the air a display line takes under it. */
private fun englishBasmalahPx(
    handPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
    typePalette: QuranTypePalette,
): Float = measurer.measure(
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
).size.height + handPx * EnglishLeafBasmalahAirEm

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
    val pitchPx = handPx * typePalette.leafLeadingEm * typePalette.tuning.book.leading
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
            wordEnds = emptyMap(),
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
            val laid = measurer.measureEnglishProse(
                prose.text, style, measurePx, density, chooseRag = false,
            )
            // How many lines the well holds. A property of the well, the
            // leading and a line's ink — not of any particular text, and
            // deliberately so: it used to be read off the candidate's own line
            // bottoms, which made the target depend on the page being measured
            // rather than on the page being filled.
            val lines = (((room - inkPx) / pitchPx).toInt() + 1).coerceAtLeast(1)
            if (laid.lineCount <= lines) {
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
                // places a leaf may break — and the leaf's line count rises
                // along them, so it bisects. Nine measurements a leaf, once for
                // the whole book, written to disk after (EnglishBookCache).
                // It is the one formulation that cannot come out a word short,
                // because a word short is a cut the search steps past.
                // The candidate is a poor authority and an excellent guess:
                // where the leaf and it agree — almost everywhere — its answer
                // *is* the answer, and where they differ it is a word or two
                // out. So the search starts there and grows outwards by
                // doubling until it has straddled the truth, then bisects what
                // is left. Same answer as searching the whole offer, in three
                // or four measurements instead of nine.
                val stops = englishLeafCutStops(runs, translation)
                val seed = englishLeafSeedStop(stops, runs, laid, prose, leaf.verses, lines)
                // Fewer lines than the well holds — *including none*. A leaf
                // can measure empty: englishLeaf drops a run whose text comes
                // out blank, which a run that is nothing but a translator's
                // aside does when the reader has asked for those to come off.
                // Reading that as "too long" would break the monotonicity the
                // search rests on and could settle it below the answer.
                fun fits(at: Int): Boolean = englishLeafLineCount(
                    page, runs, stops[at], translation,
                    verseNumberScript, style, measurePx, density, measurer, typePalette,
                ) <= lines
                // Straddle: `lo` fits, `hi` does not, and the answer is the
                // last stop before `hi`.
                var lo: Int
                var hi: Int
                if (fits(seed)) {
                    lo = seed
                    var step = 1
                    while (true) {
                        val probe = seed + step
                        if (probe > stops.lastIndex) { hi = stops.size; break }
                        if (fits(probe)) { lo = probe; step *= 2 } else { hi = probe; break }
                    }
                } else {
                    hi = seed
                    var step = 1
                    lo = -1
                    while (true) {
                        val probe = seed - step
                        if (probe < 0) break
                        if (fits(probe)) { lo = probe; break }
                        hi = probe
                        step *= 2
                    }
                }
                while (hi - lo > 1) {
                    val mid = (lo + hi) / 2
                    if (fits(mid)) lo = mid else hi = mid
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
    laid: TextLayoutResult,
    prose: EnglishLeafBlockText.Prose,
    verses: List<EnglishLeafVerse>,
    lines: Int,
): Int {
    // Where the last line the well holds ends, in the paragraph's own string.
    val at = laid.getLineEnd(lines.coerceAtLeast(1) - 1, visibleEnd = true)
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
 * that — so these are the only cuts worth measuring, and the leaf's line count
 * rises along them, which is what lets the search bisect. Never the very start
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
): Int {
    val kept = runs.subList(0, cut.runIndex + 1).toMutableList()
    kept[kept.lastIndex] = kept.last().let {
        EnglishVerseRun(it.surahId, it.ayah, it.from, cut.to)
    }
    val prose = englishLeafBlockTexts(
        leaf = englishLeaf(page, kept, translation),
        openingTokens = emptyMap(),
        wordEnds = emptyMap(),
        ink = Color.Black,
        gold = Color.Black,
        verseNumberScript = verseNumberScript,
        typePalette = typePalette,
    ).filterIsInstance<EnglishLeafBlockText.Prose>().firstOrNull() ?: return 0
    return measurer.measureEnglishProse(
        prose.text, style, measurePx, density, chooseRag = false,
    ).lineCount
}
