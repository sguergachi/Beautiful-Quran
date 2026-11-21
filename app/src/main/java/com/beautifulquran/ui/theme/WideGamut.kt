package com.beautifulquran.ui.theme

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.ColorSpace

/**
 * Puts [activity]'s window in wide-colour-gamut mode when that costs nothing,
 * and says whether it did; the answer goes to [BeautifulQuranTheme], which
 * hands out the Display P3 accents only on such a window.
 *
 * Wide colour is free only when the display composes wide gamut as 8-bit
 * Display P3. HWUI then renders into the same RGBA_8888 buffers it uses for
 * sRGB, only tagged P3: no extra memory, no extra bandwidth at 120 Hz. A
 * device whose wide-gamut preference is scRGB composes in RGBA_F16 instead —
 * twice the bytes per pixel on every buffer and layer — so it is left in sRGB.
 * The preference is the same one HWUI reads (`DeviceInfo`'s wide colour type),
 * which Pixels set to Display P3 at RGBA_8888.
 */
fun enableWideGamutIfFree(activity: Activity): Boolean {
    if (!activity.resources.configuration.isScreenWideColorGamut) return false
    val preferred = activity.display?.preferredWideGamutColorSpace ?: return false
    if (preferred != ColorSpace.get(ColorSpace.Named.DISPLAY_P3)) return false
    activity.window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
    return true
}
