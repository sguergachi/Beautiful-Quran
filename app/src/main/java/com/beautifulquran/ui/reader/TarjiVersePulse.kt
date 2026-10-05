package com.beautifulquran.ui.reader

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Segment
import com.beautifulquran.playback.RecitationCache
import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiEarSample
import com.beautifulquran.playback.TarjiEarTrack
import com.beautifulquran.playback.VoiceEnergy
import com.beautifulquran.playback.analysisHopContentMs
import com.beautifulquran.tarjilab.TarjiLabKnobs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The light a verse's words will be given, worked out before it is played:
 * the verse's own audio run through the reader's detector, ear track and word
 * gate ([rememberTarjiGate] does the same, live). The Ink Lab draws each
 * candidate's line under it as a sparkline, so where the pulse falls can be
 * seen ahead and compared with the light when it comes.
 */
internal object TarjiVersePulse {

    /** One word's share of the verse on the media-item clock. */
    class Window(val startMs: Double, val endMs: Double)

    /** A verse's audio as the tap would hand it to the detector. */
    class Decoded(val pcm: FloatArray, val hopSamples: Int, val hopMs: Double)

    /** The frame loop reads the ear this far behind the tap (a sink buffer). */
    private const val EAR_BEHIND_HOPS = 12

    /**
     * The light of each of [windows], one value per analysis hop across it
     * (−1..1; all zero where nothing is admitted). Pure: the same detector,
     * ear pulse, one-event-per-word gate and resonance the reader runs.
     */
    fun lines(
        audio: Decoded,
        knobs: TarjiLabKnobs,
        depth: Float,
        windows: List<Window>,
    ): List<FloatArray> {
        val detector = Tarji()
        detector.hopSamples = audio.hopSamples
        knobs.applyTo(detector)
        val track = TarjiEarTrack()
        val ear = TarjiEarSample()
        val gates = windows.map { TarjiWordGate() }
        val out = windows.map {
            FloatArray(((it.endMs - it.startMs) / audio.hopMs).toInt().coerceAtLeast(2))
        }
        val hops = audio.pcm.size / audio.hopSamples
        for (hop in 0 until hops + EAR_BEHIND_HOPS) {
            if (hop < hops) {
                val offset = hop * audio.hopSamples
                detector.onSamples8k(audio.pcm.copyOfRange(offset, offset + audio.hopSamples))
                track.publish(
                    hop = detector.hopCount - 1,
                    hopRms = detector.lastHopRms,
                    pitchHz = detector.lastFoldedPitchHz,
                    pitchLeadHops = detector.lastPitchLeadHops,
                    rateHz = detector.lastRateHz,
                    usesAmplitude = detector.lastVisualUsesAmplitude,
                    gain = detector.tremoloGain,
                    eventStartHop = if (detector.reverberating) detector.eventStartHop else -1,
                )
            }
            val earHop = hop - EAR_BEHIND_HOPS
            if (earHop < 0) continue
            val mediaMs = (earHop + 1) * audio.hopMs
            val index = windows.indexOfFirst { mediaMs >= it.startMs && mediaMs < it.endMs }
            if (index < 0) continue
            track.read(mediaMs, audio.hopMs, ear)
            val window = windows[index]
            val eventMs = if (ear.eventStartHop < 0) {
                VoiceEnergy.NO_EVENT_MS
            } else {
                ((ear.eventStartHop - 1) * audio.hopMs).toLong()
            }
            val allowed = gates[index].allows(ear.gain, ear.reverberating, eventMs, window.startMs.toLong())
            val light = InkEngine.glintResonance(allowed, ear.tremolo, ear.gain, depth = depth, enabled = true).light
            val line = out[index]
            line[((mediaMs - window.startMs) / audio.hopMs).toInt().coerceIn(0, line.lastIndex)] = light
        }
        return out
    }

    /**
     * Each eligible word's window: from its first segment to the next
     * segment's start — the span the reader holds it Active — or to the end
     * of the audio for the verse's last.
     */
    fun windows(ayah: Ayah, segments: List<Segment>, audioMs: Double): Map<Int, Window> {
        val last = ayah.words.lastOrNull()?.position
        val out = LinkedHashMap<Int, Window>()
        for (word in ayah.words) {
            if (!InkEngine.tarjiEligible(word.arabic, word.position == last)) continue
            val at = segments.indexOfFirst { it.position == word.position }
            if (at < 0) continue
            val start = segments[at].startMs.toDouble()
            val end = segments.getOrNull(at + 1)?.startMs?.toDouble()?.takeIf { it > start } ?: audioMs
            if (end - start >= 40.0) out[word.position] = Window(start, end)
        }
        return out
    }

    // ── On the device ────────────────────────────────────────────────────

    /** Set by the reader: a verse's audio and word timings, or null if it has none loaded. */
    @Volatile
    var source: ((surahId: Int, ayah: Int) -> Pair<String, List<Segment>>?)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = HashMap<String, Job>()
    private val done = HashSet<String>()
    private val failures = HashMap<String, Int>()

