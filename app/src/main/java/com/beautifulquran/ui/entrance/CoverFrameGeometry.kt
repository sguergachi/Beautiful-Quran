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
 * Nested rounded rects share a corner centre. The rule (Cloud Four, iOS
 * ConcentricRectangle) is **innerRadius = outerRadius − gap**. Same as
 * [R − D] from the screen: outer = R − outerInset, inner = R − innerInset
 * = outer − band. If the band is wider than the leftover curve, inner
 * floors at 0 and reads as a square — so the margin and gap must fit
 * inside R minus a visible inner fillet. The khatam is sized to the band.
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
 * [density] is px-per-dp. The band and leather margin must fit inside
 * the tightest screen corner minus a visible inner fillet, otherwise
 * `inner = outer − gap` hits 0 and the opening squares off. Inner
 * corners are derived from the outer (`outer − gap`), never independently.
 */
fun coverFrameGeometry(
    screen: ScreenCornerRadiiPx,
    density: Float,
): CoverFrameGeometry {
    val preferInset = 22f * density
    val maxInset = 40f * density
    val minInset = 12f * density
    val preferGap = 26f * density
    val minGap = 16f * density
    // Below ~20 dp a fillet reads as a square next to the outer rule.
    val minInnerR = 20f * density
    val fallbackR = 36f * density
    val designR = if (screen.max > 0f) screen.max else fallbackR
    val positives = listOf(
        screen.topLeft, screen.topRight, screen.bottomRight, screen.bottomLeft,
    ).filter { it > 0f }
    val curveR = if (positives.isEmpty()) fallbackR else positives.min()

    var outerInset = (designR * 0.48f)
        .coerceIn(preferInset, maxInset)
        .coerceAtMost(designR * 0.55f)
    var ruleGap = preferGap
    // innerRadius = outerRadius − gap. Budget R so that leftover
    // (R − outerInset − gap) stays a real curve, not 0–8 dp.
    val room = (curveR - minInnerR).coerceAtLeast(0f)
    if (room > 0f && outerInset + ruleGap > room) {
        if (room >= minInset + minGap) {
            val scale = room / (outerInset + ruleGap)
            outerInset = (outerInset * scale).coerceAtLeast(minInset)
            ruleGap = (room - outerInset).coerceAtLeast(minGap)
            if (outerInset + ruleGap > room) {
                outerInset = (room - ruleGap).coerceAtLeast(minInset)
                ruleGap = (room - outerInset).coerceAtLeast(minGap)
            }
        } else {
            outerInset = room * 0.45f
            ruleGap = room - outerInset
        }
    }
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
    // Textbook pair: innerRadius = outerRadius − gap. Do not recompute
    // from the screen independently (that is how a corner floors to 0
    // while its outer neighbour stays round).
    fun innerOf(outer: Float) = (outer - ruleGap).coerceAtLeast(0f)
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
