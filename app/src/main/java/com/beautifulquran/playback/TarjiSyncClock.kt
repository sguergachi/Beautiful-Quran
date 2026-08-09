package com.beautifulquran.playback

/** Exact content duration represented by one decimated analysis hop. */
internal fun analysisHopContentMs(
    sourceSampleRate: Int,
    decimation: Int,
    hopSamples: Int,
): Double = hopSamples * decimation * 1_000.0 / sourceSampleRate

/**
 * Stable tap-to-playback-head clock for one sink session.
 *
 * The sink capacity supplies the initial absolute delay; thereafter the tap
 * and playback-head content clocks measure only queue growth or drain. This
 * avoids pretending a late UI poll happened at the beginning of the session.
 */
internal data class TarjiBacklogAnchor(
    val tapContentMs: Double,
    val playbackContentMs: Long,
    val backlogContentMs: Double,
    val speed: Float,
) {
    fun estimate(tapContentMs: Double, playbackContentMs: Long): Double =
        (
            backlogContentMs +
                (tapContentMs - this.tapContentMs) -
                (playbackContentMs - this.playbackContentMs)
            ).coerceIn(0.0, MAX_BACKLOG_CONTENT_MS)

    companion object {
        /** Wait until the tap has supplied one sink buffer before anchoring it. */
        fun isReady(
            tapContentMs: Double,
            sinkLatencyMs: Long,
            speed: Float,
        ): Boolean = sinkLatencyMs > 0L && tapContentMs >= sinkContentMs(sinkLatencyMs, speed)

        fun capture(
            tapContentMs: Double,
            playbackContentMs: Long,
            sinkLatencyMs: Long,
            speed: Float,
        ): TarjiBacklogAnchor {
            val safeTapMs = tapContentMs.coerceAtLeast(0.0)
            val sinkContentMs = sinkContentMs(sinkLatencyMs, speed)
            val initialMs = if (sinkContentMs > 0f) {
                minOf(safeTapMs, sinkContentMs)
            } else {
                safeTapMs
            }
            return TarjiBacklogAnchor(
                tapContentMs = safeTapMs,
                playbackContentMs = playbackContentMs,
                backlogContentMs = initialMs.coerceAtMost(MAX_BACKLOG_CONTENT_MS),
                speed = speed,
            )
        }

        private fun sinkContentMs(sinkLatencyMs: Long, speed: Float): Double =
            sinkLatencyMs.coerceAtLeast(0L) * speed.coerceAtLeast(0f).toDouble()

        private const val MAX_BACKLOG_CONTENT_MS = 400.0
    }
}
