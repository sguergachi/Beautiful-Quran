package com.beautifulquran.ornamentslab

import androidx.lifecycle.ViewModel
import com.beautifulquran.ui.theme.ornament.ChapterOrnament
import com.beautifulquran.ui.theme.ornament.CoverOrnament
import com.beautifulquran.ui.theme.ornament.generateChapterOrnament
import com.beautifulquran.ui.theme.ornament.generateCoverOrnament
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/** High-level traits decoded from a generated ornament — what a filter can search on. */
data class OrnamentTraits(
    val medallionFold: Int,
    val sealFold: Int,
    val borderSignature: String,
)

/** Derive traits from what the generator actually drew (mirrors the web Lab's `coverTraits`). */
private fun decodeTraits(o: CoverOrnament): OrnamentTraits {
    return OrnamentTraits(
        medallionFold = o.medallion.fold,
        sealFold = o.cornerSeal.fold,
        borderSignature = "${o.border.strokes.size}·${o.border.dots.size}",
    )
}

data class OrnamentsLabUiState(
    val seed: Int = 0,
    val cover: CoverOrnament = generateCoverOrnament(0),
    val chapter: ChapterOrnament = generateChapterOrnament(0),
    val traits: OrnamentTraits = decodeTraits(generateCoverOrnament(0)),
    val searchNote: String = "",
)

/**
 * Backs [OrnamentsLabScreen]: current seed → its full ornament + decoded
 * traits, a brute-force "design by trait" search, and the saved-seed list
 * ([OrnamentSeedStore]). Every preview is grown by the exact same generator
 * the app ships (`ui/theme/ornament/OrnamentGenerator.kt`), so what the Lab
 * shows is what the cover / surah header would actually draw for that seed.
 */
class OrnamentsLabViewModel(private val store: OrnamentSeedStore) : ViewModel() {

    val saved: StateFlow<List<SavedOrnamentSeed>> = store.saved

    private val _ui = MutableStateFlow(freshState(Random.nextInt()))
    val ui: StateFlow<OrnamentsLabUiState> = _ui

    private fun freshState(seed: Int): OrnamentsLabUiState {
        val cover = generateCoverOrnament(seed)
        return OrnamentsLabUiState(
            seed = seed,
            cover = cover,
            chapter = generateChapterOrnament(seed),
            traits = decodeTraits(cover),
        )
    }

    fun setSeed(seed: Int) {
        _ui.value = freshState(seed)
    }

    fun step(delta: Int) = setSeed(_ui.value.seed + delta)

    fun randomize() = setSeed(Random.nextInt())

    fun save(name: String) {
        store.save(name.ifBlank { "Seed ${_ui.value.seed}" }, _ui.value.seed)
    }

    fun remove(seed: Int) = store.remove(seed)

    /**
     * Scans forward from a random offset for a seed whose traits match every
     * non-null filter, up to 200k tries. A random start (rather than 0) means
     * repeated searches with the same filters turn up different seeds.
     */
    fun findSeed(fold: Int?) {
        val start = Random.nextInt()
        for (i in 0 until MAX_SEARCH_TRIES) {
            val candidate = start + i
            val traits = decodeTraits(generateCoverOrnament(candidate))
            if (fold != null && traits.medallionFold != fold) continue
            _ui.value = freshState(candidate).copy(searchNote = "Found after ${i + 1} tries")
            return
        }
        _ui.value = _ui.value.copy(searchNote = "No match in ${MAX_SEARCH_TRIES / 1000}k seeds — loosen the filters")
    }

    private companion object {
        const val MAX_SEARCH_TRIES = 200_000
    }
}
