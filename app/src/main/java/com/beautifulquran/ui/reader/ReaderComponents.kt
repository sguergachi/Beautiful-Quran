package com.beautifulquran.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beautifulquran.QuranApp
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.EnglishTypography
import com.beautifulquran.domain.TajweedPacing
import com.beautifulquran.ui.reader.focus.FocusEngine
import com.beautifulquran.ui.theme.ArabicTitleStyle
import com.beautifulquran.ui.theme.ArabicWordStyle
import com.beautifulquran.ui.theme.GeneratedChapterRosette
import com.beautifulquran.ui.theme.GildedFlourish
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.IslamicBackToOriginCapsule
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.ShapedWordBloom
import com.beautifulquran.ui.theme.ScribeFontFamily
import com.beautifulquran.ui.theme.TranslationFontFamily
import com.beautifulquran.ui.theme.generatedFieldWeave
import com.beautifulquran.ui.theme.gilded
import com.beautifulquran.ui.theme.glyphLayerAlpha
import com.beautifulquran.ui.theme.letterFadeIn
import com.beautifulquran.ui.theme.ornament.chapterOrnamentSeed
import com.beautifulquran.ui.theme.ornament.generateChapterOrnament
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.shapedWordBloom
import com.beautifulquran.ui.theme.inkSmootherstep
import com.beautifulquran.ui.theme.verticalFadingEdges
import kotlinx.coroutines.flow.StateFlow

private fun Int.toArabicIndic(): String =
    toString().map { '٠' + (it - '0') }.joinToString("")

private fun wordFadeAlpha(progress: Float): Float {
    val resting = InkEngine.State.Upcoming.inkAlpha()
    return resting + (InkEngine.State.Active.inkAlpha() - resting) * progress.coerceIn(0f, 1f)
}

private data class RenderedLineText(
    val text: AnnotatedString,
    val wordRanges: List<IntRange>,
    /** Inclusive range of the trailing ﴿N﴾ mark in [text], if present. */
    val markRange: IntRange,
)

/**
 * Ayah-number opacity shared by every reading mode: recessed verses sit at
 * upcoming ink; when the verse is in focus the mark fades up to full.
 */
@Composable
private fun rememberAyahMarkAlpha(focused: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (focused) 1f else InkEngine.State.Upcoming.inkAlpha(),
        animationSpec = tween(
            InkEngine.tuning.ayahMarkFadeMs,
            easing = FastOutSlowInEasing,
        ),
        label = "ayahMarkAlpha",
    )

/** True when [tap] falls inside the glyph bounds of [range], inflated by [hitSlopPx]. */
private fun TextLayoutResult.rangeContains(
    tap: Offset,
    range: IntRange,
    hitSlopPx: Float,
): Boolean =
    range
        .map { offset -> getBoundingBox(offset) }
        .reduceOrNull { acc, rect -> acc.expandToInclude(rect) }
        ?.inflate(hitSlopPx)
        ?.contains(tap) == true

private fun TextLayoutResult.wordIndexAt(
    tap: Offset,
    ranges: List<IntRange>,
    hitSlopPx: Float,
): Int = ranges.indexOfFirst { rangeContains(tap, it, hitSlopPx) }

private fun Rect.expandToInclude(other: Rect): Rect =
    Rect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

/**
 * Returns the ink alpha as [State] so callers can defer the read to the draw
 * phase (inside a draw modifier): the fade animates every frame without
 * recomposing or re-laying-out a single word.
 *
 * Upcoming (including recessed non-active ayahs during playback) uses a short
 * tween so leaving a verse soft-dims; handoff onto a verse that was already
 * Upcoming does not flash full ink.
 */
@Composable
private fun animatedInkAlpha(state: InkEngine.State): State<Float> =
    animateFloatAsState(
        targetValue = state.inkAlpha(),
        // The active word's base ink is carried by the letter sweep, not this
        // value, so snap it straight to full: that way a word recited faster
        // than the tween (short words at speed) is already at full ink the
        // instant it flips to Recited, instead of dipping to a stale mid-fade
        // value and animating back up (a visible flicker on hand-off).
        animationSpec = if (state == InkEngine.State.Active) {
            snap<Float>()
        } else {
            tween(InkEngine.tuning.inkFadeMs, easing = FastOutSlowInEasing)
        },
        label = "inkAlpha",
    )

private const val ARABIC_ONLY_HAFS_FONT_MULTIPLIER = 1.0f

// The inline ayah end-marker (﴿N﴾) is set smaller than the Quranic words it
// closes, matching the standalone [AyahNumberMark] used in the other reading
// modes (20sp against the 30sp word body). Without this the marker inherits the
// full word size and reads as oversized in the Arabic-only view.
private const val AYAH_MARK_SIZE_RATIO = 20f / 30f

private data class RepeatWash(
    val progress: State<Float>,
    val alpha: State<Float>,
)

internal enum class RepeatWashAction { Hold, Reveal, Release }

internal fun repeatWashAction(
    wasRepeat: Boolean,
    previousActivation: Long,
    repeat: Boolean,
    activation: Long,
): RepeatWashAction = when {
    repeat && (!wasRepeat || activation != 0L && activation != previousActivation) ->
        RepeatWashAction.Reveal
    !repeat && wasRepeat -> RepeatWashAction.Release
    else -> RepeatWashAction.Hold
}

private class RepeatWashLifecycle(
    var repeat: Boolean = false,
    var activation: Long = 0L,
)

@Composable
private fun rememberRepeatWash(
    repeat: Boolean,
    sweepMs: Int?,
    /** Bumps on seek for the active word so replaying it re-runs orange too. */
    activation: Long = 0L,
): RepeatWash {
    val progress = remember { Animatable(if (repeat) 0f else 1f) }
    val alpha = remember { Animatable(if (repeat) 1f else 0f) }
    val lifecycle = remember { RepeatWashLifecycle() }
    val action = repeatWashAction(
        wasRepeat = lifecycle.repeat,
        previousActivation = lifecycle.activation,
        repeat = repeat,
        activation = activation,
    )
    // A completed repeat clock survives between entries. Pin its displayed
    // value to zero now, before the reveal coroutine can expose one full frame.
    val entryPending = remember(repeat, activation) {
        mutableStateOf(action == RepeatWashAction.Reveal)
    }
    SideEffect {
        lifecycle.repeat = repeat
        lifecycle.activation = activation
    }
    LaunchedEffect(repeat, activation) {
        when (action) {
            RepeatWashAction.Reveal -> {
                // Chain entry always reveals, including a same-word repeat
                // whose already-non-zero activation generation is unchanged.
                // A new non-zero generation also replays an active repeat.
                progress.snapTo(0f)
                alpha.snapTo(1f)
                entryPending.value = false
                progress.animateTo(
                    1f,
                    tween(sweepMs ?: InkEngine.tuning.repeatSweepMs, easing = InkEngine.sweepEasing),
                )
            }
            RepeatWashAction.Release -> {
                progress.snapTo(1f)
                alpha.animateTo(
                    0f,
                    tween(InkEngine.tuning.repeatFadeOutMs, easing = InkEngine.sweepEasing),
                )
            }
            // Active moving to the next word drops this word's activation to
            // zero; hold its completed orange instead of restarting the wash.
            RepeatWashAction.Hold -> Unit
        }
    }
    val displayedProgress = remember(entryPending) {
        derivedStateOf { displayedSweepProgress(entryPending.value, progress.value) }
    }
    return RepeatWash(progress = displayedProgress, alpha = alpha.asState())
}

/**
 * One-shot search-hit flash: the same directional orange wash as
 * [rememberRepeatWash], run [SearchHitFlash.PULSES] times (wash in → dissolve
 * out → wash in → dissolve out). Independent of karaoke `ink.repeat` so a
 * real repeat chain is never cancelled or restarted.
 */
@Composable
private fun rememberSearchHitWash(active: Boolean): RepeatWash {
    val progress = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) {
            progress.snapTo(1f)
            alpha.snapTo(0f)
            return@LaunchedEffect
        }
        val sweepMs = InkEngine.tuning.repeatSweepMs
        val fadeMs = InkEngine.tuning.repeatFadeOutMs
        repeat(SearchHitFlash.PULSES) {
            alpha.snapTo(1f)
            progress.snapTo(0f)
            progress.animateTo(1f, tween(sweepMs, easing = InkEngine.sweepEasing))
            alpha.animateTo(0f, tween(fadeMs, easing = InkEngine.sweepEasing))
        }
    }
    return RepeatWash(progress = progress.asState(), alpha = alpha.asState())
}

/**
 * White-gold glint of freshly laid ink ([InkEngine.glinting] — Nightfall and
 * Royal Green): full strength while the new word's letters sweep in, then a
 * slow dissolve back to plain recited ink over [InkEngine.Tuning.glintFadeMs]
 * once the voice moves on. The glint has no sweep of its own — it rides the
 * word's existing letter sweep — so this is just the dissolve alpha.
 */
@Composable
private fun rememberGlintAlpha(glinting: Boolean): State<Float> {
    val alpha = remember { Animatable(if (glinting) 1f else 0f) }
    LaunchedEffect(glinting) {
        if (glinting) {
            alpha.snapTo(1f)
        } else if (alpha.value > 0f) {
            alpha.animateTo(
                0f,
                tween(InkEngine.tuning.glintFadeMs, easing = InkEngine.sweepEasing),
            )
        }
    }
    return alpha.asState()
}

internal class GlintIdentity(repeat: Boolean) {
    private var glinting = false
    private var currentRepeat = repeat
    var repeat = repeat
        private set
    var replacedByRepeat = false
        private set

    fun update(glinting: Boolean, repeat: Boolean): Boolean {
        if (glinting && !this.glinting) {
            this.repeat = repeat
            replacedByRepeat = false
        } else if (glinting && repeat && !currentRepeat && !this.repeat) {
            replacedByRepeat = true
        }
        this.glinting = glinting
        currentRepeat = repeat
        return this.repeat
    }
}

/** Holds a glimmer's original colour through its formation and dry-down. */
@Composable
private fun rememberGlintIdentity(glinting: Boolean, repeat: Boolean): GlintIdentity =
    remember { GlintIdentity(repeat) }.also { it.update(glinting, repeat) }

internal fun glintCarryAlpha(replacedByRepeat: Boolean, repeatProgress: Float): Float =
    if (replacedByRepeat) 1f - inkSmootherstep(repeatProgress) else 1f

private fun Modifier.repeatInkLayer(
    wash: RepeatWash,
    rtl: Boolean,
): Modifier =
    glyphLayerAlpha { wash.alpha.value }
        .letterFadeIn(
            progress = { wash.progress.value },
            rtl = rtl,
            restingAlpha = 0f,
            feather = InkEngine.tuning.washFeather,
        )

/**
 * Progress + optional feather locked for one word's letter sweep. [feather]
 * is non-null only while a tajweed-paced activation (or its residual) is
 * running, so handoff does not widen/narrow the edge mid-wash.
 */
private class LetterSweep(
    val progress: State<Float>,
    val feather: State<Float?>,
)

internal enum class SweepEntryAction { Arm, Keep, Clear }

internal fun sweepEntryAction(
    wasActive: Boolean,
    previousActivation: Long,
    active: Boolean,
    activation: Long,
    hasSweep: Boolean,
): SweepEntryAction = when {
    !active || !hasSweep -> SweepEntryAction.Clear
    !wasActive || activation != previousActivation -> SweepEntryAction.Arm
    else -> SweepEntryAction.Keep
}

internal fun displayedSweepProgress(entryPending: Boolean, progress: Float): Float =
    if (entryPending) 0f else progress

internal fun continuedSweepProgress(progress: Float, start: Float): Float {
    val clampedStart = start.coerceIn(0f, 1f)
    return clampedStart + progress.coerceIn(0f, 1f) * (1f - clampedStart)
}

/**
 * Matches the completed prefix wash's leading-edge position in the main wash.
 * The prefix travels one [prefixFraction] with an equally wide feather.
 */
internal fun waslContinuationStart(prefixFraction: Float, mainFeather: Float): Float =
    (2f * prefixFraction / (1f + mainFeather)).coerceIn(0f, 1f)

private class SweepEntryLifecycle(
    var active: Boolean = false,
    var activation: Long = 0L,
    var applied: Boolean = true,
    var durationMs: Int = 1,
    var pacing: TajweedPacing.Curve? = null,
    var feather: Float? = null,
)

