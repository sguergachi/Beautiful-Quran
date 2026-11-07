package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.WrappingMediaSource

/**
 * Enter the recording before its first word, including automatic transitions.
 * Only the default entry changes; positions, duration and PCM keep the original
 * file clock. No audio is played from zero and then interrupted by a UI seek.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class HizbMediaSource(
    source: MediaSource,
    private val startMs: Long,
    private val enabled: () -> Boolean,
) : WrappingMediaSource(source) {
    /**
     * The entry must be known before preparation. With Media3's own placeholder
     * the player starts an unprepared item at 0, then MaskingMediaSource moves
     * it to the real default without a discontinuity: the controller reports 0,
     * then 3933 ms unannounced, and the ink clock's settle guard snaps the
     * first words full (Hani 4:148 played from its verse).
     */
    override fun getInitialTimeline(): Timeline =
        (super.getInitialTimeline() ?: unpreparedProgressiveTimeline(mediaItem)).withOpening()

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        refreshSourceInfo(newTimeline.withOpening())
    }

    private fun Timeline.withOpening(): Timeline = HizbOpeningTimeline(this, startMs, enabled)

    class Factory(
        private val delegate: MediaSource.Factory,
        private val openingMs: (MediaItem) -> Long,
        private val enabled: () -> Boolean,
    ) : MediaSource.Factory by delegate {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val source = delegate.createMediaSource(mediaItem)
            val opening = openingMs(mediaItem)
            return if (opening > 0L) HizbMediaSource(source, opening, enabled) else source
        }
    }
}

/** A default entry is a file position, not a new zero or a clipped duration. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class HizbOpeningTimeline(
    timeline: Timeline,
    private val startMs: Long,
    private val enabled: () -> Boolean,
) : ForwardingTimeline(timeline) {
    override fun getWindow(index: Int, window: Window, projectionUs: Long): Window =
        super.getWindow(index, window, projectionUs).also {
            if (enabled()) it.defaultPositionUs = startMs * 1_000L
        }
}

/**
 * Placeholder in the shape ProgressiveMediaSource reports on preparation: a
 * [SinglePeriodTimeline], so the period uid matches the real timeline and
 * MaskingMediaSource keeps its period. Unknown duration, not yet seekable,
 * default entry 0 (as Media3's own placeholder) until [HizbOpeningTimeline]
 * moves it.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun unpreparedProgressiveTimeline(mediaItem: MediaItem): Timeline =
    object : ForwardingTimeline(
        SinglePeriodTimeline(
            /* durationUs= */ C.TIME_UNSET,
            /* isSeekable= */ false,
            /* isDynamic= */ true,
            /* useLiveConfiguration= */ false,
            /* manifest= */ null,
            mediaItem,
        ),
    ) {
        override fun getWindow(index: Int, window: Window, projectionUs: Long): Window =
            super.getWindow(index, window, projectionUs).also {
                it.defaultPositionUs = 0L
                it.isPlaceholder = true
            }

        override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period =
            super.getPeriod(index, period, setIds).also { it.isPlaceholder = true }
    }
