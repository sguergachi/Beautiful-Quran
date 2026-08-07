package com.beautifulquran.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.beautifulquran.MainActivity
import com.beautifulquran.QuranApp
import com.beautifulquran.assistant.AssistantAction
import com.beautifulquran.assistant.AssistantIntents
import com.beautifulquran.data.model.Surah
import com.beautifulquran.domain.BASMALAH_PLAYLIST_AYAH
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

class PlaybackService : MediaLibraryService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaLibrarySession? = null
    private var prefetcher: AudioPrefetcher? = null
    private var assistantAudioResume: AssistantAudioResume? = null

    override fun onCreate() {
        super.onCreate()

        val cache = getCache(this)
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent("BeautifulQuran/1.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val prefetcher = AudioPrefetcher(cache, upstream, getSystemService()!!)
        this.prefetcher = prefetcher

        // Playlist preload covers the next ayah's join latency (Media3 1.10).
        // Surah-wide warming on unmetered networks stays in AudioPrefetcher —
        // PreloadConfiguration only looks ahead one item.
        @Suppress("UnstableApiUsage")
        val player = ExoPlayer.Builder(this, tarjiRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSource))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .experimentalSetDynamicSchedulingEnabled(true)
            .build()
            .also { exo ->
                exo.preloadConfiguration = ExoPlayer.PreloadConfiguration(
                    /* targetPreloadDurationUs = */ PLAYLIST_PRELOAD_US,
                )
            }

        player.addListener(PrefetchListener(player, prefetcher))
        player.addListener(BasmalahSkipListener(player))
        assistantAudioResume = AssistantAudioResume(
            player,
            getSystemService(AudioManager::class.java),
        )

        // Notification / lock-screen content tap opens the app. Without this,
        // Media3 still shows transport controls but the body tap is a no-op.
        val sessionActivity = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
    }

    /**
     * The player's renderers, with [VoiceTapAudioProcessor] tapped into the
     * audio sink so [VoiceEnergy] sees the PCM the listener hears — no mic or
     * Visualizer permission needed.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun tarjiRenderersFactory(context: Context): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(VoiceTapAudioProcessor()))
                .build()
        }

    /**
     * Turns playback events into cache-warming hints. Reads the player (main
     * thread) to collect ayah URIs, then hands them to [AudioPrefetcher], which
     * does the I/O off-thread.
     */
    private class PrefetchListener(
        private val player: Player,
        private val prefetcher: AudioPrefetcher,
    ) : Player.Listener {

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // A new playlist (new surah/reciter) — warm the whole thing.
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            val uris = playlistUris()
            prefetcher.warmSurah(uris)
            prefetcher.readAhead(uris, player.currentMediaItemIndex)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            prefetcher.readAhead(playlistUris(), player.currentMediaItemIndex)
        }

        private fun playlistUris(): List<String> {
            val timeline = player.currentTimeline
            val window = Timeline.Window()
            return (0 until timeline.windowCount).mapNotNull { i ->
                timeline.getWindow(i, window).mediaItem.localConfiguration?.uri?.toString()
            }
        }
    }

    /**
     * The basmalah lead-in streams Al-Fatihah 1:1 (`001001.mp3`), which is
     * inside the guaranteed ayah-per-file layout. If that item still fails,
     * skip into ayah 1 and keep reciting rather than killing chapter-start
     * playback. The item stays in the playlist (indices keep their lead-in
     * layout); errors on ayah items keep the player's normal error behavior.
     */
    private class BasmalahSkipListener(private val player: Player) : Player.Listener {

        override fun onPlayerError(error: PlaybackException) {
            val mediaId = player.currentMediaItem?.mediaId ?: return
            val ayah = PlayerController.parseMediaId(mediaId)?.ayah ?: return
            if (ayah != BASMALAH_PLAYLIST_AYAH || !player.hasNextMediaItem()) return
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /** Quran chapters exposed to Assistant, Android Auto, and other media browsers. */
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            if (parentId != LIBRARY_ROOT) return@future LibraryResult.ofError(
                LibraryResult.RESULT_ERROR_BAD_VALUE,
                params,
            )
            LibraryResult.ofItemList(chapters().paged(page, pageSize).map(::chapterItem), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
            val chapter = mediaId.chapterNumber()?.let { id -> chapters().firstOrNull { it.id == id } }
                ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            LibraryResult.ofItem(chapterItem(chapter), null)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = serviceScope.future {
            val count = searchChapters(query, chapters()).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            val items = searchChapters(query, chapters()).paged(page, pageSize).map(::chapterItem)
            LibraryResult.ofItemList(items, params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            if (mediaItems.all { it.localConfiguration?.uri != null }) {
                return Futures.immediateFuture(mediaItems)
            }
            return serviceScope.future { requestedQueue(mediaItems.firstOrNull()).items }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            val queue = requestedQueue(null)
            MediaSession.MediaItemsWithStartPosition(queue.items, queue.startIndex, 0L)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration?.uri != null }) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs),
                )
            }
            return serviceScope.future {
                val queue = requestedQueue(mediaItems.firstOrNull())
                MediaSession.MediaItemsWithStartPosition(queue.items, queue.startIndex, 0L)
            }
        }
    }

    private suspend fun chapters(): List<Surah> = (application as QuranApp).repository.surahs()

    private suspend fun requestedQueue(item: MediaItem?): RecitationQueue {
        val app = application as QuranApp
        val (surah, ayah) = requestedVerse(item, chapters())
        val reciters = app.repository.reciters()
        val reciter = reciters.firstOrNull { it.id == app.settings.settings.value.reciterId }
            ?: reciters.first()
        app.settings.updateListeningPosition(surah.id, ayah)
        return recitationQueue(surah, reciter, ayah, startWithBasmalah = ayah == 1)
    }

    private fun requestedVerse(item: MediaItem?, surahs: List<Surah>): Pair<Surah, Int> {
        val app = application as QuranApp
        val settings = app.settings.settings.value
        val mediaParts = item?.mediaId?.removePrefix(CHAPTER_PREFIX)?.split(':').orEmpty()
        val mediaSurah = mediaParts.getOrNull(0)?.toIntOrNull()
        val mediaAyah = mediaParts.getOrNull(1)?.toIntOrNull()
        val query = item?.requestMetadata?.searchQuery
        val spoken = query?.let(AssistantIntents::parseSpokenCommand) as? AssistantAction.OpenVerse
        val requestedSurah = mediaSurah ?: spoken?.surahId
        val surah = surahs.firstOrNull { it.id == requestedSurah }
            ?: query?.let { findNamedChapter(it, surahs) }
            ?: surahs.firstOrNull { it.id == settings.lastSurah }
            ?: surahs.first()
        val ayah = mediaAyah ?: spoken?.ayah ?: if (requestedSurah != null) 1 else settings.lastAyah
        return surah to ayah.coerceIn(1, surah.ayahCount)
    }

    private fun searchChapters(query: String, surahs: List<Surah>): List<Surah> {
        val spoken = AssistantIntents.parseSpokenCommand(query) as? AssistantAction.OpenVerse
        spoken?.let { action -> return surahs.filter { it.id == action.surahId } }
        val key = query.catalogKey()
        return surahs.filter { surah ->
            surah.id.toString() == key || listOf(
                surah.nameTransliteration,
                surah.nameTranslation,
                surah.nameArabic,
            ).any { it.catalogKey().contains(key) }
        }
    }

    private fun findNamedChapter(query: String, surahs: List<Surah>): Surah? =
        searchChapters(query, surahs).singleOrNull()

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(LIBRARY_ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Beautiful Quran")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .build(),
        )
        .build()

    private fun chapterItem(surah: Surah): MediaItem = MediaItem.Builder()
        .setMediaId("$CHAPTER_PREFIX${surah.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Chapter ${surah.id} • ${surah.nameTransliteration}")
                .setSubtitle(surah.nameTranslation)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .build(),
        )
        .build()

    private fun String.chapterNumber(): Int? =
        takeIf { it.startsWith(CHAPTER_PREFIX) }?.removePrefix(CHAPTER_PREFIX)?.substringBefore(':')?.toIntOrNull()

    private fun String.catalogKey(): String = lowercase()
        .replace(Regex("(?:play|recite|listen to|chapter|surah|sura|beautiful quran)"), " ")
        .filter(Char::isLetterOrDigit)

    private fun <T> List<T>.paged(page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return emptyList()
        return drop(page * pageSize).take(pageSize)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Dismissing the app from Recents ("force close") must stop the
        // recitation rather than leave it playing in the background. Stop the
        // player and tear the service (and its media notification) down;
        // onDestroy releases the player.
        mediaSession?.player?.run {
            stop()
            clearMediaItems()
        }
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        prefetcher?.release()
        prefetcher = null
        assistantAudioResume?.release()
        assistantAudioResume = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private const val LIBRARY_ROOT = "quran"
        private const val CHAPTER_PREFIX = "chapter:"
        /** Recently-heard surahs stay offline for a while: audio is small
         * (a few MB per surah), so a 1 GB LRU budget holds hundreds of them. */
        private const val CACHE_BYTES = 1024L * 1024 * 1024

        /** How much of the *next* playlist item ExoPlayer should buffer ahead
         * of the current ayah. Ayah MP3s are short; ~5 s covers a typical
         * join without racing the player's own buffer. */
        private const val PLAYLIST_PRELOAD_US = 5_000_000L

        private var cache: SimpleCache? = null

        @Synchronized
        private fun getCache(service: Context): SimpleCache =
            cache ?: SimpleCache(
                // filesDir, not cacheDir: cacheDir is evictable by the OS at
                // any moment, which would defeat offline playback. Here we own
                // eviction through the LRU budget below.
                File(service.filesDir, "audio"),
                LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                StandaloneDatabaseProvider(service),
            ).also { cache = it }
    }
}