/**
 * Drives the letter-fade sweep for the active word: restarts at 0 each time
 * the word lights up and runs for [sweepMs] — usually the karaoke hold, but
 * floored at [InkEngine.Tuning.minSweepMs] so short words still breathe.
 *
 * [activation] bumps on a genuine seek so replaying the *same* Active word
 * (tap it again) restarts the wash; mid-word retunes of [sweepMs] alone must
 * not cancel the animation.
 *
 * When Active ends as **Recited** before the wash finishes (very short holds,
 * first-word timing with almost no remaining Active time, or a min-sweep floor
 * past handoff), the residual wash **completes** instead of snapping to full
 * ink. Renderers keep the letter mask on while progress < 1 so short words
 * still show the soft directional edge.
 *
 * Leaving Active for **Upcoming/Plain** (seek, recess) abandons the residual
 * immediately — finishing toward full ink then dimming back would flash.
 *
 * With a [pacing] curve (tajweed pacing) the Animatable becomes a *linear
 * clock* and the curve shapes it into letter dwell — the ink stalls on a held
 * madd and glides over short letters. The bezier sweep easing would distort
 * that letter timing, so paced words drop it; the feathered wash edge keeps
 * the soft toe and shoulder.
 *
 * Curve, duration, and feather are captured at Active entry: toggling tajweed
 * (or retuning speed) mid-word must not remap a half-finished wash back toward
 * the start — that read as the animation "resetting and playing again."
 */
@Composable
private fun rememberLetterSweep(
    active: Boolean,
    /** True only for Active→Recited handoff; Upcoming/Plain must not residual. */
    finishResidual: Boolean,
    sweepMs: Int?,
    pacing: TajweedPacing.Curve? = null,
    activation: Long = 0L,
): LetterSweep {
    // Survives Active → Recited so a short hold can finish its wash after
    // handoff instead of recreating at progress 1 (the old hard snap).
    val sweep = remember { Animatable(1f) }
    val lockedMs = remember { mutableStateOf(0) }
    val lockedPacing = remember { mutableStateOf<TajweedPacing.Curve?>(null) }
    val lockedFeather = remember { mutableStateOf<Float?>(null) }
    val lifecycle = remember { SweepEntryLifecycle() }
    val entryAction = sweepEntryAction(
        wasActive = lifecycle.active,
        previousActivation = lifecycle.activation,
        active = active,
        activation = activation,
        hasSweep = sweepMs != null,
    )
    // Read during composition, applied after it. The entry snapshot has to be
    // taken from *this* composition's inputs, but writing it here would mutate
    // remembered state mid-composition — a composition Compose is free to
    // discard or re-run, which would leave `applied` false with no effect
    // coming to clear it. So compute now, commit in the SideEffect below.
    val armDurationMs = sweepMs ?: 1
    val armFeather = if (pacing != null) InkEngine.pacedFeather() else null
    // The display mask, by contrast, *must* be resolved during composition —
    // that is the whole point: the draw phase reads it before any effect runs.
    // A `remember` calculation is the sanctioned place to do that.
    val entryPending = remember(active, activation) {
        mutableStateOf(entryAction == SweepEntryAction.Arm)
    }
    SideEffect {
        if (entryAction == SweepEntryAction.Arm) {
            lifecycle.applied = false
            lifecycle.durationMs = armDurationMs
            lifecycle.pacing = pacing
            lifecycle.feather = armFeather
        }
        // Last, so the Arm test above still sees the previous entry.
        lifecycle.active = active
        lifecycle.activation = activation
    }
    // Key on active + residual policy + activation — restarts on word-tap /
    // seek (activation bump) and reacts when the leave target changes.
    LaunchedEffect(active, finishResidual, activation) {
        if (active && sweepMs != null) {
            // This launch belongs to the composition that armed (only an Arm
            // can bring the keys to active-with-a-sweep), so its own captured
            // values *are* the entry snapshot. Reading them instead of the
            // tracker keeps the common path independent of whether the
            // SideEffect above has run yet.
            lockedMs.value = sweepMs
            lockedPacing.value = pacing
            lockedFeather.value = armFeather
            sweep.snapTo(0f)
            lifecycle.applied = true
            entryPending.value = false
            val easing = if (pacing != null) LinearEasing else InkEngine.sweepEasing
            sweep.animateTo(1f, tween(sweepMs, easing = easing))
        } else if (finishResidual) {
            // A residual belongs to an *earlier* frame's entry, so here the
            // tracker is the only source — see the SideEffect above.
            // If Active ended before its reset coroutine ran, begin the
            // residual at zero instead of exposing the stale completed value.
            if (!lifecycle.applied) {
                lockedMs.value = lifecycle.durationMs
                lockedPacing.value = lifecycle.pacing
                lockedFeather.value = lifecycle.feather
                sweep.snapTo(0f)
                lifecycle.applied = true
            }
            val total = lockedMs.value.coerceAtLeast(1)
            if (sweep.value < 1f) {
                val remain = ((1f - sweep.value) * total).toInt().coerceAtLeast(1)
                val easing =
                    if (lockedPacing.value != null) LinearEasing else InkEngine.sweepEasing
                sweep.animateTo(1f, tween(remain, easing = easing))
            }
            // Released only now, never before the residual finishes: the curve
            // is what maps the Animatable's linear clock to wash position, so
            // dropping it mid-wash would jump the edge. At progress 1 the
            // mapping is the identity, so this is invisible — and it stops a
            // completed word from pinning its TajweedPacing.Curve until the
            // next arm.
            lockedPacing.value = null
            lockedFeather.value = null
        } else {
            // Upcoming / Plain / idle: abandon residual so dim ink applies now.
            if (sweep.value < 1f) sweep.snapTo(1f)
            lifecycle.applied = true
            lockedPacing.value = null
            lockedFeather.value = null
        }
    }
    val progress = remember(entryPending) {
        derivedStateOf {
            val mapped = lockedPacing.value?.at(sweep.value) ?: sweep.value
            displayedSweepProgress(entryPending.value, mapped)
        }
    }
    return remember(progress) { LetterSweep(progress = progress, feather = lockedFeather) }
}

/**
 * A one-glyph continuation wash. [fraction] deliberately bounds both its
 * travel and feather so the following letter stays at upcoming ink.
 */
private data class WaslPrefix(
    val fraction: Float,
    val progress: State<Float>,
)

private class ActiveWordEntry(
    var index: Int,
    var activation: Long,
)

/** Blooms the next opening letter over the connected tail of this word. */
@Composable
private fun rememberWaslProgress(
    connection: TajweedPacing.Connection?,
    sweepMs: Int?,
    identity: Int?,
    activation: Long,
): State<Float> {
    val clock = remember(identity, activation) { Animatable(0f) }
    val entryConnection = remember(identity, activation) { connection }
    val entryMs = remember(identity, activation) { sweepMs }
    LaunchedEffect(identity, activation) {
        if (entryConnection == null || entryMs == null) {
            clock.snapTo(0f)
        } else {
            clock.snapTo(0f)
            clock.animateTo(1f, tween(entryMs, easing = LinearEasing))
        }
    }
    return remember(identity, activation) {
        derivedStateOf { entryConnection?.at(clock.value) ?: 0f }
    }
}

/** Comfortable reading band the active word is kept inside while follow mode
 * scrolls the sheet (see [wordUnitBehavior] / [shapedActiveWordInView]).
 * Shared with [ReaderScreen] so the focus engine's bottom guard matches. */
internal val ActiveWordTopMargin = 144.dp
internal val ActiveWordBottomMargin = 132.dp
private val GlintLayerBleed = 14.dp

/** Measures a target as (top, bottom) in LazyColumn viewport pixels. */
private typealias ViewportBoundsMeasure = () -> Pair<Float, Float>?

/** Hands live word bounds to the focus controller (may re-call). */
private typealias OnKeepWordInView = (measure: ViewportBoundsMeasure) -> Unit

/** Routes a live note-field measure through the serialized focus controller. */
private typealias OnKeepAnnotationInView = suspend (
    keyboardOverlapPx: Float,
    keyboardPaddingPx: Float,
    measure: ViewportBoundsMeasure,
) -> Unit

/**
 * Word-level lyric follow for a single shaped paragraph (Hafs / English).
 * On each active-word change, asks [onKeepWordInView] to measure the word's
 * list-viewport bounds and scroll it into the reading band via the focus
 * controller — more reliable than BringIntoView inside a tall LazyColumn item.
 * Per-word units use [wordUnitBehavior] instead.
 */
@Composable
private fun Modifier.shapedActiveWordInView(
    keepInView: Boolean,
    activeIndex: Int,
    wordRanges: List<IntRange>,
    layoutResult: TextLayoutResult?,
    listCoordinates: () -> LayoutCoordinates?,
    onKeepWordInView: OnKeepWordInView?,
): Modifier {
    var textCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(keepInView, activeIndex, layoutResult, wordRanges, textCoordinates) {
        if (!keepInView || activeIndex < 0 || onKeepWordInView == null) return@LaunchedEffect
        // Snapshot the index/layout the effect was launched for; measure is
        // re-invoked inside the focus lock after any competing scroll settles.
        val index = activeIndex
        val layout = layoutResult
        onKeepWordInView {
            val textLayout = layout ?: return@onKeepWordInView null
            val textCoords = textCoordinates?.takeIf { it.isAttached } ?: return@onKeepWordInView null
            val listCoords = listCoordinates()?.takeIf { it.isAttached } ?: return@onKeepWordInView null
            val range = wordRanges.getOrNull(index) ?: return@onKeepWordInView null
            if (range.isEmpty()) return@onKeepWordInView null
            val first = textLayout.getBoundingBox(range.first)
            val last = textLayout.getBoundingBox(range.last)
            val top = minOf(first.top, last.top)
            val bottom = maxOf(first.bottom, last.bottom)
            val listTop = listCoords.boundsInWindow().top
            val wordTop = textCoords.localToWindow(Offset(0f, top)).y - listTop
            val wordBottom = textCoords.localToWindow(Offset(0f, bottom)).y - listTop
            wordTop to wordBottom
        }
    }
    return this.onGloballyPositioned { textCoordinates = it }
}

/**
 * Bundles the three animations every word unit runs: the lyric ink fade
 * between visual states, the letter sweep of the active word, and the orange
 * wash of a repeated word. All values are [State]s read in the draw phase.
 */
private class WordHighlight(
    val isActive: Boolean,
    private val repeat: Boolean,
    private val lyricInk: State<Float>,
    private val sweep: LetterSweep,
    private val revealStart: Float,
    val repeatWash: RepeatWash,
    private val glintAlpha: State<Float>,
    val glintIsRepeat: Boolean,
    private val glintReplacedByRepeat: Boolean,
) {
    private val sweepProgress: Float get() = sweep.progress.value
    private val continuedProgress: Float
        get() = continuedSweepProgress(sweepProgress, revealStart)
    private val washFeather: Float
        get() = sweep.feather.value ?: InkEngine.tuning.washFeather

    /** Modifier for the base text layer: letters sweep in while the word is
     * active (and while a short-hold residual wash finishes after handoff),
     * rest at the lyric ink otherwise. Progress is read only in the draw
     * phase so residual frames never recompose the word tree. While the word
     * is repeating, the base layer stays untouched — orange carries motion. */
    fun baseLayer(rtl: Boolean): Modifier = when {
        repeat -> Modifier
        else -> Modifier
            .glyphLayerAlpha {
                // Wash owns ink strength mid-reveal; lyric alpha applies only
                // once settled (upcoming dim, plain, recited).
                if (isActive || sweepProgress < 1f) 1f else lyricInk.value
            }
            .letterFadeIn(
                progress = { continuedProgress },
                rtl = rtl,
                restingAlpha = InkEngine.State.Upcoming.inkAlpha(),
                feather = washFeather,
            )
    }

    /** Whether the orange repeat overlay still has any ink to show. */
    val showRepeatLayer: Boolean get() = repeatWash.alpha.value > 0f

    /** Whether the white-gold glint overlay still has any sheen to show. */
    val showGlintLayer: Boolean get() = glintAlpha.value > 0f

    /** Repeat glimmer uses terracotta ink rather than the first-pass white gold. */
    /** Modifier for the glint overlay: rides the base layer's own letter
     * sweep (same progress and feather, so the gold edge is the ink edge),
     * then dissolves via [glintAlpha] once the word settles to Recited. */
    fun glintLayer(rtl: Boolean): Modifier =
        Modifier
            .drawWithContent {
                val alpha = glintAlpha.value *
                    glintCarryAlpha(glintReplacedByRepeat, repeatWash.progress.value)
                if (alpha <= 0f) return@drawWithContent
                val bleed = GlintLayerBleed.toPx()
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(
                        Rect(-bleed, -bleed, size.width + bleed, size.height + bleed),
                        Paint().apply { this.alpha = alpha },
                    )
                }
                drawContent()
                drawIntoCanvas { canvas -> canvas.restore() }
            }
            .letterFadeIn(
                progress = {
                    if (glintIsRepeat) repeatWash.progress.value else continuedProgress
                },
                rtl = rtl,
                restingAlpha = 0f,
                feather = washFeather,
            )

    /** Tight glyph halo: forms with the word and recedes with [glintAlpha]. */
    fun glintHaloLayer(): Modifier = Modifier.drawWithContent {
        val progress =
            if (glintIsRepeat) repeatWash.progress.value else continuedProgress
        val alpha = glintAlpha.value * inkSmootherstep(progress) *
            glintCarryAlpha(glintReplacedByRepeat, repeatWash.progress.value)
        if (alpha <= 0f) return@drawWithContent
        val bleed = GlintLayerBleed.toPx()
        drawIntoCanvas { canvas ->
            canvas.saveLayer(
                Rect(-bleed, -bleed, size.width + bleed, size.height + bleed),
                Paint().apply { this.alpha = alpha },
            )
        }
        drawContent()
        drawIntoCanvas { canvas -> canvas.restore() }
    }

    /** Draw-phase alpha for secondary lines (gloss, transliteration): they
     * fade with the word's sweep but never letter-reveal. Residual short-hold
     * wash after handoff keeps riding the same progress. */
    fun secondaryAlpha(): Float {
        if (repeat) return lyricInk.value
        if (isActive || sweepProgress < 1f) return wordFadeAlpha(sweepProgress)
        return lyricInk.value
    }
}

