package com.beautifulquran.ui.rootviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beautifulquran.data.LexiconRepository
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.data.SettingsRepository
import com.beautifulquran.data.model.LexiconEntry
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.RootOccurrence
import com.beautifulquran.data.model.RootLemmaSummary
import com.beautifulquran.data.model.Segment
import com.beautifulquran.data.model.Word
import com.beautifulquran.data.model.WordMorphology
import com.beautifulquran.playback.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RootViewerUiState(
    val isLoading: Boolean = true,
    val surahId: Int = 0,
    val ayah: Int = 0,
    /** Transliterated chapter name for the word's surah (concordance return pill). */
    val surahNameTransliteration: String = "",
    val word: Word? = null,
    val morphology: WordMorphology? = null,
    val occurrenceCount: Int = 0,
    val occurrences: List<RootOccurrence> = emptyList(),
    /** Exact QAC lemma/POS analyses grouped beneath this root. */
    val lemmas: List<RootLemmaSummary> = emptyList(),
    /** Lane's article for this root; null while loading or when he has none. */
    val lexicon: LexiconEntry? = null,
    val error: String? = null,
    /** True while the header speaker is auditioning this word. */
    val isPlayingWord: Boolean = false,
)

/**
 * Start/end ms for a single word clip. Prefer the segment's own end; if
 * missing or non-positive, use the next word's start so we still stop
 * before the following word. Null when the word has no usable timing.
 */
internal fun wordClipBounds(segments: List<Segment>, position: Int): Pair<Long, Long>? {
    val segment = segments.firstOrNull { it.position == position } ?: return null
    val startMs = segment.startMs.coerceAtLeast(0L)
    val ownEnd = segment.endMs.takeIf { it > startMs }
    val nextStart = segments
        .filter { it.position > position }
        .minByOrNull { it.position }
        ?.startMs
        ?.takeIf { it > startMs }
    val endMs = ownEnd ?: nextStart ?: return null
    return startMs to endMs
}

/**
 * Loads morphology + root concordance for the long-pressed word. The screen
 * is an ink-bleed overlay hosted by MainActivity — this ViewModel owns the
 * data for whatever word is currently revealed, and can audition that word
 * with the settings-selected reciter via the shared [PlayerController].
 */
