package com.beautifulquran.ui.rootviewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.beautifulquran.data.model.RootLemmaSummary
import com.beautifulquran.data.model.RootOccurrence
import com.beautifulquran.data.model.Word
import com.beautifulquran.ui.theme.DisplayFontFamily
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.SerifFontFamily
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.verticalFadingEdges

internal const val ROOT_CHAPTER_PREVIEW_LIMIT = 8
internal const val ROOT_OCCURRENCE_PREVIEW_LIMIT = 5
internal const val ROOT_RELATED_FORM_PREVIEW_LIMIT = 5

private const val ROOT_HELP =
    "Usually three consonants shared by a family of related words. A root points to a meaning family, not one fixed translation."
private const val LEMMA_HELP =
    "The dictionary headword for this form. Other endings of the same word share this lemma; the root is the wider family."
/** Minimal Wiktionary credit when senses are shown under Lemma. */
private const val DICTIONARY_HELP = "English senses from Wiktionary (CC BY-SA)."
private const val OCCURRENCES_HELP =
    "Every Quran word annotated with this root. Shared roots suggest a family resemblance, but context decides the meaning."
private const val RELATED_FORMS_HELP =
    "Other dictionary headwords built from the same root. Their meanings may be related, but are not necessarily identical. " +
        "Each English line is the rendering used most often for that form, not a dictionary definition."

/** Lane intro plus the Perseus credit — shown in the section ⓘ, not under every entry. */
private fun lexiconHelp(entry: com.beautifulquran.data.model.LexiconEntry): String {
    val page = entry.page.takeIf { it > 0 }?.let { ", p. $it" }.orEmpty()
    return "Edward Lane's Arabic-English Lexicon (1863–93), the deepest classical dictionary " +
        "in English. It describes the root across all of Arabic, not only the Quran, and cites " +
        "the mediaeval lexicographers it draws on in parentheses. Edward William Lane, " +
        "An Arabic-English Lexicon$page. ${entry.credit}"
}


internal data class RootOccurrenceSection(
    val surahId: Int,
    val surahName: String,
    val occurrences: List<RootOccurrence>,
)

/** Groups concordance hits by chapter while preserving their Quranic order. */
internal fun rootOccurrenceSections(occurrences: List<RootOccurrence>): List<RootOccurrenceSection> =
    occurrences
        .groupBy { it.surahId }
        .map { (surahId, chapterOccurrences) ->
            RootOccurrenceSection(
                surahId = surahId,
                surahName = chapterOccurrences.first().surahNameTransliteration,
                occurrences = chapterOccurrences,
            )
        }

/** First eight chapters, substituting the held word's chapter when necessary. */
internal fun initialRootSections(
    sections: List<RootOccurrenceSection>,
    currentSurahId: Int,
    limit: Int = ROOT_CHAPTER_PREVIEW_LIMIT,
): List<RootOccurrenceSection> {
    if (sections.size <= limit) return sections
    val current = sections.firstOrNull { it.surahId == currentSurahId }
    val visible = sections.take(limit)
    if (current == null || visible.any { it.surahId == currentSurahId }) return visible
    return (visible.take(limit - 1) + current).sortedBy(sections::indexOf)
}

/** Frequency-ordered analyses other than the form already explained above. */
internal fun relatedRootForms(
    lemmas: List<RootLemmaSummary>,
    currentLemma: String,
    currentPos: String,
): List<RootLemmaSummary> = lemmas.filter { it.lemma != currentLemma || it.pos != currentPos }

