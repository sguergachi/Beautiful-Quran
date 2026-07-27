package com.beautifulquran.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.beautifulquran.data.BookmarkRepository
import com.beautifulquran.data.AnnotationRepository
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.data.SettingsRepository
import com.beautifulquran.data.TimingScheme
import com.beautifulquran.data.effectiveTimingScheme
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Segment
import com.beautifulquran.data.model.SubwordKeyframe
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.domain.BASMALAH_PLAYLIST_AYAH
import com.beautifulquran.domain.HighlightClock
import com.beautifulquran.domain.HighlightEngine
import com.beautifulquran.domain.OutputLatency
import com.beautifulquran.domain.SURAH_FATIHA
import com.beautifulquran.domain.surahOpensWithBasmalahPreface
import com.beautifulquran.playback.AudioOutputLatency
import com.beautifulquran.playback.NowPlaying
import com.beautifulquran.playback.PlayerController
import com.beautifulquran.playback.PlayerUiState
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
    val durationMs: Long,
    /** Actual timing source for this occurrence, not merely the selected lane:
     * inferred Tajwīd for a V1 fallback, acoustic [subwordKeyframes] for V2.
     * The two are never blended. */
    val timingScheme: TimingScheme = TimingScheme.V1,
    /** Voiced span of the word (segment end − start), without the karaoke
     * hold across the gap to the next word. V1 Tajwīd inference uses this
     * share; V2 follows its measured keyframes directly. */
    val spokenMs: Long = durationMs,
    /** Machine-generated acoustic reveal points; empty on the V1 lane. */
    val subwordKeyframes: List<SubwordKeyframe> = emptyList(),
    /**
     * Acoustic wasl budget into this word (ms), measured at build time.
     * Non-zero only on true V2 occurrences with a continuous nūn-rule join.
     */
    val waslFromPrevMs: Long = 0L,
    /** Acoustic wasl into the next occurrence (for outgoing bloom on this word). */
    val nextWaslFromPrevMs: Long = 0L,
    val isRepeat: Boolean = false,
    val highWater: Int = wordPosition,
    /** First word of the active repeat chain: while repeating, words
     * [repeatStart]..[wordPosition] all hold the orange fade until the chain
     * completes. Equals [wordPosition] when not repeating. */
    val repeatStart: Int = wordPosition,
    /** Matches this occurrence to its draw-phase acoustic clock anchor. */
    val acousticEpoch: Long = 0L,
    /**
     * Bumps on a genuine backward seek so the ink wash restarts even when the
     * same word stays Active (tap the current word to play it from the start).
     */
    val activation: Long = 0L,
)

/** Media-clock anchor extrapolated at draw time for one V2 occurrence. */
data class AcousticClockAnchor(
    val ayah: Int,
    val wordPosition: Int,
    val epoch: Long,
    val mediaPositionMs: Long,
    val realtimeNanos: Long,
    val playbackSpeed: Float,
    val startMs: Long,
    val holdEndMs: Long,
) {
    fun progressAt(frameNanos: Long): Float {
        val duration = holdEndMs - startMs
        if (duration <= 0L) return 1f
        val elapsedMs = ((frameNanos - realtimeNanos).coerceAtLeast(0L) / 1_000_000f)
        val positionMs = mediaPositionMs + elapsedMs * playbackSpeed
        return ((positionMs - startMs) / duration).coerceIn(0f, 1f)
    }
}

internal fun acousticProgressFrame(
    current: Float,
    activeEpoch: Long,
    anchor: AcousticClockAnchor?,
    frameNanos: Long,
): Float = when {
    anchor == null -> current
    anchor.epoch > activeEpoch -> 1f
    anchor.epoch < activeEpoch -> 0f
    else -> maxOf(current, anchor.progressAt(frameNanos))
}

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
     * Timing lane for the installed surah map. V1 and V2 are parallel DB forks
     * ([timings] / [timings_v2]); switching reloads this map in place so live
     * A/B comparison keeps playback position.
     */
    val timingScheme: TimingScheme = TimingScheme.V1,
    /** Ayahs in the loaded map that carry true acoustic V2 keyframes (not V1 fallback). */
    val v2AcousticAyahCount: Int = 0,
    /** Ayahs with any timing rows in the loaded map. */
    val timedAyahCount: Int = 0,
)

