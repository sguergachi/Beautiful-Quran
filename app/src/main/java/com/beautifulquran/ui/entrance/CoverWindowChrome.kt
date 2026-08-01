package com.beautifulquran.ui.entrance

import android.app.Activity
import android.os.Build
import android.view.RoundedCorner
import androidx.core.view.WindowInsetsCompat

/**
 * Window chrome the entrance cover needs before Compose's inset mirror is
 * ready: screen-corner radii and safe-area insets (system bars ignoring
 * visibility ∪ display cutout). Read from [Activity.readCoverWindowChrome]
 * in `onCreate` after `enableEdgeToEdge()` so splash can hand off on the
 * first cover frame without waiting for Compose.
 */
data class CoverWindowChrome(
    val radii: ScreenCornerRadiiPx,
    val safeInsets: CoverSafeInsetsPx,
)

/**
 * Pre-read cover geometry from [WindowManager.getCurrentWindowMetrics].
 * Available from the first `onCreate` (minSdk 30); does not wait on Compose.
 */
fun Activity.readCoverWindowChrome(): CoverWindowChrome {
    val insets = windowManager.currentWindowMetrics.windowInsets
    val compat = WindowInsetsCompat.toWindowInsetsCompat(insets)
    val bars = compat.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
    val cutout = compat.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())
    val safe = CoverSafeInsetsPx(
        left = maxOf(bars.left, cutout.left),
        top = maxOf(bars.top, cutout.top),
        right = maxOf(bars.right, cutout.right),
        bottom = maxOf(bars.bottom, cutout.bottom),
    )
    return CoverWindowChrome(
        radii = readScreenCornerRadii(insets),
        safeInsets = safe,
    )
}

private fun readScreenCornerRadii(insets: android.view.WindowInsets): ScreenCornerRadiiPx {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ScreenCornerRadiiPx.Zero
    fun r(position: Int): Float =
        insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
    return ScreenCornerRadiiPx(
        topLeft = r(RoundedCorner.POSITION_TOP_LEFT),
        topRight = r(RoundedCorner.POSITION_TOP_RIGHT),
        bottomRight = r(RoundedCorner.POSITION_BOTTOM_RIGHT),
        bottomLeft = r(RoundedCorner.POSITION_BOTTOM_LEFT),
    )
}
