package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Surah
import com.beautifulquran.domain.surahOpensWithBasmalahPreface
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal data class ChapterDownload(
    val surah: Surah,
    val cached: Int,
    val bytes: Long = 0L,
) {
    val ayahCount: Int get() = surah.ayahCount
    val complete: Boolean get() = cached >= ayahCount
    val empty: Boolean get() = cached <= 0 && bytes <= 0L
}

internal data class ReciterDownloads(
    val reciter: Reciter,
    val chapters: List<ChapterDownload>,
) {
    val cachedAyahs: Int get() = chapters.sumOf { it.cached }
    val bytes: Long get() = chapters.sumOf { it.bytes }
}

internal data class EveryayahRef(
    val slug: String,
    val surah: Int,
    val ayah: Int,
)

internal data class CachedAyah(val bytes: Long, val complete: Boolean)

internal data class ChapterRef(val reciterId: Int, val surahId: Int)

internal data class DownloadRequest(val reciter: Reciter, val surah: Surah) {
    val ref: ChapterRef get() = ChapterRef(reciter.id, surah.id)
}

internal data class DownloadProgress(
    val running: Boolean = false,
    val paused: Boolean = false,
    val reciterName: String = "",
    val surahName: String = "",
    val reciterId: Int = 0,
    val surahId: Int = 0,
    val ayah: Int = 0,
    val ayahCount: Int = 0,
    val queued: List<ChapterRef> = emptyList(),
    /** Disk changed after the last catalog scan; UI must not render that scan as truth. */
    val reconciling: Map<ChapterRef, Long> = emptyMap(),
)

internal fun isChapterReconciling(
    progress: DownloadProgress,
    reciterId: Int,
    surahId: Int,
): Boolean = ChapterRef(reciterId, surahId) in progress.reconciling

internal fun isReciterReconciling(progress: DownloadProgress, reciterId: Int): Boolean =
    progress.reconciling.keys.any { it.reciterId == reciterId }

/** Live controls stay authoritative while a background disk scan catches up. */
internal fun isChapterActionSettling(
    progress: DownloadProgress,
    reciterId: Int,
    surahId: Int,
): Boolean = isChapterReconciling(progress, reciterId, surahId) &&
    !isChapterDownloading(progress, reciterId, surahId) &&
    !isChapterWaiting(progress, reciterId, surahId) &&
    !isChapterPaused(progress, reciterId, surahId)

internal fun isReciterActionSettling(progress: DownloadProgress, reciterId: Int): Boolean =
    isReciterReconciling(progress, reciterId) && !isReciterBusy(progress, reciterId)

/** An older scan may acknowledge only the exact revisions it actually read. */
internal fun remainingReconciliations(
    current: Map<ChapterRef, Long>,
    acknowledged: Map<ChapterRef, Long>,
): Map<ChapterRef, Long> = current.filter { (ref, revision) ->
    acknowledged[ref] != revision
}

internal fun mergeDownloadQueue(
    pending: List<DownloadRequest>,
    incoming: List<DownloadRequest>,
    active: DownloadRequest?,
): List<DownloadRequest> {
    val seen = LinkedHashSet<ChapterRef>()
    if (active != null) seen += active.ref
    pending.forEach { seen += it.ref }
    val out = pending.toMutableList()
    for (req in incoming) {
        if (seen.add(req.ref)) out += req
    }
    return out
}

/** Parked chapter stays first. Active is already in flight and is not queued. */
internal fun nextDownloadRequest(
    parked: DownloadRequest?,
    pending: List<DownloadRequest>,
): Pair<DownloadRequest?, List<DownloadRequest>> {
    if (parked != null) return parked to pending.filter { it.ref != parked.ref }
    if (pending.isEmpty()) return null to pending
    return pending.first() to pending.drop(1)
}

/** A resumed parked chapter remains visible while the worker claims it. */
internal fun visibleDownloadRequest(
    active: DownloadRequest?,
    parked: DownloadRequest?,
): DownloadRequest? = active ?: parked

internal fun parkedAfterDelete(
    parked: DownloadRequest?,
    reciterId: Int,
    surahId: Int? = null,
): DownloadRequest? {
    val p = parked ?: return null
    if (p.reciter.id != reciterId) return p
    if (surahId != null && p.surah.id != surahId) return p
    return null
}

