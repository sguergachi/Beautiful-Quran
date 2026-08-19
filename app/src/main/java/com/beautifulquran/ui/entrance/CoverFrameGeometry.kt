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
 * so **both** gilt rules share the phone's silhouette. The inner radius
 * is the outer minus the band gap. The khatam at each corner is sized to
 * the band it is seated in, so it reads as a hub of the border rather
 * than an ornament laid over its edge.
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
 * generous gilt margin. The inner rule uses the same [R − D] law, so
 * the pair stays concentric. If the 26 dp band would eat the leftover
 * curve (inner radius 0 → a square opening), the margin, then the gap,
 * shrink so the inner rule keeps a visible concentric fillet. Corner
 * stars span that gap so they read as pressed seals set into the band.
 */
fun coverFrameGeometry(
    screen: ScreenCornerRadiiPx,
    density: Float,
): CoverFrameGeometry {
    val minInset = 22f * density
    val maxInset = 40f * density
    val minOuterInset = 12f * density
    val minGap = 16f * density
    val minInnerR = 8f * density
    // The border zone between the two rules carries the generated frieze —
    // a real mushaf border band, not a pinstripe — so the gap starts generous.
    var ruleGap = 26f * density
    // Sharp-cornered surfaces still need a designed frame; invent a modest
    // radius so the gilt rule does not collapse to a hard rectangle.
    val fallbackR = 36f * density
    val designR = if (screen.max > 0f) screen.max else fallbackR
    // Size the leftover curve from the tightest real corner so no side
    // of the inner rule squares off.
    val positives = listOf(
        screen.topLeft, screen.topRight, screen.bottomRight, screen.bottomLeft,
    ).filter { it > 0f }
    val curveR = if (positives.isEmpty()) fallbackR else positives.min()

    // Leave at least ~45% of the design radius on the outer rule so the
    // corner still reads as a curve, not a clipped square.
    var outerInset = (designR * 0.48f)
        .coerceIn(minInset, maxInset)
        .coerceAtMost(designR * 0.55f)
    // A 26 dp band on a ~50 dp phone eats R − outerInset and the inner
    // rule becomes a square. Pull the outer rule toward the edge, then
    // slim the band, until the inner fillet keeps [minInnerR].
    val room = curveR - minInnerR
    if (room >= minOuterInset + minGap && outerInset + ruleGap > room) {
        outerInset = (room - ruleGap).coerceAtLeast(minOuterInset)
        if (outerInset + ruleGap > room) {
            ruleGap = (room - outerInset).coerceAtLeast(minGap)
        }
    }
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

    return CoverFrameGeometry(
        outerInsetPx = outerInset,
        innerInsetPx = innerInset,
        outerCorners = corners(outerInset),
        innerCorners = corners(innerInset),
        starRadiusPx = starRadius,
    )
}
