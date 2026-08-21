package com.beautifulquran.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beautifulquran.QuranApp
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Surah
import com.beautifulquran.playback.ChapterDownload
import com.beautifulquran.playback.ChapterRef
import com.beautifulquran.playback.RecitationCache
import com.beautifulquran.playback.RecitationDownloads
import com.beautifulquran.playback.RecitationUsage
import com.beautifulquran.playback.ReciterDownloads
import com.beautifulquran.playback.chapterActionIsFetch
import com.beautifulquran.playback.chapterActionLabel
import com.beautifulquran.playback.chapterFactLine
import com.beautifulquran.playback.chapterOffersDelete
import com.beautifulquran.playback.chapterProgressFactLine
import com.beautifulquran.playback.chapterProgressFraction
import com.beautifulquran.playback.chapterTrailingLabels
import com.beautifulquran.playback.downloadPercent
import com.beautifulquran.playback.formatUsage
import com.beautifulquran.playback.isChapterActionSettling
import com.beautifulquran.playback.isChapterDownloading
import com.beautifulquran.playback.isChapterPaused
import com.beautifulquran.playback.isChapterReconciling
import com.beautifulquran.playback.isChapterWaiting
import com.beautifulquran.playback.isReciterActionSettling
import com.beautifulquran.playback.isReciterBusy
import com.beautifulquran.playback.isReciterPaused
import com.beautifulquran.playback.reciterDownloadLabel
import com.beautifulquran.playback.reciterHeaderAction
import com.beautifulquran.playback.reciterHeaderActionIsFetch
import com.beautifulquran.playback.reciterHeaderSubtitle
import com.beautifulquran.playback.reciterProgressLabel
import com.beautifulquran.playback.reciterResumeSurahs
import com.beautifulquran.ui.theme.DisclosureChevron
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.verticalFadingEdges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class Pending {
    data object DeleteAll : Pending()
    data class DeleteChapter(val reciterId: Int, val surahId: Int) : Pending()
    data class DeleteReciter(val reciterId: Int) : Pending()
}

private fun Pending?.affectsReciter(reciterId: Int): Boolean = when (this) {
    Pending.DeleteAll -> true
    is Pending.DeleteReciter -> this.reciterId == reciterId
    is Pending.DeleteChapter -> this.reciterId == reciterId
    null -> false
}

private fun Pending?.affectsChapter(reciterId: Int, surahId: Int): Boolean = when (this) {
    Pending.DeleteAll -> true
    is Pending.DeleteReciter -> this.reciterId == reciterId
    is Pending.DeleteChapter -> this.reciterId == reciterId && this.surahId == surahId
    null -> false
}

/** Reserved trailing column so Keep / Delete never shove the name. */
private val ActionSlotWidth = 128.dp
/** Settings SelectRow rhythm — name + note, no forced cell height. */
private val RowPad = 8.dp
private const val ProgressInkAlpha = 0.72f