@Composable
private fun rememberWordHighlight(
    ink: InkEngine.Word,
    sweepMs: Int?,
    pacing: TajweedPacing.Curve? = null,
    revealStart: Float = 0f,
    activation: Long = 0L,
): WordHighlight {
    val isActive = ink.state == InkEngine.State.Active
    val glintInk = LocalQuranAccents.current.glintInk
    val glinting = glintInk != null && InkEngine.glinting(ink.state)
    val glintIdentity = rememberGlintIdentity(glinting, ink.repeat)
    // Freeze tajweed curve for this activation so an Ink Lab toggle mid-word
    // cannot remap the wash (or swap feather) and look like a reset.
    val entryPacing = remember(isActive, activation) { pacing.takeIf { isActive } }
    return WordHighlight(
        isActive = isActive,
        repeat = ink.repeat,
        lyricInk = animatedInkAlpha(ink.state),
        sweep = rememberLetterSweep(
            active = isActive,
            finishResidual = ink.state == InkEngine.State.Recited,
            sweepMs = sweepMs,
            pacing = entryPacing,
            activation = activation,
        ),
        revealStart = revealStart,
        repeatWash = rememberRepeatWash(
            repeat = ink.repeat,
            sweepMs = sweepMs.takeIf { isActive },
            activation = if (isActive) activation else 0L,
        ),
        glintAlpha = rememberGlintAlpha(glinting),
        glintIsRepeat = glintIdentity.repeat,
        glintReplacedByRepeat = glintIdentity.replacedByRepeat,
    )
}

/**
 * Shared word-unit chrome: keeps the word inside the comfortable reading band
 * while it is active and follow mode is on (via [onKeepWordInView] → focus
 * controller), and makes it tappable (quietly) when [onClick] is provided.
 * Apply before the unit's own padding.
 */
@Composable
private fun Modifier.wordUnitBehavior(
    active: Boolean,
    keepInView: Boolean,
    listCoordinates: () -> LayoutCoordinates?,
    onKeepWordInView: OnKeepWordInView?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    var wordCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(active, keepInView, wordCoordinates) {
        if (!active || !keepInView || onKeepWordInView == null) return@LaunchedEffect
        onKeepWordInView {
            val wordCoords = wordCoordinates?.takeIf { it.isAttached } ?: return@onKeepWordInView null
            val listCoords = listCoordinates()?.takeIf { it.isAttached } ?: return@onKeepWordInView null
            val bounds = wordCoords.boundsInWindow()
            val listTop = listCoords.boundsInWindow().top
            (bounds.top - listTop) to (bounds.bottom - listTop)
        }
    }
    return this
        .onGloballyPositioned { wordCoordinates = it }
        .let { m ->
            when {
                onClick != null && onLongClick != null ->
                    m.quietClickable(onLongClick = onLongClick, onClick = onClick)
                onClick != null -> m.quietClickable(onClick = onClick)
                onLongClick != null -> m.quietClickable(onLongClick = onLongClick, onClick = {})
                else -> m
            }
        }
}

/** Paint-only text twin: the wrapper fills the base word without contributing
 * to its measurement, while [Text] keeps the base word's natural constraints.
 * Forcing the text itself to matchParentSize can reflow Arabic by a pixel and
 * clip its overhanging marks while a wash mask is active. */
@Composable
private fun BoxScope.InkOverlayText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
) {
    Box(
        contentAlignment = AbsoluteAlignment.TopLeft,
        modifier = Modifier.matchParentSize(),
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = modifier,
        )
    }
}

/** The two-layer karaoke text every word unit renders: the base ink, plus an
 * orange overlay that sweeps in while the word belongs to a repeat chain and
 * dissolves back out once the chain releases. An optional [searchHitWash]
 * reuses that same overlay ([InkOverlayText] + [repeatInkLayer]) for the home
 * search-hit flash — never a second measured Text that would shift layout. */
@Composable
private fun HighlightLayeredText(
    text: String,
    highlight: WordHighlight,
    rtl: Boolean,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    searchHitWash: RepeatWash? = null,
    waslPrefix: WaslPrefix? = null,
) {
    val repeatInk = LocalQuranAccents.current.repeatInk
    val glintInk = LocalQuranAccents.current.glintInk
    val glimmerInk = if (highlight.glintIsRepeat) repeatInk else glintInk ?: repeatInk
    // Prefer a live repeat chain; otherwise the one-shot search-hit wash.
    val orangeWash = when {
        highlight.showRepeatLayer -> highlight.repeatWash
        searchHitWash != null && searchHitWash.alpha.value > 0f -> searchHitWash
        else -> null
    }
    Box(modifier) {
        // A restrained glyph-shaped halo sits behind the ink—no radial field.
        if (glintInk != null && highlight.showGlintLayer) {
            InkOverlayText(
                text = text,
                style = style.copy(
                    shadow = Shadow(
                        color = glimmerInk.copy(alpha = InkEngine.tuning.glintGlowAlpha),
                        blurRadius = InkEngine.tuning.glintGlowRadius,
                    ),
                ),
                color = glimmerInk.copy(alpha = 0.01f),
                modifier = highlight.glintHaloLayer(),
            )
        }
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = highlight.baseLayer(rtl),
        )
        if (waslPrefix != null) {
            InkOverlayText(
                text = text,
                style = style,
                color = color,
                modifier = Modifier.letterFadeIn(
                    progress = { waslPrefix.progress.value },
                    rtl = rtl,
                    restingAlpha = 0f,
                    feather = waslPrefix.fraction,
                    revealFraction = waslPrefix.fraction,
                ),
            )
        }
        if (orangeWash != null) {
            InkOverlayText(
                text = text,
                style = style,
                color = repeatInk.copy(alpha = InkEngine.tuning.repeatInkAlpha),
                modifier = Modifier.repeatInkLayer(orangeWash, rtl),
            )
        }
        // First-pass words glimmer white-gold; repeats glimmer terracotta.
        if (glintInk != null && highlight.showGlintLayer) {
            InkOverlayText(
                text = text,
                style = style,
                color = glimmerInk.copy(
                    alpha = if (highlight.glintIsRepeat) {
                        InkEngine.tuning.repeatInkAlpha
                    } else InkEngine.tuning.glintTintAlpha,
                ),
                modifier = highlight.glintLayer(rtl),
            )
        }
    }
}