/** Collapsed Resume continues partials; empty chapters stay off Download all. */
internal fun reciterResumeSurahs(
    chapters: List<ChapterDownload>,
    paused: Boolean,
): List<Surah> = if (paused) {
    emptyList()
} else {
    chapters.filter { !it.complete && !it.empty }.map { it.surah }
}

internal fun isChapterDownloading(progress: DownloadProgress, reciterId: Int, surahId: Int): Boolean =
    progress.running && !progress.paused &&
        progress.reciterId == reciterId && progress.surahId == surahId

internal fun isChapterWaiting(progress: DownloadProgress, reciterId: Int, surahId: Int): Boolean {
    if (!progress.queued.any { it.reciterId == reciterId && it.surahId == surahId }) return false
    // This reciter is paused: queued chapters are paused, not waiting.
    if (progress.paused && progress.reciterId == reciterId) return false
    return true
}

internal fun isChapterPaused(progress: DownloadProgress, reciterId: Int, surahId: Int): Boolean {
    if (!progress.paused) return false
    if (progress.reciterId == reciterId && progress.surahId == surahId) return true
    return progress.reciterId == reciterId &&
        progress.queued.any { it.reciterId == reciterId && it.surahId == surahId }
}

internal fun isReciterDownloading(progress: DownloadProgress, reciterId: Int): Boolean =
    progress.running && !progress.paused && progress.reciterId == reciterId

internal fun isReciterBusy(progress: DownloadProgress, reciterId: Int): Boolean =
    (progress.running || progress.paused) && (
        progress.reciterId == reciterId ||
            progress.queued.any { it.reciterId == reciterId }
        )

internal fun downloadPercent(ayah: Int, ayahCount: Int): Int? {
    if (ayahCount <= 0) return null
    if (ayah <= 0) return 0
    return ((100 * ayah) / ayahCount).coerceIn(1, 100)
}

/**
 * Queue updates must not wipe the in-flight ayah clock. Passing both [ayah]
 * and [ayahCount] is a real tick; otherwise keep the previous pair when the
 * same chapter is still active.
 */
internal fun retainedDownloadClock(
    activeReciterId: Int,
    activeSurahId: Int,
    prev: DownloadProgress,
    ayah: Int?,
    ayahCount: Int?,
): Pair<Int, Int> {
    if (ayah != null && ayahCount != null) return ayah to ayahCount
    val same = prev.reciterId == activeReciterId && prev.surahId == activeSurahId
    return if (same) prev.ayah to prev.ayahCount else 0 to 0
}

/** Reciter-subtitle progress. No reciter name — that line already names them. */
internal fun reciterProgressLabel(progress: DownloadProgress, reciterId: Int): String {
    if (!progress.running && !progress.paused) return ""
    val parts = mutableListOf<String>()
    if (progress.paused && progress.reciterId == reciterId) parts += "Paused"
    if (progress.reciterId == reciterId && progress.surahName.isNotEmpty()) {
        parts += progress.surahName
        downloadPercent(progress.ayah, progress.ayahCount)?.let { parts += "$it%" }
    }
    val waiting = progress.queued.count { it.reciterId == reciterId }
    if (waiting > 0) parts += if (waiting == 1) "1 waiting" else "$waiting waiting"
    return parts.joinToString(" · ")
}

/** Expanded catalog owns live state; collapsed header summarises it. */
internal fun reciterHeaderSubtitle(
    expanded: Boolean,
    liveLabel: String,
    catalogLabel: String,
): String = if (!expanded && liveLabel.isNotEmpty()) liveLabel else catalogLabel

internal fun reciterHeaderAction(
    expanded: Boolean,
    busy: Boolean,
    paused: Boolean,
    hasDownloadable: Boolean,
    hasBytes: Boolean,
    confirming: Boolean,
    hasResumable: Boolean = false,
): String? = when {
    confirming -> null
    paused && !expanded -> "Resume"
    busy && !expanded -> "Pause"
    !expanded && hasResumable -> "Resume"
    !expanded && hasBytes -> "Delete"
    expanded && hasDownloadable -> "Download all"
    else -> null
}

internal fun reciterHeaderActionIsFetch(action: String): Boolean =
    action == "Download all" || action == "Pause" || action == "Resume"

internal fun verseCountLabel(count: Int): String =
    if (count == 1) "1 verse" else "$count verses"