/** Compact bilingual lexicon revealed by the reader's ink bleed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootViewerScreen(
    viewModel: RootViewerViewModel,
    onBack: () -> Unit,
    onJumpToOccurrence: (surahId: Int, ayah: Int) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showTopTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val morph = ui.morphology
    val sections = remember(ui.occurrences) { rootOccurrenceSections(ui.occurrences) }
    val relatedForms = remember(ui.lemmas, morph?.lemma, morph?.pos) {
        relatedRootForms(ui.lemmas, morph?.lemma.orEmpty(), morph?.pos.orEmpty())
    }
    var openSurahId by remember(morph?.root, ui.surahId) { mutableStateOf<Int?>(ui.surahId) }
    var showAllOccurrences by remember(morph?.root) { mutableStateOf(false) }
    var showAllChapters by remember(morph?.root) { mutableStateOf(false) }
    var showAllForms by remember(morph?.root) { mutableStateOf(false) }
    var lexiconExpanded by remember(morph?.root) { mutableStateOf(false) }
    var dictionaryExpanded by remember(morph?.lemma) { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    AnimatedVisibility(
                        visible = showTopTitle && ui.word != null,
                        enter = fadeIn(tween(350)),
                        exit = fadeOut(tween(350)),
                    ) {
                        ui.word?.let {
                            CollapsedWordTitle(
                                word = it,
                                isPlaying = ui.isPlayingWord,
                                onPlay = viewModel::playCurrentWord,
                                onScrollToTop = {
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when {
            ui.isLoading -> RootMessage("…", padding)
            ui.error != null && ui.word == null -> RootMessage(ui.error.orEmpty(), padding)
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .widthIn(max = 592.dp)
                        .fillMaxSize()
                        .verticalFadingEdges(
                            color = MaterialTheme.colorScheme.background,
                            top = 16.dp,
                            bottom = 32.dp,
                        ),
                    contentPadding = PaddingValues(
                        start = 24.dp,
                        end = 24.dp,
                        top = 16.dp,
                        bottom = 32.dp,
                    ),
                ) {
                    item(key = "word-header") {
                        ui.word?.let { word ->
                            ProseMeasure {
                                WordHeader(word, ui.isPlayingWord, viewModel::playCurrentWord)
                            }
                        }
                    }

                    if (morph != null) {
                        item(key = "analysis") {
                            ProseMeasure(Modifier.padding(top = 32.dp)) {
                                WordAnalysis(
                                    morph = morph,
                                    lemmas = ui.lemmas,
                                    lexiconText = ui.lexicon?.text,
                                    dictionary = ui.dictionary,
                                    dictionaryExpanded = dictionaryExpanded,
                                    onToggleDictionary = { dictionaryExpanded = !dictionaryExpanded },
                                    onSeeLexiconDetail = {
                                        scope.launch { listState.animateScrollToKey("lexicon-heading") }
                                    },
                                    onOpenWiktionary = uriHandler::openUri,
                                )
                            }
                        }
                    }

                    if (!morph?.root.isNullOrBlank() && ui.occurrenceCount > 0) {
                        item(key = "occurrences-heading") {
                            ProseMeasure(Modifier.padding(top = 40.dp)) {
                                RootSectionTitle("Occurrences", OCCURRENCES_HELP)
                                Text(
                                    text = "This root occurs ${times(ui.occurrenceCount)} across " +
                                        "${sections.size} ${if (sections.size == 1) "chapter" else "chapters"}.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Spacer(Modifier.height(20.dp))
                            }
                        }

                        val visibleSections = if (showAllChapters) {
                            sections
                        } else {
                            initialRootSections(sections, ui.surahId)
                        }
                        visibleSections.forEach { section ->
                            val open = section.surahId == openSurahId
                            item(key = "chapter-${section.surahId}") {
                                ChapterHeading(section, open) {
                                    openSurahId = if (open) null else section.surahId
                                    showAllOccurrences = false
                                }
                            }
                            if (open) {
                                val visibleOccurrences = if (showAllOccurrences) {
                                    section.occurrences
                                } else {
                                    section.occurrences.take(ROOT_OCCURRENCE_PREVIEW_LIMIT)
                                }
                                items(
                                    items = visibleOccurrences,
                                    key = { "${it.surahId}:${it.ayahNumber}:${it.position}" },
                                ) { occurrence ->
                                    OccurrenceRow(
                                        occurrence = occurrence,
                                        isCurrent = occurrence.surahId == ui.surahId &&
                                            occurrence.ayahNumber == ui.ayah &&
                                            occurrence.position == ui.word?.position,
                                        onClick = {
                                            onJumpToOccurrence(
                                                occurrence.surahId,
                                                occurrence.ayahNumber,
                                            )
                                        },
                                    )
                                }
                                if (section.occurrences.size > ROOT_OCCURRENCE_PREVIEW_LIMIT) {
                                    item(key = "chapter-action-${section.surahId}") {
                                        val hidden = section.occurrences.size - visibleOccurrences.size
                                        TextAction(
                                            text = if (hidden > 0) {
                                                "Show $hidden more ${if (hidden == 1) "occurrence" else "occurrences"}"
                                            } else {
                                                "Show fewer occurrences"
                                            },
                                            startPadding = 40.dp,
                                        ) { showAllOccurrences = !showAllOccurrences }
                                    }
                                }
                            }
                        }
                        if (sections.size > ROOT_CHAPTER_PREVIEW_LIMIT) {
                            item(key = "chapters-action") {
                                val initiallyVisible = initialRootSections(sections, ui.surahId).size
                                TextAction(
                                    text = if (showAllChapters) {
                                        "Show fewer chapters"
                                    } else {
                                        "Show ${sections.size - initiallyVisible} more chapters"
                                    },
                                ) { showAllChapters = !showAllChapters }
                            }
                        }
                    }

                    if (relatedForms.isNotEmpty()) {
                        item(key = "related-heading") {
                            Column(Modifier.padding(top = 40.dp)) {
                                RootSectionTitle("Related forms", RELATED_FORMS_HELP)
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                        val visibleForms = if (showAllForms) {
                            relatedForms
                        } else {
                            relatedForms.take(ROOT_RELATED_FORM_PREVIEW_LIMIT)
                        }
                        items(visibleForms, key = { "form-${it.lemma}-${it.pos}" }) {
                            RelatedFormRow(it)
                        }
                        if (relatedForms.size > ROOT_RELATED_FORM_PREVIEW_LIMIT) {
                            item(key = "forms-action") {
                                TextAction(
                                    text = if (showAllForms) {
                                        "Show fewer forms"
                                    } else {
                                        "Show ${relatedForms.size - visibleForms.size} more forms"
                                    },
                                ) { showAllForms = !showAllForms }
                            }
                        }
                    }

                    ui.lexicon?.let { entry ->
                        item(key = "lexicon-heading") {
                            Column(Modifier.padding(top = 40.dp)) {
                                RootSectionTitle("Classical lexicon", lexiconHelp(entry))
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                        item(key = "lexicon-entry") {
                            LexiconArticle(
                                entry = entry,
                                expanded = lexiconExpanded,
                                onToggle = { lexiconExpanded = !lexiconExpanded },
                                onOpenOnline = uriHandler::openUri,
                            )
                        }
                    }

                    ui.word?.let { word ->
                        item(key = "online-references") {
                            OnlineReferences(
                                references = rootViewerReferences(
                                    surahId = ui.surahId,
                                    ayah = ui.ayah,
                                    position = word.position,
                                    root = morph?.root.orEmpty(),
                                ),
                                onOpen = uriHandler::openUri,
                                modifier = Modifier.padding(top = 40.dp),
                            )
                        }
                    }

                    item(key = "attribution") {
                        Text(
                            text = "Morphology from the Quranic Arabic Corpus v0.4 — corpus.quran.com",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .quietClickable { uriHandler.openUri("https://corpus.quran.com") }
                                .padding(top = 56.dp, bottom = 32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProseMeasure(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = modifier
                .widthIn(max = 544.dp)
                .fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
private fun RootMessage(text: String, padding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Space between Root / Lemma (DESIGN.md proximity scale). */
private val AnalysisGroupGap = 28.dp

