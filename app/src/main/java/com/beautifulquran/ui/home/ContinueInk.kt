package com.beautifulquran.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.PageTurnSounds
import com.beautifulquran.ui.theme.LocalNuqtaParams
import com.beautifulquran.ui.theme.NuqtaParams
import com.beautifulquran.ui.theme.drawInkWashCoats
import com.beautifulquran.ui.theme.inkCoatFingers
import com.beautifulquran.ui.theme.inkWashReachToCover
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * How far the paper stack has turned from the chapter list into the open
 * reader, 0..1 — 0 when no reader is open to turn to. Read it only while
 * drawing or in snapshot flows: it changes every frame of a swipe.
 */
val LocalReaderApproach = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * Ink that floods the continue row the moment the chapter list starts to turn
 * into the reader it continues. The wash plays out on its own clock whatever
 * the drag does. It stays wet under the reader. Returning, it stays wet
 * through the lift and the wipe is scrubbed by the same swipe that plays the
 * stems: it starts with the sweep and finishes the frame the drop — the
 * ending of the sound — begins. A turn let go short wipes the leftover. The
 * ink stays dense as it withdraws, so every word is plainly on ink or on
 * paper, never half-way.
 */
@Stable
internal class ContinueInk(val params: NuqtaParams) {
    /** How far the wash has spread, 0..1, eased. Read it only while drawing. */
    var spread by mutableFloatStateOf(0f)
        private set

    /** The wash's own clock, 0..1, before easing. */
    private var clock = 0f
    /** How far the wash has wiped back out of the row, 0..1, eased. */
    val dry = Animatable(0f)

    /** Tapped, and the turn it starts has not yet landed. */
    var tapped by mutableStateOf(false)

    val fingers = inkCoatFingers(params)

    /**
     * Runs the wash to full on its own clock. Each frame advances it by at
     * most [ContinueInkMaxFrameMs], so a long frame — the reader being built
     * as the turn starts — pauses the wash instead of skipping its flood.
     */
    suspend fun flood() {
        var last = withFrameNanos { it }
        while (clock < 1f) {
            withFrameNanos { now ->
                val ms = ((now - last) / 1_000_000f).coerceAtMost(ContinueInkMaxFrameMs)
                last = now
                clock = (clock + ms / ContinueInkSpreadMs).coerceAtMost(1f)
                spread = ContinueInkEasing.transform(clock)
            }
        }
    }

    /** Fully inked at once, for a row under the reader. */
    fun fill() {
        clock = 1f
        spread = 1f
    }

    fun clear() {
        clock = 0f
        spread = 0f
    }
}

private enum class ContinueTurn { Resting, Turning, Landed }

@Composable
internal fun rememberContinueInk(): ContinueInk {
    val params = LocalNuqtaParams.current
    val ink = remember(params) { ContinueInk(params) }
    val approach = LocalReaderApproach.current
    LaunchedEffect(ink, approach) {
        var fromReader = false
        snapshotFlow {
            val p = approach()
            when {
                p >= 1f - ContinueTurnEpsilon -> ContinueTurn.Landed
                p > ContinueTurnEpsilon || ink.tapped -> ContinueTurn.Turning
                else -> ContinueTurn.Resting
            }
        }.distinctUntilChanged().collectLatest { turn ->
            when (turn) {
                ContinueTurn.Turning -> coroutineScope {
                    if (fromReader && !ink.tapped) {
                        // Same clock as the stems: wet through the lift,
                        // wipe through the sweep, gone the frame the drop
                        // (the ending) starts.
                        snapshotFlow { continueWipeProgress(1f - approach()) }
                            .collect { ink.dry.snapTo(it) }
                    } else {
                        fromReader = false
                        // The wash plays out on its own clock. Turned again
                        // mid-wipe, the wipe runs back from where it stands, as
                        // fast as it came, while the flood carries on — every
                        // interruption picks up the ink exactly as it is.
                        launch {
                            ink.dry.animateTo(
                                0f,
                                tween(
                                    (ContinueInkDryMs * ink.dry.value).roundToInt(),
                                    easing = ContinueInkRewetEasing,
                                ),
                            )
                        }
                        ink.flood()
                    }
                }
                ContinueTurn.Landed -> {
                    // Under the reader the row stays wet, so turning back
                    // uncovers it inked and it dries with the sweep.
                    ink.tapped = false
                    fromReader = true
                    ink.fill()
                    ink.dry.snapTo(0f)
                }
                ContinueTurn.Resting -> {
                    fromReader = false
                    if (ink.spread > 0f) {
                        if (ink.dry.value < 1f) {
                            val wipeEasing =
                                if (ink.dry.value > 0f) ContinueInkRewetEasing else ContinueInkWipeEasing
                            ink.dry.animateTo(
                                1f,
                                tween(continueWipeMs(ink.dry.value), easing = wipeEasing),
                            )
                        }
                        ink.clear()
                        ink.dry.snapTo(0f)
                    }
                }
            }
        }
    }
    return ink
}

