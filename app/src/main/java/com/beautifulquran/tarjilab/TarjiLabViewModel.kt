package com.beautifulquran.tarjilab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.data.SettingsRepository
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Segment
import com.beautifulquran.playback.PlayerController
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.playback.TarjiLabCapture
import com.beautifulquran.playback.TarjiLabTrim
import com.beautifulquran.playback.VoiceEnergy
import com.beautifulquran.playback.mapTapContentToMediaMs
import com.beautifulquran.playback.sonicContentLatencyMs
import com.beautifulquran.ui.reader.InkEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Capture one word and tune the live detector against it. The loop plays
 * the selected window. Knob edits re-run the
 * pure [com.beautifulquran.playback.Tarji] detector over the same PCM.
 */
class TarjiLabViewModel(
    private val repository: QuranRepository,
    private val settingsRepo: SettingsRepository,
    private val player: PlayerController,
    private val profiles: ReciterTarjiProfiles? = null,
) : ViewModel() {

    data class TarjiLabUiState(
        val isLoading: Boolean = true,
        val surahId: Int = 0,
        val surahName: String = "",
        val ayah: Int = 1,
        val ayahCount: Int = 0,
        val reciter: Reciter? = null,
        val wordPosition: Int = 0,
        val wordArabic: String = "",
        val wordTranslation: String = "",
        val wordCount: Int = 0,
        /** The word's spoken span on the media clock (capture target). */
        val wordStartMs: Long = 0L,
        val wordEndMs: Long = 0L,
        val capture: TarjiLabCapture? = null,
        val firstHopMediaMs: Double = 0.0,
        val trace: TarjiLabTrace? = null,
        val knobs: TarjiLabKnobs = TarjiLabKnobs(),
        val reference: TarjiLabReference? = null,
        val showingReference: Boolean = false,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        /** Loop selection; the legacy exchange format also carries old labels. */
        val expectation: TarjiLabExpectation = TarjiLabExpectation(),
        val tool: TarjiLabTool = TarjiLabTool.LISTEN,
        val previewSpeed: TarjiPreviewSpeed = TarjiPreviewSpeed.FULL,
        val previewScope: TarjiPreviewScope = TarjiPreviewScope.WORD,
        val view: TarjiViewWindow = TarjiViewWindow.fit(0f),
        val sampleNotes: String = "",
        /** True while a hold handle is being dragged — the only time we print the range. */
        val holdEditing: Boolean = false,
        val capturing: Boolean = false,
        /** Capture progress from the requested word span, 0..1. */
        val captureProgress: Float = 0f,
        val analyzing: Boolean = false,
        val matchingPulse: Boolean = false,
        val captureError: String? = null,
        val previewPlaying: Boolean = false,
        val previewDurationMs: Float = 0f,
        /** Loop position at the last pause/seek, in content milliseconds. */
        val previewPositionMs: Float = 0f,
        /** When a sample was imported, its reciter name (the local reciter
         * may differ from the sample's). */
        val sampleReciterId: Int? = null,
        val sampleReciterName: String? = null,
        val note: String? = null,
    ) {
        val displayTrace: TarjiLabTrace? get() = if (showingReference) reference?.trace else trace
        val displayKnobs: TarjiLabKnobs get() = if (showingReference) reference?.knobs ?: knobs else knobs

        /** Freeze this capture and displayed tuning before opening the system save picker. */
        fun sampleForExport(): TarjiLabSample? {
            val audio = capture ?: return null
            val id = sampleReciterId ?: reciter?.id ?: return null
            val name = sampleReciterName ?: reciter?.name ?: return null
            return TarjiLabCodec.buildSample(
                capture = audio,
                firstHopMediaMs = firstHopMediaMs,
                label = TarjiLabCodec.label(name, surahId, ayah, wordPosition),
                reciterId = id, reciterName = name,
                surahId = surahId, ayah = ayah, wordPosition = wordPosition,
                wordArabic = wordArabic, knobs = displayKnobs,
                expectation = expectation, notes = sampleNotes,
            )
        }
    }

    private val knobHistory = TarjiKnobHistory()
    private val _ui = MutableStateFlow(TarjiLabUiState())
    val ui: StateFlow<TarjiLabUiState> = _ui.asStateFlow()

    private var pendingExport: TarjiLabSample? = null
    private var loadJob: Job? = null
    private var captureJob: Job? = null
    private var analyzeJob: Job? = null
    private var matchJob: Job? = null
    private var importJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var previewRateHz = 0
    private var scrubActive = false
    private var holdEditActive = false
    private var ayahSegments: List<Segment> = emptyList()
    private var previewClock: TarjiPreviewClock? = null
    private var captureProbe: VoiceEnergy? = null
    private var originalSpeed: Float? = null
    private var originalRepeatMode: Int? = null

    // ── Target ─────────────────────────────────────────────────────────────

    fun initFromLastOpened() {
        val s = settingsRepo.settings.value
        changeTarget(
            s.lastSurah.takeIf { it in 1..114 } ?: 1,
            s.lastAyah.coerceAtLeast(1),
            null,
        )
    }

    /** [focusWordPosition] is the word long-pressed in the reader. */
    fun changeTarget(surahId: Int, ayah: Int, focusWordPosition: Int? = null) {
        player.setSkipSilenceGaps(false)
        stopPreview()
        if (matchesLab(player.state.value) && player.state.value.isPlaying) {
            player.pause()
        }
        load(surahId, ayah, focusWordPosition)
    }

    fun nextWord() = stepWord(+1)

    fun prevWord() = stepWord(-1)

    private fun stepWord(delta: Int) {
        val st = _ui.value
        if (st.wordCount <= 0) return
        val next = (st.wordPosition - 1 + delta).mod(st.wordCount) + 1
        load(st.surahId, st.ayah, next)
    }

    private fun load(surahId: Int, ayah: Int, focusWordPosition: Int?) {
        importJob?.cancel()
        cancelPulseMatch()
        loadJob?.cancel()
        analyzeJob?.cancel()
        abortCapture()
        stopPreview()
        knobHistory.clear()
        val keepTool = _ui.value.tool
        _ui.value = TarjiLabUiState(
            isLoading = true,
            surahId = surahId,
            ayah = ayah,
            knobs = knobsForReciter(settingsRepo.settings.value.reciterId),
            tool = keepTool,
        )
        loadJob = viewModelScope.launch {
            try {
                val reciters = repository.reciters()
                val reciter = reciters.firstOrNull { it.id == settingsRepo.settings.value.reciterId }
                    ?: reciters.first()
                applyReciterProfile(reciter.id)
                val content = repository.surahContent(surahId)
                if (surahId != _ui.value.surahId || ayah != _ui.value.ayah) return@launch
                val ayahRow = content.ayahs[(ayah - 1).coerceIn(0, content.ayahs.lastIndex)]
                val words = ayahRow.words
                ayahSegments = repository.timings(reciter.id, surahId)[ayahRow.number].orEmpty()
                val position = if (focusWordPosition in 1..words.size) {
                    focusWordPosition!!
                } else {
                    // No held word (Settings entry): the verse closer — the
                    // canonical tarjīʿ spot.
                    words.size
                }
                val span = TarjiLabTrim.wordSpanMs(ayahSegments, position, 0L, 0L)
                val word = words[position - 1]
                _ui.value = TarjiLabUiState(
                    isLoading = false,
                    surahId = surahId,
                    surahName = content.surah.nameTransliteration,
                    ayah = ayahRow.number,
                    ayahCount = content.surah.ayahCount,
                    reciter = reciter,
                    wordPosition = position,
                    wordArabic = word.arabic,
                    wordTranslation = word.translation,
                    wordCount = words.size,
                    wordStartMs = span?.first ?: 0L,
                    wordEndMs = span?.last ?: 0L,
                    knobs = knobsForReciter(reciter.id),
                    tool = _ui.value.tool,
                )
                captureWord()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(isLoading = false, captureError = "Could not load this word: ${error.message}")
            }
        }
    }

    private fun matchesLab(ps: PlayerUiState): Boolean {
        val np = ps.nowPlaying ?: return false
        val st = _ui.value
        return np.surahId == st.surahId && np.ayah == st.ayah && np.reciterId == st.reciter?.id
    }

    // ── Capture ────────────────────────────────────────────────────────────

    /** Automatically play the target span muted while the tap records it. */
    private fun captureWord() {
        cancelPulseMatch()
        val st = _ui.value
        if (st.isLoading || st.reciter == null || st.wordPosition == 0) return
        if (st.capturing) return
        stopPreview()
        val span = TarjiLabTrim.wordSpanMs(
            ayahSegments,
            st.wordPosition,
            CAPTURE_LEAD_MS,
            CAPTURE_TAIL_MS,
        )
        if (span == null) {
            _ui.value = st.copy(captureError = "This word has no timing marks to capture against.")
            return
        }
        // The word's tail must not outrun the ayah's audio: a verse closer
        // (or any word near the end) would otherwise never reach the span's
        // end and the capture would wait out its deadline. The duration is
        // only known once the media item loads, so the clamp happens live in
        // the polling loop below.
        originalSpeed = originalSpeed ?: player.state.value.speed
        originalRepeatMode = originalRepeatMode ?: player.state.value.repeatMode
        startLabPlayback()
        player.pause()
        player.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF)
        player.setVolume(0f)
        player.setSpeed(1f)
        _ui.value = st.copy(capturing = true, captureProgress = 0f, captureError = null, note = null)
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS + (span.last - span.first)
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            // A cold Settings entry may not have created the PCM tap yet.
            while (VoiceEnergy.active == null || !matchesLab(player.state.value)) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    finishCapture("Could not prepare audio for capture.")
                    return@launch
                }
                delay(POLL_MS)
            }
            val ve = VoiceEnergy.active ?: return@launch
            captureProbe = ve
            ve.armCapture()
            player.seekToWordAndPlay(st.ayah, span.first.coerceAtLeast(0L))
            var seekLanded = false
            while (true) {
                val active = ve.captureActive
                val durationMs = player.durationMs.takeIf { it > 0L }
                val spanEnd = if (durationMs != null) {
                    minOf(span.last, durationMs - END_GUARD_MS)
                } else {
                    span.last
                }
                val positionMs = player.positionMs
                val nowPlaying = player.liveNowPlaying
                if (!seekLanded) {
                    seekLanded = nowPlaying?.surahId == st.surahId &&
                        nowPlaying.ayah == st.ayah &&
                        nowPlaying.reciterId == st.reciter.id &&
                        captureSeekHasLanded(positionMs, span.first.coerceAtLeast(0L))
                    if (!seekLanded) {
                        if (SystemClock.elapsedRealtime() > deadline) {
                            finishCapture("Could not reach the word for muted capture.")
                            return@launch
                        }
                        delay(POLL_MS)
                        continue
                    }
                }
                val progress = ((positionMs - span.first).toFloat() /
                    (spanEnd - span.first).coerceAtLeast(1L))
                    .coerceIn(0f, 1f)
                _ui.value = _ui.value.copy(captureProgress = progress)
                if (nowPlaying?.surahId != st.surahId || nowPlaying.ayah != st.ayah ||
                    nowPlaying.reciterId != st.reciter.id || VoiceEnergy.active !== ve) {
                    finishCapture("Playback changed during capture. Retry this word.")
                    return@launch
                }
                val done = !active || positionMs >= spanEnd
                if (done) {
                    finishCapture(if (active) null else "Audio stopped before the word's end.")
                    return@launch
                }
                if (SystemClock.elapsedRealtime() > deadline) {
                    finishCapture("Capture timed out.")
                    return@launch
                }
                delay(POLL_MS)
            }
        }
    }

    /** Retry is shown only after an automatic capture fails. */
    fun retryCapture() {
        val st = _ui.value
        if (st.capturing) return
        if (st.wordPosition == 0) load(st.surahId, st.ayah, null) else captureWord()
    }

    /** Stop every capture side effect, including a queued or failed player. */
    private fun abortCapture() {
        captureJob?.cancel()
        captureJob = null
        captureProbe?.disarmCapture()
        captureProbe = null
        restorePlayer()
        _ui.value = _ui.value.copy(capturing = false)
    }

    private fun restorePlayer() {
        player.pause()
        player.setVolume(1f)
        originalSpeed?.let(player::setSpeed)
        originalSpeed = null
        originalRepeatMode?.let(player::setRepeatMode)
        originalRepeatMode = null
    }

    private fun finishCapture(error: String?) {
        captureJob?.cancel()
        captureJob = null
        val ve = captureProbe
        captureProbe = null
        val st = _ui.value
        val capture = ve?.disarmCapture()
        restorePlayer()
        if (error != null || capture == null) {
            _ui.value = st.copy(
                capturing = false,
                captureProgress = 0f,
                captureError = error ?: "No audio was captured — playback did not run.",
            )
            return
        }
        val speed = 1f // Muted capture always runs at unity speed.
        val backlog = ve.measuredBacklogContentMs.takeIf { it >= 0.0 }
            ?: (ve.sinkLatencyMs * speed + sonicContentLatencyMs(speed)).toDouble()
        val firstHopMediaMs = mapTapContentToMediaMs(
            playbackPositionMs = player.positionMs,
            tapContentMs = ve.sessionContentMs,
            eventStartContentMs = capture.hopContentMs[0].toDouble(),
            backlogContentMs = backlog,
        ).toDouble()
        val span = TarjiLabTrim.wordSpanMs(
            ayahSegments,
            st.wordPosition,
            CAPTURE_LEAD_MS,
            CAPTURE_TAIL_MS,
        )
        val range = span?.let { TarjiLabTrim.hopRangeInSpan(capture, firstHopMediaMs, it) }
            ?: (0 until capture.hopCount)
        if (range.isEmpty()) {
            _ui.value = st.copy(capturing = false, captureError = "Captured audio missed the word. Retry.")
            return
        }
        val trimmed = capture.slice(range)
        val captureMs = trimmed.hopCount * trimmed.hopContentDurationMs()
        _ui.value = st.copy(
            capturing = false,
            captureProgress = 1f,
            capture = trimmed,
            trace = null,
            reference = null,
            showingReference = false,
            firstHopMediaMs = capture.hopMediaMs(range.first, firstHopMediaMs),
            expectation = TarjiLabExpectation().withWindow(
                TarjiHoldWindow(0f, captureMs),
                captureMs,
            ),
            sampleNotes = "",
            captureError = null,
            previewDurationMs = captureMs,
            previewPositionMs = 0f,
            view = TarjiViewWindow.fit(captureMs),
        )
        reanalyze()
    }

    /** Loads this ayah as a single-item playlist through the shared player. */
    private fun startLabPlayback() {
        val st = _ui.value
        val reciter = st.reciter ?: return
        player.playSurah(
            surahId = st.surahId,
            ayahCount = st.ayah,
            startAyah = st.ayah,
            reciter = reciter,
            surahName = st.surahName,
            preserveRepeatRange = false,
            includeBasmalahLeadIn = false,
            autoplay = false,
        )
    }

    // ── Offline analysis ───────────────────────────────────────────────────

    /** Re-run the detector over the captured stream with the current knobs.
     * Pure DSP on a background thread; the preview keeps playing under it. */
    private fun reanalyze() {
        if (_ui.value.capture == null) return
        analyzeJob?.cancel()
        _ui.value = _ui.value.copy(analyzing = true)
        analyzeJob = viewModelScope.launch {
            while (true) {
                val st = _ui.value
                val capture = st.capture ?: return@launch
                val trace = withContext(Dispatchers.Default) {
                    analyzeTarjiCapture(capture, st.knobs)
                }
                val live = _ui.value
                if (live.capture !== capture) return@launch
                // Brightness is render-only; every detector edit still needs a replay.
                val pending = live.knobs.copy(glintBrightness = st.knobs.glintBrightness) != st.knobs
                _ui.value = live.copy(
                    trace = trace,
                    reference = live.reference ?: TarjiLabReference(st.knobs, trace),
                    analyzing = pending,
                )
                if (!pending) return@launch
                // Edits coalesce into the next replay rather than canceling
                // every frame or waiting for the finger to stop moving.
            }
        }
    }

    /** Suggest a pulse band from the loop (or whole word), preserving playback and other knobs. */
    fun matchPulse() {
        val st = _ui.value
        val capture = st.capture ?: return
        if (st.matchingPulse || st.showingReference || st.holdEditing || st.capturing) return
        val scope = if (st.tool == TarjiLabTool.HOLD) TarjiPreviewScope.HOLD else TarjiPreviewScope.WORD
        val window = previewLoopWindow(scope, st.expectation.window, capture.totalContentMs)
        _ui.value = st.copy(matchingPulse = true, note = null)
        matchJob = viewModelScope.launch {
            val match = withContext(Dispatchers.Default) { matchTarjiPulse(capture, window, st.knobs) }
            matchJob = null
            _ui.value = _ui.value.copy(matchingPulse = false)
            if (_ui.value.capture !== capture || _ui.value.knobs != st.knobs) return@launch
            if (match == null) {
                _ui.value = _ui.value.copy(note = "No clear pulse · select a longer, steady section")
                return@launch
            }
            finishKnobEdit()
            updateKnobs { it.copy(minTremoloHz = match.minHz, maxTremoloHz = match.maxHz) }
            finishKnobEdit()
            _ui.value = _ui.value.copy(note = "Matched %.1f Hz · fine-tune Pulse speed".format(match.rateHz))
        }
    }

    private fun cancelPulseMatch() {
        matchJob?.cancel()
        matchJob = null
        if (_ui.value.matchingPulse) _ui.value = _ui.value.copy(matchingPulse = false)
    }

    /** One slider drag is one undo step; playback continues while analysis catches up. */
    fun updateKnobs(transform: (TarjiLabKnobs) -> TarjiLabKnobs) {
        val next = transform(_ui.value.knobs)
        if (next == _ui.value.knobs) return
        knobHistory.record(_ui.value.knobs)
        applyKnobs(next)
    }

    private fun applyKnobs(knobs: TarjiLabKnobs) {
        val detectorChanged = knobs.copy(glintBrightness = _ui.value.knobs.glintBrightness) != _ui.value.knobs
        cancelPulseMatch()
        persistKnobs(knobs)
        _ui.value = _ui.value.copy(knobs = knobs, showingReference = false, note = null,
            canUndo = knobHistory.canUndo, canRedo = knobHistory.canRedo,
            analyzing = _ui.value.analyzing || (detectorChanged && _ui.value.capture != null))
        if (detectorChanged && analyzeJob?.isActive != true) reanalyze()
    }

    fun finishKnobEdit() {
        knobHistory.finish(_ui.value.knobs)
        _ui.value = _ui.value.copy(canUndo = knobHistory.canUndo, canRedo = knobHistory.canRedo)
    }

    fun undoKnobs() { knobHistory.undo(_ui.value.knobs)?.let(::applyKnobs) }
    fun redoKnobs() { knobHistory.redo(_ui.value.knobs)?.let(::applyKnobs) }

    /** Compare graph and glow at the same audio position without changing the profile. */
    fun toggleReference() {
        cancelPulseMatch()
        finishKnobEdit()
        if (_ui.value.reference != null) {
            _ui.value = _ui.value.copy(showingReference = !_ui.value.showingReference, note = null)
        }
    }

    fun setReference() {
        finishKnobEdit()
        val st = _ui.value
        if (st.analyzing || st.trace == null) return
        _ui.value = st.copy(reference = TarjiLabReference(st.knobs, st.trace), showingReference = false,
            note = "Reference saved · tap Compare")
    }

    /** Restore only this reciter's defaults; the reset itself is undoable. */
    fun resetKnobs() {
        finishKnobEdit()
        updateKnobs { TarjiLabKnobs.fromTuning(InkEngine.Tuning()) }
        finishKnobEdit()
    }

    /** Choose the playback range and its editing tool together, preserving play/pause. */
    fun setTool(tool: TarjiLabTool) {
        cancelPulseMatch()
        val st = _ui.value
        val scope = if (tool == TarjiLabTool.HOLD) TarjiPreviewScope.HOLD else TarjiPreviewScope.WORD
        val switchPlayback = st.previewPlaying && st.previewScope != scope
        val position = if (switchPlayback) previewPlayheadMs() else st.previewPositionMs
        scrubActive = false
        holdEditActive = false
        _ui.value = st.copy(tool = tool, previewScope = scope, holdEditing = false)
        if (switchPlayback) startPreviewAt(position, scope)
    }

    fun setPreviewSpeed(speed: TarjiPreviewSpeed) {
        val st = _ui.value
        if (st.previewSpeed == speed) return
        _ui.value = st.copy(previewSpeed = speed)
        if (!applyPreviewSpeed()) {
            _ui.value = _ui.value.copy(previewSpeed = st.previewSpeed, note = "This speed is unavailable on this device.")
        }
    }

    fun zoomViewAt(focusMs: Float, scale: Float) {
        val st = _ui.value
        val capture = st.capture ?: return
        val captureMs = capture.hopCount * capture.hopContentDurationMs()
        _ui.value = st.copy(view = zoomView(st.view, captureMs, focusMs, scale))
    }

    fun panViewBy(deltaMs: Float) {
        val st = _ui.value
        val capture = st.capture ?: return
        val captureMs = capture.hopCount * capture.hopContentDurationMs()
        _ui.value = st.copy(view = panView(st.view, captureMs, deltaMs))
    }

    fun fitView() {
        val st = _ui.value
        val capture = st.capture ?: return
        _ui.value = st.copy(
            view = TarjiViewWindow.fit(capture.hopCount * capture.hopContentDurationMs()),
        )
    }

    /** Pause so a hold drag cannot fight the hardware loop. */
    fun beginHoldEdit() {
        cancelPulseMatch()
        if (_ui.value.previewPlaying) pausePreview()
        holdEditActive = true
        _ui.value = _ui.value.copy(holdEditing = true)
    }

    /**
     * Move the hold. Does not touch [AudioTrack] — mid-drag
     * [android.media.AudioTrack.setLoopPoints] fails silently and leaves
     * Play looping the *previous* range. Play rebuilds the loop.
     */
    fun setHoldWindow(window: TarjiHoldWindow, playheadMs: Float? = null) {
        cancelPulseMatch()
        val st = _ui.value
        val capture = st.capture ?: return
        val captureMs = capture.hopCount * capture.hopContentDurationMs()
        val next = st.expectation.withWindow(window, captureMs)
        val hold = next.window ?: return
        _ui.value = st.copy(
            expectation = next,
            previewPositionMs = (playheadMs ?: playheadAfterHoldEdit(hold))
                .coerceIn(hold.startMs, (hold.endMs - 1f).coerceAtLeast(hold.startMs)),
            note = null,
        )
    }

    fun endHoldEdit() {
        holdEditActive = false
        _ui.value = _ui.value.copy(holdEditing = false)
    }

    fun updateSampleNotes(notes: String) {
        _ui.value = _ui.value.copy(sampleNotes = notes.take(MAX_NOTES_LENGTH))
    }

    private fun knobsForReciter(reciterId: Int): TarjiLabKnobs =
        profiles?.knobsFor(reciterId) ?: TarjiLabKnobs.fromTuning(InkEngine.tuning)

    private fun applyReciterProfile(reciterId: Int) {
        profiles?.applyToEngine(reciterId)
    }

    private fun persistKnobs(knobs: TarjiLabKnobs) {
        val reciterId = activeReciterId()
        if (reciterId == settingsRepo.settings.value.reciterId) {
            InkEngine.tuning = TarjiLabKnobs.applyToTuning(knobs, InkEngine.tuning)
        }
        profiles?.save(reciterId, knobs)
    }

    private fun activeReciterId(): Int =
        _ui.value.sampleReciterId ?: _ui.value.reciter?.id ?: settingsRepo.settings.value.reciterId

    // ── Loop preview ───────────────────────────────────────────────────────

    /** Toggle the hold loop without destroying its current position. */
    fun togglePreview() = togglePreview(TarjiPreviewScope.HOLD)

    /** Toggle a loop of the whole captured word. */
    fun toggleWordPreview() = togglePreview(TarjiPreviewScope.WORD)

    private fun togglePreview(scope: TarjiPreviewScope) {
        val st = _ui.value
        if (st.previewPlaying && st.previewScope == scope) {
            pausePreview()
            return
        }
        val captureMs = st.capture?.let { it.hopCount * it.hopContentDurationMs() } ?: 0f
        val window = previewLoopWindow(scope, st.expectation.window, captureMs)
        startPreviewAt(playheadForPlay(st.previewPositionMs, window), scope)
    }

    /** Play the selected hold window on a seamless hardware loop. */
    fun startPreview() {
        startPreviewAt(currentHold()?.startMs ?: 0f, TarjiPreviewScope.HOLD)
    }

    private fun startPreviewAt(startMs: Float, scope: TarjiPreviewScope) {
        stopPreview()
        val st = _ui.value
        val capture = st.capture ?: run {
            _ui.value = st.copy(note = "Capture a word first.")
            return
        }
        val rate = com.beautifulquran.tarjilab.TarjiLabCodec.playbackSampleRate(capture)
        val bytes = toPcm16(capture)
        if (bytes.size > MAX_STATIC_BYTES) {
            _ui.value = st.copy(
                note = "Capture is ${bytes.size / 1024} KB — too long for a static loop.",
            )
            return
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes.size)
                .build()
        }.getOrNull()
        if (track == null) {
            _ui.value = st.copy(note = "Could not build the preview track.")
            return
        }
        // In static mode the track starts as STATE_NO_STATIC_DATA and only
        // becomes STATE_INITIALIZED once the buffer lands — so write first,
        // then judge both the write and the resulting state.
        if (track.write(bytes, 0, bytes.size) != bytes.size ||
            track.state != AudioTrack.STATE_INITIALIZED
        ) {
            track.release()
            _ui.value = st.copy(note = "Could not load the captured audio.")
            return
        }
        val durationMs = capture.hopCount * capture.hopContentDurationMs()
        val loop = loopRange(
            previewLoopWindow(scope, st.expectation.window, durationMs),
            capture,
            durationMs,
        )
        val positionMs = startMs.coerceIn(loop.startMs, (loop.endMs - 1f).coerceAtLeast(loop.startMs))
        val frame = previewFrame(capture, positionMs).coerceIn(loop.startFrame, loop.endFrame - 1)
        if (track.setPlaybackHeadPosition(frame) != AudioTrack.SUCCESS) {
            track.release()
            _ui.value = st.copy(note = "Could not seek the preview to the chosen sample.")
            return
        }
        if (!loopInfinitely(track, loop.startFrame, loop.endFrame)) {
            track.release()
            _ui.value = st.copy(note = "Could not arm the loop on this device.")
            return
        }
        previewRateHz = rate
        audioTrack = track
        previewClock = TarjiPreviewClock(frame, track.playbackHeadPosition, loop.startFrame, loop.endFrame)
        if (!applyPreviewSpeed() || runCatching { track.play() }.isFailure) {
            stopPreview()
            _ui.value = _ui.value.copy(note = "Could not start preview at this speed.")
            return
        }
        _ui.value = st.copy(
            previewPlaying = true,
            previewScope = scope,
            previewDurationMs = durationMs,
            previewPositionMs = positionMs,
        )
    }

    /** Static looping has used this three-argument API since Android API 3. */
    private fun loopInfinitely(track: AudioTrack, startFrame: Int, endFrame: Int): Boolean =
        runCatching { track.setLoopPoints(startFrame, endFrame, -1) == AudioTrack.SUCCESS }
            .getOrDefault(false)

    private data class LoopRange(
        val startMs: Float,
        val endMs: Float,
        val startFrame: Int,
        val endFrame: Int,
    )

    private fun currentHold(): TarjiHoldWindow? = _ui.value.expectation.window

    private fun loopRange(
        window: TarjiHoldWindow,
        capture: TarjiLabCapture,
        captureMs: Float,
    ): LoopRange {
        val frames = loopFrames(window, captureMs, capture.hopCount, capture.hopSamples)
        return LoopRange(
            startMs = window.startMs,
            endMs = window.endMs,
            startFrame = frames.first,
            endFrame = frames.last + 1,
        )
    }

    /** Pause the loop in place so Play resumes at the same sample. */
    private fun pausePreview() {
        val st = _ui.value
        if (!st.previewPlaying) return
        val position = previewPlayheadMs().coerceAtLeast(0f)
        runCatching { audioTrack?.pause() }
        _ui.value = st.copy(
            previewPlaying = false,
            previewPositionMs = position,
        )
    }

    /** Move the loop to [positionMs]. Dragging works while playing or paused. */
    fun seekPreviewTo(positionMs: Float) {
        val st = _ui.value
        val capture = st.capture ?: return
        val durationMs = st.previewDurationMs.takeIf { it > 0f }
            ?: capture.hopCount * capture.hopContentDurationMs()
        val loop = loopRange(
            previewLoopWindow(st.previewScope, st.expectation.window, durationMs),
            capture,
            durationMs,
        )
        val position = if (st.previewPlaying) {
            positionMs.coerceIn(loop.startMs, (loop.endMs - 1f).coerceAtLeast(loop.startMs))
        } else {
            positionMs.coerceIn(0f, (durationMs - 1f).coerceAtLeast(0f))
        }
        if (st.previewPlaying) {
            startPreviewAt(position, st.previewScope)
        } else {
            // Paused audio never owns the cursor; stale hardware counters must
            // not overwrite a scrub or a newly edited loop range.
            _ui.value = st.copy(previewPositionMs = position, previewDurationMs = durationMs)
        }
    }

    /** Return to the active loop's beginning, preserving playing/paused state. */
    fun rewindPreview() {
        val st = _ui.value
        val capture = st.capture ?: return
        val start = previewLoopWindow(st.previewScope, st.expectation.window, capture.totalContentMs).startMs
        seekPreviewTo(start)
    }

    /** A drag always leaves the loop paused at the chosen sample. */
    fun beginPreviewScrub() {
        if (_ui.value.previewPlaying) pausePreview()
        scrubActive = true
    }

    /** Return playhead ownership to the audio clock; Play resumes explicitly. */
    fun endPreviewScrub() {
        scrubActive = false
    }

    fun stopPreview() {
        audioTrack?.let {
            runCatching { it.pause() }
            runCatching { it.release() }
        }
        audioTrack = null
        previewClock = null
        previewRateHz = 0
        scrubActive = false
        holdEditActive = false
        _ui.value = _ui.value.copy(
            previewPlaying = false,
            previewDurationMs = _ui.value.capture?.totalContentMs ?: 0f,
            previewPositionMs = 0f,
        )
    }

    /** Playhead (ms) inside the preview loop, or −1 when stopped. */
    fun previewPlayheadMs(): Float {
        val st = _ui.value
        val duration = st.previewDurationMs
        if (duration <= 0f) return -1f
        if (!st.previewPlaying || scrubActive || holdEditActive) return st.previewPositionMs.coerceIn(0f, duration)
        val track = audioTrack ?: return st.previewPositionMs
        val clock = previewClock ?: return st.previewPositionMs
        return clock.frameAt(track.playbackHeadPosition) * 1_000f / previewRateHz
    }

    private fun applyPreviewSpeed(): Boolean {
        val track = audioTrack ?: return true
        return runCatching {
            track.playbackParams = PlaybackParams().setSpeed(_ui.value.previewSpeed.factor).setPitch(1f)
            // Setting PlaybackParams on a paused track can start it.
            if (!_ui.value.previewPlaying) track.pause()
        }.isSuccess
    }

    private fun previewFrame(capture: TarjiLabCapture, positionMs: Float): Int {
        val rate = TarjiLabCodec.playbackSampleRate(capture)
        val totalFrames = (capture.hopCount * capture.hopSamples).coerceAtLeast(1)
        return (positionMs * rate / 1_000f).roundToInt().coerceIn(0, totalFrames - 1)
    }

    // ── Samples ────────────────────────────────────────────────────────────

    /** Keep a snapshot across the picker (including Activity recreation). */
    fun prepareSampleExport(): String? {
        pendingExport = _ui.value.sampleForExport()
        val sample = pendingExport ?: run {
            _ui.value = _ui.value.copy(note = "Capture a word with reciter details first.")
            return null
        }
        return TarjiLabCodec.fileName(sample)
    }

    /** Write only to the user-selected destination; canceling the picker writes nothing. */
    fun exportSample(context: Context, uri: Uri?) {
        val sample = pendingExport ?: return
        pendingExport = null
        if (uri == null) return
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(uri, "wt")
                        ?: error("Could not open the selected file")
                    output.bufferedWriter().use { it.write(TarjiLabCodec.encode(sample)) }
                }
                _ui.value = _ui.value.copy(note = "Saved ${TarjiLabCodec.fileName(sample)}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(note = "Export failed: ${error.message}")
            }
        }
    }

    /** Document reads never block the transport; failures remain visible in the lab. */
    fun importSample(context: Context, uri: Uri) {
        importJob?.cancel()
        val resolver = context.applicationContext.contentResolver
        importJob = viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open the selected sample")
                }
                importSample(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                _ui.value = _ui.value.copy(note = "Import failed: ${error.message}")
            }
        }
    }

    /** Load a [TarjiLabSample] (from the file picker): its capture replaces
     * the current one, its knobs become the lab's, and analysis re-runs. */
    fun importSample(json: String) {
        val sample = runCatching { TarjiLabCodec.decode(json) }.getOrNull()
        if (sample == null) {
            _ui.value = _ui.value.copy(note = "Not a Tarjīʿ Lab sample.")
            return
        }
        val capture = runCatching { TarjiLabCodec.toCapture(sample) }.getOrNull()
        if (capture == null || capture.hopCount == 0) {
            _ui.value = _ui.value.copy(note = "Sample has no PCM.")
            return
        }
        loadJob?.cancel()
        analyzeJob?.cancel()
        abortCapture()
        cancelPulseMatch()
        stopPreview()
        if (sample.reciterId == settingsRepo.settings.value.reciterId) {
            InkEngine.tuning = TarjiLabKnobs.applyToTuning(sample.knobs, InkEngine.tuning)
        }
        profiles?.save(sample.reciterId, sample.knobs)
        knobHistory.clear()
        _ui.value = _ui.value.copy(
            isLoading = false,
            // The sample may come from a different word/ayah than the current
            // target — adopt its metadata so the header, span bracket, and
            // any re-export all describe what is actually in the buffer.
            surahId = sample.surahId,
            ayah = sample.ayah,
            wordPosition = sample.wordPosition,
            wordArabic = sample.wordArabic,
            sampleReciterId = sample.reciterId,
            sampleReciterName = sample.reciterName,
            // The sample's word span is unknown without its ayah's marks —
            // drop the bracket rather than draw it against the wrong media.
            wordStartMs = 0L,
            wordEndMs = 0L,
            capture = capture,
            trace = null,
            reference = null,
            showingReference = false,
            canUndo = false,
            canRedo = false,
            capturing = false,
            previewDurationMs = capture.totalContentMs,
            previewPositionMs = 0f,
            wordCount = 0,
            surahName = "Sample",
            firstHopMediaMs = sample.firstHopMediaMs,
            knobs = sample.knobs,
            expectation = TarjiLabExpectation().withWindow(
                sample.expectation.window ?: TarjiHoldWindow(0f, capture.totalContentMs),
                capture.totalContentMs,
            ),
            tool = _ui.value.tool,
            sampleNotes = sample.notes,
            captureError = null,
            note = "Loaded ${sample.label}",
            view = TarjiViewWindow.fit(capture.hopCount * capture.hopContentDurationMs()),
        )
        reanalyze()
    }

    /** Refresh analysis after returning from the background; audio stays paused. */
    fun onResume() {
        if (_ui.value.capture != null) reanalyze()
    }

    /** Called when the lab is left: silence the preview and the player. */
    fun onExit() {
        importJob?.cancel()
        cancelPulseMatch()
        loadJob?.cancel()
        analyzeJob?.cancel()
        player.setSkipSilenceGaps(true)
        stopPreview()
        val interruptedCapture = _ui.value.capturing || _ui.value.isLoading
        abortCapture()
        _ui.value = _ui.value.copy(
            analyzing = false,
            isLoading = false,
            captureError = if (interruptedCapture) "Capture interrupted. Retry this word." else _ui.value.captureError,
        )
        profiles?.applyToEngine(settingsRepo.settings.value.reciterId)
    }

    override fun onCleared() {
        super.onCleared()
        onExit()
    }

    private fun toPcm16(capture: TarjiLabCapture): ByteArray {
        val n = capture.pcm.size
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (capture.pcm[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            bytes[2 * i] = (s.toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    companion object {
        private const val CAPTURE_LEAD_MS = 300L
        private const val CAPTURE_TAIL_MS = 1_000L
        private const val CAPTURE_TIMEOUT_MS = 20_000L
        private const val POLL_MS = 40L
        private const val MAX_NOTES_LENGTH = 1_000
        /** Tail guard so the poll never chases the very last audio frames. */
        private const val END_GUARD_MS = 20L
        /** Static AudioTrack buffers above this are refused (dev-lab cap). */
        private const val MAX_STATIC_BYTES = 1_048_576
    }
}

/** Reject the stale pre-seek clock; the first poll after landing is close to
 * the requested lead-in, while an old end-of-ayah position is not. */
internal fun captureSeekHasLanded(positionMs: Long, targetStartMs: Long): Boolean =
    positionMs in (targetStartMs - 80L).coerceAtLeast(0L)..(targetStartMs + 750L)
