package com.beautifulquran.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import com.beautifulquran.ui.theme.LocalNuqtaParams
import com.beautifulquran.ui.theme.NuqtaParams
import com.beautifulquran.ui.theme.drawInkWashCoats
import com.beautifulquran.ui.theme.inkCoatFingers
import com.beautifulquran.ui.theme.inkWashReachToCover
import kotlin.math.hypot
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
 * the drag does. It stays wet under the reader, and whenever the chapter list
 * settles again — a turn let go short, or a return from the reader — it dries
 * out of the row coat by coat.
 */
@Stable
internal class ContinueInk(val params: NuqtaParams) {
    /** How far the wash has spread, 0..1, eased. Read it only while drawing. */
    var spread by mutableFloatStateOf(0f)
        private set

    /** The wash's own clock, 0..1, before easing. */
    private var clock = 0f
    val dry = Animatable(0f)

    /** Tapped, and the turn it starts has not yet landed. */
    var tapped by mutableStateOf(false)

    /** The row's presses: touching it starts the ink before the turn does. */
    val interactions = MutableInteractionSource()
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
    val held by ink.interactions.collectIsPressedAsState()
    LaunchedEffect(ink, approach) {
        snapshotFlow {
            val p = approach()
            when {
                p >= 1f - ContinueTurnEpsilon -> ContinueTurn.Landed
                p > ContinueTurnEpsilon || held || ink.tapped -> ContinueTurn.Turning
                else -> ContinueTurn.Resting
            }
        }.distinctUntilChanged().collectLatest { turn ->
            when (turn) {
                ContinueTurn.Turning -> coroutineScope {
                    // The wash plays out on its own clock; turned again
                    // mid-dry, the colour floods back as it carries on.
                    launch { ink.dry.animateTo(0f, tween(ContinueInkRewetMs, easing = LinearEasing)) }
                    ink.flood()
                }
                ContinueTurn.Landed -> {
                    // Under the reader the row stays wet, so turning back
                    // uncovers it inked and it dries as the list settles.
                    ink.tapped = false
                    ink.fill()
                    ink.dry.snapTo(0f)
                }
                ContinueTurn.Resting -> {
                    if (ink.spread > 0f) {
                        ink.dry.animateTo(1f, tween(ContinueInkDryMs, easing = LinearEasing))
                        ink.clear()
                        ink.dry.snapTo(0f)
                    }
                }
            }
        }
    }
    return ink
}

/** The wash itself, drawn behind the row, landing at its right (Arabic) end. */
internal fun Modifier.continueInkWash(ink: ContinueInk, color: Color, paper: Color): Modifier =
    clipToBounds().drawBehind { drawContinueInk(ink, lerp(color, lerp(color, paper, 0.55f), ink.dry.value)) }

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
    drawInkWashCoats(
        t = ink.spread,
        dry = ink.dry.value,
        params = ink.params,
        fingers = ink.fingers,
        ink = color,
        origin = origin,
        full = inkWashReachToCover(hypot(size.width, size.height / 2f)),
    )
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

/** A turn let go dries out of the row over this long, coat by coat. */
private const val ContinueInkDryMs = 900

/** Turned again mid-dry, the colour floods back over this long. */
private const val ContinueInkRewetMs = 180
