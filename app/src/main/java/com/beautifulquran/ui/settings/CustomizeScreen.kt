package com.beautifulquran.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beautifulquran.QuranApp
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.data.PageNumberScript
import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.Settings
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.data.VerseNumberScript
import com.beautifulquran.domain.MUSHAF_LINE_PITCH_EM
import com.beautifulquran.domain.MUSHAF_WORD_GAP_EM
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.mushafLineFit
import com.beautifulquran.domain.qcfTrailingMark
import com.beautifulquran.domain.qcfWordGlyphs
import com.beautifulquran.ui.reader.AyahNumberMark
import com.beautifulquran.ui.reader.MushafFolioMarks
import com.beautifulquran.ui.reader.MushafCell
import com.beautifulquran.ui.reader.MushafQcfFonts
import com.beautifulquran.ui.reader.PageBreak
import com.beautifulquran.ui.reader.VERSE_ANNOTATION_INK_ALPHA
import com.beautifulquran.ui.reader.collapsedStackSpanDp
import com.beautifulquran.ui.reader.formatAyahNumberMark
import com.beautifulquran.ui.reader.mushafCellOrigins
import com.beautifulquran.ui.reader.mushafLineCells
import com.beautifulquran.ui.reader.symbolicAyahBarCount
import com.beautifulquran.ui.reader.verseAnnotationStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import com.beautifulquran.ui.theme.BrushCheckParams
import com.beautifulquran.ui.theme.BrushCircleParams
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.InkCircledChoiceRow
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.TranslationFontFamily
import com.beautifulquran.ui.theme.shippedCheckParams
import com.beautifulquran.ui.theme.verticalFadingEdges

private val VIEW_MODES = listOf(
    ReadingMode.ARABIC_ONLY,
    ReadingMode.ENGLISH_ONLY,
    ReadingMode.ARABIC_ENGLISH,
)

private val PAGE_SCRIPTS = listOf(
    PageNumberScript.ARABIC,
    PageNumberScript.ENGLISH,
    PageNumberScript.BOTH,
)

// 56:76 ends page 536; 56:77 opens 537 — a real printed-page turn.
private const val SAMPLE_ARABIC_1 = "وَإِنَّهُۥ لَقَسَمٞ لَّوۡ تَعۡلَمُونَ عَظِيمٌ"
private const val SAMPLE_ARABIC_2 = "إِنَّهُۥ لَقُرۡءَانٞ كَرِيمٞ"
private const val SAMPLE_TRANSLIT = "Wa-innahu la-qasam law taʿlamūna ʿaẓīm"
private const val SAMPLE_ENGLISH =
    "And indeed, it is an oath - if you could know - [most] great."
private const val SAMPLE_ENGLISH_2 = "Indeed, it is a noble Qur'an."
private const val SAMPLE_NOTE = "The oath is the setting of the stars."
private const val SAMPLE_PAGE = 536
private const val SAMPLE_AYAH_1 = 76
private const val SAMPLE_AYAH_2 = 77
private val SAMPLE_WORDS = listOf(
    "وَإِنَّهُۥ" to "indeed",
    "لَقَسَمٞ" to "an oath",
    "عَظِيمٌ" to "great",
)