internal fun chapterFactLine(
    row: ChapterDownload,
    downloading: Boolean,
    waiting: Boolean,
    paused: Boolean,
    percent: Int? = null,
): String {
    val verses = verseCountLabel(row.ayahCount)
    return when {
        downloading -> {
            val p = percent?.let { " · $it%" }.orEmpty()
            verses + p
        }
        waiting -> "$verses · Waiting"
        paused || (!row.empty && !row.complete) -> buildString {
            append(verses)
            if (!row.empty && !row.complete) append(" · ${row.cached} of ${row.ayahCount}")
            if (row.bytes > 0L) append(" · ${formatBytesAmount(row.bytes)}")
        }
        row.complete -> if (row.bytes > 0L) {
            "$verses · ${formatBytesAmount(row.bytes)}"
        } else {
            verses
        }
        else -> verses
    }
}

internal fun chapterActionLabel(
    row: ChapterDownload,
    downloading: Boolean,
    waiting: Boolean,
    paused: Boolean,
): String = when {
    downloading || waiting -> "Pause"
    paused || (!row.complete && !row.empty) -> "Resume"
    row.complete -> "Delete"
    else -> "Download"
}

/** Paused or partial chapters also offer Delete to the left of Resume. */
internal fun chapterOffersDelete(
    row: ChapterDownload,
    downloading: Boolean,
    waiting: Boolean,
    paused: Boolean,
): Boolean = !downloading && !waiting &&
    (paused || (!row.complete && !row.empty))

/**
 * Trailing chapter verbs, left to right. Pause flips in place to Resume;
 * Delete sits to its left so Resume stays on the right edge.
 */
internal fun chapterTrailingLabels(action: String, alsoDelete: Boolean): List<String> =
    if (alsoDelete) listOf("Delete", action) else listOf(action)

internal fun chapterActionIsFetch(action: String): Boolean =
    action == "Download" || action == "Pause" || action == "Resume"

private val EVERYAYAH_URI =
    Regex("""everyayah\.com/data/([^/]+)/(\d{3})(\d{3})\.mp3""")

internal fun parseEveryayahUri(uri: String): EveryayahRef? {
    val match = EVERYAYAH_URI.find(uri) ?: return null
    return EveryayahRef(
        slug = match.groupValues[1],
        surah = match.groupValues[2].toInt(),
        ayah = match.groupValues[3].toInt(),
    )
}

/** MediaItem id `surah:ayah:reciterId` — used if a cache key is the media id. */
internal fun parseMediaCacheKey(key: String): Triple<Int, Int, Int>? {
    val parts = key.split(':')
    if (parts.size != 3) return null
    val surah = parts[0].toIntOrNull() ?: return null
    val ayah = parts[1].toIntOrNull() ?: return null
    val reciterId = parts[2].toIntOrNull() ?: return null
    return Triple(surah, ayah, reciterId)
}

/** Trailing chapter state. Empty rows say Download so the fetch is visible. */
internal fun chapterStateLabel(
    row: ChapterDownload,
    downloading: Boolean,
    waiting: Boolean,
    percent: Int? = null,
): String? = when {
    downloading -> percent?.let { "$it%" } ?: "Downloading"
    waiting -> "Waiting"
    row.complete -> if (row.bytes > 0L) formatBytesAmount(row.bytes) else null
    !row.empty -> "${row.cached} of ${row.ayahCount}"
    else -> "Download"
}

internal fun chapterStateIsLive(downloading: Boolean, waiting: Boolean): Boolean =
    downloading || waiting

/** Green fetch states — empty, partial, live — not settled size. */
internal fun chapterStateIsAction(
    row: ChapterDownload,
    downloading: Boolean,
    waiting: Boolean,
): Boolean = downloading || waiting || !row.complete

internal fun reciterDownloadLabel(row: ReciterDownloads): String {
    val done = row.chapters.count { it.complete }
    val size = if (row.bytes > 0L) formatBytesAmount(row.bytes) else null
    val count = when {
        row.bytes <= 0L -> "Nothing downloaded"
        done == row.chapters.size -> "All chapters"
        else -> "$done of ${row.chapters.size}"
    }
    return if (size != null) "$count · $size" else count
}

internal fun countCachedAyahs(ayahCount: Int, cached: (ayah: Int) -> Boolean): Int {
    var n = 0
    for (ayah in 1..ayahCount) if (cached(ayah)) n++
    return n
}

internal fun isUriFullyCached(cache: Cache, uri: String): Boolean {
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(uri))
    return length != C.LENGTH_UNSET.toLong() &&
        cache.getCachedBytes(uri, 0, length) >= length
}

