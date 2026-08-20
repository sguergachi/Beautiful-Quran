package com.beautifulquran.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class RecitationUsage(
    val listenBytes: Long = 0L,
    val keepBytes: Long = 0L,
) {
    val total: Long get() = listenBytes + keepBytes
}

/**
 * Recitation audio in two trees:
 *  - listen: [Context.getCacheDir]/audio, 1 GB LRU — playback and prefetch
 *  - keep: [Context.getFilesDir]/audio, no LRU — chapters the reader downloaded
 *
 * Playback reads keep first, then listen, and writes only listen.
 * Explicit Download writes keep (copying from listen when the ayah is already there).
 */
internal object RecitationCache {

    const val MAX_BYTES = 1024L * 1024 * 1024

    private val trimStarted = AtomicBoolean(false)
    private val relocated = AtomicBoolean(false)
    private val lock = Any()
    private var db: StandaloneDatabaseProvider? = null
    private var listenCache: SimpleCache? = null
    private var keepCache: SimpleCache? = null

    fun listenDir(context: Context) = File(context.cacheDir, "audio")

    fun keepDir(context: Context) = File(context.filesDir, "audio")

    fun usage(context: Context) = RecitationUsage(
        listenBytes = directorySize(listenDir(context)),
        keepBytes = directorySize(keepDir(context)),
    )

    /** Fast live total once the caches are open; avoids walking every audio file. */
    fun indexedUsage(context: Context) = RecitationUsage(
        listenBytes = listen(context).cacheSpace,
        keepBytes = keep(context).cacheSpace,
    )

    fun usedBytes(context: Context): Long = usage(context).total

    fun close() {
        synchronized(lock) {
            listenCache?.release()
            keepCache?.release()
            listenCache = null
            keepCache = null
        }
    }

    fun listen(context: Context): SimpleCache = synchronized(lock) {
        maybeRelocateLocked(context)
        listenCache ?: SimpleCache(
            listenDir(context).also { it.mkdirs() },
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            database(context),
        ).also { listenCache = it }
    }

    fun keep(context: Context): SimpleCache = synchronized(lock) {
        maybeRelocateLocked(context)
        keepCache ?: SimpleCache(
            keepDir(context).also { it.mkdirs() },
            NoOpCacheEvictor(),
            database(context),
        ).also { keepCache = it }
    }

