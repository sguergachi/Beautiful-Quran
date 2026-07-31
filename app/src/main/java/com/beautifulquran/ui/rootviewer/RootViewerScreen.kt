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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.verticalFadingEdges

internal const val ROOT_CHAPTER_PREVIEW_LIMIT = 8
internal const val ROOT_OCCURRENCE_PREVIEW_LIMIT = 5
internal const val ROOT_RELATED_FORM_PREVIEW_LIMIT = 5

private const val ROOT_HELP =
    "Usually three consonants shared by a family of related words. A root points to a meaning family, not one fixed translation."
private const val LEMMA_HELP =
    "The form you'd look up in a dictionary for this word. Other endings of the same word share this lemma; the root is the wider family."
private const val GRAMMAR_HELP =
    "How this word functions here: its part of speech and features such as verb form, person, number, gender, case, voice, or mood."
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
                            CollapsedWordTitle(it, ui.isPlayingWord, viewModel::playCurrentWord)
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
                                WordAnalysis(morph, ui.lemmas)
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

@Composable
private fun WordAnalysis(
    morph: com.beautifulquran.data.model.WordMorphology,
    lemmas: List<RootLemmaSummary>,
) {
    if (morph.root.isNotBlank()) {
        RootLabel("Root", ROOT_HELP)
        Text(
            text = MorphologyLabels.spacedRoot(morph.root),
            fontFamily = HafsFontFamily,
            fontSize = 32.sp,
            lineHeight = 46.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (morph.lemma.isNotBlank() || morph.pos.isNotBlank()) {
        Column(Modifier.padding(top = if (morph.root.isBlank()) 0.dp else 24.dp)) {
            if (morph.lemma.isNotBlank()) {
                RootLabel("Lemma", LEMMA_HELP)
                Text(
                    text = morph.lemma,
                    fontFamily = HafsFontFamily,
                    fontSize = 24.sp,
                    lineHeight = 36.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val grammar = listOf(
                MorphologyLabels.posLabel(morph.pos).takeIf { morph.pos.isNotBlank() },
                MorphologyLabels.featureSummary(morph.features).takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" · ")
            if (grammar.isNotBlank()) {
                RootLabel(
                    text = "Grammar",
                    explanation = GRAMMAR_HELP,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = grammar,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val lemmaCount = lemmas.filter { it.lemma == morph.lemma }.sumOf { it.occurrenceCount }
            if (lemmaCount > 0) {
                Text(
                    text = "This lemma occurs ${times(lemmaCount)}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    if (morph.root.isBlank()) {
        Text(
            text = "No lexical root is annotated for this word.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun RootLabel(
    text: String,
    explanation: String? = null,
    modifier: Modifier = Modifier,
) {
    ExplainedHeading(
        text = text.uppercase(),
        explanation = explanation,
        modifier = modifier,
        textContent = {
            Text(
                text = it,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.56.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
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
    textContent: @Composable (String) -> Unit,
    afterTitle: @Composable RowScope.() -> Unit = {},
) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            textContent(text)
            if (explanation != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .quietClickable(role = Role.Button) { expanded = !expanded }
                        .semantics {
                            contentDescription = "Explain ${text.lowercase()}"
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "ⓘ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    )
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
    Column(modifier.fillMaxWidth()) {
        block.form?.let { form ->
            Text(
                text = form,
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
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
        for (run in lexiconRuns(text)) {
            when {
                run.isArabic -> withStyle(
                    SpanStyle(fontFamily = HafsFontFamily, fontSize = 19.sp, color = bodyColor),
                ) { append(run.text) }
                run.isCitation -> withStyle(SpanStyle(color = citationColor, fontSize = 15.sp)) {
                    append(run.text)
                }
                else -> append(run.text)
            }
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
private fun CollapsedWordTitle(word: Word, isPlaying: Boolean, onPlay: () -> Unit) {
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
