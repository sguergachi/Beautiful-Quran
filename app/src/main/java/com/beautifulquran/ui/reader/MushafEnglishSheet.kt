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
import com.beautifulquran.domain.englishLeafFittedLeadingEm
import com.beautifulquran.domain.englishLeafOverflowHandPx
import com.beautifulquran.domain.englishLeafHandPx
import com.beautifulquran.domain.ENGLISH_LEAF_LEADING_EM
import com.beautifulquran.domain.mushafIsOpeningLeaf
import com.beautifulquran.domain.quranWordKey
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.SerifFontFamily
import com.beautifulquran.ui.theme.ShapedWordBloom
import com.beautifulquran.ui.theme.letterFadeIn
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.shapedWordBloom
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
 * wash crosses the sentence — but it crosses it on the map
 * `EnglishWordAlignment` builds from the word glosses, so the ink is on the
 * English of the word being said, not on the same fraction of the characters
 * ([englishVerseReadProgress]). Verses still to come wait under the same
 * recess; verses already read hold their ink.
 *
 * For the same reason the leaf carries no orange repeat and no wet-ink glint:
 * both are statements about one Arabic word — that the reciter went back over
 * it, that its ink is still wet — and there is no word here to say them of.
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
    verseNumberScript: VerseNumberScript,
    /** The leaf's fore-edge, shared with the running head and the folio. */
    foreEdge: Dp,
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
        // A hair off the well: the block is solved to fill this exactly, and
        // rounding must not put the last line's descenders past the foot.
        val wellPx = with(density) {
            (constraints.maxHeight - EnglishLeafFitSlack.roundToPx()).toFloat().coerceAtLeast(1f)
        }
        val measurePx = with(density) {
            (constraints.maxWidth - foreEdge.roundToPx() * 2).toFloat().coerceAtLeast(1f)
        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = foreEdge),
            // A leaf whose content will not reach the foot hangs from the
            // head, as a book's last page of a chapter does — the paper simply
            // runs out under it.
            //
            // Pages 1-2 are the print's exception and stay the exception here
            // (§1). They are the two lightest leaves in the book by a wide
            // margin — al-Fatihah, and five verses of al-Baqarah — so even at
            // the widest leading they fill about a third of the well, and hung
            // from the head they read as a page that failed rather than a page
            // that opens a book. Centred, the chapter's panel sits where the
            // print's own medallion sits.
            verticalArrangement = if (mushafIsOpeningLeaf(page.page)) {
                Arrangement.Center
            } else {
                Arrangement.Top
            },
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
        val closed = stands - (ENGLISH_LEAF_LEADING_EM - leadingEm) * pitches * handPx
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
                                paper = palette.paperColor,
                            )
                        }
                    }
                },
                layout = { layoutResult },
                rtl = false,
                feather = InkEngine.tuning.washFeather,
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
 * The blooms for one verse of the paragraph.
 *
 * Three states and no more. A verse still to come waits under a paper cover; a
 * verse being recited is washed across at the fraction of it the voice has
 * reached; a verse already read holds full ink and draws nothing at all.
 *
 * A verse the reader has just seeked into is the awkward fourth case, and it is
 * the one the recess cover exists for. Tapping the middle of a sentence puts the
 * voice there, which makes everything before the tap *already read* — and the
 * wash drew that read ink at full strength on the very next frame, so the
 * sentence flashed on and then dimmed again as the wash restarted behind the
 * voice. The Arabic leaf never did that: its already-read words carry the ayah's
 * `recessCover` and rise out of the paper over `recessMs`. A verse of prose is
 * one range rather than a row of words, so it takes the same rise through
 * [ShapedWordBloom.InkReveal.readAlpha].
 */
private fun englishVerseBlooms(
    verse: EnglishProseVerse,
    pack: AyahInkPack?,
    paper: Color,
): List<ShapedWordBloom> {
    if (pack == null) return emptyList()
    val blooms = ArrayList<ShapedWordBloom>(2)
    if (pack.motions.isEmpty()) {
        val cover = pack.recessCover.value.coerceIn(0f, 1f)
        if (cover > 0f) {
            blooms += ShapedWordBloom.UpcomingDim(
                range = verse.range,
                paper = paper,
                coverAlpha = cover,
            )
        }
    } else {
        val read = verse.fragmentProgress(
            englishVerseReadProgress(pack.motions, verse.wordEnds),
        )
        val resting = InkEngine.State.Upcoming.inkAlpha()
        val readAlpha = englishReadInkAlpha(pack.recessCover.value, resting)
        if (read < 1f || readAlpha < 1f) {
            blooms += ShapedWordBloom.InkReveal(
                range = verse.range,
                progress = read,
                paper = paper,
                // What the sentence rests at before the voice reaches it —
                // the same floor the unread words of a recited verse sit at on
                // the Arabic leaf.
                restingAlpha = resting,
                // One English span per Arabic word, so the sentence's word
                // count is the unit the edge is measured in.
                feather = englishWashFeather(pack.motions.size),
                readAlpha = readAlpha,
            )
        }
    }
    val markCover = (1f - pack.markAlpha.value).coerceIn(0f, 1f)
    if (markCover > 0f && !verse.markRange.isEmpty()) {
        blooms += ShapedWordBloom.UpcomingDim(
            range = verse.markRange,
            paper = paper,
            coverAlpha = markCover,
        )
    }
    return blooms
}

