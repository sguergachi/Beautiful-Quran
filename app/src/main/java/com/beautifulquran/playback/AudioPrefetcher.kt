package com.beautifulquran.playback

import android.net.ConnectivityManager
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Warms the audio cache ahead of the listener so per-ayah streaming never
 * stutters and recently-heard surahs work offline. Fully automatic: driven by
 * playback events, no user configuration.
 *
 * ExoPlayer's playlist [androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration]
 * already buffers the *next* playlist item. This class covers what that API
 * does not:
 *
 *  - **Read-ahead** ([readAhead]) pulls a few ayahs beyond the next one on any
 *    network, so a skip/seek still lands on warm cache.
 *  - **Surah warming** ([warmSurah]) pulls the whole surah, but only on an
 *    unmetered network so it never eats a data plan.
 *
 * Both jobs write into the *same* listen [SimpleCache] the player reads from,
 * and skip URIs already fully present in listen or in the permanent keep tree.
 * Cache keys default to the URI string in both the player's [CacheDataSource]
 * and the one built here, so writes made here satisfy the player's reads.
 * Blocking [CacheWriter] work is cancelled on supersession and [release].
 */
class AudioPrefetcher(
    private val cache: Cache,
    upstreamFactory: DataSource.Factory,
    private val connectivityManager: ConnectivityManager,
    /** Permanent keep tree; fully kept ayahs must not be fetched into listen. */
    private val keep: Cache? = null,
) {
    /** Factory adds a cache-write sink by default, so CacheWriter fills it. */
    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // Bounded parallelism so prefetch can't saturate the network the player
    // itself is streaming over.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(2),
    )

    private var readAheadJob: Job? = null
    private var warmJob: Job? = null
    private var readAheadWriters = PrefetchWriterGate()
    private var warmWriters = PrefetchWriterGate()

    /** How many upcoming ayahs to keep warm during playback, *beyond* the
     * single next item ExoPlayer already preloads via PreloadConfiguration. */
    private val readAheadCount = 3

    /**
     * Warm [readAheadCount] ayahs after [currentIndex], skipping the immediate
     * next item (ExoPlayer's playlist preload already covers that). Cancels any
     * prior read-ahead so a skip/seek doesn't leave stale work running.
     */
    fun readAhead(uris: List<String>, currentIndex: Int) {
        val writers = supersedeReadAhead()
        // drop(currentIndex + 2): +1 is "next" (player preload), +2 starts our
        // deeper warm so we don't double-fetch the same URI.
        val next = uris.drop(currentIndex + 2).take(readAheadCount)
        if (next.isEmpty()) return
        readAheadJob = scope.launch { cacheAll(next, writers) }
    }

    /**
     * Warm the entire surah on an unmetered network. Cancels any prior warm so
     * moving to another surah/reciter supersedes rather than stacks work.
     */
    fun warmSurah(uris: List<String>) {
        val writers = supersedeWarm()
        if (uris.isEmpty() || connectivityManager.isActiveNetworkMetered) return
        warmJob = scope.launch { cacheAll(uris, writers) }
    }

    private suspend fun cacheAll(uris: List<String>, writers: PrefetchWriterGate) {
        for (uri in uris) {
            coroutineContext.ensureActive()
            if (isFullyCached(uri)) continue
            try {
                val dataSource = cacheDataSourceFactory.createDataSource()
                val writer = CacheWriter(
                    dataSource,
                    DataSpec.Builder().setUri(uri).build(),
                    null,
                    null,
                )
                if (!writers.run(writer::cancel) { writer.cache() }) return
            } catch (_: IOException) {
                // Network hiccup or eviction race — leave it for the player to
                // fetch on demand. Prefetch is best-effort, never fatal.
            } catch (_: InterruptedException) {
                return // Cancelled mid-write.
            }
        }
    }

    /** Cheap disk check to skip files we already hold in full, listen or keep. */
    private fun isFullyCached(uri: String): Boolean =
        isUriFullyCached(cache, uri) || (keep != null && isUriFullyCached(keep, uri))

    /**
     * Job.cancel first so the coroutine will not start another writer, then
     * cancel the blocking writers of that job, then open a fresh gate the new
     * job registers on. The old job holds the superseded gate, so it cannot
     * register on the replacement.
     */
    private fun supersedeReadAhead(): PrefetchWriterGate {
        readAheadJob?.cancel()
        readAheadWriters.cancel()
        return PrefetchWriterGate().also { readAheadWriters = it }
    }

    private fun supersedeWarm(): PrefetchWriterGate {
        warmJob?.cancel()
        warmWriters.cancel()
        return PrefetchWriterGate().also { warmWriters = it }
    }

    fun release() {
        readAheadJob?.cancel()
        warmJob?.cancel()
        readAheadWriters.cancel()
        warmWriters.cancel()
        scope.cancel()
    }
}