@Composable
internal fun CustomizeScreen(
    settings: Settings,
    brushParams: BrushCircleParams,
    paintToken: Int,
    checkParams: BrushCheckParams = shippedCheckParams(),
    checkPaintToken: Int = 0,
    onBack: () -> Unit,
    onUpdate: ((Settings) -> Settings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(20.dp))
            BackChevron(onBack)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Customize",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Section("Preview")
            ReadingPreview(
                readingMode = settings.readingMode,
                readingLayout = settings.readingLayout,
                verseNumberScript = settings.verseNumberScript,
                pageNumberScript = settings.pageNumberScript,
                ayahSelectorSide = settings.ayahSelectorSide,
                annotationsEnabled = settings.annotationsEnabled,
                showWordGloss = settings.showWordGloss,
                fontScale = settings.fontScale,
                showTranslation = settings.showTranslation,
                showTransliteration = settings.showTransliteration,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalFadingEdges(
                    color = MaterialTheme.colorScheme.background,
                    top = 36.dp,
                    bottom = 40.dp,
                ),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
        Section("Layout")
        InkCircledChoiceRow(
            entries = ReadingLayout.entries,
            selected = settings.readingLayout,
            params = brushParams,
            paintToken = paintToken,
            label = { layout ->
                when (layout) {
                    ReadingLayout.SCROLL -> "Scroll"
                    ReadingLayout.MUSHAF -> "Mushaf"
                }
            },
            onSelect = { layout -> onUpdate { applyReadingLayout(it, layout) } },
        )
        if (showsScrollChrome(settings.readingLayout)) {
            Section("View")
            InkCircledChoiceRow(
                entries = VIEW_MODES,
                selected = settings.readingMode,
                params = brushParams,
                paintToken = paintToken,
                label = { mode ->
                    when (mode) {
                        ReadingMode.ARABIC_ONLY -> "Arabic"
                        ReadingMode.ENGLISH_ONLY -> "English"
                        ReadingMode.ARABIC_ENGLISH -> "Both"
                    }
                },
                onSelect = { mode -> onUpdate { applyReadingMode(it, mode) } },
            )
        }

        Section("Text size")
        TextSizeControl(
            scale = settings.fontScale,
            onScale = { value -> onUpdate { it.copy(fontScale = value) } },
        )

        // The scroll layout's toggles share one vertical rhythm: a single
        // 20dp stand-off before the group, then even 12dp between rows —
        // the old layout gapped 20dp before some rows and nothing between
        // Transliteration and Ayah translation.
        if (showsScrollChrome(settings.readingLayout)) {
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (
                    settings.readingLayout == ReadingLayout.SCROLL &&
                    settings.readingMode == ReadingMode.ARABIC_ENGLISH
                ) {
                    ToggleRow(
                        label = "Transliteration",
                        checked = settings.showTransliteration,
                        onChange = { value -> onUpdate { it.copy(showTransliteration = value) } },
                        checkParams = checkParams,
                        checkPaintToken = checkPaintToken,
                    )
                    ToggleRow(
                        label = "Ayah translation",
                        checked = settings.showTranslation,
                        onChange = { value -> onUpdate { it.copy(showTranslation = value) } },
                        checkParams = checkParams,
                        checkPaintToken = checkPaintToken,
                    )
                }
                if (showsWordGlossChrome(settings.readingLayout, settings.readingMode)) {
                    ToggleRow(
                        label = "Word-by-word translation",
                        checked = settings.showWordGloss,
                        onChange = { v -> onUpdate { it.copy(showWordGloss = v) } },
                        checkParams = checkParams,
                        checkPaintToken = checkPaintToken,
                    )
                }
                ToggleRow(
                    label = "Verse annotations",
                    checked = settings.annotationsEnabled,
                    onChange = { v -> onUpdate { it.copy(annotationsEnabled = v) } },
                    checkParams = checkParams,
                    checkPaintToken = checkPaintToken,
                )
            }
        }

        if (showsScrollChrome(settings.readingLayout)) {
            Section("Verse numbers")
            InkCircledChoiceRow(
                entries = VerseNumberScript.entries,
                selected = settings.verseNumberScript,
                params = brushParams,
                paintToken = paintToken,
                label = { script ->
                    when (script) {
                        VerseNumberScript.ARABIC -> "Arabic"
                        VerseNumberScript.ENGLISH -> "English"
                    }
                },
                onSelect = { script -> onUpdate { it.copy(verseNumberScript = script) } },
            )
        }

        Section("Page numbers")
        InkCircledChoiceRow(
            entries = PAGE_SCRIPTS,
            selected = settings.pageNumberScript,
            params = brushParams,
            paintToken = paintToken,
            label = { script ->
                when (script) {
                    PageNumberScript.BOTH -> "Both"
                    PageNumberScript.ARABIC -> "Arabic"
                    PageNumberScript.ENGLISH -> "English"
                }
            },
            onSelect = { script -> onUpdate { it.copy(pageNumberScript = script) } },
        )

        if (showsScrollChrome(settings.readingLayout)) {
            Section("Ayah selector")
            InkCircledChoiceRow(
                entries = AyahSelectorSide.entries,
                selected = settings.ayahSelectorSide,
                params = brushParams,
                paintToken = paintToken,
                label = { side ->
                    when (side) {
                        AyahSelectorSide.LEFT -> "Left side"
                        AyahSelectorSide.RIGHT -> "Right side"
                    }
                },
                onSelect = { side -> onUpdate { it.copy(ayahSelectorSide = side) } },
            )
        }

        Section("Theme")
        Spacer(Modifier.height(2.dp))
        ThemeMode.entries.forEach { mode ->
            SelectRow(
                label = mode.label,
                selected = settings.themeMode == mode,
                onClick = { onUpdate { it.copy(themeMode = mode) } },
                trailing = { ThemeColorPreview(mode = mode) },
            )
        }

        Spacer(Modifier.height(48.dp))
        }
        }
    }
}