internal fun hitsFromCacheKeys(
    keys: Iterable<String>,
    reciters: List<Reciter> = emptyList(),
    cachedBytes: (String) -> Long,
    fullyCached: (String) -> Boolean,
): Map<EveryayahRef, CachedAyah> {
    val slugs = reciters.associate { it.id to it.slug }
    val out = LinkedHashMap<EveryayahRef, CachedAyah>()
    for (key in keys) {
        val n = cachedBytes(key)
        if (n <= 0L) continue
        val ref = parseEveryayahUri(key) ?: parseMediaCacheKey(key)?.let { id ->
            if (id.second <= 0) null else slugs[id.third]?.let { EveryayahRef(it, id.first, id.second) }
        } ?: continue
        val previous = out[ref]
        out[ref] = CachedAyah(
            bytes = (previous?.bytes ?: 0L) + n,
            complete = previous?.complete == true || fullyCached(key),
        )
    }
    return out
}

internal fun cacheKeysForChapter(
    keys: Iterable<String>,
    reciter: Reciter,
    surahId: Int,
): List<String> = keys.filter { key ->
    val uri = parseEveryayahUri(key)
    if (uri != null) return@filter uri.slug == reciter.slug && uri.surah == surahId
    val id = parseMediaCacheKey(key) ?: return@filter false
    id.third == reciter.id && id.first == surahId
}

internal fun cacheKeysForReciter(
    keys: Iterable<String>,
    reciter: Reciter,
): List<String> = keys.filter { key ->
    val uri = parseEveryayahUri(key)
    if (uri != null) return@filter uri.slug == reciter.slug
    val id = parseMediaCacheKey(key) ?: return@filter false
    id.third == reciter.id
}

internal fun reciterDownloads(
    reciters: List<Reciter>,
    surahs: List<Surah>,
    ayahs: Map<EveryayahRef, CachedAyah>,
): List<ReciterDownloads> = reciters.map { reciter ->
    ReciterDownloads(
        reciter = reciter,
        chapters = surahs.map { surah ->
            ChapterDownload(
                surah = surah,
                cached = countCachedAyahs(surah.ayahCount) { ayah ->
                    ayahs[EveryayahRef(reciter.slug, surah.id, ayah)]?.complete == true
                },
                bytes = (1..surah.ayahCount).sumOf { ayah ->
                    ayahs[EveryayahRef(reciter.slug, surah.id, ayah)]?.bytes ?: 0L
                },
            )
        },
    )
}

/** Basmalah is a real first playlist item for every chapter except 1 and 9. */
internal fun chapterAudioRequests(reciter: Reciter, surah: Surah): List<Pair<Int, String>> =
    buildList {
        if (surahOpensWithBasmalahPreface(surah.id)) add(0 to reciter.basmalahAudioUrl())
        for (ayah in 1..surah.ayahCount) add(ayah to reciter.audioUrl(surah.id, ayah))
    }

internal object RecitationDownloads {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = ArrayDeque<DownloadRequest>()
    private var active: DownloadRequest? = null
    private var skipActive = false
    private var stopped = false
    private var paused = false
    private var parked: DownloadRequest? = null
    private var job: Job? = null
    private var activeWriter: CacheWriter? = null
    private var activeWriterFinished: CountDownLatch? = null
    private val reconciling = LinkedHashMap<ChapterRef, Long>()
    private var reconciliationRevision = 0L

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    fun scan(
        context: android.content.Context,
        reciters: List<Reciter>,
        surahs: List<Surah>,
    ): List<ReciterDownloads> {
        val app = context.applicationContext
        val ayahs = hitsFromCacheKeys(
            keys = RecitationCache.allKeys(app),
            reciters = reciters,
            cachedBytes = { key -> RecitationCache.cachedBytes(app, key) },
            fullyCached = { key -> RecitationCache.isFullyCached(app, key) },
        )
        return reciterDownloads(reciters, surahs, ayahs)
    }

    /** Release only transitions represented by the scan the UI just applied. */
    fun acknowledgeReconciled(snapshot: Map<ChapterRef, Long>) {
        synchronized(lock) {
            val remaining = remainingReconciliations(reconciling, snapshot)
            reconciling.clear()
            reconciling.putAll(remaining)
            publishLocked()
        }
    }

    fun downloadAll(context: android.content.Context, reciter: Reciter, surahs: List<Surah>) {
        enqueue(context, surahs.map { DownloadRequest(reciter, it) })
    }