@Composable
private fun WordUnit(
    word: Word,
    ink: InkEngine.Word,
    fontScale: Float,
    sweepMs: Int?,
    showGloss: Boolean,
    showTransliteration: Boolean,
    searchHit: Boolean,
    keepInView: Boolean,
    listCoordinates: () -> LayoutCoordinates? = { null },
    onKeepWordInView: OnKeepWordInView? = null,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    /** When true, run the orange search-hit wash on Arabic + gloss. */
    showFlash: Boolean = false,
    /** Tajweed pacing of the active word's sweep — null for the plain sweep. */
    pacing: TajweedPacing.Curve? = null,
    /** Main-wash progress already laid down by a connected previous word. */
    revealStart: Float = 0f,
    /** Opening-letter ink handed across from a connected previous word. */
    waslPrefix: WaslPrefix? = null,
    /** Seek-generation so replaying this Active word restarts the wash. */
    activation: Long = 0L,
) {
    val highlight = rememberWordHighlight(
        ink = ink,
        sweepMs = sweepMs,
        pacing = pacing,
        revealStart = revealStart,
        activation = activation,
    )
    val searchHitWash = rememberSearchHitWash(showFlash)
    val repeatInk = LocalQuranAccents.current.repeatInk
    val glossWeight = if (searchHit) FontWeight.Bold else null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .wordUnitBehavior(
                active = highlight.isActive,
                keepInView = keepInView,
                listCoordinates = listCoordinates,
                onKeepWordInView = onKeepWordInView,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        HighlightLayeredText(
            text = word.arabic,
            highlight = highlight,
            rtl = true,
            color = MaterialTheme.colorScheme.onBackground,
            style = ArabicWordStyle.copy(fontSize = ArabicWordStyle.fontSize * fontScale),
            searchHitWash = searchHitWash,
            waslPrefix = waslPrefix,
        )
        if (showGloss) {
            Box {
                Text(
                    text = word.translation,
                    fontSize = 12.sp * fontScale,
                    lineHeight = 15.sp * fontScale,
                    fontWeight = glossWeight,
                    color = if (searchHit) {
                        LocalQuranAccents.current.gold
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.glyphLayerAlpha { highlight.secondaryAlpha() },
                )
                if (searchHitWash.alpha.value > 0f) {
                    Text(
                        text = word.translation,
                        fontSize = 12.sp * fontScale,
                        lineHeight = 15.sp * fontScale,
                        fontWeight = glossWeight,
                        color = repeatInk.copy(alpha = InkEngine.tuning.repeatInkAlpha),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .matchParentSize()
                            .repeatInkLayer(searchHitWash, rtl = false),
                    )
                }
            }
        }
        if (showTransliteration) {
            Text(
                text = word.transliteration,
                fontSize = 11.sp * fontScale,
                lineHeight = 14.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.glyphLayerAlpha { highlight.secondaryAlpha() },
            )
        }
    }
}

/**
 * One Arabic word in the connected (word-for-word gloss disabled) flow. Same
 * recitation letter-fade as [WordUnit], but with no gloss column and tight
 * spacing so the words read as continuous Quranic script rather than boxed
 * tokens.
 */
@Composable
fun ConnectedArabicWordUnit(
    word: Word,
    ink: InkEngine.Word,
    fontScale: Float,
    sweepMs: Int?,
    keepInView: Boolean,
    listCoordinates: () -> LayoutCoordinates? = { null },
    onKeepWordInView: OnKeepWordInView? = null,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    activation: Long = 0L,
) {
    val highlight = rememberWordHighlight(ink, sweepMs, activation = activation)
    HighlightLayeredText(
        text = word.arabic,
        highlight = highlight,
        rtl = true,
        color = MaterialTheme.colorScheme.onBackground,
        style = ArabicWordStyle.copy(fontSize = ArabicWordStyle.fontSize * fontScale),
        modifier = Modifier
            .wordUnitBehavior(
                active = highlight.isActive,
                keepInView = keepInView,
                listCoordinates = listCoordinates,
                onKeepWordInView = onKeepWordInView,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 4.dp, vertical = 3.dp),
    )
}

/**
 * Ink colours for the Arabic-only shaped ayah. Base spans stay full ink —
 * upcoming dim, first-pass bloom, and orange repeat are all draw-phase
 * overlays so word/ayah transitions never reshape the run or flash span
 * colours.
 */
private class WordInkPalette(
    private val fullInk: Color,
    private val paper: Color,
    private val repeatInk: Color,
) {
    val fullInkColor: Color get() = fullInk
    val paperColor: Color get() = paper
    val repeatInkColor: Color get() = repeatInk
}

@Composable
private fun rememberWordInkPalette(): WordInkPalette {
    val fullInk = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    val repeatInk = LocalQuranAccents.current.repeatInk
    return remember(fullInk, paper, repeatInk) {
        WordInkPalette(fullInk = fullInk, paper = paper, repeatInk = repeatInk)
    }
}

/** One letter sweep per word, running for Active and finishing residual on
 * Recited. Progress/feather must be read in the draw phase only — never while
 * building the annotated string — so the sweep does not reshape every frame. */
@Composable
private fun rememberLetterSweeps(
    inks: List<InkEngine.Word>,
    activeSweepMs: Int?,
    pacing: TajweedPacing.Curve? = null,
    activation: Long = 0L,
): List<LetterSweep> = inks.map { ink ->
    val active = ink.state == InkEngine.State.Active
    rememberLetterSweep(
        active = active,
        finishResidual = ink.state == InkEngine.State.Recited,
        sweepMs = activeSweepMs.takeIf { active },
        pacing = if (active) pacing else null,
        activation = if (active) activation else 0L,
    )
}

/** Orange wash per word in the repeat chain — same timing as gloss mode. */
@Composable
private fun rememberRepeatWashes(
    inks: List<InkEngine.Word>,
    activeSweepMs: Int?,
    activation: Long = 0L,
): List<RepeatWash> = inks.map { ink ->
    val active = ink.state == InkEngine.State.Active
    rememberRepeatWash(
        repeat = ink.repeat,
        sweepMs = activeSweepMs.takeIf { active },
        activation = if (active) activation else 0L,
    )
}

private data class Glint(
    val alpha: State<Float>,
    val repeat: Boolean,
    val replacedByRepeat: Boolean,
)

/** Glint lifecycle per word, including a repeat colour latched through fade-out.
 * All zeros (and no animation ever starts) on themes without a glint ink. */
@Composable
private fun rememberGlints(inks: List<InkEngine.Word>): List<Glint> {
    val glintInk = LocalQuranAccents.current.glintInk
    return inks.map { ink ->
        val glinting = glintInk != null && InkEngine.glinting(ink.state)
        val identity = rememberGlintIdentity(glinting, ink.repeat)
        Glint(
            alpha = rememberGlintAlpha(glinting),
            repeat = identity.repeat,
            replacedByRepeat = identity.replacedByRepeat,
        )
    }
}

/**
 * English-only lyric set as one continuous prose line. Word ranges retain
 * independent karaoke ink and hit targets without turning natural spaces into
 * layout gaps, so the paragraph keeps a single baseline and browser-like wrap.
 */
@Composable
private fun ResponsiveEnglishAyah(
    ayah: Ayah,
    inks: List<InkEngine.Word>,
    markAlpha: () -> Float,
    fontScale: Float,
    activeSweepMs: Int?,
    activation: Long = 0L,
    searchQuery: String?,
    flashWordPosition: Int?,
    keepActiveWordInView: Boolean,
    listCoordinates: () -> LayoutCoordinates?,
    onKeepWordInView: OnKeepWordInView?,
    onAyahClick: () -> Unit,
    onWordClick: ((Word) -> Unit)?,
    onWordLongClick: ((Word) -> Unit)?,
    /** Hold the trailing ﴿N﴾ mark to write this verse's note. */
    onMarkLongClick: (() -> Unit)? = null,
) {
    val palette = rememberWordInkPalette()
    val gold = LocalQuranAccents.current.gold
    val glintInk = LocalQuranAccents.current.glintInk
    val sweeps = rememberLetterSweeps(inks, activeSweepMs, activation = activation)
    val repeatWashes = rememberRepeatWashes(inks, activeSweepMs, activation)
    val glints = rememberGlints(inks)
    val searchHitWash = rememberSearchHitWash(flashWordPosition != null)
    val activeIndex = inks.indexOfFirst { it.state == InkEngine.State.Active }
    val upcomingCover = 1f - InkEngine.State.Upcoming.inkAlpha()
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = TranslationFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp * fontScale,
        lineHeight = 1.5.em,
        letterSpacing = 0.sp,
        textAlign = TextAlign.Start,
    )
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hitSlopPx = with(LocalDensity.current) { 8.dp.toPx() }
    val punctuatedGlosses = remember(ayah) {
        EnglishTypography.punctuate(ayah.words.map { it.translation })
    }

    val rendered = remember(ayah, palette.fullInkColor, gold, searchQuery, fontScale) {
        val ranges = ArrayList<IntRange>(ayah.words.size)
        var markRange = 0..-1
        val text = buildAnnotatedString {
            ayah.words.forEachIndexed { index, word ->
                val start = length
                withStyle(
                    SpanStyle(
                        color = if (
                            searchQuery != null &&
                            word.translation.contains(searchQuery, ignoreCase = true)
                        ) {
                            gold
                        } else {
                            palette.fullInkColor
                        },
                    ),
                ) {
                    append(punctuatedGlosses[index])
                }
                ranges += start until length
                append(" ")
            }
            val markStart = length
            withStyle(
                SpanStyle(
                    color = gold,
                    fontFamily = HafsFontFamily,
                    // 17/22 keeps the ornament proportional. Sharing the prose
                    // baseline avoids a font-metric paint lift on Android.
                    fontSize = 17.sp * fontScale,
                ),
            ) {
                append("﴿")
                append(ayah.number.toString())
                append("﴾")
            }
            markRange = markStart until length
        }
        RenderedLineText(text = text, wordRanges = ranges, markRange = markRange)
    }

    Text(
        text = rendered.text,
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .shapedActiveWordInView(
                keepInView = keepActiveWordInView,
                activeIndex = activeIndex,
                wordRanges = rendered.wordRanges,
                layoutResult = layoutResult,
                listCoordinates = listCoordinates,
                onKeepWordInView = onKeepWordInView,
            )
            .shapedWordBloom(
                blooms = {
                    val blooms = ArrayList<ShapedWordBloom>(inks.size + 2)
                    inks.forEachIndexed { index, ink ->
                        if (ink.state == InkEngine.State.Active) return@forEachIndexed
                        val coverAlpha = if (ink.state == InkEngine.State.Upcoming) upcomingCover else 0f
                        if (coverAlpha <= 0f) return@forEachIndexed
                        blooms += ShapedWordBloom.UpcomingDim(
                            range = rendered.wordRanges[index],
                            paper = palette.paperColor,
                            coverAlpha = coverAlpha,
                        )
                    }
                    val markCover = (1f - markAlpha()).coerceIn(0f, 1f)
                    if (markCover > 0f && !rendered.markRange.isEmpty()) {
                        blooms += ShapedWordBloom.UpcomingDim(
                            range = rendered.markRange,
                            paper = palette.paperColor,
                            coverAlpha = markCover,
                        )
                    }
                    // First-pass ink: active word, plus any short-hold residual
                    // still finishing after handoff (progress < 1).
                    sweeps.forEachIndexed { index, sweepState ->
                        if (inks[index].repeat) return@forEachIndexed
                        val p = sweepState.progress.value
                        if (p >= 1f) return@forEachIndexed
                        blooms += ShapedWordBloom.InkReveal(
                            range = rendered.wordRanges[index],
                            progress = p,
                            paper = palette.paperColor,
                            restingAlpha = InkEngine.State.Upcoming.inkAlpha(),
                            feather = sweepState.feather.value,
                        )
                    }
                    repeatWashes.forEachIndexed { index, wash ->
                        if (wash.alpha.value <= 0f) return@forEachIndexed
                        blooms += ShapedWordBloom.ColorReveal(
                            range = rendered.wordRanges[index],
                            progress = wash.progress.value,
                            color = palette.repeatInkColor,
                            restingAlpha = 0f,
                            layerAlpha = wash.alpha.value,
                            colorAlpha = InkEngine.tuning.repeatInkAlpha,
                        )
                    }
                    // White-gold sheen sits above both fresh and orange repeat
                    // ink, following whichever wash is active.
                    if (glintInk != null) {
                        glints.forEachIndexed { index, glint ->
                            if (glint.alpha.value <= 0f) return@forEachIndexed
                            val carryAlpha = glintCarryAlpha(
                                replacedByRepeat = glint.replacedByRepeat,
                                repeatProgress = repeatWashes[index].progress.value,
                            )
                            blooms += ShapedWordBloom.ColorReveal(
                                range = rendered.wordRanges[index],
                                progress = if (glint.repeat) {
                                    repeatWashes[index].progress.value
                                } else {
                                    sweeps[index].progress.value
                                },
                                color = if (glint.repeat) {
                                    palette.repeatInkColor
                                } else glintInk,
                                restingAlpha = 0f,
                                layerAlpha = glint.alpha.value * carryAlpha,
                                colorAlpha = if (glint.repeat) {
                                    InkEngine.tuning.repeatInkAlpha
                                } else InkEngine.tuning.glintTintAlpha,
                                glowAlpha = InkEngine.tuning.glintGlowAlpha,
                                glowRadius = InkEngine.tuning.glintGlowRadius,
                                feather = if (glint.repeat) null else sweeps[index].feather.value,
                            )
                        }
                    }
                    val flashIndex = ayah.words.indexOfFirst { it.position == flashWordPosition }
                    if (flashIndex >= 0 && searchHitWash.alpha.value > 0f) {
                        blooms += ShapedWordBloom.ColorReveal(
                            range = rendered.wordRanges[flashIndex],
                            progress = searchHitWash.progress.value,
                            color = palette.repeatInkColor,
                            restingAlpha = 0f,
                            layerAlpha = searchHitWash.alpha.value,
                            colorAlpha = InkEngine.tuning.repeatInkAlpha,
                        )
                    }
                    blooms
                },
                layout = { layoutResult },
                rtl = false,
                feather = InkEngine.tuning.washFeather,
            )
            .then(
                if (onWordClick == null) {
                    Modifier.quietClickable(onClick = onAyahClick)
                } else {
                    Modifier.wordTapTarget(
                        words = ayah.words,
                        ranges = rendered.wordRanges,
                        layoutResult = layoutResult,
                        hitSlopPx = hitSlopPx,
                        onWordClick = onWordClick,
                        onWordLongClick = onWordLongClick,
                        onMiss = onAyahClick,
                        markRange = rendered.markRange,
                        onMarkLongClick = onMarkLongClick,
                    )
                },
            ),
        onTextLayout = { layoutResult = it },
    )
}

/**
 * Resolves taps on an annotated ayah line to the word whose glyph bounds
 * (inflated by [hitSlopPx]) contain the tap; taps that miss every word go to
 * [onMiss] (null = ignored).
 *
 * A long-press is checked against the trailing ﴿N﴾ [markRange] *first*: the mark
 * is the verse's own identity on the page, so holding it opens the verse note
 * rather than a word's lexicon (docs/NOTES.md).
 */
private fun Modifier.wordTapTarget(
    words: List<Word>,
    ranges: List<IntRange>,
    layoutResult: TextLayoutResult?,
    hitSlopPx: Float,
    onWordClick: (Word) -> Unit,
    onWordLongClick: ((Word) -> Unit)? = null,
    onMiss: (() -> Unit)? = null,
    markRange: IntRange = IntRange.EMPTY,
    onMarkLongClick: (() -> Unit)? = null,
): Modifier = pointerInput(ranges, words, layoutResult, onWordLongClick, onMarkLongClick) {
    detectTapGestures(
        onTap = { tap ->
            val wordIndex = layoutResult?.wordIndexAt(tap, ranges, hitSlopPx) ?: -1
            if (wordIndex >= 0) onWordClick(words[wordIndex]) else onMiss?.invoke()
        },
        onLongPress = if (onWordLongClick == null && onMarkLongClick == null) {
            null
        } else {
            { pos ->
                val onMark = onMarkLongClick != null &&
                    layoutResult?.rangeContains(pos, markRange, hitSlopPx) == true
                if (onMark) {
                    onMarkLongClick!!()
                } else if (onWordLongClick != null) {
                    val wordIndex = layoutResult?.wordIndexAt(pos, ranges, hitSlopPx) ?: -1
                    if (wordIndex >= 0) onWordLongClick(words[wordIndex])
                }
            }
        },
    )
}