private val PreviewLeaf = RoundedCornerShape(3.dp)
// Miniature of the reader: ~0.8 of the live sizes, same ratios.
private val PreviewArabicSize = 24.sp
private val PreviewLyricSize = 18.sp
private val PreviewTranslationSize = 13.sp
private val PreviewGlossSize = 10.sp
private val PreviewFolioPad = PaddingValues(horizontal = 0.dp, vertical = 6.dp)

/**
 * A live miniature of the reader: a faded leaf with a gold hairline, carrying
 * the same Hafs, Garamond, gold marks, and folio the sheet itself will use.
 */
@Composable
internal fun ReadingPreview(
    readingMode: ReadingMode,
    readingLayout: ReadingLayout,
    verseNumberScript: VerseNumberScript,
    pageNumberScript: PageNumberScript,
    ayahSelectorSide: AyahSelectorSide = AyahSelectorSide.LEFT,
    annotationsEnabled: Boolean = false,
    showWordGloss: Boolean = false,
    /** The reader's text-size stop, so the miniature grows and shrinks with
     *  the same dial — every sp inside, marks and folio included. */
    fontScale: Float = 1f,
    /** Scroll-layout toggles, mirrored live like every other choice. */
    showTranslation: Boolean = true,
    showTransliteration: Boolean = false,
) {
    // Every sp inside the preview, marks and folio included, rides the
    // reader's text-size dial — the miniature is the reader at one glance.
    val previewDensity = Density(
        density = LocalDensity.current.density,
        fontScale = LocalDensity.current.fontScale * fontScale,
    )
    val arabicOnly = readingLayout == ReadingLayout.MUSHAF ||
        readingMode == ReadingMode.ARABIC_ONLY
    val englishOnly = readingLayout == ReadingLayout.SCROLL &&
        readingMode == ReadingMode.ENGLISH_ONLY
    val arabicMarks = verseNumberScript == VerseNumberScript.ARABIC
    val showNote = showsPreviewAnnotation(readingLayout, annotationsEnabled)
    val showGloss = showsPreviewWordGloss(readingLayout, readingMode, showWordGloss)
    val showRail = showsPreviewAyahRail(readingLayout)
    val gold = LocalQuranAccents.current.gold
    val leaf = MaterialTheme.colorScheme.surface
    val railPad = if (showRail) 10.dp else 0.dp
    val contentPad = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp)
        .padding(
            start = if (showRail && ayahSelectorSide == AyahSelectorSide.LEFT) railPad else 0.dp,
            end = if (showRail && ayahSelectorSide == AyahSelectorSide.RIGHT) railPad else 0.dp,
        )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PreviewLeaf)
            .background(leaf.copy(alpha = 0.42f), PreviewLeaf)
            .border(0.5.dp, gold.copy(alpha = 0.28f), PreviewLeaf)
            .graphicsLayer { alpha = 0.74f },
    ) {
        // The height lock lives inside the scaled density: it measures the
        // max leaf at the preview's own text size, so the size dial grows
        // and shrinks the leaf instead of clipping bigger type at a height
        // measured for the old size.
        CompositionLocalProvider(LocalDensity provides previewDensity) {
            // Height is the max leaf, always. Settings only change what is painted.
            PreviewHeightLock(contentPad)
            Column(Modifier.matchParentSize().clipToBounds().then(contentPad)) {
            if (readingLayout == ReadingLayout.MUSHAF) {
                PreviewMushafLeaf(
                    pageNumberScript = pageNumberScript,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (englishOnly) {
                PreviewEnglishLyric(
                    SAMPLE_ENGLISH,
                    number = SAMPLE_AYAH_1,
                    arabicMarks = arabicMarks,
                )
                PageBreak(
                    page = SAMPLE_PAGE,
                    script = pageNumberScript,
                    contentPadding = PreviewFolioPad,
                )
                PreviewEnglishLyric(
                    SAMPLE_ENGLISH_2,
                    number = SAMPLE_AYAH_2,
                    arabicMarks = arabicMarks,
                )
            } else {
                PreviewArabicLine(
                    SAMPLE_ARABIC_1,
                    number = SAMPLE_AYAH_1,
                    arabicMarks = arabicMarks,
                    showGloss = showGloss,
                )
                if (!arabicOnly) {
                    if (showTransliteration) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = SAMPLE_TRANSLIT,
                            fontFamily = TranslationFontFamily,
                            fontSize = PreviewLyricSize * 13f / 18f,
                            lineHeight = 1.4.em,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    if (showTranslation) {
                        Spacer(Modifier.height(12.dp))
                        PreviewTranslation()
                    }
                }
                if (showNote) {
                    Spacer(Modifier.height(12.dp))
                    PreviewAnnotation()
                }
                PageBreak(
                    page = SAMPLE_PAGE,
                    script = pageNumberScript,
                    contentPadding = PreviewFolioPad,
                )
                if (arabicOnly) {
                    PreviewArabicLine(
                        SAMPLE_ARABIC_2,
                        number = SAMPLE_AYAH_2,
                        arabicMarks = arabicMarks,
                    )
                }
            }
        }
        if (showRail) {
            PreviewAyahRail(
                side = ayahSelectorSide,
                modifier = Modifier.align(
                    if (ayahSelectorSide == AyahSelectorSide.RIGHT) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    },
                ),
            )
        }
    }
    }
}