    fun downloadChapter(context: android.content.Context, reciter: Reciter, surah: Surah) {
        enqueue(context, listOf(DownloadRequest(reciter, surah)))
    }

    private fun cancel(): CountDownLatch? {
        val running: Job?
        val writerFinished: CountDownLatch?
        synchronized(lock) {
            stopped = true
            paused = false
            parked = null
            pending.clear()
            active = null
            skipActive = true
            running = job
            job = null
            writerFinished = cancelWriterLocked()
        }
        running?.cancel()
        _progress.value = DownloadProgress()
        return writerFinished
    }

    fun pauseChapter(reciterId: Int, surahId: Int) {
        synchronized(lock) {
            if (active?.reciter?.id == reciterId && active?.surah?.id == surahId) {
                paused = true
                skipActive = true
                parked = active
                cancelWriterLocked()
            } else {
                pending.removeAll { it.reciter.id == reciterId && it.surah.id == surahId }
            }
            publishLocked()
        }
    }

    fun pauseReciter(reciterId: Int) {
        synchronized(lock) {
            if (active?.reciter?.id == reciterId) {
                paused = true
                skipActive = true
                parked = active
                cancelWriterLocked()
            } else {
                pending.removeAll { it.reciter.id == reciterId }
            }
            publishLocked()
        }
    }

    fun resume(context: android.content.Context) {
        val app = context.applicationContext
        synchronized(lock) {
            paused = false
            stopped = false
            skipActive = false
            if (active?.ref == parked?.ref) parked = null
            publishLocked()
            if (job?.isActive != true) {
                job = scope.launch { runQueue(app) }
            }
        }
    }

    fun clearKept(context: android.content.Context) {
        cancel()?.await()
        RecitationCache.clearKeep(context)
    }

    fun clearListen(context: android.content.Context) {
        RecitationCache.clearListen(context)
    }

    fun clearChapter(context: android.content.Context, reciter: Reciter, surah: Surah) {
        val app = context.applicationContext
        val writerFinished: CountDownLatch?
        synchronized(lock) {
            pending.removeAll { it.reciter.id == reciter.id && it.surah.id == surah.id }
            if (active?.reciter?.id == reciter.id && active?.surah?.id == surah.id) {
                skipActive = true
                writerFinished = cancelWriterLocked()
            } else {
                writerFinished = null
            }
            parked = parkedAfterDelete(parked, reciter.id, surah.id)
            if (parked == null) paused = false
            publishLocked()
            if (!paused && job?.isActive != true && pending.isNotEmpty()) {
                job = scope.launch { runQueue(app) }
            }
        }
        writerFinished?.await()
        for (key in cacheKeysForChapter(RecitationCache.allKeys(app), reciter, surah.id)) {
            RecitationCache.removeKey(app, key)
        }
    }

    fun clearReciter(context: android.content.Context, reciter: Reciter) {
        val app = context.applicationContext
        val writerFinished: CountDownLatch?
        synchronized(lock) {
            pending.removeAll { it.reciter.id == reciter.id }
            if (active?.reciter?.id == reciter.id) {
                skipActive = true
                writerFinished = cancelWriterLocked()
            } else {
                writerFinished = null
            }
            parked = parkedAfterDelete(parked, reciter.id)
            if (parked == null) paused = false
            publishLocked()
            if (!paused && job?.isActive != true && pending.isNotEmpty()) {
                job = scope.launch { runQueue(app) }
            }
        }
        writerFinished?.await()
        for (key in cacheKeysForReciter(RecitationCache.allKeys(app), reciter)) {
            RecitationCache.removeKey(app, key)
        }
    }

    private fun enqueue(context: android.content.Context, incoming: List<DownloadRequest>) {
        val app = context.applicationContext
        val startWorker: Boolean
        synchronized(lock) {
            stopped = false
            paused = false
            skipActive = false
            if (active?.ref == parked?.ref) parked = null
            val merged = mergeDownloadQueue(
                pending.toList(),
                incoming,
                active ?: parked,
            )
            pending.clear()
            pending.addAll(merged)
            publishLocked()
            startWorker = job?.isActive != true
            if (startWorker) {
                job = scope.launch { runQueue(app) }
            }
        }
    }