/**
 * Label → first value under Root / Lemma. Kept as a [Spacer] (not Text
 * padding) so large Hafs glyphs cannot paint up into the gap.
 */
private val AnalysisLabelToValue = 12.dp
/** Lemma title → Arabic/senses — a hair more air than Root. */
private val LemmaLabelToValue = 20.dp

/** Quiet ink for the lemma↔dictionary column rule. */
private val LemmaConnectorAlpha = 0.22f

/** Shared face size for Root radicals and Lemma Arabic. */
private val AnalysisArabicSize = 32.sp
private val AnalysisArabicLineHeight = 40.sp
private val AnalysisArabicBearingCrop = 16.dp

/**
 * Gutter between lemma and glosses: long horizontal stub on the shared
 * baseline, then a vertical column rule down the sense stack. Single-sense
 * rows split the gutter evenly so the elbow sits midway (see
 * [LemmaSingleSenseRow]).
 */
private val LemmaGutterWidth = 52.dp
private val LemmaGutterPaddingStart = 16.dp
private val LemmaGutterPaddingEnd = 18.dp
private val LemmaGlossGap = 10.dp
private val LemmaMetaGap = 2.dp
/** Air between the sense stack (or bare lemma) and grammar / frequency. */
private val LemmaToMetaGap = 20.dp

/** Shared face for Root Form‑1 lead and Lemma dictionary glosses. */
private val AnalysisGlossAlpha = 0.9f
private val AnalysisGlossSize = 18.sp
private val AnalysisGlossLineHeight = 22.sp

