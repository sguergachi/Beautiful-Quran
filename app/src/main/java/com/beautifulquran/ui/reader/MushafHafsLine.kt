package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import com.beautifulquran.domain.MUSHAF_LINE_EM
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafToken
import com.beautifulquran.domain.buildMushafQcfLine
import com.beautifulquran.domain.mushafGapSpacingPx
import com.beautifulquran.domain.mushafLineJustifies
import com.beautifulquran.domain.mushafLineCondense
import com.beautifulquran.domain.qcfTrailingMark
import com.beautifulquran.domain.qcfWordGlyphs
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.MushafFontFamily
import com.beautifulquran.ui.theme.PaperCoverPad
import com.beautifulquran.ui.theme.ShapedWordBloom
import com.beautifulquran.ui.theme.shapedWordBloom

private const val MARK_SIZE_RATIO = 20f / 30f

/** Air between a verse's closing letter and its circled mark. */
private val MushafMarkGap = 0.10.em

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

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val natural = remember(line, palette.fullInkColor, ayahMarkInk, fontSize, useQcf) {
            if (useQcf) {
                qcfRendered(line, palette.fullInkColor, ayahMarkInk)
            } else {
                buildMushafLine(line, palette.fullInkColor, ayahMarkInk, fontSize, gapSpacing = 0.sp)
            }
        }
        val naturalWidth = remember(natural.text, style, constraints.maxWidth) {
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
                pageWidthPx = constraints.maxWidth.toFloat(),
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
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val measureWidthPx = constraints.maxWidth.toFloat()
    // The book is set at one size (see MUSHAF_DESIGN_LINE_EM). A line whose
    // glyph run still runs past the measure — a few dozen in the whole mushaf —
    // is set that little bit tighter, so one long line never drags its page's
    // type down with it.
    val condense = remember(line, fontSize, pageFont, measureWidthPx) {
        val probe = TextStyle(
            fontFamily = pageFont,
            fontSize = fontSize,
            textDirection = TextDirection.Rtl,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
        val natural = measurer.measure(
            text = buildMushafQcfLine(line.tokens).text,
            style = probe,
            constraints = Constraints(),
            maxLines = 1,
            softWrap = false,
        ).size.width.toFloat()
        // The line is measured as one run but drawn one [Text] per word, and
        // each of those rounds its width up. Fit to a measure a pixel per word
        // short of the real one: fitted exactly, that rounding pushes the last
        // word — and the circled ayah mark riding on it — past the edge.
        mushafLineCondense(
            naturalWidthPx = natural,
            measureWidthPx = (measureWidthPx - line.tokens.size).coerceAtLeast(1f),
        )
    }
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
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        // A justified line carries its own weight spacers, so it starts at the
        // fore-edge and fills the measure. A short line — al-Fātiḥah, a surah's
        // closing line — is centred on the page, the way it is printed, never
        // hung off the right margin.
        horizontalArrangement = if (justify) Arrangement.Start else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        line.tokens.forEachIndexed { index, token ->
            if (justify && index > 0) Spacer(Modifier.weight(1f))
            MushafQcfWord(
                token = token,
                style = style,
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
        }
    }
    }
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
    val mark = if (raw.isNotEmpty()) qcfTrailingMark(raw) else ""
    val rendered = remember(word, mark, palette.fullInkColor, ayahMarkInk) {
        val ranges = listOf(0 until word.length)
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = palette.fullInkColor)) {
                if (mark.isEmpty() || word.length < 2) {
                    append(word)
                } else {
                    // A hair of air before the medallion. The page fonts carry
                    // no space glyph, so the gap is let out of the closing
                    // letter's own advance rather than typed — printed, a mark
                    // never sits hard against the word it closes.
                    append(word.substring(0, word.length - 1))
                    withStyle(SpanStyle(letterSpacing = MushafMarkGap)) {
                        append(word.substring(word.length - 1))
                    }
                }
            }
            if (mark.isNotEmpty()) {
                withStyle(SpanStyle(color = ayahMarkInk)) { append(mark) }
            }
        }
        RenderedLineText(text = text, wordRanges = ranges, markRange = 0..-1)
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
        if (!liveInk || pack == null || motion != null) 1f
        else (1f - pack.recessCover.value).coerceIn(0f, 1f)
    }
    val blooms = {
        if (!liveInk) {
            emptyList()
        } else {
            val pack = packs[token.surahId to token.ayah]
            val motion = pack?.motions?.getOrNull(token.word.position - 1)
            if (pack == null || motion == null) {
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
                )
            }
        }
    }
    Text(
        text = rendered.text,
        style = style,
        maxLines = 1,
        softWrap = false,
        // Never slice a glyph at its box. The circled ayah mark is the last
        // glyph of a verse-closing word and its medallion inks wider than its
        // advance, so clipping to the measured box cuts the number in half —
        // which is exactly what the line end showed.
        overflow = TextOverflow.Visible,
        modifier = Modifier
            .graphicsLayer {
                alpha = recessAlpha()
                // Modulate, never composite: a leaf carries ~150 word nodes,
                // and letting alpha < 1 buy each one an offscreen buffer would
                // cost far more than the mask it replaced. Glyphs on a line do
                // not overlap, so modulating is also correct.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
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
