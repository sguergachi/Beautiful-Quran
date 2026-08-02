package com.beautifulquran.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Places teaching copy on a ray extending from the feature spotlight.
 *
 * Zero degrees points right and positive angles turn clockwise with screen Y.
 * [bodyDistanceFraction] is a share of the ray from the spotlight to the first
 * screen edge, so every angle remains inside the current surface.
 */
@Immutable
data class ContextualTipPlacement(
    val bodyAngleDegrees: Float,
    val bodyDistanceFraction: Float = 0.78f,
    val actionDistanceFraction: Float = 0.94f,
) {
    fun bodyCenter(spotlight: Offset, surface: Size): Offset =
        pointOnRay(spotlight, surface, bodyDistanceFraction)

    fun actionCenter(spotlight: Offset, surface: Size): Offset =
        pointOnRay(spotlight, surface, actionDistanceFraction)

    private fun pointOnRay(spotlight: Offset, surface: Size, fraction: Float): Offset {
        val radians = bodyAngleDegrees * (PI.toFloat() / 180f)
        val direction = Offset(cos(radians), sin(radians))
        val xLimit = when {
            direction.x > EPSILON -> (surface.width - spotlight.x) / direction.x
            direction.x < -EPSILON -> -spotlight.x / direction.x
            else -> Float.POSITIVE_INFINITY
        }
        val yLimit = when {
            direction.y > EPSILON -> (surface.height - spotlight.y) / direction.y
            direction.y < -EPSILON -> -spotlight.y / direction.y
            else -> Float.POSITIVE_INFINITY
        }
        val distance = min(xLimit, yLimit).coerceAtLeast(0f) * fraction.coerceIn(0f, 1f)
        return Offset(
            x = (spotlight.x + direction.x * distance).coerceIn(0f, surface.width),
            y = (spotlight.y + direction.y * distance).coerceIn(0f, surface.height),
        )
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
