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
        var acceptedScale = 1f
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            var firstPressed = -1
            var secondPressed = -1
            for (index in event.changes.indices) {
                if (!event.changes[index].pressed) continue
                if (firstPressed < 0) firstPressed = index else {
                    secondPressed = index
                    break
                }
            }
            if (secondPressed >= 0) {
                val span = (
                    event.changes[firstPressed].position - event.changes[secondPressed].position
                ).getDistance()
                if (!pinching) {
                    openingSpan = span
                    openingScale = currentScale()
                    acceptedScale = openingScale
                    pinching = true
                } else if (openingSpan > 0f) {
                    val scale = pinchFontScale(openingScale, span / openingSpan, acceptedScale)
                    if (scale != acceptedScale) {
                        acceptedScale = scale
                        onScale(scale)
                    }
                }
                event.changes.forEach { it.consume() }
            } else if (pinching) {
                event.changes.forEach { it.consume() }
            }
            if (firstPressed < 0) break
        }
    }
}
