package com.beautifulquran.domain

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Local developer capture provenance. Sample-clock traces do not certify rendered motion. */
@Serializable
data class HighlightTraceHeader(
    val sourceRevision: String,
    val sourceDirty: Boolean,
    val applicationVersion: String,
    val schemaVersion: Int = 1,
    val visibleEngineVersion: Int = HighlightSync.BASELINE_ENGINE_VERSION,
    val candidateEngineVersion: Int = HighlightSync.ENGINE_VERSION,
    val scope: String = "sample-clock",
)

/** Exact timing input, including the voice gate; no provider lookup is needed to replay it. */
@Serializable
data class HighlightTraceTiming(val digest: String, val voiceStartMs: Long, val triples: List<Long>)

/** One ordered input and the observed V1/V2 sample-clock results. */
@Serializable
data class HighlightTraceSample(
    val surah: Int,
    val ayah: Int,
    val reciter: Int,
    val rawMs: Long,
    val atNanos: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val speed: Float,
    val clockRevision: Long,
    val inkGeneration: Long,
    val timingRevision: Long,
    val timingDigest: String,
    val lagWallMs: Long,
    val wordLeadMs: Long,
    val provisionalBaselineArm: Boolean,
    val baselineRearm: Boolean,
    val baselineCheckpoint: HighlightClock.Checkpoint?,
    val baselineCheckpointItem: HighlightItem?,
    val baselineMs: Long,
    val candidateMs: Long?,
    val baselineWord: Int?,
    val baselineOccurrenceStartMs: Long?,
    val candidateOccurrence: Int?,
    val candidateWord: Int?,
    val candidatePhase: String?,
    val candidateProgress: Float?,
)

/** Bounded captures keep their complete prefix and explicitly report dropped samples. */
@Serializable
data class HighlightTrace(
    val header: HighlightTraceHeader,
    val timings: List<HighlightTraceTiming>,
    val samples: List<HighlightTraceSample>,
    val droppedSamples: Int,
)

/**
 * Records only when explicitly called by the developer capture adapter.
 * Storage is bounded; source tables are hashed once per publication/gate.
 * The recorder has no network, file, player, or clock access.
 */
class HighlightTraceRecorder(private val header: HighlightTraceHeader, private val maxSamples: Int = 1024) {
    init { require(maxSamples > 0) }
    private val samples = ArrayList<HighlightTraceSample>()
    private val tables = linkedMapOf<String, HighlightTraceTiming>()
    private var previousTiming: HighlightTimingContext? = null
    private var timingDigest = ""
    private var dropped = 0

    fun record(
        sample: HighlightPlaybackSample,
        timing: HighlightTimingContext,
        calibration: HighlightCalibration,
        observation: HighlightObservation,
    ) {
        if (samples.size >= maxSamples) { dropped++; return }
        if (previousTiming != timing) {
            val triples = timing.timings.segments.flatMap { listOf(it.position.toLong(), it.startMs, it.endMs) }
            val bytes = ("${timing.voiceStartMs}:" + triples.joinToString(",")).toByteArray(Charsets.UTF_8)
            timingDigest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            tables.putIfAbsent(timingDigest, HighlightTraceTiming(timingDigest, timing.voiceStartMs, triples))
            previousTiming = timing
        }
        check(observation.visibleEngineVersion == header.visibleEngineVersion)
        samples += HighlightTraceSample(
            sample.item.surah, sample.item.ayah, sample.item.reciter, sample.positionMs,
            sample.sampledAtNanos, sample.isPlaying, sample.isBuffering, sample.speed,
            sample.clockRevision, sample.inkGeneration, timing.revision, timingDigest,
            calibration.outputLagWallMs, calibration.wordLeadMs, observation.provisionalBaselineArm,
            observation.baselineRearm, observation.baselineCheckpoint, observation.baselineCheckpointItem,
            observation.baselinePositionMs, observation.candidate?.inkMs,
            observation.baselineActive?.position, observation.baselineActive?.startMs,
            observation.candidate?.occurrence, observation.candidate?.active?.position,
            observation.candidate?.phase?.name, observation.candidate?.linearProgress,
        )
    }

    fun snapshot(): HighlightTrace = HighlightTrace(header, tables.values.toList(), samples.toList(), dropped)
}