@Composable
private fun ResponsiveHafsAyah(
    ayah: Ayah,
    inks: List<InkEngine.Word>,
    /** True while another ayah is the lyric line — cover every word with the
     * same upcoming paper so unread ink does not change when this ayah
     * becomes active. */
    dimmed: Boolean,
    /** 0..1 opacity for the trailing ﴿N﴾ mark — fades to full when focused. */
    markAlpha: () -> Float,
    fontSize: TextUnit,
    activeSweepMs: Int?,
    /** Tajweed pacing of the active word's sweep — null for the plain sweep. */
    pacing: TajweedPacing.Curve? = null,
    activeRevealStart: Float = 0f,
    waslPrefixes: List<WaslPrefix?> = emptyList(),
    activation: Long = 0L,
    flashWordPosition: Int? = null,
    /** When the verse is taller than the viewport, keep the active word in the
     * reading band so large type does not disappear under the player bar. */
    keepActiveWordInView: Boolean = false,
    listCoordinates: () -> LayoutCoordinates? = { null },
    onKeepWordInView: OnKeepWordInView? = null,
    onAyahClick: () -> Unit,
    onWordClick: ((Word) -> Unit)?,
    onWordLongClick: ((Word) -> Unit)? = null,
    /** Hold the trailing ﴿N﴾ mark to write this verse's note. */
    onMarkLongClick: (() -> Unit)? = null,
) {
    val palette = rememberWordInkPalette()
    val ayahMarkInk = LocalQuranAccents.current.gold
    val glintInk = LocalQuranAccents.current.glintInk
    // Lock tajweed for this activation (Hafs path shares one curve for the
    // active word) so an Ink Lab toggle mid-word cannot remount the wash.
    val entryPacing = remember(activation, activeSweepMs) { pacing }
    val sweeps = rememberLetterSweeps(inks, activeSweepMs, entryPacing, activation)
    val repeatWashes = rememberRepeatWashes(inks, activeSweepMs, activation)
    val glints = rememberGlints(inks)
    val searchHitWash = rememberSearchHitWash(flashWordPosition != null)
    val activeIndex = inks.indexOfFirst { it.state == InkEngine.State.Active }
    val upcomingCover = 1f - InkEngine.State.Upcoming.inkAlpha()
    // While recessed, the same upcoming paper cover sits on every word.
    // Tween both directions so play-start and pause breathe; Upcoming words
    // keep a floor of [upcomingCover] so ayah handoff never flashes full ink.
    val recessCover = animateFloatAsState(
        targetValue = if (dimmed) upcomingCover else 0f,
        animationSpec = tween(InkEngine.tuning.recessMs, easing = FastOutSlowInEasing),
        label = "recessCover",
    )
    val style = ArabicWordStyle.merge(
        TextStyle(
            fontFamily = HafsFontFamily,
            fontSize = fontSize,
            lineHeight = 1.95.em,
            textAlign = TextAlign.Center,
        ),
    )
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hitSlopPx = with(LocalDensity.current) { 8.dp.toPx() }

    // Full-ink spans only — never bake upcoming/active into the annotated
    // string. Dim, bloom, and orange are draw-phase overlays, so word and
    // ayah boundaries do not reshape or flash the run.
    val rendered = remember(ayah, palette.fullInkColor, ayahMarkInk, fontSize) {
        val ranges = ArrayList<IntRange>(ayah.words.size)
        var markRange = 0..-1
        val text = buildAnnotatedString {
            ayah.words.forEach { word ->
                val start = length
                // One contiguous colour span per word keeps Uthmanic Hafs
                // joining/ligatures intact. Per-glyph spans split shaping runs
                // and caused a visible font flip (#133).
                withStyle(SpanStyle(color = palette.fullInkColor)) {
                    append(word.arabic)
                }
                ranges += start until length
                append(" ")
            }
            val markStart = length
            withStyle(
                SpanStyle(
                    color = ayahMarkInk,
                    fontSize = fontSize * AYAH_MARK_SIZE_RATIO,
                ),
            ) {
                append("﴿")
                append(ayah.number.toArabicIndic())
                append("﴾")
            }
            markRange = markStart until length
        }
        RenderedLineText(text = text, wordRanges = ranges, markRange = markRange)
    }
    Text(
        text = rendered.text,
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .shapedActiveWordInView(
                keepInView = keepActiveWordInView,
                activeIndex = activeIndex,
                wordRanges = rendered.wordRanges,
                layoutResult = layoutResult,
                listCoordinates = listCoordinates,
                onKeepWordInView = onKeepWordInView,
            )
            .shapedWordBloom(
                blooms = {
                    val recess = recessCover.value
                    val blooms = ArrayList<ShapedWordBloom>(inks.size + 2)
                    // Faint cover while recessed (all words) or Upcoming while
                    // active. Same cover strength — ayah handoff does not
                    // change unread ink; only the active word starts its bloom.
                    inks.forEachIndexed { index, ink ->
                        val coverAlpha = when {
                            // Active word is revealed by InkReveal, not recess.
                            ink.state == InkEngine.State.Active -> 0f
                            // Upcoming keeps a dim floor during recess lift so
                            // handoff never flashes full ink.
                            ink.state == InkEngine.State.Upcoming ->
                                maxOf(recess, upcomingCover)
                            recess > 0f -> recess
                            else -> 0f
                        }
                        if (coverAlpha <= 0f) return@forEachIndexed
                        val range = rendered.wordRanges.getOrNull(index)
                            ?: return@forEachIndexed
                        blooms += ShapedWordBloom.UpcomingDim(
                            range = range,
                            paper = palette.paperColor,
                            coverAlpha = coverAlpha,
                        )
                    }
                    // ﴿N﴾ mark: paper cover of (1 − markAlpha) so it fades up
                    // to full gold with the shared ayah-mark animation.
                    val markCover = (1f - markAlpha()).coerceIn(0f, 1f)
                    if (markCover > 0f && !rendered.markRange.isEmpty()) {
                        blooms += ShapedWordBloom.UpcomingDim(
                            range = rendered.markRange,
                            paper = palette.paperColor,
                            coverAlpha = markCover,
                        )
                    }
                    // First-pass ink reveal: paper cover over the shaped full-ink
                    // glyphs, pulled back on the letterFadeIn curve. Skipped
                    // while repeating — orange carries the motion, same as
                    // WordUnit. Also draws residual short-hold washes after
                    // handoff (progress < 1) so short words still breathe.
                    // At progress 0 this matches UpcomingDim, so the first
                    // word hands off without a flash.
                    sweeps.forEachIndexed { index, sweepState ->
                        if (inks[index].repeat) return@forEachIndexed
                        val p = continuedSweepProgress(
                            progress = sweepState.progress.value,
                            start = activeRevealStart.takeIf { index == activeIndex } ?: 0f,
                        )
                        if (p >= 1f) return@forEachIndexed
                        val range = rendered.wordRanges.getOrNull(index)
                            ?: return@forEachIndexed
                        blooms += ShapedWordBloom.InkReveal(
                            range = range,
                            progress = p,
                            paper = palette.paperColor,
                            restingAlpha = InkEngine.State.Upcoming.inkAlpha(),
                            // Locked at Active entry so residual handoff keeps
                            // the paced edge width (not only while activeIndex).
                            feather = sweepState.feather.value,
                        )
                    }
                    waslPrefixes.forEachIndexed { index, prefix ->
                        if (prefix == null || prefix.progress.value <= 0f) {
                            return@forEachIndexed
                        }
                        val range = rendered.wordRanges.getOrNull(index)
                            ?: return@forEachIndexed
                        blooms += ShapedWordBloom.ColorReveal(
                            range = range,
                            progress = prefix.progress.value,
                            color = palette.fullInkColor,
                            feather = prefix.fraction,
                            revealFraction = prefix.fraction,
                        )
                    }
                    // Orange directional bloom: SrcIn-tint the shaped glyphs,
                    // then DstIn-wash — same motion as gloss mode's orange
                    // overlay, without re-shaping or painting neighbour rects.
                    repeatWashes.forEachIndexed { index, wash ->
                        if (wash.alpha.value <= 0f) return@forEachIndexed
                        val range = rendered.wordRanges.getOrNull(index)
                            ?: return@forEachIndexed
                        blooms += ShapedWordBloom.ColorReveal(
                            range = range,
                            progress = wash.progress.value,
                            color = palette.repeatInkColor,
                            restingAlpha = 0f,
                            layerAlpha = wash.alpha.value,
                            colorAlpha = InkEngine.tuning.repeatInkAlpha,
                        )
                    }
                    // White-gold sheen sits above both fresh and orange repeat
                    // ink, following whichever wash is active.
                    if (glintInk != null) {
                        glints.forEachIndexed { index, glint ->
                            if (glint.alpha.value <= 0f) return@forEachIndexed
                            val range = rendered.wordRanges.getOrNull(index)
                                ?: return@forEachIndexed
                            val carryAlpha = glintCarryAlpha(
                                replacedByRepeat = glint.replacedByRepeat,
                                repeatProgress = repeatWashes[index].progress.value,
                            )
                            blooms += ShapedWordBloom.ColorReveal(
                                range = range,
                                progress = if (glint.repeat) {
                                    repeatWashes[index].progress.value
                                } else {
                                    continuedSweepProgress(
                                        progress = sweeps[index].progress.value,
                                        start = activeRevealStart.takeIf {
                                            index == activeIndex
                                        } ?: 0f,
                                    )
                                },
                                color = if (glint.repeat) {
                                    palette.repeatInkColor
                                } else glintInk,
                                restingAlpha = 0f,
                                layerAlpha = glint.alpha.value * carryAlpha,
                                colorAlpha = if (glint.repeat) {
                                    InkEngine.tuning.repeatInkAlpha
                                } else InkEngine.tuning.glintTintAlpha,
                                glowAlpha = InkEngine.tuning.glintGlowAlpha,
                                glowRadius = InkEngine.tuning.glintGlowRadius,
                                feather = if (glint.repeat) {
                                    null
                                } else {
                                    sweeps[index].feather.value
                                },
                            )
                        }
                    }
                    // Home search-hit flash: same ColorReveal wash as the
                    // orange repeat bloom — directional mask + dissolve × 2.
                    val flashPos = flashWordPosition
                    if (flashPos != null && searchHitWash.alpha.value > 0f) {
                        val flashIndex = ayah.words.indexOfFirst { it.position == flashPos }
                        val range = rendered.wordRanges.getOrNull(flashIndex)
                        if (range != null) {
                            blooms += ShapedWordBloom.ColorReveal(
                                range = range,
                                progress = searchHitWash.progress.value,
                                color = palette.repeatInkColor,
                                restingAlpha = 0f,
                                layerAlpha = searchHitWash.alpha.value,
                                colorAlpha = InkEngine.tuning.repeatInkAlpha,
                            )
                        }
                    }
                    blooms
                },
                layout = { layoutResult },
                rtl = true,
                feather = InkEngine.tuning.washFeather,
            )
            .then(
                if (onWordClick == null) {
                    Modifier.quietClickable(onClick = onAyahClick)
                } else {
                    Modifier.wordTapTarget(
                        words = ayah.words,
                        ranges = rendered.wordRanges,
                        layoutResult = layoutResult,
                        hitSlopPx = hitSlopPx,
                        onWordClick = onWordClick,
                        onWordLongClick = onWordLongClick,
                        onMiss = onAyahClick,
                        markRange = rendered.markRange,
                        onMarkLongClick = onMarkLongClick,
                    )
                },
            ),
        onTextLayout = { layoutResult = it },
    )
}

/** Marks every occurrence of [query] in [text] with a soft gold wash. */
private fun highlightMatches(text: String, query: String?, mark: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        if (query.isNullOrEmpty()) return@buildAnnotatedString
        var i = text.indexOf(query, ignoreCase = true)
        while (i >= 0) {
            addStyle(SpanStyle(background = mark), i, i + query.length)
            i = text.indexOf(query, i + query.length, ignoreCase = true)
        }
    }

/** Quiet typographic ayah marker: ornate brackets leafed in gradient gold. */
@Composable
fun AyahNumberMark(
    number: Int,
    fontScale: Float,
    verticalNudge: Dp = 0.dp,
    useArabicIndicDigits: Boolean = true,
) {
    val accents = LocalQuranAccents.current
    Text(
        text = "﴿${if (useArabicIndicDigits) number.toArabicIndic() else number.toString()}﴾",
        fontFamily = HafsFontFamily,
        fontSize = 20.sp * fontScale,
        color = accents.gold,
        modifier = Modifier
            .offset(y = verticalNudge)
            .gilded(
                bright = accents.goldBright.copy(alpha = 0.9f),
                deep = accents.goldDeep.copy(alpha = 0.9f),
            ),
    )
}