/**
 * How far the continue row has wiped for a return swipe of [turnProgress]
 * (0 at the reader, 1 at home). 0 through the lift, 1 the frame the drop
 * stem — the ending of the sound — begins.
 */
internal fun continueWipeProgress(turnProgress: Float): Float {
    val start = PageTurnSounds.SWEEP_AT
    val end = PageTurnSounds.DROP_AT
    return ((turnProgress - start) / (end - start)).coerceIn(0f, 1f)
}

/** Remaining leftover wipe, as a share of the sweep-to-drop window. */
internal fun continueWipeMs(dry: Float): Int =
    (ContinueInkDryMs * (1f - dry)).roundToInt().coerceAtLeast(0)

/** The wash itself, drawn behind the row, landing at its right (Arabic) end. */
internal fun Modifier.continueInkWash(ink: ContinueInk, color: Color): Modifier =
    clipToBounds().drawBehind { drawContinueInk(ink, color) }

/**
 * For a copy of the row's content in colours that read on the ink: shows it
 * only where the wash lies, and exactly as strongly as the wash is dense.
 */
internal fun Modifier.continueInkMask(ink: ContinueInk): Modifier = drawWithContent {
    if (ink.spread <= 0f) return@drawWithContent
    drawIntoCanvas { canvas ->
        val bounds = Rect(Offset.Zero, size)
        canvas.saveLayer(bounds, Paint())
        drawContent()
        // Its own layer so, composited DstIn, bare paper clears the copy too.
        canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstIn })
        drawContinueInk(ink, Color.Black)
        canvas.restore()
        canvas.restore()
    }
}

private fun DrawScope.drawContinueInk(ink: ContinueInk, color: Color) {
    val origin = Offset(size.width, size.height / 2f)
    fun wash() = drawInkWashCoats(
        t = ink.spread,
        dry = 0f,
        params = ink.params,
        fingers = ink.fingers,
        ink = color,
        origin = origin,
        full = inkWashReachToCover(hypot(size.width, size.height / 2f)),
    )
    val wipe = ink.dry.value
    if (wipe <= 0f) {
        wash()
        return
    }
    // Wiping back: a soft edge sweeps from the row's far end toward where the
    // ink landed, and only what lies behind it keeps its ink.
    val feather = ContinueInkWipeFeather.toPx()
    val edge = -feather + (size.width + 2f * feather) * wipe
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        wash()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                1f to Color.Black,
                startX = edge - feather,
                endX = edge + feather,
            ),
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }
}

/** The wash's own clock, from the turn starting to the row fully inked. */
private const val ContinueInkSpreadMs = 420

/**
 * The wash floods out fast and eases long into the row's far end: a soft
 * start so it never pops, then most of the row in the first third.
 */
private val ContinueInkEasing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

private const val ContinueTurnEpsilon = 0.001f

/** The most a single frame may advance the wash's clock. */
private const val ContinueInkMaxFrameMs = 20f

/** Leftover wipes (a turn let go short of the reader) run over the sweep-to-drop window of a 460 ms page turn. */
private const val ContinueInkDryMs = 212

/** The wipe gathers, crosses the words briskly, and settles into the Arabic end. */
private val ContinueInkWipeEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** Half the width of the wipe's soft edge. */
private val ContinueInkWipeFeather = 14.dp

/**
 * For a wipe picking up mid-way, in either direction: it is already moving,
 * so it starts at speed and settles, never pausing to gather.
 */
private val ContinueInkRewetEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
