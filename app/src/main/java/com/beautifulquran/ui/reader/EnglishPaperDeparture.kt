package com.beautifulquran.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

private data class EnglishInkPerformance(val ayah: Int, val generation: Long, val reciter: Int?)
private class EnglishInkPrevious(
    var performance: EnglishInkPerformance? = null,
    var departure: EnglishPaperDeparture? = null,
)

/** Cosmetic release of the old printed prefix; it never advances the shared audio clock. */
internal class EnglishPaperDeparture(
    private val deposits: Map<Int, PaperInkDeposit>,
    private val remaining: State<Float>,
    private val initialOpacity: Map<Int, Float> = emptyMap(),
) {
    private val departing = HashSet<Int>()

    fun opacity(at: Int): Float = remaining.value * (initialOpacity[at] ?: 1f)

    fun word(at: Int, progress: Float): PaperInkDeposit? =
        englishDepartedWord(deposits[at], progress, opacity(at)).also {
            if (it != null) departing += at else departing -= at
        }

    /** A subsequent seek inherits the coat as visible now, rather than rearming full ink. */
    fun snapshotOpacity(at: Int): Float = if (at in departing) opacity(at) else 1f
}

/** A word inside a long phrase can rewind even when that phrase's first Arabic owner stays read. */
@Composable
internal fun rememberEnglishPaperDeparture(
    word: State<ActiveWord?>,
    deposits: Map<Int, PaperInkDeposit>,
): EnglishPaperDeparture {
    val performance by remember(word) {
        derivedStateOf { word.value?.let { EnglishInkPerformance(it.ayah, it.playbackActivation, it.reciterId) } }
    }
    val previous = remember(deposits) { EnglishInkPrevious() }
    val departure = remember(deposits, performance) {
        val retained = if (previous.performance?.ayah == performance?.ayah) deposits.filterKeys { it >= 0 } else emptyMap()
        val opacity = retained.keys.associateWith { previous.departure?.snapshotOpacity(it) ?: 1f }
        val animated = Animatable(if (retained.isEmpty()) 0f else 1f)
        val release = EnglishPaperDeparture(retained, animated.asState(), opacity)
        previous.performance = performance
        previous.departure = release
        release to animated
    }
    LaunchedEffect(departure) {
        departure.second.animateTo(0f, tween(850, easing = FastOutSlowInEasing))
    }
    return departure.first
}

/** Re-entered words use the new wash; only untouched words ahead of it release their old coat. */
internal fun englishDepartedWord(old: PaperInkDeposit?, progress: Float, remaining: Float): PaperInkDeposit? =
    old?.takeIf { progress < 0f && it.progress >= 0f && remaining > 0f }
