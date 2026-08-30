package com.beautifulquran.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.data.SettingsRepository
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahWordSearchSection
import com.beautifulquran.data.model.WordSearchHit
import com.beautifulquran.domain.isWordSearchQuery
import com.beautifulquran.domain.sectionWordSearchHits
import com.beautifulquran.playback.PlayerController
import com.beautifulquran.playback.PlayerUiState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class ContinueTarget(val surah: Surah, val ayah: Int)

/** Now-playing summary for the cover sheet's floating transport. */
data class FloatingPlaybackTarget(
    val surah: Surah,
    val ayah: Int,
)

data class HomeUiState(
    val query: String = "",
    val surahs: List<Surah> = emptyList(),
    /** Every surah, unfiltered — the jump dials scroll across the whole book. */
    val allSurahs: List<Surah> = emptyList(),
    /** Reading place parked on the previous visit, kept visible as the green
     * marker even while the chapter list is filtered. */
    val continueTarget: ContinueTarget? = null,
    /** When the query is a `surah:ayah` reference, the ayah to open the matched surah at. */
    val ayahTarget: Int? = null,
    /** Verse currently loaded in the player — drives the floating transport. */
    val floatingPlayback: FloatingPlaybackTarget? = null,
    val playerState: PlayerUiState = PlayerUiState(),
    val reciterName: String = "",
    /** Quran-wide word hits, sectioned by surah (truncated until expanded). */
    val wordSections: List<SurahWordSearchSection> = emptyList(),
    /** True while a debounced word search is in flight. */
    val wordSearchLoading: Boolean = false,
)

/** A `surah:ayah` reference parsed from a search query, e.g. `2:255`. Ayah is null for `2:`. */
internal data class AyahReference(val surah: Int, val ayah: Int?)

/** Matches `aa:bb` or `aa:` (surah number, optional ayah number), ignoring surrounding space. */
private val ayahReferenceRegex = Regex("""^\s*(\d+)\s*:\s*(\d+)?\s*$""")

internal fun parseAyahReference(query: String): AyahReference? {
    val match = ayahReferenceRegex.matchEntire(query) ?: return null
    // toIntOrNull, not toInt: the regex matches digit runs of any length, so a
    // long number (e.g. "99999999999:1") overflows Int and would otherwise
    // throw NumberFormatException from the search flow. An unparseable number
    // is simply not a reference.
    val surah = match.groupValues[1].toIntOrNull() ?: return null
    val ayahText = match.groupValues[2]
    val ayah = if (ayahText.isEmpty()) null else (ayahText.toIntOrNull() ?: return null)
    return AyahReference(surah = surah, ayah = ayah)
}

/** What a search query resolves to: the surahs to list, and — when the query
 * was a valid `surah:ayah` reference — the ayah to open the match at. */
internal data class SurahFilterResult(val surahs: List<Surah>, val ayahTarget: Int? = null)

/** The home search: blank shows everything; a `surah:ayah` reference resolves
 * to that one surah (empty when out of range); anything else matches names
 * (transliteration/translation case-insensitively, Arabic exactly) or the
 * bare surah number. */
internal fun filterSurahs(surahs: List<Surah>, query: String): SurahFilterResult {
    val reference = parseAyahReference(query)
    return when {
        query.isBlank() -> SurahFilterResult(surahs)
        reference != null -> {
            val surah = surahs.firstOrNull { it.id == reference.surah }
            val ayahInRange = reference.ayah == null ||
                reference.ayah in 1..(surah?.ayahCount ?: 0)
            if (surah != null && ayahInRange) {
                SurahFilterResult(listOf(surah), reference.ayah)
            } else {
                SurahFilterResult(emptyList())
            }
        }
        else -> {
            // Match on the trimmed query so stray leading/trailing whitespace
            // (common from keyboard autocomplete) never hides an otherwise
            // matching surah name or number.
            val trimmed = query.trim()
            SurahFilterResult(
                surahs.filter {
                    it.nameTransliteration.contains(trimmed, ignoreCase = true) ||
                        it.nameTranslation.contains(trimmed, ignoreCase = true) ||
                        it.nameArabic.contains(trimmed) ||
                        it.id.toString() == trimmed
                },
            )
        }
    }
}

/** Word search runs for typed queries that are not `surah:ayah` jumps. */
internal fun shouldRunWordSearch(query: String): Boolean {
    if (!isWordSearchQuery(query)) return false
    return parseAyahReference(query.trim()) == null
}

