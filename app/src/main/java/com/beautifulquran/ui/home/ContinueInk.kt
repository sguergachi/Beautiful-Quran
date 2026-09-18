package com.beautifulquran.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
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
 * the drag does; a turn let go short of the reader dries it out coat by coat,
 * and one that lands clears it unseen under the reader.
 */
@Stable
internal class ContinueInk(val params: NuqtaParams) {
    /** How far the wash has spread, 0..1. */
    val spread = Animatable(0f)
    val dry = Animatable(0f)

    /** Only a turn that starts on the chapter list inks the row. */
    var armed = true
    val fingers = inkCoatFingers(params)
}

private enum class ContinueTurn { Resting, Turning, Landed }

@Composable
internal fun rememberContinueInk(): ContinueInk {
    val params = LocalNuqtaParams.current
    val ink = remember(params) { ContinueInk(params) }
    val approach = LocalReaderApproach.current
    LaunchedEffect(ink, approach) {
        snapshotFlow {
            val p = approach()
            when {
                p >= 1f - ContinueTurnEpsilon -> ContinueTurn.Landed
                p > ContinueTurnEpsilon -> ContinueTurn.Turning
                else -> ContinueTurn.Resting
            }
        }.distinctUntilChanged().collectLatest { turn ->
            when (turn) {
                ContinueTurn.Turning -> if (ink.armed) coroutineScope {
                    // The wash plays out on its own clock; turned again
                    // mid-dry, the colour floods back as it carries on.
                    launch { ink.dry.animateTo(0f, tween(ContinueInkRewetMs, easing = LinearEasing)) }
                    ink.spread.animateTo(
                        1f,
                        tween(
                            (ContinueInkSpreadMs * (1f - ink.spread.value)).roundToInt(),
                            easing = LinearEasing,
                        ),
                    )
                }
                ContinueTurn.Landed -> {
                    // Landed in the reader: the row is under it, clear it.
                    ink.armed = false
                    ink.spread.snapTo(0f)
                    ink.dry.snapTo(0f)
                }
                ContinueTurn.Resting -> {
                    ink.armed = true
                    if (ink.spread.value > 0f) {
                        ink.dry.animateTo(1f, tween(ContinueInkDryMs, easing = LinearEasing))
                        ink.spread.snapTo(0f)
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
    if (ink.spread.value <= 0f) return@drawWithContent
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
        t = ink.spread.value,
        dry = ink.dry.value,
        params = ink.params,
        fingers = ink.fingers,
        ink = color,
        origin = origin,
        full = inkWashReachToCover(hypot(size.width, size.height / 2f)),
    )
}

/** The wash's own clock, from the turn starting to the row fully inked. */
private const val ContinueInkSpreadMs = 700

private const val ContinueTurnEpsilon = 0.001f

/** A turn let go dries out of the row over this long, coat by coat. */
private const val ContinueInkDryMs = 900

/** Turned again mid-dry, the colour floods back over this long. */
private const val ContinueInkRewetMs = 180