@Composable
private fun analysisGlossStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    fontSize = AnalysisGlossSize,
    lineHeight = AnalysisGlossLineHeight,
    fontWeight = FontWeight.Medium,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Top,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * First value under an analysis label. Top-aligned with ascent kept in-box
 * (no Trim.Both — that let Hafs paint up through the spacer).
 */
private fun analysisValueStyle(
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
): TextStyle = TextStyle(
    fontFamily = fontFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Top,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * Hafs ships a large empty top bearing; without this, ROOT's 32sp radicals sit
 * visibly farther under the label than GRAMMAR's Latin line after the same
 * [AnalysisLabelToValue] spacer. Pulls ink up and shortens layout by [crop].
 */
private fun Modifier.tightenHafsTopBearing(crop: Dp): Modifier {
    if (crop <= 0.dp) return this
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val cut = crop.roundToPx().coerceIn(0, placeable.height / 2)
        layout(placeable.width, placeable.height - cut) {
            placeable.placeRelative(0, -cut)
        }
    }
}

/**
 * Word analysis rhythm (DESIGN.md proximity scale):
 * [AnalysisLabelToValue] label→value, 10–12dp within one fact,
 * [AnalysisGroupGap] between groups.
 */
@Composable
private fun WordAnalysis(
    morph: com.beautifulquran.data.model.WordMorphology,
    lemmas: List<RootLemmaSummary>,
    lexiconText: String? = null,
    dictionary: com.beautifulquran.data.model.DictionaryEntry? = null,
    dictionaryExpanded: Boolean = false,
    onToggleDictionary: () -> Unit = {},
    onSeeLexiconDetail: (() -> Unit)? = null,
    onOpenWiktionary: (String) -> Unit = {},
) {
    val rootSense = remember(lexiconText) { lexiconText?.let { lexiconRootSense(it) } }
    val multiForm = remember(lexiconText) {
        lexiconText != null && lexiconFormCount(lexiconText) > 1
    }
    val grammar = listOf(
        MorphologyLabels.posLabel(morph.pos).takeIf { morph.pos.isNotBlank() },
        MorphologyLabels.featureSummary(morph.features).takeIf { it.isNotBlank() },
    ).filterNotNull().joinToString(" · ")
    val lemmaCount = lemmas.filter { it.lemma == morph.lemma }.sumOf { it.occurrenceCount }
    val hasRoot = morph.root.isNotBlank()
    val hasLemma = morph.lemma.isNotBlank()

    // spacedBy — not per-group top padding — so Root→Lemma share one gap.
    Column(verticalArrangement = Arrangement.spacedBy(AnalysisGroupGap)) {
        Column {
            RootLabel("Root", ROOT_HELP, iconNudgePx = 2)
            Spacer(Modifier.height(AnalysisLabelToValue))
            if (hasRoot) {
                Text(
                    text = MorphologyLabels.spacedRoot(morph.root),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = analysisValueStyle(
                        AnalysisArabicSize,
                        AnalysisArabicLineHeight,
                        HafsFontFamily,
                    ),
                    modifier = Modifier.tightenHafsTopBearing(AnalysisArabicBearingCrop),
                )
                if (rootSense != null) {
                    val bodyColor = MaterialTheme.colorScheme.onSurface.copy(alpha = AnalysisGlossAlpha)
                    val citationColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    val annotated = remember(rootSense, bodyColor, citationColor) {
                        lexiconAnnotated(rootSense, bodyColor, citationColor)
                    }
                    Text(
                        text = annotated,
                        style = analysisGlossStyle(),
                        color = bodyColor,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (multiForm && onSeeLexiconDetail != null) {
                    AnalysisActionLink(
                        text = "See more detail",
                        onClick = onSeeLexiconDetail,
                        modifier = Modifier.padding(
                            top = if (rootSense != null) 10.dp else 12.dp,
                        ),
                    )
                }
            } else {
                Text(
                    text = "No lexical root is annotated for this word.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.None,
                        ),
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                )
            }
        }
        if (hasLemma) {
            Column {
                val lemmaHelp = if (dictionary != null) {
                    "$LEMMA_HELP $DICTIONARY_HELP"
                } else {
                    LEMMA_HELP
                }
                RootLabel("Lemma", lemmaHelp, iconNudgePx = 1)
                Spacer(Modifier.height(LemmaLabelToValue))
                if (dictionary != null) {
                    LemmaWithDictionary(
                        lemma = morph.lemma,
                        entry = dictionary,
                        qacPos = morph.pos,
                        grammar = grammar,
                        lemmaCount = lemmaCount,
                        expanded = dictionaryExpanded,
                        onToggle = onToggleDictionary,
                        onOpenOnline = onOpenWiktionary,
                    )
                } else {
                    Text(
                        text = morph.lemma,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = analysisValueStyle(
                            AnalysisArabicSize,
                            AnalysisArabicLineHeight,
                            HafsFontFamily,
                        ),
                        modifier = Modifier.tightenHafsTopBearing(AnalysisArabicBearingCrop),
                    )
                    LemmaMeta(
                        grammar = grammar,
                        lemmaCount = lemmaCount,
                        modifier = Modifier.padding(top = LemmaToMetaGap),
                    )
                }
            }
        }
    }
}

/**
 * Quiet text action under Root / Lemma. Same line box for every trailing
 * link so Material's 48dp min-touch enlargement cannot pull Root→Lemma and
 * lemma dictionary links apart when one label has descenders / an arrow.
 */
@Composable
private fun AnalysisActionLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = modifier
                .quietClickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun RootLabel(
    text: String,
    explanation: String? = null,
    modifier: Modifier = Modifier,
    iconNudgePx: Int = 1,
) {
    ExplainedHeading(
        text = text.uppercase(),
        explanation = explanation,
        modifier = modifier,
        titleRowHeight = 16.dp,
        iconNudgePx = iconNudgePx,
        textContent = {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                // Keep EB Garamond (a bare TextStyle drops LocalTextStyle's
                // serif and falls back to system sans). Trim matches the ⓘ
                // so CenterVertically stays even between ROOT and LEMMA.
                style = TextStyle(
                    fontFamily = SerifFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.56.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
            )
        },
    )
}

@Composable
private fun RootSectionTitle(text: String, explanation: String? = null) {
    ExplainedHeading(
        text = text,
        explanation = explanation,
        textContent = {
            Text(
                text = it,
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 29.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        afterTitle = {
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)),
            )
        },
    )
}

