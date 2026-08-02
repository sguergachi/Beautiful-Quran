package com.beautifulquran.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A continuous GPU-rendered vellum field that leaves the live page visible. */
@Composable
internal fun InkSpillField(
    progress: () -> Float,
    targetCenterY: Dp,
    lessonOnLeft: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderInkSpillField(progress, targetCenterY, lessonOnLeft, color, modifier)
    } else {
        GradientInkSpillField(progress, lessonOnLeft, color, modifier)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderInkSpillField(
    progress: () -> Float,
    targetCenterY: Dp,
    lessonOnLeft: Boolean,
    color: Color,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(VellumFieldShader) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier) {
        val tuning = ContextualGuideStyle.tuning
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("targetY", targetCenterY.toPx() / size.height)
        shader.setFloatUniform("progress", progress())
        shader.setFloatUniform("direction", if (lessonOnLeft) 1f else -1f)
        shader.setFloatUniform("bodyEdge", tuning.bodyEdge)
        shader.setFloatUniform("featherWidth", tuning.featherWidth)
        shader.setFloatUniform("fadeSoftness", tuning.fadeSoftness)
        shader.setFloatUniform("blurRadius", tuning.blurRadiusDp.dp.toPx())
        shader.setFloatUniform("blurStrength", tuning.blurStrength)
        shader.setFloatUniform("vellumGrain", tuning.vellumGrain)
        shader.setFloatUniform("verticalTaper", tuning.verticalTaper)
        shader.setColorUniform(
            "inkColor",
            android.graphics.Color.valueOf(color.red, color.green, color.blue, color.alpha),
        )
        drawRect(brush)
    }
}

/** Smooth fallback for Android 11–12, where RuntimeShader is unavailable. */
@Composable
private fun GradientInkSpillField(
    progress: () -> Float,
    lessonOnLeft: Boolean,
    color: Color,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val amount = progress()
        if (amount <= 0.001f) return@Canvas
        val tuning = ContextualGuideStyle.tuning
        val reachFraction = tuning.bodyEdge + tuning.featherWidth
        val reach = size.width * reachFraction * amount
        val bodyStop = (tuning.bodyEdge / reachFraction).coerceIn(0f, 1f)
        val stops = arrayOf(
            0f to color.copy(alpha = amount),
            bodyStop to color.copy(alpha = amount),
            bodyStop + (1f - bodyStop) * 0.42f to color.copy(alpha = 0.72f * amount),
            bodyStop + (1f - bodyStop) * 0.76f to color.copy(alpha = 0.28f * amount),
            1f to Color.Transparent,
        )
        val colorStops = if (lessonOnLeft) {
            stops
        } else {
            stops.map { (at, ink) -> (1f - at) to ink }.reversed().toTypedArray()
        }
        val left = if (lessonOnLeft) 0f else size.width - reach
        drawRect(
            brush = Brush.horizontalGradient(
                *colorStops,
                startX = left,
                endX = left + reach,
            ),
            topLeft = Offset(left, 0f),
            size = Size(reach, size.height),
        )
    }
}

private const val VellumFieldFunctions = """
    float smoother(float value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    float hash(float2 point) {
        return fract(sin(dot(point, float2(127.1, 311.7))) * 43758.5453);
    }

    float noise(float2 point) {
        float2 cell = floor(point);
        float2 part = fract(point);
        part = part * part * (3.0 - 2.0 * part);
        return mix(
            mix(hash(cell), hash(cell + float2(1.0, 0.0)), part.x),
            mix(hash(cell + float2(0.0, 1.0)), hash(cell + float2(1.0, 1.0)), part.x),
            part.y
        );
    }

    float vellumDensity(float2 fragCoord) {
        float2 uv = fragCoord / resolution;
        float fromEdge = direction > 0.0 ? uv.x : 1.0 - uv.x;
        float down = uv.y - targetY;
        float focusTaper = exp(-(down * down) / 0.18);
        float taper = verticalTaper * (0.3 + 0.7 * focusTaper);
        float solid = (bodyEdge + taper) * progress;
        float outer = (bodyEdge + featherWidth + taper) * progress;
        float density = clamp((outer - fromEdge) / max(outer - solid, 0.001), 0.0, 1.0);
        float transition = 4.0 * density * (1.0 - density);
        float clouds = noise(fragCoord * float2(0.006, 0.008)) * 0.65
            + noise(fragCoord * float2(0.017, 0.021)) * 0.35;
        density = clamp(density + (clouds - 0.5) * vellumGrain * transition, 0.0, 1.0);
        return density;
    }

    float vellumCoverage(float density) {
        return mix(pow(density, fadeSoftness), smoother(density), 0.22);
    }
"""

private const val VellumFieldShader = """
    uniform float2 resolution;
    uniform float targetY;
    uniform float progress;
    uniform float direction;
    uniform float bodyEdge;
    uniform float featherWidth;
    uniform float fadeSoftness;
    uniform float blurRadius;
    uniform float blurStrength;
    uniform float vellumGrain;
    uniform float verticalTaper;
    layout(color) uniform half4 inkColor;
""" + VellumFieldFunctions + """
    half4 main(float2 fragCoord) {
        float density = vellumDensity(fragCoord);
        float coverage = vellumCoverage(density);
        float edge = 4.0 * density * (1.0 - density);
        float radius = blurRadius * (0.35 + 0.65 * edge);
        float axis = direction * radius;
        float softened = coverage * 0.36;
        softened += vellumCoverage(vellumDensity(fragCoord + float2(axis, 0.0))) * 0.24;
        softened += vellumCoverage(vellumDensity(fragCoord - float2(axis, 0.0))) * 0.24;
        softened += vellumCoverage(vellumDensity(fragCoord + float2(axis * 2.0, 0.0))) * 0.08;
        softened += vellumCoverage(vellumDensity(fragCoord - float2(axis * 2.0, 0.0))) * 0.08;
        coverage = mix(coverage, softened, blurStrength * edge);
        half alpha = inkColor.a * half(coverage * progress);
        return half4(inkColor.rgb * alpha, alpha);
    }
"""
