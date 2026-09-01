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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

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
    flow: Offset,
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
    val direction = flow.normalized()
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
        val maskStops = arrayOf(
            inner to Color.Transparent,
            quarter to Color.White.copy(alpha = 0.24f * amount),
            center to Color.White.copy(alpha = 0.72f * amount),
            threeQuarter to Color.White.copy(alpha = 0.38f * amount),
            outer to Color.Transparent,
        )
        val span = abs(direction.x) * size.width + abs(direction.y) * size.height
        val fieldCenter = Offset(size.width / 2f, size.height / 2f)
        val mask = Brush.linearGradient(
            *maskStops,
            start = fieldCenter - direction * (span / 2f),
            end = fieldCenter + direction * (span / 2f),
        )
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
    spotlightCenter: DpOffset,
    bodyCenter: DpOffset,
    actionCenter: DpOffset,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderInkSpillField(
            progress,
            spotlightCenter,
            bodyCenter,
            actionCenter,
            color,
            modifier,
        )
    } else {
        GradientInkSpillField(progress, spotlightCenter, bodyCenter, color, modifier)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderInkSpillField(
    progress: () -> Float,
    spotlightCenter: DpOffset,
    bodyCenter: DpOffset,
    actionCenter: DpOffset,
    color: Color,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(VellumFieldShader) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier) {
        val tuning = ContextualGuideStyle.tuning
        val spotlight = Offset(spotlightCenter.x.toPx(), spotlightCenter.y.toPx())
        val body = Offset(bodyCenter.x.toPx(), bodyCenter.y.toPx())
        val action = Offset(actionCenter.x.toPx(), actionCenter.y.toPx())
        val flow = (spotlight - body).normalized()
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("spotlight", spotlight.x, spotlight.y)
        shader.setFloatUniform("bodyCenter", body.x, body.y)
        shader.setFloatUniform("actionCenter", action.x, action.y)
        shader.setFloatUniform("progress", progress())
        shader.setFloatUniform("flow", flow.x, flow.y)
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
    spotlightCenter: DpOffset,
    bodyCenter: DpOffset,
    color: Color,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val amount = progress()
        if (amount <= 0.001f) return@Canvas
        val tuning = ContextualGuideStyle.tuning
        val spotlight = Offset(spotlightCenter.x.toPx(), spotlightCenter.y.toPx())
        val body = Offset(bodyCenter.x.toPx(), bodyCenter.y.toPx())
        val direction = (spotlight - body).normalized()
        val reachFraction = tuning.bodyEdge + tuning.featherWidth
        val span = abs(direction.x) * size.width + abs(direction.y) * size.height
        val reach = span * reachFraction * amount
        val bodyStop = (tuning.bodyEdge / reachFraction).coerceIn(0f, 1f)
        val stops = arrayOf(
            0f to color.copy(alpha = amount),
            bodyStop to color.copy(alpha = amount),
            bodyStop + (1f - bodyStop) * 0.42f to color.copy(alpha = 0.72f * amount),
            bodyStop + (1f - bodyStop) * 0.76f to color.copy(alpha = 0.28f * amount),
            1f to Color.Transparent,
        )
        val fieldCenter = Offset(size.width / 2f, size.height / 2f)
        val start = fieldCenter - direction * (span / 2f)
        drawRect(
            brush = Brush.linearGradient(
                *stops,
                start = start,
                end = start + direction * reach,
            ),
        )
    }
}

/** Shared AGSL pigment: hash, fibre, tooth, glaze. Used by the guide field
 *  and by the ink-spot selector so both are the same paper. */
internal const val VellumPigmentFunctions = """
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

    float vellumCoverage(float density) {
        float wash = pow(density, max(0.7, fadeSoftness * 0.58));
        // A second translucent glaze adds pigment depth without moving the
        // diffusion contour or hardening its paper-side tail.
        return 1.0 - pow(1.0 - wash, 1.18);
    }
"""

