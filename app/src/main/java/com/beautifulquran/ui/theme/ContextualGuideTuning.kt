package com.beautifulquran.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Live GPU parameters for the contextual guide's progressive vellum field. */
@Immutable
data class ContextualGuideTuning(
    val bodyEdge: Float = 0.4f,
    val featherWidth: Float = 0.4f,
    val fadeSoftness: Float = 1.4f,
    val blurRadiusDp: Float = 14f,
    val blurStrength: Float = 0.9f,
    val vellumGrain: Float = 0.1f,
    val verticalTaper: Float = 0.14f,
)

/** Snapshot-backed bridge between the renderer and developer Ink Lab. */
object ContextualGuideStyle {
    var tuning: ContextualGuideTuning by mutableStateOf(ContextualGuideTuning())
        internal set
}
