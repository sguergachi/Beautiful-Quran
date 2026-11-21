package com.beautifulquran.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Light status-bar icons (clock, battery) while this is composed, restoring
 * the previous appearance when it leaves. The developer labs are always dark
 * workbenches; without this the reader's dark icons carry over and read as
 * black-on-black.
 */
@Composable
fun LightStatusBarIconsWhileShown() {
    val view = LocalView.current
    val window = LocalActivity.current?.window
    DisposableEffect(view, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (previous != null) controller.isAppearanceLightStatusBars = previous
        }
    }
}