@Composable
private fun ArabicAyahNumberUnit(
    number: Int,
    fontScale: Float,
    onLongClick: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val arabicLineHeight = with(density) {
        (ArabicWordStyle.fontSize * fontScale * 1.9f).toDp()
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .requiredHeight(arabicLineHeight)
            .then(
                if (onLongClick != null) {
                    Modifier.pointerInput(onLongClick) {
                        detectTapGestures(onLongPress = { onLongClick() })
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AyahNumberMark(number, fontScale)
    }
}

/**
 * The reader's marginal note for one verse: italic EB Garamond below the
 * translation. When [isEditing], a chromeless [BasicTextField] takes focus so
 * the reader writes in place. Tap away to commit; empty text = no note.
 */
/**
 * The reader's own hand: Cormorant Garamond Italic, a chancery cursive that is
 * a genuinely *different* hand from the app's EB Garamond prose — not the same
 * voice leaning into emphasis. See [ScribeFontFamily] for why.
 *
 * Set a touch larger than the old EB italic (16 sp) because Cormorant runs
 * small on the body, and with slightly open letterspacing so the fine strokes
 * keep their air. Shared by the reader's verse note and the Bookmarks index.
 *
 * [fontScale] mirrors the translation's damped scaling so the note grows with
 * the page instead of staying pinned at one size when the reader sizes type up.
 */
@Composable
internal fun verseAnnotationStyle(
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = 23.sp,
    fontScale: Float = 1f,
): TextStyle {
    val damped = 0.9f + 0.1f * fontScale
    return MaterialTheme.typography.bodyMedium.copy(
        fontFamily = ScribeFontFamily,
        fontWeight = FontWeight.Medium,
        fontStyle = FontStyle.Italic,
        fontSize = fontSize * damped,
        lineHeight = lineHeight * damped,
        letterSpacing = 0.15.sp,
        fontFeatureSettings = "'kern' 1, 'liga' 1, 'onum' 1",
    )
}

/** Pale annotation prose remains clearly legible without reading as scripture. */
internal const val VERSE_ANNOTATION_INK_ALPHA = 0.85f

/** The ruby mark itself — always full ink, never halved with the prose. */
private const val ANNOTATION_MARK_ALPHA = 0.92f

/** The rule marking an annotation's left edge, and the wider lane it opens into
 * while editing so a delete mark can stand where the rule was. */
private val ANNOTATION_RULE_WIDTH = 2.dp
private val ANNOTATION_RULE_GAP = 12.dp
private val ANNOTATION_DELETE_LANE = 20.dp

/** Annotations leaving / returning to the page when recitation starts and stops.
 * Matches the verse ink recess so the whole sheet settles as one move. */
private const val ANNOTATION_FADE_MS = 400

/** Rule ⇄ delete-mark crossfade when the editor opens and closes. */
private const val ANNOTATION_EDIT_FADE_MS = 220

/** Paper kept between the line being written and the top of the keyboard.
 * The focus engine includes it in the field's keyboard-safe landing. */
private val ANNOTATION_KEYBOARD_CLEARANCE = 16.dp

/**
 * The reader's marginal note for one verse, set in the scribe's hand below the
 * translation. When [isEditing], a chromeless [BasicTextField] takes focus so
 * the reader writes in place; otherwise the settled note is shown and tapping
 * it reopens the editor. Blank text on commit deletes the note.
 */
@Composable
internal fun VerseAnnotationField(
    text: String,
    isEditing: Boolean,
    fontScale: Float,
    translationRecess: () -> Float,
    onAnnotationChange: ((String) -> Unit)?,
    onEditDone: () -> Unit = {},
    onStartEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    listCoordinates: () -> LayoutCoordinates? = { null },
    onKeepInView: OnKeepAnnotationInView? = null,
) {
    val accents = LocalQuranAccents.current
    val markInk = accents.bookmarkRibbon
    val noteInk = accents.annotationInk
    val noteStyle = verseAnnotationStyle(fontScale = fontScale)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Own the field as TextFieldValue so the caret opens at the end of any
    // existing note. A plain String value lands the caret at 0, which forces
    // the reader to arrow through text they already wrote.
    var fieldValue by remember(isEditing) {
        mutableStateOf(TextFieldValue(text, selection = TextRange(text.length)))
    }
    // `onFocusChanged` delivers an initial callback the moment the field
    // attaches, with isFocused = false — *before* the LaunchedEffect below can
    // request focus. Treating that as "the reader tapped away" committed an
    // empty note and closed the editor in the frame it opened, so the field
    // flashed and vanished. Only a loss that follows a real gain ends the edit.
    var everFocused by remember(isEditing) { mutableStateOf(false) }
    LaunchedEffect(isEditing) {
        if (isEditing) focusRequester.requestFocus()
    }

    // Re-measure as the field grows or the IME animates, then let the focus
    // engine calculate and serialize the keyboard-safe scroll.
    var fieldCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val view = LocalView.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(isEditing, imeBottom, fieldSize, fieldCoordinates, onKeepInView) {
        if (!isEditing || fieldSize.height == 0 || onKeepInView == null) return@LaunchedEffect
        val clearance = with(density) { ANNOTATION_KEYBOARD_CLEARANCE.toPx() }
        val list = listCoordinates()?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val listTop = list.localToWindow(Offset.Zero).y
        val listBottom = list.localToWindow(Offset(0f, list.size.height.toFloat())).y
        val keyboardOverlap = FocusEngine.keyboardOverlapPx(
            listBottomInWindowPx = listBottom,
            windowHeightPx = view.height.toFloat(),
            keyboardInsetPx = imeBottom.toFloat(),
        )
        onKeepInView(keyboardOverlap, clearance) {
            val field = fieldCoordinates?.takeIf { it.isAttached } ?: return@onKeepInView null
            // boundsInWindow clips off-screen descendants, underestimating how
            // far a note below a long verse must travel. Transform both edges.
            val top = field.localToWindow(Offset.Zero).y - listTop
            val bottom = field.localToWindow(Offset(0f, field.size.height.toFloat())).y - listTop
            top to bottom
        }
    }

    // While writing, the margin rule gives its place up to a delete mark: the
    // one destructive action lives exactly where the annotation's own mark was,
    // so it can never be mistaken for anything on the verse itself.
    val editing by animateFloatAsState(
        targetValue = if (isEditing) 1f else 0f,
        animationSpec = tween(ANNOTATION_EDIT_FADE_MS),
        label = "annotationEditMark",
    )
    val laneWidth by animateDpAsState(
        targetValue = if (isEditing) ANNOTATION_DELETE_LANE else ANNOTATION_RULE_WIDTH,
        animationSpec = tween(ANNOTATION_EDIT_FADE_MS),
        label = "annotationLane",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onSizeChanged { fieldSize = it }
            .onGloballyPositioned { fieldCoordinates = it }
            .graphicsLayer { if (!isEditing) alpha = translationRecess() },
    ) {
        Box(
            modifier = Modifier
                .width(laneWidth)
                .fillMaxHeight()
                .then(
                    if (isEditing && onDelete != null) {
                        Modifier.quietClickable(
                            role = Role.Button,
                            onClick = onDelete,
                        )
                    } else {
                        Modifier
                    },
                )
                .semantics { if (isEditing) contentDescription = "Delete this annotation" },
        ) {
            // The rule: a stroke down the annotation's left edge, marking the
            // block the way a scribe rules the margin beside a gloss.
            Canvas(Modifier.fillMaxSize()) {
                val ruleAlpha = (1f - editing) * ANNOTATION_MARK_ALPHA
                if (ruleAlpha > 0.01f) {
                    val x = ANNOTATION_RULE_WIDTH.toPx() / 2f
                    drawLine(
                        color = markInk.copy(alpha = ruleAlpha),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = ANNOTATION_RULE_WIDTH.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                // The delete mark: a drawn ruby cross on the first line, never
                // an icon in a container.
                if (editing > 0.01f) {
                    val arm = 5.dp.toPx()
                    val cx = size.width / 2f
                    val cy = noteStyle.lineHeight.toPx() / 2f
                    val stroke = 1.8.dp.toPx()
                    val color = markInk.copy(alpha = editing * ANNOTATION_MARK_ALPHA)
                    drawLine(
                        color,
                        Offset(cx - arm, cy - arm),
                        Offset(cx + arm, cy + arm),
                        stroke,
                        StrokeCap.Round,
                    )
                    drawLine(
                        color,
                        Offset(cx + arm, cy - arm),
                        Offset(cx - arm, cy + arm),
                        stroke,
                        StrokeCap.Round,
                    )
                }
            }
        }
        Spacer(Modifier.width(ANNOTATION_RULE_GAP))
        if (isEditing) {
            BasicTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    onAnnotationChange?.invoke(it.text)
                },
                textStyle = noteStyle.copy(color = noteInk.copy(alpha = VERSE_ANNOTATION_INK_ALPHA)),
                cursorBrush = SolidColor(markInk),
                // Notes wrap freely, but the keyboard's Done key is the reader's
                // way out — the page itself still offers no Save button.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            everFocused = true
                            // Re-assert end caret on focus: the IME can reset
                            // selection when it attaches after requestFocus().
                            val end = fieldValue.text.length
                            if (fieldValue.selection != TextRange(end)) {
                                fieldValue = fieldValue.copy(selection = TextRange(end))
                            }
                        } else if (everFocused) {
                            onEditDone()
                        }
                    },
                decorationBox = { field ->
                    Box {
                        if (fieldValue.text.isEmpty()) {
                            Text(
                                "Write a note…",
                                style = noteStyle,
                                color = noteInk.copy(alpha = 0.42f),
                            )
                        }
                        field()
                    }
                },
            )
        } else {
            Text(
                text = text,
                style = noteStyle,
                color = noteInk.copy(alpha = VERSE_ANNOTATION_INK_ALPHA),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onStartEdit != null) {
                            Modifier.quietClickable(onClick = onStartEdit)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/**
 * One ayah on the sheet. In Arabic mode the words flow right-to-left with the
 * English gloss beneath each word; in English mode the gloss itself becomes
 * the lyric line, flowing left-to-right. Either way the letters fade in and
 * out with the recitation — no blocks, no backgrounds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AyahBlock(
    ayah: Ayah,
    readingMode: ReadingMode,
    activeWord: ActiveWord?,
    playbackSpeed: Float,
    isActiveAyah: Boolean,
    dimmed: Boolean,
    obscuredBySelector: Boolean,
    fontScale: Float,
    showGloss: Boolean,
    showTransliteration: Boolean,
    showTranslation: Boolean,
    searchQuery: String? = null,
    /** 1-based word to orange-flash (home search hit); null = no flash. */
    flashWordPosition: Int? = null,
    keepActiveWordInView: Boolean = false,
    /** LazyColumn layout coords — used to map the active word into viewport
     * space for word-band follow. */
    listCoordinates: () -> LayoutCoordinates? = { null },
    /** Hands a live word-bounds measure to the focus controller. */
    onKeepWordInView: OnKeepWordInView? = null,
    /** Hands the editing note's live bounds to the focus controller. */
    onKeepAnnotationInView: OnKeepAnnotationInView? = null,
    /** Bookmark ribbon lives in this block's outer margin (opposite the
     * ayah selector). Null hides the ribbon entirely. While gathering,
     * [gatherOrdinal] reuses this margin instead of the ribbon. */
    bookmarkSide: AyahSelectorSide? = null,
    bookmarked: Boolean = false,
    bookmarkFocused: Boolean = false,
    bookmarkChromeAlpha: () -> Float = { 1f },
    bookmarkInteractive: Boolean = true,
    onToggleBookmark: (() -> Boolean)? = null,
    /**
     * 1-based gather ordinal drawn in the outer margin (gold Arabic-Indic).
     * Non-null only while gather mode has this verse selected.
     */
    gatherOrdinal: Int? = null,
    onWordClick: ((Word) -> Unit)?,
    onWordLongClick: ((Word) -> Unit)? = null,
    onAyahClick: () -> Unit,
    /** Text to display: the saved note when idle, the in-progress draft while editing. */
    annotationText: String? = null,
    /** True while the reader is composing a note on this specific verse. */
    isEditingAnnotation: Boolean = false,
    /** Called with each keystroke to update the draft in the caller. */
    onAnnotationChange: ((String) -> Unit)? = null,
    /** Called when the editor loses focus — caller should commit and clear edit state. */
    onAnnotationEditDone: (() -> Unit)? = null,
    /** Opens the editor: long-press on the gold ayah mark, or a tap on a
     * settled note to revise it. */
    onAyahMarkLongClick: (() -> Unit)? = null,
    /** True while recitation is running: annotations leave the page entirely, the
     * same rule the ribbon and rail marks follow. */
    annotationsHidden: Boolean = false,
    /** Clears this verse's annotation from the delete mark in the editor. */
    onAnnotationDelete: (() -> Unit)? = null,
    /** True while *another* verse is being annotated: this one recedes so the
     * page holds only the verse being written on. */
    recededForAnnotationEdit: Boolean = false,
) {
    fun hits(word: Word) =
        searchQuery != null && word.translation.contains(searchQuery, ignoreCase = true)
    // Non-active ayahs recede while another is being recited. Dim is applied
    // at the word level (Upcoming ink / paper cover) in every mode so ayah
    // handoff does not brighten the verse — block alpha stays at 1 except
    // when the selector obscures the page. Soft tween when receding; snap
    // when becoming the lyric line.
    val blockAlpha = animateFloatAsState(
        targetValue = when {
            obscuredBySelector -> 0.07f
            // Writing on a verse quiets the rest of the sheet so the page is
            // only the verse being annotated and the hand writing on it.
            recededForAnnotationEdit -> 0.14f
            else -> 1f
        },
        animationSpec = when {
            obscuredBySelector -> tween(600)
            recededForAnnotationEdit -> tween(ANNOTATION_FADE_MS)
            else -> tween(ANNOTATION_FADE_MS)
        },
        label = "ayahAlpha",
    )

    // The letter fade paces itself to how long the reciter dwells on the
    // word, corrected for the chosen playback speed.
    val sweepMs = InkEngine.sweepMs(activeWord, playbackSpeed)
    val activation = activeWord?.activation ?: 0L

    // Letter-level tajweed pacing of that sweep (Ink Lab toggle,
    // docs/TAJWEED_PACING.md): null keeps the plain constant-rate wash.
    // Gated on this ayah owning the active word — not the fade-led
    // isActiveAyah — so a waqf hold is not dropped 500 ms before the audio
    // boundary (see InkEngine.wordState). The verse-closing word is flagged
    // so its waqf can be sustained.
    val pacing = activeWord
        ?.let { aw ->
            val idx = ayah.words.indexOfFirst { it.position == aw.wordPosition }
            ayah.words.getOrNull(idx)?.let { word ->
                InkEngine.pacing(
                    arabic = word.arabic,
                    activeWord = aw,
                    isAyahFinal = word.position == ayah.words.lastOrNull()?.position,
                    prevArabic = ayah.words.getOrNull(idx - 1)?.arabic,
                    nextArabic = ayah.words.getOrNull(idx + 1)?.arabic,
                )
            }
        }

    // Each word's ink behaviour, derived once for the whole ayah. All the
    // policy (upcoming/active/recited/high-water, repeat chain) lives in
    // InkEngine; the render branches below only draw what it decided.
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
    SideEffect {
        previousActive.index = activeIndex
        previousActive.activation = activation
    }
    val activeRevealStart = if (carriedIncoming && incomingConnection != null) {
        waslContinuationStart(
            prefixFraction = incomingConnection.prefixFraction,
            mainFeather = if (pacing != null) {
                InkEngine.pacedFeather()
            } else {
                InkEngine.tuning.washFeather
            },
        )
    } else {
        0f
    }
    val fullWaslProgress = remember { mutableStateOf(1f) }
    val waslPrefixes = ayah.words.indices.map { index ->
        when {
            // Preserve a completed outgoing bloom only across the natural
            // adjacent handoff. A seek bumps activation and starts clean.
            index == activeIndex && carriedIncoming && incomingConnection != null ->
                WaslPrefix(incomingConnection.prefixFraction, fullWaslProgress)
            index == activeIndex + 1 && outgoingConnection != null ->
                WaslPrefix(outgoingConnection.prefixFraction, outgoingProgress)
            else -> null
        }
    }

    // Shared across gloss, English, and Arabic-only: mark sits at upcoming
    // ink while recessed, then fades up to full when this verse is in focus.
    val ayahMarkAlpha = rememberAyahMarkAlpha(focused = !dimmed)
    // Translation recess matches word ink. Animate a 0..1 multiplier read
    // only in graphicsLayer — never in composition (docs/PERFORMANCE.md).
    val translationRecess = animateFloatAsState(
        targetValue = if (dimmed) InkEngine.State.Upcoming.inkAlpha() else 1f,
        animationSpec = tween(InkEngine.tuning.inkFadeMs, easing = FastOutSlowInEasing),
        label = "translationRecess",
    )

    // The ribbon is part of the verse block itself — same Box, same height —
    // so it never "follows" from a floating overlay. Text keeps the existing
    // horizontal inset; the ribbon sits in the outer margin opposite the
    // ayah selector.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = blockAlpha.value },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    // Extra room on the bookmark ribbon's side so its tip
                    // doesn't crowd the verse text.
                    start = if (bookmarkSide == AyahSelectorSide.LEFT) 38.dp else 28.dp,
                    end = if (bookmarkSide == AyahSelectorSide.RIGHT) 38.dp else 28.dp,
                    top = 14.dp,
                    bottom = 14.dp,
                ),
        ) {
            if (readingMode == ReadingMode.ENGLISH_ONLY) {
                ResponsiveEnglishAyah(
                    ayah = ayah,
                    inks = inks,
                    markAlpha = { ayahMarkAlpha.value },
                    fontScale = fontScale,
                    activeSweepMs = sweepMs,
                    activation = activation,
                    searchQuery = searchQuery,
                    flashWordPosition = flashWordPosition,
                    keepActiveWordInView = keepActiveWordInView,
                    listCoordinates = listCoordinates,
                    onKeepWordInView = onKeepWordInView,
                    onAyahClick = onAyahClick,
                    onWordClick = onWordClick,
                    onWordLongClick = onWordLongClick,
                    onMarkLongClick = onAyahMarkLongClick,
                )
            } else if (showGloss) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(if (showGloss) 12.dp else 4.dp),
                    ) {
                        ayah.words.forEachIndexed { index, word ->
                            val ink = inks[index]
                            val isActiveWord = ink.state == InkEngine.State.Active
                            val flashing = flashWordPosition == word.position
                            WordUnit(
                                word = word,
                                ink = ink,
                                fontScale = fontScale,
                                sweepMs = sweepMs.takeIf { isActiveWord },
                                pacing = pacing.takeIf { isActiveWord },
                                revealStart = activeRevealStart.takeIf {
                                    isActiveWord
                                } ?: 0f,
                                waslPrefix = waslPrefixes[index],
                                activation = if (isActiveWord) activation else 0L,
                                showGloss = showGloss,
                                showTransliteration = showTransliteration,
                                searchHit = hits(word),
                                keepInView = keepActiveWordInView && isActiveWord,
                                listCoordinates = listCoordinates,
                                onKeepWordInView = onKeepWordInView,
                                onClick = onWordClick?.let { handler -> { handler(word) } },
                                onLongClick = onWordLongClick?.let { handler -> { handler(word) } },
                                showFlash = flashing,
                            )
                        }
                        Box(
                            modifier = Modifier.graphicsLayer { alpha = ayahMarkAlpha.value },
                        ) {
                            ArabicAyahNumberUnit(ayah.number, fontScale, onLongClick = onAyahMarkLongClick)
                        }
                    }
                }
            } else {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ResponsiveHafsAyah(
                        ayah = ayah,
                        inks = inks,
                        // Same faint cover while another ayah is playing, so
                        // landing on this verse does not change unread ink.
                        dimmed = dimmed,
                        markAlpha = { ayahMarkAlpha.value },
                        fontSize = ArabicWordStyle.fontSize * fontScale * ARABIC_ONLY_HAFS_FONT_MULTIPLIER,
                        activeSweepMs = sweepMs,
                        pacing = pacing,
                        activeRevealStart = activeRevealStart,
                        waslPrefixes = waslPrefixes,
                        activation = activation,
                        flashWordPosition = flashWordPosition,
                        keepActiveWordInView = keepActiveWordInView,
                        listCoordinates = listCoordinates,
                        onKeepWordInView = onKeepWordInView,
                        onAyahClick = onAyahClick,
                        onWordClick = onWordClick?.let { handler -> { word -> handler(word) } },
                        onWordLongClick = onWordLongClick?.let { handler -> { word -> handler(word) } },
                        onMarkLongClick = onAyahMarkLongClick,
                    )
                }
            }
            if (showTranslation && readingMode == ReadingMode.ARABIC_ENGLISH) {
                Spacer(Modifier.height(12.dp))
                // Block alpha stays 1 while recessed (word-level dim); the
                // translation still needs to recede with the verse.
                Text(
                    text = highlightMatches(
                        text = ayah.translation,
                        query = searchQuery,
                        mark = LocalQuranAccents.current.gold.copy(alpha = 0.28f),
                    ),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = TranslationFontFamily,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * (0.9f + 0.1f * fontScale),
                        lineHeight = 26.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = translationRecess.value }
                        .quietClickable(onClick = onAyahClick),
                )
            }
            // Reciting clears annotation off the sheet so only scripture is
            // left under the voice; it grows back when the voice stops.
            // Editing always wins — writing in progress never vanishes because
            // playback happened to start.
            AnimatedVisibility(
                visible = (isEditingAnnotation || annotationText != null) &&
                    (isEditingAnnotation || !annotationsHidden),
                enter = fadeIn(tween(ANNOTATION_FADE_MS)) +
                    expandVertically(tween(ANNOTATION_FADE_MS)),
                exit = fadeOut(tween(ANNOTATION_FADE_MS)) +
                    shrinkVertically(tween(ANNOTATION_FADE_MS)),
            ) {
                Column {
                    // The note is a different voice, so it earns at least the
                    // air the translation takes from the Arabic above it.
                    Spacer(Modifier.height(12.dp))
                    VerseAnnotationField(
                        text = annotationText ?: "",
                        isEditing = isEditingAnnotation,
                        fontScale = fontScale,
                        translationRecess = { translationRecess.value },
                        onAnnotationChange = onAnnotationChange,
                        onEditDone = { onAnnotationEditDone?.invoke() },
                        onStartEdit = onAyahMarkLongClick,
                        onDelete = onAnnotationDelete,
                        listCoordinates = listCoordinates,
                        onKeepInView = onKeepAnnotationInView,
                    )
                }
            }
            // Whitespace is the divider.
            Spacer(Modifier.height(if (readingMode == ReadingMode.ENGLISH_ONLY) 18.dp else 26.dp))
        }

        if (gatherOrdinal != null && bookmarkSide != null) {
            Box(Modifier.matchParentSize()) {
                GatherOrdinalMark(
                    ordinal = gatherOrdinal,
                    side = bookmarkSide,
                    chromeAlpha = bookmarkChromeAlpha,
                    modifier = Modifier
                        .align(
                            if (bookmarkSide == AyahSelectorSide.RIGHT) {
                                AbsoluteAlignment.TopRight
                            } else {
                                AbsoluteAlignment.TopLeft
                            },
                        )
                        // Align with first ink line (ribbon tip uses ~24 dp).
                        .padding(top = 22.dp),
                )
            }
        } else if (bookmarkSide != null && onToggleBookmark != null) {
            // matchParentSize (not fillMaxHeight): the ayah Box is wrap-content,
            // so fillMaxHeight would measure to 0 and the ribbon would vanish.
            // This sizes to the Column after layout, keeping the ribbon in-block.
            Box(Modifier.matchParentSize()) {
                VerseBookmarkRibbon(
                    bookmarked = bookmarked,
                    focused = bookmarkFocused,
                    side = bookmarkSide,
                    chromeAlpha = bookmarkChromeAlpha,
                    interactive = bookmarkInteractive,
                    onToggle = onToggleBookmark,
                    modifier = Modifier
                        .align(
                            if (bookmarkSide == AyahSelectorSide.RIGHT) {
                                AbsoluteAlignment.TopRight
                            } else {
                                AbsoluteAlignment.TopLeft
                            },
                        )
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * Gold Arabic-Indic ordinal in the verse's outer margin while gather mode is
 * active. Replaces the bookmark ribbon for the duration of the mode so the
 * margin never carries two marks. Sized to read as a mark, not decoration.
 */
@Composable
private fun GatherOrdinalMark(
    ordinal: Int,
    side: AyahSelectorSide,
    chromeAlpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val gold = LocalQuranAccents.current.gold
    Text(
        text = ordinal.toArabicIndic(),
        style = MaterialTheme.typography.headlineSmall,
        color = gold,
        textAlign = if (side == AyahSelectorSide.RIGHT) TextAlign.End else TextAlign.Start,
        modifier = modifier
            .width(44.dp)
            .graphicsLayer { alpha = chromeAlpha() }
            .padding(horizontal = 6.dp),
    )
}

/**
 * Shared chapter opening — the weave, medallion, and title used by both the
 * real [SurahHeader] and the end-of-chapter invitation so a continuous advance
 * can hand one off as the other without a pattern or type flash.
 *
 * [rosetteScale] is paint-only (graphicsLayer) so invitation polish never
 * thrash layout height during a scroll handoff.
 *
 * Handoff layers are split so a flyer↔header crossfade can keep **one**
 * constant-strength weave and medallion (both double if stacked) while only
 * the titles use complementary alphas that sum to 1.
 */
@Composable
fun ChapterOpening(
    chapterNumber: Int,
    nameArabic: String,
    nameTransliteration: String,
    nameTranslation: String,
    revelationPlace: String,
    ayahCount: Int,
    sheen: State<Float>,
    modifier: Modifier = Modifier,
    /** Tighter bottom when a basmalah block follows (real header only). */
    compactBottom: Boolean = surahOpensWithBasmalahPreface(chapterNumber),
    rosetteScale: Float = 1f,
    rosetteAlpha: Float = 1f,
    /** When false, layout is unchanged but the Hankin field is not painted. */
    showFieldWeave: Boolean = true,
    /**
     * When false, medallion slot is kept (same height) but not painted — used
     * so only one full-strength rosette exists during a handoff crossfade.
     */
    showRosette: Boolean = true,
    /** Opacity of titles only — not the field weave or medallion. */
    contentAlpha: Float = 1f,
) {
    val accents = LocalQuranAccents.current
    val weaveFade = MaterialTheme.colorScheme.background
    val ornament = remember(chapterNumber, ayahCount) {
        generateChapterOrnament(chapterOrnamentSeed(chapterNumber, ayahCount))
    }
    val scale = rosetteScale.coerceIn(0.5f, 1.2f)
    val titles = contentAlpha.coerceIn(0f, 1f)
    // Weave + medallion are full-strength when owned; only titles take contentAlpha.
    Box(modifier = modifier.fillMaxWidth()) {
        if (showFieldWeave) {
            Box(
                Modifier
                    .matchParentSize()
                    .generatedFieldWeave(
                        field = ornament.field,
                        ink = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f),
                        embossLight = accents.embossLight.copy(alpha = 0.05f),
                    )
                    .verticalFadingEdges(color = weaveFade, top = 12.dp, bottom = 36.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = 36.dp,
                    bottom = if (compactBottom) 8.dp else 30.dp,
                    start = 24.dp,
                    end = 24.dp,
                ),
        ) {
            // Always reserve the 52.dp slot so handoff layout doesn't jump when
            // ownership moves from flyer → settled header.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp),
            ) {
                if (showRosette) {
                    GeneratedChapterRosette(
                        spec = ornament.rosette,
                        size = 52.dp,
                        brightGold = accents.goldBright,
                        deepGold = accents.goldDeep,
                        embossDark = accents.embossDark,
                        embossLight = accents.embossLight,
                        sheen = sheen,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = rosetteAlpha.coerceIn(0f, 1f)
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = titles },
            ) {
                Text(
                    text = "سُورَةُ $nameArabic",
                    style = ArabicTitleStyle,
                    fontSize = 32.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "$nameTransliteration · $nameTranslation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Chapter $chapterNumber · ${revelationPlace.replaceFirstChar { it.uppercase() }} · $ayahCount ayahs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/**
 * Surah opening: quiet centered typography over a whisper-faint embossed
 * star-and-cross weave, crowned by a gilded eight-fold rosette whose sheen
 * shifts with the page ([sheen] is read at draw time only). The chapter-opening
 * basmalah (when present) is a separate list item beneath this header so the
 * focus engine can home onto it independently — see [BasmalahBlock].
 */
@Composable
fun SurahHeader(
    chapterNumber: Int,
    nameArabic: String,
    nameTransliteration: String,
    nameTranslation: String,
    revelationPlace: String,
    ayahCount: Int,
    sheen: State<Float>,
) {
    ChapterOpening(
        chapterNumber = chapterNumber,
        nameArabic = nameArabic,
        nameTransliteration = nameTransliteration,
        nameTranslation = nameTranslation,
        revelationPlace = revelationPlace,
        ayahCount = ayahCount,
        sheen = sheen,
    )
}

/**
 * Chapter-opening basmalah as its own LazyColumn item — the focus engine's
 * target while the lead-in clip plays ([BASMALAH_PLAYLIST_AYAH]). Kept separate
 * from [SurahHeader] so placement, lyric-follow, and return-to-verse use the
 * calligraphy's own geometry (same path as any verse), not the taller title block.
 */
@Composable
fun BasmalahBlock(
    active: Boolean,
    dimmed: Boolean,
    washProgress: StateFlow<Float?>? = null,
    onClick: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp),
    ) {
        BasmalahCalligraphy(
            active = active,
            dimmed = dimmed,
            washProgress = washProgress,
            onClick = onClick,
        )
    }
}

/**
 * The surah name as it reappears in the top bar once the opening header has
 * scrolled off the page: flanked by gilded khatam flourishes, with the
 * transliteration whispered beneath. [sheen] keeps the gold lit in step with
 * the header rosette.
 */
@Composable
fun OrnateSurahTitle(
    chapterNumber: Int,
    nameArabic: String,
    nameTransliteration: String,
    sheen: State<Float>,
) {
    val accents = LocalQuranAccents.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        GildedFlourish(
            width = 36.dp,
            height = 13.dp,
            brightGold = accents.goldBright,
            deepGold = accents.goldDeep,
            embossDark = accents.embossDark,
            embossLight = accents.embossLight,
            sheen = sheen,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(
                text = "سُورَةُ $nameArabic",
                style = ArabicTitleStyle,
                fontSize = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = "$chapterNumber · ${nameTransliteration.uppercase()}",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
        GildedFlourish(
            width = 36.dp,
            height = 13.dp,
            brightGold = accents.goldBright,
            deepGold = accents.goldDeep,
            embossDark = accents.embossDark,
            embossLight = accents.embossLight,
            sheen = sheen,
            mirrored = true,
        )
    }
}

/**
 * Subtle page break: Arabic modes place Western and Arabic-Indic page numbers
 * at opposite ends of a thin gold line. English-only centers one Western page
 * number between equal rules, matching the web reader.
 */
@Composable
fun PageBreak(page: Int, useArabicIndicDigits: Boolean = true) {
    val accents = LocalQuranAccents.current
    val pageNumberSize = 12.sp
    val pageNumberColor = accents.gold.copy(alpha = 0.68f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!useArabicIndicDigits) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    thickness = 1.dp,
                    color = accents.gold.copy(alpha = 0.36f),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = page.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = pageNumberSize,
                color = pageNumberColor,
            )
            Spacer(Modifier.width(8.dp))
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 0.5.dp,
                color = accents.gold.copy(alpha = if (useArabicIndicDigits) 0.2f else 0.36f),
            )
            if (useArabicIndicDigits) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = page.toArabicIndic(),
                    // Keep the Arabic-Indic digits at the same 12sp as the Western
                    // numeral, but ask for a serif fallback so they stay in the
                    // same family class as the EB Garamond label.
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Serif,
                    ),
                    fontSize = pageNumberSize,
                    color = pageNumberColor,
                )
            }
        }
    }
}

