package com.beautifulquran.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
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
    override fun getInitialTimeline(): Timeline? = super.getInitialTimeline()?.withOpening()

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
