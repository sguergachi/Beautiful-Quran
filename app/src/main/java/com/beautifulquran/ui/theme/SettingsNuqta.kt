package com.beautifulquran.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The paper stack's turn toward Settings, as the settings nuqta reads it. */
@Stable
class SettingsApproach(
    /**
     * How far the stack has turned from the sheet beneath Settings toward
     * Settings itself, 0..1. Read it only while drawing or in snapshot flows:
     * it changes every frame of a swipe.
     */
    val progress: () -> Float,
    /** Whether a finger is dragging the stack right now. */
    val dragging: () -> Boolean,
    /** The share of a drag past which letting go completes the turn. */
    val commitAt: Float,
)

val LocalSettingsApproach = staticCompositionLocalOf {
    SettingsApproach(progress = { 0f }, dragging = { false }, commitAt = 1f)
}

/**
 * Lets the settings button ink its nuqta while it is held — pass
 * [interactions] to its clickable — and the instant it is tapped.
 */
@Stable
class SettingsNuqtaState {
    /** The button's presses: holding it inks the nuqta as its pressed state. */
    val interactions = MutableInteractionSource()

    /** Tapped, and the turn it starts has not yet settled. */
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
 * a page turn will land. The moment the button is held, or the stack starts
 * to turn toward Settings — a swipe or a tap ([SettingsNuqtaState.drop]) —
 * the drop spreads on the same clock as a chosen row.
 *
 * The drag then stretches it like a rubber band in two stages: once the
 * drag passes the point where letting go completes the turn, the drop
 * bounces up a stage with a haptic tick. Holding the button shows the band
 * at its fullest. If the press or turn falls back short of Settings the ink
 * dries in place, its layers fading one after another. The glyph takes the contrasting colour only
 * where the ink covers it.
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
    val turn = LocalSettingsApproach.current
    val approach = turn.progress
    val held by state.interactions.collectIsPressedAsState()
    val view = LocalView.current
    val spread = remember { Animatable(0f) }
    val presence = remember { Animatable(0f) }
    // The band's second stage, on a bouncy spring: 0 before the commit
    // point, 1 past it.
    val stage = remember { Animatable(0f) }
    // Holding the button pulls the band to its fullest.
    val pull = remember { Animatable(0f) }
    LaunchedEffect(turn, state) {
        // A tap's turn carries the ink until it lands on Settings, or until
        // a finger takes the page over.
        snapshotFlow { approach() >= 1f - SettingsTurnEpsilon || turn.dragging() }.collect { done ->
            if (done) state.pressed = false
        }
    }
    LaunchedEffect(turn, state) {
        snapshotFlow { held || state.pressed || approach() >= turn.commitAt }
            .distinctUntilChanged()
            .collectLatest { committed ->
                if (committed && turn.dragging()) view.paperSelectHaptic()
                stage.animateTo(
                    if (committed) 1f else 0f,
                    spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessMedium),
                )
            }
    }
    LaunchedEffect(state) {
        snapshotFlow { held }.collectLatest { holding ->
            pull.animateTo(
                if (holding) settingsNuqtaStretch(1f) else 0f,
                spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }
    LaunchedEffect(turn, state, params) {
        snapshotFlow { approach() > SettingsTurnEpsilon || state.pressed || held }
            .distinctUntilChanged()
            .collectLatest { inked ->
                if (inked) {
                    if (presence.value <= 0f) spread.snapTo(0f)
                    // Turned again while it dries: the colour floods back into
                    // the drop as it is, and it carries on spreading.
                    coroutineScope {
                        launch {
                            presence.animateTo(1f, tween(SettingsNuqtaRewetMs, easing = LinearEasing))
                        }
                        spread.animateTo(
                            1f,
                            tween((params.spreadMs * (1f - spread.value)).roundToInt(), easing = LinearEasing),
                        )
                    }
                } else {
                    // A press or turn let go short of Settings dries in place:
                    // the drop keeps its shape while its layers fade one after
                    // another, the coat paling toward the paper as it goes.
                    presence.animateTo(0f, tween(SettingsNuqtaDryMs, easing = LinearEasing))
                    spread.snapTo(0f)
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
        val swell = 1f + maxOf(settingsNuqtaStretch(approach()), pull.value) +
            SettingsNuqtaStageSwell * stage.value
        // A drying coat is a thinner one: its pigment pales toward the paper.
        val coat = lerp(ink, lerp(ink, paper, SettingsNuqtaDriedTint), 1f - lift)
        scale(swell) { drawInkNuqta(t, 1f, params, fingers, coat, Color.Transparent, dry = 1f - lift) }
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
            scale(swell) {
                drawInkNuqta(t, 1f, params, fingers, Color.Black, Color.Transparent, dry = 1f - lift)
            }
            canvas.restore()
            canvas.restore()
        }
    }
}

/** The nuqta's size as a multiple of the glyph it sits behind. */
private const val SettingsNuqtaScale = 1.6f

/** A drop let go dries in place over this long, layer by layer. */
private const val SettingsNuqtaDryMs = 560

/** Turned again mid-dry, the colour floods back over this long. */
private const val SettingsNuqtaRewetMs = 180

/** How far toward the paper the last of a drying coat has paled. */
private const val SettingsNuqtaDriedTint = 0.55f

/** The extra swell the band bounces up by as the drag passes the commit point. */
private const val SettingsNuqtaStageSwell = 0.14f

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
