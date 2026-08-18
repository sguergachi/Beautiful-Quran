package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt
import com.beautifulquran.domain.MUSHAF_LINE_EM
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.buildMushafQcfLine
import com.beautifulquran.domain.mushafGapSpacingPx
import com.beautifulquran.domain.mushafLineJustifies
import com.beautifulquran.domain.MUSHAF_WORD_GAP_EM
import com.beautifulquran.domain.MushafLineFit
import com.beautifulquran.domain.mushafLineFit
import com.beautifulquran.domain.mushafLineCondense
import com.beautifulquran.domain.qcfTrailingMark
import com.beautifulquran.domain.qcfWordGlyphs
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.MushafFontFamily
import com.beautifulquran.ui.theme.PaperCoverPad
import com.beautifulquran.ui.theme.ShapedWordBloom
import com.beautifulquran.ui.theme.glyphLayerAlpha
import com.beautifulquran.ui.theme.letterFadeIn
import com.beautifulquran.ui.theme.shapedWordBloom

private const val MARK_SIZE_RATIO = 20f / 30f

/**
 * One Madinah line. QCF V2 is one handwritten word-glyph per token
 * (no U+0020). Leftover width is [Spacer] weights so the line fills
 * the page like the printed 15-line mushaf. Unicode fallback uses
 * Digital Khatt. Ink is [shapedWordBloom].
 */