private val PreviewQcfSize = 20.sp
/** Page hand for the miniature folio, so the figures read at ~10 sp. */
private val PreviewFolioGlyph = 22.sp
/** 21:91–92 occupy three exclusive Madinah lines (page 330, lines 1–3). */
private const val PreviewMushafPage = 330
private const val PreviewMushafLineFirst = 1
private const val PreviewMushafLineLast = 3
private const val PreviewMushafSurahName = "سُورَةُ الأنبياء"

/** Two short verses as three printed lines, scaled to the measure — never gap-stretched. */
@Composable
private fun PreviewMushafLeaf(
    pageNumberScript: PageNumberScript,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf<MushafPage?>(null) }
    LaunchedEffect(Unit) {
        page = (context.applicationContext as QuranApp).repository
            .mushafCatalog()
            .page(PreviewMushafPage)
    }
    val face = remember { MushafQcfFonts.family(context, PreviewMushafPage) }
    val typeface = remember(face) { MushafQcfFonts.cachedTypeface(PreviewMushafPage) }
    val gold = LocalQuranAccents.current.gold
    val lines = page?.lines.orEmpty().filter {
        it.number in PreviewMushafLineFirst..PreviewMushafLineLast
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = PreviewMushafSurahName,
            fontFamily = HafsFontFamily,
            fontSize = 13.sp,
            color = gold.copy(alpha = 0.58f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (lines.size == 3 && face != null && typeface != null) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                PreviewQcfLines(lines = lines, face = face, typeface = typeface)
            }
        } else {
            val line = with(LocalDensity.current) {
                (PreviewQcfSize * MUSHAF_LINE_PITCH_EM).toDp()
            }
            Spacer(Modifier.height(line * 3))
        }
        MushafFolioMarks(
            page = PreviewMushafPage,
            glyphSize = PreviewFolioGlyph,
            script = pageNumberScript,
            modifier = Modifier
                .fillMaxWidth()
                .padding(PreviewFolioPad),
        )
    }
}

/** One drawn cell of a preview line — a word, or the circled mark after it. */
private data class PreviewQcfCell(val text: String, val mark: Boolean)

private fun previewQcfCells(line: MushafLine): List<PreviewQcfCell> = buildList {
    line.tokens.forEach { token ->
        val raw = token.word.qcfV2
        add(
            PreviewQcfCell(
                text = if (raw.isNotEmpty()) {
                    qcfWordGlyphs(raw, token.endsAyah)
                } else {
                    token.word.arabic
                },
                mark = false,
            ),
        )
        val mark = if (raw.isNotEmpty()) qcfTrailingMark(raw, token.endsAyah) else ""
        if (mark.isNotEmpty()) add(PreviewQcfCell(text = mark, mark = true))
    }
}

