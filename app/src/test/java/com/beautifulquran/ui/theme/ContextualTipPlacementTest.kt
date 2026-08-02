package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualTipPlacementTest {

    @Test
    fun cardinalAnglesPlaceBodyOnAnyScreenSide() {
        val surface = Size(100f, 200f)
        val spotlight = Offset(50f, 100f)

        assertPoint(Offset(10f, 100f), placement(180f).bodyCenter(spotlight, surface))
        assertPoint(Offset(90f, 100f), placement(0f).bodyCenter(spotlight, surface))
        assertPoint(Offset(50f, 20f), placement(270f).bodyCenter(spotlight, surface))
        assertPoint(Offset(50f, 180f), placement(90f).bodyCenter(spotlight, surface))
    }

    @Test
    fun diagonalAngleUsesFirstEdgeAndStaysInsideSurface() {
        val body = placement(225f).bodyCenter(Offset(50f, 100f), Size(100f, 200f))

        assertEquals(10f, body.x, 0.001f)
        assertEquals(60f, body.y, 0.001f)
    }

    @Test
    fun actionCanSitFartherAlongTheSameRay() {
        val placement = ContextualTipPlacement(
            bodyAngleDegrees = 180f,
            bodyDistanceFraction = 0.5f,
            actionDistanceFraction = 0.9f,
        )

        assertPoint(
            Offset(50f, 50f),
            placement.bodyCenter(Offset(100f, 50f), Size(100f, 100f)),
        )
        assertPoint(
            Offset(10f, 50f),
            placement.actionCenter(Offset(100f, 50f), Size(100f, 100f)),
        )
    }

    private fun placement(angle: Float) = ContextualTipPlacement(
        bodyAngleDegrees = angle,
        bodyDistanceFraction = 0.8f,
    )

    private fun assertPoint(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }
}
