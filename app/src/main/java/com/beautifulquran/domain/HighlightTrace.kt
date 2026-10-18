package com.beautifulquran.domain

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val highlightTraceJson = Json { encodeDefaults = true }

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
    val syncCheckpoint: HighlightSync.Checkpoint? = null,
)

/** A frame references the exact accepted sample used by the renderer's shared timeline. */
@Serializable
data class HighlightTraceFrame(
    val sampleIndex: Int,
    val atNanos: Long,
    val mediaMs: Long,
    val inkMs: Long,
    val occurrence: Int?,
    val progress: Float,
)

/** Bounded captures keep their complete prefix and explicitly report dropped samples. */
@Serializable
data class HighlightTrace(
    val header: HighlightTraceHeader,
    val timings: List<HighlightTraceTiming>,
    val samples: List<HighlightTraceSample>,
    val droppedSamples: Int,
    val frames: List<HighlightTraceFrame> = emptyList(),
    val droppedFrames: Int = 0,
)

/** Persist schema and actual engine versions explicitly, even when they equal codec defaults. */
fun HighlightTrace.encodeForReplay(): String = highlightTraceJson.encodeToString(this)

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
    private val frames = ArrayList<HighlightTraceFrame>()
    private var droppedFrames = 0

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
            observation.syncCheckpoint,
        )
    }

    /** Skip a frame whose sample is outside this capture; never attach it to a newer poll. */
    fun recordFrame(state: HighlightSyncState, atNanos: Long) {
        val index = samples.indexOfLast { it.atNanos == state.sample.sampledAtNanos &&
            it.clockRevision == state.sample.clockRevision && it.inkGeneration == state.sample.inkGeneration &&
            it.surah == state.sample.item.surah && it.ayah == state.sample.item.ayah &&
            it.reciter == state.sample.item.reciter && it.timingRevision == state.timing.revision }
        if (index < 0) return
        if (frames.size >= 2048) { droppedFrames++; return }
        val frame = HighlightSync.frame(state, atNanos)
        frames += HighlightTraceFrame(index, atNanos, frame.mediaMs, frame.inkMs, frame.occurrence, frame.linearProgress)
    }

    fun snapshot(): HighlightTrace = HighlightTrace(header, tables.values.toList(), samples.toList(), dropped,
        frames.toList(), droppedFrames)
}