private const val VellumFieldFunctions = """
    float2 orientedCoordinates(float2 point) {
        float2 crossAxis = float2(-flow.y, flow.x);
        if (abs(crossAxis.y) > 0.0001) {
            if (crossAxis.y < 0.0) crossAxis = -crossAxis;
        } else if (crossAxis.x < 0.0) {
            crossAxis = -crossAxis;
        }
        float alongMin = min(0.0, flow.x * resolution.x)
            + min(0.0, flow.y * resolution.y);
        float crossMin = min(0.0, crossAxis.x * resolution.x)
            + min(0.0, crossAxis.y * resolution.y);
        float alongSpan = max(
            abs(flow.x) * resolution.x + abs(flow.y) * resolution.y,
            1.0
        );
        float crossSpan = max(
            abs(crossAxis.x) * resolution.x + abs(crossAxis.y) * resolution.y,
            1.0
        );
        return float2(
            (dot(point, flow) - alongMin) / alongSpan,
            (dot(point, crossAxis) - crossMin) / crossSpan
        );
    }

    float vellumDensity(float2 fragCoord) {
        float2 oriented = orientedCoordinates(fragCoord);
        float2 target = orientedCoordinates(spotlight);
        float2 body = orientedCoordinates(bodyCenter);
        float fromEdge = oriented.x;
        float cross = oriented.y;
        float targetCross = target.y;
        float riseEnd = clamp(targetCross + 0.05, 0.30, 0.42);
        float rise = smoother(clamp((cross + 0.02) / riseEnd, 0.0, 1.0));
        float tail = 1.0 - 0.68 * smoother(clamp((cross - 0.72) / 0.28, 0.0, 1.0));
        float silhouette = rise * tail;
        float taper = verticalTaper * (1.0 - silhouette);
        float lessonDistance = (cross - targetCross) / 0.17;
        float lessonReservoir = verticalTaper * 0.85
            * exp(-lessonDistance * lessonDistance);
        // A single diffusion profile has no solid/feather junction to expose.
        // Its midpoint follows only the lesson; the button contour is separate.
        float bodyFloor = body.x + featherWidth * 0.08;
        float spotlightCeiling = target.x - featherWidth * 0.12;
        float midpoint = clamp(
            bodyEdge + featherWidth * 0.32 - taper + lessonReservoir,
            min(bodyFloor, spotlightCeiling),
            max(bodyFloor, spotlightCeiling)
        ) * progress;
        float diffusion = max(featherWidth * 0.28, 0.025);
        float clouds = noise(fragCoord * float2(0.006, 0.008)) * 0.65
            + noise(fragCoord * float2(0.017, 0.021)) * 0.35;
        float fibres = noise(fragCoord * float2(0.055, 0.071));
        float texture = (clouds - 0.5) + (fibres - 0.5) * 0.18;
        float absorbed = midpoint - fromEdge + texture * vellumGrain * 0.18;
        float lessonDensity = 1.0 / (1.0 + exp(-absorbed / diffusion));

        // A second, localized diffusion contour gives the untouched-paper
        // action breathing room without changing the lesson silhouette.
        float actionCross = orientedCoordinates(actionCenter).y;
        float buttonDistance = (cross - actionCross) / 0.11;
        float buttonEnvelope = exp(-buttonDistance * buttonDistance);
        float buttonMidpoint = clamp(
            bodyEdge + featherWidth * 0.18,
            min(bodyFloor, spotlightCeiling),
            max(bodyFloor, spotlightCeiling)
        ) * progress;
        float buttonDiffusion = max(featherWidth * 0.22, 0.02);
        float buttonAbsorbed = buttonMidpoint - fromEdge
            + texture * vellumGrain * 0.12;
        float buttonDensity = buttonEnvelope
            / (1.0 + exp(-buttonAbsorbed / buttonDiffusion));
        return 1.0 - (1.0 - lessonDensity) * (1.0 - buttonDensity);
    }
"""

internal const val VellumFieldShader = """
    uniform float2 resolution;
    uniform float2 spotlight;
    uniform float2 bodyCenter;
    uniform float2 actionCenter;
    uniform float progress;
    uniform float2 flow;
    uniform float bodyEdge;
    uniform float featherWidth;
    uniform float fadeSoftness;
    uniform float blurRadius;
    uniform float blurStrength;
    uniform float vellumGrain;
    uniform float verticalTaper;
    layout(color) uniform half4 inkColor;
""" + VellumPigmentFunctions + VellumFieldFunctions + """
    half4 main(float2 fragCoord) {
        float density = vellumDensity(fragCoord);
        float coverage = vellumCoverage(density);
        float edge = 4.0 * density * (1.0 - density);
        float radius = blurRadius * (0.35 + 0.65 * edge);
        float2 axis = flow * radius;
        float softened = coverage * 0.36;
        softened += vellumCoverage(vellumDensity(fragCoord + axis)) * 0.24;
        softened += vellumCoverage(vellumDensity(fragCoord - axis)) * 0.24;
        softened += vellumCoverage(vellumDensity(fragCoord + axis * 2.0)) * 0.08;
        softened += vellumCoverage(vellumDensity(fragCoord - axis * 2.0)) * 0.08;
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
        float fromSource = orientedCoordinates(fragCoord).x;
        float sourcePool = smoother(clamp((0.5 - fromSource) / 0.5, 0.0, 1.0));
        coverage = 1.0 - pow(1.0 - coverage, 1.0 + 0.75 * sourcePool);
        half alpha = inkColor.a * half(coverage * progress);
        return half4(inkColor.rgb * alpha, alpha);
    }
"""