@Composable
private fun PreviewQcfLines(
    lines: List<MushafLine>,
    face: FontFamily,
    typeface: android.graphics.Typeface,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val gold = LocalQuranAccents.current.gold
    val density = LocalDensity.current
    val cells = remember(lines) { lines.map { previewQcfCells(it) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurePx = constraints.maxWidth.toFloat()
        val base = TextStyle(
            fontFamily = face,
            fontSize = PreviewQcfSize,
            lineHeight = MUSHAF_LINE_PITCH_EM.em,
            textDirection = TextDirection.Rtl,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
        val fontPx = with(density) { PreviewQcfSize.toPx() }
        val rawCells = remember(cells, typeface, fontPx) {
            cells.map { line -> mushafLineCells(line.map { it.text }, typeface, fontPx, 1f) }
        }
        val needed = rawCells.map { line ->
            line.sumOf { it.inkWidth.toDouble() }.toFloat() +
                (line.size - 1).coerceAtLeast(0) * MUSHAF_WORD_GAP_EM * fontPx
        }
        val longest = needed.maxOrNull() ?: 0f
        val sizeScale = if (longest > measurePx && longest > 0f) measurePx / longest else 1f
        val fitted = base.copy(fontSize = PreviewQcfSize * sizeScale)
        val fittedPx = fontPx * sizeScale
        Column(Modifier.fillMaxWidth()) {
            cells.forEach { line ->
                val lineCells = mushafLineCells(line.map { it.text }, typeface, fittedPx, 1f)
                val inkWidth = lineCells.sumOf { it.inkWidth.toDouble() }.toFloat()
                val fit = mushafLineFit(
                    inkWidthPx = inkWidth,
                    gapCount = (line.size - 1).coerceAtLeast(0),
                    measureWidthPx = measurePx,
                    fontPx = fittedPx,
                )
                val style = if (fit.scale == 1f) {
                    fitted
                } else {
                    fitted.copy(textGeometricTransform = TextGeometricTransform(scaleX = fit.scale))
                }
                val placedCells = if (fit.scale == 1f) {
                    lineCells
                } else {
                    mushafLineCells(line.map { it.text }, typeface, fittedPx, fit.scale)
                }
                PreviewQcfLine(metrics = placedCells, fit = fit) {
                    line.forEach { cell ->
                        Text(
                            text = cell.text,
                            style = style.copy(color = if (cell.mark) gold else ink),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewQcfLine(
    metrics: List<MushafCell>,
    fit: com.beautifulquran.domain.MushafLineFit,
    content: @Composable () -> Unit,
) {
    Layout(modifier = Modifier.fillMaxWidth(), content = content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val origins = mushafCellOrigins(
            cells = metrics,
            count = placeables.size,
            width = constraints.maxWidth.toFloat(),
            fit = fit,
        )
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(
                    origins.getOrElse(index) { 0f }.roundToInt(),
                    (height - placeable.height) / 2,
                )
            }
        }
    }
}

@Composable
private fun PreviewArabicLine(
    text: String,
    number: Int,
    arabicMarks: Boolean,
    showGloss: Boolean = false,
    glossVisible: Boolean = showGloss,
    modifier: Modifier = Modifier,
) {
    val gold = LocalQuranAccents.current.gold
    val ink = MaterialTheme.colorScheme.onSurface
    if (showGloss) {
        val markBox = with(LocalDensity.current) { (PreviewArabicSize * 1.9f).toDp() }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                SAMPLE_WORDS.forEach { (arabic, gloss) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = arabic,
                            fontFamily = HafsFontFamily,
                            fontSize = PreviewArabicSize,
                            lineHeight = 1.4.em,
                            color = ink,
                        )
                        Text(
                            text = gloss,
                            fontSize = PreviewGlossSize,
                            lineHeight = 13.sp,
                            color = ink.copy(alpha = 0.62f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.alpha(if (glossVisible) 1f else 0f),
                        )
                    }
                }
                Box(
                    modifier = Modifier.requiredHeight(markBox),
                    contentAlignment = Alignment.Center,
                ) {
                    AyahNumberMark(
                        number = number,
                        fontScale = 0.8f,
                        useArabicIndicDigits = arabicMarks,
                    )
                }
            }
        }
        return
    }
    val run = buildAnnotatedString {
        withStyle(SpanStyle(color = ink)) { append(text) }
        append("\u00a0")
        withStyle(
            SpanStyle(
                color = gold,
                fontSize = PreviewArabicSize * 20f / 30f,
            ),
        ) {
            append(formatAyahNumberMark(number, arabicMarks))
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Text(
            text = run,
            fontFamily = HafsFontFamily,
            fontSize = PreviewArabicSize,
            lineHeight = 1.9.em,
            textAlign = TextAlign.Right,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** English lyric: the verse is the prose, with ﴿N﴾ glued to the last word. */
@Composable
private fun PreviewEnglishLyric(
    verse: String,
    number: Int,
    arabicMarks: Boolean,
) {
    val gold = LocalQuranAccents.current.gold
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = ink)) { append(verse) }
        append(" ")
        withStyle(
            SpanStyle(
                color = gold,
                fontFamily = HafsFontFamily,
                fontSize = PreviewLyricSize * 17f / 22f,
            ),
        ) {
            append(formatAyahNumberMark(number, arabicMarks))
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = text,
            fontFamily = TranslationFontFamily,
            fontSize = PreviewLyricSize,
            lineHeight = 1.5.em,
            style = TextStyle(textDirection = TextDirection.Ltr),
        )
    }
}

/**
 * Invisible copies of the tallest scroll leaf and the mushaf miniature.
 * The preview Box sizes to whichever is taller, so Layout / View / gloss /
 * notes / folio script never resize it.
 */
@Composable
private fun PreviewHeightLock(contentPad: Modifier) {
    Column(Modifier.alpha(0f).then(contentPad)) {
        PreviewBothBody(
            arabicMarks = true,
            showWordGloss = true,
            showNote = true,
            pageNumberScript = PageNumberScript.BOTH,
        )
    }
    Column(Modifier.alpha(0f).then(contentPad)) {
        PreviewMushafLeaf(
            pageNumberScript = PageNumberScript.BOTH,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PreviewBothBody(
    arabicMarks: Boolean,
    showWordGloss: Boolean,
    showNote: Boolean,
    pageNumberScript: PageNumberScript,
) {
    PreviewArabicLine(
        SAMPLE_ARABIC_1,
        number = SAMPLE_AYAH_1,
        arabicMarks = arabicMarks,
        showGloss = showWordGloss,
    )
    Spacer(Modifier.height(12.dp))
    PreviewTranslation()
    if (showNote) {
        Spacer(Modifier.height(12.dp))
        PreviewAnnotation()
    }
    PageBreak(
        page = SAMPLE_PAGE,
        script = pageNumberScript,
        contentPadding = PreviewFolioPad,
    )
}

@Composable
private fun PreviewTranslation() {
    Text(
        text = SAMPLE_ENGLISH,
        fontFamily = TranslationFontFamily,
        fontSize = PreviewTranslationSize,
        lineHeight = 21.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
    )
}

private const val PREVIEW_RAIL_AYAHS = 7

/** Collapsed dash stack, flush to the leaf edge — same bars as the reader rail. */
@Composable
private fun PreviewAyahRail(
    side: AyahSelectorSide,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val mirrored = side == AyahSelectorSide.RIGHT
    Canvas(
        modifier
            .width(18.dp)
            .height(collapsedStackSpanDp(PREVIEW_RAIL_AYAHS).dp),
    ) {
        val count = symbolicAyahBarCount(PREVIEW_RAIL_AYAHS)
        val barH = 1.5.dp.toPx()
        val spacing = (72.dp.toPx() / count).coerceIn(4.dp.toPx(), 8.dp.toPx())
        val step = barH + spacing
        val centerY = size.height / 2f
        val barWidth = 10.dp.toPx()
        val corner = CornerRadius(barH, barH)
        fun rectLeft(x: Float, width: Float) =
            if (mirrored) size.width - x - width else x
        for (index in 0 until count) {
            val relative = index - (count - 1) / 2f
            val y = centerY + relative * step
            val focus = (1f - abs(index.toFloat())).coerceIn(0f, 1f)
            val width = barWidth * (0.7f + 0.45f * focus) + barH
            drawRoundRect(
                color = onSurface.copy(alpha = 0.18f + 0.72f * focus),
                topLeft = Offset(rectLeft(-barH, width), y - barH / 2f),
                size = Size(width, barH),
                cornerRadius = corner,
            )
        }
    }
}

/** Settled ḥāshiya: ruby rule + Cormorant italic, same hand as the reader. */
@Composable
private fun PreviewAnnotation() {
    val accents = LocalQuranAccents.current
    val rule = accents.bookmarkRibbon.copy(alpha = 0.92f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.width(2.dp).fillMaxHeight()) {
            val x = size.width / 2f
            drawLine(
                color = rule,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = size.width,
                cap = StrokeCap.Round,
            )
        }
        Text(
            text = SAMPLE_NOTE,
            style = verseAnnotationStyle(fontSize = 14.sp, lineHeight = 20.sp, fontScale = 0.8f),
            color = accents.annotationInk.copy(alpha = VERSE_ANNOTATION_INK_ALPHA),
        )
    }
}