/**
 * End-of-chapter invitation built around the same [ChapterOpening] as the real
 * header. Invitation chrome (air, "NEXT", Continue pill) only fades via alpha —
 * heights stay fixed so measuring/sliding the opening never jumps.
 *
 * [headerMorph] 0 = full invitation, 1 = chrome faded (opening ready to fly).
 * [pullProgress] fills the Continue pill during bottom overscroll.
 * [openingAlpha] fades the in-list opening while a flying overlay carries it.
 * [onOpeningPositioned] reports the opening block for the slide animation.
 */
@Composable
fun NextChapterFooter(
    chapterNumber: Int,
    nameArabic: String,
    nameTransliteration: String,
    nameTranslation: String,
    revelationPlace: String,
    ayahCount: Int,
    sheen: State<Float>,
    onOpen: () -> Unit,
    enabled: Boolean = true,
    pullProgress: Float = 0f,
    headerMorph: Float = 0f,
    openingAlpha: Float = 1f,
    onOpeningPositioned: ((LayoutCoordinates) -> Unit)? = null,
) {
    val accents = LocalQuranAccents.current
    val morph = FastOutSlowInEasing.transform(headerMorph.coerceIn(0f, 1f))
    val invite = (1f - morph).coerceIn(0f, 1f)
    val openA = openingAlpha.coerceIn(0f, 1f)
    val rosetteScale = 40f / 52f + (1f - 40f / 52f) * morph
    val rosetteAlpha = (0.88f + 0.12f * morph) * openA

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Fixed-height invitation chrome — alpha only, no layout thrash.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .graphicsLayer { alpha = invite },
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "NEXT",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                fontSize = 10.sp,
                color = accents.gold.copy(alpha = 0.55f),
            )
        }

        // Pixel-identical opening to [SurahHeader] — the continuous handoff target.
        ChapterOpening(
            chapterNumber = chapterNumber,
            nameArabic = nameArabic,
            nameTransliteration = nameTransliteration,
            nameTranslation = nameTranslation,
            revelationPlace = revelationPlace,
            ayahCount = ayahCount,
            sheen = sheen,
            compactBottom = false,
            rosetteScale = rosetteScale,
            rosetteAlpha = rosetteAlpha,
            modifier = Modifier
                .graphicsLayer { alpha = openA }
                .then(
                    if (onOpeningPositioned != null) {
                        Modifier.onGloballyPositioned(onOpeningPositioned)
                    } else {
                        Modifier
                    },
                ),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .graphicsLayer { alpha = invite },
        ) {
            Spacer(Modifier.height(22.dp))
            NextChapterOpenPill(
                chapterName = nameTransliteration,
                onClick = onOpen,
                enabled = enabled && invite > 0.45f,
                fillProgress = pullProgress,
            )
        }
    }
}

