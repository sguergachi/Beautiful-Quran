package com.beautifulquran.timingslab

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.data.Settings
import com.beautifulquran.data.SettingsRepository
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.HighlightEngine
import com.beautifulquran.playback.PlayerController
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.reader.ActiveWord
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The Lab's two verbs: watch/tune the existing marks, or record a fresh
 * tap-sync pass over the whole ayah (Musixmatch style). */
enum class LabMode { LISTEN, RECORD }

data class TimingsLabUiState(
    val isLoading: Boolean = true,
    val surahId: Int = 0,
    val surahName: String = "",
    val ayah: Int = 1,
    val ayahCount: Int = 0,
    val reciter: Reciter? = null,
    /** The full ayah row — rendered by the reader's own AyahBlock. */
    val ayahData: Ayah? = null,
    /** Working copy of this ayah's marks, always sorted by startMs. The live
     * preview runs HighlightEngine straight over this list, so edits are
     * visible before they persist. */
    val passes: List<Segment> = emptyList(),
    /** The bundled (shipped) marks for this ayah, with no overrides — the
     * target of a per-word "Reset to default". */
    val defaultPasses: List<Segment> = emptyList(),
    val mode: LabMode = LabMode.LISTEN,
    /** Index into [passes] of the mark being tuned, or null. */
    val selectedPass: Int? = null,
    /** This ayah has a saved on-device correction. */
    val isOverridden: Boolean = false,
    /** Corrected ayahs in the whole override store (the submit ribbon). */
    val overrideCount: Int = 0,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val speed: Float = 1f,
    /** Marks dropped in the current record pass (enables the in-pass Undo). */
    val recordedMarks: Int = 0,
    val error: String? = null,
)