@Composable
internal fun DownloadManagerPage(
    reciters: List<Reciter>,
    surahs: List<Surah>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val progress by RecitationDownloads.progress.collectAsStateWithLifecycle()
    var usage by remember { mutableStateOf(RecitationUsage()) }
    var reciterRows by remember { mutableStateOf<List<ReciterDownloads>>(emptyList()) }
    var catalogLoaded by remember { mutableStateOf(false) }
    var expandedReciterIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var deleting by remember { mutableStateOf<Pending?>(null) }

    suspend fun refresh() {
        val snap = withContext(Dispatchers.IO) {
            RecitationDownloads.scan(context, reciters, surahs) to
                RecitationCache.indexedUsage(context)
        }
        reciterRows = snap.first
        usage = snap.second
        catalogLoaded = true
    }

    fun delete(target: Pending, remove: () -> Unit) {
        if (deleting != null) return
        pending = null
        deleting = target
        (context.applicationContext as QuranApp).player.stop()
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) { remove() }
            } finally {
                try {
                    refresh()
                } finally {
                    deleting = null
                }
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(reciters, surahs, lifecycle) {
        catalogLoaded = false
        while (isActive) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refresh()
            delay(2_000)
        }
    }
    LaunchedEffect(progress.running, progress.pausedChapters) {
        if (!progress.running && progress.reconciling.isEmpty()) refresh()
    }
    LaunchedEffect(progress.reconciling) {
        val snapshot = progress.reconciling
        if (snapshot.isNotEmpty()) {
            refresh()
            RecitationDownloads.acknowledgeReconciled(snapshot)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val visibleRows = if (catalogLoaded) {
            reciterRows
        } else {
            reciters.map { ReciterDownloads(it, emptyList()) }
        }
        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalFadingEdges(
                    color = MaterialTheme.colorScheme.background,
                    top = 20.dp,
                    bottom = 40.dp,
                ),
            contentPadding = PaddingValues(horizontal = 28.dp),
        ) {
            item(key = "download-manager-header") {
                Column {
                    Spacer(Modifier.height(20.dp))
                    BackChevron(onBack)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Download manager",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(36.dp))
                    when {
                        !catalogLoaded -> FactActionRow(
                            fact = "Downloads",
                            action = "…",
                            fetch = false,
                            enabled = false,
                            onAction = {},
                        )
                        pending == Pending.DeleteAll -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                        ) {
                            Text(
                                text = "Delete all downloads?",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            ConfirmActions(
                                confirmLabel = "Delete",
                                onKeep = { pending = null },
                                onConfirm = {
                                    delete(Pending.DeleteAll) {
                                        RecitationDownloads.clearKept(context)
                                        RecitationDownloads.clearListen(context)
                                    }
                                },
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                        deleting == Pending.DeleteAll -> FactActionRow(
                            fact = formatUsage(usage),
                            action = "…",
                            fetch = false,
                            enabled = false,
                            onAction = {},
                        )
                        usage.total <= 0L -> FactActionRow(
                            fact = formatUsage(usage),
                            action = null,
                            fetch = false,
                            enabled = false,
                            onAction = {},
                        )
                        else -> FactActionRow(
                            fact = formatUsage(usage),
                            action = "Delete all",
                            fetch = false,
                            enabled = deleting == null,
                            onAction = { pending = Pending.DeleteAll },
                        )
                    }
                }
            }

            item(key = "reciters-label") {
                Column {
                    Spacer(Modifier.height(32.dp))
                    SectionLabel("Reciters")
                    Spacer(Modifier.height(10.dp))
                }
            }
            visibleRows.forEachIndexed { index, reciterRow ->
                val reciterId = reciterRow.reciter.id
                val expanded = catalogLoaded && reciterId in expandedReciterIds
                val reconciling = isReciterActionSettling(progress, reciterId) ||
                    deleting.affectsReciter(reciterId)
                val downloadable = reciterRow.chapters.filter { ch ->
                    !ch.complete &&
                        !isChapterReconciling(progress, reciterId, ch.surah.id) &&
                        !isChapterDownloading(progress, reciterId, ch.surah.id) &&
                        !isChapterWaiting(progress, reciterId, ch.surah.id) &&
                        !isChapterPaused(progress, reciterId, ch.surah.id)
                }
                val busy = isReciterBusy(progress, reciterId)
                val hasPaused = isReciterPaused(progress, reciterId)
                val paused = hasPaused && !busy
                val confirmingDelete = pending == Pending.DeleteReciter(reciterId)
                val hasResumable = hasPaused || reciterRow.chapters.any { !it.complete && !it.empty }
                val headerAction = if (!catalogLoaded) {
                    null
                } else if (reconciling) {
                    "…"
                } else {
                    reciterHeaderAction(
                        expanded = expanded,
                        busy = busy,
                        paused = paused,
                        hasDownloadable = downloadable.isNotEmpty(),
                        hasBytes = reciterRow.bytes > 0L,
                        hasResumable = hasResumable,
                        confirming = confirmingDelete,
                    )
                }
                item(key = "reciter-$reciterId") {
                    Column {
                        if (index > 0) Spacer(Modifier.height(24.dp))
                        ReciterHeader(
                            name = reciterRow.reciter.name,
                            expanded = expanded,
                            subtitle = if (catalogLoaded) {
                                reciterHeaderSubtitle(
                                    expanded = expanded,
                                    liveLabel = reciterProgressLabel(progress, reciterId),
                                    catalogLabel = reciterDownloadLabel(reciterRow),
                                )
                            } else {
                                "…"
                            },
                            action = headerAction,
                            confirming = confirmingDelete,
                            actionEnabled = catalogLoaded && deleting == null && !reconciling,
                            onToggle = {
                                if (catalogLoaded) {
                                    pending = null
                                    expandedReciterIds = if (expanded) {
                                        expandedReciterIds - reciterId
                                    } else {
                                        expandedReciterIds + reciterId
                                    }
                                }
                            },
                            onAction = {
                                when (headerAction) {
                                    "Pause" -> RecitationDownloads.pauseReciter(reciterId)
                                    "Resume" -> {
                                        if (hasPaused) {
                                            RecitationDownloads.resumeReciter(context, reciterId)
                                        } else {
                                            RecitationDownloads.downloadAll(
                                                context,
                                                reciterRow.reciter,
                                                reciterResumeSurahs(
                                                    reciterRow.chapters,
                                                    paused = false,
                                                ),
                                            )
                                        }
                                    }
                                    "Delete" -> pending = Pending.DeleteReciter(reciterId)
                                    "Download all" -> RecitationDownloads.downloadAll(
                                        context,
                                        reciterRow.reciter,
                                        downloadable.map { it.surah },
                                    )
                                }
                            },
                            onKeep = { pending = null },
                            onDelete = {
                                delete(Pending.DeleteReciter(reciterId)) {
                                    RecitationDownloads.clearReciter(context, reciterRow.reciter)
                                }
                            },
                        )
                    }
                }
                if (expanded) {
                    itemsIndexed(
                        items = reciterRow.chapters,
                        key = { _, row -> "chapter-$reciterId-${row.surah.id}" },
                    ) { chapterIndex, row ->
                        val confirming = pending ==
                            Pending.DeleteChapter(reciterId, row.surah.id)
                        val downloading = isChapterDownloading(
                            progress, reciterId, row.surah.id,
                        )
                        val waiting = isChapterWaiting(
                            progress, reciterId, row.surah.id,
                        )
                        val chapterPaused = isChapterPaused(
                            progress, reciterId, row.surah.id,
                        )
                        val chapterReconciling = isChapterActionSettling(
                            progress, reciterId, row.surah.id,
                        ) || deleting.affectsChapter(reciterId, row.surah.id)
                        val pausedClock = progress.pausedClocks[
                            ChapterRef(reciterId, row.surah.id)
                        ]?.takeIf { it.ayahCount > 0 }
                        val pausedAyahs = pausedClock?.ayah ?: row.cached
                        val chapterPercent = when {
                            downloading -> downloadPercent(progress.ayah, progress.ayahCount)
                            chapterPaused -> downloadPercent(
                                pausedAyahs,
                                pausedClock?.ayahCount ?: row.ayahCount,
                            )
                            else -> null
                        }
                        val completedAyahs = when {
                            downloading -> progress.ayah
                            chapterPaused -> pausedAyahs
                            else -> null
                        }
                        Column {
                            if (chapterIndex == 0) {
                                Spacer(Modifier.height(16.dp))
                            }
                            ChapterRow(
                                row = row,
                                downloading = downloading,
                                waiting = waiting,
                                paused = chapterPaused,
                                completedAyahs = completedAyahs,
                                percent = chapterPercent,
                                confirming = confirming,
                                reconciling = chapterReconciling,
                                actionEnabled = deleting == null && !chapterReconciling,
                                onAction = { action ->
                                    when (action) {
                                        "Download" -> RecitationDownloads.downloadChapter(
                                            context,
                                            reciterRow.reciter,
                                            row.surah,
                                        )
                                        "Resume" -> RecitationDownloads.downloadChapter(
                                            context,
                                            reciterRow.reciter,
                                            row.surah,
                                        )
                                        "Pause" -> RecitationDownloads.pauseChapter(
                                            reciterId,
                                            row.surah.id,
                                        )
                                        "Delete" -> pending = Pending.DeleteChapter(
                                            reciterId,
                                            row.surah.id,
                                        )
                                    }
                                },
                                onKeep = { pending = null },
                                onDelete = {
                                    delete(Pending.DeleteChapter(reciterId, row.surah.id)) {
                                        RecitationDownloads.clearChapter(
                                            context,
                                            reciterRow.reciter,
                                            row.surah,
                                        )
                                    }
                                },
                            )
                            ChapterHairline(
                                progress = chapterProgressFraction(
                                    downloading = downloading,
                                    paused = chapterPaused,
                                    percent = chapterPercent,
                                    complete = row.complete,
                                ),
                            )
                        }
                    }
                }
            }
            item(key = "download-manager-tail") { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun ReciterHeader(
    name: String,
    expanded: Boolean,
    subtitle: String,
    action: String?,
    confirming: Boolean,
    actionEnabled: Boolean,
    onToggle: () -> Unit,
    onAction: () -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RowPad),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .quietClickable(onClick = onToggle),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = if (expanded) {
                            "Collapse $name"
                        } else {
                            "Expand $name"
                        }
                    },
            )
            DisclosureChevron(expanded = expanded)
        }
        if (confirming) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(top = 2.dp),
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ConfirmActions(
                    confirmLabel = "Delete",
                    onKeep = onKeep,
                    onConfirm = onDelete,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        } else if (action != null) {
            FactActionRow(
                fact = subtitle,
                action = action,
                fetch = reciterHeaderActionIsFetch(action),
                enabled = actionEnabled,
                onAction = onAction,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            FactActionRow(
                fact = subtitle,
                action = null,
                fetch = false,
                enabled = false,
                onAction = {},
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun FactActionRow(
    fact: String,
    action: String?,
    fetch: Boolean,
    enabled: Boolean = true,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
    ) {
        Text(
            text = fact,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (action != null) {
            ActionWord(
                label = action,
                fetch = fetch,
                enabled = enabled,
                onClick = onAction,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun ChapterHairline(progress: Float?) {
    val gold = LocalQuranAccents.current.gold
    val progressInk = MaterialTheme.colorScheme.onSurface.copy(alpha = ProgressInkAlpha)
    val fill by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = tween(durationMillis = 240),
        label = "chapter download progress",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(gold.copy(alpha = 0.22f)),
    ) {
        if (progress != null && fill > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .fillMaxHeight()
                    .background(progressInk),
            )
        }
    }
}

@Composable
private fun ConfirmActions(
    confirmLabel: String,
    onKeep: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        ActionWord(
            label = "Keep",
            fetch = true,
            onClick = onKeep,
            modifier = Modifier.padding(end = 16.dp),
        )
        ActionWord(
            label = confirmLabel,
            fetch = false,
            onClick = onConfirm,
        )
    }
}

/** One size for every trailing verb — Download, Resume, Pause, Delete. */
@Composable
private fun ActionWord(
    label: String,
    fetch: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (fetch) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
        },
        maxLines = 1,
        modifier = modifier
            .quietClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

@Composable
private fun ActionSlot(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier
            .widthIn(min = ActionSlotWidth)
            .heightIn(min = 44.dp)
            .padding(start = 12.dp),
    ) {
        content()
    }
}

@Composable
private fun ChapterRow(
    row: ChapterDownload,
    downloading: Boolean,
    waiting: Boolean,
    paused: Boolean,
    completedAyahs: Int?,
    percent: Int?,
    confirming: Boolean,
    reconciling: Boolean,
    actionEnabled: Boolean,
    onAction: (String) -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    val progressInk = MaterialTheme.colorScheme.onSurface.copy(alpha = ProgressInkAlpha)
    val facts = if (downloading || paused) {
        chapterProgressFactLine(
            completed = completedAyahs ?: 0,
            total = row.ayahCount,
            percent = percent.takeIf { downloading },
        )
    } else {
        chapterFactLine(row, downloading, waiting, paused)
    }
    val action = chapterActionLabel(row, downloading, waiting, paused)
    val alsoDelete = chapterOffersDelete(row, downloading, waiting, paused)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RowPad),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = row.surah.nameTransliteration,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = facts,
                style = MaterialTheme.typography.labelSmall,
                color = if (downloading || paused || row.complete) {
                    progressInk
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ActionSlot {
            if (confirming) {
                ConfirmActions(
                    confirmLabel = "Delete",
                    onKeep = onKeep,
                    onConfirm = onDelete,
                )
            } else if (reconciling) {
                ActionWord(
                    label = "…",
                    fetch = false,
                    enabled = false,
                    onClick = {},
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    chapterTrailingLabels(action, alsoDelete).forEachIndexed { i, label ->
                        ActionWord(
                            label = label,
                            fetch = chapterActionIsFetch(label),
                            enabled = actionEnabled,
                            onClick = { onAction(label) },
                            modifier = Modifier
                                .padding(start = if (i == 0) 0.dp else 16.dp)
                                .semantics {
                                    contentDescription =
                                        "$label ${row.surah.nameTransliteration}"
                                },
                        )
                    }
                }
            }
        }
    }
}