@OptIn(FlowPreview::class)
class HomeViewModel(
    private val repository: QuranRepository,
    private val settings: SettingsRepository,
    private val player: PlayerController,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val allSurahs = MutableStateFlow<List<Surah>>(emptyList())
    private val reciterNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    private val wordHits = MutableStateFlow<List<WordSearchHit>>(emptyList())
    private val expandedSurahIds = MutableStateFlow<Set<Int>>(emptySet())
    private val wordSearchLoading = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> =
        combine(
            combine(query, allSurahs, settings.settings, player.state, reciterNames) {
                    q, surahs, prefs, playerState, names ->
                HomeCombineBase(
                    query = q,
                    surahs = surahs,
                    lastSurah = prefs.lastSurah,
                    lastAyah = prefs.lastAyah,
                    reciterId = prefs.reciterId,
                    playerState = playerState,
                    names = names,
                )
            },
            wordHits,
            expandedSurahIds,
            wordSearchLoading,
        ) { base, hits, expanded, loading ->
            val (filtered, ayahTarget) = filterSurahs(base.surahs, base.query)
            val continueTarget = base.surahs.firstOrNull { it.id == base.lastSurah }
                ?.let { ContinueTarget(it, base.lastAyah) }
            val nowPlaying = base.playerState.nowPlaying
            val floating = nowPlaying?.let { np ->
                // The basmalah lead-in reports ayah 0; the float reads (and
                // opens the reader at) the chapter opening, ayah 1.
                base.surahs.firstOrNull { it.id == np.surahId }
                    ?.let { FloatingPlaybackTarget(it, np.ayah.coerceAtLeast(1)) }
            }
            val reciterName = nowPlaying?.let { base.names[it.reciterId] }
                ?: base.names[base.reciterId].orEmpty()
            HomeUiState(
                query = base.query,
                surahs = filtered,
                allSurahs = base.surahs,
                continueTarget = continueTarget,
                ayahTarget = ayahTarget,
                floatingPlayback = floating,
                playerState = base.playerState,
                reciterName = reciterName,
                wordSections = sectionWordSearchHits(hits, expanded),
                wordSearchLoading = loading,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            allSurahs.value = repository.surahs()
            reciterNames.value = repository.reciters().associate { it.id to it.name }
        }
        viewModelScope.launch {
            query
                .debounce(220)
                .collectLatest { q ->
                    if (!shouldRunWordSearch(q)) {
                        wordHits.value = emptyList()
                        wordSearchLoading.value = false
                        return@collectLatest
                    }
                    wordSearchLoading.value = true
                    wordHits.value = repository.searchWords(q)
                    wordSearchLoading.value = false
                }
        }
    }

    fun onQueryChange(value: String) {
        if (value != query.value) {
            expandedSurahIds.value = emptySet()
        }
        query.value = value
        // Show a quiet loading cue as soon as a word-searchable query is typed,
        // before debounce fires — avoids a blank gap under the surah matches.
        wordSearchLoading.value = shouldRunWordSearch(value)
        if (!shouldRunWordSearch(value)) {
            wordHits.value = emptyList()
        }
    }

    /** Expands or collapses the truncated hit list for one surah section. */
    fun toggleWordSearchSection(surahId: Int) {
        expandedSurahIds.update { current ->
            if (surahId in current) current - surahId else current + surahId
        }
    }

    fun togglePlayPause() = player.togglePlayPause()

    fun fastForward() = player.next()

    fun fastBackward() = player.previous()

    fun cycleSpeed() {
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f)
        val current = player.state.value.speed
        val idx = speeds.indexOfFirst { abs(it - current) < 0.01f }
        player.setSpeed(speeds[(idx + 1).mod(speeds.size)])
    }

    /** Cycles Media3 repeat modes — the cover sheet has no range dialog. */
    fun cycleRepeatMode() {
        val next = when (player.state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.setRepeatMode(next)
    }

    /** Dismisses the cover float and ends the playback session. */
    fun dismissFloatingPlayback() = player.stop()
}

/**
 * Intermediate combine payload so the outer combine can stay within the
 * five-flow overload while still carrying settings fields we need.
 */
private data class HomeCombineBase(
    val query: String,
    val surahs: List<Surah>,
    val lastSurah: Int,
    val lastAyah: Int,
    val reciterId: Int,
    val playerState: PlayerUiState,
    val names: Map<Int, String>,
)
