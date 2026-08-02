package com.beautifulquran.ui.theme

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Optically scatters the live reader only where the vellum is translucent.
 * This belongs directly on each visible ayah render node: sampling the parent
 * LazyColumn loses Android's virtualized child display lists on some devices.
 */
@Composable
internal fun Modifier.contextualGuideProgressiveBlur(
    enabled: Boolean,
    visible: Boolean,
    rendered: Boolean,
    lessonOnLeft: Boolean,
    layerBlock: GraphicsLayerScope.() -> Unit = {},
): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !enabled) {
        return graphicsLayer(layerBlock)
    }
    val pixelDensity = LocalDensity.current.density
    val tuning = ContextualGuideStyle.tuning
    val blurRadiusPx = tuning.blurRadiusDp * pixelDensity
    val blurEffect = remember(blurRadiusPx) {
        if (blurRadiusPx > 0.01f) {
            RenderEffect.createBlurEffect(
                blurRadiusPx,
                blurRadiusPx,
                Shader.TileMode.DECAL,
            ).asComposeRenderEffect()
        } else {
            null
        }
    }
    val sourceLayer = rememberGraphicsLayer()
    val softenedLayer = rememberGraphicsLayer()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            if (visible) 1f else 0f,
            tween(
                durationMillis = if (visible) 380 else 320,
                easing = if (visible) InkExpandEasing else FastOutSlowInEasing,
            ),
        )
    }
    return graphicsLayer(layerBlock).drawWithContent {
        sourceLayer.record { this@drawWithContent.drawContent() }
        val amount = progress.value * tuning.blurStrength
        if (!rendered || amount <= 0.001f || blurEffect == null) {
            drawLayer(sourceLayer)
            return@drawWithContent
        }

        softenedLayer.renderEffect = blurEffect
        softenedLayer.record { drawLayer(sourceLayer) }
        val inner = (tuning.bodyEdge - tuning.featherWidth * 0.25f).coerceIn(0f, 1f)
        val quarter = (tuning.bodyEdge + tuning.featherWidth * 0.1f).coerceIn(inner, 1f)
        val center = (tuning.bodyEdge + tuning.featherWidth * 0.38f).coerceIn(quarter, 1f)
        val threeQuarter = (tuning.bodyEdge + tuning.featherWidth * 0.67f)
            .coerceIn(center, 1f)
        val outer = (tuning.bodyEdge + tuning.featherWidth).coerceIn(threeQuarter, 1f)
        val maskStops = if (lessonOnLeft) {
            arrayOf(
                inner to Color.Transparent,
                quarter to Color.White.copy(alpha = 0.24f * amount),
                center to Color.White.copy(alpha = 0.72f * amount),
                threeQuarter to Color.White.copy(alpha = 0.38f * amount),
                outer to Color.Transparent,
            )
        } else {
            arrayOf(
                (1f - outer) to Color.Transparent,
                (1f - threeQuarter) to Color.White.copy(alpha = 0.38f * amount),
                (1f - center) to Color.White.copy(alpha = 0.72f * amount),
                (1f - quarter) to Color.White.copy(alpha = 0.24f * amount),
                (1f - inner) to Color.Transparent,
            )
        }
        val mask = Brush.horizontalGradient(*maskStops)
        val bounds = Rect(Offset.Zero, size)
        drawContext.canvas.saveLayer(bounds, Paint())
        drawLayer(sourceLayer)
        drawRect(brush = mask, blendMode = BlendMode.DstOut)
        drawContext.canvas.restore()
        drawContext.canvas.saveLayer(bounds, Paint())
        drawLayer(softenedLayer)
        drawRect(
            brush = mask,
            blendMode = BlendMode.DstIn,
        )
        drawContext.canvas.restore()
    }
}

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

    float brushedPigment(float2 fragCoord) {
        float2 warp = float2(
            noise(fragCoord * 0.0027),
            noise((fragCoord + float2(173.0, 91.0)) * 0.0031)
        ) - 0.5;
        float2 point = fragCoord + warp * 30.0;
        float pooling = noise(point * 0.0045) * 0.55
            + noise((point + float2(47.0, 113.0)) * 0.011) * 0.3
            + noise((point + float2(211.0, 29.0)) * 0.026) * 0.15;
        float fibres = noise(float2(
            point.x * 0.021 + noise(point * 0.004) * 1.7,
            point.y * 0.006
        ));
        float tooth = noise((point + float2(61.0, 157.0)) * 0.057);
        return pooling * 0.42 + fibres * 0.3 + tooth * 0.28;
    }

    float vellumDensity(float2 fragCoord) {
        float2 uv = fragCoord / resolution;
        float fromEdge = direction > 0.0 ? uv.x : 1.0 - uv.x;
        float riseEnd = clamp(targetY + 0.05, 0.30, 0.42);
        float rise = smoother(clamp((uv.y + 0.02) / riseEnd, 0.0, 1.0));
        float tail = 1.0 - 0.68 * smoother(clamp((uv.y - 0.72) / 0.28, 0.0, 1.0));
        float silhouette = rise * tail;
        float taper = verticalTaper * (1.0 - silhouette);
        float lessonDistance = (uv.y - targetY) / 0.17;
        float lessonReservoir = verticalTaper * 0.85
            * exp(-lessonDistance * lessonDistance);
        // A single diffusion profile has no solid/feather junction to expose.
        // Its midpoint follows the tapered silhouette and opens at the lesson.
        float midpoint = (bodyEdge + featherWidth * 0.32 - taper
            + lessonReservoir) * progress;
        float diffusion = max(featherWidth * 0.28, 0.025);
        float clouds = noise(fragCoord * float2(0.006, 0.008)) * 0.65
            + noise(fragCoord * float2(0.017, 0.021)) * 0.35;
        float fibres = noise(fragCoord * float2(0.055, 0.071));
        float texture = (clouds - 0.5) + (fibres - 0.5) * 0.18;
        float absorbed = midpoint - fromEdge + texture * vellumGrain * 0.18;
        return 1.0 / (1.0 + exp(-absorbed / diffusion));
    }

    float vellumCoverage(float density) {
        float wash = pow(density, max(0.7, fadeSoftness * 0.58));
        // A second translucent glaze adds pigment depth without moving the
        // diffusion contour or hardening its paper-side tail.
        return 1.0 - pow(1.0 - wash, 1.18);
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
        // Paper tooth varies pigment load, rather than displacing a contour.
        // Pixel-scale dither keeps the long translucent ramp quantization-free.
        float brush = brushedPigment(fragCoord) - 0.5;
        coverage = clamp(
            coverage * (1.0 + brush * vellumGrain * (1.4 + 2.0 * edge))
                + (hash(floor(fragCoord)) - 0.5) * edge / 255.0,
            0.0,
            1.0
        );
        float2 uv = fragCoord / resolution;
        float fromSource = direction > 0.0 ? uv.x : 1.0 - uv.x;
        float sourcePool = smoother(clamp((0.5 - fromSource) / 0.5, 0.0, 1.0));
        coverage = 1.0 - pow(1.0 - coverage, 1.0 + 0.75 * sourcePool);
        half alpha = inkColor.a * half(coverage * progress);
        return half4(inkColor.rgb * alpha, alpha);
    }
"""