/**
 * A circular ink drop on vellum. The silhouette is a circle; fibre and
 * a capillary halo live only in the rim so the box never clips a lobe.
 * [progress] grows the soak. [fill] > 0.5 uses the guide field's
 * logistic diffusion — an organic verse soak, not a box-fitted oval.
 */
internal const val VellumSpotShader = """
    uniform float2 resolution;
    uniform float progress;
    uniform float seed;
    uniform float fadeSoftness;
    uniform float vellumGrain;
    uniform float fill;
    layout(color) uniform half4 inkColor;
""" + VellumPigmentFunctions + """
    half4 main(float2 fragCoord) {
        float2 res = max(resolution, float2(1.0, 1.0));
        float2 origin = fragCoord + float2(seed * 17.0, seed * 11.0);
        float fibre = brushedPigment(origin);
        float density;
        float appear;
        float r;
        if (fill > 0.5) {
            // Same clouds + fibre field as VellumFieldShader, pooled from a
            // seed so the mark reads as ink spreading into the paper.
            float2 reach = 0.5 * res;
            float2 center = 0.5 * res + (float2(
                hash(float2(seed, 1.3)),
                hash(float2(seed, 4.7))
            ) - 0.5) * reach * 0.05;
            float2 p = (fragCoord - center) / max(reach, float2(1.0));
            r = length(p);
            float clouds = noise(origin * float2(0.006, 0.008)) * 0.65
                + noise(origin * float2(0.017, 0.021)) * 0.35;
            float fibres = noise(origin * float2(0.055, 0.071));
            float texture = (clouds - 0.5) + (fibres - 0.5) * 0.18;
            float lobes = noise(float2(seed, seed) + p * 2.1) - 0.5;
            float warp = texture * 0.20 + (fibre - 0.5) * 0.10 + lobes * 0.08;
            float midpoint = mix(0.06, 0.70, progress);
            float diffusion = max(0.11, 0.16 * fadeSoftness * 0.55);
            float absorbed = midpoint - r + warp;
            density = 1.0 / (1.0 + exp(-absorbed / diffusion));
            appear = smoother(clamp(progress * 5.0, 0.0, 1.0));
        } else {
            float circleAxis = 0.5 * min(res.x, res.y);
            float2 reach = float2(circleAxis);
            float2 center = 0.5 * res + (float2(
                hash(float2(seed, 1.3)),
                hash(float2(seed, 4.7))
            ) - 0.5) * reach * 0.04;
            float2 p = (fragCoord - center) / max(reach, float2(1.0));
            r = length(p);
            float ang = atan(p.y, p.x);
            float rimWobble = 0.025 * sin(ang * 3.0 + seed)
                + 0.02 * (fibre - 0.5);
            float radius = 0.56 + rimWobble;
            float soak = mix(0.55, 1.0, progress);
            float edge = r - radius * soak;
            float body = 1.0 - smoother(clamp(edge / 0.18 + 0.15, 0.0, 1.0));
            float pool = 1.0 - smoother(clamp(r / 0.34, 0.0, 1.0));
            density = body * (0.40 + 0.60 * pool);
            float halo = exp(-max(edge, 0.0) * 18.0) * 0.38 * progress;
            density = max(density, halo * (0.35 + 0.65 * fibre));
            appear = progress;
        }
        float rim = 4.0 * density * (1.0 - density);
        float grain = max(vellumGrain, 0.08);
        density = clamp(
            density * (1.0 + (fibre - 0.5) * grain * (1.6 + 2.2 * rim))
                + (hash(floor(origin)) - 0.5) * rim / 200.0,
            0.0,
            1.0
        );
        // Hard zero before the box edge so Compose never shears the drop.
        float clipStart = mix(0.92, 1.06, fill);
        density *= 1.0 - smoother(clamp((r - clipStart) / 0.10, 0.0, 1.0));
        float coverage = vellumCoverage(density);
        // Guide reservoir: denser pigment in the body, not a flat oval.
        float sourcePool = smoother(clamp((0.55 - r) / 0.55, 0.0, 1.0));
        coverage = 1.0 - pow(1.0 - coverage, 1.0 + 0.55 * sourcePool * fill);
        half alpha = inkColor.a * half(coverage * appear);
        return half4(inkColor.rgb * alpha, alpha);
    }
"""

private fun Offset.normalized(): Offset {
    val length = hypot(x, y)
    return if (length > 1e-4f) this / length else Offset(1f, 0f)
}
