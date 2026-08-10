package com.beautifulquran.tarjilab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Environment
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The Tarjīʿ Lab: capture one word's PCM from the live tap, replay it on a
 * seamless loop, and re-run the pure [com.beautifulquran.playback.Tarji]
 * detector offline with editable knobs — waveform and tarjīʿ sine drawn
 * from the exact same hop stream the live shimmer eats.
 *
 * The loop preview plays the *captured* stream through its own [AudioTrack]
 * (static mode, hardware loop points), so the playhead, the plotted trace,
 * and the shimmer preview pulse are in lockstep by construction — no route
 * latency, no clock rebasing. Capture itself plays the word once through the
 * shared player so the tap records it.
 *
 * Knobs are the Ink Lab's: every edit also writes [InkEngine.tuning], so
 * what the lab perfects is what the app ships.
 */
class TarjiLabViewModel(
    private val repository: QuranRepository,
    private val settingsRepo: SettingsRepository,
    private val player: PlayerController,
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
        val sineFit: TarjiSineFit? = null,
        val knobs: TarjiLabKnobs = TarjiLabKnobs(),
        val capturing: Boolean = false,
        /** Capture progress from the requested word span, 0..1. */
        val captureProgress: Float = 0f,
        val analyzing: Boolean = false,
        val captureError: String? = null,
        val previewPlaying: Boolean = false,
        /** Wall clock when the preview loop started (−1 while stopped). */
        val previewStartWallMs: Long = -1L,
        val previewDurationMs: Float = 0f,
        /** Loop position at the last pause/seek, in content milliseconds. */
        val previewPositionMs: Float = 0f,
        /** When a sample was imported, its reciter name (the local reciter
         * may differ from the sample's). */
        val sampleReciterName: String? = null,
        val note: String? = null,
    )

    private val _ui = MutableStateFlow(TarjiLabUiState())
    val ui: StateFlow<TarjiLabUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var captureJob: Job? = null
    private var analyzeJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var previewRateHz = 0
    private var previewTotalFrames = 0
    private var scrubWasPlaying = false
    private var ayahSegments: List<Segment> = emptyList()
    private var recitersCache: List<Reciter> = emptyList()

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
        loadJob?.cancel()
        captureJob?.cancel()
        audioTrack?.let { runCatching { it.pause() } }
        _ui.value = TarjiLabUiState(
            isLoading = true,
            surahId = surahId,
            ayah = ayah,
            knobs = TarjiLabKnobs.fromTuning(InkEngine.tuning),
        )
        loadJob = viewModelScope.launch {
            val reciters = repository.reciters()
            recitersCache = reciters
            val reciter = reciters.firstOrNull { it.id == settingsRepo.settings.value.reciterId }
                ?: reciters.first()
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
                knobs = TarjiLabKnobs.fromTuning(InkEngine.tuning),
            )
            settingsRepo.updateListeningPosition(surahId, ayahRow.number)
        }
    }

    private fun matchesLab(ps: PlayerUiState): Boolean {
        val np = ps.nowPlaying ?: return false
        val st = _ui.value
        return np.surahId == st.surahId && np.ayah == st.ayah && np.reciterId == st.reciter?.id
    }

    // ── Capture ────────────────────────────────────────────────────────────

    /** Play the word once through the shared player while the tap records
     * its decimated stream, then stop at the span's end and loop it. */
    fun captureWord() {
        val st = _ui.value
        if (st.isLoading || st.reciter == null || st.wordPosition == 0) return
        if (st.capturing) {
            cancelCapture()
            return
        }
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
        val ve = VoiceEnergy.active
        if (ve == null) {
            _ui.value = st.copy(captureError = "No audio probe (player not created).")
            return
        }
        // Deterministic start: pause, arm, then seek — the seek's sink flush
        // starts the capture cleanly at the new position.
        if (matchesLab(player.state.value)) {
            player.pause()
        } else {
            startLabPlayback()
        }
        ve.armCapture()
        player.setSpeed(1f)
        player.seekToWordAndPlay(st.ayah, span.first)
        _ui.value = st.copy(
            capturing = true,
            captureProgress = 0f,
            captureError = null,
            note = null,
        )
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            while (true) {
                val active = VoiceEnergy.active?.captureActive == true
                val durationMs = player.durationMs.takeIf { it > 0L }
                val spanEnd = if (durationMs != null) {
                    minOf(span.last, durationMs - END_GUARD_MS)
                } else {
                    span.last
                }
                val progress = ((player.positionMs - span.first).toFloat() /
                    (spanEnd - span.first).coerceAtLeast(1L))
                    .coerceIn(0f, 1f)
                _ui.value = _ui.value.copy(captureProgress = progress)
                val done = !active || player.positionMs >= spanEnd
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

    /** Cancel an in-flight tap without leaving a stale capture armed. */
    private fun cancelCapture() {
        captureJob?.cancel()
        captureJob = null
        VoiceEnergy.active?.disarmCapture()
        player.pause()
        _ui.value = _ui.value.copy(
            capturing = false,
            captureProgress = 0f,
            captureError = null,
            note = "Capture cancelled.",
        )
    }

    private fun finishCapture(error: String?) {
        captureJob?.cancel()
        val ve = VoiceEnergy.active
        val st = _ui.value
        val capture = ve?.disarmCapture()
        player.pause()
        if (error != null || capture == null) {
            _ui.value = st.copy(
                capturing = false,
                captureProgress = 0f,
                captureError = error ?: "No audio was captured — playback did not run.",
            )
            return
        }
        val speed = player.state.value.speed.coerceAtLeast(0f)
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
        val trimmed = if (span != null) {
            val range = TarjiLabTrim.hopRangeInSpan(capture, firstHopMediaMs, span)
            if (range.isEmpty()) capture else capture.slice(range)
        } else {
            capture
        }
        _ui.value = st.copy(
            capturing = false,
            captureProgress = 1f,
            capture = trimmed,
            firstHopMediaMs = firstHopMediaMs,
            captureError = null,
        )
        reanalyze()
        startPreview()
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
        )
    }

    // ── Offline analysis ───────────────────────────────────────────────────

    /** Re-run the detector over the captured stream with the current knobs.
     * Pure DSP on a background thread; the preview keeps playing under it. */
    private fun reanalyze() {
        val st = _ui.value
        val capture = st.capture ?: return
        analyzeJob?.cancel()
        _ui.value = st.copy(analyzing = true)
        analyzeJob = viewModelScope.launch {
            val trace = withContext(Dispatchers.Default) {
                analyzeTarjiCapture(capture, st.knobs)
            }
            _ui.value = _ui.value.copy(
                trace = trace,
                sineFit = fitTarjiSine(trace),
                analyzing = false,
            )
        }
    }

    // ── Knobs ──────────────────────────────────────────────────────────────

    /** Edit the detector knobs: written to the Ink Lab tuning (one source of
     * truth — the live detector and persistence follow), then the trace
     * re-runs against the capture. */
    fun updateKnobs(transform: (TarjiLabKnobs) -> TarjiLabKnobs) {
        val next = transform(_ui.value.knobs)
        if (next == _ui.value.knobs) return
        InkEngine.tuning = TarjiLabKnobs.applyToTuning(next, InkEngine.tuning)
        _ui.value = _ui.value.copy(knobs = next)
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            delay(KNOB_DEBOUNCE_MS)
            reanalyze()
        }
    }

    /** Restore the shipped detector defaults (also resets the Ink Lab). */
    fun resetKnobs() {
        InkEngine.resetLabToShippedDefaults()
        val knobs = TarjiLabKnobs.fromTuning(InkEngine.tuning)
        _ui.value = _ui.value.copy(knobs = knobs)
        reanalyze()
    }

    // ── Loop preview ───────────────────────────────────────────────────────

    /** Toggle the captured loop without destroying its current position. */
    fun togglePreview() {
        if (_ui.value.previewPlaying) pausePreview() else resumePreview()
    }

    /** Play the captured stream on a seamless hardware loop. Static mode
     * loops in the DSP itself, so the wall-clock playhead the UI computes
     * (modulo the capture duration) is exact to the sample. */
    fun startPreview() {
        startPreviewAt(0f)
    }

    private fun startPreviewAt(startMs: Float) {
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
        val totalFrames = capture.hopCount * capture.hopSamples
        if (!loopInfinitely(track, totalFrames)) {
            track.release()
            _ui.value = st.copy(note = "Could not arm the loop on this device.")
            return
        }
        previewRateHz = rate
        previewTotalFrames = totalFrames
        val durationMs = capture.hopCount * capture.hopContentDurationMs()
        val positionMs = normalizePreviewPosition(startMs, durationMs)
        val frame = previewFrame(capture, positionMs)
        runCatching { track.setPlaybackHeadPosition(frame) }
        audioTrack = track
        track.play()
        _ui.value = st.copy(
            previewPlaying = true,
            previewStartWallMs = SystemClock.elapsedRealtime(),
            previewDurationMs = durationMs,
            previewPositionMs = positionMs,
        )
    }

    /** Hardware loop of the whole static buffer. The modern form is
     * (start, end, loopCount) with −1 = infinite — the only one the recent
     * stubs carry; older runtimes may still offer the two-argument form.
     * Reflect both so the lab works on any supported API. */
    private fun loopInfinitely(track: AudioTrack, totalFrames: Int): Boolean {
        val threeArg = runCatching {
            AudioTrack::class.java
                .getMethod(
                    "setLoopPoints",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(track, 0, totalFrames, -1) as? Int
        }.getOrNull()
        if (threeArg != null) return threeArg >= 0
        return runCatching {
            AudioTrack::class.java
                .getMethod(
                    "setLoopPoints",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(track, 0, totalFrames)
            true
        }.getOrDefault(false)
    }

    /** Pause the loop in place so Play resumes at the same sample. */
    private fun pausePreview() {
        val st = _ui.value
        if (!st.previewPlaying) return
        val position = previewPlayheadMs().coerceAtLeast(0f)
        runCatching { audioTrack?.pause() }
        _ui.value = st.copy(
            previewPlaying = false,
            previewStartWallMs = -1L,
            previewPositionMs = position,
        )
    }

    /** Resume an existing paused track, or build one if the sample was loaded
     * but has not played yet. */
    private fun resumePreview() {
        val st = _ui.value
        val capture = st.capture ?: run {
            startPreview()
            return
        }
        val track = audioTrack
        if (track == null) {
            startPreviewAt(st.previewPositionMs)
            return
        }
        val position = normalizePreviewPosition(st.previewPositionMs, st.previewDurationMs)
        runCatching {
            track.setPlaybackHeadPosition(previewFrame(capture, position))
            track.play()
        }.onSuccess {
            _ui.value = st.copy(
                previewPlaying = true,
                previewStartWallMs = SystemClock.elapsedRealtime(),
                previewPositionMs = position,
            )
        }.onFailure {
            _ui.value = st.copy(note = "Could not resume the preview loop.")
        }
    }

    /** Move the loop to [positionMs]. Dragging works while playing or paused. */
    fun seekPreviewTo(positionMs: Float) {
        val st = _ui.value
        val capture = st.capture ?: return
        val durationMs = st.previewDurationMs.takeIf { it > 0f }
            ?: capture.hopCount * capture.hopContentDurationMs()
        val position = normalizePreviewPosition(positionMs, durationMs)
        val wasPlaying = st.previewPlaying
        val track = audioTrack
        if (track != null) {
            runCatching {
                if (wasPlaying) track.pause()
                track.setPlaybackHeadPosition(previewFrame(capture, position))
                if (wasPlaying) track.play()
            }.onFailure {
                _ui.value = st.copy(note = "Could not seek the preview loop.")
                return
            }
        }
        _ui.value = st.copy(
            previewPlaying = wasPlaying,
            previewStartWallMs = if (wasPlaying) SystemClock.elapsedRealtime() else -1L,
            previewDurationMs = durationMs,
            previewPositionMs = position,
        )
    }

    /** Pause once at the start of a drag so repeated finger updates only move
     * the hardware head instead of racing pause/play on every motion event. */
    fun beginPreviewScrub() {
        scrubWasPlaying = _ui.value.previewPlaying
        if (scrubWasPlaying) pausePreview()
    }

    /** Restore playback only if the scrub began while the loop was playing. */
    fun endPreviewScrub() {
        if (!scrubWasPlaying) return
        scrubWasPlaying = false
        resumePreview()
    }

    fun stopPreview() {
        audioTrack?.let {
            runCatching { it.pause() }
            runCatching { it.release() }
        }
        audioTrack = null
        previewRateHz = 0
        previewTotalFrames = 0
        scrubWasPlaying = false
        _ui.value = _ui.value.copy(
            previewPlaying = false,
            previewStartWallMs = -1L,
            previewDurationMs = 0f,
            previewPositionMs = 0f,
        )
    }

    /** Wall-clock playhead (ms) inside the preview loop, or −1 when stopped. */
    fun previewPlayheadMs(): Float {
        val st = _ui.value
        val duration = st.previewDurationMs
        if (duration <= 0f) return -1f
        val track = audioTrack
        if (track != null && previewRateHz > 0 && previewTotalFrames > 0) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            val frame = (head % previewTotalFrames).toInt()
            return frame * 1_000f / previewRateHz
        }
        val anchor = st.previewPositionMs.coerceIn(0f, duration)
        if (!st.previewPlaying || st.previewStartWallMs < 0L) return anchor
        val elapsed = (SystemClock.elapsedRealtime() - st.previewStartWallMs).toFloat()
        return (anchor + elapsed) % duration
    }

    private fun previewFrame(capture: TarjiLabCapture, positionMs: Float): Int {
        val rate = TarjiLabCodec.playbackSampleRate(capture)
        val totalFrames = (capture.hopCount * capture.hopSamples).coerceAtLeast(1)
        return (positionMs * rate / 1_000f).roundToInt().coerceIn(0, totalFrames - 1)
    }

    private fun normalizePreviewPosition(positionMs: Float, durationMs: Float): Float {
        if (durationMs <= 0f) return 0f
        return ((positionMs % durationMs) + durationMs) % durationMs
    }

    // ── Samples ────────────────────────────────────────────────────────────

    fun exportSample(context: Context) {
        val st = _ui.value
        val capture = st.capture ?: run {
            _ui.value = st.copy(note = "Capture a word first.")
            return
        }
        val reciter = st.reciter ?: return
        val sample = TarjiLabCodec.buildSample(
            capture = capture,
            firstHopMediaMs = st.firstHopMediaMs,
            label = TarjiLabCodec.label(reciter.name, st.surahId, st.ayah, st.wordPosition),
            reciterId = reciter.id,
            reciterName = reciter.name,
            surahId = st.surahId,
            ayah = st.ayah,
            wordPosition = st.wordPosition,
            wordArabic = st.wordArabic,
            knobs = st.knobs,
        )
        val json = TarjiLabCodec.encode(sample)
        val dir = runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        if (dir == null) {
            _ui.value = st.copy(note = "No external files dir — cannot export.")
            return
        }
        val file = File(dir, TarjiLabCodec.fileName(sample))
        runCatching { file.writeText(json) }
            .onSuccess {
                _ui.value = _ui.value.copy(
                    note = "Exported: ${file.absolutePath}",
                )
            }
            .onFailure {
                _ui.value = _ui.value.copy(note = "Export failed: ${it.message}")
            }
    }

    /** Load a [TarjiLabSample] (from the file picker): its capture replaces
     * the current one, its knobs become the lab's, and analysis re-runs. */
    fun importSample(json: String) {
        stopPreview()
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
        InkEngine.tuning = TarjiLabKnobs.applyToTuning(sample.knobs, InkEngine.tuning)
        _ui.value = _ui.value.copy(
            isLoading = false,
            // The sample may come from a different word/ayah than the current
            // target — adopt its metadata so the header, span bracket, and
            // any re-export all describe what is actually in the buffer.
            surahId = sample.surahId,
            ayah = sample.ayah,
            wordPosition = sample.wordPosition,
            wordArabic = sample.wordArabic,
            sampleReciterName = sample.reciterName,
            // The sample's word span is unknown without its ayah's marks —
            // drop the bracket rather than draw it against the wrong media.
            wordStartMs = 0L,
            wordEndMs = 0L,
            capture = capture,
            firstHopMediaMs = sample.firstHopMediaMs,
            knobs = sample.knobs,
            captureError = null,
            note = "Loaded ${sample.label}",
        )
        reanalyze()
    }

    /** Called when the lab is left: silence the preview and the player. */
    fun onExit() {
        stopPreview()
        captureJob?.cancel()
        if (matchesLab(player.state.value)) {
            player.setSpeed(1f)
            if (player.state.value.isPlaying) player.pause()
        }
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
        private const val KNOB_DEBOUNCE_MS = 120L
        /** Tail guard so the poll never chases the very last audio frames. */
        private const val END_GUARD_MS = 20L
        /** Static AudioTrack buffers above this are refused (dev-lab cap). */
        private const val MAX_STATIC_BYTES = 1_048_576
    }
}
