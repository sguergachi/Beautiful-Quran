package com.beautifulquran.playback

import com.beautifulquran.domain.HighlightItem
import com.beautifulquran.domain.HighlightPlaybackSample

/**
 * Reject a handoff or state change spanning a read. A missed sample is safer
 * than associating a new file's position with the previous file's identity.
 * All arguments are values so the production adapter's guard is testable off-device.
 */
internal fun coherentHighlightSample(
    expected: NowPlaying,
    before: PlayerUiState,
    after: PlayerUiState,
    liveBefore: NowPlaying?,
    liveAfter: NowPlaying?,
    positionMs: Long,
    sampledAtNanos: Long,
): HighlightPlaybackSample? {
    if (before != after || before.nowPlaying != expected ||
        liveBefore != expected || liveAfter != expected || positionMs < 0L
    ) return null
    return HighlightPlaybackSample(
        HighlightItem(expected.surahId, expected.ayah, expected.reciterId),
        positionMs, sampledAtNanos, before.isPlaying, before.isBuffering, before.speed,
        before.positionEvents.clockId, before.positionEvents.inkId,
    )
}
