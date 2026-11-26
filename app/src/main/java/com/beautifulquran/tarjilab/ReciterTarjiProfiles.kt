package com.beautifulquran.tarjilab

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.beautifulquran.playback.Tarji
import com.beautifulquran.ui.reader.InkEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
        val due = SHIPPED_TARJI_TUNINGS.filterNot { prefs.getBoolean(it.appliedKey, false) }
        if (due.isEmpty()) return book
        // Install each supplied capture's tuning once; later lab edits remain yours.
        val seeded = book.copy(profiles = book.profiles + due.map { it.reciterId.toString() to it.knobs })
        prefs.edit {
            putString(KEY, ReciterTarjiProfileBook.encode(seeded))
            due.forEach { putBoolean(it.appliedKey, true) }
        }
        return seeded
    }

    private companion object {
        const val PREFS = "tarji_profiles"
        const val KEY = "book"
    }
}

/**
 * Exact settings exported in tarji_7_2_14_w16.json (Hani Ar-Rifai). They
 * replace the ones tuned on 1:7 w9 alone, and pulse harder on both captures.
 */
internal val HANI_TUNING = TarjiLabKnobs(
    maxTremoloHz = 8.3f,
    minTremoloHz = 2.6999998f,
    holdMinMs = 1200f,
    minTremoloDepth = 0.12308814f,
    minPeriodicity = 0.15000004f,
    maxPitchDrift = 0.3f,
    attackMs = 50f,
    releaseMs = 100f,
    glintBrightness = 2f,
)

/**
 * Exact settings exported in tarji_20_114_2_w2.json (Tawfeek As-Sawaigh,
 * ٱلنَّاسِ in 114:2), tuned under the Recording detector.
 */
internal val TAWFEEK_TUNING = TarjiLabKnobs(
    maxTremoloHz = 8.2f,
    minTremoloHz = 2.6999998f,
    holdMinMs = 1154.7185f,
    minTremoloDepth = 0.045600593f,
    minPeriodicity = 0.15000004f,
    minVolume = 0.01f,
    minDrama = 0.36118606f,
    maxPitchDrift = 0.3f,
    attackMs = 50f,
    releaseMs = 100f,
    glintBrightness = 2f,
)

/** A tuning a reciter ships with, installed once under [appliedKey]. A new
 * key re-installs it over that reciter's saved profile, once. */
internal class ShippedTarjiTuning(val reciterId: Int, val knobs: TarjiLabKnobs, val appliedKey: String)

internal val SHIPPED_TARJI_TUNINGS = listOf(
    ShippedTarjiTuning(7, HANI_TUNING, "hani_2_14_w16_tuning_applied"),
    ShippedTarjiTuning(20, TAWFEEK_TUNING, "tawfeek_114_2_w2_tuning_applied"),
)

@Serializable
data class ReciterTarjiProfileBook(
    val profiles: Map<String, TarjiLabKnobs> = emptyMap(),
    /** Books before version 1 used a stock 50% drama threshold. */
    val schema: Int = 1,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(book: ReciterTarjiProfileBook): String =
            json.encodeToString(serializer(), book)

        fun decode(raw: String): ReciterTarjiProfileBook = runCatching {
            val encoded = json.parseToJsonElement(raw).jsonObject
            val book = json.decodeFromJsonElement(serializer(), encoded)
            if ("schema" in encoded) book else book.copy(
                profiles = book.profiles.mapValues { (_, knobs) ->
                    if (knobs.minDrama == 0.5f) knobs.copy(minDrama = Tarji.MIN_DRAMA) else knobs
                },
            )
        }.getOrDefault(ReciterTarjiProfileBook())
    }
}