@Composable
private fun ExplainedHeading(
    text: String,
    explanation: String?,
    modifier: Modifier = Modifier,
    /** When set (analysis labels), locks ROOT/LEMMA ⓘ to one vertical center. */
    titleRowHeight: Dp? = null,
    /** Optical lift for the ⓘ, in px (ROOT needs a hair more than LEMMA). */
    iconNudgePx: Int = 0,
    textContent: @Composable (String) -> Unit,
    afterTitle: @Composable RowScope.() -> Unit = {},
) {
    var expanded by remember(text) { mutableStateOf(false) }
    val iconNudge = with(LocalDensity.current) { (-iconNudgePx).toDp() }
    Column(modifier) {
        Row(
            modifier = titleRowHeight?.let { Modifier.height(it) } ?: Modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            textContent(text)
            if (explanation != null) {
                // Layout height matches the label line (16.dp); the 40.dp hit
                // target overflows so it stays centered on the glyphs without
                // lifting the ⓘ above ROOT / LEMMA / GRAMMAR.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .then(
                            if (iconNudgePx != 0) Modifier.offset(y = iconNudge) else Modifier,
                        )
                        .size(16.dp),
                ) {
                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .requiredSize(40.dp)
                                .quietClickable(role = Role.Button) { expanded = !expanded }
                                .semantics {
                                    contentDescription = "Explain ${text.lowercase()}"
                                    stateDescription =
                                        if (expanded) "Expanded" else "Collapsed"
                                },
                        ) {
                            Text(
                                text = "ⓘ",
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    lineHeight = 14.sp,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both,
                                    ),
                                ),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            afterTitle()
        }
        AnimatedVisibility(
            visible = expanded && explanation != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
        ) {
            Text(
                text = explanation.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun OnlineReferences(
    references: List<RootViewerReference>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        RootSectionTitle("Learn more online")
        Spacer(Modifier.height(12.dp))
        references.forEach { reference ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .quietClickable(role = Role.Button) { onOpen(reference.url) }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = "${reference.title}  ↗",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = reference.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ChapterHeading(section: RootOccurrenceSection, open: Boolean, onClick: () -> Unit) {
    val gold = LocalQuranAccents.current.gold
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .quietClickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.surahId.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = gold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = section.surahName,
                fontFamily = DisplayFontFamily,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.occurrences.size.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (open) {
                    Icons.Rounded.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun OccurrenceRow(occurrence: RootOccurrence, isCurrent: Boolean, onClick: () -> Unit) {
    val gold = LocalQuranAccents.current.gold
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(start = 40.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Text(
            text = "${occurrence.surahId}:${occurrence.ayahNumber}${if (isCurrent) " · Here" else ""}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.onSurface
            } else {
                gold
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = occurrence.arabic,
                fontFamily = HafsFontFamily,
                fontSize = 24.sp,
                lineHeight = 36.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (occurrence.translation.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = occurrence.translation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RelatedFormRow(form: RootLemmaSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = form.lemma,
                    fontFamily = HafsFontFamily,
                    fontSize = 24.sp,
                    lineHeight = 36.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = MorphologyLabels.posLabel(form.pos),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                )
            }
            Text(
                text = if (form.occurrenceCount == 1) "once" else "${form.occurrenceCount}×",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (form.gloss.isNotBlank()) {
            Text(
                text = form.gloss,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Arabic lemma left, Wiktionary senses right — one shared baseline, long
 * gutter stub, vertical column rule down the sense stack. A single sense
 * centers the elbow in that gutter. Grammar / frequency sit under the
 * senses; action links under that.
 */
@Composable
private fun LemmaWithDictionary(
    lemma: String,
    entry: com.beautifulquran.data.model.DictionaryEntry,
    qacPos: String,
    grammar: String,
    lemmaCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenOnline: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glosses = remember(entry, qacPos) { dictionaryGlosses(entry, qacPos) }
    val visible = if (expanded) glosses else glosses.take(DICTIONARY_PREVIEW_SENSES)
    val glossColor = MaterialTheme.colorScheme.onSurface.copy(alpha = AnalysisGlossAlpha)
    // No POS labels in the Lemma section; keep it to plain glosses.
    val showPosLabels = false
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = LemmaConnectorAlpha)
    val glossStyle = analysisGlossStyle()

    Column(modifier.fillMaxWidth()) {
        if (visible.size == 1) {
            LemmaSingleSenseRow(
                lemma = lemma,
                gloss = visible[0].let { (posLabel, gloss) ->
                    if (showPosLabels && posLabel != null) "$posLabel · $gloss" else gloss
                },
                lineColor = lineColor,
                glossStyle = glossStyle,
                glossColor = glossColor,
            )
        } else {
            LemmaSenseStackRow(
                lemma = lemma,
                visible = visible,
                showPosLabels = showPosLabels,
                lineColor = lineColor,
                glossStyle = glossStyle,
                glossColor = glossColor,
                expanded = expanded,
                canExpand = dictionaryNeedsExpand(glosses.size),
                onToggle = onToggle,
            )
        }
        LemmaMeta(
            grammar = grammar,
            lemmaCount = lemmaCount,
            modifier = Modifier.padding(top = LemmaToMetaGap),
        )
        AnalysisActionLink(
            text = "Open on Wiktionary  ↗",
            onClick = { onOpenOnline(wiktionaryArabicUrl(entry.word)) },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * One gloss: horizontal stub from the Arabic to the English edge, sitting on
 * the shared baseline, with a vertical rule at that edge half above / half
 * below the stub.
 */
@Composable
private fun LemmaSingleSenseRow(
    lemma: String,
    gloss: String,
    lineColor: Color,
    glossStyle: TextStyle,
    glossColor: Color,
) {
    val spineHeight = with(LocalDensity.current) { AnalysisGlossLineHeight.toDp() }
    val spineWidthPx = with(LocalDensity.current) { 1.dp.toPx() }
    val stubOffsetPx = with(LocalDensity.current) { 4.dp.toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Text(
            text = lemma,
            color = MaterialTheme.colorScheme.onSurface,
            style = analysisValueStyle(
                AnalysisArabicSize,
                AnalysisArabicLineHeight,
                HafsFontFamily,
            ),
            modifier = Modifier
                .alignByBaseline()
                .tightenHafsTopBearing(AnalysisArabicBearingCrop),
        )
        Box(
            modifier = Modifier
                .padding(horizontal = LemmaGutterPaddingStart)
                .width(LemmaGutterWidth)
                .height(spineHeight)
                // Centre the gutter on the shared baseline so the stub sits on
                // the baseline and the vertical rule is half above / half below.
                .alignBy { it.measuredHeight / 2 }
                .drawBehind {
                    val y = size.height / 2f - stubOffsetPx
                    val rightX = size.width - spineWidthPx / 2f
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(rightX, y),
                        strokeWidth = spineWidthPx,
                    )
                    drawLine(
                        color = lineColor,
                        start = Offset(rightX, -stubOffsetPx),
                        end = Offset(rightX, size.height - stubOffsetPx),
                        strokeWidth = spineWidthPx,
                    )
                },
        )
        Text(
            text = gloss,
            style = glossStyle,
            color = glossColor,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
        )
    }
}

/** Multi-sense stack: fixed stub, column rule down the English side. */
@Composable
private fun LemmaSenseStackRow(
    lemma: String,
    visible: List<Pair<String?, String>>,
    showPosLabels: Boolean,
    lineColor: Color,
    glossStyle: TextStyle,
    glossColor: Color,
    expanded: Boolean,
    canExpand: Boolean,
    onToggle: () -> Unit,
) {
    val spineWidthPx = with(LocalDensity.current) { 1.dp.toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Text(
            text = lemma,
            color = MaterialTheme.colorScheme.onSurface,
            style = analysisValueStyle(
                AnalysisArabicSize,
                AnalysisArabicLineHeight,
                HafsFontFamily,
            ),
            modifier = Modifier
                .alignByBaseline()
                .tightenHafsTopBearing(AnalysisArabicBearingCrop),
        )
        Box(
            modifier = Modifier
                .padding(start = LemmaGutterPaddingStart)
                .width(LemmaGutterWidth)
                .height(1.dp)
                .alignBy { it.measuredHeight }
                .background(lineColor),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .alignByBaseline()
                .drawBehind {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = spineWidthPx,
                    )
                }
                .padding(start = LemmaGutterPaddingEnd),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(LemmaGlossGap)) {
                visible.forEachIndexed { index, row ->
                    val posLabel = row.first
                    val gloss = row.second
                    // One child per sense so spacedBy is even; first sense
                    // is a single Text so FirstBaseline locks to lemma.
                    if (index == 0) {
                        Text(
                            text = if (showPosLabels && posLabel != null) {
                                "$posLabel · $gloss"
                            } else {
                                gloss
                            },
                            style = glossStyle,
                            color = glossColor,
                        )
                    } else {
                        Column {
                            if (showPosLabels && posLabel != null) {
                                Text(
                                    text = posLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = 0.55f,
                                    ),
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            Text(
                                text = gloss,
                                style = glossStyle,
                                color = glossColor,
                            )
                        }
                    }
                }
            }
            if (canExpand) {
                AnalysisActionLink(
                    text = if (expanded) "Show less" else "Show more",
                    onClick = onToggle,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Quiet grammar / frequency between lemma senses and the action links. */
@Composable
private fun LemmaMeta(
    grammar: String,
    lemmaCount: Int,
    modifier: Modifier = Modifier,
) {
    if (grammar.isBlank() && lemmaCount <= 0) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LemmaMetaGap),
    ) {
        if (grammar.isNotBlank()) {
            Text(
                text = grammar,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                style = lemmaMetaStyle(),
            )
        }
        if (lemmaCount > 0) {
            Text(
                text = "This lemma occurs ${times(lemmaCount)}.",
                style = lemmaMetaStyle(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
            )
        }
    }
}

@Composable
private fun lemmaMetaStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontSize = 14.sp,
    lineHeight = 18.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Top,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * Lane's article for the open root. Collapsed to its opening senses — the
 * longest run to some 99,000 characters — with the whole article a tap away.
 *
 * Form labels, sense breaks, and the morphology→gloss pivot become spaced
 * blocks; his parenthetical source marks recede so the English can be read.
 */
@Composable
private fun LexiconArticle(
    entry: com.beautifulquran.data.model.LexiconEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenOnline: (String) -> Unit,
) {
    val text = remember(entry.text, expanded) { lexiconArticleText(entry.text, expanded) }
    val blocks = remember(text) { lexiconBlocks(text) }
    val bodyColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
    val citationColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val onlineUrl = remember(entry.root) { laneLexiconUrl(entry.root) }

    Column(modifier = Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val top = when {
                index == 0 -> 0.dp
                block.form != null -> 28.dp // new Form section
                else -> 12.dp // sense within the same Form
            }
            LexiconBlockView(
                block = block,
                bodyColor = bodyColor,
                citationColor = citationColor,
                modifier = Modifier.padding(top = top),
            )
        }
        if (entry.text.length > LEXICON_PREVIEW_CHARS || expanded) {
            TextAction(
                text = if (expanded) "Show less of the entry" else "Read the whole entry",
                onClick = onToggle,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .quietClickable(role = Role.Button) { onOpenOnline(onlineUrl) }
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = "Open the full entry online  ↗",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Lane on arabiclexicon.hawramani.com — the complete article for this root.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun LexiconBlockView(
    block: LexiconBlock,
    bodyColor: Color,
    citationColor: Color,
    modifier: Modifier = Modifier,
) {
    val gold = LocalQuranAccents.current.gold
    Column(modifier.fillMaxWidth()) {
        block.form?.let { form ->
            Text(
                text = form,
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                color = gold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (block.text.isNotEmpty()) {
            val annotated = remember(block.text, bodyColor, citationColor) {
                lexiconAnnotated(block.text, bodyColor, citationColor)
            }
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 32.sp,
                color = bodyColor,
            )
        }
    }
}

/** Arabic in the mushaf face; Lane's source marks at quieter ink. */
private fun lexiconAnnotated(
    text: String,
    bodyColor: Color,
    citationColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        val runs = lexiconRuns(text)
        runs.forEachIndexed { index, run ->
            when {
                run.isArabic -> withStyle(
                    SpanStyle(fontFamily = HafsFontFamily, fontSize = 19.sp, color = bodyColor),
                ) { append(run.text) }
                run.isCitation -> withStyle(SpanStyle(color = citationColor, fontSize = 15.sp)) {
                    append(run.text)
                }
                else -> append(run.text)
            }
            // Keep "see" glued to its Arabic target across the style boundary.
            val next = runs.getOrNull(index + 1)
            if (run.isCitation && next?.isArabic == true) append("\u2060")
        }
    }

/**
 * Scrolls a LazyColumn to the item with [key], probing near the end when the
 * target is not yet laid out (Classical lexicon sits below long concordance).
 */
private suspend fun LazyListState.animateScrollToKey(key: Any) {
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let {
        animateScrollToItem(it.index)
        return
    }
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    var guess = last
    while (guess >= 0) {
        scrollToItem(guess)
        layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let {
            animateScrollToItem(it.index)
            return
        }
        guess -= layoutInfo.visibleItemsInfo.size.coerceAtLeast(3)
    }
}

@Composable
private fun TextAction(text: String, startPadding: Dp = 0.dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .quietClickable(role = Role.Button, onClick = onClick)
            .padding(start = startPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun WordHeader(word: Word, isPlaying: Boolean, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Balance the speaker and its gap so the Arabic itself stays centered.
        Spacer(Modifier.width(32.dp))
        Text(
            text = word.arabic,
            fontFamily = HafsFontFamily,
            fontSize = 48.sp,
            lineHeight = 68.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        WordSpeakerButton(isPlaying, onPlay, 22.dp)
    }
    if (word.translation.isNotBlank()) {
        Text(
            text = word.translation,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
    if (word.transliteration.isNotBlank()) {
        Text(
            text = word.transliteration,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}

@Composable
private fun CollapsedWordTitle(
    word: Word,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onScrollToTop: () -> Unit,
) {
    fun Modifier.scrollToTop(): Modifier =
        quietClickable(role = Role.Button, onClick = onScrollToTop)
            .semantics { contentDescription = "Scroll to top" }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(26.dp))
            Text(
                text = word.arabic,
                fontFamily = HafsFontFamily,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.scrollToTop(),
            )
            Spacer(Modifier.width(8.dp))
            WordSpeakerButton(isPlaying, onPlay, 18.dp)
        }
        if (word.translation.isNotBlank()) {
            Text(
                text = word.translation,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.scrollToTop(),
            )
        }
    }
}

@Composable
private fun WordSpeakerButton(isPlaying: Boolean, onPlay: () -> Unit, size: Dp) {
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
        contentDescription = "Play word",
        tint = MaterialTheme.colorScheme.primary.copy(alpha = if (isPlaying) 0.9f else 0.65f),
        modifier = Modifier
            .size(size)
            .quietClickable(onClick = onPlay)
            .semantics { role = Role.Button },
    )
}

private fun times(count: Int): String = if (count == 1) "once" else "$count times"
