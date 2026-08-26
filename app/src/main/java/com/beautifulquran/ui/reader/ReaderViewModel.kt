package com.beautifulquran.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.beautifulquran.data.BookmarkRepository
import com.beautifulquran.data.AnnotationRepository
import com.beautifulquran.data.EducationMoment
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.data.SettingsRepository
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Segment
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.domain.BASMALAH_PLAYLIST_AYAH
import com.beautifulquran.domain.HighlightClock
import com.beautifulquran.domain.HighlightEngine
import com.beautifulquran.domain.MushafCatalog
import com.beautifulquran.domain.OutputLatency
import com.beautifulquran.domain.SURAH_FATIHA
import com.beautifulquran.domain.surahOpensWithBasmalahPreface
import com.beautifulquran.playback.AudioOutputLatency
import com.beautifulquran.playback.NowPlaying
import com.beautifulquran.playback.PlayerController
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.playback.TarjiBacklogAnchor
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The word currently being recited: ayah number + 1-based word position.
 * [durationMs] is how long the reciter dwells on it (at 1× speed) and paces
 * the letter-by-letter fade. [isRepeat] is true when the reciter is re-reciting
 * a word from an earlier pass (drives the orange fade); [highWater] is the
 * furthest word reached in the ayah, so words already recited hold their ink
 * instead of dimming when the recitation jumps backward for a repeat. */
data class ActiveWord(
    val ayah: Int,
    val wordPosition: Int,
    /** Start on the media-item timing clock, used for acoustic event ownership. */
    val startMs: Long = 0L,
    val durationMs: Long,
    /** Voiced span of the word (segment end − start), without the karaoke
     * hold across the gap to the next word. Tajweed pacing distributes the
     * letters over this share of [durationMs] and rests for the remainder. */
    val spokenMs: Long = durationMs,
    /** Next timing owner, including a repeat backtrack; null at ayah end. */
    val nextWordPosition: Int? = null,
    val isRepeat: Boolean = false,
    val highWater: Int = wordPosition,
    /** First word of the active repeat chain: while repeating, words
     * [repeatStart]..[wordPosition] all hold the orange fade until the chain
     * completes. Equals [wordPosition] when not repeating. */
    val repeatStart: Int = wordPosition,
    /**
     * Bumps on a genuine backward seek so the ink wash restarts even when the
     * same word stays Active (tap the current word to play it from the start).
     */
    val activation: Long = 0L,
)

data class MushafUi(
    val catalog: MushafCatalog,
    val surahsById: Map<Int, Surah>,
)

data class ReaderUiState(
    val content: SurahContent? = null,
    /** Next surah in order, or null on chapter 114 / while loading. */
    val nextSurah: Surah? = null,
    /** Previous surah in order, or null on chapter 1 / while loading. */
    val previousSurah: Surah? = null,
    val reciters: List<Reciter> = emptyList(),
    val currentReciter: Reciter? = null,
    val hasTimings: Boolean = false,
    val isLoading: Boolean = true,
    /**
     * True while a playback-driven chapter swap is in flight: the outgoing
     * content stays on the glass and the reader's entrance fade holds full,
     * so hitting play on another chapter's leaf swaps the ink without a
     * whole-screen flash.
     */
    val keepsContentThroughLoad: Boolean = false,
)

/** Off-screen chapter payload for continuous chapter advance. */
data class PreparedSurah(
    val content: SurahContent,
    val nextSurah: Surah?,
    val previousSurah: Surah?,
    val reciters: List<Reciter>,
    val reciter: Reciter,
    val timings: Map<Int, List<Segment>>,
    /**
     * [ReaderSessionGate.generation] when [materialize] started. [installPrepared]
     * no-ops if navigation has advanced since then, so a late continuous-scroll
     * install cannot cancel and override a newer [load].
     */
    val originGeneration: Long = 0L,
)

/** Reading-session state temporarily displaced by an in-page surface such as
 * the Root Word Viewer and its isolated word-audition playlist. */
data class ReaderPlaybackSnapshot(
    val ayah: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val repeatRange: IntRange?,
    val speed: Float,
    /** When false, restore the chapter queue without autoplay (paused open). */
    val wasPlaying: Boolean = true,
)

internal data class PollingIdentity<K : Any>(
    val sampleKey: K,
    /** Cancels a sleeping paused poll so resume samples immediately. */
    val isPlaying: Boolean,
    /** Restarts immediately when Media3 reports any authoritative clock jump. */
    val discontinuityId: Long,
)

/** Result of a ribbon tap; animation and contextual education have separate owners. */
data class BookmarkToggleResult(
    val bookmarked: Boolean,
    val showNoteTip: Boolean = false,
)

internal fun <K : Any> pollingIdentity(
    state: PlayerUiState,
    loadedSurahId: Int,
    key: (NowPlaying) -> K?,
): PollingIdentity<K>? = state.nowPlaying
    ?.takeIf { it.surahId == loadedSurahId }
    ?.let(key)
    ?.let { PollingIdentity(it, state.isPlaying, state.positionEvents.clockId) }

