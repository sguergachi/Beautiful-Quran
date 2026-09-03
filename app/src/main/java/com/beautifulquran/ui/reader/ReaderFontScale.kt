package com.beautifulquran.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.beautifulquran.ui.settings.pinchFontScale

/** Pinch-only reader gesture; one-finger scroll and taps remain with their children. */
internal fun Modifier.readerFontScalePinch(
    currentScale: () -> Float,
    onScale: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var openingSpan = 0f
        var openingScale = 1f
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val span = (pressed[0].position - pressed[1].position).getDistance()
                if (!pinching) {
                    openingSpan = span
                    openingScale = currentScale()
                    pinching = true
                } else if (openingSpan > 0f) {
                    val scale = pinchFontScale(openingScale, span / openingSpan)
                    if (scale != currentScale()) onScale(scale)
                }
                event.changes.forEach { it.consume() }
            } else if (pinching) {
                event.changes.forEach { it.consume() }
            }
            if (pressed.isEmpty()) break
        }
    }
}
