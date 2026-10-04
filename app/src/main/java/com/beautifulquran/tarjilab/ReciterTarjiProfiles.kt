package com.beautifulquran.tarjilab

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.beautifulquran.ui.reader.InkEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-reciter detector knobs. Each reciter has a signature — voice, room,
 * and recording chain — so the lab stores one [TarjiLabKnobs] profile per
 * reciter id and writes it onto [InkEngine.tuning] when that reciter is live.
 */
class ReciterTarjiProfiles(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun knobsFor(reciterId: Int): TarjiLabKnobs =
        load().profiles[reciterId.toString()] ?: TarjiLabKnobs.fromTuning(InkEngine.tuning)

    fun save(reciterId: Int, knobs: TarjiLabKnobs) {
        val next = load().profiles.toMutableMap()
        next[reciterId.toString()] = knobs
        prefs.edit { putString(KEY, ReciterTarjiProfileBook.encode(ReciterTarjiProfileBook(next))) }
    }

    fun clear(reciterId: Int) {
        val next = load().profiles.toMutableMap()
        next.remove(reciterId.toString())
        if (next.isEmpty()) {
            prefs.edit { remove(KEY) }
        } else {
            prefs.edit { putString(KEY, ReciterTarjiProfileBook.encode(ReciterTarjiProfileBook(next))) }
        }
    }

    /** Apply this reciter's stored knobs onto the live detector.
     * Missing profiles leave the current Ink Lab snapshot alone. */
    fun applyToEngine(reciterId: Int) {
        val stored = load().profiles[reciterId.toString()] ?: return
        InkEngine.tuning = TarjiLabKnobs.applyToTuning(stored, InkEngine.tuning)
    }

    private fun load(): ReciterTarjiProfileBook {
        val book = ReciterTarjiProfileBook.decode(prefs.getString(KEY, null) ?: "{}")
        if (prefs.getBoolean(HANI_TUNING_APPLIED, false)) return book
        // Install the supplied capture's tuning once; later lab edits remain yours.
        val seeded = book.copy(profiles = book.profiles + ("7" to HANI_TUNING))
        prefs.edit {
            putString(KEY, ReciterTarjiProfileBook.encode(seeded))
            putBoolean(HANI_TUNING_APPLIED, true)
        }
        return seeded
    }

    private companion object {
        const val PREFS = "tarji_profiles"
        const val KEY = "book"
        const val HANI_TUNING_APPLIED = "hani_1_7_w9_tuning_applied"
    }
}

/** Exact settings exported in tarji_7_1_7_w9.json (Hani Ar-Rifai). */
internal val HANI_TUNING = TarjiLabKnobs(
    maxTremoloHz = 8f,
    minTremoloHz = 1.6f,
    holdMinMs = 656.6863f,
    minTremoloDepth = 0.13819668f,
    minPeriodicity = 0.38481894f,
    maxPitchDrift = 0.3f,
    attackMs = 50f,
    releaseMs = 1095.3691f,
    glintBrightness = 2f,
)

@Serializable
data class ReciterTarjiProfileBook(
    val profiles: Map<String, TarjiLabKnobs> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(book: ReciterTarjiProfileBook): String =
            json.encodeToString(serializer(), book)

        fun decode(raw: String): ReciterTarjiProfileBook =
            runCatching { json.decodeFromString(serializer(), raw) }
                .getOrDefault(ReciterTarjiProfileBook())
    }
}