class RootViewerViewModel(
    private val repository: QuranRepository,
    private val lexicon: LexiconRepository,
    private val settings: SettingsRepository,
    private val player: PlayerController,
) : ViewModel() {

    private val _ui = MutableStateFlow(RootViewerUiState())
    val ui: StateFlow<RootViewerUiState> = _ui.asStateFlow()

    private var wordClipJob: Job? = null

    fun open(surahId: Int, ayah: Int, wordPosition: Int) {
        stopWordAudition(pauseIfPlaying = true)
        _ui.value = RootViewerUiState(isLoading = true, surahId = surahId, ayah = ayah)
        viewModelScope.launch {
            val surahName = repository.surahs().firstOrNull { it.id == surahId }
                ?.nameTransliteration
                .orEmpty()
            val word = repository.wordAt(surahId, ayah, wordPosition)
            val morph = repository.wordMorphology(surahId, ayah, wordPosition)
            val summary = morph?.root?.takeIf { it.isNotBlank() }?.let { repository.rootSummary(it) }
            _ui.value = RootViewerUiState(
                isLoading = false,
                surahId = surahId,
                ayah = ayah,
                surahNameTransliteration = surahName,
                word = word,
                morphology = morph,
                occurrenceCount = summary?.occurrenceCount ?: 0,
                occurrences = summary?.occurrences.orEmpty(),
                lemmas = summary?.lemmas.orEmpty(),
                error = when {
                    word == null -> "Word not found"
                    else -> null
                },
            )
            // Extracting and opening the lexicon can take a beat on first use;
            // the analysis above must never wait on it.
            val root = morph?.root.orEmpty()
            if (root.isNotBlank()) {
                val entry = lexicon.entryFor(root)
                if (entry != null && _ui.value.morphology?.root == root) {
                    _ui.value = _ui.value.copy(lexicon = entry)
                }
            }
        }
    }

    /**
     * Plays **only** the open word with the currently selected reciter —
     * from its timing mark to its end, then pauses. Without a usable
     * segment the speaker is a no-op (never starts the rest of the ayah).
     */
    fun playCurrentWord() {
        val st = _ui.value
        val word = st.word ?: return
        if (st.isLoading) return
        viewModelScope.launch {
            val reciters = repository.reciters()
            if (reciters.isEmpty()) return@launch
            val reciterId = settings.settings.value.reciterId
            val reciter = reciters.firstOrNull { it.id == reciterId } ?: reciters.first()
            val segments = repository.timings(reciter.id, st.surahId)[st.ayah].orEmpty()
            val clip = wordClipBounds(segments, word.position) ?: return@launch
            startWordAudition(
                surahId = st.surahId,
                ayah = st.ayah,
                surahName = st.surahNameTransliteration,
                reciter = reciter,
                startMs = clip.first,
                endMs = clip.second,
            )
        }
    }

    private fun startWordAudition(
        surahId: Int,
        ayah: Int,
        surahName: String,
        reciter: Reciter,
        startMs: Long,
        endMs: Long,
    ) {
        stopWordAudition(pauseIfPlaying = true)
        player.clearRepeatRange()
        // Always load a one-ayah playlist so a late pause cannot spill into
        // the next ayah, and seek lands on this verse even if the reader had
        // a different surah loaded.
        loadAyahAndPlay(surahId, ayah, surahName, reciter, startMs)
        _ui.value = _ui.value.copy(isPlayingWord = true)
        wordClipJob = viewModelScope.launch {
            if (!awaitPlaybackIntoWord(surahId, ayah, startMs)) {
                _ui.value = _ui.value.copy(isPlayingWord = false)
                wordClipJob = null
                return@launch
            }
            // Hard-stop at the word boundary — never continue into the next word.
            while (true) {
                val now = player.state.value.nowPlaying
                if (now?.surahId != surahId || now.ayah != ayah) {
                    break
                }
                if (player.positionMs >= endMs) {
                    player.pause()
                    break
                }
                delay(WORD_CLIP_POLL_MS)
            }
            // Belt-and-suspenders: if we left the loop while still past the
            // end (or still playing), force a pause.
            if (player.state.value.isPlaying &&
                player.state.value.nowPlaying?.surahId == surahId &&
                player.state.value.nowPlaying?.ayah == ayah &&
                player.positionMs >= endMs
            ) {
                player.pause()
            }
            _ui.value = _ui.value.copy(isPlayingWord = false)
            wordClipJob = null
        }
    }

    /**
     * Wait until the playhead is on [ayah] at/after [startMs] and audio is
     * moving (or buffering). Returns false if the load never engages.
     */
    private suspend fun awaitPlaybackIntoWord(
        surahId: Int,
        ayah: Int,
        startMs: Long,
    ): Boolean {
        val deadline = System.nanoTime() + PLAY_READY_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val now = player.state.value.nowPlaying
            val onTarget = now?.surahId == surahId && now.ayah == ayah
            val engaged = player.state.value.isPlaying || player.state.value.isBuffering
            if (onTarget && engaged && player.positionMs >= startMs - SEEK_SLACK_MS) {
                return true
            }
            delay(WORD_CLIP_POLL_MS)
        }
        // Timed out — don't leave a runaway ayah playing.
        player.pause()
        return false
    }

    /** One-ayah playlist for this word — same shape as Timings Lab. */
    private fun loadAyahAndPlay(
        surahId: Int,
        ayah: Int,
        surahName: String,
        reciter: Reciter,
        startMs: Long,
    ) {
        player.playSurah(
            surahId = surahId,
            ayahCount = ayah,
            startAyah = ayah,
            reciter = reciter,
            surahName = surahName.ifBlank { surahId.toString() },
            startPositionMs = startMs,
            preserveRepeatRange = false,
            // A word audition is not a chapter opening — no basmalah item.
            includeBasmalahLeadIn = false,
        )
    }

    fun clear() {
        stopWordAudition(pauseIfPlaying = true)
        _ui.value = RootViewerUiState()
    }

    private fun stopWordAudition(pauseIfPlaying: Boolean) {
        val wasAuditioning = _ui.value.isPlayingWord
        wordClipJob?.cancel()
        wordClipJob = null
        if (pauseIfPlaying && wasAuditioning) player.pause()
        if (wasAuditioning) {
            _ui.value = _ui.value.copy(isPlayingWord = false)
        }
    }

    override fun onCleared() {
        stopWordAudition(pauseIfPlaying = true)
        super.onCleared()
    }

    private companion object {
        const val WORD_CLIP_POLL_MS = 16L
        const val PLAY_READY_TIMEOUT_MS = 4_000L
        /** Allow a small seek undershoot before treating the clip as started. */
        const val SEEK_SLACK_MS = 40L
    }
}
