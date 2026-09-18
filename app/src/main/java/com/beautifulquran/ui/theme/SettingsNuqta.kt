package com.beautifulquran.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * How far the paper stack has turned from the sheet beneath Settings toward
 * Settings itself, 0..1. Read it only while drawing: it changes every frame
 * of a swipe.
 */
val LocalSettingsApproach = staticCompositionLocalOf<() -> Float> { { 0f } }

/** Lets the settings button start its nuqta the instant it is tapped. */
@Stable
class SettingsNuqtaState {
    /** Tapped, and the turn it starts has not yet moved the stack. */
    internal var pressed by mutableStateOf(false)

    /** Touch the pen down: the nuqta spreads on its own select clock. */
    fun drop() {
        pressed = true
    }
}

@Composable
fun rememberSettingsNuqtaState(): SettingsNuqtaState = remember { SettingsNuqtaState() }

/**
 * The settings glyph with a nuqta of ink behind it, so the reader sees where
 * a page turn will land. The moment the stack starts to turn toward Settings
 * — a swipe or a tap ([SettingsNuqtaState.drop]) — the drop spreads on the
 * same clock as a chosen row. From there the drag stretches it like a rubber
 * band, and if the turn falls back short of Settings the ink dries back
 * the way it came. The glyph takes the
 * contrasting colour only where the ink covers it.
 */
@Composable
fun SettingsNuqtaIcon(
    state: SettingsNuqtaState,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
    params: NuqtaParams = LocalNuqtaParams.current,
) {
    val approach = LocalSettingsApproach.current
    val spread = remember { Animatable(0f) }
    val presence = remember { Animatable(0f) }
    LaunchedEffect(approach, state) {
        // Once the turn is moving it carries the ink; the tap is spent.
        snapshotFlow { approach() > SettingsTurnEpsilon }.collect { turning ->
            if (turning) state.pressed = false
        }
    }
    LaunchedEffect(approach, state, params) {
        snapshotFlow { approach() > SettingsTurnEpsilon || state.pressed }
            .distinctUntilChanged()
            .collectLatest { inked ->
                if (inked) {
                    // Turned again while it dries: the ink re-wets from
                    // where it has shrunk to, and its colour floods back.
                    val ms = (params.spreadMs * (1f - spread.value)).roundToInt()
                    coroutineScope {
                        launch { presence.animateTo(1f, tween(ms, easing = LinearEasing)) }
                        spread.animateTo(1f, tween(ms, easing = LinearEasing))
                    }
                } else {
                    // A turn let go short of Settings dries back: the clock
                    // runs in reverse, so the edge draws in toward where the
                    // drop landed, while the coat thins — lighter and more
                    // transparent — gathering pace as the paper drinks it.
                    val ms = (params.spreadMs * spread.value).roundToInt()
                    coroutineScope {
                        launch { presence.animateTo(0f, tween(ms, easing = params.lift.easing())) }
                        spread.animateTo(0f, tween(ms, easing = LinearEasing))
                    }
                    presence.snapTo(0f)
                }
            }
    }
    val fingers = remember(params.seed) { nuqtaFingers(params.seed) }
    val painter = rememberVectorPainter(Icons.Rounded.Tune)
    val ink = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.background
    val inked = MaterialTheme.colorScheme.onPrimary

    Canvas(
        modifier
            .size(iconSize * SettingsNuqtaScale)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        val t = spread.value
        val lift = presence.value
        val glyphPx = iconSize.toPx()
        fun glyph(color: Color) = translate(
            left = (size.width - glyphPx) / 2f,
            top = (size.height - glyphPx) / 2f,
        ) {
            with(painter) { draw(Size(glyphPx, glyphPx), colorFilter = ColorFilter.tint(color)) }
        }
        if (t <= 0f || lift <= 0f) {
            glyph(tint)
            return@Canvas
        }
        val swell = 1f + settingsNuqtaStretch(approach())
        // A drying coat is a thinner one: its pigment pales toward the paper.
        val coat = lerp(ink, lerp(ink, paper, SettingsNuqtaDriedTint), 1f - lift)
        scale(swell) { drawInkNuqta(t, lift, params, fingers, coat, Color.Transparent) }
        glyph(tint)
        // Where the ink lies, the glyph turns to paper exactly as far as the
        // ink under it is dense: a contrasting copy, masked by the same drop.
        drawIntoCanvas { canvas ->
            val bounds = Rect(Offset.Zero, size)
            canvas.saveLayer(bounds, Paint())
            glyph(inked)
            // The mask is its own layer so that, composited DstIn, its bare
            // paper clears the copy too — not only where ink was drawn.
            canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstIn })
            scale(swell) { drawInkNuqta(t, lift, params, fingers, Color.Black, Color.Transparent) }
            canvas.restore()
            canvas.restore()
        }
    }
}

/** The nuqta's size as a multiple of the glyph it sits behind. */
private const val SettingsNuqtaScale = 1.6f

/** How far toward the paper the last of a drying coat has paled. */
private const val SettingsNuqtaDriedTint = 0.55f

/** How far the stack must move before it counts as turning toward Settings. */
private const val SettingsTurnEpsilon = 0.001f

/** The swell the rubber band tends toward if the turn could run on forever. */
private const val SettingsNuqtaStretchLimit = 0.8f

/** How stiff the band is: its pull, per share of the turn, as it starts to stretch. */
private const val SettingsNuqtaStretchStiffness = 1f

/**
 * The swell past full size at [approach]. The drop is a rubber band: it
 * grows with the drag all the way to Settings, but each step buys less than
 * the one before, like tension building before it snaps back.
 */
internal fun settingsNuqtaStretch(approach: Float): Float {
    val over = approach.coerceIn(0f, 1f)
    return SettingsNuqtaStretchLimit * (1f - 1f / (1f + SettingsNuqtaStretchStiffness * over))
}