    /** For the lab's readout: what the last verse that could not be worked out said. */
    var lastFailure by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var working by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    /** A device has only so many decoders; a screenful of verses takes turns. */
    private val decoders = kotlinx.coroutines.sync.Semaphore(2)

    /**
     * Works out [ayah]'s lines once per audio and detector setting and hands
     * them to the words' traces. Main thread; returns at once — false until
     * the lines are in the traces, so the caller can ask again: the reciter
     * or timings may not be loaded yet, and a fetch can fail and be retried.
     */
    fun ensure(context: Context, ayah: Ayah): Boolean {
        val (url, segments) = source?.invoke(ayah.surahId, ayah.number) ?: return false
        if (segments.isEmpty()) return false
        val tuning = InkEngine.tuning
        val knobs = TarjiLabKnobs.fromTuning(tuning)
        val depth = tuning.glintResonanceDepth
        val key = "$url|$knobs|$depth"
        if (key in done) return true
        if (jobs.containsKey(key) || (failures[key] ?: 0) >= MAX_ATTEMPTS) return false
        working++
        val app = context.applicationContext
        jobs[key] = scope.launch {
            val attempt = runCatching {
                val audio = decoders.withPermit { decode(app, url) }
                    ?: error("no audio track in ${url.substringAfterLast('/')}")
                val audioMs = audio.pcm.size / audio.hopSamples * audio.hopMs
                val windows = windows(ayah, segments, audioMs)
                val lines = lines(audio, knobs, depth, windows.values.toList())
                windows.keys.zip(lines).toMap() to windows
            }
            withContext(Dispatchers.Main) {
                jobs.remove(key)
                working--
                val result = attempt.getOrElse { cause ->
                    failures[key] = (failures[key] ?: 0) + 1
                    lastFailure = "${ayah.surahId}:${ayah.number} ${cause.javaClass.simpleName}: ${cause.message}"
                    return@withContext
                }
                done += key
                val (lines, windows) = result
                for (word in ayah.words) {
                    val line = lines[word.position] ?: continue
                    val window = windows.getValue(word.position)
                    InkEngine.tarjiTrace(word).set(
                        line, window.startMs.toFloat(), (window.endMs - window.startMs).toFloat())
                }
            }
        }
        return false
    }

    private const val MAX_ATTEMPTS = 3

    /** The verse's audio through the playback cache, decoded and decimated as the tap does. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun decode(context: Context, url: String): Decoded? {
        val file = File.createTempFile("tarji", ".audio", context.cacheDir)
        try {
            val data = RecitationCache.playbackDataSourceFactory(
                context,
                DefaultHttpDataSource.Factory().setUserAgent("BeautifulQuran/1.0"),
            ).createDataSource()
            try {
                data.open(DataSpec(Uri.parse(url)))
                file.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = data.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                }
            } finally {
                data.close()
            }
            return decodeFile(file)
        } finally {
            file.delete()
        }
    }

    private fun decodeFile(file: File): Decoded? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decimator = Decimator()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outIndex >= 0) {
                    val buffer = decoder.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset).limit(info.offset + info.size)
                    val pcm = buffer.order(ByteOrder.LITTLE_ENDIAN)
                    decimator.configure(sampleRate)
                    while (pcm.remaining() >= 2 * channels) {
                        decimator.add(pcm.short / 32768f)
                        pcm.position(pcm.position() + 2 * (channels - 1))
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    idle = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (inputDone && ++idle > 300) {
                    break // a decoder that never signals its end: keep what it gave
                }
            }
            return decimator.finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** The tap's own decimation ([VoiceEnergy.onPcm16]): first channel, block mean. */
    internal class Decimator {
        private var step = 0
        private var sourceRate = 0
        private var sum = 0f
        private var n = 0
        private var out = FloatArray(1 shl 16)
        private var size = 0

        fun configure(sampleRate: Int) {
            if (sourceRate != 0) return
            sourceRate = sampleRate
            step = maxOf(1, sampleRate / Tarji.SAMPLE_RATE)
        }

        fun add(sample: Float) {
            sum += sample
            if (++n < step) return
            if (size == out.size) out = out.copyOf(size * 2)
            out[size++] = sum / step
            sum = 0f
            n = 0
        }

        fun finish(): Decoded? {
            if (sourceRate == 0 || size == 0) return null
            val hopSamples = ((sourceRate / step) * (Tarji.HOP_MS / 1000f)).roundToInt().coerceAtLeast(1)
            return Decoded(out.copyOf(size), hopSamples, analysisHopContentMs(sourceRate, step, hopSamples))
        }
    }
}

/**
 * Ink Lab: while candidates are marked, has this verse's pulse lines worked
 * out ahead of the voice, and again when the detector is retuned. Every
 * reader that builds ink motions for a verse calls it.
 */
@androidx.compose.runtime.Composable
internal fun RequestTarjiPulseLines(ayah: Ayah) {
    if (!InkEngine.tarjiMarkCandidates) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val tuning = InkEngine.tuning
    androidx.compose.runtime.LaunchedEffect(ayah, tuning) {
        while (!TarjiVersePulse.ensure(context, ayah)) kotlinx.coroutines.delay(750)
    }
}