/**
 * How strong the ink *behind* the wash stands while a verse lifts out of the
 * page recess, 0..1.
 *
 * The cover runs from a full recess (`1 - upcoming ink`) to nothing over
 * [InkEngine.Tuning.recessMs]; read ink runs the other way, from the upcoming
 * floor up to full, so a sentence seeked into rises out of the paper instead of
 * appearing on it. Full recess and the upcoming floor are the same number, so
 * at the start of the lift the read ink and the unread ink are indistinguishable
 * — which is exactly right: nothing has been read *on this page* yet.
 */
internal fun englishReadInkAlpha(recessCover: Float, restingAlpha: Float): Float {
    val full = 1f - restingAlpha
    if (full <= 0f) return 1f
    val lift = (1f - recessCover.coerceIn(0f, full) / full).coerceIn(0f, 1f)
    return restingAlpha + (1f - restingAlpha) * lift
}

/**
 * The wash's edge, as a fraction of the sentence it crosses.
 *
 * [InkEngine.tuning.washFeather] is 1.6 of a **word** — an edge wider than the
 * thing it crosses, so a word breathes in rather than being wiped. That is what
 * the scrolling reader draws on each English gloss, and it is the feel this leaf
 * has to match. The leaf's difficulty is only that its range is the whole
 * sentence rather than one word, so the same number cannot be used raw: 1.6 of a
 * sentence is an edge wider than the sentence, and nothing behind the voice ever
 * reaches full ink.
 *
 * It was a *line of the page* for a while, which is the wrong unit — a line of
 * prose is six or seven words, so the edge crossed six or seven words at once
 * and the wash read as a slow brightening rather than as words being said. The
 * unit is the word, as everywhere else: the sentence holds one English span per
 * Arabic word ([EnglishWordAlignment]), so an edge of `washFeather` word-spans
 * is `washFeather / words` of the sentence — the same 1.6 words the scrolling
 * reader shows, whatever length the verse is.
 *
 * A one-word verse (2:1, الٓمٓ) comes out at the cap and breathes as one, which
 * is right: it *is* one word.
 */
internal fun englishWashFeather(words: Int): Float =
    (InkEngine.tuning.washFeather / words.coerceAtLeast(1))
        .coerceIn(EnglishWashFeatherFloor, InkEngine.tuning.washFeather)

/**
 * Only a guard. The rule above is already scale-free — it is 1.6 words wide on
 * a seven-word verse and on a fifty-word one — so this exists to stop a verse
 * with an implausible word count from producing a hard peel, not to widen a
 * long sentence's edge back out.
 */
private const val EnglishWashFeatherFloor = 0.02f

/**
 * How far through a verse's *English* the reciter has read, 0..1.
 *
 * The timings name Arabic words, so this starts from them: the word being
 * recited, plus how far into it its own letter sweep has come. [wordEnds] then
 * says where that word's English actually is — the share of the sentence it
 * ends at — so the ink lands on the words the listener is hearing rather than
 * on the same fraction of the characters (`EnglishWordAlignment`). Without an
 * alignment the words divide the sentence evenly, which is what the leaf did
 * before and what an unalignable verse still gets.
 *
 * Read off the active word's *index* rather than by counting what is behind it,
 * so the wash cannot run backwards on a frame where a word ahead of the voice
 * has not yet settled into its state.
 *
 * The word's own share is crossed on [InkMotion.plainSweepProgress] — the
 * linear clock — and never on the tajweed-paced one. Pacing says where
 * inside an Arabic *word* the time is going, which is a true and useful thing
 * to draw on Arabic letters and a false one to draw on English prose: it parks
 * the sentence's wash for as long as the reciter sustains a madd (a closing
 * word is held ~3 s), then sprints it. The scrolling reader's English mode
 * refuses the same curve for the same reason.
 */
internal fun englishVerseReadProgress(
    motions: List<InkMotion>,
    wordEnds: FloatArray? = null,
): Float {
    if (motions.isEmpty()) return 1f
    val active = motions.indexOfFirst { it.isActive }
    if (active < 0) {
        // No word is being recited: either the verse is done (or an idle leaf,
        // which reads as done) or it has not begun.
        val waiting = motions.count { it.ink.state == InkEngine.State.Upcoming }
        return if (waiting == motions.size) 0f else 1f
    }
    val sweep = motions[active].plainSweepProgress.coerceIn(0f, 1f)
    val ends = wordEnds?.takeIf { it.size == motions.size }
        ?: return ((active + sweep) / motions.size).coerceIn(0f, 1f)
    val from = if (active == 0) 0f else ends[active - 1]
    return (from + sweep * (ends[active] - from)).coerceIn(0f, 1f)
}

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