/**
 * Quiet green stadium control for chapter advance. Soft accent wash at rest;
 * [fillProgress] paints a left-to-right green fill so overscroll can read as a
 * progress bar. Tap still opens immediately.
 *
 * [actionLabel] is the visible verb ("Continue" / "Open"); [chevronDown] flips
 * the chevron for previous-chapter pull.
 */
@Composable
fun NextChapterOpenPill(
    chapterName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillProgress: Float = 0f,
    actionLabel: String = "Continue",
    chevronDown: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val label = "Open $chapterName"
    val fill = fillProgress.coerceIn(0f, 1f)
    // Ink flips to the fill's contrasting color once the wash covers the label.
    val contentColor = androidx.compose.ui.graphics.lerp(
        colors.primary,
        colors.onPrimary,
        ((fill - 0.32f) / 0.28f).coerceIn(0f, 1f),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(44.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .drawBehind {
                val h = size.height
                val r = h / 2f
                val capsule = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = size.width,
                            bottom = h,
                            cornerRadius = CornerRadius(r, r),
                        ),
                    )
                }
                // Quiet resting wash.
                drawPath(capsule, colors.primary.copy(alpha = 0.10f))
                // Progress fill — clipped stadium growing left → right.
                if (fill > 0f) {
                    clipRect(right = size.width * fill) {
                        drawPath(capsule, colors.primary)
                    }
                }
            }
            .quietClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
    ) {
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.6.sp),
            color = contentColor,
        )
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.size(18.dp)) {
            val stroke = Stroke(
                width = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (chevronDown) {
                    moveTo(w * 0.22f, h * 0.38f)
                    lineTo(w * 0.50f, h * 0.62f)
                    lineTo(w * 0.78f, h * 0.38f)
                } else {
                    moveTo(w * 0.22f, h * 0.62f)
                    lineTo(w * 0.50f, h * 0.38f)
                    lineTo(w * 0.78f, h * 0.62f)
                }
            }
            drawPath(path, contentColor, style = stroke)
        }
    }
}

/**
 * Top-of-chapter previous invitation. Hosted in a layout slot above the list
 * whose height tracks overscroll (not a behind-the-list draw layer).
 * [pullProgress] fills the Open pill.
 */
@Composable
fun PreviousChapterPullChrome(
    nameTransliteration: String,
    pullProgress: Float,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accents = LocalQuranAccents.current
    // Compact so the full invitation fits in the ~156.dp rubber band at full pull.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 14.dp),
    ) {
        Text(
            text = "PREVIOUS",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
            fontSize = 10.sp,
            color = accents.gold.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = nameTransliteration,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(12.dp))
        NextChapterOpenPill(
            chapterName = nameTransliteration,
            onClick = onOpen,
            enabled = enabled,
            fillProgress = pullProgress.coerceIn(0f, 1f),
            actionLabel = "Open",
            chevronDown = false,
        )
    }
}

/**
 * Opaque floating capsule after a Root Viewer concordance jump — stadium twin
 * of the return-to-ayah roundel (paper fill, gilt rim, drawn qalam arrow).
 * Hosted above the paper stack in MainActivity so it survives closing the
 * reader; tap returns, scroll or page-turn arms the 30s fade. See
 * docs/ROOT_VIEWER.md and docs/DESIGN.md.
 */
@Composable
fun BackToOriginPill(
    target: RootReturnTarget,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IslamicBackToOriginCapsule(
        chapterLabel = target.chapterLabel,
        ayahLabel = target.ayahLabel,
        contentDescription = target.label,
        onClick = onClick,
        modifier = modifier,
    )
}
