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
 * Leather margin and band width are fixed (22–40 dp and 26 dp). Outer
 * corners follow the screen ([R − outerInset]). Inner corners are the
 * only thing that changes: **innerRadius = outerRadius − gap**, floored
 * so a leftover of 0 does not square the opening. The khatam is sized
 * to the (unchanged) band.
 */
data class CoverFrameGeometry(
    val outerInsetPx: Float,
    val innerInsetPx: Float,
    val outerCorners: ScreenCornerRadiiPx,
    val innerCorners: ScreenCornerRadiiPx,
    /**
     * On-screen radius of each corner khatam — the distance from the seal's
     * centre to its outermost feature (the bezel tip), not the radius of
     * some nominal box the ornament then overhangs. The seal is seated on
     * the band's centreline, so this is half the border's thickness: the
     * khatam fills the band exactly and never breaks either gilt rule.
     */
    val starRadiusPx: Float,
)

/**
 * Derive a cover-frame geometry from the display's corner radii.
 *
 * [density] is px-per-dp. Margin and band width do not change with the
 * screen curve. Only the inner corner radius is derived: outer − gap,
 * never below 20 dp so the opening stays a fillet.
 */
fun coverFrameGeometry(
    screen: ScreenCornerRadiiPx,
    density: Float,
): CoverFrameGeometry {
    val minInset = 22f * density
    val maxInset = 40f * density
    val ruleGap = 26f * density
    val minInnerR = 20f * density
    val fallbackR = 36f * density
    val designR = if (screen.max > 0f) screen.max else fallbackR

    val outerInset = (designR * 0.48f)
        .coerceIn(minInset, maxInset)
        .coerceAtMost(designR * 0.55f)
    val innerInset = outerInset + ruleGap
    val starRadius = ruleGap / 2f

    fun concentric(r: Float, inset: Float): Float {
        val base = if (screen.max > 0f) r else fallbackR
        return (base - inset).coerceAtLeast(0f)
    }

    val outerCorners = ScreenCornerRadiiPx(
        topLeft = concentric(screen.topLeft, outerInset),
        topRight = concentric(screen.topRight, outerInset),
        bottomRight = concentric(screen.bottomRight, outerInset),
        bottomLeft = concentric(screen.bottomLeft, outerInset),
    )
    // Only the inner radius changes: outer − gap, floored so a leftover
    // of 0 does not square the opening. Band width stays [ruleGap].
    fun innerOf(outer: Float) = (outer - ruleGap).coerceAtLeast(minInnerR)
    val innerCorners = ScreenCornerRadiiPx(
        topLeft = innerOf(outerCorners.topLeft),
        topRight = innerOf(outerCorners.topRight),
        bottomRight = innerOf(outerCorners.bottomRight),
        bottomLeft = innerOf(outerCorners.bottomLeft),
    )

    return CoverFrameGeometry(
        outerInsetPx = outerInset,
        innerInsetPx = innerInset,
        outerCorners = outerCorners,
        innerCorners = innerCorners,
        starRadiusPx = starRadius,
    )
}
