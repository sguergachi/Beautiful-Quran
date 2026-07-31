package com.beautifulquran.ui.bookmarks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.data.model.BookmarkedAyah
import com.beautifulquran.data.model.Surah
import com.beautifulquran.ui.reader.VerseBookmarkRibbon
import com.beautifulquran.ui.reader.VERSE_ANNOTATION_INK_ALPHA
import com.beautifulquran.ui.reader.verseAnnotationStyle
import com.beautifulquran.ui.theme.ArabicTitleStyle
import com.beautifulquran.ui.theme.PaperSearchField
import com.beautifulquran.ui.theme.DisclosureChevron
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.verticalFadingEdges

private data class BookmarkKey(val surahId: Int, val ayahNumber: Int)

@Composable
fun BookmarksScreen(
    viewModel: BookmarksViewModel,
    onClose: () -> Unit,
    onOpenAyah: (surahId: Int, ayah: Int) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingRemoval by remember { mutableStateOf<BookmarkKey?>(null) }
    var expandedSurahs by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var collapsedSurahs by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val searching = uiState.query.isNotBlank()

    LaunchedEffect(uiState.query) { pendingRemoval = null }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalFadingEdges(
                        color = MaterialTheme.colorScheme.background,
                        top = 24.dp,
                        bottom = 48.dp,
                    ),
                contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
            ) {
                item(key = "title") {
                    BookmarksHeader(onClose)
                }
                item(key = "search") {
                    PaperSearchField(
                        value = uiState.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = "Search bookmarks, text, or 2:255",
                        contentDescription = "Search bookmarked verses",
                        clearContentDescription = "Clear bookmark search",
                        modifier = Modifier
                            .padding(start = 24.dp, top = 24.dp, end = 24.dp)
                            .fillMaxWidth(),
                    )
                }

                if (!uiState.loading && uiState.sections.isEmpty()) {
                    item(key = "empty") {
                        BookmarkEmptyState(uiState.query)
                    }
                }

                uiState.sections.forEachIndexed { index, section ->
                    val collapsed = isBookmarkSectionCollapsed(
                        section.surah.id,
                        collapsedSurahs,
                        searching,
                    )
                    item(key = "surah-${section.surah.id}") {
                        BookmarkSectionHeader(
                            surah = section.surah,
                            first = index == 0,
                            expanded = !collapsed,
                            collapsible = !searching,
                            onToggle = {
                                collapsedSurahs = if (collapsed) {
                                    collapsedSurahs - section.surah.id
                                } else {
                                    collapsedSurahs + section.surah.id
                                }
                                pendingRemoval = null
                            },
                        )
                    }
                    val expanded = section.surah.id in expandedSurahs
                    items(
                        items = if (collapsed) {
                            emptyList()
                        } else {
                            visibleBookmarkAyahs(section.ayahs, expanded, searching)
                        },
                        key = { "${it.surah.id}:${it.ayahNumber}" },
                    ) { bookmark ->
                        val key = BookmarkKey(bookmark.surah.id, bookmark.ayahNumber)
                        BookmarkAyahRow(
                            bookmark = bookmark,
                            annotationText = uiState.annotations["${bookmark.surah.id}:${bookmark.ayahNumber}"],
                            confirming = pendingRemoval == key,
                            onOpen = { onOpenAyah(bookmark.surah.id, bookmark.ayahNumber) },
                            onRequestRemove = { pendingRemoval = key },
                            onConfirmRemove = {
                                viewModel.removeBookmark(bookmark.surah.id, bookmark.ayahNumber)
                                pendingRemoval = null
                            },
                            onKeep = { pendingRemoval = null },
                        )
                    }
                    if (!collapsed && !searching &&
                        section.ayahs.size > BOOKMARK_SECTION_PREVIEW_LIMIT
                    ) {
                        item(key = "more-${section.surah.id}") {
                            BookmarkDisclosure(
                                hiddenCount = hiddenBookmarkCount(section.ayahs, expanded, searching),
                                expanded = expanded,
                                onClick = {
                                    expandedSurahs = if (expanded) {
                                        expandedSurahs - section.surah.id
                                    } else {
                                        expandedSurahs + section.surah.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksHeader(onClose: () -> Unit) {
    val focusManager = LocalFocusManager.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Top air comes from LazyColumn contentPadding (matches the soft edge).
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Bookmarks",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 36.sp,
                    lineHeight = 44.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .quietClickable(
                    role = Role.Button,
                    onClick = {
                        // Search may still hold focus after the sheet peels
                        // away; drop it so the IME does not follow to Chapters.
                        focusManager.clearFocus()
                        onClose()
                    },
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "Back to Chapters",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun BookmarkSectionHeader(
    surah: Surah,
    first: Boolean,
    expanded: Boolean,
    collapsible: Boolean,
    onToggle: () -> Unit,
) {
    val gold = LocalQuranAccents.current.gold
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (collapsible) {
                    Modifier
                        .quietClickable(role = Role.Button, onClick = onToggle)
                        .semantics(mergeDescendants = true) {
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 24.dp)
            .padding(top = if (first) 18.dp else 32.dp, bottom = 12.dp),
    ) {
        Text(
            text = surah.id.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "'kern' 1, 'liga' 1, 'lnum' 1, 'tnum' 1",
            ),
            color = gold,
            modifier = Modifier
                .width(40.dp)
                .padding(start = 4.dp),
        )
        Text(
            text = surah.nameTransliteration,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = surah.nameArabic,
            style = ArabicTitleStyle.copy(fontSize = 20.sp, lineHeight = 30.sp),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Spacer(Modifier.width(16.dp))
        DisclosureChevron(
            expanded = expanded,
            pointsLeftWhenCollapsed = true,
            modifier = Modifier.alpha(if (collapsible) 1f else 0f),
        )
    }
}

@Composable
private fun BookmarkAyahRow(
    bookmark: BookmarkedAyah,
    annotationText: String?,
    confirming: Boolean,
    onOpen: () -> Unit,
    onRequestRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
    onKeep: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Balance the ribbon spine so the verse copy stays centered
                // between equal reading gutters.
                .padding(horizontal = 40.dp)
                .quietClickable(onClick = onOpen),
        ) {
            Text(
                text = bookmark.text,
                style = ArabicTitleStyle.copy(fontSize = 24.sp, lineHeight = 36.sp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = bookmark.translation,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.84f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (annotationText != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = annotationText,
                    style = verseAnnotationStyle(fontSize = 15.sp, lineHeight = 21.sp),
                    color = LocalQuranAccents.current.annotationInk.copy(alpha = VERSE_ANNOTATION_INK_ALPHA),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BookmarkReferenceOrConfirmation(
                bookmark = bookmark,
                confirming = confirming,
                onKeep = onKeep,
                onRemove = onConfirmRemove,
            )
        }
        Box(Modifier.matchParentSize()) {
            VerseBookmarkRibbon(
                bookmarked = true,
                focused = true,
                side = AyahSelectorSide.LEFT,
                chromeAlpha = { 1f },
                interactive = true,
                onToggle = {
                    onRequestRemove()
                    true
                },
                animateOnTap = false,
                edgeInset = 2.dp,
                topInset = 0.dp,
                bottomGap = 12.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .semantics {
                        contentDescription =
                            "Remove bookmark ${bookmark.surah.id}:${bookmark.ayahNumber}"
                    },
            )
        }
    }
}

@Composable
private fun BookmarkReferenceOrConfirmation(
    bookmark: BookmarkedAyah,
    confirming: Boolean,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
) {
    val keepFocus = remember { FocusRequester() }
    LaunchedEffect(confirming) { if (confirming) keepFocus.requestFocus() }
    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
        AnimatedContent(
            targetState = confirming,
            transitionSpec = {
                (fadeIn() + slideInHorizontally { it / 8 }) togetherWith
                    (fadeOut() + slideOutHorizontally { -it / 8 })
            },
            label = "bookmark confirmation",
        ) { asking ->
            if (asking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        "Remove this bookmark?",
                        style = bookmarkMetadataStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        "Keep",
                        style = bookmarkMetadataStyle(FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .focusRequester(keepFocus)
                            .quietClickable(role = Role.Button, onClick = onKeep)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                    Text(
                        "Remove",
                        style = bookmarkMetadataStyle(FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                        modifier = Modifier
                            .quietClickable(role = Role.Button, onClick = onRemove)
                            .padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Ayah",
                        style = bookmarkMetadataStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = bookmark.ayahNumber.toString(),
                        style = bookmarkMetadataStyle(FontWeight.Medium, numeric = true),
                        color = LocalQuranAccents.current.gold,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkDisclosure(hiddenCount: Int, expanded: Boolean, onClick: () -> Unit) {
    Text(
        text = bookmarkDisclosureLabel(hiddenCount, expanded),
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 17.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(role = Role.Button, onClick = onClick)
            .padding(start = 64.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
    )
}

@Composable
private fun BookmarkEmptyState(query: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp),
    ) {
        Text(
            text = if (query.isBlank()) {
                "Your marked verses will gather here."
            } else {
                "No marked verse matches “${query.trim()}”."
            },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
            textAlign = TextAlign.Center,
        )
        if (query.isBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Mark a verse in the reader to return to it later.",
                style = bookmarkMetadataStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun bookmarkMetadataStyle(
    weight: FontWeight = FontWeight.Normal,
    numeric: Boolean = false,
): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = weight,
        fontFeatureSettings = if (numeric) {
            "'kern' 1, 'liga' 1, 'lnum' 1, 'tnum' 1"
        } else {
            MaterialTheme.typography.bodyMedium.fontFeatureSettings
        },
    )
