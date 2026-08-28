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
import com.beautifulquran.domain.ENGLISH_LEAF_SPECIMEN
import com.beautifulquran.domain.EnglishLeaf
import com.beautifulquran.domain.EnglishLeafBlock
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.englishLeaf
import com.beautifulquran.domain.englishLeafFittedLeadingEm
import com.beautifulquran.domain.englishLeafHandPx
import com.beautifulquran.domain.englishLeafLeadingEm
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
 * leaf and the scrolling reader are driven from ([MushafPageInkClocks]) — what
 * differs is only what it can honestly say. The reciter's timings name Arabic
 * words and this page has none, so the wash is not per word here: it crosses
 * the sentence of the verse being recited at the fraction of that verse the
 * voice has actually reached ([englishVerseReadProgress]). Verses still to come
 * wait under the same recess; verses already read hold their ink.
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
    onAyahClick: (MushafToken) -> Unit,
    onBasmalahClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ayahsOnPage = remember(page.page, content.surah.id, content.ayahs) {
        page.ayahKeys.mapNotNull { (surahId, ayah) ->
            if (surahId != content.surah.id) return@mapNotNull null
            content.ayahs.firstOrNull { it.number == ayah }
        }
    }
    // The neighbouring chapters on a shared leaf, whose text is not loaded.
    // Same reading as the Arabic leaf gives them: a lower id is behind the
    // reciter and keeps its ink, a higher one is still to come and waits.
    val upcomingOnPage = remember(page.page, content.surah.id) {
        page.ayahKeys.filter { (surahId, _) -> surahId > content.surah.id }
    }
    val recitedOnPage = remember(page.page, content.surah.id) {
        page.ayahKeys.filter { (surahId, _) -> surahId < content.surah.id }
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
    val leaf = remember(page, leafText, hideParentheticals) {
        val verses = leafText ?: return@remember null
        englishLeaf(page, hideParentheticals) { surahId, ayah ->
            verses[quranWordKey(surahId, ayah, 1)].orEmpty()
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
        val blocks = remember(leaf, palette.fullInkColor, gold, verseNumberScript, page) {
            englishLeafBlockTexts(leaf, page, palette.fullInkColor, gold, verseNumberScript)
        }
        val setting = remember(blocks, wellPx, measurePx, density, measurer) {
            setEnglishLeaf(blocks, wellPx, measurePx, density, measurer)
        }
        val fontSize = with(density) { setting.handPx.toSp() }
        val handDp = with(density) { setting.handPx.toDp() }
        val basmalahDp = remember(setting.handPx, measurePx, density, measurer) {
            with(density) {
                englishBasmalahPx(setting.handPx, measurePx, density, measurer).toDp()
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
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(handDp * EnglishLeafPanelEm),
                            contentAlignment = Alignment.Center,
                        ) {
                            MushafSurahTitleBand(
                                surah = surahsById[block.surahId],
                                fontSize = fontSize * EnglishLeafPanelType,
                                bandHeight = handDp * EnglishLeafPanelEm * 0.82f,
                                latin = true,
                            )
                        }
                        if (block.basmalah) {
                            EnglishBasmalahLine(
                                fontSize = fontSize,
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
                        onAyahClick = onAyahClick,
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
 * The chapter panel and its basmalah, in **ems of the book's hand**.
 *
 * Ems, not line pitches. The pitch is the one thing on this leaf that varies
 * from page to page — it is how a leaf fills its well — so a panel measured in
 * pitches was a third larger on a light leaf than on a heavy one. The
 * illumination is the book's, like the hand: one size wherever a chapter opens.
 */
private const val EnglishLeafPanelEm = 3.5f

/** Air under the basmalah, so the chapter's first verse does not run into it. */
private const val EnglishLeafBasmalahAirEm = 0.9f

/** The panel's name is set a step above the text, as the Arabic leaf sets it. */
private const val EnglishLeafPanelType = 1.08f

/** The mark rides at this share of the prose size, as the scrolling reader sets it. */
private const val EnglishLeafMarkType = 17f / 22f

private const val EnglishLeafFadeMs = 220

/** One block of the leaf, with its text already built. */
private sealed class EnglishLeafBlockText {
    data class Opening(val surahId: Int, val basmalah: Boolean) : EnglishLeafBlockText() {
        /** The paper this opening takes: a fixed panel, and a measured basmalah. */
        fun heightPx(handPx: Float, basmalahPx: Float): Float =
            handPx * EnglishLeafPanelEm + if (basmalah) basmalahPx else 0f
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
    val markRange: IntRange,
    /** The verse's first word on the page — what a tap plays from. */
    val token: MushafToken?,
)

/**
 * Builds every block's text once. The ranges are recorded as the string is
 * assembled, which is the only moment they are knowable.
 */
private fun englishLeafBlockTexts(
    leaf: EnglishLeaf,
    page: MushafPage,
    ink: Color,
    gold: Color,
    verseNumberScript: VerseNumberScript,
): List<EnglishLeafBlockText> {
    val openingTokens = page.lines
        .flatMap { it.tokens }
        .filter { it.word.position == 1 }
        .associateBy { it.surahId to it.ayah }
    return leaf.blocks.map { block ->
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
                        append(" ")
                        val markStart = length
                        appendAyahNumberMark(
                            number = verse.ayah,
                            useArabicIndicDigits =
                                verseNumberScript == VerseNumberScript.ARABIC,
                            style = SpanStyle(color = gold, fontSize = EnglishLeafMarkType.em),
                            // The leaf is set left to right whichever digits
                            // the reader has chosen, so the cups are always the
                            // LTR pair.
                            ltr = true,
                        )
                        verses += EnglishProseVerse(
                            surahId = verse.surahId,
                            ayah = verse.ayah,
                            range = range,
                            markRange = markStart until length,
                            token = openingTokens[verse.surahId to verse.ayah],
                        )
                    }
                }
                EnglishLeafBlockText.Prose(text = text, verses = verses)
            }
        }
    }
}

/** The hand and the leading this leaf came out at. */
private data class EnglishLeafSetting(val handPx: Float, val leadingEm: Float)

/**
 * Sets the leaf: takes the book's hand, then solves the one leading that puts
 * the block's foot on the foot of the well.
 *
 * The hand is never touched. It is the book's, cut for the heaviest leaf in it
 * (`EnglishLeafFit.kt`), and a leaf that set its own type would be the one
 * fault a reader turning pages cannot miss.
 *
 * A block of `n` lines stands `(n − 1)` pitches plus one line's own ink —
 * because the leaf is set `LineHeightStyle.Trim.Both`, so the first line begins
 * at its ascent and the last ends at its descender rather than at half a
 * leading beyond either. That is what puts the top of the text on the grid: the
 * head gutter is the same paper on every leaf in the book, whatever leading the
 * page happens to be set on. Chapter panels are a fixed number of ems and do
 * not move with the leading at all. So the whole leaf is
 *
 * ```
 *   well = Σ panels + Σ ( (nᵢ − 1) · hand · ℓ + ink )      →      solve for ℓ
 * ```
 *
 * and the second pass is the guarantee rather than the arithmetic: the leaf is
 * measured as it will actually be drawn, and the leading steps by whatever is
 * left over. Down without a floor — a line past the foot is revelation the
 * reader cannot see — and up no further than the band allows.
 */
private fun setEnglishLeaf(
    blocks: List<EnglishLeafBlockText>,
    wellPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
): EnglishLeafSetting {
    val handPx = englishLeafHandPx(
        wellHeightPx = wellPx,
        measureWidthPx = measurePx,
        charAdvanceEm = englishCharAdvanceEm(measurer, density),
    )
    val basmalahPx = englishBasmalahPx(handPx, measurePx, density, measurer)
    val shape = englishLeafShape(blocks, handPx, basmalahPx, measurePx, density, measurer)
    val chosen = englishLeafLeadingEm(
        lines = shape.pitches,
        fontPx = handPx,
        wellHeightPx = (wellPx - shape.fixedPx).coerceAtLeast(1f),
    )
    val stands =
        englishLeafHeightPx(blocks, handPx, basmalahPx, chosen, measurePx, density, measurer)
    return EnglishLeafSetting(
        handPx = handPx,
        leadingEm = englishLeafFittedLeadingEm(
            leadingEm = chosen,
            measuredHeightPx = stands,
            wellHeightPx = wellPx,
            pitchesPx = (shape.pitches * handPx).coerceAtLeast(1f),
        ),
    )
}

/**
 * What the leaf holds that the leading cannot change: how many baseline steps
 * its prose takes, and how much paper its panels and its lines' own ink take
 * whatever the leading is.
 */
private data class EnglishLeafShape(val pitches: Float, val fixedPx: Float)

private fun englishLeafShape(
    blocks: List<EnglishLeafBlockText>,
    handPx: Float,
    basmalahPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
): EnglishLeafShape {
    val inkPx = englishLineInkPx(handPx, measurePx, density, measurer)
    var pitches = 0f
    var fixed = 0f
    blocks.forEach { block ->
        when (block) {
            is EnglishLeafBlockText.Opening -> fixed += block.heightPx(handPx, basmalahPx)
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
                fixed += inkPx
            }
        }
    }
    return EnglishLeafShape(pitches = pitches.coerceAtLeast(0.001f), fixedPx = fixed)
}

/** One line of the book's own ink, top of ascent to foot of descender. */
private fun englishLineInkPx(
    handPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
): Float = measurer.measure(
    text = AnnotatedString(ENGLISH_LEAF_SPECIMEN),
    style = englishProseStyle(with(density) { handPx.toSp() }, TextUnit.Unspecified),
    constraints = Constraints(maxWidth = measurePx.toInt().coerceAtLeast(1)),
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
    val style = englishProseStyle(
        with(density) { handPx.toSp() },
        with(density) { (handPx * leadingEm).toSp() },
    )
    return blocks.sumOf { block ->
        when (block) {
            is EnglishLeafBlockText.Opening -> block.heightPx(handPx, basmalahPx).toDouble()
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
 * The face's average character advance, in ems.
 *
 * Measured rather than assumed: it is the one term of the hand equation that
 * belongs to the type and not to the page, and EB Garamond runs narrow enough
 * that guessing it would set the whole book a size out.
 */
private fun englishCharAdvanceEm(measurer: TextMeasurer, density: Density): Float {
    val probeSp = 100.sp
    val probePx = with(density) { probeSp.toPx() }
    val width = measurer.measure(
        text = AnnotatedString(ENGLISH_LEAF_SPECIMEN),
        style = englishProseStyle(probeSp, TextUnit.Unspecified)
            .copy(textAlign = TextAlign.Start),
        constraints = Constraints(),
        density = density,
    ).size.width
    if (width <= 0 || probePx <= 0f) return EnglishLeafFallbackAdvanceEm
    return width / (probePx * ENGLISH_LEAF_SPECIMEN.length)
}

/** Only ever used if the measurer answers nothing: EB Garamond's own figure. */
private const val EnglishLeafFallbackAdvanceEm = 0.40f

/**
 * The book's hand: EB Garamond, justified, unhyphenated.
 *
 * Latin fills its line by the word space, which is what `TextAlign.Justify`
 * does — and unlike the mushaf's flush-last rule (`QURAN_TYPOGRAPHY.md` §3) a
 * Latin paragraph's last line stands where it ends, which is what Compose
 * already does. `LineBreak.Paragraph` breaks the whole block at once rather
 * than greedily line by line, which is what keeps a justified narrow measure
 * from ending on one very loose line.
 *
 * **Not hyphenated, and this is load-bearing.** A justified measure of about
 * fifty characters would ordinarily be hyphenated, and the type would be better
 * set for it. But hyphenation breaks a *word* across two lines, and
 * `ShapedWordBloom.ColorReveal` takes the union bounds of a range's glyph path
 * — so a tinted wash over a broken word would sweep the width of the whole
 * line. `InkReveal` handles a multi-line range correctly (it advances one wash
 * across the fragments in order, which is what this page's verse wash needs),
 * but the tinted layers do not. Anyone turning hyphens on must fix ColorReveal
 * the same way first.
 */
private fun englishProseStyle(fontSize: TextUnit, lineHeight: TextUnit) = TextStyle(
    fontFamily = SerifFontFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    textAlign = TextAlign.Justify,
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
    onAyahClick: (MushafToken) -> Unit,
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
                        val layout = layoutResult
                        block.verses.flatMap { verse ->
                            englishVerseBlooms(
                                verse = verse,
                                pack = packs[verse.surahId to verse.ayah],
                                paper = palette.paperColor,
                                lines = layout?.let { englishVerseLines(it, verse) } ?: 1,
                            )
                        }
                    }
                },
                layout = { layoutResult },
                rtl = false,
                feather = InkEngine.tuning.washFeather,
                // The page is justified, and the paper masks have to be told:
                // a selection path is measured before the line is stretched to
                // the measure. See Modifier.shapedWordBloom.
                justified = true,
            )
            .pointerInput(block, layoutResult) {
                detectTapGestures { tap ->
                    val layout = layoutResult ?: return@detectTapGestures
                    // A tap anywhere in a verse plays it. There is no word to
                    // aim at here — the sentence is the unit of this page, as
                    // the word is the unit of the Arabic one.
                    block.verses
                        .firstOrNull { layout.rangeContains(tap, it.range, hitSlopPx) }
                        ?.token
                        ?.let(onAyahClick)
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
 */
private fun englishVerseBlooms(
    verse: EnglishProseVerse,
    pack: AyahInkPack?,
    paper: Color,
    /** How many lines the sentence is set over — the wash's edge is one of them. */
    lines: Int,
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
        val read = englishVerseReadProgress(pack.motions)
        if (read < 1f) {
            blooms += ShapedWordBloom.InkReveal(
                range = verse.range,
                progress = read,
                paper = paper,
                // What the sentence rests at before the voice reaches it —
                // the same floor the unread words of a recited verse sit at on
                // the Arabic leaf.
                restingAlpha = InkEngine.State.Upcoming.inkAlpha(),
                feather = englishWashFeather(lines),
            )
        }
    }
    val markCover = (1f - pack.markAlpha.value).coerceIn(0f, 1f)
    if (markCover > 0f) {
        blooms += ShapedWordBloom.UpcomingDim(
            range = verse.markRange,
            paper = paper,
            coverAlpha = markCover,
        )
    }
    return blooms
}

/** The lines of the paragraph this verse's sentence is set over. */
private fun englishVerseLines(layout: TextLayoutResult, verse: EnglishProseVerse): Int {
    val text = layout.layoutInput.text
    if (text.isEmpty() || verse.range.isEmpty()) return 1
    val first = layout.getLineForOffset(verse.range.first.coerceIn(0, text.length - 1))
    val last = layout.getLineForOffset(verse.range.last.coerceIn(0, text.length - 1))
    return (last - first + 1).coerceAtLeast(1)
}

/**
 * The wash's edge, as a fraction of the sentence it crosses.
 *
 * [InkEngine.tuning.washFeather] is 1.6 of a *word*, which is the shape of the
 * Arabic leaf's ink: an edge wider than the thing it crosses, so a word breathes
 * in rather than being wiped. A verse of English prose is eight lines of that
 * thing, and the same fraction made the edge wider than the whole sentence —
 * which meant nothing behind the voice ever reached full ink, and the page
 * brightened as one wash instead of being read through. Already-recited ink
 * holds full strength; that is not negotiable (docs/INK_ENGINE.md).
 *
 * So the edge is a line of the page instead, whatever the sentence's length.
 * A one-line verse comes out near the word's own figure and is capped at it.
 */
private fun englishWashFeather(lines: Int): Float =
    (EnglishWashFeatherLines / lines.coerceAtLeast(1))
        .coerceIn(EnglishWashFeatherFloor, InkEngine.tuning.washFeather)

private const val EnglishWashFeatherLines = 1.1f
private const val EnglishWashFeatherFloor = 0.06f

/**
 * How far through a verse the reciter has actually read, 0..1.
 *
 * The honest quantity behind the English leaf's wash. The timings name Arabic
 * words, so this counts them: the words before the one being recited, plus how
 * far into that word its own letter sweep has come. It is not a claim that any
 * English word lines up with any Arabic one — it is the statement that the
 * voice is this far through this verse, which is true.
 *
 * Read off the active word's *index* rather than by counting what is behind it,
 * so the wash cannot run backwards on a frame where a word ahead of the voice
 * has not yet settled into its state.
 */
internal fun englishVerseReadProgress(motions: List<InkMotion>): Float {
    if (motions.isEmpty()) return 1f
    val active = motions.indexOfFirst { it.isActive }
    if (active < 0) {
        // No word is being recited: either the verse is done (or an idle leaf,
        // which reads as done) or it has not begun.
        val waiting = motions.count { it.ink.state == InkEngine.State.Upcoming }
        return if (waiting == motions.size) 0f else 1f
    }
    val sweep = motions[active].sweepProgress.coerceIn(0f, 1f)
    return ((active + sweep) / motions.size).coerceIn(0f, 1f)
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
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ENGLISH_BASMALAH,
            style = englishBasmalahStyle(fontSize),
            color = MaterialTheme.colorScheme.onBackground,
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
                            feather = InkEngine.prefaceFeather(),
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
)

/** What the basmalah stands at, plus the air a display line takes under it. */
private fun englishBasmalahPx(
    handPx: Float,
    measurePx: Float,
    density: Density,
    measurer: TextMeasurer,
): Float = measurer.measure(
    text = AnnotatedString(ENGLISH_BASMALAH),
    style = englishBasmalahStyle(with(density) { handPx.toSp() }),
    constraints = Constraints(maxWidth = measurePx.toInt().coerceAtLeast(1)),
    density = density,
).size.height + handPx * EnglishLeafBasmalahAirEm

/** Saheeh International's rendering — the translation the book is set in. */
private const val ENGLISH_BASMALAH =
    "In the name of Allah, the Entirely Merciful, the Especially Merciful."