class ReaderViewModel(
    private val repository: QuranRepository,
    val settings: SettingsRepository,
    private val bookmarks: BookmarkRepository,
    val player: PlayerController,
    private val annotations: AnnotationRepository,
    private val outputLatency: AudioOutputLatency,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private val _mushaf = MutableStateFlow<MushafUi?>(null)
    val mushaf: StateFlow<MushafUi?> = _mushaf

    fun ensureMushaf() {
        if (_mushaf.value != null) return
        viewModelScope.launch {
            val catalog = repository.mushafCatalog()
            val surahs = repository.surahs().associateBy { it.id }
            _mushaf.value = MushafUi(catalog, surahs)
        }
    }

    /**
     * Verse under the reading line (scroll / rail / follow). Used for Assistant
     * "bookmark this" — not for Continue Listening (that only tracks audio).
     */
    private var focusedAyah: Int = 1

    val playerState: StateFlow<PlayerUiState> = player.state

    /**
     * Versioned navigation: every [load] / [installPrepared] bumps a generation
     * so a slower older materialize cannot install content, timings, or autoplay
     * after a newer intent. [surahId] is the chapter owned by the live generation.
     */
    private val sessions = ReaderSessionGate()
    private val surahId: Int get() = sessions.surahId

    /** Drives [bookmarkedAyahs]: the surah currently loaded into the reader, so
     * each verse ribbon only ever renders marks for the verses on screen. */
    private val loadedSurah = MutableStateFlow(0)

    /** The ayah numbers bookmarked *in the loaded surah*. Each verse ribbon
     * reads this; it recomposes only when a mark is added or removed. */
    val bookmarkedAyahs: StateFlow<Set<Int>> =
        combine(bookmarks.bookmarks, loadedSurah) { all, surah ->
            all.filter { it.surahId == surah }.map { it.ayah }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), emptySet())

    /** Annotation text keyed by ayah number for the surah on screen. */
    val annotationsForSurah: StateFlow<Map<Int, String>> =
        combine(annotations.annotations, loadedSurah) { all, surah ->
            all.filter { it.surahId == surah }.associate { it.ayah to it.text }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), emptyMap())
    /** Raw segments for seek / Timings Lab; prepared tables power the poll. */
    private var timings: Map<Int, List<Segment>> = emptyMap()
    /** Per-ayah repeat/high-water tables built once at load — hot-path lookups
     * allocate nothing (see [HighlightEngine.PreparedTimings]). */
    private var preparedTimings: Map<Int, HighlightEngine.PreparedTimings> = emptyMap()
    private var loadJob: Job? = null

    private fun installTimings(loaded: Map<Int, List<Segment>>) {
        timings = loaded
        preparedTimings = loaded.mapValues { (_, segs) ->
            HighlightEngine.PreparedTimings.prepare(segs)
        }
    }

    /**
     * Surah timings plus, for preface surahs, Al-Fatihah 1:1 segments under
     * [BASMALAH_PLAYLIST_AYAH] so the lead-in clip and calligraphy wash share
     * the same word clock.
     */
    private suspend fun timingsWithBasmalahLeadIn(
        reciterId: Int,
        surahId: Int,
    ): Map<Int, List<Segment>> {
        val loaded = repository.timings(reciterId, surahId)
        if (!surahOpensWithBasmalahPreface(surahId)) return loaded
        val basmalah = repository.timings(reciterId, SURAH_FATIHA)[1] ?: return loaded
        return loaded + (BASMALAH_PLAYLIST_AYAH to basmalah)
    }

    /**
     * The polling backbone of the sync engine: while this surah is the loaded
     * one, samples [sample] every [TICK_MS] (gently while paused, since the
     * position is frozen) and publishes only *changes*, so downstream
     * recomposition happens per word/ayah boundary — not per tick.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <K : Any, T> pollingWhileLoaded(
        key: (NowPlaying) -> K?,
        sample: (K) -> T?,
    ): StateFlow<T?> = player.state
        .map { pollingIdentity(it, surahId, key) }
        .distinctUntilChanged()
        .flatMapLatest { identity ->
            if (identity == null) {
                flowOf<T?>(null)
            } else {
                val k = identity.sampleKey
                flow<T?> {
                    while (true) {
                        // At an ayah handoff the controller's item (and its
                        // position, already near zero) advances a beat before
                        // [player.state] — and therefore this flow's key —
                        // catches up. Sampling the stale key against the new
                        // item's position bounces the value backward for one
                        // tick, which the renderer amplifies into the word
                        // flicker. Skip incoherent ticks; the key switches
                        // within milliseconds and samples fresh.
                        val live = player.liveNowPlaying
                        val coherent = live == null ||
                            live.takeIf { it.surahId == surahId }?.let(key) == k
                        if (coherent) emit(sample(k))
                        // Position is frozen while paused; poll gently.
                        delay(if (player.state.value.isPlaying) TICK_MS else PAUSED_TICK_MS)
                    }
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    /** Never lets sampling jitter bounce the highlight backward across a word
     * boundary — the source of the random full → faint → wash word flicker. */
    private val highlightClock = HighlightClock()

    private var lastClockEventId = player.state.value.positionEvents.clockId
    /** Last applied lag/lead so a lab or route change can reset the clock. */
    private var lastOutputLatencyMs = -1L
    private var lastHighlightLeadMs = -1L

    // Tarjīʿ backlog measurement: stable absolute sink baseline plus relative
    // tap/playback-head content clocks for the current sink session.
    private var latchedTapSessionStart = 0L
    private var tapBacklogAnchor: TarjiBacklogAnchor? = null
    private var smoothedBacklogContentMs = 0.0
    private var shimmerWasOn = false
    /** Ink Lab → Highlight can override route detection with an absolute lag. */
    private fun outputLatencyMs(): Long =
        InkEngine.outputLatencyOverrideMs?.toLong() ?: outputLatency.latencyMs.value

    /** Media position at the listener's ear, before any word-only ink lead. */
    private fun heardPositionMs(): Long =
        OutputLatency.heardMs(player.positionMs, outputLatencyMs())

    /**
     * The heard position plus the word-only Ink Lab lead. [firstWordStartMs]
     * keeps that lead from crossing encoded opening silence. A route or lab
     * change steps query time, so arm [HighlightClock] to take it rather than
     * hold it as jitter.
     */
    private fun highlightPositionMs(firstWordStartMs: Long): Long {
        val latencyMs = outputLatencyMs()
        // The tarjīʿ shimmer delays the tapped voice signal by the same
        // latency plus the sink buffer so it lands on the same clock the
        // highlight uses. Once that buffer has filled, exact tap and playback
        // content clocks track only queue growth/drain around the stable
        // baseline; a small EMA rejects position polling jitter.
        val voice = com.beautifulquran.playback.VoiceEnergy.active
        voice?.outputLatencyMs = latencyMs
        if (voice != null) {
            val speed = voice.playbackSpeed
            if (voice.sessionStartWall != latchedTapSessionStart) {
                latchedTapSessionStart = voice.sessionStartWall
                tapBacklogAnchor = null
                voice.measuredBacklogContentMs = -1.0
            }
            var anchor = tapBacklogAnchor
            if (
                TarjiBacklogAnchor.isReady(
                    tapContentMs = voice.sessionContentMs,
                    sinkLatencyMs = voice.sinkLatencyMs,
                    speed = speed,
                ) &&
                (anchor == null || abs(anchor.speed - speed) > 0.001f)
            ) {
                anchor = TarjiBacklogAnchor.capture(
                    tapContentMs = voice.sessionContentMs,
                    playbackContentMs = player.positionMs,
                    sinkLatencyMs = voice.sinkLatencyMs,
                    speed = speed,
                )
                tapBacklogAnchor = anchor
                smoothedBacklogContentMs = anchor.backlogContentMs
            }
            if (anchor != null) {
                val lagMs = anchor.estimate(voice.sessionContentMs, player.positionMs)
                smoothedBacklogContentMs +=
                    BACKLOG_EMA * (lagMs - smoothedBacklogContentMs)
                voice.measuredBacklogContentMs = smoothedBacklogContentMs
            }
            voice.updatePlaybackPosition(player.positionMs)
            // Live tarjīʿ effect log: the shimmer's on/off transitions in
            // media time (the same gain the renderer gates on), so the
            // effect's engagement is confirmable in logcat as it happens.
            val shimmerOn = voice.shimmerGain > 0.01f
            if (shimmerOn != shimmerWasOn) {
                shimmerWasOn = shimmerOn
                android.util.Log.i(
                    "TarjiEffect",
                    if (shimmerOn) {
                        "tarjīʿ shimmer ON at media ${player.positionMs} ms · " +
                            "${"%.1f".format(voice.rateHz)} Hz · hold ${"%.1f".format(voice.holdMs / 1000f)}s"
                    } else {
                        "tarjīʿ shimmer OFF at media ${player.positionMs} ms"
                    },
                )
            }
        }
        val leadMs = InkEngine.highlightLeadMs.toLong().coerceAtLeast(0L)
        if (latencyMs != lastOutputLatencyMs || leadMs != lastHighlightLeadMs) {
            lastOutputLatencyMs = latencyMs
            lastHighlightLeadMs = leadMs
            highlightClock.acceptNextSample()
        }
        return OutputLatency.highlightMs(
            mediaPositionMs = player.positionMs,
            latencyMs = latencyMs,
            leadMs = leadMs,
            leadNotBeforeMs = firstWordStartMs,
        )
    }

    /** Emits the active word ~30x/sec while this surah is playing, but only
     * publishes on change, so the UI recomposes once per word. The highlight
     * holds while paused (like a lyrics player); it only clears when this
     * surah stops being the loaded one. */
    val activeWord: StateFlow<ActiveWord?> = pollingWhileLoaded(key = { it }) { np ->
        val events = player.state.value.positionEvents
        if (events.clockId != lastClockEventId) {
            highlightClock.acceptNextSample()
            lastClockEventId = events.clockId
        }
        val firstWordStartMs = preparedTimings[np.ayah]
            ?.segments
            ?.firstOrNull()
            ?.startMs
            ?: 0L
        val rawMs = highlightPositionMs(firstWordStartMs)
        val clockMs = highlightClock.sample(np, rawMs)
        preparedTimings[np.ayah]
            ?.activeInfo(clockMs)
            ?.let {
                ActiveWord(
                    ayah = np.ayah,
                    wordPosition = it.position,
                    startMs = it.startMs,
                    // Karaoke hold lifetime — sweep finishes as the next word
                    // lights, not merely when this segment's endMs elapses.
                    durationMs = (it.holdEndMs - it.startMs).coerceAtLeast(0L),
                    spokenMs = (it.endMs - it.startMs)
                        .coerceIn(0L, (it.holdEndMs - it.startMs).coerceAtLeast(0L)),
                    nextWordPosition = it.nextPosition,
                    isRepeat = it.isRepeat,
                    highWater = it.highWater,
                    repeatStart = it.repeatStart,
                    activation = events.inkId,
                )
            }
    }

    /** The ayah focus/prepare target on the sheet. Normally the playing ayah,
     * but advances to the next ayah [InkEngine.fadeLeadMs] before the current
     * one's last word ends, so focus and recess-prepare lead the handoff.
     * Null while the chapter-opening basmalah lead-in is playing (no ayah yet). */
    val activeAyah: StateFlow<Int?> = pollingWhileLoaded(key = { it.ayah }) { ayah ->
        if (ayah == BASMALAH_PLAYLIST_AYAH) null else ayahWithFadeLead(ayah)
    }

    /**
     * True while the dedicated basmalah lead-in clip is the current media item
     * on a preface surah. Drives Active ink on the header calligraphy.
     */
    val activeBasmalah: StateFlow<Boolean?> = pollingWhileLoaded(key = { it }) { np ->
        np.ayah == BASMALAH_PLAYLIST_AYAH &&
            surahOpensWithBasmalahPreface(np.surahId)
    }

    /**
     * Calligraphy wash 0..1 while the basmalah lead-in plays: locked to the
     * clip's own word timings and paced by tajweed inside each word's band of
     * artwork, falling back to a plain clip ramp when those timings are missing
     * (see [InkEngine.prefaceWashProgress] /
     * [com.beautifulquran.domain.BasmalahWash]). Null when not on the lead-in.
     */
    val basmalahWashProgress: StateFlow<Float?> = pollingWhileLoaded(key = { it.ayah }) { ayah ->
        if (ayah != BASMALAH_PLAYLIST_AYAH) return@pollingWhileLoaded null
        val timed = timings[BASMALAH_PLAYLIST_AYAH]
        // The real media duration once known: it is both the fallback ramp's
        // span and the ceiling the paced wash is fitted inside (a source row
        // that overruns its own MP3 must still finish). Until then the timing
        // span, so the wash still advances on the first ticks.
        val duration = player.durationMs
        val endMs = when {
            duration > 0L -> duration
            timed != null -> timed.last().endMs
            else -> 0L
        }
        // The pure ear clock: this consumer must not arm the ink clock's
        // "accept next sample" latch on the ink poll's behalf.
        InkEngine.prefaceWashProgress(heardPositionMs(), endMs, timed)
    }

    /** Advances the lit ayah to the next one during the final
     * [InkEngine.fadeLeadMs] of the current ayah's *recitation*, so its fade-in
     * leads the last word (including a waqf hold) rather than trailing encoded
     * silence on the media file. Only while playing. */
    private fun ayahWithFadeLead(ayah: Int): Int {
        val ayahCount = _uiState.value.content?.surah?.ayahCount ?: return ayah
        // Prefer last word end so lead fires during the closing hold, not after
        // it in file-trailing silence (where activeWord is already null and the
        // Ink Lab slider appears to do nothing). Fall back to media duration
        // when timings are missing or still loading.
        val endMs = timings[ayah]?.lastOrNull()?.endMs?.takeIf { it > 0L }
            ?: player.durationMs
        return FadeLead.ayahWithFadeLead(
            ayah = ayah,
            isPlaying = player.state.value.isPlaying,
            // The ear clock, not the raw playhead: [endMs] is a segment time,
            // and the ink it is supposed to lead is latency-corrected. Reading
            // player.positionMs here made the effective lead fadeLeadMs + route
            // latency (680 ms instead of 500 ms on A2DP), shifting whenever the
            // listener changed audio output.
            positionMs = heardPositionMs(),
            endMs = endMs,
            leadMs = InkEngine.fadeLeadMs.toLong(),
            ayahCount = ayahCount,
            repeatRangeLast = player.state.value.repeatRange?.last,
        )
    }

    init {
        // React when the reciter is changed on the settings sheet: reload the
        // timing data and, if this surah is playing, continue with the new voice.
        viewModelScope.launch {
            settings.settings
                .map { it.reciterId }
                .distinctUntilChanged()
                .drop(1)
                .collect { onReciterChanged() }
        }
        // Timings Lab corrections land immediately: whenever the override
        // store changes, re-pull this surah's fused timings so the highlight
        // follows the edit the moment the Lab sheet is lowered.
        viewModelScope.launch {
            repository.timingOverridesChanged?.drop(1)?.collect {
                val gen = sessions.generation
                val id = sessions.surahId.takeIf { it != 0 } ?: return@collect
                val reciter = _uiState.value.currentReciter ?: return@collect
                val refreshed = timingsWithBasmalahLeadIn(reciter.id, id)
                // Drop if navigation moved on while the DB re-read ran.
                if (!sessions.isCurrent(gen, id)) return@collect
                installTimings(refreshed)
                _uiState.value = _uiState.value.copy(hasTimings = refreshed.isNotEmpty())
            }
        }
    }

    /**
     * Loads [surahId]. When [startPlaybackAtAyah] is set, starts recitation from
     * that ayah once content is ready (for example, "play chapter 2").
     */
    fun load(
        surahId: Int,
        startPlaybackAtAyah: Int? = null,
        startPlaybackAtWord: Int? = null,
        keepContent: Boolean = false,
    ) {
        if (
            this.surahId == surahId &&
            (_uiState.value.content != null || _uiState.value.isLoading)
        ) {
            if (startPlaybackAtAyah != null) {
                if (_uiState.value.content != null) {
                    playFromAyahWord(startPlaybackAtAyah, startPlaybackAtWord)
                } else {
                    // Same in-flight chapter: update autoplay without a new gen.
                    sessions.setPendingPlay(startPlaybackAtAyah, startPlaybackAtWord)
                    focusedAyah = startPlaybackAtAyah.coerceAtLeast(1)
                }
            }
            return
        }
        val gen = sessions.begin(surahId, startPlaybackAtAyah, startPlaybackAtWord)
        loadedSurah.value = surahId
        focusedAyah = startPlaybackAtAyah?.coerceAtLeast(1) ?: 1
        installTimings(emptyMap())
        // keepContent: a playback-driven swap keeps the outgoing chapter on
        // the glass until the new one commits — the leaf is already showing
        // its own glyphs, and blanking read as the whole screen flashing.
        val outgoing = _uiState.value.content
        _uiState.value = ReaderUiState(
            reciters = _uiState.value.reciters,
            currentReciter = _uiState.value.currentReciter,
            isLoading = true,
            content = if (keepContent) outgoing else null,
            keepsContentThroughLoad = keepContent && outgoing != null,
        )
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val prepared = materialize(surahId)
            if (prepared == null) {
                // The swap failed with nothing to commit: release the
                // entrance-fade hold or it would suppress the fade of the
                // next real navigation.
                if (sessions.isCurrent(gen, surahId)) {
                    _uiState.value = _uiState.value.copy(keepsContentThroughLoad = false)
                }
                return@launch
            }
            if (!sessions.isCurrent(gen, surahId)) return@launch
            commitPrepared(prepared)
            val playAyah = sessions.takePendingPlay(gen)
            if (playAyah != null) {
                playFromAyahWord(playAyah, sessions.takePendingPlayWord())
            }
        }
    }

    /**
     * Builds a chapter off-screen for continuous scroll advance — does not
     * touch [uiState], so the outgoing page stays put until [installPrepared]
     * commits at the mid-transition apex.
     */
    suspend fun materialize(surahId: Int): PreparedSurah? {
        // Snapshot before any suspend: a concurrent load()/install must make
        // this payload stale so installPrepared can reject it.
        val originGeneration = sessions.generation
        val reciters = repository.reciters()
        val reciter = currentReciter(reciters)
        val loadedTimings = timingsWithBasmalahLeadIn(reciter.id, surahId)
        val content = repository.surahContent(surahId)
        val surahs = repository.surahs()
        val nextSurah = surahs.firstOrNull { it.id == surahId + 1 }
        val previousSurah = surahs.firstOrNull { it.id == surahId - 1 }
        return PreparedSurah(
            content = content,
            nextSurah = nextSurah,
            previousSurah = previousSurah,
            reciters = reciters,
            reciter = reciter,
            timings = loadedTimings,
            originGeneration = originGeneration,
        )
    }

    /**
     * Publish a [materialize]d chapter as the live reader page.
     * No-ops when a newer [load]/installPrepared] began after this payload was
     * materialised — continuous advance must not override a fresher intent.
     * When still current, cancels any in-flight [load] for the same session
     * window and starts a new generation for the committed chapter.
     */
    fun installPrepared(prepared: PreparedSurah) {
        if (!sessions.isCurrent(prepared.originGeneration)) return
        loadJob?.cancel()
        loadJob = null
        sessions.begin(prepared.content.surah.id, pendingPlayAyah = null)
        commitPrepared(prepared)
    }

    /** Applies [prepared] to UI + timings. Caller must already own the live session. */
    private fun commitPrepared(prepared: PreparedSurah) {
        loadedSurah.value = prepared.content.surah.id
        focusedAyah = 1
        longAyahMidpointConsumed = 0
        installTimings(prepared.timings)
        _uiState.value = ReaderUiState(
            content = prepared.content,
            nextSurah = prepared.nextSurah,
            previousSurah = prepared.previousSurah,
            reciters = prepared.reciters,
            currentReciter = prepared.reciter,
            hasTimings = prepared.timings.isNotEmpty(),
            isLoading = false,
            // The swap happened under a composed leaf: hold the reader at
            // full ink through the content change (the screen's entrance
            // effect reads this and skips its fade for exactly one swap).
            keepsContentThroughLoad = _uiState.value.keepsContentThroughLoad,
        )
    }

    /** Consumes the one-shot entrance hold after the kept chapter is on glass. */
    fun onKeptContentCommitted() {
        if (_uiState.value.keepsContentThroughLoad) {
            _uiState.value = _uiState.value.copy(keepsContentThroughLoad = false)
        }
    }

    private suspend fun onReciterChanged() {
        val gen = sessions.generation
        val id = sessions.surahId
        if (id == 0) return
        val reciters = _uiState.value.reciters.ifEmpty { repository.reciters() }
        val reciter = currentReciter(reciters)
        val refreshed = timingsWithBasmalahLeadIn(reciter.id, id)
        if (!sessions.isCurrent(gen, id)) return
        installTimings(refreshed)
        _uiState.value = _uiState.value.copy(
            currentReciter = reciter,
            hasTimings = timings.isNotEmpty(),
        )
        val np = player.state.value.nowPlaying
        if (np != null && np.surahId == id && np.reciterId != reciter.id) {
            val preservedRange = player.state.value.repeatRange
            // Basmalah lead-in (ayah 0) restarts as a chapter opening.
            val resumeAyah = np.ayah.coerceAtLeast(1)
            playFromAyah(resumeAyah)
            if (preservedRange != null) {
                player.setRepeatRange(
                    preservedRange.first,
                    preservedRange.last,
                    repeatEndPositionFor(preservedRange.last),
                )
            }
        }
    }

    private fun currentReciter(reciters: List<Reciter>): Reciter {
        val id = settings.settings.value.reciterId
        return reciters.firstOrNull { it.id == id } ?: reciters.first()
    }

    fun segmentsFor(ayah: Int): List<Segment>? = timings[ayah]

    /** Marks or unmarks [ayah], and identifies a mark that should teach notes. */
    fun toggleBookmark(ayah: Int): BookmarkToggleResult {
        val surah = surahId.takeIf { it != 0 } ?: return BookmarkToggleResult(false)
        val nowBookmarked = bookmarks.toggle(surah, ayah)
        val currentSettings = settings.settings.value
        return BookmarkToggleResult(
            bookmarked = nowBookmarked,
            showNoteTip = nowBookmarked &&
                currentSettings.developerModeEnabled &&
                currentSettings.educationGuidesEnabled &&
                currentSettings.annotationsEnabled &&
                !settings.isEducationDismissed(EducationMoment.BOOKMARK_NOTE),
        )
    }

    fun dismissBookmarkNoteTip() = settings.dismissEducation(EducationMoment.BOOKMARK_NOTE)

    /** Whether a settled chapter opening should teach its live ayah rail. */
    fun shouldShowAyahRailTip(): Boolean {
        val current = settings.settings.value
        return current.developerModeEnabled &&
            current.educationGuidesEnabled &&
            !settings.isEducationDismissed(EducationMoment.AYAH_RAIL)
    }

    fun dismissAyahRailTip() = settings.dismissEducation(EducationMoment.AYAH_RAIL)

    /** Writes (or, on blank [text], clears) the reader's note on a verse. The
     * surah is passed in rather than read from the loaded chapter so a draft
     * committed during a chapter advance still lands on the verse it was
     * written for. */
    fun writeAnnotation(surahId: Int, ayah: Int, text: String) {
        annotations.write(surahId, ayah, text)
    }

    /**
     * Long-ayah midpoint skip already issued for this ayah (0 = none).
     * Decided by intent, not [PlayerController.positionMs], because seeks are
     * async — a second FF before position catches up must not re-seek midpoint.
     */
    private var longAyahMidpointConsumed: Int = 0

    fun fastForward() {
        val content = _uiState.value.content ?: return
        val np = playerState.value.nowPlaying?.takeIf { it.surahId == surahId } ?: return
        // During the basmalah lead-in, skip ahead into ayah 1.
        if (np.ayah == BASMALAH_PLAYLIST_AYAH) {
            longAyahMidpointConsumed = 0
            player.seekToAyah(1)
            return
        }
        val action = FastForwardPolicy.action(
            ayah = np.ayah,
            positionMs = player.positionMs,
            ayahCount = content.surah.ayahCount,
            midpointMs = midpointForLongAyah(np.ayah),
            midpointConsumedForAyah = longAyahMidpointConsumed,
        )
        longAyahMidpointConsumed = FastForwardPolicy.nextConsumedAyah(action)
        when (action) {
            is FastForwardPolicy.Action.SeekToMidpoint -> {
                player.seekToWord(action.ayah, action.positionMs)
            }
            is FastForwardPolicy.Action.SeekToAyah -> {
                player.seekToAyah(action.ayah)
            }
            FastForwardPolicy.Action.None -> Unit
        }
    }

    fun fastBackward() {
        val np = playerState.value.nowPlaying?.takeIf { it.surahId == surahId } ?: return
        if (np.ayah == BASMALAH_PLAYLIST_AYAH) {
            player.seekToBasmalah()
            return
        }
        if (player.positionMs > START_SEEK_GRACE_MS) {
            player.seekToAyah(np.ayah)
            return
        }

        if (np.ayah > 1) {
            player.seekToAyah(np.ayah - 1)
        } else if (np.ayah == 1) {
            // Restart from the basmalah lead-in when present.
            player.playLoadedFromAyah(1)
        }
    }

    private fun midpointForLongAyah(ayah: Int): Long? =
        FastForwardPolicy.midpointMs(timings[ayah].orEmpty())

    /** Loads this surah as the playlist from [startAyah]; no-op until content
     * and reciter are ready. Returns false when not started.
     * [startWithBasmalah] prepends and begins on the everyayah basmalah clip
     * when opening a chapter from ayah 1 (not for mid-ayah word seeks). */
    private fun startSurah(
        startAyah: Int,
        startPositionMs: Long = 0L,
        preserveRepeatRange: Boolean = true,
        startWithBasmalah: Boolean = false,
        autoplay: Boolean = true,
    ): Boolean {
        val content = _uiState.value.content ?: return false
        val reciter = _uiState.value.currentReciter ?: return false
        player.playSurah(
            surahId = content.surah.id,
            ayahCount = content.surah.ayahCount,
            startAyah = startAyah,
            reciter = reciter,
            surahName = content.surah.nameTransliteration,
            startPositionMs = startPositionMs,
            preserveRepeatRange = preserveRepeatRange,
            startWithBasmalah = startWithBasmalah,
            autoplay = autoplay,
        )
        return true
    }

    fun playFromAyah(ayah: Int) {
        // Playing a specific ayah abandons any active repeat range.
        // Chapter openings (ayah 1) include the basmalah lead-in.
        if (startSurah(ayah, preserveRepeatRange = false, startWithBasmalah = ayah == 1)) {
            rememberListened(ayah)
        }
    }

    /**
     * Starts [ayah] as close to [word] as the reciter's timings allow: the
     * word's own segment when it has one, otherwise the last segment that
     * begins before it (a word inside an unsplit span starts where that span
     * starts, not back at the top of the verse). Falls back to the verse when
     * the chapter has no timings at all.
     */
    fun playFromAyahWord(ayah: Int, word: Int?) {
        // A chapter's opening — ayah 1 at its first word — starts with the
        // basmalah lead-in, exactly as play-from-ayah-1 does. The mushaf's
        // play target is a word, and the word path used to seek straight
        // into ayah 1 and skip the bismillah.
        if (ayah == 1 && (word == null || word <= 1) && surahOpensWithBasmalahPreface(surahId)) {
            playFromAyah(ayah)
            return
        }
        val start = word?.let { startMsForWord(ayah, it) }
        if (start != null) playFromWord(ayah, start) else playFromAyah(ayah)
    }

    /** Timing start of [word] in [ayah], or of the nearest segment before it. */
    fun startMsForWord(ayah: Int, word: Int): Long? {
        val segments = timings[ayah]?.takeIf { it.isNotEmpty() } ?: return null
        val exact = segments.firstOrNull { it.position == word }
        if (exact != null) return exact.startMs
        return segments.filter { it.position <= word }.maxByOrNull { it.position }?.startMs
    }

    fun playFromWord(ayah: Int, positionMs: Long) {
        val reciter = _uiState.value.currentReciter ?: return
        val np = playerState.value.nowPlaying
        // Keep the loop when the tapped verse is inside the active range;
        // only abandon it when the user jumps outside.
        val keepRepeat = playerState.value.repeatRange?.let { ayah in it } == true
        // Always restart ink: tap-to-play must re-run the wash even when the
        // same word stays Active or the seek is shorter than the jitter hold.
        val seekMs = positionMs.coerceAtLeast(0L)
        if (np != null && np.surahId == surahId && np.reciterId == reciter.id) {
            if (!keepRepeat) player.clearRepeatRange()
            player.seekToWordAndPlay(ayah, seekMs)
            rememberListened(ayah)
        } else if (startSurah(ayah, startPositionMs = seekMs, preserveRepeatRange = keepRepeat)) {
            rememberListened(ayah)
        }
    }

    /** Resume a loaded playlist from [ayah] and mark it as listened. */
    fun playLoadedFromAyah(ayah: Int) {
        player.playLoadedFromAyah(ayah)
        rememberListened(ayah)
    }

    /**
     * Focus / scroll / rail target changed.
     * Bookmark "this verse" uses the focused ayah; does not touch Continue
     * Listening (that only tracks verses actually recited).
     */
    fun onAyahBecameActive(ayah: Int) {
        if (ayah < 1) return
        focusedAyah = ayah
    }

    /**
     * The verse currently being recited advanced (or play started on it).
     * Updates Continue Listening — never call this for bare scroll/jump.
     */
    fun onListenedAyah(ayah: Int) {
        rememberListened(ayah)
    }

    /** Persist Continue Listening — only for verses the user actually heard. */
    private fun rememberListened(ayah: Int) {
        if (surahId in 1..114 && ayah >= 1) {
            focusedAyah = ayah
            settings.updateListeningPosition(surahId, ayah)
        }
    }

    /**
     * Best-effort verse for Assistant "bookmark this": the loaded chapter and
     * the verse under the reading line (scroll / jump / playback focus).
     */
    fun currentVerseForBookmark(): Pair<Int, Int>? {
        val surah = surahId.takeIf { it in 1..114 } ?: return null
        return surah to focusedAyah.coerceAtLeast(1)
    }

    /**
     * Captures this chapter's playhead (playing or paused) so the root viewer
     * can restore a full surah queue after word audition. Pauses only when
     * audio is live. Null when the player is not on this chapter.
     */
    fun pauseForRootViewer(): ReaderPlaybackSnapshot? {
        val state = playerState.value
        val nowPlaying = player.liveNowPlaying ?: state.nowPlaying ?: return null
        if (nowPlaying.surahId != surahId) return null
        val snapshot = ReaderPlaybackSnapshot(
            ayah = nowPlaying.ayah,
            positionMs = player.positionMs,
            repeatMode = state.repeatMode,
            repeatRange = state.repeatRange,
            speed = state.speed,
            wasPlaying = state.isPlaying,
        )
        if (state.isPlaying) player.pause()
        return snapshot
    }

    /**
     * Restores the full chapter playlist displaced by the root viewer's
     * audition. Auto-plays only when [ReaderPlaybackSnapshot.wasPlaying].
     */
    fun resumeAfterRootViewer(snapshot: ReaderPlaybackSnapshot) {
        val playlistAyah = snapshot.ayah.coerceAtLeast(1)
        if (!startSurah(
                startAyah = playlistAyah,
                startPositionMs = snapshot.positionMs,
                preserveRepeatRange = false,
                startWithBasmalah = snapshot.ayah == BASMALAH_PLAYLIST_AYAH,
                autoplay = snapshot.wasPlaying,
            )
        ) return
        player.setSpeed(snapshot.speed)
        val range = snapshot.repeatRange
        if (range != null) {
            player.setRepeatRange(range.first, range.last, repeatEndPositionFor(range.last))
        } else {
            player.setRepeatMode(snapshot.repeatMode)
        }
    }

    /** One of Player.REPEAT_MODE_*; always leaves range-repeat behind. */
    fun setRepeatMode(mode: Int) {
        if (mode == Player.REPEAT_MODE_ONE) {
            val ayah = playerState.value.nowPlaying
                ?.takeIf { it.surahId == surahId }
                ?.ayah
                ?.coerceAtLeast(1)
                ?: settings.settings.value.lastAyah
            setRepeatRange(ayah, ayah)
            return
        }
        player.clearRepeatRange()
        player.setRepeatMode(mode)
    }

    /**
     * Loops ayahs [from]..[to]. If already playing inside that range, keep the
     * current position (do not seek back to the first ayah). Otherwise start
     * the surah from [from].
     */
    fun setRepeatRange(from: Int, to: Int) {
        val content = _uiState.value.content ?: return
        val start = from.coerceIn(1, content.surah.ayahCount)
        val end = to.coerceIn(start, content.surah.ayahCount)
        val reciter = _uiState.value.currentReciter
        val np = playerState.value.nowPlaying
        val playingInRange = playerState.value.isPlaying &&
            reciter != null &&
            np != null &&
            np.surahId == surahId &&
            np.reciterId == reciter.id &&
            np.ayah in start..end
        if (!playingInRange) {
            if (!startSurah(start, preserveRepeatRange = false)) return
            rememberListened(start)
        }
        player.setRepeatRange(start, end, repeatEndPositionFor(end))
    }

    private fun repeatEndPositionFor(ayah: Int): Long? =
        timings[ayah]?.lastOrNull()?.endMs

    fun cycleSpeed() {
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f)
        val current = playerState.value.speed
        val idx = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        player.setSpeed(speeds[(idx + 1).mod(speeds.size)])
    }

    companion object {
        private const val TICK_MS = 33L
        private const val PAUSED_TICK_MS = 250L
        private const val START_SEEK_GRACE_MS = 1_500L
        /** Suppress 33 ms player-position jitter without a seconds-long drift. */
        private const val BACKLOG_EMA = 0.2
    }
}

/**
 * Pure ayah-handoff lead: when playback is in the final [leadMs] of an ayah's
 * recitation span, report the *next* ayah so focus and ink-prepare can start
 * early. Session-tunable via [InkEngine.fadeLeadMs] (Ink Lab → Highlight).
 *
 * [endMs] should be the last word's end (not media-file duration) so trailing
 * encoded silence does not hide the lead entirely.
 */
internal object FadeLead {
    fun ayahWithFadeLead(
        ayah: Int,
        isPlaying: Boolean,
        positionMs: Long,
        endMs: Long,
        leadMs: Long,
        ayahCount: Int,
        repeatRangeLast: Int?,
    ): Int {
        if (!isPlaying) return ayah
        if (repeatRangeLast != null && ayah >= repeatRangeLast) return ayah
        if (ayah >= ayahCount) return ayah
        if (endMs <= 0L) return ayah
        val lead = leadMs.coerceAtLeast(0L)
        // Once inside the lead window, stay advanced through trailing silence
        // (position past endMs) until the media item itself advances.
        val remaining = endMs - positionMs
        return if (remaining <= lead) ayah + 1 else ayah
    }
}
