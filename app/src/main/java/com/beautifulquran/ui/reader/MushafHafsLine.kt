package com.beautifulquran.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.beautifulquran.domain.mushafGapSpacingPx
import com.beautifulquran.domain.mushafLineJustifies
import com.beautifulquran.domain.qcfTrailingMark
import com.beautifulquran.domain.qcfWordGlyphs
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.MushafFontFamily
import com.beautifulquran.ui.theme.PaperCoverPad
import com.beautifulquran.ui.theme.ShapedWordBloom
import com.beautifulquran.ui.theme.glyphLayerAlpha
import com.beautifulquran.ui.theme.glowLayerBleed
import com.beautifulquran.ui.theme.letterFadeIn
import com.beautifulquran.ui.theme.shapedWordBloom

private const val MARK_SIZE_RATIO = 20f / 30f

/** Keeps a newly active pack recessed until composition attaches its wash. */
internal fun mushafLayerTransitionAlpha(
    hasWashLayer: Boolean,
    currentPackHasMotions: Boolean,
    resolvedAlpha: Float,
): Float = if (!hasWashLayer && currentPackHasMotions) {
    InkEngine.State.Upcoming.inkAlpha()
} else {
    resolvedAlpha
}

/**
 * One Madinah line. QCF V2 is one handwritten word-glyph per token
 * (no U+0020). Leftover width is [Spacer] weights so the line fills
 * the page like the printed 15-line mushaf. Unicode fallback uses
 * Digital Khatt. Ink is [shapedWordBloom].
 */