class TimingsLabViewModel(
    private val repository: QuranRepository,
    private val settingsRepo: SettingsRepository,
    private val player: PlayerController,
    private val overrides: TimingOverrides,
) : ViewModel() {

    private val _ui = MutableStateFlow(TimingsLabUiState())
    val ui: StateFlow<TimingsLabUiState> = _ui.asStateFlow()

    /** Reader settings (reading mode, gloss, font scale) so the stage renders
     * exactly what the reader renders. */
    val settings: StateFlow<Settings> = settingsRepo.settings

    /** Drives AyahBlock's karaoke fade, computed from the *edited* marks. */
    private val _activeWord = MutableStateFlow<ActiveWord?>(null)
    val activeWord: StateFlow<ActiveWord?> = _activeWord.asStateFlow()

    val playerState: StateFlow<PlayerUiState> = player.state

    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var saveJob: Job? = null
    private var auditionJob: Job? = null
    @Volatile private var recitersCache: List<Reciter> = emptyList()

    /** True once any edit touched the working copy; gates auto-save so merely
     * opening an ayah never writes an override. */
    private var edited = false

    /** Marks of the in-flight record pass in tap order (kept sorted because
     * the playhead only moves forward between rewinds). */
    private val recordMarks = mutableListOf<Segment>()

    /** Working copy as it was when Re-sync started; restored on cancel. */
    private var recordBackup: List<Segment> = emptyList()

    /** Whether playback actually started during this record pass, so the
     * stream ending can be told apart from it never starting. */
    private var recordPlaybackSeen = false

    init {
        viewModelScope.launch {
            player.state.collect { ps ->
                val matches = matchesLab(ps)
                _ui.value = _ui.value.copy(
                    isPlaying = matches && ps.isPlaying,
                    isBuffering = matches && ps.isBuffering,
                    speed = ps.speed,
                    durationMs = if (matches && player.durationMs > 0L) player.durationMs else _ui.value.durationMs,
                    error = if (matches) ps.error else null,
                )
                if (matches) {
                    if (_ui.value.mode == LabMode.RECORD && ps.isPlaying) recordPlaybackSeen = true
                    pollPosition()
                } else if (_ui.value.mode == LabMode.RECORD && ps.nowPlaying == null && recordPlaybackSeen) {
                    // The single-item playlist ended (record runs with repeat
                    // off): the pass is complete.
                    finishRecord()
                }
            }
        }
        viewModelScope.launch {
            overrides.overrides.collect { map ->
                _ui.value = _ui.value.copy(overrideCount = map.size)
            }
        }
    }

    private fun matchesLab(ps: PlayerUiState): Boolean {
        val np = ps.nowPlaying ?: return false
        val st = _ui.value
        return np.surahId == st.surahId && np.ayah == st.ayah && np.reciterId == st.reciter?.id
    }

    /** Samples the playhead and re-derives the active word while this ayah is
     * the loaded media item; the highlight follows edits live because it is
     * recomputed from [TimingsLabUiState.passes] every tick. */
    private fun pollPosition() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (matchesLab(player.state.value)) {
                val st = _ui.value
                val duration = player.durationMs.takeIf { it > 0L } ?: st.durationMs
                val pos = player.positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
                _ui.value = st.copy(positionMs = pos, durationMs = duration)
                _activeWord.value = st.passes
                    .let { HighlightEngine.activeInfo(it, pos) }
                    ?.let {
                        ActiveWord(
                            ayah = st.ayah,
                            wordPosition = it.position,
                            startMs = it.startMs,
                            durationMs = (it.holdEndMs - it.startMs).coerceAtLeast(0L),
                            isRepeat = it.isRepeat,
                            highWater = it.highWater,
                            repeatStart = it.repeatStart,
                        )
                    }
                // With repeat off, a record pass ends when the audio does.
                if (st.mode == LabMode.RECORD && duration > 0L && pos >= duration - END_GUARD_MS) {
                    finishRecord()
                }
                delay(if (player.state.value.isPlaying) TICK_MS else PAUSED_TICK_MS)
            }
            _activeWord.value = null
        }
    }

    // ── Target ayah ────────────────────────────────────────────────────────

    fun initFromLastOpened() {
        val s = settingsRepo.settings.value
        changeTarget(s.lastSurah.takeIf { it in 1..114 } ?: 1, s.lastAyah.coerceAtLeast(1))
    }

    /** [focusWordPosition] is the word that was long-pressed in the reader:
     * its mark is selected and auditioned as soon as the ayah loads, so the
     * fix loop starts without another tap. */
    fun changeTarget(surahId: Int, ayah: Int, focusWordPosition: Int? = null) {
        if (_ui.value.mode == LabMode.RECORD) finishRecord()
        persistNow()
        pauseIfLabAyahPlaying()
        load(surahId, ayah, focusWordPosition)
    }

    fun nextAyah() {
        val st = _ui.value
        if (st.ayahCount <= 0) return
        changeTarget(st.surahId, (st.ayah % st.ayahCount) + 1)
    }

    fun prevAyah() {
        val st = _ui.value
        if (st.ayahCount <= 0) return
        changeTarget(st.surahId, if (st.ayah <= 1) st.ayahCount else st.ayah - 1)
    }

    private fun pauseIfLabAyahPlaying() {
        if (matchesLab(player.state.value) && player.state.value.isPlaying) {
            player.togglePlayPause()
        }
    }

    private fun load(surahId: Int, ayah: Int, focusWordPosition: Int? = null) {
        loadJob?.cancel()
        auditionJob?.cancel()
        edited = false
        recordMarks.clear()
        _activeWord.value = null
        _ui.value = TimingsLabUiState(
            isLoading = true,
            surahId = surahId,
            ayah = ayah,
            overrideCount = _ui.value.overrideCount,
        )
        loadJob = viewModelScope.launch {
            val reciters = repository.reciters()
            recitersCache = reciters
            val reciter = reciters.firstOrNull { it.id == settingsRepo.settings.value.reciterId }
                ?: reciters.first()
            val content = repository.surahContent(surahId)
            if (surahId != _ui.value.surahId || ayah != _ui.value.ayah) return@launch
            val ayahRow = content.ayahs[(ayah - 1).coerceIn(0, content.ayahs.lastIndex)]
            // timings() already fuses saved overrides over the DB row;
            // bundledTimings() is the un-overridden shipped default, for reset.
            val segs = repository.timings(reciter.id, surahId)[ayahRow.number].orEmpty()
            val defaults = repository.bundledTimings(reciter.id, surahId)[ayahRow.number].orEmpty()
            val key = OverrideKey(reciter.id, surahId, ayahRow.number)
            _ui.value = TimingsLabUiState(
                isLoading = false,
                surahId = surahId,
                surahName = content.surah.nameTransliteration,
                ayah = ayahRow.number,
                ayahCount = content.surah.ayahCount,
                reciter = reciter,
                ayahData = ayahRow,
                passes = segs,
                defaultPasses = defaults,
                isOverridden = overrides.get(key) != null,
                overrideCount = _ui.value.overrideCount,
                speed = player.state.value.speed,
            )
            settingsRepo.updateListeningPosition(surahId, ayahRow.number)
            // Long-pressed word from the reader: jump straight into tuning it.
            if (focusWordPosition != null) selectWord(focusWordPosition)
        }
    }

    // ── Transport ──────────────────────────────────────────────────────────

    fun playPause() {
        val st = _ui.value
        if (st.isLoading) return
        if (matchesLab(player.state.value)) {
            player.togglePlayPause()
        } else {
            startLabPlayback(startMs = 0L, loop = st.mode == LabMode.LISTEN)
        }
    }

    fun restart() = seekTo(0L, play = true)

    fun cycleSpeed() {
        val speeds = listOf(1f, 0.75f, 0.5f)
        val idx = speeds.indexOfFirst { abs(it - player.state.value.speed) < 0.01f }
        player.setSpeed(speeds[(idx + 1).mod(speeds.size)])
    }

    fun scrubTo(positionMs: Long) = seekTo(positionMs, play = true)

    private fun seekTo(positionMs: Long, play: Boolean) {
        val st = _ui.value
        // Duration is unknown (0) until the media item loads — don't clamp the
        // target to it then, or the first audition would always start at 0;
        // the player clamps out-of-range seeks itself.
        val ms = positionMs.coerceIn(0L, st.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        if (matchesLab(player.state.value)) {
            if (play) player.seekToWordAndPlay(st.ayah, ms) else player.seekToWord(st.ayah, ms)
        } else if (play) {
            startLabPlayback(startMs = ms, loop = st.mode == LabMode.LISTEN)
        }
    }

    /** Loads a one-item playlist for the lab's ayah through the shared
     * playback service, so caching / audio focus behave exactly as in the
     * reader. Looping uses the same range-repeat path as the reader
     * (setRepeatRange(ayah, ayah)) so the boundary monitor wraps the loop
     * ~80 ms before the audio actually ends — never entering STATE_ENDED —
     * instead of fighting ExoPlayer's own REPEAT_MODE_ONE at the seam, which
     * stuttered the last word on every wrap. */
    private fun startLabPlayback(startMs: Long, loop: Boolean) {
        val st = _ui.value
        val reciter = st.reciter ?: return
        player.playSurah(
            surahId = st.surahId,
            ayahCount = st.ayah,
            startAyah = st.ayah,
            reciter = reciter,
            surahName = st.surahName,
            startPositionMs = startMs,
            preserveRepeatRange = false,
            includeBasmalahLeadIn = false,
        )
        viewModelScope.launch {
            delay(PLAY_SETTLE_MS)
            if (loop) {
                // Wrap exactly at the last mark's end so the listener hears
                // the whole verse before the loop — matches the reader.
                val endMs = st.passes.lastOrNull()?.endMs
                    ?.takeIf { it > 0L }
                player.setRepeatRange(st.ayah, st.ayah, endMs)
            } else {
                player.clearRepeatRange()
            }
        }
    }

    // ── Record (Re-sync) ───────────────────────────────────────────────────

    fun startRecord() {
        val st = _ui.value
        if (st.isLoading || st.reciter == null) return
        recordBackup = st.passes
        recordMarks.clear()
        recordPlaybackSeen = false
        _ui.value = st.copy(
            mode = LabMode.RECORD,
            passes = emptyList(),
            selectedPass = null,
            recordedMarks = 0,
        )
        _activeWord.value = null
        // Repeat OFF so the stream ending marks the pass complete.
        if (matchesLab(player.state.value)) {
            player.clearRepeatRange()
            player.seekToWordAndPlay(st.ayah, 0L)
        } else {
            startLabPlayback(startMs = 0L, loop = false)
        }
    }

    /** A word tapped on the stage. In Record it drops that word's start mark
     * at the playhead (earlier positions again = repeat backtrack); in Listen
     * it selects the word's mark for tuning and auditions it. */
    fun tapWord(position: Int) {
        val st = _ui.value
        val wordCount = st.ayahData?.words?.size ?: return
        val pos = position.coerceIn(1, wordCount)
        if (st.mode == LabMode.RECORD) dropMark(pos) else selectWord(pos)
    }

    private fun dropMark(position: Int) {
        val st = _ui.value
        // Compensate finger reaction latency, scaled to how fast the audio is
        // actually moving; the Tune card catches whatever this misses.
        val comp = (TAP_LATENCY_MS * player.state.value.speed).toLong()
        var start = (player.positionMs - comp).coerceAtLeast(0L)
        recordMarks.lastOrNull()?.let { prev ->
            if (start <= prev.startMs) start = prev.startMs + MIN_MARK_GAP_MS
        }
        val provisionalEnd = st.durationMs.takeIf { it > start } ?: (start + PROVISIONAL_MARK_MS)
        recordMarks += Segment(position, start, provisionalEnd)
        publishRecordMarks()
    }

    fun undoTap() {
        val last = recordMarks.removeLastOrNull() ?: return
        publishRecordMarks()
        seekTo((last.startMs - UNDO_REWIND_MS).coerceAtLeast(0L), play = true)
    }

    /** Jump back a few seconds and clear the marks in the overrun, so just
     * that stretch is re-tapped. */
    fun rewind() {
        val target = (_ui.value.positionMs - REWIND_MS).coerceAtLeast(0L)
        recordMarks.removeAll { it.startMs >= target }
        publishRecordMarks()
        seekTo(target, play = true)
    }

    private fun publishRecordMarks() {
        edited = true
        _ui.value = _ui.value.copy(
            passes = withDerivedEnds(recordMarks.toList(), _ui.value.durationMs),
            recordedMarks = recordMarks.size,
        )
    }

    /** Ends the record pass: derives ends, saves, and replays from the top so
     * the correction is verified immediately. A pass with no taps is treated
     * as cancelled — the previous marks come back. */
    fun finishRecord() {
        val st = _ui.value
        if (st.mode != LabMode.RECORD) return
        player.setSpeed(1f)
        if (recordMarks.isEmpty()) {
            _ui.value = st.copy(mode = LabMode.LISTEN, passes = recordBackup, recordedMarks = 0)
            player.setRepeatRange(st.ayah, st.ayah, lastMarkEnd(labPasses = recordBackup))
            return
        }
        val passes = withDerivedEnds(recordMarks.toList(), st.durationMs)
        recordMarks.clear()
        _ui.value = st.copy(mode = LabMode.LISTEN, passes = passes, recordedMarks = 0)
        persistNow()
        player.setRepeatRange(st.ayah, st.ayah, lastMarkEnd(labPasses = passes))
        seekTo(0L, play = true)
    }

    /** Wrap the loop exactly at the last mark's end so the whole verse still
     * plays before the wrap — matches the reader's per-ayah repeat range. */
    private fun lastMarkEnd(labPasses: List<Segment>): Long? =
        labPasses.lastOrNull()?.endMs?.takeIf { it > 0L }

    // ── Tune (Listen-mode selection + nudges) ──────────────────────────────

    private fun selectWord(position: Int) {
        val st = _ui.value
        val candidates = st.passes.withIndex().filter { it.value.position == position }
        if (candidates.isEmpty()) return
        val idx = candidates.minByOrNull { abs(it.value.startMs - st.positionMs) }!!.index
        _ui.value = st.copy(selectedPass = idx)
        audition(idx)
    }

    fun selectPass(index: Int) {
        if (index !in _ui.value.passes.indices) return
        _ui.value = _ui.value.copy(selectedPass = index)
        audition(index)
    }

    fun closeTune() {
        auditionJob?.cancel()
        _ui.value = _ui.value.copy(selectedPass = null)
    }

    fun auditionSelected() {
        _ui.value.selectedPass?.let(::audition)
    }

    /** Once a burst of nudges settles, replay from just before the new start
     * so every adjustment is judged by ear without reaching for Replay. */
    private fun auditionAfterNudge() {
        auditionJob?.cancel()
        auditionJob = viewModelScope.launch {
            delay(NUDGE_AUDITION_MS)
            auditionSelected()
        }
    }

    /** Play from just before the mark so the ear judges the (new) start. */
    private fun audition(index: Int) {
        val pass = _ui.value.passes.getOrNull(index) ?: return
        seekTo((pass.startMs - AUDITION_LEAD_MS).coerceAtLeast(0L), play = true)
    }

    // ── Reset a word to its bundled default ─────────────────────────────────

    /** The marks the selected word shipped with (may be empty if the word had
     * no bundled timing). Null when nothing is selected. */
    private fun selectedWordDefault(): Pair<Int, List<Segment>>? {
        val st = _ui.value
        val pos = st.selectedPass?.let { st.passes.getOrNull(it)?.position } ?: return null
        return pos to st.defaultPasses.filter { it.position == pos }
    }

    /** Reverts just the selected word to its bundled default — its adjusted
     * mark(s) are replaced by the shipped one(s), every other word untouched. */
    fun resetSelectedWordToDefault() {
        val st = _ui.value
        val (pos, def) = selectedWordDefault() ?: return
        auditionJob?.cancel()
        edited = true
        val rebuilt = withDerivedEnds(st.passes.filter { it.position != pos } + def, st.durationMs)
        // Re-select the word's (now default) first mark if it still exists.
        val newSel = rebuilt.indexOfFirst { it.position == pos }.takeIf { it >= 0 }
        _ui.value = st.copy(passes = rebuilt, selectedPass = newSel)
        persistNow()
        newSel?.let { audition(it) }
    }

    /** Moves the selected mark's start by [deltaMs].
     *
     * A **repeat** pass floats free — droppable anywhere in the ayah, even
     * across other marks, because it's an extra occurrence rather than part of
     * the ordered first-pass sequence. A **first-pass** word mark stays in
     * reading order, clamped between its neighbours so the sequence can't
     * scramble. Ends are re-derived and the selection re-found by identity, so
     * a repeat that jumps past a neighbour stays selected. */
    fun nudgeSelected(deltaMs: Long) {
        val st = _ui.value
        val i = st.selectedPass ?: return
        val pass = st.passes.getOrNull(i) ?: return
        val maxStart = st.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        // Repeat = an earlier mark already reached this word's position.
        val priorMax = st.passes.take(i).maxOfOrNull { it.position } ?: 0
        val isRepeat = priorMax >= pass.position
        val newStart = if (isRepeat) {
            (pass.startMs + deltaMs).coerceIn(0L, maxStart)
        } else {
            val floor = st.passes.getOrNull(i - 1)?.startMs?.plus(MIN_MARK_GAP_MS) ?: 0L
            val ceil = st.passes.getOrNull(i + 1)?.startMs?.minus(MIN_MARK_GAP_MS)
                ?: maxStart.takeIf { it != Long.MAX_VALUE }?.minus(MIN_MARK_GAP_MS)
                ?: Long.MAX_VALUE
            (pass.startMs + deltaMs).coerceIn(floor, ceil.coerceAtLeast(floor))
        }
        if (newStart == pass.startMs) return
        val rebuilt = withDerivedEnds(
            st.passes.filterIndexed { j, _ -> j != i } + pass.copy(startMs = newStart),
            st.durationMs,
        )
        val newIndex = rebuilt.indexOfFirst { it.position == pass.position && it.startMs == newStart }
            .takeIf { it >= 0 } ?: i.coerceIn(0, rebuilt.lastIndex)
        edited = true
        _ui.value = st.copy(passes = rebuilt, selectedPass = newIndex)
        persistDebounced()
        auditionAfterNudge()
    }

    /** Adds a repeat pass for the selected word: a new mark at the current
     * playhead (or just after the selection if the playhead is earlier), then
     * selects it so the slide bar positions exactly where the repeat lands.
     * Everything else is left untouched — this never re-syncs the ayah. */
    fun addRepeat() {
        val st = _ui.value
        val i = st.selectedPass ?: return
        val sel = st.passes.getOrNull(i) ?: return
        val seed = maxOf(sel.startMs + REPEAT_SEED_GAP_MS, st.positionMs)
        val ceil = st.durationMs.takeIf { it > 0L }?.minus(MIN_MARK_GAP_MS)
            ?: (seed + PROVISIONAL_MARK_MS)
        val start = seed.coerceIn(0L, ceil.coerceAtLeast(0L))
        val newSeg = Segment(sel.position, start, start + PROVISIONAL_MARK_MS)
        val merged = withDerivedEnds(st.passes + newSeg, st.durationMs)
        val newIndex = merged.indexOfFirst { it.position == sel.position && it.startMs == start }
            .takeIf { it >= 0 } ?: i
        auditionJob?.cancel()
        edited = true
        _ui.value = st.copy(passes = merged, selectedPass = newIndex)
        persistNow()
        audition(newIndex)
    }

    fun deleteSelected() {
        val st = _ui.value
        val i = st.selectedPass ?: return
        if (i !in st.passes.indices) return
        auditionJob?.cancel()
        edited = true
        _ui.value = st.copy(
            passes = withDerivedEnds(st.passes.filterIndexed { j, _ -> j != i }, st.durationMs),
            selectedPass = null,
        )
        persistNow()
    }

    /** Contiguity repair after any mutation: a mark may never overlap the next
     * one, and the final mark ends at the audio duration when its end is
     * missing or inverted. Valid hand-set / bundled ends are left alone. */
    private fun withDerivedEnds(passes: List<Segment>, durationMs: Long): List<Segment> {
        val sorted = passes.sortedBy { it.startMs }
        return sorted.mapIndexed { i, p ->
            val next = sorted.getOrNull(i + 1)
            val end = when {
                next != null && (p.endMs > next.startMs || p.endMs <= p.startMs) -> next.startMs
                next == null && p.endMs <= p.startMs ->
                    durationMs.takeIf { it > p.startMs } ?: (p.startMs + PROVISIONAL_MARK_MS)
                else -> p.endMs
            }
            if (end == p.endMs) p else p.copy(endMs = end)
        }
    }

    // ── Persistence ────────────────────────────────────────────────────────

    private fun persistDebounced() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persistNow()
        }
    }

    private fun persistNow() {
        saveJob?.cancel()
        if (!edited) return
        val st = _ui.value
        val reciter = st.reciter ?: return
        val key = OverrideKey(reciter.id, st.surahId, st.ayah)
        val segs = st.passes.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (segs.isEmpty()) overrides.clear(key) else overrides.set(OverrideEntry(key, segs))
        edited = false
        _ui.value = _ui.value.copy(isOverridden = segs.isNotEmpty())
    }

    /** Reverts this ayah to the bundled DB timings. */
    fun resetOverride() {
        val st = _ui.value
        val reciter = st.reciter ?: return
        overrides.clear(OverrideKey(reciter.id, st.surahId, st.ayah))
        edited = false
        viewModelScope.launch {
            // With the override gone, timings() serves the DB row again.
            val segs = repository.timings(reciter.id, st.surahId)[st.ayah].orEmpty()
            _ui.value = _ui.value.copy(passes = segs, isOverridden = false, selectedPass = null)
        }
    }

    fun clearAllOverrides() {
        overrides.clearAll()
        resetOverride()
    }

    /** Called when the Lab page is left: settle everything so the reader
     * comes back to normal speed and silence. */
    fun onExit() {
        auditionJob?.cancel()
        if (_ui.value.mode == LabMode.RECORD) finishRecord()
        persistNow()
        player.setSpeed(1f)
        pauseIfLabAyahPlaying()
    }

    // ── Submission ─────────────────────────────────────────────────────────

    /** Build a patch of either the open ayah only, or every on-device override. */
    private fun buildPatch(currentOnly: Boolean): TimingsPatch {
        persistNow()
        val reciters = recitersCache
        val source = if (currentOnly) {
            val st = _ui.value
            val reciter = st.reciter
            if (reciter == null) {
                emptyMap()
            } else {
                val key = OverrideKey(reciter.id, st.surahId, st.ayah)
                overrides.get(key)?.let { mapOf(key to it) }.orEmpty()
            }
        } else {
            overrides.overrides.value
        }
        return TimingsPatchExporter.build(source) { id ->
            reciters.firstOrNull { it.id == id }
        }
    }

    fun submit(context: Context, currentOnly: Boolean = false) {
        val patch = buildPatch(currentOnly)
        if (patch.edits.isEmpty()) return
        try {
            context.startActivity(TimingsPatchExporter.newIssueIntent(patch))
        } catch (e: Exception) {
            TimingsPatchExporter.copyToClipboard(context, patch)
        }
    }

    fun copyPatch(context: Context, currentOnly: Boolean = false) {
        val patch = buildPatch(currentOnly)
        if (patch.edits.isEmpty()) return
        TimingsPatchExporter.copyToClipboard(context, patch)
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    companion object {
        private const val TICK_MS = 33L
        private const val PAUSED_TICK_MS = 250L
        private const val PLAY_SETTLE_MS = 200L
        /** Finger reaction compensation at 1× (scaled by playback speed). */
        private const val TAP_LATENCY_MS = 100f
        private const val MIN_MARK_GAP_MS = 10L
        private const val PROVISIONAL_MARK_MS = 600L
        /** Where a new repeat mark seeds relative to the selected mark when the
         * playhead isn't already past it. */
        private const val REPEAT_SEED_GAP_MS = 500L
        private const val AUDITION_LEAD_MS = 800L
        /** Quiet gap after the last nudge before the automatic re-audition. */
        private const val NUDGE_AUDITION_MS = 550L
        private const val UNDO_REWIND_MS = 1_200L
        private const val REWIND_MS = 4_000L
        private const val END_GUARD_MS = 80L
        private const val SAVE_DEBOUNCE_MS = 600L
    }
}
