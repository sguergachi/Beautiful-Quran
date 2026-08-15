package com.beautifulquran.ui.entrance

/**
 * Display corner radii in pixels, as reported by
 * [android.view.WindowInsets.getRoundedCorner] (API 31+). Zeros mean the
 * platform did not expose a radius (pre-31, square emulator, or the corner
 * lies outside the window).
 */
data class ScreenCornerRadiiPx(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    val max: Float get() = maxOf(topLeft, topRight, bottomRight, bottomLeft)

    companion object {
        val Zero = ScreenCornerRadiiPx(0f, 0f, 0f, 0f)
    }
}

/**
 * Safe-area insets for the entrance cover frame, in pixels. Union of system
 * bars (ignoring visibility) and display cutout — the ceremony hides the
 * status bar but still reserves that band so the gilt rule never jumps.
 */
data class CoverSafeInsetsPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    companion object {
        val Zero = CoverSafeInsetsPx(0, 0, 0, 0)
    }
}

/**
 * Horizontal and vertical frame margins in dp for the cover's gilt rule.
 *
 * Book proportions: generous head/foot, tighter fore-edges. Each side is
 * floored to clear [safe]; the fore-edge only widens (never past the base
 * margin) as far as it must to seat the isti'adha inside the inner rule.
 */
fun coverFrameMarginsDp(
    density: Float,
    safe: CoverSafeInsetsPx,
    screenWidthPx: Float,
    duaWidthPx: Float,
    innerInsetPx: Float,
): Pair<Float, Float> {
    val breathing = 8f * density
    val baseH = 16f * density
    val baseV = 44f * density
    val floorH = maxOf(
        10f * density,
        safe.left + breathing,
        safe.right + breathing,
    )
    val duaGap = 14f * density
    val allowH = screenWidthPx / 2f - duaWidthPx / 2f - duaGap - innerInsetPx
    val hPx = allowH.coerceAtMost(baseH).coerceAtLeast(floorH)
    val vPx = maxOf(
        baseV,
        safe.top + breathing,
        safe.bottom + breathing,
    )
    return (hPx / density) to (vPx / density)
}

/**
 * Concentric gilt-frame insets, corner radii, and corner-ornament size for
 * the entrance cover.
 *
 * For a screen corner of radius [R] and a uniform inset [D], each frame
 * corner is [R − D] — the classic concentric rounded-rect relationship —
 * so the doubled gilt rule reads as designed for that phone's silhouette
 * rather than a fixed square-ish border floating inside it. The khatam
 * star at each corner scales with the inset so the ornament stays in
 * proportion to the margin it sits in.
 */
data class CoverFrameGeometry(
    val outerInsetPx: Float,
    val innerInsetPx: Float,
    val outerCorners: ScreenCornerRadiiPx,
    val innerCorners: ScreenCornerRadiiPx,
    /** Radius of each corner khatam, sized to the frame's margin. */
    val starRadiusPx: Float,
)

/**
 * Derive a cover-frame geometry from the display's corner radii.
 *
 * [density] is px-per-dp. The outer inset scales with the screen radius
 * (~48% of the largest corner) and is clamped so every phone gets a
 * generous gilt margin without flattening the concentric curve. The inner
 * rule sits a fixed gap inside the outer; corner stars scale with the
 * outer inset so they read as pressed seals, not pinpricks.
 */
fun coverFrameGeometry(
    screen: ScreenCornerRadiiPx,
    density: Float,
): CoverFrameGeometry {
    val minInset = 22f * density
    val maxInset = 40f * density
    // The border zone between the two rules carries the generated frieze —
    // a real mushaf border band, not a pinstripe — so the gap is generous.
    val ruleGap = 26f * density
    // Sharp-cornered surfaces still need a designed frame; invent a modest
    // radius so the gilt rule does not collapse to a hard rectangle.
    val fallbackR = 36f * density
    val designR = if (screen.max > 0f) screen.max else fallbackR

    // Leave at least ~45% of the design radius on the outer rule so the
    // corner still reads as a curve, not a clipped square.
    val outerInset = (designR * 0.48f)
        .coerceIn(minInset, maxInset)
        .coerceAtMost(designR * 0.55f)
    // The inner rule sits the full band gap inside; concentric() floors its
    // corner radius at zero when the curve is used up.
    val innerInset = outerInset + ruleGap

    // Corner seals: ~70% of the outer margin. Floor at 18 dp when the
    // margin can hold it; on tight radii, stay inside the available inset.
    val starFloor = minOf(18f * density, outerInset * 0.85f)
    val starRadius = (outerInset * 0.70f)
        .coerceIn(starFloor, 28f * density)

    fun concentric(r: Float, inset: Float): Float {
        val base = if (screen.max > 0f) r else fallbackR
        return (base - inset).coerceAtLeast(0f)
    }

    fun corners(inset: Float) = ScreenCornerRadiiPx(
        topLeft = concentric(screen.topLeft, inset),
        topRight = concentric(screen.topRight, inset),
        bottomRight = concentric(screen.bottomRight, inset),
        bottomLeft = concentric(screen.bottomLeft, inset),
    )

    return CoverFrameGeometry(
        outerInsetPx = outerInset,
        innerInsetPx = innerInset,
        outerCorners = corners(outerInset),
        innerCorners = corners(innerInset),
        starRadiusPx = starRadius,
    )
}

/**
 * Slimmer open-page frame: enough margin for a generated frieze and corner
 * seals, without the cover's leather-board insets eating the text block.
 */
fun mushafPageFrameGeometry(density: Float): CoverFrameGeometry {
    val outer = 14f * density
    val gap = 5f * density
    val curve = 18f * density
    val inner = outer + gap
    fun corners(inset: Float) = ScreenCornerRadiiPx(
        topLeft = (curve - inset).coerceAtLeast(5f * density),
        topRight = (curve - inset).coerceAtLeast(5f * density),
        bottomRight = (curve - inset).coerceAtLeast(5f * density),
        bottomLeft = (curve - inset).coerceAtLeast(5f * density),
    )
    return CoverFrameGeometry(
        outerInsetPx = outer,
        innerInsetPx = inner,
        outerCorners = corners(outer),
        innerCorners = corners(inner),
        starRadiusPx = (outer * 0.62f).coerceIn(6f * density, 8f * density),
    )
}
