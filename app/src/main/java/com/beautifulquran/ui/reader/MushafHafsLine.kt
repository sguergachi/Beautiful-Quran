package com.beautifulquran.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicText
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
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.buildMushafQcfLine
import com.beautifulquran.domain.mushafGapSpacingPx
import com.beautifulquran.domain.mushafLineJustifies
import com.beautifulquran.domain.MUSHAF_WORD_GAP_EM
import com.beautifulquran.domain.MUSHAF_MAX_LINE_SCALE
import com.beautifulquran.domain.MUSHAF_MIN_LINE_SCALE
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

/**
 * Measured geometry for one mushaf line, cacheable across leaf compositions.
 *
 * Swiping back to a visited leaf re-composes its lines, and the measure is
 * not cheap: ink profiles render each word to a bitmap, the joins derive
 * from them, and the fit runs a bisection per line — all on the UI thread,
 * mid-swipe. The face LRU evicts typefaces (and with them the profile
 * cache), so this cache holds the geometry strongly, keyed by what the
 * geometry actually depends on: the page's own face, the line's words,
 * the size, and the measure. Line number alone is not the line — display
 * reflow rebuilds row N from a different token list once the face lands,
 * and reusing the previous row's flush fit stretched three words across
 * the measure (a river down the leaf).
 */
internal class MushafLineGeometry(
    val cells: List<MushafCell>,
    val joinsEm: List<MushafInkJoin>,
    val fit: MushafLineFit,
)

private data class MushafLineGeometryKey(
    val page: Int,
    val line: Int,
    val fontPxBits: Int,
    val measureWidthPxBits: Int,
    val typefaceId: Int,
    val contentKey: Int,
)

private val lineGeometryCache =
    object : LinkedHashMap<MushafLineGeometryKey, MushafLineGeometry>(64, 0.75f, true) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<MushafLineGeometryKey, MushafLineGeometry>,
    ) =
        size > 512
}

/** Identity of the words a display row is actually setting. */
internal fun mushafLineContentKey(line: MushafLine): Int {
    var h = line.tokens.size
    line.tokens.forEach { token ->
        h = 31 * h + token.surahId
        h = 31 * h + token.ayah
        h = 31 * h + token.word.position
    }
    return h
}

private fun lineGeometryKey(
    page: Int,
    line: MushafLine,
    pageTypeface: android.graphics.Typeface?,
    fontPx: Float,
    measureWidthPx: Float,
): MushafLineGeometryKey = MushafLineGeometryKey(
    page = page,
    line = line.number,
    fontPxBits = fontPx.toRawBits(),
    measureWidthPxBits = measureWidthPx.toRawBits(),
    typefaceId = System.identityHashCode(pageTypeface),
    contentKey = mushafLineContentKey(line),
)

