package com.beautifulquran.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Live GPU parameters for the contextual guide's progressive vellum field. */
@Immutable
data class ContextualGuideTuning(
    val bodyEdge: Float = 0.5f,
    val featherWidth: Float = 0.2819f,
    val fadeSoftness: Float = 1.3329f,
    val blurRadiusDp: Float = 24f,
    val blurStrength: Float = 1f,
    val vellumGrain: Float = 0.0297f,
    val verticalTaper: Float = 0.24f,
)

/** Snapshot-backed bridge between the renderer and developer Ink Lab. */
object ContextualGuideStyle {
    var tuning: ContextualGuideTuning by mutableStateOf(ContextualGuideTuning())
        internal set
}
