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

/** Outer gilt rule is a square fillet — 3 dp, not the screen's curve. */
const val COVER_OUTER_CORNER_RADIUS_DP = 3f

/**
 * Gilt-frame insets, corner radii, and corner-ornament size for the
 * entrance cover.
 *
 * The **outer** rule is a square 3 dp fillet — a tooled plate, not a
 * hoop concentric with the phone. The **inner** rule stays concentric
 * with the display ([R − D]) so the opening still follows the
 * silhouette. The khatam at each corner is sized to the band it is
 * seated in, so it reads as a hub of the border rather than an
 * ornament laid over its edge.
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
 * [density] is px-per-dp. The outer inset scales with the screen radius
 * (~48% of the largest corner) and is clamped so every phone gets a
 * generous gilt margin. The outer corners are a fixed 3 dp square
 * fillet. The inner rule sits a fixed gap inside the outer and stays
 * concentric with the display; corner stars span that gap so they read
 * as pressed seals set into the band, not pinpricks over it.
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

    // Corner seals are hubs of the border band, not stamps in the margin:
    // the seal's outer extent is half the band, so its bezel tips land on
    // the two gilt rules. Sizing it from the outer inset instead let the
    // seal grow past the outer rule on phones whose margin is wider than
    // the band (the ornament's bezel reaches well past its nominal box).
    val starRadius = ruleGap / 2f

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

    val outerR = COVER_OUTER_CORNER_RADIUS_DP * density
    return CoverFrameGeometry(
        outerInsetPx = outerInset,
        innerInsetPx = innerInset,
        outerCorners = ScreenCornerRadiiPx(outerR, outerR, outerR, outerR),
        innerCorners = corners(innerInset),
        starRadiusPx = starRadius,
    )
}
