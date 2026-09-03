package com.beautifulquran.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.beautifulquran.data.model.Ayah

/** Shared ink clocks for one ayah — used by [AyahBlock] and mushaf lines. */
internal data class AyahInkPack(
    val motions: List<InkMotion>,
    val recessCover: State<Float>,
    val markAlpha: State<Float>,
    val searchHitWash: RepeatWash,
    val searchHitWordPosition: Int? = null,
    /** A motionless mushaf ayah that still waits beneath the page recess. */
    val wholeAyahRecess: Boolean = false,
)

internal fun ayahRecessCoverTarget(dimmed: Boolean, enteringFromRecess: Boolean): Float =
    if (dimmed || enteringFromRecess) 1f - InkEngine.State.Upcoming.inkAlpha() else 0f

/**
 * The motion owner [AyahBlock] already uses: tajweed pacing, wasl handoff,
 * letter sweep, orange repeat. Mushaf lines must call this — never a second
 * wash clock — so the page and the scrolling reader are the same ink.
 */
@Composable
internal fun rememberAyahInkPack(
    ayah: Ayah,
    activeWord: ActiveWord?,
    playbackSpeed: Float,
    isActiveAyah: Boolean,
    dimmed: Boolean,
    flashWordPosition: Int? = null,
    /** True while the voice is running: the wet-ink glint dries on a pause. */
    wetInk: Boolean = true,
    /** Mushaf selection enters from the paper cover already on the ayah. */
    initiallyRecessed: Boolean = false,
): AyahInkPack {
    val sweepMs = InkEngine.sweepMs(activeWord, playbackSpeed)
    val repeatDwellMs = InkEngine.repeatDwellMs(activeWord, playbackSpeed)
    val activation = activeWord?.activation ?: 0L
    val activeWordStartMs = activeWord?.startMs ?: Long.MIN_VALUE
    val pacing = activeWord?.let { aw ->
        val idx = ayah.words.indexOfFirst { it.position == aw.wordPosition }
        ayah.words.getOrNull(idx)?.let { word ->
            InkEngine.pacing(
                arabic = word.arabic,
                activeWord = aw,
                isAyahFinal = word.position == ayah.words.lastOrNull()?.position,
                prevArabic = ayah.words.getOrNull(idx - 1)?.arabic,
            )
        }
    }
    val inks = ayah.words.map { word ->
        InkEngine.word(
            position = word.position,
            activeWord = activeWord,
            isActiveAyah = isActiveAyah,
            dimmed = dimmed,
        )
    }
    val activeIndex = inks.indexOfFirst { it.state == InkEngine.State.Active }
    val incomingConnection = if (activeIndex > 0) {
        InkEngine.connection(
            prevArabic = ayah.words[activeIndex - 1].arabic,
            arabic = ayah.words[activeIndex].arabic,
        )
    } else {
        null
    }
    val outgoingConnection = if (activeIndex in 0 until ayah.words.lastIndex) {
        InkEngine.connection(
            prevArabic = ayah.words[activeIndex].arabic,
            arabic = ayah.words[activeIndex + 1].arabic,
        )
    } else {
        null
    }
    val outgoingProgress = rememberWaslProgress(
        connection = outgoingConnection,
        sweepMs = sweepMs,
        identity = activeWord?.wordPosition,
        activation = activation,
    )
    val previousActive = remember { ActiveWordEntry(activeIndex, activation) }
    val carriedIncoming = remember(activeIndex, activation) {
        previousActive.index == activeIndex - 1 &&
            previousActive.activation == activation
    }
    val incomingHandoff = if (carriedIncoming) previousActive.outgoingHandoff else 0f
    SideEffect {
        previousActive.index = activeIndex
        previousActive.activation = activation
        previousActive.outgoingHandoff = outgoingProgress.atHandoff
    }
    val waslMainFeather = if (pacing != null) {
        InkEngine.pacedFeather()
    } else {
        InkEngine.tuning.washFeather
    }
    val activeRevealStart = if (carriedIncoming && incomingConnection != null) {
        waslWashProgress(
            windowProgress = incomingHandoff,
            endProgress = waslContinuationStart(
                prefixFraction = incomingConnection.prefixFraction,
                mainFeather = waslMainFeather,
            ),
        )
    } else {
        0f
    }
    val fullWaslWindow = remember { mutableStateOf(1f) }
    val waslPrefixes = ayah.words.indices.map { index ->
        when {
            index == activeIndex && carriedIncoming && incomingConnection != null ->
                WaslPrefix(
                    windowProgress = fullWaslWindow,
                    endProgress = activeRevealStart,
                    feather = waslMainFeather,
                )
            index == activeIndex + 1 && outgoingConnection != null ->
                WaslPrefix(
                    windowProgress = outgoingProgress.value,
                    endProgress = waslContinuationStart(
                        prefixFraction = outgoingConnection.prefixFraction,
                        mainFeather = waslMainFeather,
                    ),
                    feather = waslMainFeather,
                )
            else -> null
        }
    }
    val motions = rememberInkMotions(
        words = ayah.words,
        inks = inks,
        activeSweepMs = sweepMs,
        activeRepeatDwellMs = repeatDwellMs,
        pacing = pacing,
        activeRevealStart = activeRevealStart,
        waslPrefixes = waslPrefixes,
        activation = activation,
        activeWordStartMs = activeWordStartMs,
        sequentialSweeps = false,
        animateLyricInk = false,
        wetInk = wetInk,
    )
    val enteringFromRecess = remember(initiallyRecessed) {
        mutableStateOf(initiallyRecessed)
    }
    LaunchedEffect(initiallyRecessed) {
        enteringFromRecess.value = false
    }
    val recessCover = animateFloatAsState(
        targetValue = ayahRecessCoverTarget(dimmed, enteringFromRecess.value),
        animationSpec = tween(InkEngine.tuning.recessMs, easing = FastOutSlowInEasing),
        label = "mushafRecessCover",
    )
    return AyahInkPack(
        motions = motions,
        recessCover = recessCover,
        markAlpha = rememberAyahMarkAlpha(focused = !dimmed && !enteringFromRecess.value),
        searchHitWash = rememberSearchHitWash(flashWordPosition),
        searchHitWordPosition = flashWordPosition,
    )
}
