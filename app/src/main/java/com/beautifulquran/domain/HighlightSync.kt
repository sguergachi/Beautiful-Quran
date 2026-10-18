package com.beautifulquran.domain

import kotlinx.serialization.Serializable

/** The audio-file identity. Ayah zero denotes the separate basmalah playlist item. */
@Serializable
data class HighlightItem(val surah: Int, val ayah: Int, val reciter: Int)

/**
 * One coherent platform read. Revisions come from authoritative player events,
 * not from guessing that a sufficiently large position change is a seek.
 * [sampledAtNanos] is monotonic time; zero is a valid timestamp in replay.
 */
data class HighlightPlaybackSample(
    val item: HighlightItem,
    val positionMs: Long,
    val sampledAtNanos: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean = false,
    val speed: Float = 1f,
    val clockRevision: Long = 0L,
    val inkGeneration: Long = 0L,
) {
    init {
        require(positionMs >= 0L)
        require(speed.isFinite() && speed > 0f)
        require(clockRevision >= 0L && inkGeneration >= 0L)
    }

    val advances: Boolean get() = isPlaying && !isBuffering
}

/** Immutable timing publication; a replacement must receive a new [revision]. */
data class HighlightTimingContext(
    val revision: Long,
    val timings: HighlightEngine.PreparedTimings,
    val voiceStartMs: Long,
) {
    init { require(revision >= 0L && voiceStartMs >= 0L) }
}

/** Wall-time output correction and media-time word lead have different units. */
data class HighlightCalibration(val outputLagWallMs: Long = 0L, val wordLeadMs: Long = 0L) {
    init { require(outputLagWallMs >= 0L && wordLeadMs >= 0L) }

    fun heardMs(mediaMs: Long, speed: Float): Long =
        OutputLatency.heardMs(mediaMs, OutputLatency.mediaLagMs(outputLagWallMs, speed))

    fun inkMs(mediaMs: Long, speed: Float, voiceStartMs: Long): Long =
        OutputLatency.highlightMs(
            mediaMs, OutputLatency.mediaLagMs(outputLagWallMs, speed), wordLeadMs, voiceStartMs,
        )
}

/** The transport phase is explicit even when no timing occurrence is selected. */
enum class HighlightPhase { MissingTimings, WaitingForVoice, Speaking, GapHold, Paused, Buffering, Completed }

/**
 * A V2 frame carries linear media progress. Arabic pacing and English meaning
 * projection remain separate consumers; this type makes no letter-level claim.
 */
data class HighlightFrame(
    val item: HighlightItem,
    val inkGeneration: Long,
    val timingRevision: Long,
    val mediaMs: Long,
    val heardMs: Long,
    val inkMs: Long,
    val phase: HighlightPhase,
    val occurrence: Int?,
    val active: HighlightEngine.ActiveInfo?,
    val linearProgress: Float,
)

/** Immutable reconciliation state, suitable for deterministic sample/frame replay. */
@ConsistentCopyVisibility
data class HighlightSyncState internal constructor(
    val sample: HighlightPlaybackSample,
    val timing: HighlightTimingContext,
    val calibration: HighlightCalibration,
    val acceptedMediaMs: Long,
    internal val projectionBaseMs: Long,
    internal val retainedInkMs: Long,
    internal val settleStartNanos: Long,
    internal val stableSamples: Int,
)

/**
 * Candidate HighlightEngine V2 time authority. No time is acquired here.
 *
 * The settle guard uses elapsed wall time and playback speed rather than a
 * presumed 33 ms poll. Backward corrections never start a new performance.
 * Continuous frame projection is deliberately not capped at the next poll.
 * This core runs in shadow until the migration and device gates are complete.
 */
object HighlightSync {
    const val ENGINE_VERSION = 2
    const val BASELINE_ENGINE_VERSION = 1
    private const val MIN_SETTLE_NS = 400_000_000L
    private const val SETTLE_CAP_NS = 1_500_000_000L
    private const val STABLE_SAMPLES = 4