    private suspend fun runQueue(app: android.content.Context) {
        val keep = RecitationCache.keep(app)
        val writer = RecitationCache.downloadDataSourceFactory(app, httpFactory())
        try {
            while (true) {
                val req = synchronized(lock) {
                    if (paused || stopped) return@synchronized null
                    skipActive = false
                    val (next, rest) = nextDownloadRequest(parked, pending.toList())
                    parked = if (next != null && parked?.ref == next.ref) null else parked
                    pending.clear()
                    pending.addAll(rest)
                    active = next
                    if (next != null) publishLocked()
                    next
                } ?: break
                try {
                    for ((ayah, uri) in chapterAudioRequests(req.reciter, req.surah)) {
                        coroutineContext.ensureActive()
                        if (synchronized(lock) { skipActive || paused }) break
                        synchronized(lock) { publishLocked(ayah, req.surah.ayahCount) }
                        if (isUriFullyCached(keep, uri)) {
                            RecitationCache.dropListenIfKept(app, uri)
                            continue
                        }
                        val cacheWriter = CacheWriter(
                            writer.createDataSource(),
                            DataSpec.Builder().setUri(uri).build(),
                            null,
                            null,
                        )
                        val writerFinished = CountDownLatch(1)
                        val shouldCache = synchronized(lock) {
                            if (skipActive || paused || stopped) {
                                false
                            } else {
                                activeWriter = cacheWriter
                                activeWriterFinished = writerFinished
                                true
                            }
                        }
                        if (!shouldCache) break
                        try {
                            cacheWriter.cache()
                            RecitationCache.dropListenIfKept(app, uri)
                        } catch (_: IOException) {
                            // Best-effort; the player can fetch on demand.
                        } catch (_: InterruptedException) {
                            return
                        } finally {
                            synchronized(lock) {
                                if (activeWriter === cacheWriter) {
                                    activeWriter = null
                                    activeWriterFinished = null
                                }
                                writerFinished.countDown()
                            }
                        }
                    }
                } finally {
                    synchronized(lock) {
                        reconciling[req.ref] = ++reconciliationRevision
                        if (paused && parked == null && active == req) parked = req
                        if (active == req) active = null
                        publishLocked()
                    }
                }
            }
        } finally {
            val leftover: List<DownloadRequest>
            synchronized(lock) {
                job = null
                leftover = when {
                    stopped -> emptyList()
                    paused -> {
                        publishLocked()
                        emptyList()
                    }
                    else -> pending.toList()
                }
                if (!paused) {
                    active = null
                    publishLocked()
                }
            }
            if (leftover.isNotEmpty()) enqueue(app, emptyList())
        }
    }

    private fun publishLocked(ayah: Int? = null, ayahCount: Int? = null) {
        val a = visibleDownloadRequest(active, parked)
        val q = pending.map { it.ref }
        if (paused && a != null) {
            val clock = retainedDownloadClock(
                a.reciter.id,
                a.surah.id,
                _progress.value,
                ayah,
                ayahCount,
            )
            _progress.value = DownloadProgress(
                running = false,
                paused = true,
                reciterName = a.reciter.name,
                surahName = a.surah.nameTransliteration,
                reciterId = a.reciter.id,
                surahId = a.surah.id,
                ayah = clock.first,
                ayahCount = clock.second,
                queued = q,
                reconciling = reconciling.toMap(),
            )
            return
        }
        _progress.value = if (a == null && q.isEmpty()) {
            DownloadProgress(reconciling = reconciling.toMap())
        } else {
            val clock = if (a != null) {
                retainedDownloadClock(
                    a.reciter.id,
                    a.surah.id,
                    _progress.value,
                    ayah,
                    ayahCount,
                )
            } else {
                0 to 0
            }
            DownloadProgress(
                running = true,
                reciterName = a?.reciter?.name.orEmpty(),
                surahName = a?.surah?.nameTransliteration.orEmpty(),
                reciterId = a?.reciter?.id ?: 0,
                surahId = a?.surah?.id ?: 0,
                ayah = clock.first,
                ayahCount = clock.second,
                queued = q,
                reconciling = reconciling.toMap(),
            )
        }
    }

    /** CacheWriter is blocking; deletion waits for this exact write to yield. */
    private fun cancelWriterLocked(): CountDownLatch? {
        activeWriter?.cancel()
        return activeWriterFinished
    }

    private fun httpFactory() = DefaultHttpDataSource.Factory()
        .setUserAgent("BeautifulQuran/1.0")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
        .setAllowCrossProtocolRedirects(true)
}
