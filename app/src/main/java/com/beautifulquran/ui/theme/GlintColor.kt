package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** The accepted vocal crest turns wet ink white; its falling edge restores the base hue. */
internal fun glintPulseColor(base: Color, peak: Float): Color =
    lerp(base, Color.White, peak.coerceIn(0f, 1f))
