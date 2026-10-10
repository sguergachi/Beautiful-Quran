package com.beautifulquran

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.beautifulquran.assistant.AssistantAction
import com.beautifulquran.assistant.VoiceShortcuts
import com.beautifulquran.data.BookmarkRepository
import com.beautifulquran.data.AnnotationRepository
import com.beautifulquran.data.DictionaryDatabase
import com.beautifulquran.data.DictionaryRepository
import com.beautifulquran.data.EnglishBookCache
import com.beautifulquran.data.LexiconDatabase
import com.beautifulquran.data.LexiconRepository
import com.beautifulquran.data.QfContentCacheDatabase
import com.beautifulquran.data.QfContentSyncHttpApi
import com.beautifulquran.data.QuranDatabase
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.data.RuntimeMushafCache
import com.beautifulquran.data.RuntimeTimingCache
import com.beautifulquran.data.RuntimeTimingDatabase
import com.beautifulquran.data.RuntimeTimingHttpApi
import com.beautifulquran.data.readCanonicalTimingCounts
import com.beautifulquran.data.readCanonicalWords
import com.beautifulquran.data.SearchConceptRepository
import com.beautifulquran.data.SettingsRepository
import com.beautifulquran.ornamentslab.OrnamentSeedStore
import com.beautifulquran.playback.AudioOutputRoutes
import com.beautifulquran.playback.PlayerController
import com.beautifulquran.playback.RecitationCache
import com.beautifulquran.timingslab.TimingOverrides
import com.beautifulquran.tarjilab.ReciterTarjiProfiles
import com.beautifulquran.ui.reader.InkEngine
import com.beautifulquran.ui.reader.InkLabStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class QuranApp : Application() {

    /** One-shot OS actions that should also move an already-open reader. */
    val assistantActions = MutableSharedFlow<AssistantAction>(extraBufferCapacity = 1)

    lateinit var repository: QuranRepository
        private set
    /** Lane's Lexicon — opened lazily, on the first root the reader unfolds. */
    lateinit var lexicon: LexiconRepository
        private set
    /** English Wiktionary Arabic — lazy, keyed by the open word's QAC lemma. */
    lateinit var dictionary: DictionaryRepository
        private set
    lateinit var settings: SettingsRepository

    /** The English book's leaves, kept on disk so they are measured once. */
    lateinit var englishBookCache: EnglishBookCache
        private set
    lateinit var bookmarks: BookmarkRepository
        private set
    lateinit var annotations: AnnotationRepository
        private set
    lateinit var player: PlayerController
        private set
    /** Route-based output delay for the karaoke clock (BT A2DP / LE presets). */
    lateinit var outputRoutes: AudioOutputRoutes
        private set
    lateinit var timingOverrides: TimingOverrides
        private set
    lateinit var ornamentSeeds: OrnamentSeedStore
        private set
    /** Developer Ink Lab numbers — attached to [InkEngine] on start. */
    lateinit var inkLab: InkLabStore
        private set
    /** Per-reciter tarjīʿ detector knobs — applied after the Ink Lab snapshot. */
    lateinit var tarjiProfiles: ReciterTarjiProfiles
        private set
    /** Authenticated QF word/QCF fields live here, never in the bundled database. */
    var runtimeMushaf: RuntimeMushafCache? = null
        private set
    /** Reviewed QF repeat timing is maintained separately from the packaged content. */
    lateinit var runtimeTimings: RuntimeTimingCache
        private set

    override fun onCreate() {
        super.onCreate()
        DevProfiling.install(this)
        val overrides = TimingOverrides(this)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val database = QuranDatabase(this)
        val store = QfContentCacheDatabase(this)
        runtimeTimings = RuntimeTimingCache(
            RuntimeTimingHttpApi(BuildConfig.QF_CONTENT_BASE_URL),
            RuntimeTimingDatabase(this),
            appScope,
            wordCounts = { readCanonicalTimingCounts(database) },
            onAccessRevoked = { runtimeMushaf?.clearRevokedContent() },
        )
        runtimeMushaf = RuntimeMushafCache(
            QfContentSyncHttpApi(BuildConfig.QF_CONTENT_BASE_URL, cacheDir),
            store,
            appScope,
            canonicalWords = { readCanonicalWords(database) },
            onAccessRevoked = { runtimeTimings.clearRevokedContent() },
        )
        repository = QuranRepository(
            database,
            overrides,
            SearchConceptRepository(this),
            runtimeMushaf,
            runtimeTimings,
        )
        appScope.launch {
            runtimeMushaf?.refreshIfNeeded()
            runtimeTimings.refreshIfNeeded()
        }
        if (runtimeMushaf != null) {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        appScope.launch {
                            runtimeMushaf?.refreshIfNeeded()
                            runtimeTimings.refreshIfNeeded()
                        }
                    }
                },
            )
        }
        lexicon = LexiconRepository(LexiconDatabase(this))
        dictionary = DictionaryRepository(DictionaryDatabase(this))
        settings = DevProfiling.trace("settingsInit") { SettingsRepository(this) }
        englishBookCache = EnglishBookCache(this)
        bookmarks = DevProfiling.trace("bookmarksInit") { BookmarkRepository(this) }
        annotations = AnnotationRepository(this)
        player = PlayerController(this)
        outputRoutes = AudioOutputRoutes()
        timingOverrides = overrides
        ornamentSeeds = OrnamentSeedStore(this)
        inkLab = InkLabStore(this)
        tarjiProfiles = ReciterTarjiProfiles(this)
        // Restore last lab audition before any reader opens.
        InkEngine.attachLabStore(inkLab)
        tarjiProfiles.applyToEngine(settings.settings.value.reciterId)
        // Long-press app icon → Continue / Bookmarks (works without App Actions review).
        VoiceShortcuts.publishDynamic(this)
        RecitationCache.prepare(this)
    }
}