@Composable
private fun lineGeometry(
    page: Int,
    line: MushafLine,
    pageTypeface: android.graphics.Typeface?,
    linePx: Float,
    measureWidthPx: Float,
    justify: Boolean,
): MushafLineGeometry {
    val key = lineGeometryKey(page, line, pageTypeface, linePx, measureWidthPx)
    return remember(key) {
        lineGeometryCache.getOrPut(key) {
            com.beautifulquran.DevProfiling.trace("lineGeometryMiss") {
                val texts = mushafLineTexts(line)
                val glyphs = texts.map { it.text }
                val rawCells = mushafLineCells(glyphs, pageTypeface, linePx, condense = 1f)
                val joinsEm = mushafLineJoins(texts, pageTypeface)
                val fit = if (!justify) {
                    MushafLineFit(
                        scale = 1f,
                        gapPx = MUSHAF_WORD_GAP_EM * linePx,
                        flush = false,
                    )
                } else if (joinsEm.size == (rawCells.size - 1).coerceAtLeast(0) && joinsEm.isNotEmpty()) {
                    mushafInkLineFit(
                        inkWidthPx = rawCells.sumOf { it.inkWidth.toDouble() }.toFloat(),
                        joins = joinsEm,
                        measureWidthPx = measureWidthPx,
                        fontPx = linePx,
                    )
                } else {
                    mushafLineFit(
                        inkWidthPx = rawCells.sumOf { it.inkWidth.toDouble() }.toFloat(),
                        gapCount = (rawCells.size - 1).coerceAtLeast(0),
                        measureWidthPx = measureWidthPx,
                        fontPx = linePx,
                    )
                }
                MushafLineGeometry(rawCells, joinsEm, fit)
            }
        }
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
    val geometry = lineGeometry(page, line, pageTypeface, linePx, measureWidthPx, justify)
    val rawCells = geometry.cells
    val joinsEm = geometry.joinsEm
    val fit = geometry.fit
    val condense = fit.scale
    val texts = remember(line) { mushafLineTexts(line) }
    val glyphs = remember(texts) { texts.map { it.text } }
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
    val cells = remember(glyphs, pageTypeface, linePx, condense) {
        if (condense == 1f) rawCells else mushafLineCells(glyphs, pageTypeface, linePx, condense)
    }
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
    Layout(
        modifier = modifier
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
            },
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
                )
                val mark = token.word.qcfV2.takeIf { it.isNotEmpty() }
                    ?.let { qcfTrailingMark(it, token.endsAyah) }.orEmpty()
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

internal fun mushafAyahMarkInkAlpha(liveInk: Boolean, markAlpha: Float?): Float =
    if (liveInk && markAlpha != null) markAlpha.coerceIn(0f, 1f) else 1f

/** A cell's advance and where its ink sits inside it, in px. */
internal class MushafCell(val advance: Float, val inkLeft: Float, val inkRight: Float) {
    val inkWidth: Float get() = (inkRight - inkLeft).coerceAtLeast(0f)

    /** The same cell with its letterforms narrowed — scaleX scales ink and
     * advance alike, so the measurements scale with them. */
    fun scaled(k: Float) = MushafCell(advance * k, inkLeft * k, inkRight * k)
}

/**
 * What the line draws, one string per node.
 *
 * One entry per drawn child, empty or not: the layout emits a node for every
 * word whether or not it carries glyphs, and a list that skipped the empty ones
 * put every following word on the wrong origin — the line then ended short of
 * its own margin by about a word.
 */
private fun mushafLineTexts(line: MushafLine): List<MushafLineText> {
    val out = ArrayList<MushafLineText>(line.tokens.size + 4)
    line.tokens.forEach { token ->
        val raw = token.word.qcfV2
        out += MushafLineText(
            text = if (raw.isNotEmpty()) {
                qcfWordGlyphs(raw, token.endsAyah)
            } else {
                token.word.arabic
            },
            mark = false,
        )
        val mark = if (raw.isNotEmpty()) qcfTrailingMark(raw, token.endsAyah) else ""
        if (mark.isNotEmpty()) {
            out += MushafLineText(text = mark, mark = true)
        }
    }
    return out
}

/**
 * One cell of a line, and whether it is a verse mark rather than a word.
 *
 * The mark is a cell of its own — see docs/QURAN_TYPOGRAPHY.md rule 8 — but it
 * is not a word, and the joins either side of it are not word joins: see
 * MUSHAF_MARK_WHITE_K.
 */
private class MushafLineText(val text: String, val mark: Boolean)

internal fun mushafLineCells(
    texts: List<String>,
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
    return texts.map { text ->
        if (text.isEmpty()) {
            MushafCell(0f, 0f, 0f)
        } else {
            val advance = paint.measureText(text)
            paint.getTextBounds(text, 0, text.length, bounds)
            MushafCell(advance, bounds.left.toFloat(), bounds.right.toFloat())
        }
    }
}

/**
 * What each join between two words offers, in em — the white it already
 * carries, and the closest the two come.
 *
 * Held in em because it is a property of the two letterforms and nothing else:
 * a horizontal narrowing scales it exactly as it scales the ink either side,
 * and the type size scales it too, so the raster work is done once per glyph
 * for the whole book. See [MushafInkProfiles].
 */
private fun mushafLineJoins(
    texts: List<MushafLineText>,
    typeface: android.graphics.Typeface?,
): List<MushafInkJoin> {
    if (texts.size < 2 || typeface == null) return emptyList()
    val profiles = texts.map { MushafInkProfiles.of(typeface, it.text) }
    return List(texts.size - 1) { i ->
        mushafInkJoin(profiles[i], profiles[i + 1], texts[i].mark || texts[i + 1].mark)
    }
}

/** Rasterizes a leaf's ink joins before that leaf reaches composition. */
internal fun warmMushafInkProfiles(
    page: MushafPage?,
    typeface: android.graphics.Typeface?,
) {
    if (page == null || typeface == null) return
    page.lines.forEach { line ->
        mushafLineTexts(line).forEach { MushafInkProfiles.of(typeface, it.text) }
    }
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
    /** What each join already carries, in em, fore-edge first. */
    joins: List<MushafInkJoin> = emptyList(),
    /** How many px the line's em comes to, once narrowed. */
    emPx: Float = 0f,
): FloatArray {
    val n = minOf(cells.size, count)
    val origins = FloatArray(count)
    if (n == 0) return origins
    val inkTotal = (0 until n).sumOf { cells[it].inkWidth.toDouble() }.toFloat()
    // A flush line divides what is actually left, so it ends on the margin
    // whatever the rasteriser did with the letterforms. The fit has already
    // chosen the scale that makes this residue the space it wants.
    val spread = if (fit.flush && n > 1) {
        (width - inkTotal).coerceAtLeast(0f)
    } else {
        fit.gapPx * (n - 1)
    }
    val steps = mushafJoinSteps(joins, n, spread, emPx)
    // A flush line starts at the fore-edge; a short one is centred on the page,
    // the way a chapter's closing line is printed.
    var inkRight = if (fit.flush) {
        width
    } else {
        width - ((width - (inkTotal + spread)) / 2f).coerceAtLeast(0f)
    }
    for (i in 0 until n) {
        origins[i] = inkRight - cells[i].inkRight
        inkRight -= cells[i].inkWidth + steps.getOrElse(i) { 0f }
    }
    return origins
}

/**
 * How far apart to set each pair of ink boxes so that every join on the line
 * carries the same *white*, given [spread] px of paper to divide between them.
 *
 * Dividing the paper equally between the boxes is what a line of Latin type
 * does, and it is wrong here: Arabic words nest, and how much white a join
 * already holds at the moment its boxes touch varies threefold along one line.
 * So the paper is divided to level the white instead — every join set to the
 * same measure `level` of it — except where a join's two words run alongside
 * each other and would weld at that setting. Those are held at their own floor
 * and the rest of the line absorbs the difference.
 *
 * `level` is found by bisection because the floors make the total a piecewise
 * function of it; twenty-four halvings settle it well inside a pixel. It is
 * free to go negative, and must be: a pair that nests deeply carries a third of
 * an em of white with its boxes already touching, so levelling a line down to
 * an ordinary word space means drawing that pair's boxes *through* each other.
 * Held at zero, those joins could never give anything back, and the whole of a
 * dense line's shortfall fell on the pairs that had least to spare.
 */
private fun mushafJoinSteps(
    joins: List<MushafInkJoin>,
    n: Int,
    spread: Float,
    emPx: Float,
): FloatArray {
    val steps = FloatArray(n)
    if (n < 2) return steps
    if (joins.size < n - 1 || emPx <= 0f) {
        val even = spread / (n - 1)
        for (i in 0 until n - 1) steps[i] = even
        return steps
    }
    // In em, like the level itself: a verse mark's white bends at a knee set in
    // em, so the bisection cannot be run in pixels and read the same.
    val active = if (joins.size == n - 1) joins else joins.subList(0, n - 1)
    val floors = FloatArray(n - 1) { active[it].floorEm * emPx }
    val spreadEm = spread / emPx
    var floorTotal = 0f
    for (i in 0 until n - 1) floorTotal += floors[i]
    if (floorTotal >= spread) {
        // The line is denser than bare clearance allows — the fit condenses to
        // avoid this, but it stops at MUSHAF_MIN_LINE_SCALE and a line may
        // arrive here anyway. What is missing then comes off each join in
        // proportion to the white it holds of its own, so the joins that have
        // none keep what little the floor gave them and no two words weld.
        var slackTotal = 0f
        for (i in 0 until n - 1) slackTotal += active[i].closest
        val short = floorTotal - spread
        for (i in 0 until n - 1) {
            steps[i] = floors[i] - if (slackTotal > 0f) {
                short * active[i].closest / slackTotal
            } else {
                short / (n - 1)
            }
        }
        return steps
    }
    val level = mushafWhiteLevel(active, spreadEm)
    for (i in 0 until n - 1) {
        val join = active[i]
        steps[i] = maxOf(join.whiteAt(level) - join.paper, join.floorEm) * emPx
    }
    return steps
}

/** What [joins] take between them when the line levels at [level], in em. */
private fun mushafWhiteSum(joins: List<MushafInkJoin>, level: Float): Float {
    var sum = 0f
    for (join in joins) sum += maxOf(join.whiteAt(level) - join.paper, join.floorEm)
    return sum
}

/**
 * The level of white a line settles at when [spreadEm] em of paper is divided
 * between [joins] — the same bisection [mushafJoinSteps] runs, asked in em and
 * before anything is placed, so the fit can ask what a setting would cost.
 */
private fun mushafWhiteLevel(joins: List<MushafInkJoin>, spreadEm: Float): Float {
    if (joins.isEmpty()) return 0f
    // Every join sits on its floor at `low`, so the level the line wants is at
    // or above it. It is not bounded from above by anything as simple: a mark's
    // white climbs more slowly than the level does, and past its knee slower
    // still, so the bracket is opened until it holds rather than solved for.
    var paperTotal = 0f
    var low = Float.MAX_VALUE
    for (join in joins) {
        paperTotal += join.paper
        low = minOf(low, join.levelFor(join.paper + join.floorEm))
    }
    if (mushafWhiteSum(joins, low) >= spreadEm) return low
    var high = maxOf(low + 1f, spreadEm + paperTotal)
    var opens = 0
    while (mushafWhiteSum(joins, high) < spreadEm && opens < 32) {
        high = low + (high - low) * 2f
        opens++
    }
    repeat(24) {
        val mid = (low + high) / 2f
        if (mushafWhiteSum(joins, mid) < spreadEm) low = mid else high = mid
    }
    return (low + high) / 2f
}

/**
 * How far the line's letters are narrowed, and whether it reaches its margins.
 *
 * The fit this replaces reserved a fixed word space per *gap between ink
 * boxes*, which is not a quantity the eye can see: on a join where the next
 * word tucks under an open ن, a fifth of an em between the boxes is half an em
 * of visible white, and on a join where two flat ends meet it is a fifth. So
 * lines were condensed to buy space they did not need, and every line paid for
 * it in letterform — measured over forty pages, only about half a page's lines
 * came through at their own width.
 *
 * Reserving the white each join actually needs instead, the same forty pages
 * leave seven lines in ten untouched, and the ones that do give way give up
 * less. The order is still a compositor's: the white is guaranteed first and
 * the letters yield to it, never the reverse.
 */
internal fun mushafInkLineFit(
    inkWidthPx: Float,
    joins: List<MushafInkJoin>,
    measureWidthPx: Float,
    fontPx: Float,
): MushafLineFit {
    if (inkWidthPx <= 0f || measureWidthPx <= 0f || fontPx <= 0f || joins.isEmpty()) {
        return MushafLineFit(scale = 1f, gapPx = MUSHAF_WORD_GAP_EM * fontPx, flush = false)
    }
    val ink = inkWidthPx / fontPx
    val measure = measureWidthPx / fontPx
    var tight = 0f
    for (join in joins) tight += join.fitFloorEm
    if (ink + tight > measure) {
        // Denser than the line's own white will allow: narrow the letters until
        // it is not, and stop at the point where narrowing itself starts to
        // read as a fault.
        val scale = (measure / (ink + tight)).coerceAtLeast(MUSHAF_MIN_LINE_SCALE)
        return MushafLineFit(scale = scale, gapPx = 0f, flush = true)
    }
    // Room to spare. Level the white as far as a line is set, and see what the
    // letters would have to do to take up whatever is still left.
    var opened = 0f
    for (join in joins) {
        opened += maxOf(join.whiteAt(MUSHAF_MAX_WHITE_LEVEL_EM) - join.paper, join.floorEm)
    }
    val needed = measure / (ink + opened)
    if (needed <= 1f) return MushafLineFit(scale = 1f, gapPx = 0f, flush = true)
    if (needed <= MUSHAF_MAX_LINE_SCALE) {
        return MushafLineFit(scale = needed, gapPx = 0f, flush = true)
    }
    // Neither the letters at their bound nor the white at its own reaches the
    // margin alone. Take both, and if the white still has to open past what
    // reads as one line of text, leave the line short and centred instead —
    // which is how the print sets a line that will not fill.
    val stretched = mushafWhiteLevel(joins, measure / MUSHAF_MAX_LINE_SCALE - ink)
    if (stretched <= MUSHAF_STRETCH_WHITE_LEVEL_EM) {
        return MushafLineFit(scale = MUSHAF_MAX_LINE_SCALE, gapPx = 0f, flush = true)
    }
    return MushafLineFit(scale = 1f, gapPx = MUSHAF_WORD_GAP_EM * fontPx, flush = false)
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
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
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
            motion.isActive || (motion.sweepProgress > 0f && motion.sweepProgress < 1f) -> 1f
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
                    words = listOf(token.word),
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
        onTextLayout = { layoutResult = it },
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
                flashWordPositions = pack.searchHitWordPositions,
                searchHitWash = pack.searchHitWash,
                searchFocusPositions = pack.searchFocusPositions,
                searchBackgroundAlpha = { pack.searchBackgroundAlpha.value },
                waslInk = palette.fullInkColor,
            ),
        )
    }
}
