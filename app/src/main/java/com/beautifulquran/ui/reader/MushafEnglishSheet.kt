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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
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
import com.beautifulquran.domain.ENGLISH_LEAF_LEADING_EM
import com.beautifulquran.domain.EnglishLeafFill
import com.beautifulquran.domain.EnglishLeafRuler
import com.beautifulquran.domain.EnglishLeafVerse
import com.beautifulquran.domain.EnglishRulerCut
import com.beautifulquran.domain.mushafLeafBands
import com.beautifulquran.domain.quranWordKey
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.SerifFontFamily
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
    hideParentheticals: Boolean,
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
    /** The leaf's fore-edge, shared with the running head and the folio. */
    foreEdge: Dp,
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
    onBasmalahClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            packsState = packsState,
        )
    }
    val leaf = remember(page.page, leafRuns, leafText, hideParentheticals) {
        val text = leafText ?: return@remember null
        englishLeaf(page.page, leafRuns, hideParentheticals) { surahId, ayah ->
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
        ) {
            englishLeafBlockTexts(
                leaf,
                leafTokens,
                leafWordEnds,
                palette.fullInkColor,
                gold,
                verseNumberScript,
            )
        }
        val setting = remember(blocks, wellPx, measurePx, density, measurer) {
            setEnglishLeaf(blocks, wellPx, measurePx, density, measurer)
        }
        val fontSize = with(density) { setting.handPx.toSp() }
        val pitchDp = with(density) { (setting.handPx * setting.leadingEm).toDp() }
        val basmalahDp = remember(setting.handPx, measurePx, density, measurer) {
            with(density) {
                englishBasmalahPx(setting.handPx, measurePx, density, measurer).toDp()
            }
        }
        val basmalahFontSize = remember(setting.handPx, measurePx, density, measurer) {
            with(density) {
                englishBasmalahHandPx(setting.handPx, measurePx, density, measurer).toSp()
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
                .padding(horizontal = foreEdge),
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
                        liveInk = liveInk,
                        onVerseSeek = onVerseSeek,
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
    val measurePx = (paperWidthPx - englishLeafForeEdge(paperWidthPx.toDp()).roundToPx() * 2)
        .toFloat().coerceAtLeast(1f)
    floatArrayOf(wellPx, measurePx)
}

internal fun englishLeafForeEdge(leafWidth: Dp): Dp =
    (leafWidth * EnglishLeafForeEdgeFraction).coerceAtLeast(MushafEdgeGutter)

private const val EnglishLeafForeEdgeFraction = 0.055f

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
     * Where the reciter is inside *this* fragment, given where they are inside
     * the verse. Identity for a verse the leaf sets whole.
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
                    val range = start until length
                    // Only the fragment that ends the verse carries its mark: a
                    // carried sentence is numbered where it finishes, as a
                    // paragraph carried over a page is punctuated where it ends.
                    val markRange = if (verse.endsVerse) {
                        append(" ")
                        val markStart = length
                        appendAyahNumberMark(
                            number = verse.ayah,
                            useArabicIndicDigits =
                                verseNumberScript == VerseNumberScript.ARABIC,
                            style = SpanStyle(color = gold, fontSize = EnglishLeafMarkType.em),
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
                        fragmentProgress = verse::fragmentProgress,
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
): EnglishLeafSetting {
    var handPx = englishBookHandPx(wellPx, measurePx, density, measurer)
    // Three passes at most, and all but one leaf in the book settles on the
    // first: the hand is the book's, the leading is the book's, and the leaf
    // fits. The rest is the rescue — close the leading to its floor, and if the
    // block still stands past the foot, give up a little of the hand. See
    // englishLeafOverflowHandPx for why that order and not the other.
    repeat(3) { pass ->
        val basmalahPx = englishBasmalahPx(handPx, measurePx, density, measurer)
        val pitches = englishLeafPitches(blocks, handPx, measurePx, density, measurer)
        val stands = englishLeafHeightPx(
            blocks,
            handPx,
            basmalahPx,
            ENGLISH_LEAF_LEADING_EM,
            measurePx,
            density,
            measurer,
        )
        val leadingEm = englishLeafFittedLeadingEm(
            leadingEm = ENGLISH_LEAF_LEADING_EM,
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
            (ENGLISH_LEAF_LEADING_EM - minOf(leadingEm, ENGLISH_LEAF_LEADING_EM)) *
            pitches * handPx
        val next = englishLeafOverflowHandPx(handPx, closed, wellPx)
        if (next >= handPx || pass == 2) {
            return EnglishLeafSetting(
                handPx = handPx,
                lineInkPx = englishLineInkPx(handPx, density, measurer),
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
): Float {
    var pitches = 0f
    blocks.forEach { block ->
        when (block) {
            is EnglishLeafBlockText.Opening -> pitches += EnglishLeafPanelAir * 2f
            is EnglishLeafBlockText.Prose -> {
                val lines = measurer
                    .measure(
                        text = block.text,
                        style = englishProseStyle(
                            with(density) { handPx.toSp() },
                            TextUnit.Unspecified,
                        ),
                        constraints = Constraints(maxWidth = measurePx.toInt().coerceAtLeast(1)),
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
): Float = measurer.measure(
    text = AnnotatedString(ENGLISH_LEAF_SPECIMEN),
    style = englishProseStyle(with(density) { handPx.toSp() }, TextUnit.Unspecified),
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
): Float {
    val inkPx = englishLineInkPx(handPx, density, measurer)
    val style = englishProseStyle(
        with(density) { handPx.toSp() },
        with(density) { (handPx * leadingEm).toSp() },
    )
    return blocks.sumOf { block ->
        when (block) {
            is EnglishLeafBlockText.Opening -> block
                .heightPx(handPx * leadingEm, inkPx, basmalahPx)
                .toDouble()
            is EnglishLeafBlockText.Prose -> measurer
                .measure(
                    text = block.text,
                    style = style,
                    constraints = Constraints(maxWidth = measurePx.toInt().coerceAtLeast(1)),
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
): Float {
    val block = AnnotatedString(englishLeafReferenceBlock())
    var handPx = ENGLISH_LEAF_PROBE_FONT_PX
    repeat(2) {
        val stands = measurer.measure(
            text = block,
            style = englishProseStyle(
                with(density) { handPx.toSp() },
                with(density) { (handPx * ENGLISH_LEAF_LEADING_EM).toSp() },
            ),
            constraints = Constraints(maxWidth = measurePx.toInt().coerceAtLeast(1)),
            density = density,
        ).size.height.toFloat()
        handPx = englishLeafHandPx(handPx, stands, wellPx)
    }
    return handPx
}

/**
 * The book's hand: EB Garamond, ragged right, unhyphenated.
 *
 * **Ragged, not justified.** The mushaf's own rule is that every full line
 * reaches both margins (`QURAN_TYPOGRAPHY.md` §3) — but that is a rule about
 * Arabic, which fills a line by the letterform, and it is the calligrapher's
 * art. Latin has only the word space to fill with, and on a measure of about
 * fifty characters that is not enough of a lever: the spaces open unevenly,
 * the same line's colour changes from one page to the next, and the reader
 * pays for a straight right edge with rivers of white running down the page.
 * An even rag is the more readable page, and on a phone it is not close.
 *
 * `LineBreak.Paragraph` stays: it breaks the whole block at once rather than
 * greedily line by line, which is what makes the rag *even* rather than merely
 * ragged — the difference between a right edge that undulates and one that
 * lurches.
 *
 * **Not hyphenated, and this is load-bearing.** Hyphenation breaks a *word*
 * across two lines, and `ShapedWordBloom.ColorReveal` takes the union bounds of
 * a range's glyph path — so a tinted wash over a broken word would sweep the
 * width of the whole line. `InkReveal` handles a multi-line range correctly (it
 * advances one wash across the fragments in order, which is what this page's
 * verse wash needs), but the tinted layers do not. Anyone turning hyphens on
 * must fix ColorReveal the same way first. Ragged setting needs them far less
 * anyway — the rag absorbs the long word that justification would have had to
 * stretch a line around.
 */
private fun englishProseStyle(fontSize: TextUnit, lineHeight: TextUnit) = TextStyle(
    fontFamily = SerifFontFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    textAlign = TextAlign.Start,
    lineBreak = LineBreak.Paragraph,
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

/** One paragraph of the leaf, with the reciter's ink over it. */
@Composable
private fun EnglishProseBlock(
    block: EnglishLeafBlockText.Prose,
    packs: Map<Pair<Int, Int>, AyahInkPack>,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    liveInk: Boolean,
    onVerseSeek: (surahId: Int, ayah: Int, through: Float) -> Unit,
) {
    val palette = rememberWordInkPalette()
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hitSlopPx = with(LocalDensity.current) { 6.dp.toPx() }
    Text(
        text = block.text,
        style = englishProseStyle(fontSize, lineHeight),
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
                // Ragged: a line is drawn where it was measured, so the paper
                // masks need no justification correction. See
                // Modifier.shapedWordBloom.
                justified = false,
            )
            .pointerInput(block, layoutResult) {
                detectTapGestures { tap ->
                    val layout = layoutResult ?: return@detectTapGestures
                    // The sentence is the unit of this page, as the word is the
                    // unit of the Arabic one — but a tap still says *where* in
                    // the sentence, and the reciter can be sent to the same
                    // share of the verse. See englishSeekWordPosition.
                    val verse = block.verses
                        .firstOrNull { layout.rangeContains(tap, it.range, hitSlopPx) }
                        ?: return@detectTapGestures
                    val at = layout.getOffsetForPosition(tap) - verse.range.first
                    onVerseSeek(
                        verse.surahId,
                        verse.ayah,
                        verse.verseFractionAt(at.coerceAtLeast(0)),
                    )
                }
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
    if (ends != null) blooms.addEnglishRepeatBlooms(verse, motions, ends, palette, text)
    val markCover = (1f - pack.markAlpha.value).coerceIn(0f, 1f)
    if (markCover > 0f && !verse.markRange.isEmpty()) {
        blooms += cover(verse.markRange, paper, markCover)
    }
    return blooms
}

/**
 * The orange the reciter leaves on a word they went back over, on this leaf's
 * English of it.
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
 */
private fun MutableList<ShapedWordBloom>.addEnglishRepeatBlooms(
    verse: EnglishProseVerse,
    motions: List<InkMotion>,
    ends: FloatArray,
    palette: WordInkPalette,
    text: CharSequence,
) {
    motions.forEachIndexed { index, motion ->
        if (motion.repeatAlpha <= 0f) return@forEachIndexed
        val span = englishWashBands(
            range = verse.range,
            from = verse.fragmentProgress(if (index == 0) 0f else ends[index - 1]),
            to = verse.fragmentProgress(ends[index]),
            unread = 1f,
            text = text,
        ).saying
        if (span.isEmpty()) return@forEachIndexed
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
    if (!text[at - 1].isLetter() || !text[at].isLetter()) return at
    for (step in 1..EnglishBandEdgeReach) {
        val back = at - step
        if (back > range.first && !text[back - 1].isLetter()) return back
        val on = at + step
        if (on <= range.last && !text[on].isLetter()) return on + 1
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
            style = englishBasmalahStyle(fontSize),
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
 * A display line, but not necessarily *one* line — on a phone measure it takes
 * two, and the leaf has to give it the paper it actually needs. Assuming a slot
 * for it put its second line on top of the chapter's first verse.
 */
private fun englishBasmalahStyle(fontSize: TextUnit) = TextStyle(
    fontFamily = SerifFontFamily,
    fontStyle = FontStyle.Italic,
    fontSize = fontSize,
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
 * The hand the basmalah is set in: the page's, brought down until the line fits
 * the measure.
 *
 * It is one line. Set at the page's own hand it took two on a phone, and a
 * basmalah broken across a line-end is not a display line — it is a paragraph
 * of one sentence sitting where a heading should be. This is the Latin form of
 * what the Arabic leaf does when a line will not reach its measure
 * (`QURAN_TYPOGRAPHY.md` §4): the line is made to fit, by the only lever the
 * script gives, which here is the size rather than the letterform.
 *
 * It is still one size for the whole book — the measure does not change from
 * leaf to leaf, so neither does this — and a display line set smaller than the
 * body is what a printed translation does with it anyway.
 */
private fun englishBasmalahHandPx(
    handPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
): Float {
    val natural = measurer.measure(
        text = AnnotatedString(ENGLISH_BASMALAH),
        style = englishBasmalahStyle(with(density) { handPx.toSp() }),
        constraints = Constraints(),
        density = density,
    ).size.width
    if (natural <= 0) return handPx
    val fits = measurePx * EnglishBasmalahMeasureFill / natural
    return handPx * fits.coerceIn(EnglishBasmalahMinHand, 1f)
}

/**
 * How much of the measure the basmalah may fill. Short of all of it: a display
 * line that reaches both margins reads as a line of text that happens to be
 * centred, and the last of the fit is rounding slack the line must not spend.
 */
private const val EnglishBasmalahMeasureFill = 0.94f

/** Never smaller than this share of the page's hand, whatever the measure. */
private const val EnglishBasmalahMinHand = 0.62f

/** What the basmalah stands at, plus the air a display line takes under it. */
private fun englishBasmalahPx(
    handPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
): Float = measurer.measure(
    text = AnnotatedString(ENGLISH_BASMALAH),
    style = englishBasmalahStyle(
        with(density) { englishBasmalahHandPx(handPx, measurePx, density, measurer).toSp() },
    ),
    constraints = Constraints(maxWidth = measurePx.toInt().coerceAtLeast(1)),
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
    verseNumberScript: VerseNumberScript,
    hideParentheticals: Boolean,
    translation: (surahId: Int, ayah: Int) -> String,
): EnglishLeafRuler {
    val handPx = englishBookHandPx(wellPx, measurePx, density, measurer)
    val pitchPx = handPx * ENGLISH_LEAF_LEADING_EM
    val inkPx = englishLineInkPx(handPx, density, measurer)
    val basmalahPx = englishBasmalahPx(handPx, measurePx, density, measurer)
    val style = with(density) { englishProseStyle(handPx.toSp(), pitchPx.toSp()) }
    val constraints = Constraints(maxWidth = measurePx.toInt().coerceAtLeast(1))
    return EnglishLeafRuler { page, runs ->
        val leaf = englishLeaf(page, runs, hideParentheticals, translation)
        val blocks = englishLeafBlockTexts(
            leaf = leaf,
            openingTokens = emptyMap(),
            wordEnds = emptyMap(),
            ink = Color.Black,
            gold = Color.Black,
            verseNumberScript = verseNumberScript,
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
            val laid = measurer.measure(
                prose.text,
                style,
                constraints = constraints,
                density = density,
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
                    page, runs, stops[at], hideParentheticals, translation,
                    verseNumberScript, style, constraints, density, measurer,
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
    hideParentheticals: Boolean,
    translation: (Int, Int) -> String,
    verseNumberScript: VerseNumberScript,
    style: TextStyle,
    constraints: Constraints,
    density: Density,
    measurer: TextMeasurer,
): Int {
    val kept = runs.subList(0, cut.runIndex + 1).toMutableList()
    kept[kept.lastIndex] = kept.last().let {
        EnglishVerseRun(it.surahId, it.ayah, it.from, cut.to)
    }
    val prose = englishLeafBlockTexts(
        leaf = englishLeaf(page, kept, hideParentheticals, translation),
        openingTokens = emptyMap(),
        wordEnds = emptyMap(),
        ink = Color.Black,
        gold = Color.Black,
        verseNumberScript = verseNumberScript,
    ).filterIsInstance<EnglishLeafBlockText.Prose>().firstOrNull() ?: return 0
    return measurer.measure(prose.text, style, constraints = constraints, density = density)
        .lineCount
}
