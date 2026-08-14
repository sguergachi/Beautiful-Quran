package com.beautifulquran

import android.app.Application
import com.beautifulquran.assistant.AssistantAction
import com.beautifulquran.assistant.VoiceShortcuts
import com.beautifulquran.data.BookmarkRepository
import com.beautifulquran.data.AnnotationRepository
import com.beautifulquran.data.DictionaryDatabase
import com.beautifulquran.data.DictionaryRepository
import com.beautifulquran.data.LexiconDatabase
import com.beautifulquran.data.LexiconRepository
import com.beautifulquran.data.QuranDatabase
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.data.QfContentCacheDatabase
import com.beautifulquran.data.RuntimeTimingCache
import com.beautifulquran.data.SettingsRepository
import com.beautifulquran.data.TimingContentSyncApi
import com.beautifulquran.ornamentslab.OrnamentSeedStore
import com.beautifulquran.playback.AudioOutputLatency
import com.beautifulquran.playback.PlayerController
import com.beautifulquran.timingslab.TimingOverrides
import com.beautifulquran.tarjilab.ReciterTarjiProfiles
import com.beautifulquran.ui.reader.InkEngine
import com.beautifulquran.ui.reader.InkLabStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

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
        private set
    lateinit var bookmarks: BookmarkRepository
        private set
    lateinit var annotations: AnnotationRepository
        private set
    lateinit var player: PlayerController
        private set
    /** Route-based output delay for the karaoke clock (BT A2DP / LE presets). */
    lateinit var outputLatency: AudioOutputLatency
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

    override fun onCreate() {
        super.onCreate()
        DevProfiling.install(this)
        val overrides = TimingOverrides(this)
        settings = SettingsRepository(this)
        val runtimeTimings = BuildConfig.TIMING_CONTENT_BASE_URL
            .takeIf { it.isNotBlank() }
            ?.let { baseUrl ->
                runCatching {
                    RuntimeTimingCache(
                        api = TimingContentSyncApi(baseUrl),
                        store = QfContentCacheDatabase(this),
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    )
                }.getOrNull()
            }
        repository = QuranRepository(QuranDatabase(this), overrides, runtimeTimings)
        runtimeTimings?.refresh(settings.settings.value.reciterId)
        lexicon = LexiconRepository(LexiconDatabase(this))
        dictionary = DictionaryRepository(DictionaryDatabase(this))
        bookmarks = BookmarkRepository(this)
        annotations = AnnotationRepository(this)
        player = PlayerController(this)
        outputLatency = AudioOutputLatency(this)
        timingOverrides = overrides
        ornamentSeeds = OrnamentSeedStore(this)
        inkLab = InkLabStore(this)
        tarjiProfiles = ReciterTarjiProfiles(this)
        // Restore last lab audition before any reader opens.
        InkEngine.attachLabStore(inkLab)
        tarjiProfiles.applyToEngine(settings.settings.value.reciterId)
        // Long-press app icon → Continue / Bookmarks (works without App Actions review).
        VoiceShortcuts.publishDynamic(this)
    }
}