@Composable
internal fun MushafHafsLine(
    line: MushafLine,
    packs: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
    fontSize: TextUnit,
    /**
     * The line's measure, in px. Passed down rather than read from a
     * [BoxWithConstraints] of its own: every line on a leaf is set to the same
     * measure, which the leaf has already computed, and a subcomposition per
     * line cost fifteen of them per page — forty-five live in the pager —
     * for a number that never differs between them.
     */
    measureWidthPx: Float,
    /** The page's own face, for measuring where each word's ink falls. */
    pageTypeface: android.graphics.Typeface? = null,
    liveInk: Boolean,
    onWordClick: (MushafToken) -> Unit,
    onWordLongClick: (MushafToken) -> Unit,
    onAyahClick: (MushafToken) -> Unit,
    pageFont: FontFamily? = null,
    modifier: Modifier = Modifier,
) {
    val palette = rememberWordInkPalette()
    val ayahMarkInk = LocalQuranAccents.current.gold
    val glintInk = LocalQuranAccents.current.glintInk
    val useQcf = pageFont != null && line.tokens.any { it.word.qcfV2.isNotEmpty() }
    if (useQcf && pageFont != null) {
        MushafQcfPageLine(
            line = line,
            pageFont = pageFont,
            fontSize = fontSize,
            measureWidthPx = measureWidthPx,
            pageTypeface = pageTypeface,
            packs = packs,
            liveInk = liveInk,
            palette = palette,
            ayahMarkInk = ayahMarkInk,
            glintInk = glintInk,
            onWordClick = onWordClick,
            onWordLongClick = onWordLongClick,
            onAyahClick = onAyahClick,
            modifier = modifier,
        )
        return
    }
    val style = remember(fontSize, pageFont, useQcf) {
        TextStyle(
            fontFamily = pageFont.takeIf { useQcf } ?: MushafFontFamily,
            fontSize = fontSize,
            lineHeight = MUSHAF_LINE_EM.em,
            textAlign = TextAlign.Start,
            textDirection = TextDirection.Rtl,
            fontFeatureSettings = if (useQcf) null else "'liga' 1, 'calt' 1, 'rlig' 1, 'rclt' 1",
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val hitSlopPx = with(density) { 8.dp.toPx() }
    val measurer = rememberTextMeasurer()

    Box(modifier.fillMaxWidth()) {
        val natural = remember(line, palette.fullInkColor, ayahMarkInk, fontSize, useQcf) {
            if (useQcf) {
                qcfRendered(line, palette.fullInkColor, ayahMarkInk)
            } else {
                buildMushafLine(line, palette.fullInkColor, ayahMarkInk, fontSize, gapSpacing = 0.sp)
            }
        }
        val naturalWidth = remember(natural.text, style, measureWidthPx) {
            measurer.measure(
                text = natural.text,
                style = style,
                constraints = Constraints(),
                maxLines = 1,
                softWrap = false,
            ).size.width
        }
        val gapPx = if (useQcf) {
            0f
        } else {
            mushafGapSpacingPx(
                naturalWidthPx = naturalWidth.toFloat(),
                pageWidthPx = measureWidthPx,
                gapCount = (line.tokens.size - 1).coerceAtLeast(0),
                fontPx = with(density) { fontSize.toPx() },
            )
        }
        val gapSp = with(density) { gapPx.toSp() }
        val rendered = remember(line, palette.fullInkColor, ayahMarkInk, fontSize, gapSp, useQcf) {
            when {
                gapPx <= 0f -> natural
                useQcf -> qcfRendered(line, palette.fullInkColor, ayahMarkInk, gapSp)
                else -> buildMushafLine(line, palette.fullInkColor, ayahMarkInk, fontSize, gapSp)
            }
        }
        val blooms = {
            if (!liveInk) {
                emptyList()
            } else {
                buildLineBlooms(
                    line = line,
                    packs = packs,
                    rendered = rendered,
                    palette = palette,
                    glintInk = glintInk,
                )
            }
        }
        Text(
            text = rendered.text,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .fillMaxWidth()
                .mushafLineInk(
                    liveInk = liveInk,
                    blooms = blooms,
                    layout = { layoutResult },
                )
                .wordTapTarget(
                    words = line.tokens.map { it.word },
                    ranges = rendered.wordRanges,
                    layoutResult = layoutResult,
                    hitSlopPx = hitSlopPx,
                    onWordClick = { word ->
                        line.tokens.firstOrNull { it.word === word }?.let(onWordClick)
                    },
                    onWordLongClick = { word ->
                        line.tokens.firstOrNull { it.word === word }?.let(onWordLongClick)
                    },
                    onMiss = { line.tokens.firstOrNull()?.let(onAyahClick) },
                ),
            onTextLayout = { layoutResult = it },
        )
    }
}

@Composable
private fun MushafQcfPageLine(
    line: MushafLine,
    pageFont: FontFamily,
    fontSize: TextUnit,
    measureWidthPx: Float,
    pageTypeface: android.graphics.Typeface?,
    packs: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
    liveInk: Boolean,
    palette: WordInkPalette,
    ayahMarkInk: androidx.compose.ui.graphics.Color,
    glintInk: androidx.compose.ui.graphics.Color?,
    onWordClick: (MushafToken) -> Unit,
    onWordLongClick: (MushafToken) -> Unit,
    onAyahClick: (MushafToken) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hitSlopPx = with(density) { 8.dp.toPx() }
    val justify = mushafLineJustifies(line.tokens.size)
    val measurer = rememberTextMeasurer()
    // The book is set at one size (see MUSHAF_DESIGN_LINE_EM). A line whose
    // glyph run still runs past the measure — a few dozen in the whole mushaf —
    // is set that little bit tighter, so one long line never drags its page's
    // type down with it.
    val linePx = with(density) { fontSize.toPx() }
    // What the words actually mark, measured from the page's own face and
    // unscaled. Everything about the line's fit follows from this.
    val rawCells = remember(line, pageTypeface, linePx) {
        mushafLineCells(line, pageTypeface, linePx, condense = 1f)
    }
    // A word space is guaranteed first and the letters give way to it, which is
    // the order a compositor works in. Fitting the advance run to the measure
    // instead left the spacing to be whatever paper happened to remain: over
    // 738 lines that put a third of them under 0.10 em and the tightest tenth
    // into overlap. See MUSHAF_MIN_WORD_GAP_EM.
    val fit = remember(rawCells, measureWidthPx, linePx, justify) {
        if (!justify) {
            // Too few words to justify: the page's own space, centred.
            MushafLineFit(
                scale = 1f,
                gapPx = MUSHAF_WORD_GAP_EM * linePx,
                flush = false,
            )
        } else {
            mushafLineFit(
                inkWidthPx = rawCells.sumOf { it.inkWidth.toDouble() }.toFloat(),
                gapCount = (rawCells.size - 1).coerceAtLeast(0),
                measureWidthPx = measureWidthPx,
                fontPx = linePx,
            )
        }
    }
    val condense = fit.scale
    // Condensed, never resized. A line brought inside the measure keeps its
    // height, weight and colour — the page still reads as one hand — where a
    // line set at a different size reads as a fault.
    val style = remember(fontSize, pageFont, condense) {
        TextStyle(
            fontFamily = pageFont,
            fontSize = fontSize,
            lineHeight = MUSHAF_LINE_EM.em,
            textDirection = TextDirection.Rtl,
            textGeometricTransform = TextGeometricTransform(scaleX = condense),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            // AB: linear metrics + subpixel positioning (no grid fitting)
            textMotion = TextMotion.Animated,
        )
    }
    // Coloured once for the line. A Material [Text] resolves the ambient text
    // style and content colour on every call, and a leaf carries ~150 word
    // nodes: folding the colour into the style here lets each word draw with
    // foundation's BasicText, which does none of that work.
    val wordStyle = remember(style, palette.fullInkColor) { style.copy(color = palette.fullInkColor) }
    val markStyle = remember(style, ayahMarkInk) { style.copy(color = ayahMarkInk) }
    // Where each cell's ink actually falls, not where its advance box does.
    //
    // The page faces carry the print's own side bearings, and they differ
    // enormously from glyph to glyph: measured on page 3, splitting a line's
    // leftover paper evenly between advance boxes put visual gaps of 0.21,
    // -0.48, 0.27, 0.40, 0.20 and 0.17 em on one line — one pair of words
    // overlapping by half an em while another sat twice as far apart as its
    // neighbour. That is what reads as letters running together in one place
    // and drifting apart in the next. So the line is spaced by ink: the paper
    // between one word's last stroke and the next word's first is made equal,
    // which is what a printed line does.
    // Measured again at the size they are drawn, not scaled arithmetically from
    // the unscaled measurement: the rasteriser's ink at a given textScaleX is
    // not exactly that many times its ink at 1, and over nine cells the
    // difference left a line sixty pixels short of its own margin.
    val cells = remember(line, pageTypeface, linePx, condense) {
        if (condense == 1f) rawCells else mushafLineCells(line, pageTypeface, linePx, condense)
    }
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            line.tokens.forEach { token ->
                MushafQcfWord(
                    token = token,
                    style = wordStyle,
                    packs = packs,
                    liveInk = liveInk,
                    palette = palette,
                    ayahMarkInk = ayahMarkInk,
                    glintInk = glintInk,
                    hitSlopPx = hitSlopPx,
                    onWordClick = onWordClick,
                    onWordLongClick = onWordLongClick,
                    onAyahClick = onAyahClick,
                )
                val mark = token.word.qcfV2.takeIf { it.isNotEmpty() }
                    ?.let(::qcfTrailingMark).orEmpty()
                if (mark.isNotEmpty()) {
                    // The mark is its own cell, so it sits outside the word's
                    // ink node and needs the verse's own alphas applied here:
                    // the focus alpha the scrolling reader gives every mark, and
                    // the recess that dims a verse waiting its turn. Read in the
                    // layer block so both animate without recomposing the leaf.
                    val markInkAlpha = {
                        val pack = packs[token.surahId to token.ayah]
                        if (!liveInk || pack == null) 1f
                        else (pack.markAlpha.value * (1f - pack.recessCover.value))
                            .coerceIn(0f, 1f)
                    }
                    BasicText(
                        text = mark,
                        style = markStyle,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        modifier = if (liveInk) {
                            Modifier.graphicsLayer {
                                alpha = markInkAlpha()
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                        } else {
                            Modifier
                        },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val width = constraints.maxWidth
        val origins = mushafCellOrigins(cells, placeables.size, width.toFloat(), fit)
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                p.place(origins.getOrElse(i) { 0f }.roundToInt(), (height - p.height) / 2)
            }
        }
    }
}

/** A cell's advance and where its ink sits inside it, in px. */
internal class MushafCell(val advance: Float, val inkLeft: Float, val inkRight: Float) {
    val inkWidth: Float get() = (inkRight - inkLeft).coerceAtLeast(0f)

    /** The same cell with its letterforms narrowed — scaleX scales ink and
     * advance alike, so the measurements scale with them. */
    fun scaled(k: Float) = MushafCell(advance * k, inkLeft * k, inkRight * k)
}

private fun mushafLineCells(
    line: MushafLine,
    typeface: android.graphics.Typeface?,
    fontPx: Float,
    condense: Float,
): List<MushafCell> {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = fontPx
        textScaleX = condense
    }
    val bounds = android.graphics.Rect()
    val out = ArrayList<MushafCell>(line.tokens.size + 4)
    // One cell per drawn child, empty or not: the layout emits a node for every
    // word whether or not it carries glyphs, and a cell list that skipped the
    // empty ones put every following word on the wrong origin — the line then
    // ended short of its own margin by about a word.
    fun add(text: String) {
        if (text.isEmpty()) {
            out += MushafCell(0f, 0f, 0f)
            return
        }
        val advance = paint.measureText(text)
        paint.getTextBounds(text, 0, text.length, bounds)
        out += MushafCell(advance, bounds.left.toFloat(), bounds.right.toFloat())
    }
    line.tokens.forEach { token ->
        val raw = token.word.qcfV2
        add(if (raw.isNotEmpty()) qcfWordGlyphs(raw) else token.word.arabic)
        if (raw.isNotEmpty() && qcfTrailingMark(raw).isNotEmpty()) add(qcfTrailingMark(raw))
    }
    return out
}

/**
 * Left origin for each cell, right to left across [width].
 *
 * A justified line divides the paper left over between the cells' *ink*, so
 * every join carries the same air. A short line — al-Fātiḥah, a chapter's
 * closing line — keeps the face's own advances and is centred, the way it is
 * printed.
 */
internal fun mushafCellOrigins(
    cells: List<MushafCell>,
    count: Int,
    width: Float,
    fit: MushafLineFit,
): FloatArray {
    val n = minOf(cells.size, count)
    val origins = FloatArray(count)
    if (n == 0) return origins
    val inkTotal = (0 until n).sumOf { cells[it].inkWidth.toDouble() }.toFloat()
    // A flush line divides what is actually left, so it ends on the margin
    // whatever the rasteriser did with the letterforms. The fit has already
    // chosen the scale that makes this residue the space we want.
    val gap = if (fit.flush && n > 1) {
        ((width - inkTotal) / (n - 1)).coerceAtLeast(0f)
    } else {
        fit.gapPx
    }
    // A flush line starts at the fore-edge; a short one is centred on the page,
    // the way a chapter's closing line is printed. Either way the words sit the
    // same distance apart — the page's own space.
    var inkRight = if (fit.flush) {
        width
    } else {
        width - ((width - (inkTotal + (n - 1) * gap)) / 2f).coerceAtLeast(0f)
    }
    for (i in 0 until n) {
        origins[i] = inkRight - cells[i].inkRight
        inkRight -= cells[i].inkWidth + gap
    }
    return origins
}

@Composable
private fun MushafQcfWord(
    token: MushafToken,
    style: TextStyle,
    packs: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
    liveInk: Boolean,
    palette: WordInkPalette,
    ayahMarkInk: androidx.compose.ui.graphics.Color,
    glintInk: androidx.compose.ui.graphics.Color?,
    hitSlopPx: Float,
    onWordClick: (MushafToken) -> Unit,
    onWordLongClick: (MushafToken) -> Unit,
    onAyahClick: (MushafToken) -> Unit,
) {
    val raw = token.word.qcfV2
    val word = if (raw.isNotEmpty()) qcfWordGlyphs(raw) else token.word.arabic
    val rendered = remember(word) {
        // The word alone. Its circled mark, if it closes a verse, is set as its
        // own cell of the line (see [MushafQcfPageLine]) so the line's spacing
        // falls either side of it evenly — glued to the word, a mark took the
        // gap on one side only and the page read lopsided around every verse.
        // No colour span: the ink is in the style now, and a span costs the
        // paragraph a resolve pass per word.
        RenderedLineText(
            text = AnnotatedString(word),
            wordRanges = listOf(0 until word.length),
            markRange = 0..-1,
        )
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // A word waiting its turn is dimmed by its own alpha, not by paper laid
    // over it. A paper mask is a rectangle on a word's box, and a QCF glyph
    // inks past that box — so the mask left the overhang at full strength,
    // which on a dark leaf reads as a white peak stuck to the letter. Alpha
    // takes the glyph exactly as it is drawn, tail and all, and cannot reach
    // the word beside it. Read in the layer block, so the dim animates in the
    // draw phase without recomposing the leaf.
    val recessAlpha = {
        val pack = packs[token.surahId to token.ayah]
        val motion = pack?.motions?.getOrNull(token.word.position - 1)
        when {
            !liveInk || pack == null -> 1f
            // No motion: a whole verse waiting its turn, dimmed as a block.
            motion == null -> (1f - pack.recessCover.value).coerceIn(0f, 1f)
            // The wash owns ink strength while a word is revealing; the lyric
            // alpha applies once settled, and before the word has begun — which
            // is what dims the words still ahead of the voice inside the verse
            // being recited, and what keeps a waiting word off the layered
            // wash path entirely (see wordSweep).
            motion.isActive || (motion.sweepProgress > 0f && motion.sweepProgress < 1f) -> 1f
            else -> motion.lyricAlpha
        }
    }
    // The reveal of the one word being recited, read in the draw phase.
    //
    // A word that has not begun reports a finished wash on purpose: the wash
    // opens an offscreen layer for as long as it is running, and a leaf holds
    // ~150 words. Their waiting dim is a flat alpha instead (see recessAlpha),
    // so only the word actually under the voice pays for a layer.
    val wordSweep = {
        val motion = packs[token.surahId to token.ayah]
            ?.motions?.getOrNull(token.word.position - 1)
        when {
            motion == null || motion.repeat -> 1f
            motion.isActive || motion.sweepProgress > 0f -> motion.sweepProgress
            else -> 1f
        }
    }
    val wordFeather = {
        packs[token.surahId to token.ayah]
            ?.motions?.getOrNull(token.word.position - 1)
            ?.washFeather ?: InkEngine.tuning.washFeather
    }
    val blooms = {
        if (!liveInk) {
            emptyList()
        } else {
            val pack = packs[token.surahId to token.ayah]
            val motion = pack?.motions?.getOrNull(token.word.position - 1)
            if (pack == null || motion == null ||
                (!motion.isActive && motion.ink.state == InkEngine.State.Upcoming)
            ) {
                // Waiting words carry no bloom at all now: their dim is the
                // node's own alpha, so nothing rectangular is ever drawn over
                // a glyph.
                emptyList()
            } else {
                buildShapedBlooms(
                    motions = listOf(motion),
                    words = listOf(token.word),
                    rendered = rendered,
                    palette = palette,
                    glintInk = glintInk,
                    markAlpha = { pack.markAlpha.value },
                    recessCover = { pack.recessCover.value },
                    flashWordPosition = null,
                    searchHitWash = pack.searchHitWash,
                    waslInk = palette.fullInkColor,
                    // The reveal is washed onto this word's own layer below,
                    // where it follows the letterform. Paper laid over the line
                    // box cannot: it left tails, high marks and the circled
                    // number standing at full strength beside faint letters.
                    baseReveal = false,
                )
            }
        }
    }
    BasicText(
        text = word,
        style = style,
        maxLines = 1,
        softWrap = false,
        // Never slice a glyph at its box. The circled ayah mark is the last
        // glyph of a verse-closing word and its medallion inks wider than its
        // advance, so clipping to the measured box cuts the number in half —
        // which is exactly what the line end showed.
        overflow = TextOverflow.Visible,
        modifier = Modifier
            // Only while there is ink to animate. A graphicsLayer marks its
            // node transformed, and Compose then keeps that node's rect index
            // up to date by walking its whole subhierarchy — profiled on
            // device, insertOrUpdateTransformedNodeSubhierarchy is the single
            // hottest method in the reader, and a leaf hands it ~150 nodes.
            // Reading and turning pages need none of it: the alpha is 1 and
            // the wash is already gated the same way (see [mushafLineInk]).
            .then(
                if (!liveInk) {
                    Modifier
                } else {
                    // Both washes work on the word's own drawing, so the ink is
                    // masked by its own coverage — a tail, a mark, the circled
                    // number all fade with the letter they belong to, and no
                    // edge can fall across a stroke. This is the same pair the
                    // scrolling reader uses; the leaf used to lay paper over a
                    // rectangle instead, which is what showed as clipping.
                    Modifier
                        .glyphLayerAlpha { recessAlpha() }
                        .letterFadeIn(
                            progress = { wordSweep() },
                            rtl = true,
                            restingAlpha = InkEngine.State.Upcoming.inkAlpha(),
                            feather = wordFeather(),
                        )
                },
            )
            .mushafLineInk(
                liveInk = liveInk,
                blooms = blooms,
                layout = { layoutResult },
                coverPad = 0.dp,
            )
            .wordTapTarget(
                words = listOf(token.word),
                ranges = rendered.wordRanges,
                layoutResult = layoutResult,
                hitSlopPx = hitSlopPx,
                onWordClick = { onWordClick(token) },
                onWordLongClick = { onWordLongClick(token) },
                onMiss = { onAyahClick(token) },
            ),
        onTextLayout = { layoutResult = it },
    )
}

private fun Modifier.mushafLineInk(
    liveInk: Boolean,
    blooms: () -> List<ShapedWordBloom>,
    layout: () -> TextLayoutResult?,
    /** Zero for a per-word node, whose neighbours are other nodes to paint over. */
    coverPad: Dp = PaperCoverPad,
): Modifier = if (!liveInk) {
    this
} else {
    shapedWordBloom(
        blooms = blooms,
        layout = layout,
        rtl = true,
        feather = InkEngine.tuning.washFeather,
        coverPad = coverPad,
    )
}

private fun qcfRendered(
    line: MushafLine,
    ink: androidx.compose.ui.graphics.Color,
    markInk: androidx.compose.ui.graphics.Color,
    gapSpacing: TextUnit = 0.sp,
): RenderedLineText {
    val ranges = ArrayList<IntRange>(line.tokens.size)
    val text = buildAnnotatedString {
        line.tokens.forEach { token ->
            val raw = token.word.qcfV2
            val word = if (raw.isNotEmpty()) qcfWordGlyphs(raw) else token.word.arabic
            val mark = if (raw.isNotEmpty()) qcfTrailingMark(raw) else ""
            val start = length
            withStyle(SpanStyle(color = ink)) { append(word) }
            ranges += start until length
            if (mark.isNotEmpty()) {
                withStyle(SpanStyle(color = markInk)) { append(mark) }
            }
        }
    }
    return RenderedLineText(text = text, wordRanges = ranges, markRange = 0..-1)
}

private fun buildMushafLine(
    line: MushafLine,
    ink: androidx.compose.ui.graphics.Color,
    markInk: androidx.compose.ui.graphics.Color,
    fontSize: TextUnit,
    gapSpacing: TextUnit,
): RenderedLineText {
    val ranges = ArrayList<IntRange>(line.tokens.size)
    val text = buildAnnotatedString {
        line.tokens.forEachIndexed { index, token ->
            val start = length
            withStyle(SpanStyle(color = ink)) {
                append(token.word.arabic)
            }
            ranges += start until length
            if (token.endsAyah) {
                withStyle(
                    SpanStyle(
                        color = markInk,
                        fontSize = fontSize * MARK_SIZE_RATIO,
                    ),
                ) {
                    append(formatMushafAyahMark(token.ayah))
                }
            }
            if (index < line.tokens.lastIndex) {
                withStyle(SpanStyle(letterSpacing = gapSpacing)) {
                    append(" ")
                }
            }
        }
    }
    return RenderedLineText(text = text, wordRanges = ranges, markRange = 0..-1)
}

private fun buildLineBlooms(
    line: MushafLine,
    packs: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
    rendered: RenderedLineText,
    palette: WordInkPalette,
    glintInk: androidx.compose.ui.graphics.Color?,
) = buildList {
    line.tokens.forEachIndexed { tokenIndex, token ->
        val pack = packs[token.surahId to token.ayah] ?: return@forEachIndexed
        val range = rendered.wordRanges.getOrNull(tokenIndex) ?: return@forEachIndexed
        val motion = pack.motions.getOrNull(token.word.position - 1)
        if (motion == null) {
            val cover = pack.recessCover.value
            if (cover > 0f) {
                add(
                    ShapedWordBloom.UpcomingDim(
                        range = range,
                        paper = palette.paperColor,
                        coverAlpha = cover,
                    ),
                )
            }
            return@forEachIndexed
        }
        val slice = RenderedLineText(
            text = rendered.text,
            wordRanges = listOf(range),
            markRange = 0..-1,
        )
        addAll(
            buildShapedBlooms(
                motions = listOf(motion),
                words = listOf(token.word),
                rendered = slice,
                palette = palette,
                glintInk = glintInk,
                markAlpha = { pack.markAlpha.value },
                recessCover = { pack.recessCover.value },
                flashWordPosition = null,
                searchHitWash = pack.searchHitWash,
                waslInk = palette.fullInkColor,
            ),
        )
    }
}
