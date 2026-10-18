package com.beautifulquran.domain

/** A sample comparison reports the implementation actually drawing, not the newest compiled engine. */
data class HighlightObservation(
    val visibleEngineVersion: Int,
    val baselinePositionMs: Long,
    val baselineActive: HighlightEngine.ActiveInfo?,
    val candidate: HighlightFrame?,
    val provisionalBaselineArm: Boolean,
    val baselineRearm: Boolean,
    val baselineCheckpoint: HighlightClock.Checkpoint?,
    val baselineCheckpointItem: HighlightItem?,
    val syncState: HighlightSyncState?,
    val syncCheckpoint: HighlightSync.Checkpoint?,
) {
    val visiblePositionMs: Long get() = if (visibleEngineVersion == 2) candidate!!.inkMs else baselinePositionMs
    val visibleActive: HighlightEngine.ActiveInfo? get() =
        if (visibleEngineVersion == 2) candidate?.active else baselineActive
}

/**
 * Production V2 and its diagnostic V1 comparison share identical
 * inputs. This coordinator preserves V1's calibration/rearm semantics while
 * keeping both clock implementations independent. V1 selects ink only in baseline replay tests.
 * It owns no player or timer.
 */
class HighlightSession(private val visibleEngineVersion: Int = 1) {
    init { require(visibleEngineVersion == 1 || visibleEngineVersion == 2) }
    private val baseline = HighlightClock()
    private var clockRevision: Long? = null
    private var lagMs: Long? = null
    private var leadMs: Long? = null
    private var candidate: HighlightSyncState? = null
    private var captureId: Long? = null
    private var provisionalArm = false
    private var baselineItem: HighlightItem? = null

    /** Called when the shared reader observation stops, without changing playback itself. */
    fun suspendObservation(atNanos: Long) {
        candidate = candidate?.let { HighlightSync.suspendObservation(it, atNanos) }
    }

    /** Preserve V1's provisional tap latch; V2 still waits for player revision confirmation. */
    fun acceptNextSample() {
        provisionalArm = true
        baseline.acceptNextSample()
    }

    /** A new [shadowCaptureId] starts a fresh comparison; production V2 keeps its live state. */
    fun observe(
        sample: HighlightPlaybackSample,
        timing: HighlightTimingContext,
        calibration: HighlightCalibration,
        shadowCaptureId: Long? = null,
    ): HighlightObservation {
        val captureStarting = shadowCaptureId != null && shadowCaptureId != captureId
        val checkpoint = if (captureStarting) baseline.checkpoint() else null
        val checkpointItem = baselineItem
        val lag = OutputLatency.mediaLagMs(calibration.outputLagWallMs, sample.speed)
        val rearm = provisionalArm || clockRevision != sample.clockRevision ||
            lagMs != lag || leadMs != calibration.wordLeadMs
        if (rearm) {
            baseline.acceptNextSample()
        }
        clockRevision = sample.clockRevision
        lagMs = lag
        leadMs = calibration.wordLeadMs
        val position = baseline.sample(
            sample.item, calibration.inkMs(sample.positionMs, sample.speed, timing.voiceStartMs),
        )
        if (visibleEngineVersion == 1 && shadowCaptureId != captureId) candidate = null
        captureId = shadowCaptureId
        candidate = if (shadowCaptureId == null && visibleEngineVersion == 1) null else
            HighlightSync.sample(candidate, sample, timing, calibration)
        val observation = HighlightObservation(
            visibleEngineVersion, position, timing.timings.activeInfo(position),
            candidate?.let { HighlightSync.frame(it, sample.sampledAtNanos) },
            provisionalArm,
            rearm, checkpoint, checkpointItem,
            candidate, candidate?.takeIf { captureStarting }?.let(HighlightSync::checkpoint),
        )
        baselineItem = sample.item
        provisionalArm = false
        return observation
    }
}