@Composable
internal fun MushafHafsLine(
    line: MushafLine,
    /** The leaf this line sits on — the line geometry cache's key. */
    page: Int,
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
    /** Only a chapter ending or an opening medallion may stand short. */
    allowShort: Boolean = false,
    pageFont: FontFamily? = null,
    modifier: Modifier = Modifier,
) {
    val palette = rememberWordInkPalette()
    val ayahMarkInk = LocalQuranAccents.current.gold
    val glintInk = LocalQuranAccents.current.glintInk
    val useQcf = pageFont != null && line.tokens.any { it.word.qcfV2.isNotEmpty() }
    if (useQcf) {
        MushafQcfPageLine(
            line = line,
            page = page,
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
            allowShort = allowShort,
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
                allowShort = allowShort,
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
        // Fixed by the line and its ranges, so they are built once rather
        // than per token on every frame of the wash. See [LineBloomSlice].
        val slices = remember(line, rendered) { lineBloomSlices(line, rendered) }
        val blooms = {
            if (!liveInk) {
                emptyList()
            } else {
                buildLineBlooms(
                    slices = slices,
                    packs = packs,
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
private fun lineGeometry(
    page: Int,
    line: MushafLine,
    pageTypeface: android.graphics.Typeface?,
    linePx: Float,
    measureWidthPx: Float,
    justify: Boolean,
    allowShort: Boolean,
): MushafLineGeometry {
    val key = lineGeometryKey(page, line, pageTypeface, linePx, measureWidthPx, allowShort)
    return remember(key) {
        mushafLineGeometry(page, line, pageTypeface, linePx, measureWidthPx, justify, allowShort)
    }
}

@Composable
private fun MushafQcfPageLine(
    line: MushafLine,
    page: Int,
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
    allowShort: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val justify = mushafLineJustifies(line.tokens.size)
    val measurer = rememberTextMeasurer()
    // The book is set at one size (see MUSHAF_DESIGN_LINE_EM). A line whose
    // glyph run still runs past the measure — a few dozen in the whole mushaf —
    // is set that little bit tighter, so one long line never drags its page's
    // type down with it.
    val linePx = with(density) { fontSize.toPx() }
    // What the words actually mark, measured from the page's own face and
    // unscaled. Everything about the line's fit follows from this.
    // Cells, joins and fit come from the process-wide geometry cache: a leaf
    // re-composing mid-swipe (returning to a visited page) re-measures on
    // the UI thread otherwise — ink profiles render each word to a bitmap,
    // and the fit runs a bisection per line. See [lineGeometry].
    val geometry = lineGeometry(page, line, pageTypeface, linePx, measureWidthPx, justify, allowShort)
    val joinsEm = geometry.joinsEm
    val fit = geometry.fit
    val condense = fit.scale
    val texts = remember(line) { mushafLineTexts(line) }
    // Condensed, never resized. A line brought inside the measure keeps its
    // height, weight and colour — the page still reads as one hand — where a
    // line set at a different size reads as a fault.
    val style = remember(fontSize, pageFont, condense) {
        mushafQcfLineStyle(fontSize, pageFont, condense)
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
    val cells = geometry.drawnCells
    // A narrowing scales a join's pocket exactly as it scales the strokes
    // either side of it, so the em measurement holds at any width and only the
    // size of the em changes.
    val emPx = linePx * condense
    // Which token each cell belongs to — words and verse marks both live in
    // the cells, and a tap on a mark is a tap on its verse's last word.
    val cellToken = remember(texts) {
        IntArray(texts.size).also { map ->
            var cell = 0
            line.tokens.forEachIndexed { ti, token ->
                map[cell++] = ti
                val mark = token.word.qcfV2.takeIf { it.isNotEmpty() }
                    ?.let { qcfTrailingMark(it, token.endsAyah) }.orEmpty()
                if (mark.isNotEmpty()) map[cell++] = ti
            }
        }
    }
    // The placed origins, captured by the measure below and read by the
    // line's tap handler: one gesture handler per line, not one per word —
    // a leaf carrying a pointer-input node per word handed the input system
    // four hundred and fifty hit-test targets per leaf, and swiping paid
    // for every one of them.
    val placedOrigins = remember(line) { arrayOfNulls<FloatArray>(1) }
    fun tokenAt(x: Float): MushafToken? {
        val origins = placedOrigins[0] ?: return null
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in cells.indices) {
            val cell = cells[i]
            val origin = origins.getOrElse(i) { 0f }
            val left = origin + minOf(cell.inkLeft, cell.inkRight)
            val right = origin + maxOf(cell.inkLeft, cell.inkRight)
            val d = when {
                x in left..right -> 0f
                x < left -> left - x
                else -> x - right
            }
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        if (best < 0) return null
        val ti = cellToken.getOrElse(best) { -1 }
        return line.tokens.getOrNull(ti)
    }
    val tappable = modifier
        .fillMaxWidth()
        .pointerInput(line, cells, onWordClick, onWordLongClick, onAyahClick) {
            detectTapGestures(
                onTap = { pos ->
                    val token = tokenAt(pos.x)
                    if (token != null) onWordClick(token) else onAyahClick(line.tokens.first())
                },
                onLongPress = { pos ->
                    tokenAt(pos.x)?.let(onWordLongClick)
                },
            )
        }
    // A line with no ink to animate is one node: see [mushafStaticQcfLine].
    // A cell big enough to be drawn as a path keeps the word nodes, since it
    // has to be drawn live over the leaf's picture (see mushafCellDrawnAsPath).
    val inkAtRest by remember(line, packs) { derivedStateOf { mushafLineInkAtRest(line, packs) } }
    val oneNode = (!liveInk || inkAtRest) && cells.size == texts.size && cells.none(::mushafCellDrawnAsPath)
    if (oneNode) {
        val onPlaced = remember(placedOrigins) { { origins: FloatArray -> placedOrigins[0] = origins } }
        Spacer(
            tappable.mushafStaticQcfLine(
                texts = texts,
                wordStyle = wordStyle,
                markStyle = markStyle,
                geometry = geometry,
                emPx = emPx,
                onPlaced = onPlaced,
            ),
        )
        return
    }
    Layout(
        modifier = tappable,
        content = {
            var cell = 0
            line.tokens.forEach { token ->
                val wordCell = cells.getOrNull(cell++)
                MushafQcfWord(
                    token = token,
                    liveOverPicture = wordCell != null && mushafCellDrawnAsPath(wordCell),
                    style = wordStyle,
                    packs = packs,
                    liveInk = liveInk,
                    palette = palette,
                    ayahMarkInk = ayahMarkInk,
                    glintInk = glintInk,
                )
                val mark = token.word.qcfV2.takeIf { it.isNotEmpty() }
                    ?.let { qcfTrailingMark(it, token.endsAyah) }.orEmpty()
                if (mark.isNotEmpty()) cell++
                if (mark.isNotEmpty()) {
                    // The mark is its own cell, so it sits outside the word's
                    // ink node. markAlpha already expresses the verse's focus
                    // state; multiplying it by the matching recess would dim an
                    // upcoming mark twice (22% → 5%). Read it in the layer block
                    // so the animation does not recompose the leaf.
                    val markInkAlpha = {
                        val pack = packs[token.surahId to token.ayah]
                        mushafAyahMarkInkAlpha(liveInk, pack?.markAlpha?.value)
                    }
                    MushafWordText(
                        text = mark,
                        style = markStyle,
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
        val origins = mushafCellOrigins(
            cells = cells,
            count = placeables.size,
            width = width.toFloat(),
            fit = fit,
            joins = joinsEm,
            emPx = emPx,
        )
        placedOrigins[0] = origins
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                p.place(origins.getOrElse(i) { 0f }.roundToInt(), (height - p.height) / 2)
            }
        }
    }
}

/**
 * Whether every verse on [line] is inked exactly as a leaf without live ink
 * inks it: no wash running, no recess, and its circled mark at full strength.
 * That is every verse of a leaf at rest with no voice on it, and every verse
 * the voice has finished with once its mark has come up. [MushafQcfWord] draws
 * nothing then but the word, so the line can be drawn by one node, and goes
 * back to its word nodes the frame a verse on it takes ink again.
 */
internal fun mushafLineInkAtRest(
    line: MushafLine,
    packs: Map<Pair<Int, Int>, AyahInkPack>,
): Boolean = line.tokens.all { token ->
    val pack = packs[token.surahId to token.ayah]
    pack == null || (
        pack.motions.isEmpty() && !pack.wholeAyahRecess &&
            (!token.endsAyah || pack.markAlpha.value >= 1f)
        )
}

internal fun mushafAyahMarkInkAlpha(liveInk: Boolean, markAlpha: Float?): Float =
    if (liveInk && markAlpha != null) markAlpha.coerceIn(0f, 1f) else 1f

@Composable
private fun MushafQcfWord(
    token: MushafToken,
    /** Kept out of the leaf's turn picture (see [mushafCellDrawnAsPath]). */
    liveOverPicture: Boolean,
    style: TextStyle,
    packs: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
    liveInk: Boolean,
    palette: WordInkPalette,
    ayahMarkInk: androidx.compose.ui.graphics.Color,
    glintInk: androidx.compose.ui.graphics.Color?,
) {
    val raw = token.word.qcfV2
    val word = if (raw.isNotEmpty()) {
        qcfWordGlyphs(raw, token.endsAyah)
    } else {
        token.word.arabic
    }
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
    // Single-element and fixed for this node: only the motion changes per frame.
    val wordAsList = remember(token.word) { listOf(token.word) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Paper: the shared ink pipeline draws this word while it is live, in place
    // of the whole wash chain below (docs/PAPER_INK.md).
    val paperNode = rememberPaperInkNode(
        paperInkPlacement(token.surahId, token.ayah, token.word.position),
    )
    val packKey = remember(token.surahId, token.ayah) { token.surahId to token.ayah }
    val packState = remember(packs, packKey) { derivedStateOf { packs[packKey] } }
    val pack = packState.value
    // Pack identity is a composition decision: it determines whether this
    // word owns only the cheap recess layer or the full wash chain. Animated
    // values still come from the latest pack during draw. Capturing a stale
    // pack here leaves the previous word's completed sweep visible for one
    // frame at every handoff before the new wash begins.
    // Only the verse under the voice carries wash layers. A waiting ayah needs
    // the cheaper glyph-alpha draw modifier, but never the wash/tint layers;
    // completed and manually browsed words carry neither. This keeps the page
    // progression without restoring ~150 transformed nodes to every leaf.
    val hasMotionInk = liveInk && pack?.motions?.isNotEmpty() == true
    val hasWholeAyahRecess = liveInk && pack?.wholeAyahRecess == true
    // A word waiting its turn is dimmed by its own alpha, not by paper laid
    // over it. A paper mask is a rectangle on a word's box, and a QCF glyph
    // inks past that box — so the mask left the overhang at full strength,
    // which on a dark leaf reads as a white peak stuck to the letter. Alpha
    // takes the glyph exactly as it is drawn, tail and all, and cannot reach
    // the word beside it. Read in the layer block, so the dim animates in the
    // draw phase without recomposing the leaf.
    val recessAlpha = {
        val currentPack = packState.value
        val motion = currentPack?.motions?.getOrNull(token.word.position - 1)
        val resolved = when {
            !liveInk || currentPack == null -> 1f
            // No motion: a whole verse waiting its turn, dimmed as a block.
            motion == null -> (1f - currentPack.recessCover.value).coerceIn(0f, 1f)
            // The wash owns ink strength while a word is revealing; the lyric
            // alpha applies once settled, and before the word has begun — which
            // is what dims the words still ahead of the voice inside the verse
            // being recited, and what keeps a waiting word off the layered
            // wash path entirely (see wordSweep).
            motion.paperDeparture?.active == true || motion.isActive ||
                (motion.sweepProgress > 0f && motion.sweepProgress < 1f) -> 1f
            else -> motion.lyricAlpha
        }
        mushafLayerTransitionAlpha(
            hasWashLayer = hasMotionInk,
            currentPackHasMotions = currentPack?.motions?.isNotEmpty() == true,
            resolvedAlpha = resolved,
        )
    }
    // The reveal of the one word being recited, read in the draw phase.
    //
    // A word that has not begun reports a finished wash on purpose: the wash
    // opens an offscreen layer for as long as it is running, and a leaf holds
    // ~150 words. Their waiting dim is a flat alpha instead (see recessAlpha),
    // so only the word actually under the voice pays for a layer.
    val wordSweep = {
        val motion = packState.value?.motions?.getOrNull(token.word.position - 1)
        when {
            motion == null || motion.repeat -> 1f
            motion.paperDeparture?.active == true -> motion.paperDepartureProgress
            motion.isActive || motion.sweepProgress > 0f -> motion.sweepProgress
            else -> 1f
        }
    }
    val wordFeather = {
        packState.value?.motions?.getOrNull(token.word.position - 1)
            ?.washFeather ?: InkEngine.tuning.washFeather
    }
    val blooms = {
        if (!hasMotionInk) {
            emptyList()
        } else {
            val motion = pack.motions.getOrNull(token.word.position - 1)
            if (motion == null ||
                (!motion.isActive && motion.ink.state == InkEngine.State.Upcoming)
            ) {
                // Waiting words carry no bloom at all now: their dim is the
                // node's own alpha, so nothing rectangular is ever drawn over
                // a glyph.
                emptyList()
            } else {
                buildShapedBlooms(
                    motions = listOf(motion),
                    words = wordAsList,
                    rendered = rendered,
                    palette = palette,
                    glintInk = glintInk,
                    markAlpha = { pack.markAlpha.value },
                    recessCover = { pack.recessCover.value },
                    flashWordPositions = pack.searchHitWordPositions,
                    searchHitWash = pack.searchHitWash,
                    searchFocusPositions = pack.searchFocusPositions,
                    searchBackgroundAlpha = { pack.searchBackgroundAlpha.value },
                    waslInk = palette.fullInkColor,
                    // This node draws its own paper ink (paperInkBase, above).
                    paperInk = null,
                    // The reveal is washed onto this word's own layer below,
                    // where it follows the letterform. Paper laid over the line
                    // box cannot: it left tails, high marks and the circled
                    // number standing at full strength beside faint letters.
                    baseReveal = false,
                )
            }
        }
    }
    // Set as BasicText(maxLines = 1, softWrap = false, overflow = Visible),
    // through the process-wide layout cache (see [MushafWordLayouts]). Never
    // slice a glyph at its box: the circled ayah mark is the last glyph of a
    // verse-closing word and its medallion inks wider than its advance, so
    // clipping to the measured box cuts the number in half — which is exactly
    // what the line end showed.
    MushafWordText(
        text = word,
        style = style,
        modifier = Modifier
            .then(if (liveOverPicture) Modifier.drawnLiveOverPicture() else Modifier)
            .then(
                if (hasMotionInk && palette.paperInk != null) {
                    with(paperNode) {
                        Modifier.paperInkBase(
                            motion = { packState.value?.motions?.getOrNull(token.word.position - 1) },
                            style = palette.paperInk,
                            rtl = true,
                            letterCount = remember(token.word.arabic) {
                                com.beautifulquran.domain.TajweedPacing.letterOffsets(token.word.arabic).size
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            // Only while there is ink to animate — the verse under the voice.
            // A graphicsLayer marks its node transformed, and Compose then
            // keeps that node's rect index up to date by walking its whole
            // subhierarchy — profiled on device,
            // insertOrUpdateTransformedNodeSubhierarchy is the single hottest
            // method in the reader, and a leaf handed it ~150 nodes on every
            // frame of a swipe. A static word's ink is always full, so it
            // carries no layers at all.
            .then(
                when {
                    hasMotionInk -> {
                        // Both washes work on the word's own drawing, so the
                        // ink is masked by its own coverage and no edge can
                        // fall across a tail, high mark, or circled number.
                        Modifier
                            .glyphLayerAlpha { recessAlpha() }
                            .letterFadeIn(
                                progress = { wordSweep() },
                                rtl = true,
                                restingAlpha = InkEngine.State.Upcoming.inkAlpha(),
                                feather = wordFeather(),
                                revealedAlpha = {
                                    packState.value?.motions?.getOrNull(token.word.position - 1)?.let {
                                        if (it.paperDeparture?.active == true) it.paperDepartureAlpha else 1f
                                    } ?: 1f
                                },
                                // The glimmer's halo is drawn inside this layer.
                                bleed = glowLayerBleed(
                                    if (glintInk != null) InkEngine.tuning.glintGlowRadius else 0f,
                                ),
                            )
                    }
                    hasWholeAyahRecess -> Modifier.glyphLayerAlpha { recessAlpha() }
                    else -> Modifier
                },
            )
            .then(
                if (!hasMotionInk) {
                    Modifier
                } else {
                    Modifier.mushafLineInk(
                        liveInk = true,
                        blooms = blooms,
                        layout = { layoutResult },
                        coverPad = 0.dp,
                        // The repeat and glint washes are tints of this word's
                        // own glyphs on this word's own layer, so the
                        // letterform is their mask — the same contour the
                        // first-pass wash follows. The selection-path clip
                        // belongs to a shared line, and here it squared the
                        // orange off at the advance and left every tail and
                        // high mark standing in black.
                        clipTintToRange = false,
                    )
                },
            ),
        onTextLayout = {
            layoutResult = it
            paperNode.layout = it
        },
    )
}

private fun Modifier.mushafLineInk(
    liveInk: Boolean,
    blooms: () -> List<ShapedWordBloom>,
    layout: () -> TextLayoutResult?,
    /** Zero for a per-word node, whose neighbours are other nodes to paint over. */
    coverPad: Dp = PaperCoverPad,
    /** False for a per-word node: see [Modifier.shapedWordBloom]. */
    clipTintToRange: Boolean = true,
): Modifier = if (!liveInk) {
    this
} else {
    shapedWordBloom(
        blooms = blooms,
        layout = layout,
        rtl = true,
        feather = InkEngine.tuning.washFeather,
        coverPad = coverPad,
        clipTintToRange = clipTintToRange,
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
            val word = if (raw.isNotEmpty()) {
                qcfWordGlyphs(raw, token.endsAyah)
            } else {
                token.word.arabic
            }
            val mark = if (raw.isNotEmpty()) qcfTrailingMark(raw, token.endsAyah) else ""
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

/**
 * The parts of one token's bloom that the line fixes and a frame cannot change:
 * its range, the one-word view of the line's layout, and the single-element
 * word list [buildShapedBlooms] takes.
 *
 * Rebuilt inside the draw lambda they were four objects per token on every
 * frame of the wash — for a whole line of words whose geometry had not moved
 * since the leaf was laid out.
 */
private class LineBloomSlice(
    val token: MushafToken,
    val range: IntRange,
    val rendered: RenderedLineText,
    val words: List<com.beautifulquran.data.model.Word>,
)

private fun lineBloomSlices(
    line: MushafLine,
    rendered: RenderedLineText,
): List<LineBloomSlice> = line.tokens.mapIndexedNotNull { tokenIndex, token ->
    val range = rendered.wordRanges.getOrNull(tokenIndex) ?: return@mapIndexedNotNull null
    LineBloomSlice(
        token = token,
        range = range,
        rendered = RenderedLineText(
            text = rendered.text,
            wordRanges = listOf(range),
            markRange = 0..-1,
        ),
        words = listOf(token.word),
    )
}

private fun buildLineBlooms(
    slices: List<LineBloomSlice>,
    packs: SnapshotStateMap<Pair<Int, Int>, AyahInkPack>,
    palette: WordInkPalette,
    glintInk: androidx.compose.ui.graphics.Color?,
) = buildList {
    slices.forEach { slice ->
        val token = slice.token
        val pack = packs[token.surahId to token.ayah] ?: return@forEach
        val motion = pack.motions.getOrNull(token.word.position - 1)
        if (motion == null) {
            val cover = pack.recessCover.value
            if (cover > 0f) {
                add(
                    ShapedWordBloom.UpcomingDim(
                        range = slice.range,
                        paper = palette.paperColor,
                        coverAlpha = cover,
                    ),
                )
            }
            return@forEach
        }
        addAll(
            buildShapedBlooms(
                motions = listOf(motion),
                words = slice.words,
                rendered = slice.rendered,
                palette = palette,
                glintInk = glintInk,
                markAlpha = { pack.markAlpha.value },
                recessCover = { pack.recessCover.value },
                flashWordPositions = pack.searchHitWordPositions,
                searchHitWash = pack.searchHitWash,
                searchFocusPositions = pack.searchFocusPositions,
                searchBackgroundAlpha = { pack.searchBackgroundAlpha.value },
                waslInk = palette.fullInkColor,
            ),
        )
    }
}
