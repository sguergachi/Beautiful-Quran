package com.beautifulquran.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.domain.EnglishTypography

/**
 * English Scroll's single owner of where its ink is (docs/ENGLISH_PACING.md).
 *
 * **One rule:** in English Scroll the verse clock ([EnglishScrollClock])
 * decides both *how far* each gloss is inked and *which* gloss is the active
 * word. The per-word machinery the Arabic uses — a word gets ink only while its
 * Arabic word is active, and is released full when that word ends — must not
 * get a say of its own, or anything the clock does across Arabic word
 * boundaries (borrowed time, smoothing, a lead, a held pause) is silently cut
 * back to the Arabic word's own span. That is exactly how a levelling fix once
 * passed every clock test and changed nothing on the phone.
 *
 * So [rememberEnglishScrollPerformance] returns the clock *and* the active word
 * derived from it, and the verse block hands both to [rememberInkMotions]
 * together. Re-says (the orange repeat) and unconfirmed taps keep the voiced
 * word; before a verse's clock exists (its first frame) English falls back to
 * the voiced word's eased window ([englishRevealDurationMs]).
 */
internal class EnglishScrollPerformance(
    /** Null until the verse is being recited and its timings are known. */
    val clock: EnglishScrollClock?,
    /** The word to treat as active: the gloss being inked, else the voiced word. */
    val activeWord: ActiveWord?,
)

/** How long past its levelled end a gloss's window stays open (about 3σ of smoothing). */
internal const val ENGLISH_CLOCK_RESIDUAL_MS = 450L

/**
 * The voiced word re-addressed to the gloss the clock is inking ([index] in the
 * verse), timed across that gloss's [window]. Pure.
 */
internal fun englishClockActiveWord(voiced: ActiveWord, ayah: Ayah, index: Int, window: LongRange): ActiveWord {
    // The window runs on past its end so a finishing residual reads the clock
    // to full rather than being released a few letters short.
    val span = window.last - window.first + ENGLISH_CLOCK_RESIDUAL_MS
    val position = ayah.words[index].position
    return voiced.copy(
        wordPosition = position,
        startMs = window.first,
        durationMs = span,
        spokenMs = span,
        nextWordPosition = ayah.words.getOrNull(index + 1)?.position,
        highWater = position,
        repeatStart = position,
    )
}

@Composable
internal fun rememberEnglishScrollPerformance(ayah: Ayah, voiced: ActiveWord?): EnglishScrollPerformance {
    val clock = rememberEnglishScrollClock(ayah)
    val timeline = LocalHighlightTimeline.current
    val current = rememberUpdatedState(voiced)
    // Read every frame, but only a change of inked gloss recomposes.
    val index by remember(clock, ayah) {
        derivedStateOf {
            val word = current.value
            val frame = timeline.frame()
            if (clock == null || word == null || word.isRepeat || word.fromTap || frame == null ||
                frame.item.surah != ayah.surahId || frame.item.ayah != ayah.number) null
            else clock.wordAt(frame.inkMs)
        }
    }
    val at = index
    val window = at?.let { clock?.window(it) }
    val active = if (voiced == null || at == null || window == null || at !in ayah.words.indices) voiced
        else remember(voiced, at, window) { englishClockActiveWord(voiced, ayah, at, window) }
    return EnglishScrollPerformance(clock, active)
}

/**
 * The verse clock while [ayah] is the one being recited (null otherwise).
 * Rebuilt only when its timings, pauses or tuning change, never per frame.
 */
@Composable
private fun rememberEnglishScrollClock(ayah: Ayah): EnglishScrollClock? {
    val timeline = LocalHighlightTimeline.current
    val timings by remember(ayah.surahId, ayah.number) {
        derivedStateOf(referentialEqualityPolicy()) {
            timeline.sample.value?.takeIf { it.sample.item.surah == ayah.surahId && it.sample.item.ayah == ayah.number }
                ?.timing?.timings
        }
    }
    val tuning = InkEngine.tuning
    val glosses = remember(ayah) {
        EnglishTypography.lyricize(ayah.words.map { it.translation }, ayah.words.map { it.arabic })
    }
    // The reciter's heard pauses, fixed once this verse's voice begins (EnglishPauses).
    val pauses = EnglishPauses.aheadFor(ayah.surahId, ayah.number)
    return remember(ayah, timings, glosses, pauses, tuning.englishSmoothMs, tuning.englishLeadMs, tuning.englishEaseMs,
        tuning.englishTajweedTempo, tuning.tajweedPacing,
        tuning.holdMadd, tuning.holdGhunnah, tuning.holdWaqf, tuning.holdConnect) {
        val segments = timings?.segments ?: return@remember null
        val words = ayah.words
        val translations = words.map { it.translation }
        val arabic = words.map { it.arabic }
        EnglishScrollClock.of(
            glossLengths = IntArray(words.size) { glosses[it].length },
            positions = IntArray(words.size) { words[it].position },
            owner = { EnglishTypography.coalescedGlossOwnerIndex(translations, arabic, it) },
            segments = segments,
            sigmaMs = tuning.englishSmoothMs,
            pauses = pauses,
            leadMs = tuning.englishLeadMs,
            easeMs = tuning.englishEaseMs,
            // Tempo 0 (Ink Lab) is a steady pace inside each word; the clock stays.
            tempo = { index ->
                InkEngine.englishSegmentTempo(arabic[index], index == words.lastIndex, arabic.getOrNull(index - 1))
            },
        )
    }
}
