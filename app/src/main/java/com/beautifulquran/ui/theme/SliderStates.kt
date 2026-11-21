package com.beautifulquran.ui.theme

import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/*
 * Material3 1.5 deprecates the value-based Slider/RangeSlider in favour of the
 * stateful overloads (which also own the custom thumb/track slots). Our sliders
 * keep the caller's value as the source of truth: these states mirror it on
 * every composition, and the stateful slider reports drags via onValueChange.
 * The write lands before the slider reads the state in the same composition.
 */

/** A [SliderState] that follows [value] (coerced into [valueRange]). */
@Composable
fun rememberMirroredSliderState(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
): SliderState {
    val shown = value.coerceIn(valueRange)
    val state = remember(valueRange, steps) { SliderState(shown, steps, valueRange) }
    if (state.value != shown) state.value = shown
    return state
}

/** A [RangeSliderState] that follows [start]..[end] within [valueRange]. */
@Composable
fun rememberMirroredRangeSliderState(
    start: Float,
    end: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
): RangeSliderState {
    val state = remember(valueRange, steps) { RangeSliderState(start, end, steps, valueRange) }
    if (state.startValue != start) state.startValue = start
    if (state.endValue != end) state.endValue = end
    return state
}
