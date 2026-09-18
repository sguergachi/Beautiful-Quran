package com.beautifulquran.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/**
 * How far the paper stack has turned from the sheet beneath Settings toward
 * Settings itself, 0..1. Read it only while drawing: it changes every frame
 * of a swipe.
 */
val LocalSettingsApproach = staticCompositionLocalOf<() -> Float> { { 0f } }

/** Lets the settings button start its nuqta the instant it is tapped. */
@Stable
class SettingsNuqtaState {
    internal var drops by mutableIntStateOf(0)

    /** Touch the pen down: the nuqta spreads on its own select clock. */
    fun drop() {
        drops++
    }
}

@Composable
fun rememberSettingsNuqtaState(): SettingsNuqtaState = remember { SettingsNuqtaState() }

/**
 * The settings glyph with a nuqta of ink behind it. The drop soaks outward
 * in step with the page turn toward Settings — a swipe spreads it live, full
 * by [SettingsNuqtaFullAt] of the turn and swelling elastically beyond it,
 * and turning back lifts it — so the reader sees where the turn will land. A tap
 * ([SettingsNuqtaState.drop]) spreads it on the same clock as a chosen row.
 * The glyph takes the contrasting colour only where the ink covers it.
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
    val tap = remember { Animatable(0f) }
    LaunchedEffect(state.drops) {
        if (state.drops == 0) return@LaunchedEffect
        tap.snapTo(0f)
        tap.animateTo(1f, tween(params.spreadMs, easing = LinearEasing))
        // Hold the drop until the turn settles. On Settings the turn itself
        // keeps it spread; if the turn fell back, lift it like a let-go choice.
        val settled = snapshotFlow { approach() }.first { it >= 0.999f || it <= 0.001f }
        if (settled >= 0.999f) {
            tap.snapTo(0f)
        } else {
            tap.animateTo(0f, tween(params.liftMs, easing = params.lift.easing()))
        }
    }
    // Past full spread the drop keeps swelling a little on a loose spring:
    // the page is past its point of no return and will finish the turn.
    val stretch = remember { Animatable(0f) }
    LaunchedEffect(approach) {
        snapshotFlow { settingsNuqtaStretch(approach()) }.collectLatest { target ->
            stretch.animateTo(target, spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val fingers = remember(params.seed) { nuqtaFingers(params.seed) }
    val painter = rememberVectorPainter(Icons.Rounded.Tune)
    val ink = MaterialTheme.colorScheme.primary
    val inked = MaterialTheme.colorScheme.onPrimary

    Canvas(
        modifier
            .size(iconSize * SettingsNuqtaScale)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        // The drop is fully spread well before the turn's midpoint, so the
        // whole nuqta reads while the reader can still decide.
        val t = maxOf((approach() / SettingsNuqtaFullAt).coerceIn(0f, 1f), tap.value)
        val glyphPx = iconSize.toPx()
        fun glyph(color: Color) = translate(
            left = (size.width - glyphPx) / 2f,
            top = (size.height - glyphPx) / 2f,
        ) {
            with(painter) { draw(Size(glyphPx, glyphPx), colorFilter = ColorFilter.tint(color)) }
        }
        if (t <= 0f) {
            glyph(tint)
            return@Canvas
        }
        val swell = 1f + stretch.value
        scale(swell) { drawInkNuqta(t, 1f, params, fingers, ink, Color.Transparent) }
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
            scale(swell) { drawInkNuqta(t, 1f, params, fingers, Color.Black, Color.Transparent) }
            canvas.restore()
            canvas.restore()
        }
    }
}

/** The nuqta's size as a multiple of the glyph it sits behind. */
private const val SettingsNuqtaScale = 1.6f

/** The share of the turn toward Settings at which the nuqta is fully spread. */
private const val SettingsNuqtaFullAt = 0.4f

/** How far past full size the drop swells once the turn runs beyond full spread. */
private const val SettingsNuqtaMaxStretch = 0.38f

/** The share of the turn, after full spread, over which the swell arrives. */
private const val SettingsNuqtaStretchSpan = 0.3f

/** The swell past full size at [approach], easing out into its limit. */
internal fun settingsNuqtaStretch(approach: Float): Float {
    val over = ((approach - SettingsNuqtaFullAt) / SettingsNuqtaStretchSpan).coerceIn(0f, 1f)
    return SettingsNuqtaMaxStretch * (1f - (1f - over).let { it * it * it })
}
