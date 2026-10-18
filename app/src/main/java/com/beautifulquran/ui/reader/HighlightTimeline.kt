package com.beautifulquran.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.beautifulquran.domain.HighlightFrame
import com.beautifulquran.domain.HighlightSync
import com.beautifulquran.domain.HighlightSyncState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** One display clock; its snapshot reads belong in draw or derived state, never the reader root. */
internal class HighlightTimeline {
    val sample = mutableStateOf<HighlightSyncState?>(null)
    private val frameNanos = mutableLongStateOf(0L)

    private val projected = derivedStateOf { sample.value?.let { HighlightSync.frame(it, frameNanos.longValue) } }
    fun frame(): HighlightFrame? = projected.value

    fun update(state: HighlightSyncState?) {
        sample.value = state
        frameNanos.longValue = state?.sample?.sampledAtNanos ?: 0L
    }

    fun tick(nanos: Long) {
        frameNanos.longValue = nanos
    }
}

internal val LocalHighlightTimeline = staticCompositionLocalOf { HighlightTimeline() }

/** Stops observation with the reader lifecycle; reopening waits for a fresh coherent sample. */
@Composable
internal fun ProvideHighlightTimeline(states: StateFlow<HighlightSyncState?>, content: @Composable () -> Unit) {
    val timeline = remember { HighlightTimeline() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(states, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                states.collectLatest { state ->
                    timeline.update(state)
                    if (state?.sample?.advances == true) {
                        while (coroutineContext.isActive) withFrameNanos { nanos ->
                            timeline.tick(nanos)
                            com.beautifulquran.DevProfiling.recordHighlightFrame(state, nanos)
                        }
                    }
                }
            } finally { timeline.update(null) }
        }
    }
    CompositionLocalProvider(LocalHighlightTimeline provides timeline, content = content)
}
