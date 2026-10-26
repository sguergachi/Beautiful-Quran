package com.beautifulquran.ui.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.beautifulquran.domain.TajweedPacing
import com.beautifulquran.playback.Tarji
import com.beautifulquran.ui.theme.ContextualGuideTuning
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
    val inkFadeMs: Int = InkEngine.CHROME_RECEDE_MS,
    val ayahMarkFadeMs: Int = InkEngine.CHROME_RECEDE_MS,
    val recessMs: Int = InkEngine.CHROME_RECEDE_MS,
    val minSweepMs: Int = 140,
    val maxSweepMs: Int = 8_000,
    val repeatSweepMs: Int = 450,
    val repeatFadeOutMs: Int = 1_800,
    val repeatInkAlpha: Float = 1f,
    val glintFadeMs: Int = 1_000,
    val glintTintAlpha: Float = 0.88f,
    val glintGlowAlpha: Float = 0.4514f,
    val glintGlowRadius: Float = 5f,
    val glintBrightness: Float = 1f,
    val glintBloomAlpha: Float = 0.75f,
    val glintVeilAlpha: Float = 0.6f,
    val glintVeilWarmth: Float = 0.5195f,
    val tarjiLightRise: Float = 0.3f,
    val tarjiLightFall: Float = 0.1101f,
    val glintRestLight: Float = 0.8858f,
    val tarjiLightSmoothMs: Float = 139f,
    val tarjiGlowGain: Float = 8.6696f,
    val washFeather: Float = 1.6f,
    val sweepEaseX1: Float = 0.3f,
    val sweepEaseY1: Float = 0.24f,
    val sweepEaseX2: Float = 0.7f,
    val sweepEaseY2: Float = 0.78f,
    val tajweedPacing: Boolean = true,
    val pacedFeather: Float = 1.1092f,
    val holdMadd: Boolean = true,
    val holdGhunnah: Boolean = true,
    val holdWaqf: Boolean = true,
    val holdConnect: Boolean = true,
    val waslPrefixMs: Int = 120,
    val waslHandoff: Float = TajweedPacing.DEFAULT_WASL_HANDOFF,
    val cruiseCap: Float = 1.4185f,
    val waqfShare: Float = 0.5932f,
    val waqfLengthScale: Float = 1f,
    val holdCreep: Float = 0.3f,
    val glintResonance: Boolean = true,
    val glintResonanceDepth: Float = InkEngine.GLINT_RESONANCE_DEPTH,
    val glintResonanceMaxHz: Float = InkEngine.GLINT_RESONANCE_MAX_HZ,
    val tarjiMinHz: Float = Tarji.MIN_TREMOLO_HZ,
    val tarjiHoldMinMs: Float = Tarji.HOLD_MIN_MS.toFloat(),
    val tarjiMinDepth: Float = Tarji.MIN_TREMOLO_DEPTH,
    val tarjiMinPeriodicity: Float = Tarji.MIN_PERIODICITY,
    val tarjiMinVolume: Float = Tarji.MIN_VOLUME,
    val tarjiMinDrama: Float = Tarji.MIN_DRAMA,
    val tarjiPitchDrift: Float = Tarji.MAX_PITCH_DRIFT,
    val tarjiAttackMs: Float = Tarji.ATTACK_MS,
    val tarjiReleaseMs: Float = Tarji.RELEASE_MS,
    val tarjiEarDelayMs: Float = 0f,
    val paperInk: Boolean = true,
    val paperInkPooling: Float = 0.28f,
    val paperInkReflection: Float = 0.5f,
    val paperInkLeadFade: Float = 0.5f,
    val paperInkFlowing: Boolean = false,
    val guideBodyEdge: Float = 0.5f,
    val guideFeatherWidth: Float = 0.2819f,
    val guideFadeSoftness: Float = 1.3329f,
    val guideBlurRadiusDp: Float = 24f,
    val guideBlurStrength: Float = 1f,
    val guideVellumGrain: Float = 0.0297f,
    val guideVerticalTaper: Float = 0.24f,
    val highlightLeadMs: Int = InkEngine.DEFAULT_HIGHLIGHT_LEAD_MS,
    val fadeLeadMs: Int = InkEngine.DEFAULT_FADE_LEAD_MS,
    val scrollLeadMs: Int = InkEngine.DEFAULT_SCROLL_LEAD_MS,
    /** Null means the untrimmed presentation clock; omitted on old saves → null. */
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
        glintBrightness = glintBrightness,
        glintBloomAlpha = glintBloomAlpha,
        glintVeilAlpha = glintVeilAlpha,
        glintVeilWarmth = glintVeilWarmth,
        tarjiLightRise = tarjiLightRise,
        glintRestLight = glintRestLight,
        tarjiLightFall = tarjiLightFall,
        tarjiLightSmoothMs = tarjiLightSmoothMs,
        tarjiGlowGain = tarjiGlowGain,
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
        glintResonance = glintResonance,
        glintResonanceDepth = glintResonanceDepth,
        glintResonanceMaxHz = glintResonanceMaxHz.coerceIn(
            Tarji.MIN_TREMOLO_HZ,
            Tarji.MAX_MEASURABLE_TREMOLO_HZ,
        ),
        tarjiMinHz = tarjiMinHz,
        tarjiHoldMinMs = tarjiHoldMinMs,
        tarjiMinDepth = tarjiMinDepth,
        tarjiMinPeriodicity = tarjiMinPeriodicity,
        tarjiMinVolume = tarjiMinVolume,
        tarjiMinDrama = tarjiMinDrama,
        tarjiPitchDrift = tarjiPitchDrift,
        tarjiAttackMs = tarjiAttackMs,
        tarjiReleaseMs = tarjiReleaseMs,
        tarjiEarDelayMs = tarjiEarDelayMs,
        paperInk = paperInk,
        paperInkPooling = paperInkPooling,
        paperInkReflection = paperInkReflection,
        paperInkLeadFade = paperInkLeadFade,
        paperInkFlowing = paperInkFlowing,
    )

    fun toContextualGuideTuning(): ContextualGuideTuning = ContextualGuideTuning(
        bodyEdge = guideBodyEdge,
        featherWidth = guideFeatherWidth,
        fadeSoftness = guideFadeSoftness,
        blurRadiusDp = guideBlurRadiusDp,
        blurStrength = guideBlurStrength,
        vellumGrain = guideVellumGrain,
        verticalTaper = guideVerticalTaper,
    )

    companion object {
        /**
         * 2: the glow's numbers changed meaning — blur in dp in every reading
         * mode, swings in linear light. A save from before is dropped back to
         * shipped for those fields ([decode]); the rest of it is kept.
         * 3: adopt zero scroll lead in existing saves; later auditions persist.
         * 4: retune that scroll lead to 100 ms, preserving the other knobs.
         * 5: give the repeat wash a slower 1.8 s release without resetting the camera.
         * 6: match old default verse fades to chrome; preserve custom fade auditions.
         * 7: adopt 0.28 pooling for old stock profiles; retire the softness dial.
         * 8: raise the old stock recording-drama threshold from 50% to 53%.
         */
        const val SCHEMA = 8

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun capture(
            tuning: InkEngine.Tuning = InkEngine.tuning,
            guide: ContextualGuideTuning = InkEngine.contextualGuideTuning,
            highlightLeadMs: Int = InkEngine.highlightLeadMs,
            fadeLeadMs: Int = InkEngine.fadeLeadMs,
            scrollLeadMs: Int = InkEngine.scrollLeadMs,
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
            glintBrightness = tuning.glintBrightness,
            glintBloomAlpha = tuning.glintBloomAlpha,
            glintVeilAlpha = tuning.glintVeilAlpha,
            glintVeilWarmth = tuning.glintVeilWarmth,
            tarjiLightRise = tuning.tarjiLightRise,
            glintRestLight = tuning.glintRestLight,
            tarjiLightFall = tuning.tarjiLightFall,
            tarjiLightSmoothMs = tuning.tarjiLightSmoothMs,
            tarjiGlowGain = tuning.tarjiGlowGain,
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
            glintResonance = tuning.glintResonance,
            glintResonanceDepth = tuning.glintResonanceDepth,
            glintResonanceMaxHz = tuning.glintResonanceMaxHz,
            tarjiMinHz = tuning.tarjiMinHz,
            tarjiHoldMinMs = tuning.tarjiHoldMinMs,
            tarjiMinDepth = tuning.tarjiMinDepth,
            tarjiMinPeriodicity = tuning.tarjiMinPeriodicity,
            tarjiMinVolume = tuning.tarjiMinVolume,
            tarjiMinDrama = tuning.tarjiMinDrama,
            tarjiPitchDrift = tuning.tarjiPitchDrift,
            tarjiAttackMs = tuning.tarjiAttackMs,
            tarjiReleaseMs = tuning.tarjiReleaseMs,
            tarjiEarDelayMs = tuning.tarjiEarDelayMs,
            paperInk = tuning.paperInk,
            paperInkPooling = tuning.paperInkPooling,
            paperInkReflection = tuning.paperInkReflection,
            paperInkLeadFade = tuning.paperInkLeadFade,
            paperInkFlowing = tuning.paperInkFlowing,
            guideBodyEdge = guide.bodyEdge,
            guideFeatherWidth = guide.featherWidth,
            guideFadeSoftness = guide.fadeSoftness,
            guideBlurRadiusDp = guide.blurRadiusDp,
            guideBlurStrength = guide.blurStrength,
            guideVellumGrain = guide.vellumGrain,
            guideVerticalTaper = guide.verticalTaper,
            highlightLeadMs = highlightLeadMs,
            fadeLeadMs = fadeLeadMs,
            scrollLeadMs = scrollLeadMs,
            outputLatencyOverrideMs = outputLatencyOverrideMs,
        )

        fun encode(snapshot: InkLabSnapshot): String =
            json.encodeToString(serializer(), snapshot)

        fun decode(raw: String): InkLabSnapshot? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()?.let { saved ->
                val shipped = InkLabSnapshot()
                val glow = if (saved.schema >= 2) saved else saved.copy(
                    glintGlowAlpha = shipped.glintGlowAlpha,
                    glintGlowRadius = shipped.glintGlowRadius,
                    glintBloomAlpha = shipped.glintBloomAlpha,
                    glintVeilAlpha = shipped.glintVeilAlpha,
                    tarjiLightRise = shipped.tarjiLightRise,
                    tarjiLightFall = shipped.tarjiLightFall,
                    tarjiGlowGain = shipped.tarjiGlowGain,
                )
                val camera = if (saved.schema >= 4) glow else glow.copy(
                    scrollLeadMs = shipped.scrollLeadMs,
                )
                val repeat = if (saved.schema >= 5) camera else camera.copy(
                    repeatFadeOutMs = shipped.repeatFadeOutMs,
                )
                val fades = if (saved.schema >= 6) repeat else repeat.copy(
                    inkFadeMs = repeat.inkFadeMs.takeUnless { it == 400 } ?: shipped.inkFadeMs,
                    ayahMarkFadeMs = repeat.ayahMarkFadeMs.takeUnless { it == 400 } ?: shipped.ayahMarkFadeMs,
                    recessMs = repeat.recessMs.takeUnless { it == 400 } ?: shipped.recessMs,
                )
                val pooling = if (saved.schema >= 7) fades else fades.copy(
                    paperInkPooling = fades.paperInkPooling.takeUnless { it == 0.25f }
                        ?: shipped.paperInkPooling,
                )
                if (saved.schema >= SCHEMA) pooling else pooling.copy(
                    schema = SCHEMA,
                    tarjiMinDrama = pooling.tarjiMinDrama.takeUnless { it == 0.5f }
                        ?: shipped.tarjiMinDrama,
                )
            }
    }
}
