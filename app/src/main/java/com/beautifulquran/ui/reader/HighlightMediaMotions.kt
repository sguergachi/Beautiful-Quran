package com.beautifulquran.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.domain.TajweedPacing
import com.beautifulquran.domain.HighlightWash
import com.beautifulquran.domain.HighlightWashState
import com.beautifulquran.domain.HighlightWashWindow

private data class MediaEntry(
    val wash: HighlightWashState = HighlightWashState(),
    val pacing: TajweedPacing.Curve? = null,
    val feather: Float? = null,
    val revealStart: Float = 0f,
)
private class MediaHistory(var entry: MediaEntry = MediaEntry())

/** Capture visual pacing once; transport and every residual use the shared media timeline. */
@Composable
private fun rememberMediaEntry(
    active: Boolean,
    retain: Boolean,
    activation: Long,
    window: HighlightWashWindow?,
    provisional: Boolean,
    pacing: TajweedPacing.Curve?,
    revealStart: Float = 0f,
    repeatOccurrence: Boolean = false,
): State<MediaEntry> {
    val timeline = LocalHighlightTimeline.current
    val history = remember { MediaHistory() }
    val prior = history.entry
    var next = HighlightWash.transition(prior.wash, active, retain, activation, window, provisional, repeatOccurrence)
    // Release completed residuals at a word boundary without subscribing composition to frames.
    if (!active && next.window != null && !next.provisional &&
        Snapshot.withoutReadObservation { HighlightWash.progress(next, timeline.frame()) >= 1f }) {
        next = next.copy(window = null)
    }
    val entry = if (next.window != prior.wash.window || next.provisional != prior.wash.provisional) {
        MediaEntry(next, pacing, pacing?.let { InkEngine.pacedFeather() }, revealStart.coerceIn(0f, 1f))
    } else prior.copy(wash = next)
    SideEffect { history.entry = entry }
    return rememberUpdatedState(entry)
}

internal fun mediaWashWindow(verse: Ayah?, word: ActiveWord?, wallDurationMs: Int?, speed: Float): HighlightWashWindow? =
    if (verse == null || word == null || wallDurationMs == null) null else HighlightWashWindow(
        verse.surahId, verse.number, word.playbackActivation, word.startMs,
        (wallDurationMs.toDouble() * speed).toLong().coerceAtLeast(1L), word.reciterId,
    )

/** Serial prose gates on the preceding edge, without queuing or restarting a media window. */
@Composable
internal fun rememberMediaLetterSweep(
    active: Boolean,
    retain: Boolean,
    activation: Long,
    window: HighlightWashWindow?,
    provisional: Boolean,
    pacing: TajweedPacing.Curve?,
    revealStart: Float,
    predecessor: State<Float>?,
): LetterSweep {
    val timeline = LocalHighlightTimeline.current
    val entry = rememberMediaEntry(active, retain, activation, window, provisional, pacing, revealStart)
    val prior = rememberUpdatedState(predecessor)
    val raw = remember {
        derivedStateOf {
            if (prior.value?.value?.let { !canStartSequentialSweep(it) } == true) 0f else {
                val e = entry.value
                val linear = if (e.wash.window == null) { if (e.wash.active) 0f else 1f }
                    else HighlightWash.progress(e.wash, timeline.frame())
                if (e.pacing != null) linear else InkEngine.sweepEasing.transform(linear)
            }
        }
    }
    val progress = remember {
        derivedStateOf {
            val e = entry.value
            continuedSweepProgress(e.pacing?.at(raw.value) ?: raw.value, e.revealStart)
        }
    }
    val feather = remember { derivedStateOf { entry.value.feather } }
    val curve = remember { derivedStateOf { entry.value.pacing } }
    val letters = remember {
        derivedStateOf {
            val e = entry.value
            continuedSweepProgress(e.pacing?.atLetters(raw.value) ?: raw.value, e.revealStart)
        }
    }
    return remember { LetterSweep(progress, feather, curve, raw, letters) }
}

/** Re-says have independent occurrence windows; only the cosmetic tint dissolve uses wall time. */
@Composable
internal fun rememberMediaRepeatWash(
    repeat: Boolean,
    active: Boolean,
    activation: Long,
    window: HighlightWashWindow?,
    provisional: Boolean,
    pacing: TajweedPacing.Curve?,
    base: State<Float>,
): RepeatWash {
    val timeline = LocalHighlightTimeline.current
    val entry = rememberMediaEntry(repeat && active, true, activation, window, provisional, pacing,
        repeatOccurrence = true)
    val baseState = rememberUpdatedState(base)
    val raw = remember {
        derivedStateOf {
            if (!canStartSequentialSweep(baseState.value.value)) 0f else {
                val e = entry.value
                val linear = if (e.wash.window == null) { if (e.wash.active) 0f else 1f }
                    else HighlightWash.progress(e.wash, timeline.frame())
                if (e.pacing != null) linear else InkEngine.sweepEasing.transform(linear)
            }
        }
    }
    val progress = remember { derivedStateOf { entry.value.pacing?.at(raw.value) ?: raw.value } }
    val letters = remember { derivedStateOf { entry.value.pacing?.atLetters(raw.value) ?: raw.value } }
    val complete = remember { derivedStateOf(structuralEqualityPolicy()) { progress.value >= 1f } }
    val alpha = remember { Animatable(if (repeat) 1f else 0f) }
    LaunchedEffect(repeat, complete.value) {
        if (repeat) alpha.snapTo(1f)
        else if (complete.value) alpha.animateTo(0f, tween(InkEngine.tuning.repeatFadeOutMs))
    }
    val feather = remember { derivedStateOf { entry.value.feather } }
    return remember { RepeatWash(progress, alpha.asState(), feather, letters) }
}