/** Off-screen chapter payload for continuous chapter advance. */
data class PreparedSurah(
    val content: SurahContent,
    val nextSurah: Surah?,
    val previousSurah: Surah?,
    val reciters: List<Reciter>,
    val reciter: Reciter,
    val timings: Map<Int, List<Segment>>,
    /** Timing lane used to materialize [timings]. */
    val timingScheme: TimingScheme = TimingScheme.V1,
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
)

internal data class PollingIdentity<K : Any>(
    val sampleKey: K,
    /** Cancels a sleeping paused poll so resume samples immediately. */
    val isPlaying: Boolean,
)

internal fun <K : Any> pollingIdentity(
    state: PlayerUiState,
    loadedSurahId: Int,
    key: (NowPlaying) -> K?,
): PollingIdentity<K>? = state.nowPlaying
    ?.takeIf { it.surahId == loadedSurahId }
    ?.let(key)
    ?.let { PollingIdentity(it, state.isPlaying) }

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
    private val _acousticWordClock = MutableStateFlow<AcousticClockAnchor?>(null)
    /** Sampled on the same listener-corrected clock as word membership. */
    val acousticWordClock: StateFlow<AcousticClockAnchor?> = _acousticWordClock
    private var acousticEpoch = 0L
    private var lastAcousticAyah = -1
    private var lastAcousticPosition = -1
    private var lastAcousticStartMs = -1L
    private var lastAcousticActivation = -1L
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
        scheme: TimingScheme,
    ): Map<Int, List<Segment>> {
        val loaded = repository.timings(reciterId, surahId, scheme)
        if (!surahOpensWithBasmalahPreface(surahId)) return loaded
        val basmalah = repository.timings(reciterId, SURAH_FATIHA, scheme)[1] ?: return loaded
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

    /** Bumps on genuine seeks so replaying the same Active word restarts ink. */
    private var inkActivation = 0L
    private var lastInkSampleKey: Any? = null
    private var lastInkClockMs = -1L
    /** Last applied lag/lead so a lab or route change can reset the clock. */
    private var lastOutputLatencyMs = -1L
    private var lastHighlightLeadMs = -1L
    /**
     * User seek target (ayah → ms) applied on the next poll once that ayah is
     * the media item — so ink jumps to the tapped word without waiting for
     * MediaController's position estimate to catch up.
     */
    private var forcedHighlight: Pair<Int, Long>? = null

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
     *
     * Forced word seeks stay on the media timeline so a tap lights the word
     * that was just sought.
     */
    private fun highlightPositionMs(ayah: Int, forcedMediaMs: Long?): Long {
        val latencyMs = outputLatencyMs()
        val leadMs = if (preparedTimings[ayah]?.hasAcousticKeyframes == true) {
            0L
        } else {
            InkEngine.highlightLeadMs.toLong().coerceAtLeast(0L)
        }
        if (latencyMs != lastOutputLatencyMs || leadMs != lastHighlightLeadMs) {
            lastOutputLatencyMs = latencyMs
            lastHighlightLeadMs = leadMs
            highlightClock.acceptNextSample()
        }
        if (forcedMediaMs != null) return forcedMediaMs
        val firstWordStartMs = preparedTimings[ayah]
            ?.segments
            ?.firstOrNull()
            ?.startMs
            ?: 0L
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
        val forced = forcedHighlight
        val forcedMs = if (forced != null && forced.first == np.ayah) {
            forcedHighlight = null
            forced.second
        } else {
            null
        }
        val rawMs = highlightPositionMs(np.ayah, forcedMs)
        val clockMs = highlightClock.sample(np, rawMs)
        if (lastInkSampleKey != np) {
            lastInkSampleKey = np
        } else if (
            lastInkClockMs >= 0L &&
            clockMs + HighlightClock.SEEK_THRESHOLD_MS < lastInkClockMs
        ) {
            // Large backward jump within the same media item (scrub / unnoted seek).
            inkActivation++
        }
        lastInkClockMs = clockMs
        val info = preparedTimings[np.ayah]?.activeInfo(clockMs)
        val acousticInfo = info?.takeIf { it.subwordKeyframes.isNotEmpty() }
        if (acousticInfo != null) {
            if (
                np.ayah != lastAcousticAyah ||
                acousticInfo.position != lastAcousticPosition ||
                acousticInfo.startMs != lastAcousticStartMs ||
                inkActivation != lastAcousticActivation
            ) {
                acousticEpoch++
            }
            lastAcousticAyah = np.ayah
            lastAcousticPosition = acousticInfo.position
            lastAcousticStartMs = acousticInfo.startMs
            lastAcousticActivation = inkActivation
        } else {
            lastAcousticAyah = -1
        }
        _acousticWordClock.value = acousticInfo
            ?.let {
                AcousticClockAnchor(
                    ayah = np.ayah,
                    wordPosition = it.position,
                    epoch = acousticEpoch,
                    mediaPositionMs = clockMs,
                    realtimeNanos = System.nanoTime(),
                    playbackSpeed = playerState.value
                        .takeIf { state -> state.isPlaying }
                        ?.speed ?: 0f,
                    startMs = it.startMs,
                    holdEndMs = it.holdEndMs,
                )
            }
        info?.let {
                ActiveWord(
                    ayah = np.ayah,
                    wordPosition = it.position,
                    // Karaoke hold lifetime — sweep finishes as the next word
                    // lights, not merely when this segment's endMs elapses.
                    durationMs = (it.holdEndMs - it.startMs).coerceAtLeast(0L),
                    timingScheme = if (it.subwordKeyframes.isEmpty()) {
                        TimingScheme.V1
                    } else {
                        TimingScheme.V2
                    },
                    spokenMs = (it.endMs - it.startMs)
                        .coerceIn(0L, (it.holdEndMs - it.startMs).coerceAtLeast(0L)),
                    subwordKeyframes = it.subwordKeyframes,
                    waslFromPrevMs = it.waslFromPrevMs,
                    nextWaslFromPrevMs = it.nextWaslFromPrevMs,
                    isRepeat = it.isRepeat,
                    highWater = it.highWater,
                    repeatStart = it.repeatStart,
                    acousticEpoch = if (it.subwordKeyframes.isEmpty()) 0L else acousticEpoch,
                    activation = inkActivation,
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
        // Reload when either the voice or timing lane changes. Switching only
        // the lane keeps playback in place; changing reciter continues in the
        // new voice as before.
        viewModelScope.launch {
            settings.settings
                .map { it.reciterId to it.effectiveTimingScheme }
                .distinctUntilChanged()
                .drop(1)
                .collect { onTimingSourceChanged() }
        }
        // Timings Lab corrections land immediately: whenever the override
        // store changes, re-pull this surah's fused timings so the highlight
        // follows the edit the moment the Lab sheet is lowered.
        viewModelScope.launch {
            repository.timingOverridesChanged?.drop(1)?.collect {
                if (settings.settings.value.effectiveTimingScheme == TimingScheme.V2) {
                    return@collect
                }
                val gen = sessions.generation
                val id = sessions.surahId.takeIf { it != 0 } ?: return@collect
                val reciter = _uiState.value.currentReciter ?: return@collect
                val refreshed = timingsWithBasmalahLeadIn(
                    reciter.id,
                    id,
                    TimingScheme.V1,
                )
                // Drop if navigation moved on while the DB re-read ran.
                if (
                    !sessions.isCurrent(gen, id) ||
                    settings.settings.value.effectiveTimingScheme != TimingScheme.V1 ||
                    settings.settings.value.reciterId != reciter.id
                ) return@collect
                installTimings(refreshed)
                _uiState.value = _uiState.value.copy(hasTimings = refreshed.isNotEmpty())
            }
        }
    }

    /**
     * Loads [surahId]. When [startPlaybackAtAyah] is set, starts recitation from
     * that ayah once content is ready (for example, "play chapter 2").
     */
    fun load(surahId: Int, startPlaybackAtAyah: Int? = null) {
        if (
            this.surahId == surahId &&
            (_uiState.value.content != null || _uiState.value.isLoading)
        ) {
            if (startPlaybackAtAyah != null) {
                if (_uiState.value.content != null) {
                    playFromAyah(startPlaybackAtAyah)
                } else {
                    // Same in-flight chapter: update autoplay without a new gen.
                    sessions.setPendingPlay(startPlaybackAtAyah)
                    focusedAyah = startPlaybackAtAyah.coerceAtLeast(1)
                }
            }
            return
        }
        val gen = sessions.begin(surahId, startPlaybackAtAyah)
        loadedSurah.value = surahId
        focusedAyah = startPlaybackAtAyah?.coerceAtLeast(1) ?: 1
        installTimings(emptyMap())
        _uiState.value = ReaderUiState(
            reciters = _uiState.value.reciters,
            currentReciter = _uiState.value.currentReciter,
            isLoading = true,
        )
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val prepared = materialize(surahId)?.withCurrentTimingSource() ?: return@launch
            if (!sessions.isCurrent(gen, surahId)) return@launch
            commitPrepared(prepared)
            val playAyah = sessions.takePendingPlay(gen)
            if (playAyah != null) {
                playFromAyah(playAyah)
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
        val scheme = settings.settings.value.effectiveTimingScheme
        val loadedTimings = timingsWithBasmalahLeadIn(reciter.id, surahId, scheme)
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
            timingScheme = scheme,
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
    suspend fun installPrepared(prepared: PreparedSurah) {
        if (!sessions.isCurrent(prepared.originGeneration)) return
        val current = prepared.withCurrentTimingSource()
        if (!sessions.isCurrent(prepared.originGeneration)) return
        loadJob?.cancel()
        loadJob = null
        sessions.begin(current.content.surah.id, pendingPlayAyah = null)
        commitPrepared(current)
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
            timingScheme = prepared.timingScheme,
            v2AcousticAyahCount = acousticV2AyahCount(prepared.timings),
            timedAyahCount = prepared.timings.size,
        )
    }

    private suspend fun onTimingSourceChanged() {
        val gen = sessions.generation
        val id = sessions.surahId
        if (id == 0) return
        val reciters = _uiState.value.reciters.ifEmpty { repository.reciters() }
        while (sessions.isCurrent(gen, id)) {
            val reciter = currentReciter(reciters)
            val scheme = settings.settings.value.effectiveTimingScheme
            val refreshed = timingsWithBasmalahLeadIn(reciter.id, id, scheme)
            if (
                currentReciter(reciters).id != reciter.id ||
                settings.settings.value.effectiveTimingScheme != scheme
            ) continue
            if (!sessions.isCurrent(gen, id)) return
            installTimings(refreshed)
            _uiState.value = _uiState.value.copy(
                currentReciter = reciter,
                hasTimings = timings.isNotEmpty(),
                timingScheme = scheme,
                v2AcousticAyahCount = acousticV2AyahCount(refreshed),
                timedAyahCount = refreshed.size,
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
            return
        }
    }

    private fun currentReciter(reciters: List<Reciter>): Reciter {
        val id = settings.settings.value.reciterId
        return reciters.firstOrNull { it.id == id } ?: reciters.first()
    }

    /**
     * Refreshes only the timing-bearing portion of an off-screen payload when
     * reciter or timing lane changed while it was suspended or animated.
     */
    private suspend fun PreparedSurah.withCurrentTimingSource(): PreparedSurah {
        var current = this
        while (true) {
            val reciter = currentReciter(reciters)
            val scheme = settings.settings.value.effectiveTimingScheme
            if (current.reciter.id == reciter.id && current.timingScheme == scheme) {
                return current
            }
            current = current.copy(
                reciter = reciter,
                timings = timingsWithBasmalahLeadIn(
                    reciter.id,
                    content.surah.id,
                    scheme,
                ),
                timingScheme = scheme,
            )
        }
    }

    fun segmentsFor(ayah: Int): List<Segment>? = timings[ayah]

    /** Marks or unmarks [ayah] in the loaded surah. Returns `true` when the
     * verse is now bookmarked, so the ribbon runs the unfurl animation only on
     * an add (never on a remove). */
    fun toggleBookmark(ayah: Int): Boolean {
        val surah = surahId.takeIf { it != 0 } ?: return false
        return bookmarks.toggle(surah, ayah)
    }

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
            noteInkRestart(1, seekMs = 0L)
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
                noteInkRestart(action.ayah, seekMs = action.positionMs)
                player.seekToWord(action.ayah, action.positionMs)
            }
            is FastForwardPolicy.Action.SeekToAyah -> {
                noteInkRestart(action.ayah, seekMs = 0L)
                player.seekToAyah(action.ayah)
            }
            FastForwardPolicy.Action.None -> Unit
        }
    }

    fun fastBackward() {
        val np = playerState.value.nowPlaying?.takeIf { it.surahId == surahId } ?: return
        if (np.ayah == BASMALAH_PLAYLIST_AYAH) {
            noteInkRestart(BASMALAH_PLAYLIST_AYAH, seekMs = 0L)
            player.seekToBasmalah()
            return
        }
        if (player.positionMs > START_SEEK_GRACE_MS) {
            // Restart this ayah: pin ink at 0 and arm the clock settle window
            // so post-seek position corrections cannot bounce word 2/3 and
            // re-run the (tajweed) wash mid-hold.
            noteInkRestart(np.ayah, seekMs = 0L)
            player.seekToAyah(np.ayah)
            return
        }

        if (np.ayah > 1) {
            noteInkRestart(np.ayah - 1, seekMs = 0L)
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
        )
        return true
    }

    fun playFromAyah(ayah: Int) {
        // Playing a specific ayah abandons any active repeat range.
        // Chapter openings (ayah 1) include the basmalah lead-in.
        noteInkRestart(ayah, seekMs = 0L)
        if (startSurah(ayah, preserveRepeatRange = false, startWithBasmalah = ayah == 1)) {
            rememberListened(ayah)
        }
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
        noteInkRestart(ayah, seekMs)
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
        noteInkRestart(ayah, seekMs = 0L)
        player.playLoadedFromAyah(ayah)
        rememberListened(ayah)
    }

    /**
     * User-initiated play/seek: bump ink activation, accept the next clock
     * sample, and pin highlight to [seekMs] on [ayah] so the wash restarts
     * on the word being played (not the pre-seek active word).
     */
    private fun noteInkRestart(ayah: Int, seekMs: Long) {
        inkActivation++
        highlightClock.acceptNextSample()
        forcedHighlight = ayah to seekMs
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

    /** Pauses a live reading session and returns enough state to restore it. */
    fun pauseForRootViewer(): ReaderPlaybackSnapshot? {
        val state = playerState.value
        if (!state.isPlaying) return null
        val nowPlaying = player.liveNowPlaying ?: state.nowPlaying ?: return null
        if (nowPlaying.surahId != surahId) return null
        val snapshot = ReaderPlaybackSnapshot(
            ayah = nowPlaying.ayah,
            positionMs = player.positionMs,
            repeatMode = state.repeatMode,
            repeatRange = state.repeatRange,
            speed = state.speed,
        )
        player.pause()
        return snapshot
    }

    /** Restores the chapter playlist displaced by the root viewer's audition. */
    fun resumeAfterRootViewer(snapshot: ReaderPlaybackSnapshot) {
        val playlistAyah = snapshot.ayah.coerceAtLeast(1)
        if (!startSurah(
                startAyah = playlistAyah,
                startPositionMs = snapshot.positionMs,
                preserveRepeatRange = false,
                startWithBasmalah = snapshot.ayah == BASMALAH_PLAYLIST_AYAH,
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

        /** True V2 rows carry measured keyframes; V1 fallback rows do not. */
        internal fun acousticV2AyahCount(timings: Map<Int, List<Segment>>): Int =
            timings.values.count { segs -> segs.any { it.subwordKeyframes.isNotEmpty() } }
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
