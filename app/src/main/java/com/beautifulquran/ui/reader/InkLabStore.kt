package com.beautifulquran.ui.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.beautifulquran.domain.TajweedPacing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-device store for developer-mode Ink Lab numbers — [InkEngine.Tuning]
 * plus the Highlight-tab sync knobs. Survives process death so multi-session
 * auditioning does not reset to shipped defaults. The Focus freeze stays
 * out of this store (session-only by design).
 *
 * One SharedPreferences key holding a JSON object. Missing fields on load
 * fall back to shipped defaults so new knobs pick up defaults after an
 * upgrade without wiping older saves.
 */
class InkLabStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Null when the user has never edited (or has Reset). */
    fun load(): InkLabSnapshot? {
        val raw = prefs.getString(KEY, null) ?: return null
        return InkLabSnapshot.decode(raw)
    }

    fun save(snapshot: InkLabSnapshot) {
        prefs.edit { putString(KEY, InkLabSnapshot.encode(snapshot)) }
    }

    fun clear() {
        prefs.edit { remove(KEY) }
    }

    private companion object {
        const val PREFS = "ink_lab"
        const val KEY = "snapshot"
    }
}

/**
 * Wire format for [InkLabStore]. Field defaults match [InkEngine.Tuning] and
 * highlight-sync shipped constants so incomplete JSON still loads cleanly.
 */
@Serializable
data class InkLabSnapshot(
    val schema: Int = SCHEMA,
    val upcomingAlpha: Float = 0.2661f,
    val inkFadeMs: Int = 400,
    val ayahMarkFadeMs: Int = 400,
    val recessMs: Int = 400,
    val minSweepMs: Int = 140,
    val maxSweepMs: Int = 8_000,
    val repeatSweepMs: Int = 450,
    val repeatFadeOutMs: Int = 900,
    val repeatInkAlpha: Float = 1f,
    val glintFadeMs: Int = 1_000,
    val glintTintAlpha: Float = 0.62f,
    val glintGlowAlpha: Float = 0.49f,
    val glintGlowRadius: Float = 10f,
    val washFeather: Float = 1.6f,
    val sweepEaseX1: Float = 0.3f,
    val sweepEaseY1: Float = 0.24f,
    val sweepEaseX2: Float = 0.7f,
    val sweepEaseY2: Float = 0.78f,
    val tajweedPacing: Boolean = true,
    val pacedFeather: Float = 1.1857f,
    val holdMadd: Boolean = true,
    val holdGhunnah: Boolean = true,
    val holdWaqf: Boolean = true,
    val holdConnect: Boolean = true,
    val waslPrefixMs: Int = 480,
    val waslHandoff: Float = TajweedPacing.DEFAULT_WASL_HANDOFF,
    val cruiseCap: Float = 2f,
    val waqfShare: Float = 0.5932f,
    val waqfLengthScale: Float = 1f,
    val holdCreep: Float = 0.1076f,
    val highlightLeadMs: Int = InkEngine.DEFAULT_HIGHLIGHT_LEAD_MS,
    val fadeLeadMs: Int = InkEngine.DEFAULT_FADE_LEAD_MS,
    /** Null means auto route preset; omitted on old saves → null. */
    val outputLatencyOverrideMs: Int? = null,
) {
    fun toTuning(): InkEngine.Tuning = InkEngine.Tuning(
        upcomingAlpha = upcomingAlpha,
        inkFadeMs = inkFadeMs,
        ayahMarkFadeMs = ayahMarkFadeMs,
        recessMs = recessMs,
        minSweepMs = minSweepMs,
        maxSweepMs = maxSweepMs,
        repeatSweepMs = repeatSweepMs,
        repeatFadeOutMs = repeatFadeOutMs,
        repeatInkAlpha = repeatInkAlpha,
        glintFadeMs = glintFadeMs,
        glintTintAlpha = glintTintAlpha,
        glintGlowAlpha = glintGlowAlpha,
        glintGlowRadius = glintGlowRadius,
        washFeather = washFeather,
        sweepEaseX1 = sweepEaseX1,
        sweepEaseY1 = sweepEaseY1,
        sweepEaseX2 = sweepEaseX2,
        sweepEaseY2 = sweepEaseY2,
        tajweedPacing = tajweedPacing,
        pacedFeather = pacedFeather,
        holdMadd = holdMadd,
        holdGhunnah = holdGhunnah,
        holdWaqf = holdWaqf,
        holdConnect = holdConnect,
        waslPrefixMs = waslPrefixMs,
        waslHandoff = waslHandoff,
        cruiseCap = cruiseCap,
        waqfShare = waqfShare,
        waqfLengthScale = waqfLengthScale,
        holdCreep = holdCreep,
    )

    companion object {
        const val SCHEMA = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun capture(
            tuning: InkEngine.Tuning = InkEngine.tuning,
            highlightLeadMs: Int = InkEngine.highlightLeadMs,
            fadeLeadMs: Int = InkEngine.fadeLeadMs,
            outputLatencyOverrideMs: Int? = InkEngine.outputLatencyOverrideMs,
        ): InkLabSnapshot = InkLabSnapshot(
            upcomingAlpha = tuning.upcomingAlpha,
            inkFadeMs = tuning.inkFadeMs,
            ayahMarkFadeMs = tuning.ayahMarkFadeMs,
            recessMs = tuning.recessMs,
            minSweepMs = tuning.minSweepMs,
            maxSweepMs = tuning.maxSweepMs,
            repeatSweepMs = tuning.repeatSweepMs,
            repeatFadeOutMs = tuning.repeatFadeOutMs,
            repeatInkAlpha = tuning.repeatInkAlpha,
            glintFadeMs = tuning.glintFadeMs,
            glintTintAlpha = tuning.glintTintAlpha,
            glintGlowAlpha = tuning.glintGlowAlpha,
            glintGlowRadius = tuning.glintGlowRadius,
            washFeather = tuning.washFeather,
            sweepEaseX1 = tuning.sweepEaseX1,
            sweepEaseY1 = tuning.sweepEaseY1,
            sweepEaseX2 = tuning.sweepEaseX2,
            sweepEaseY2 = tuning.sweepEaseY2,
            tajweedPacing = tuning.tajweedPacing,
            pacedFeather = tuning.pacedFeather,
            holdMadd = tuning.holdMadd,
            holdGhunnah = tuning.holdGhunnah,
            holdWaqf = tuning.holdWaqf,
            holdConnect = tuning.holdConnect,
            waslPrefixMs = tuning.waslPrefixMs,
            waslHandoff = tuning.waslHandoff,
            cruiseCap = tuning.cruiseCap,
            waqfShare = tuning.waqfShare,
            waqfLengthScale = tuning.waqfLengthScale,
            holdCreep = tuning.holdCreep,
            highlightLeadMs = highlightLeadMs,
            fadeLeadMs = fadeLeadMs,
            outputLatencyOverrideMs = outputLatencyOverrideMs,
        )

        fun encode(snapshot: InkLabSnapshot): String =
            json.encodeToString(serializer(), snapshot)

        fun decode(raw: String): InkLabSnapshot? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
    }
}
