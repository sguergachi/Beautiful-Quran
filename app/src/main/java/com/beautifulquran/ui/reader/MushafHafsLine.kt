package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import com.beautifulquran.domain.mushafLineSqueeze
import com.beautifulquran.domain.qcfTrailingMark
import com.beautifulquran.domain.qcfWordGlyphs
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.MushafFontFamily
import com.beautifulquran.ui.theme.ShapedWordBloom
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
    packs: State<Map<Pair<Int, Int>, AyahInkPack>>,
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
    packs: State<Map<Pair<Int, Int>, AyahInkPack>>,
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
    val squeeze = remember(line, fontSize, pageFont, measureWidthPx) {
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
        mushafLineSqueeze(naturalWidthPx = natural, measureWidthPx = measureWidthPx)
    }
    val style = remember(fontSize, pageFont, squeeze) {
        TextStyle(
            fontFamily = pageFont,
            fontSize = fontSize * squeeze,
            lineHeight = MUSHAF_LINE_EM.em,
            textDirection = TextDirection.Rtl,
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
    packs: State<Map<Pair<Int, Int>, AyahInkPack>>,
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
            withStyle(SpanStyle(color = palette.fullInkColor)) { append(word) }
            if (mark.isNotEmpty()) {
                withStyle(SpanStyle(color = ayahMarkInk)) { append(mark) }
            }
        }
        RenderedLineText(text = text, wordRanges = ranges, markRange = 0..-1)
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val blooms = {
        if (!liveInk) {
            emptyList()
        } else {
            val pack = packs.value[token.surahId to token.ayah]
            val motion = pack?.motions?.getOrNull(token.word.position - 1)
            if (pack == null || motion == null) {
                val cover = pack?.recessCover?.value ?: 0f
                if (cover > 0f) {
                    listOf(
                        ShapedWordBloom.UpcomingDim(
                            range = rendered.wordRanges.first(),
                            paper = palette.paperColor,
                            coverAlpha = cover,
                        ),
                    )
                } else {
                    emptyList()
                }
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
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .mushafLineInk(
                liveInk = liveInk,
                blooms = blooms,
                layout = { layoutResult },
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
): Modifier = if (!liveInk) {
    this
} else {
    shapedWordBloom(
        blooms = blooms,
        layout = layout,
        rtl = true,
        feather = InkEngine.tuning.washFeather,
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
    packs: State<Map<Pair<Int, Int>, AyahInkPack>>,
    rendered: RenderedLineText,
    palette: WordInkPalette,
    glintInk: androidx.compose.ui.graphics.Color?,
) = buildList {
    line.tokens.forEachIndexed { tokenIndex, token ->
        val pack = packs.value[token.surahId to token.ayah] ?: return@forEachIndexed
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
