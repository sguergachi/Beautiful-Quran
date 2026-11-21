package com.beautifulquran.ui.theme

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.ColorSpace

/**
 * Puts [activity]'s window in wide-colour-gamut mode when that costs nothing,
 * or back in the default sRGB mode when it does not, and says which it ended
 * in. The answer goes to [BeautifulQuranTheme], which hands out the Display P3
 * accents only on a wide window and the exact sRGB accents everywhere else.
 *
 * Wide colour is free only when the display composes wide gamut as 8-bit
 * Display P3. HWUI then renders into the same RGBA_8888 buffers it uses for
 * sRGB, only tagged P3: no extra memory, no extra bandwidth at 120 Hz. A
 * device whose wide-gamut preference is scRGB composes in RGBA_F16 instead —
 * twice the bytes per pixel on every buffer and layer — so it is left in sRGB.
 * The preference is the same one HWUI reads (`DeviceInfo`'s wide colour type),
 * which Pixels set to Display P3 at RGBA_8888.
 *
 * Falling back is graceful by construction:
 * - an sRGB panel, or one whose wide preference is not Display P3, stays (or
 *   is put back) in the default mode and gets the sRGB accents bit for bit;
 * - the window has the last word: P3 is reported only when
 *   [android.view.Window.isWideColorGamut] confirms the mode took and the
 *   display backs it, otherwise the mode is reset and sRGB is reported;
 * - anything the platform throws while asking means sRGB;
 * - it is called again on every configuration change, so a window that moves
 *   to an sRGB display (a fold's other panel, an external screen) drops the
 *   P3 accents instead of drawing them clipped.
 *
 * Off-window renders never see the P3 set: shared images are composed in
 * their own sRGB theme (ShareImageRenderer), and every gradient keeps its
 * stops in one colour space (OneSpace.kt).
 */
fun applyWideGamutIfFree(activity: Activity): Boolean {
    val window = activity.window
    return try {
        val wanted = activity.resources.configuration.isScreenWideColorGamut &&
            activity.display?.preferredWideGamutColorSpace == ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val mode = if (wanted) ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT else ActivityInfo.COLOR_MODE_DEFAULT
        if (window.colorMode != mode) window.colorMode = mode
        val wide = wanted && window.isWideColorGamut
        if (wanted && !wide) window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        wide
    } catch (_: RuntimeException) {
        runCatching { window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT }
        false
    }
}
