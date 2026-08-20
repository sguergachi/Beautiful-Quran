package com.beautifulquran.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
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
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.MUSHAF_LINE_EM
import com.beautifulquran.domain.mushafLineJustifies
import com.beautifulquran.domain.qcfTrailingMark
import com.beautifulquran.domain.qcfWordGlyphs
import com.beautifulquran.ui.reader.AyahNumberMark
import com.beautifulquran.ui.reader.MushafQcfFonts
import com.beautifulquran.ui.reader.PageBreak
import com.beautifulquran.ui.reader.VERSE_ANNOTATION_INK_ALPHA
import com.beautifulquran.ui.reader.collapsedStackSpanDp
import com.beautifulquran.ui.reader.formatAyahNumberMark
import com.beautifulquran.ui.reader.symbolicAyahBarCount
import com.beautifulquran.ui.reader.verseAnnotationStyle
import kotlin.math.abs
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

private const val MUSHAF_LINE_1 = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ"
private const val MUSHAF_LINE_2 = "ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَٰلَمِينَ"
// 56:76 ends page 536; 56:77 opens 537 — a real printed-page turn.
private const val SAMPLE_ARABIC_1 = "وَإِنَّهُۥ لَقَسَمٞ لَّوۡ تَعۡلَمُونَ عَظِيمٌ"
private const val SAMPLE_ARABIC_2 = "إِنَّهُۥ لَقُرۡءَانٞ كَرِيمٞ"
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
        Caption("Mushaf is a printed Arabic page.")

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

            if (showsWordGlossChrome(settings.readingLayout, settings.readingMode)) {
                Spacer(Modifier.height(20.dp))
                ToggleRow(
                    label = "Word-by-word translation",
                    checked = settings.showWordGloss,
                    onChange = { v -> onUpdate { it.copy(showWordGloss = v) } },
                    checkParams = checkParams,
                    checkPaintToken = checkPaintToken,
                )
            }

            Spacer(Modifier.height(20.dp))
            ToggleRow(
                label = "Verse annotations",
                checked = settings.annotationsEnabled,
                onChange = { v -> onUpdate { it.copy(annotationsEnabled = v) } },
                checkParams = checkParams,
                checkPaintToken = checkPaintToken,
            )

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
) {
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
        if (readingLayout == ReadingLayout.MUSHAF) {
            PreviewMushafLeaf(
                pageNumberScript = pageNumberScript,
                modifier = contentPad,
            )
        } else {
            // Both sets the leaf height. Other views fill that box and clip.
            Column(Modifier.alpha(0f).then(contentPad)) {
                PreviewBothBody(
                    arabicMarks = arabicMarks,
                    showWordGloss = showWordGloss,
                    showNote = showNote,
                    pageNumberScript = pageNumberScript,
                )
            }
            Column(Modifier.matchParentSize().clipToBounds().then(contentPad)) {
                if (englishOnly) {
                    PreviewEnglishLyric(
                        SAMPLE_ENGLISH,
                        number = SAMPLE_AYAH_1,
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
                if (englishOnly) {
                    PreviewEnglishLyric(
                        SAMPLE_ENGLISH_2,
                        number = SAMPLE_AYAH_2,
                        arabicMarks = arabicMarks,
                    )
                } else if (arabicOnly) {
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

private val PreviewQcfSize = 13.sp
private const val PreviewMushafLineCap = 7

/** A miniature of page 1 in the printed QCF hand — not a Hafs scroll. */
@Composable
private fun PreviewMushafLeaf(
    pageNumberScript: PageNumberScript,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf<MushafPage?>(null) }
    LaunchedEffect(Unit) {
        page = (context.applicationContext as QuranApp).repository.mushafCatalog().page(1)
    }
    val face = remember { MushafQcfFonts.family(context, 1) }
    val gold = LocalQuranAccents.current.gold
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "سُورَةُ الفَاتِحَةِ",
            fontFamily = HafsFontFamily,
            fontSize = 13.sp,
            color = gold.copy(alpha = 0.58f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val leaf = page
        if (leaf != null && face != null) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                leaf.lines.take(PreviewMushafLineCap).forEach { line ->
                    PreviewQcfLine(line = line, face = face)
                }
            }
        } else {
            PreviewArabicLine(MUSHAF_LINE_1, number = 1, arabicMarks = true)
            Spacer(Modifier.height(8.dp))
            PreviewArabicLine(MUSHAF_LINE_2, number = 2, arabicMarks = true)
        }
        PageBreak(page = 1, script = pageNumberScript, contentPadding = PreviewFolioPad)
    }
}

@Composable
private fun PreviewQcfLine(line: MushafLine, face: FontFamily) {
    val ink = MaterialTheme.colorScheme.onSurface
    val gold = LocalQuranAccents.current.gold
    val style = remember(face) {
        TextStyle(
            fontFamily = face,
            fontSize = PreviewQcfSize,
            lineHeight = MUSHAF_LINE_EM.em,
            textDirection = TextDirection.Rtl,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    }
    val justify = mushafLineJustifies(line.tokens.size)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        line.tokens.forEachIndexed { index, token ->
            if (justify && index > 0) Spacer(Modifier.weight(1f))
            val raw = token.word.qcfV2
            val word = if (raw.isNotEmpty()) {
                qcfWordGlyphs(raw, token.endsAyah)
            } else {
                token.word.arabic
            }
            val mark = if (raw.isNotEmpty()) qcfTrailingMark(raw, token.endsAyah) else ""
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = ink)) { append(word) }
                    if (mark.isNotEmpty()) {
                        withStyle(SpanStyle(color = gold)) { append(mark) }
                    }
                },
                style = style,
                maxLines = 1,
            )
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