    /** Reconcile a sample; reject stale callbacks even when they carry an old seek. */
    fun sample(
        previous: HighlightSyncState?,
        next: HighlightPlaybackSample,
        timing: HighlightTimingContext,
        calibration: HighlightCalibration = HighlightCalibration(),
    ): HighlightSyncState {
        if (previous != null && next.sampledAtNanos < previous.sample.sampledAtNanos) return previous
        val old = previous?.sample
        if (old != null && (next.clockRevision < old.clockRevision || next.inkGeneration < old.inkGeneration ||
                timing.revision < previous.timing.revision)
        ) return previous

        val samePerformance = old != null && old.item == next.item && old.inkGeneration == next.inkGeneration
        val rearm = !samePerformance || old.clockRevision != next.clockRevision
        val retained = if (samePerformance) frame(previous, next.sampledAtNanos).inkMs else 0L
        if (rearm) {
            return HighlightSyncState(
                next, timing, calibration, next.positionMs, next.positionMs,
                retained, next.sampledAtNanos, 0,
            )
        }

        val elapsedNs = next.sampledAtNanos - old.sampledAtNanos
        // The preceding sample owns speed/transport for this elapsed interval.
        val expectedMs = if (old.advances) elapsedNs / 1_000_000.0 * old.speed else 0.0
        val resuming = !old.advances && next.advances
        val settleStart = if (resuming) next.sampledAtNanos else previous.settleStartNanos
        val settleNs = next.sampledAtNanos - settleStart
        val settling = settleNs < SETTLE_CAP_NS &&
            (settleNs < MIN_SETTLE_NS || previous.stableSamples < STABLE_SAMPLES || resuming)
        val maxStepMs = maxOf(100.0, expectedMs * 3.0)
        val believableMs = maxOf(66.0, expectedMs * 2.0)
        val rawAdvance = next.positionMs - old.positionMs
        val gap = next.positionMs - previous.acceptedMediaMs
        val stable = if (old.advances && rawAdvance >= 0 && rawAdvance <= believableMs && gap >= 0) {
            (previous.stableSamples + 1).coerceAtMost(STABLE_SAMPLES)
        } else 0
        val accepted = when {
            gap < 0 -> previous.acceptedMediaMs
            settling && rawAdvance > maxStepMs -> previous.acceptedMediaMs
            settling && gap > believableMs -> previous.acceptedMediaMs + believableMs.toLong()
            else -> next.positionMs
        }
        return HighlightSyncState(
            next, timing, calibration, accepted,
            // After a backward correction, project the actual raw series until
            // it catches retained ink; projecting the held ceiling adds debt.
            minOf(accepted, next.positionMs), retained,
            settleStart, stable,
        )
    }

    /** Project first, then calibrate. Reordering these operations changes the opening ramp. */
    fun frame(state: HighlightSyncState, frameNanos: Long): HighlightFrame {
        val sample = state.sample
        val elapsedMs = if (sample.advances) {
            (frameNanos - sample.sampledAtNanos).coerceAtLeast(0L) / 1_000_000.0 * sample.speed
        } else 0.0
        val mediaMs = (state.projectionBaseMs + elapsedMs).toLong()
        val inkMs = maxOf(
            state.retainedInkMs,
            state.calibration.inkMs(mediaMs, sample.speed, state.timing.voiceStartMs),
        )
        val prepared = state.timing.timings
        val segments = prepared.segments
        val beforeVoice = inkMs < state.timing.voiceStartMs
        val occurrence = if (beforeVoice) null else prepared.activeOccurrence(inkMs)
        val info = occurrence?.let(prepared::infoAtOccurrence)
        val phase = when {
            segments.isEmpty() -> HighlightPhase.MissingTimings
            sample.isBuffering -> HighlightPhase.Buffering
            !sample.isPlaying -> HighlightPhase.Paused
            beforeVoice || inkMs < segments.first().startMs -> HighlightPhase.WaitingForVoice
            inkMs >= segments.last().endMs -> HighlightPhase.Completed
            info != null && inkMs >= info.endMs -> HighlightPhase.GapHold
            else -> HighlightPhase.Speaking
        }
        val start = if (occurrence == 0) maxOf(info!!.startMs, state.timing.voiceStartMs) else info?.startMs
        val progress = if (info == null || start == null) 0f else {
            val duration = info.holdEndMs - start
            if (duration <= 0L) 1f else ((inkMs - start).toDouble() / duration).toFloat().coerceIn(0f, 1f)
        }
        return HighlightFrame(
            sample.item, sample.inkGeneration, state.timing.revision, mediaMs,
            state.calibration.heardMs(mediaMs, sample.speed), inkMs, phase, occurrence, info, progress,
        )
    }
}
