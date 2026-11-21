package com.beautifulquran.ui.entrance

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.beautifulquran.ui.theme.CoverLeatherCenter
import com.beautifulquran.ui.theme.CoverLeatherEdge
import com.beautifulquran.ui.theme.setManagedColorUniform

/**
 * The cover's leather: a radial falloff from [CoverLeatherCenter] to
 * [CoverLeatherEdge] across the whole board. The two greens are only a few
 * eight-bit steps apart over a screen's height, so a plain gradient lands
 * those steps as rings. On Android 13+ the same ramp is drawn in AGSL with a
 * triangular ±1-step noise added, which breaks the rings up below the
 * threshold of seeing it; the paint's own dither is off so the two never
 * stack. Older devices draw the plain gradient they always have.
 *
 * Built once per board size (the ceremony recomposes; the size does not), so
 * no frame allocates: the shader and paint are fixed, and a resize only
 * rewrites two uniforms.
 */
internal class CoverLeather {
    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(LEATHER_AGSL).apply {
                setManagedColorUniform("inner", CoverLeatherCenter)
                setManagedColorUniform("outer", CoverLeatherEdge)
            }
        } else {
            null
        }
    private val paint = android.graphics.Paint().apply {
        isDither = false
        shader = this@CoverLeather.shader
    }
    private var boardSize = Size.Unspecified
    private var fallback: Brush? = null

    /** Re-aims the ramp for a board of [size]; a no-op when it has not changed. */
    fun resize(size: Size) {
        if (size == boardSize) return
        boardSize = size
        val center = Offset(size.width / 2f, size.height * 0.42f)
        val radius = size.height * 0.75f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            shader.setFloatUniform("center", center.x, center.y)
            shader.setFloatUniform("radius", radius)
        } else {
            fallback = Brush.radialGradient(
                0f to CoverLeatherCenter,
                1f to CoverLeatherEdge,
                center = center,
                radius = radius,
            )
        }
    }

    fun DrawScope.drawLeather() {
        val brush = fallback
        if (brush != null) {
            drawRect(brush)
        } else {
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
        }
    }
}

// Interleaved gradient noise, twice, makes a triangular distribution: its
// mean is the ramp itself and its error never correlates with the ramp, so
// the steps vanish without the grain changing strength across the board.
private const val LEATHER_AGSL = """
uniform float2 center;
uniform float radius;
layout(color) uniform float4 inner;
layout(color) uniform float4 outer;

float ign(float2 p) {
    return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
}

half4 main(float2 p) {
    float t = clamp(distance(p, center) / radius, 0.0, 1.0);
    float3 leather = mix(inner.rgb, outer.rgb, t);
    float noise = ign(p) + ign(p + float2(47.0, 17.0)) - 1.0;
    return half4(leather + noise / 255.0, 1.0);
}
"""