    /** Playback and prefetch write target — the evictable tree. */
    fun get(context: Context): SimpleCache = listen(context)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playbackDataSourceFactory(
        context: Context,
        upstream: DataSource.Factory,
    ): ResolvingDataSource.Factory {
        val listenCds = CacheDataSource.Factory()
            .setCache(listen(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val keepRead = CacheDataSource.Factory()
            .setCache(keep(context))
            .setUpstreamDataSourceFactory(listenCds)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ResolvingDataSource.Factory(keepRead) { allowCacheWhenLengthUnknown(it) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun downloadDataSourceFactory(
        context: Context,
        upstream: DataSource.Factory,
    ): CacheDataSource.Factory {
        val readListen = CacheDataSource.Factory()
            .setCache(listen(context))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return CacheDataSource.Factory()
            .setCache(keep(context))
            .setUpstreamDataSourceFactory(readListen)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun clearListen(context: Context) {
        synchronized(lock) {
            val cache = listenCache
            if (cache != null) empty(cache) else listenDir(context).deleteRecursively()
        }
    }

    fun clearKeep(context: Context) {
        synchronized(lock) {
            val cache = keepCache
            if (cache != null) empty(cache) else keepDir(context).deleteRecursively()
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            listenCache?.let { empty(it) } ?: listenDir(context).deleteRecursively()
            keepCache?.let { empty(it) } ?: keepDir(context).deleteRecursively()
        }
    }

    fun removeKey(context: Context, key: String) {
        listen(context).removeResource(key)
        keep(context).removeResource(key)
    }

    fun allKeys(context: Context): Set<String> = LinkedHashSet<String>().apply {
        addAll(listen(context).keys)
        addAll(keep(context).keys)
    }

    fun cachedBytes(context: Context, key: String): Long = cachedBytesForKey(
        listen(context).getCachedBytes(key, /* position = */ 0, Long.MAX_VALUE),
        keep(context).getCachedBytes(key, /* position = */ 0, Long.MAX_VALUE),
    )

    fun isFullyCached(context: Context, key: String): Boolean =
        isFullyCached(keep(context), key) || isFullyCached(listen(context), key)

    /** Drop the listen copy once keep fully holds this ayah. */
    fun dropListenIfKept(context: Context, key: String) {
        synchronized(lock) {
            val keep = keepCache ?: return
            val listen = listenCache ?: return
            if (isFullyCached(keep, key)) listen.removeResource(key)
        }
    }

    fun prepare(context: Context) {
        if (!trimStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            dropListenCopiesLocked(app)
            trim(listen(app))
        }, "recitation-cache").apply {
            isDaemon = true
            start()
        }
    }

    internal fun spansToEvict(
        spans: Collection<CacheSpan>,
        usedBytes: Long,
        maxBytes: Long = MAX_BYTES,
    ): List<CacheSpan> {
        if (usedBytes <= maxBytes) return emptyList()
        var extra = usedBytes - maxBytes
        val out = ArrayList<CacheSpan>()
        for (span in spans.sortedBy { it.lastTouchTimestamp }) {
            if (extra <= 0L) break
            out += span
            extra -= span.length
        }
        return out
    }

    private fun database(context: Context): StandaloneDatabaseProvider {
        db?.let { return it }
        return StandaloneDatabaseProvider(context.applicationContext).also { db = it }
    }

    private fun maybeRelocateLocked(context: Context) {
        if (!relocated.compareAndSet(false, true)) return
        relocateLegacyAudioOnce(
            marker = File(context.noBackupFilesDir, "legacy-recitation-cache-moved-v1"),
            from = keepDir(context),
            to = listenDir(context),
        )
    }

    private fun empty(cache: SimpleCache) {
        for (key in cache.keys.toList()) cache.removeResource(key)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun isFullyCached(cache: SimpleCache, key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length != C.LENGTH_UNSET.toLong() &&
            cache.getCachedBytes(key, 0, length) >= length
    }

    private fun dropListenCopiesLocked(context: Context) {
        synchronized(lock) {
            maybeRelocateLocked(context)
            val keep = keep(context)
            val listen = listen(context)
            for (key in keep.keys.toList()) {
                if (isFullyCached(keep, key)) listen.removeResource(key)
            }
        }
    }

    private fun trim(cache: SimpleCache) {
        val spans = cache.keys.flatMap { key -> cache.getCachedSpans(key) }
            .filter { it.isCached }
        for (span in spansToEvict(spans, cache.cacheSpace)) {
            cache.removeSpan(span)
        }
    }
}

/**
 * Progressive playback sends [DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN], so
 * streamed ayahs never land in [SimpleCache]. Listening *is* downloading —
 * drop that flag so the bytes we already fetched stay on disk.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun allowCacheWhenLengthUnknown(dataSpec: DataSpec): DataSpec {
    val hide = DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN
    if (dataSpec.flags and hide == 0) return dataSpec
    return dataSpec.buildUpon().setFlags(dataSpec.flags and hide.inv()).build()
}

internal fun directorySize(dir: File): Long {
    if (!dir.exists()) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

internal fun formatBytesAmount(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes < 1024L * 1024L -> "< 1 MB"
    else -> "${bytes / (1024L * 1024L)} MB"
}

internal fun formatDownloadedBytes(bytes: Long): String = when {
    bytes <= 0L -> "None downloaded"
    bytes < 1024L * 1024L -> "< 1 MB downloaded"
    else -> "${bytes / (1024L * 1024L)} MB downloaded"
}

internal fun formatUsage(usage: RecitationUsage): String =
    formatDownloadedBytes(usage.total)

/** Keep wins: a downloaded ayah must not count twice in listen + keep. */
internal fun cachedBytesForKey(listenBytes: Long, keepBytes: Long): Long =
    if (keepBytes > 0L) keepBytes else listenBytes

internal fun isCacheStubFile(file: File): Boolean {
    val name = file.name
    return name.endsWith(".uid") || name.endsWith(".lock") || name == ".nomedia"
}

/** True when [dir] holds ayah files, not just SimpleCache uid/lock stubs. */
internal fun hasCachedAudio(dir: File): Boolean {
    if (!dir.isDirectory) return false
    return dir.walkTopDown().any { it.isFile && !isCacheStubFile(it) }
}

/**
 * Move [from] onto [to] when [to] is missing, empty, or a uid/lock stub.
 * If both already hold ayah files, leave [from] so the trees can report
 * separately.
 */
internal fun relocateAudioDir(from: File, to: File) {
    if (!from.exists() || from == to) return
    if (!hasCachedAudio(from)) {
        from.deleteRecursively()
        return
    }
    if (to.exists() && (to.list().isNullOrEmpty() || !hasCachedAudio(to))) {
        to.deleteRecursively()
    }
    if (!to.exists()) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        from.copyRecursively(to)
        from.deleteRecursively()
        return
    }
}

/** The old filesDir LRU is moved at most once; later filesDir audio is permanent. */
internal fun relocateLegacyAudioOnce(marker: File, from: File, to: File) {
    marker.parentFile?.mkdirs()
    if (!marker.createNewFile()) return
    relocateAudioDir(from, to)
}
